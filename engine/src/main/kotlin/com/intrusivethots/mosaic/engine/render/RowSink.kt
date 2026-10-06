package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.image.PixelImage

fun interface RowSink {
    fun writeRow(y: Int, pixels: IntArray)
}

class MemoryRowSink(val width: Int, val height: Int) : RowSink {
    private val pixels = IntArray(width * height)

    override fun writeRow(y: Int, pixels: IntArray) {
        require(y in 0 until height)
        System.arraycopy(pixels, 0, this.pixels, y * width, width)
    }

    fun toImage(): PixelImage = PixelImage(width, height, pixels)
}

/** Counts rows and retains only the latest one, to prove large renders stay row-sized. */
class DiscardRowSink(val width: Int, val height: Int) : RowSink {
    var rows: Int = 0
        private set
    var maxRetained: Int = 0
        private set

    override fun writeRow(y: Int, pixels: IntArray) {
        rows++
        maxRetained = maxOf(maxRetained, pixels.size)
    }
}
