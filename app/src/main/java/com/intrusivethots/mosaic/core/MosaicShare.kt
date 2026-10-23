package com.intrusivethots.mosaic.core

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class ShareDraft(val file: File, val mime: String)

/** Share-sheet copy of a finished mosaic, at the full render's size when that file exists. */
class MosaicShare(private val context: Context) {
    suspend fun shareResult(fullPath: String?, bitmap: Bitmap?, title: String): Boolean {
        val draft = try {
            withContext(Dispatchers.IO) { prepare(fullPath, bitmap, title) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return false
        return runCatching { shareFile(draft) }.isSuccess
    }

    fun shareContent(uri: Uri, mime: String) {
        startChooser(send(uri, mime, "mosaic"))
    }

    fun prepare(
        fullPath: String?,
        bitmap: Bitmap?,
        title: String,
        day: LocalDate = LocalDate.now()
    ): ShareDraft {
        var owned: Bitmap? = null
        val source = if (fullPath != null && File(fullPath).exists()) {
            BitmapFactory.decodeFile(fullPath)?.also { owned = it }
                ?: error("Could not read the mosaic.")
        } else {
            bitmap ?: error("There is no mosaic to share.")
        }
        try {
            val format = GalleryExport.formatOf(source)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, GalleryExport.fileName(title, day, format))
            file.outputStream().use { GalleryExport.write(source, format, it) }
            return ShareDraft(file, format.mime)
        } finally {
            owned?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun shareFile(draft: ShareDraft) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", draft.file)
        startChooser(send(uri, draft.mime, draft.file.name))
    }

    private fun send(uri: Uri, mime: String, label: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, label, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun startChooser(send: Intent) {
        val chooser = Intent.createChooser(send, "Share mosaic").apply {
            clipData = send.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
