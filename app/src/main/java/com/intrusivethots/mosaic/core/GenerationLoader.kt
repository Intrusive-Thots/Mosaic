package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.net.Uri
import com.intrusivethots.mosaic.cache.BitmapLruCache
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import com.intrusivethots.mosaic.engine.tile.TileSource
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class LoadedLibrary(val tiles: List<TileSource>, val skipped: Int)

internal fun generationFailureMessage(failure: Throwable): String {
    return when (failure) {
        is SecurityException -> "The app no longer has permission to read a photo. Add the pictures again."
        is OutOfMemoryError -> "Not enough memory to build this mosaic. Try fewer photos."
        else -> failure.message?.takeIf { it.isNotBlank() } ?: "Mosaic creation failed."
    }
}

internal fun skippedImageMessage(skipped: Int): String {
    val noun = if (skipped == 1) "image" else "images"
    val verb = if (skipped == 1) "was" else "were"
    return "$skipped $noun could not be read and $verb skipped."
}

/**
 * Reads the library one photo at a time. A photo that cannot be decoded is skipped
 * so one bad file cannot take down Generate.
 */
internal class GenerationLoader(
    private val repository: ProjectRepository,
    private val bitmapCache: BitmapLruCache
) {
    suspend fun load(
        snapshot: MosaicUiState,
        owned: MutableList<Bitmap>,
        onLoad: (String, Float) -> Unit
    ): LoadedLibrary {
        val sources = mutableListOf<TileSource>()
        val collage = snapshot.config.mosaicKind == MosaicKind.COLLAGE
        var skipped = 0
        var loadedPhotos = false
        if (!collage || snapshot.config.collage.includeSourcePhotos) {
            skipped += appendPhotos(snapshot, sources, owned, onLoad)
            loadedPhotos = true
        }
        snapshot.customStamps.forEachIndexed { index, stamp ->
            sources += BitmapTileSource(bitmapIdentity(stamp, "stamp-$index"), stamp)
        }
        if (snapshot.config.extractSubjectsWithAi && snapshot.tileUris.isNotEmpty()) {
            skipped += extractSubjects(snapshot, sources, owned, onLoad)
        }
        if (!loadedPhotos && sources.isEmpty() && snapshot.tileUris.isNotEmpty()) {
            skipped += appendPhotos(snapshot, sources, owned, onLoad)
        }
        return LoadedLibrary(sources, skipped)
    }

    private suspend fun extractSubjects(
        snapshot: MosaicUiState,
        sources: MutableList<TileSource>,
        owned: MutableList<Bitmap>,
        onLoad: (String, Float) -> Unit
    ): Int {
        val originals = sources.size
        var skipped = 0
        snapshot.tileUris.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            onLoad(
                "Extracting subjects ${index + 1} of ${snapshot.tileUris.size}",
                index.toFloat() / snapshot.tileUris.size
            )
            val large = readBitmap(uri, 1024)
            if (large == null) {
                skipped++
                return@forEachIndexed
            }
            val sticker = readyMadeSticker(large, edgeFor(snapshot))
            if (sticker != null) {
                owned += sticker
                sources += BitmapTileSource(bitmapIdentity(sticker, "sticker-$index"), sticker)
                if (!large.isRecycled) large.recycle()
                return@forEachIndexed
            }
            try {
                val subjects = SubjectSegmenterHelper.extractForLibrary(large, originals, snapshot.config.segmentation)
                subjects.forEachIndexed { subjectIndex, subject ->
                    val thumb = scaleToLongEdge(subject, 128)
                    if (thumb !== subject) subject.recycle()
                    owned += thumb
                    sources += BitmapTileSource(bitmapIdentity(thumb, "subject-$index-$subjectIndex"), thumb)
                }
            } catch (failure: Exception) {
                skipped++
            } catch (oom: OutOfMemoryError) {
                skipped++
            } finally {
                if (!large.isRecycled) large.recycle()
            }
        }
        return skipped
    }

    private fun edgeFor(snapshot: MosaicUiState): Int {
        return snapshot.config.descriptorMaxEdge.coerceAtLeast(96).coerceAtMost(128)
    }

    private suspend fun appendPhotos(
        snapshot: MosaicUiState,
        sources: MutableList<TileSource>,
        owned: MutableList<Bitmap>,
        onLoad: (String, Float) -> Unit
    ): Int {
        val edge = edgeFor(snapshot)
        var skipped = 0
        snapshot.tileUris.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            onLoad("Loading images", index.toFloat() / snapshot.tileUris.size.coerceAtLeast(1))
            val bitmap = bitmapCache.get(uri.toString()) ?: readBitmap(uri, edge)?.also {
                bitmapCache.put(uri.toString(), it)
            }
            if (bitmap == null) {
                skipped++
                return@forEachIndexed
            }
            val turns = snapshot.tileQuarterTurns.getOrElse(index) { 0 } and 3
            val oriented = try {
                if (turns == 0) bitmap else rotateBitmap(bitmap, turns).also { owned += it }
            } catch (oom: OutOfMemoryError) {
                skipped++
                return@forEachIndexed
            }
            val token = if (turns == 0) uri.toString() else "${uri}#q$turns"
            sources += BitmapTileSource(identityOf(uri, token, oriented), oriented)
        }
        return skipped
    }

    private fun identityOf(uri: Uri, token: String, bitmap: Bitmap): TileIdentity = TileIdentity(
        uri = token,
        width = bitmap.width,
        height = bitmap.height,
        byteSize = repository.contentSize(uri),
        modifiedTimeMs = repository.contentModified(uri)
    )

    private suspend fun readBitmap(uri: Uri, edge: Int): Bitmap? {
        return try {
            repository.loadBitmapFromUri(uri, edge)
        } catch (oom: OutOfMemoryError) {
            null
        }
    }
}
