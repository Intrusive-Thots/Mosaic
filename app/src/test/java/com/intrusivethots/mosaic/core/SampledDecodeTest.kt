package com.intrusivethots.mosaic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SampledDecodeTest {
    @Test
    fun sampleSizeKeepsTheLongEdgeAtOrAboveTheAsk() {
        assertEquals(1, sampleSize(1000, 800, 1024))
        assertEquals(4, sampleSize(8000, 6000, 1024))
        assertEquals(16, sampleSize(8000, 6000, 320))
        for (edge in listOf(256, 320, 512, 1024, 2048)) {
            for (long in listOf(edge, edge * 3, 12_000)) {
                val sample = sampleSize(long, long / 2, edge)
                assertTrue("long $long edge $edge sample $sample", long / sample >= edge)
            }
        }
    }

    /** Full-size decodes are allowed only where the full mosaic itself is shared or exported. */
    @Test
    fun noOtherScreenDecodesAPhotoAtFullSize() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val allowed = setOf("Bitmaps.kt", "MosaicShare.kt", "GalleryExport.kt")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { file -> Regex("""BitmapFactory\.decode(File|ByteArray|Stream|Resource)""").containsMatchIn(file.readText()) }
            .map { it.name }
            .toList()
        assertTrue("unsampled decodes in $offenders", offenders.isEmpty())
    }
}
