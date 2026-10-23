package com.intrusivethots.mosaic.drive

import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.io.File

internal class DriveImageCache(private val root: File, private val maxBytes: Long = MAX_BYTES) {
    fun existing(id: String, modified: String): File? {
        val file = File(root, fileName(id, modified))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun store(id: String, modified: String, pinned: Set<String>, write: suspend (File) -> Unit): File {
        existing(id, modified)?.let { cached ->
            cached.setLastModified(System.currentTimeMillis())
            return cached
        }
        root.mkdirs()
        val dest = File(root, fileName(id, modified))
        val temp = File(root, dest.name + ".part")
        try {
            write(temp)
            if (!temp.isFile || temp.length() <= 0L) {
                temp.delete()
                throw DriveFailure(DriveProblem.FAILED, "That Drive photo had no data.")
            }
            evict(temp.length(), pinned + dest.name)
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        } catch (cancelled: CancellationException) {
            temp.delete()
            throw cancelled
        } catch (failure: DriveFailure) {
            temp.delete()
            throw failure
        } catch (failure: Exception) {
            temp.delete()
            throw DriveFailure(DriveProblem.FAILED, failure.message ?: "The Drive photo could not be saved.")
        }
        return dest
    }

    fun releaseName(name: String) {
        val file = File(root, name)
        if (file.isFile) file.delete()
    }

    private fun evict(incoming: Long, pinned: Set<String>) {
        val files = root.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
            .sortedBy { it.lastModified() }
        var used = files.sumOf { it.length() } + incoming
        for (file in files) {
            if (used <= maxBytes) return
            if (file.name in pinned) continue
            val size = file.length()
            if (file.delete()) used -= size
        }
        if (used > maxBytes) {
            throw DriveFailure(
                DriveProblem.FAILED,
                "Drive downloads used the room set aside for them. Remove some Drive photos and try again."
            )
        }
    }

    companion object {
        const val MAX_BYTES = 200L * 1024L * 1024L
        private const val DIR = "drive-library"

        fun directory(filesDir: File): File = File(filesDir, DIR)

        fun ownedName(uri: Uri): String? {
            if (uri.authority?.endsWith(".files") != true) return null
            val name = uri.lastPathSegment ?: return null
            return name.takeIf { it.startsWith("drive-") }
        }
    }
}

internal fun fileName(id: String, modified: String): String {
    val safe = id.map { char -> if (char.isLetterOrDigit()) char else '_' }.joinToString("").take(48)
    val stamp = modified.hashCode().toUInt().toString(16)
    return "drive-$safe-$stamp.img"
}

internal fun deleteOwnedDriveFile(filesDir: File, uri: Uri) {
    val name = DriveImageCache.ownedName(uri) ?: return
    DriveImageCache(DriveImageCache.directory(filesDir)).releaseName(name)
}
