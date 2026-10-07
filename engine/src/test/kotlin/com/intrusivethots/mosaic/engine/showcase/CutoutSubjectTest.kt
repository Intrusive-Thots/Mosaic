package com.intrusivethots.mosaic.engine.showcase

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CutoutSubjectTest {
    @Test
    fun darkFrameWithARedCapIsNotACutout() {
        val image = capped(48, 36, argb(20, 18, 16), argb(49, 24, 19))
        assertNull(asCutout(image))
        assertNull(brightPhoto(image))
    }

    @Test
    fun busyDarkScreenshotIsRejected() {
        val pixels = IntArray(40 * 28) { index ->
            val tone = 12 + (index * 17) % 30
            argb(tone, tone, tone + (index % 5))
        }
        val image = PixelImage(40, 28, pixels)
        assertNull(asCutout(image))
        assertNull(brightPhoto(image))
    }

    @Test
    fun figureOnAFlatBackgroundBecomesASubject() {
        val image = figure(48, 48, argb(245, 245, 245), argb(230, 120, 30))
        val cutout = assertNotNull(asCutout(image))
        assertTrue((cutout.pixel(0, 0) ushr 24) < 40)
        val center = cutout.pixel(24, 24)
        assertTrue((center ushr 24) > 200)
        assertTrue(((center ushr 16) and 255) > 180)
    }
}

private fun capped(width: Int, height: Int, body: Int, cap: Int): PixelImage {
    val pixels = IntArray(width * height) { body }
    for (y in 0 until 4) {
        for (x in 0 until width) pixels[y * width + x] = cap
    }
    return PixelImage(width, height, pixels)
}

private fun figure(width: Int, height: Int, ground: Int, ink: Int): PixelImage {
    val pixels = IntArray(width * height) { ground }
    for (y in height / 5 until height * 4 / 5) {
        for (x in width / 4 until width * 3 / 4) pixels[y * width + x] = ink
    }
    return PixelImage(width, height, pixels)
}
