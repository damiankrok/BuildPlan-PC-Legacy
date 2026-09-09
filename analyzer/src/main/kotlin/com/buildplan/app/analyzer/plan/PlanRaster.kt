package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.RasterImage

/**
 * The pixel classes a plan raster is split into before any geometry is read.
 *
 * Nothing here knows what a wall is. It knows that architects' plan renders
 * print structure in solid near-black, annotation in colour, furniture in
 * thin grey and — on an attic plan — the roof outline as a mid-tone band,
 * and it separates those by colour and thickness alone.
 */
class PlanRaster(val image: RasterImage) {

    /**
     * Neutral dark pixels: walls, thin black lines, black text — including
     * walls under a semi-transparent watermark, which a plan render dims to
     * mid grey rather than removing. Coloured pixels (labels, plants, a roof
     * band) are not ink whatever their darkness.
     */
    val ink: BinaryMask = BinaryMask.of(image) { x, y ->
        image.luma(x, y) < INK_MAX_LUMA && saturation(x, y) <= INK_MAX_SATURATION && image.alpha(x, y) >= 128
    }

    /**
     * Dark, moderately saturated pixels that are not ink: the roof band of an
     * attic plan, shaded fills. Told apart from dimmed walls by saturation
     * rather than by chroma, because a tinted watermark over black gives a
     * pixel whose chroma grows with its brightness while its saturation stays
     * low, whereas a coloured fill is saturated at any brightness.
     */
    val midTone: BinaryMask = BinaryMask.of(image) { x, y ->
        val l = image.luma(x, y)
        val s = saturation(x, y)
        l in MIDTONE_LUMA && s > INK_MAX_SATURATION && s <= MIDTONE_MAX_SATURATION && !ink[x, y]
    }

    /** HSV-style saturation: chroma over the brightest channel, 0..1. */
    private fun saturation(x: Int, y: Int): Double {
        val max = maxOf(image.red(x, y), image.green(x, y), image.blue(x, y))
        return if (max == 0) 0.0 else image.chroma(x, y).toDouble() / max
    }

    /**
     * Ink at least [minThicknessPx] thick in both directions: structure. An
     * opening with a square window of that size removes anti-aliased lines,
     * text strokes and hatching; components under [minStructurePixels]
     * (symbols, bold digits) are dropped afterwards.
     */
    fun structure(minThicknessPx: Int, minStructurePixels: Int): BinaryMask =
        ink.openWindow(minThicknessPx, minThicknessPx).withoutSmallComponents(minStructurePixels)

    /** Ink at least [thicknessPx] thick: the exterior walls and any thick oblique wall, without partitions. */
    fun thick(thicknessPx: Int, minPixels: Int): BinaryMask =
        ink.openWindow(thicknessPx, thicknessPx).withoutSmallComponents(minPixels)

    /** Ink that is not structure: furniture, dimension lines, text, symbols. */
    fun thinInk(structure: BinaryMask): BinaryMask = ink.andNot(structure.dilate(1, 1))

    /**
     * The roof band of an attic plan: mid-tone fill thick enough to be a drawn
     * area, in components large enough to frame a building — never a shaded
     * symbol or a coloured fill inside a room.
     */
    fun roofBand(minThicknessPx: Int, minExtentPx: Int): BinaryMask {
        val opened = midTone.openWindow(minThicknessPx, minThicknessPx)
        val components = opened.components()
        val keep = (1..components.count).filter { label ->
            val box = components.boundingBox(label) ?: return@filter false
            // A band segment along one facade is long in one direction only; a corner piece in both.
            box.width >= minExtentPx || box.height >= minExtentPx
        }.toSet()
        val out = BinaryMask(image.width, image.height)
        for (i in out.bits.indices) if (components.labels[i] in keep) out.bits[i] = true
        return out
    }

    companion object {
        const val INK_MAX_LUMA = 150
        const val INK_MAX_SATURATION = 0.25
        val MIDTONE_LUMA = 35..150
        const val MIDTONE_MAX_SATURATION = 0.70
    }
}

/** Receives intermediate masks for evidence; the Lab ignores them, evaluation tests write PNGs. */
fun interface PlanDebugSink {
    fun emit(name: String, mask: BinaryMask)

    companion object {
        val NONE = PlanDebugSink { _, _ -> }
    }
}
