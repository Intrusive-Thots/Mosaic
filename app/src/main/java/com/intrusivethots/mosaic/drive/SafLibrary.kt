package com.intrusivethots.mosaic.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal class OpenPersistableImages : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input).addFlags(readFlags())
    }
}

internal class OpenPersistableTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).addFlags(readFlags())
    }
}

internal class CreatePersistableDocument(mime: String) : ActivityResultContracts.CreateDocument(mime) {
    override fun createIntent(context: Context, input: String): Intent {
        return super.createIntent(context, input).addFlags(readFlags() or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

internal fun keepReadPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (failure: SecurityException) {
        // A one-shot grant still lets us read the photo during this session.
    }
}

internal fun keepWritePermission(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (failure: SecurityException) {
        // The write grant from the picker is enough for this save.
    }
}

internal class FolderRead(val uris: List<Uri>, val truncated: Boolean)

internal fun collectTreeImages(context: Context, tree: Uri, limit: Int = 400): FolderRead {
    val root = DocumentFile.fromTreeUri(context, tree) ?: return FolderRead(emptyList(), false)
    val found = ArrayList<Uri>(limit.coerceAtMost(64))
    val truncated = walkDocuments(root, found, limit, 6)
    return FolderRead(found, truncated)
}

private fun walkDocuments(directory: DocumentFile, found: MutableList<Uri>, limit: Int, depth: Int): Boolean {
    val children = directory.listFiles()
    for (child in children) {
        if (found.size >= limit) return true
        if (child.isDirectory) {
            if (depth > 0 && walkDocuments(child, found, limit, depth - 1)) return true
            continue
        }
        if (!child.isFile || !isLibraryImage(child.type, child.name ?: "")) continue
        found += child.uri
    }
    return found.size >= limit
}

internal fun copyFileToUri(context: Context, file: File, dest: Uri): Boolean {
    return try {
        val stream = context.contentResolver.openOutputStream(dest) ?: return false
        stream.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
        true
    } catch (failure: java.io.IOException) {
        false
    }
}

private fun readFlags(): Int {
    return Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
}
