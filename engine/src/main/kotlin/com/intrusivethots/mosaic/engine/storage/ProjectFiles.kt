package com.intrusivethots.mosaic.engine.storage

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class ReconcileReport(
    val orphansDeleted: List<String>,
    val missing: List<String>
)

fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.tmp")
    try {
        FileOutputStream(temporary).use { stream ->
            write(stream)
            stream.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            error("Could not replace ${target.name}.")
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    } catch (exception: Exception) {
        temporary.delete()
        throw exception
    }
}

/**
 * Deletes image files in [directory] that no project references, and reports referenced paths
 * that are no longer on disk. Temp files left by a crash are always removed.
 */
fun reconcileProjectFiles(directory: File, referencedPaths: Set<String>): ReconcileReport {
    val referenced = referencedPaths.map { File(it).absolutePath }.toSet()
    val orphans = mutableListOf<String>()
    if (directory.isDirectory) {
        directory.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val absolute = file.absolutePath
            val temporary = file.name.endsWith(".tmp")
            val ownedImage = file.extension.lowercase() in OWNED_EXTENSIONS
            if (temporary || (ownedImage && absolute !in referenced)) {
                if (file.delete()) orphans.add(file.name)
            }
        }
    }
    val missing = referencedPaths.filter { path -> !File(path).exists() }
    return ReconcileReport(orphansDeleted = orphans, missing = missing)
}

private val OWNED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
