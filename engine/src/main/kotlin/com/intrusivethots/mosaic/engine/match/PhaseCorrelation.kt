package com.intrusivethots.mosaic.engine.match

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Translation between two same-sized luminance patches, in fractions of the patch.
 * A positive x means the source pattern sits to the right of the reference.
 */
internal fun phaseOffset(reference: FloatArray, source: FloatArray, size: Int = PATCH_EDGE): Pair<Float, Float> {
    val count = size * size
    if (reference.size < count || source.size < count) return 0f to 0f
    val real = FloatArray(count)
    val imag = FloatArray(count)
    val otherReal = FloatArray(count)
    val otherImag = FloatArray(count)
    copyCentered(reference, real, size)
    copyCentered(source, otherReal, size)
    fft2(real, imag, size, inverse = false)
    fft2(otherReal, otherImag, size, inverse = false)
    crossPower(real, imag, otherReal, otherImag)
    fft2(real, imag, size, inverse = true)
    return peakShift(real, size)
}

private fun copyCentered(source: FloatArray, into: FloatArray, size: Int) {
    var mean = 0f
    val count = size * size
    for (index in 0 until count) mean += source[index]
    mean /= count.toFloat()
    for (index in 0 until count) into[index] = source[index] - mean
}

private fun crossPower(real: FloatArray, imag: FloatArray, otherReal: FloatArray, otherImag: FloatArray) {
    for (index in real.indices) {
        val re = real[index] * otherReal[index] + imag[index] * otherImag[index]
        val im = imag[index] * otherReal[index] - real[index] * otherImag[index]
        val magnitude = sqrt(re * re + im * im).coerceAtLeast(1e-6f)
        real[index] = re / magnitude
        imag[index] = im / magnitude
    }
}

private fun peakShift(real: FloatArray, size: Int): Pair<Float, Float> {
    var best = 0
    var peak = real[0]
    for (index in 1 until real.size) {
        if (real[index] <= peak) continue
        peak = real[index]
        best = index
    }
    var dx = best % size
    var dy = best / size
    if (dx > size / 2) dx -= size
    if (dy > size / 2) dy -= size
    return -dx.toFloat() / size.toFloat() to -dy.toFloat() / size.toFloat()
}

private fun fft2(real: FloatArray, imag: FloatArray, size: Int, inverse: Boolean) {
    for (y in 0 until size) fft1d(real, imag, y * size, 1, size, inverse)
    for (x in 0 until size) fft1d(real, imag, x, size, size, inverse)
    if (!inverse) return
    val scale = 1f / (size * size).toFloat()
    for (index in real.indices) {
        real[index] *= scale
        imag[index] *= scale
    }
}

private fun fft1d(real: FloatArray, imag: FloatArray, start: Int, stride: Int, count: Int, inverse: Boolean) {
    bitReverse(real, imag, start, stride, count)
    var length = 2
    while (length <= count) {
        val angle = (if (inverse) 2.0 else -2.0) * PI / length.toDouble()
        val turnReal = cos(angle).toFloat()
        val turnImag = sin(angle).toFloat()
        butterfly(real, imag, start, stride, count, length, turnReal, turnImag)
        length = length shl 1
    }
}

private fun bitReverse(real: FloatArray, imag: FloatArray, start: Int, stride: Int, count: Int) {
    var reversed = 0
    for (index in 1 until count) {
        var bit = count shr 1
        while (reversed and bit != 0) {
            reversed = reversed xor bit
            bit = bit shr 1
        }
        reversed = reversed xor bit
        if (index < reversed) swap(real, imag, start, stride, index, reversed)
    }
}

private fun swap(real: FloatArray, imag: FloatArray, start: Int, stride: Int, left: Int, right: Int) {
    val a = start + left * stride
    val b = start + right * stride
    val realValue = real[a]
    val imagValue = imag[a]
    real[a] = real[b]
    imag[a] = imag[b]
    real[b] = realValue
    imag[b] = imagValue
}

private fun butterfly(
    real: FloatArray,
    imag: FloatArray,
    start: Int,
    stride: Int,
    count: Int,
    length: Int,
    turnReal: Float,
    turnImag: Float
) {
    val half = length / 2
    var origin = 0
    while (origin < count) {
        var spinReal = 1f
        var spinImag = 0f
        for (step in 0 until half) {
            val even = start + (origin + step) * stride
            val odd = start + (origin + step + half) * stride
            val mixedReal = real[odd] * spinReal - imag[odd] * spinImag
            val mixedImag = real[odd] * spinImag + imag[odd] * spinReal
            real[odd] = real[even] - mixedReal
            imag[odd] = imag[even] - mixedImag
            real[even] += mixedReal
            imag[even] += mixedImag
            val nextReal = spinReal * turnReal - spinImag * turnImag
            spinImag = spinReal * turnImag + spinImag * turnReal
            spinReal = nextReal
        }
        origin += length
    }
}

internal const val PATCH_EDGE = 16
