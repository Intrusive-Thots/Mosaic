package com.intrusivethots.mosaic.engine.render

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Deflater.DEFAULT_COMPRESSION

/**
 * Writes an RGBA PNG one scanline at a time. Compressed bytes are flushed in IDAT chunks so a
 * large mosaic never has to exist as one contiguous bitmap.
 */
class StreamingPngWriter(
    private val output: OutputStream,
    val width: Int,
    val height: Int
) : RowSink, AutoCloseable {
    private val deflater = Deflater(DEFAULT_COMPRESSION)
    private val deflateBuffer = ByteArray(8192)
    private val pending = java.io.ByteArrayOutputStream(CHUNK_BYTES)
    private val rawRow = ByteArray(1 + width * 4)
    private var rowsWritten = 0
    private var closed = false

    init {
        require(width > 0 && height > 0)
        output.write(SIGNATURE)
        writeChunk(IHDR, header())
    }

    override fun writeRow(y: Int, pixels: IntArray) {
        check(!closed) { "PNG writer is closed." }
        require(y == rowsWritten) { "Rows must be written in order." }
        require(pixels.size >= width)
        rawRow[0] = 0
        var offset = 1
        for (x in 0 until width) {
            val pixel = pixels[x]
            rawRow[offset++] = (pixel shr 16).toByte()
            rawRow[offset++] = (pixel shr 8).toByte()
            rawRow[offset++] = pixel.toByte()
            rawRow[offset++] = (pixel ushr 24).toByte()
        }
        deflater.setInput(rawRow, 0, rawRow.size)
        drain(finish = false)
        rowsWritten++
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            check(rowsWritten == height) { "Expected $height rows, wrote $rowsWritten." }
            deflater.finish()
            drain(finish = true)
            flushPending(force = true)
            writeChunk(IEND, ByteArray(0))
            output.flush()
        } finally {
            deflater.end()
        }
    }

    private fun drain(finish: Boolean) {
        while (true) {
            val count = deflater.deflate(deflateBuffer, 0, deflateBuffer.size, Deflater.NO_FLUSH)
            if (count > 0) {
                pending.write(deflateBuffer, 0, count)
                if (pending.size() >= CHUNK_BYTES) flushPending(force = false)
            }
            if (finish) {
                if (deflater.finished()) break
                if (count == 0) {
                    val extra = deflater.deflate(deflateBuffer, 0, deflateBuffer.size, Deflater.SYNC_FLUSH)
                    if (extra > 0) pending.write(deflateBuffer, 0, extra)
                    if (deflater.finished()) break
                }
            } else if (deflater.needsInput() || count == 0) {
                break
            }
        }
    }

    private fun flushPending(force: Boolean) {
        if (pending.size() == 0) return
        if (!force && pending.size() < CHUNK_BYTES) return
        writeChunk(IDAT, pending.toByteArray())
        pending.reset()
    }

    private fun header(): ByteArray {
        val data = ByteArray(13)
        writeInt(data, 0, width)
        writeInt(data, 4, height)
        data[8] = 8
        data[9] = 6
        return data
    }

    private fun writeChunk(type: ByteArray, data: ByteArray) {
        val length = ByteArray(4)
        writeInt(length, 0, data.size)
        output.write(length)
        output.write(type)
        if (data.isNotEmpty()) output.write(data)
        val crc = CRC32()
        crc.update(type)
        if (data.isNotEmpty()) crc.update(data)
        val crcBytes = ByteArray(4)
        writeInt(crcBytes, 0, crc.value.toInt())
        output.write(crcBytes)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    companion object {
        private val SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private val IHDR = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        private val IDAT = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
        private val IEND = byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
        private const val CHUNK_BYTES = 16 * 1024
    }
}
