package com.intrusivethots.mosaic.drive

import android.graphics.Bitmap
import com.intrusivethots.mosaic.core.writeBitmapPng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class PreparedExport(val file: File, val temporary: Boolean)

internal suspend fun prepareExport(cacheDir: File, title: String, path: String?, bitmap: Bitmap?): PreparedExport? {
    if (path != null) {
        val existing = File(path)
        if (existing.isFile && existing.length() > 0L) return PreparedExport(existing, false)
    }
    if (bitmap == null || bitmap.isRecycled) return null
    val dest = File(cacheDir, "drive-export/${mosaicExportName(title)}")
    withContext(Dispatchers.IO) { writeBitmapPng(bitmap, dest) }
    return PreparedExport(dest, true)
}
