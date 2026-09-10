package com.buildplan.app.analyzer.visual

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.RasterImage

/**
 * What a pixel of a marketing picture is, decided by colour alone.
 *
 * Elevations and renders of catalogue houses are drawn against a sky and a
 * garden: the building is white, grey, dark or clad in warm timber, and
 * everything that is not the building is blue or green. That is the whole
 * trick, and it is stated as one so its limits are visible — a white cloud
 * is building-coloured, a green door is garden-coloured — and handled where
 * they bite, in the reader, rather than hidden behind a learned model.
 */
enum class PixelClass {
    SKY, VEGETATION, WHITE, GREY, DARK, WOOD, GLASS, OTHER;

    companion object {
        /** By ordinal, allocated once: [PictureClasses] stores bytes and reads them back through this. */
        val BY_ORDINAL: Array<PixelClass> = entries.toTypedArray()
    }
}

/**
 * One picture's pixels, classified.
 *
 * Stored as a [ByteArray] rather than an `Array<PixelClass>` on purpose. An
 * object array of half a million references costs two megabytes on a 32-bit
 * heap and has to be walked by the collector; the bytes cost half a megabyte
 * and are ignored by it. On a two-gigabyte device that difference, over the
 * six pictures a project page carries, is the difference between an analysis
 * that finishes and one the system kills.
 */
class PictureClasses private constructor(val width: Int, val height: Int, private val classes: ByteArray) {

    operator fun get(x: Int, y: Int): PixelClass =
        if (x in 0 until width && y in 0 until height) PixelClass.BY_ORDINAL[classes[y * width + x].toInt()] else PixelClass.OTHER

    fun mask(vararg of: PixelClass): BinaryMask {
        val wanted = BooleanArray(PixelClass.BY_ORDINAL.size)
        of.forEach { wanted[it.ordinal] = true }
        val m = BinaryMask(width, height)
        for (y in 0 until height) for (x in 0 until width) if (wanted[classes[y * width + x].toInt()]) m[x, y] = true
        return m
    }

    fun count(x0: Int, y0: Int, x1: Int, y1: Int, of: PixelClass): Int {
        val target = of.ordinal.toByte()
        var n = 0
        for (y in maxOf(0, y0) until minOf(height, y1)) for (x in maxOf(0, x0) until minOf(width, x1)) {
            if (classes[y * width + x] == target) n++
        }
        return n
    }

    companion object {

        fun of(image: RasterImage): PictureClasses {
            val out = ByteArray(image.pixelCount)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                out[y * image.width + x] = classify(image, x, y).ordinal.toByte()
            }
            return PictureClasses(image.width, image.height, out)
        }

        /**
         * The colour rules, in the order they are tried.
         *
         * Sky and vegetation first, because they are what the building is
         * *not*; then glass, which is a darkened sky reflected inside the
         * building; then timber by its warmth; then the greys by luma.
         */
        fun classify(image: RasterImage, x: Int, y: Int): PixelClass {
            val r = image.red(x, y)
            val g = image.green(x, y)
            val b = image.blue(x, y)
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            val chroma = maxOf(r, g, b) - minOf(r, g, b)
            return when {
                // Sky: blue leads, bright. A reflected sky in a pane is darker and comes below.
                b > r + 12 && b >= g && luma >= 150 -> PixelClass.SKY
                // Vegetation: green leads.
                g > r + 12 && g > b + 8 -> PixelClass.VEGETATION
                // Glass: clearly bluish and darker than sky. A dark roof tile has a faint blue cast
                // too, and the margin here is what keeps it fabric rather than glass.
                b > r + 16 && b >= g - 4 && luma < 150 -> PixelClass.GLASS
                // Timber: warm and saturated — red leads green leads blue.
                r > g + 18 && g > b + 8 && r > 100 && chroma > 40 -> PixelClass.WOOD
                luma >= 190 && chroma < 40 -> PixelClass.WHITE
                luma < 85 && chroma < 60 -> PixelClass.DARK
                chroma < 50 -> PixelClass.GREY
                else -> PixelClass.OTHER
            }
        }
    }
}
