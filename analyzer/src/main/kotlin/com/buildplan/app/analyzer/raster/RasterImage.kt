package com.buildplan.app.analyzer.raster

/**
 * A decoded image as the analyzer sees it: width, height and one packed ARGB
 * `Int` per pixel, row-major. No platform type — `android.graphics.Bitmap`
 * on the device, `java.awt.image.BufferedImage` on the JVM — reaches past the
 * codec that produced this, so every pixel algorithm runs and is tested on
 * a plain JVM.
 */
class RasterImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "RasterImage needs positive dimensions, got ${width}x$height" }
        require(argb.size == width * height) { "RasterImage pixel count ${argb.size} != ${width}x$height" }
    }

    val pixelCount: Int get() = width * height

    fun pixel(x: Int, y: Int): Int = argb[y * width + x]

    fun red(x: Int, y: Int): Int = (pixel(x, y) shr 16) and 0xFF
    fun green(x: Int, y: Int): Int = (pixel(x, y) shr 8) and 0xFF
    fun blue(x: Int, y: Int): Int = pixel(x, y) and 0xFF
    fun alpha(x: Int, y: Int): Int = (pixel(x, y) ushr 24) and 0xFF

    /** Rec. 601 luma of one pixel, 0..255; transparent pixels count as white. */
    fun luma(x: Int, y: Int): Int {
        val p = pixel(x, y)
        if ((p ushr 24) and 0xFF < 128) return 255
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** The largest channel minus the smallest, 0..255: a cheap saturation. */
    fun chroma(x: Int, y: Int): Int {
        val r = red(x, y)
        val g = green(x, y)
        val b = blue(x, y)
        return maxOf(r, g, b) - minOf(r, g, b)
    }
}

/**
 * Turns encoded bytes into a [RasterImage], or null when the bytes are not
 * an image this codec reads. Implemented by the platform: `BitmapFactory`
 * in the debug Lab, `ImageIO` in JVM tests.
 */
interface RasterCodec {
    fun decode(bytes: ByteArray): RasterImage?
}
