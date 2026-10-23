package com.intrusivethots.mosaic.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

internal interface DriveTokens {
    suspend fun token(scope: String): String

    suspend fun invalidate(scope: String)
}

internal class DriveSession(private val client: DriveClient, private val tokens: DriveTokens) {
    suspend fun <T> use(scope: String, block: suspend (String) -> T): T {
        try {
            return block(tokens.token(scope))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DriveFailure) {
            if (failure.problem != DriveProblem.UNAUTHORIZED) throw failure
        }
        tokens.invalidate(scope)
        return try {
            block(tokens.token(scope))
        } catch (failure: DriveFailure) {
            if (failure.problem == DriveProblem.UNAUTHORIZED) {
                throw DriveFailure(DriveProblem.REVOKED, "Google Drive access expired or was revoked. Connect again.")
            }
            throw failure
        }
    }

    suspend fun list(folderId: String, pageToken: String?): DrivePage {
        return use(DRIVE_READ_SCOPE) { token -> client.list(token, folderId, pageToken) }
    }

    suspend fun about(): DriveAccount = use(DRIVE_READ_SCOPE) { token -> client.about(token) }

    suspend fun thumbnail(link: String): ByteArray? {
        return use(DRIVE_READ_SCOPE) { token -> client.thumbnail(token, link) }
    }
}

internal class DriveLibrary(
    private val session: DriveSession,
    private val client: DriveClient,
    private val cache: DriveImageCache
) {
    suspend fun download(entries: List<DriveEntry>, pinned: Set<String>, onProgress: (Int, String) -> Unit): List<File> {
        val images = entries.filter { it.image }
        val saved = ArrayList<File>(images.size)
        images.forEachIndexed { index, entry ->
            coroutineContext.ensureActive()
            onProgress(index, entry.name)
            saved += fetch(entry, pinned + saved.map { it.name })
        }
        onProgress(images.size, "")
        return saved
    }

    suspend fun folderImages(
        folderId: String,
        pinned: Set<String>,
        depth: Int = 4,
        limit: Int = 400,
        onProgress: (Int, String) -> Unit
    ): FolderImport {
        val saved = ArrayList<File>()
        val truncated = walk(folderId, pinned, depth, limit, saved, onProgress)
        return FolderImport(saved, truncated)
    }

    suspend fun list(folderId: String, pageToken: String?): DrivePage = session.list(folderId, pageToken)

    suspend fun about(): DriveAccount = session.about()

    suspend fun thumbnail(link: String): ByteArray? = session.thumbnail(link)

    suspend fun exportTo(parentId: String, name: String, file: File): DriveUpload {
        return try {
            DriveUpload(upload(parentId, name, file), false)
        } catch (failure: DriveFailure) {
            if (failure.problem != DriveProblem.FAILED) throw failure
            val folder = mosaicFolder()
            if (folder.id == parentId) throw failure
            DriveUpload(upload(folder.id, name, file), true)
        }
    }

    suspend fun exportToAppFolder(name: String, file: File): DriveEntry {
        val folder = mosaicFolder()
        return upload(folder.id, name, file)
    }

    private suspend fun walk(
        folderId: String,
        pinned: Set<String>,
        depth: Int,
        limit: Int,
        saved: MutableList<File>,
        onProgress: (Int, String) -> Unit
    ): Boolean {
        var page: String? = null
        var truncated = false
        do {
            coroutineContext.ensureActive()
            val listed = session.list(folderId, page)
            val nested = ArrayList<DriveEntry>()
            for (entry in listed.entries) {
                coroutineContext.ensureActive()
                if (entry.folder) {
                    if (depth > 0) nested += entry
                    continue
                }
                if (!entry.image) continue
                if (saved.size >= limit) return true
                onProgress(saved.size, entry.name)
                saved += fetch(entry, pinned + saved.map { it.name })
            }
            for (folder in nested) {
                if (saved.size >= limit) return true
                truncated = walk(folder.id, pinned, depth - 1, limit, saved, onProgress) || truncated
            }
            page = listed.nextPageToken
        } while (!page.isNullOrBlank())
        return truncated
    }

    private suspend fun fetch(entry: DriveEntry, pinned: Set<String>): File {
        cache.existing(entry.id, entry.modifiedTime)?.let { return it }
        return cache.store(entry.id, entry.modifiedTime, pinned) { dest ->
            session.use(DRIVE_READ_SCOPE) { token ->
                client.download(token, entry.id, dest) { }
            }
        }
    }

    private suspend fun upload(parentId: String, name: String, file: File): DriveEntry {
        return session.use(DRIVE_FILE_SCOPE) { token ->
            client.upload(token, parentId, name, "image/png", file)
        }
    }

    private suspend fun mosaicFolder(): DriveEntry {
        return session.use(DRIVE_FILE_SCOPE) { token ->
            client.findFolder(token, APP_FOLDER) ?: client.createFolder(token, APP_FOLDER)
        }
    }
}

internal class DriveUpload(val entry: DriveEntry, val usedAppFolder: Boolean)

internal class FolderImport(val files: List<File>, val truncated: Boolean)

private const val APP_FOLDER = "Mosaic"
