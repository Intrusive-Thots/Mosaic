package com.intrusivethots.mosaic.engine.color

import kotlin.math.pow

/**
 * sRGB ↔ OKLab conversion (Björn Ottosson).
 * OKLab is the space used for matching because Euclidean distance tracks perceived color
 * more closely than the previous weighted RGB formula.
 */
object OkLab {
    data class Lab(val l: Float, val a: Float, val b: Float)

    private val srgbToLinear = FloatArray(256) { channel ->
        val s = channel / 255f
        if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    fun fromSrgb(red: Int, green: Int, blue: Int): Lab {
        val r = srgbToLinear[red and 255]
        val g = srgbToLinear[green and 255]
        val b = srgbToLinear[blue and 255]
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)
        return Lab(
            l = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot,
            a = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot,
            b = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot
        )
    }

    fun fromArgb(argb: Int): Lab = fromSrgb(
        red = (argb shr 16) and 255,
        green = (argb shr 8) and 255,
        blue = argb and 255
    )

    /** Writes L, a, b at [offset] without allocating a [Lab]. */
    fun writeLab(argb: Int, dest: FloatArray, offset: Int) {
        val red = srgbToLinear[(argb shr 16) and 255]
        val green = srgbToLinear[(argb shr 8) and 255]
        val blue = srgbToLinear[argb and 255]
        val l = 0.4122214708f * red + 0.5363325363f * green + 0.0514459929f * blue
        val m = 0.2119034982f * red + 0.6806995451f * green + 0.1073969566f * blue
        val s = 0.0883024619f * red + 0.2817188376f * green + 0.6299787005f * blue
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)
        dest[offset] = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot
        dest[offset + 1] = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot
        dest[offset + 2] = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot
    }

    fun toArgb(lab: Lab, alpha: Int = 255): Int = toArgb(lab.l, lab.a, lab.b, alpha)

    fun toArgb(l: Float, a: Float, b: Float, alpha: Int = 255): Int {
        val lRoot = l + 0.3963377774f * a + 0.2158037573f * b
        val mRoot = l - 0.1055613458f * a - 0.0638541728f * b
        val sRoot = l - 0.0894841775f * a - 1.2914855480f * b
        val l = lRoot * lRoot * lRoot
        val m = mRoot * mRoot * mRoot
        val s = sRoot * sRoot * sRoot
        val red = linearToSrgb(+4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s)
        val green = linearToSrgb(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s)
        val blue = linearToSrgb(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s)
        return packArgb(alpha, red, green, blue)
    }

    fun distance(first: Lab, second: Lab): Float {
        val dl = first.l - second.l
        val da = first.a - second.a
        val db = first.b - second.b
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }

    private fun cbrt(value: Float): Float = Math.cbrt(value.toDouble()).toFloat()

    private fun linearToSrgb(linear: Float): Int {
        val clamped = linear.coerceIn(0f, 1f)
        val encoded = if (clamped <= 0.0031308f) {
            clamped * 12.92f
        } else {
            1.055f * clamped.pow(1f / 2.4f) - 0.055f
        }
        return (encoded * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}

fun packArgb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue

fun argb(red: Int, green: Int, blue: Int, alpha: Int = 255): Int =
    packArgb(alpha, red, green, blue)
