package com.intrusivethots.mosaic.core

import com.intrusivethots.mosaic.engine.match.CollageSession
import com.intrusivethots.mosaic.engine.match.readCollageSession
import com.intrusivethots.mosaic.engine.match.writeCollageSession
import java.io.File

/** Atomic file for the collage plan and its undo stack. */
class CollageSessionStore(private val file: File) {
    fun write(session: CollageSession) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.outputStream().use { output -> writeCollageSession(session, output) }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    fun read(): CollageSession? {
        if (!file.isFile) return null
        return file.inputStream().use { input -> readCollageSession(input) }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}
