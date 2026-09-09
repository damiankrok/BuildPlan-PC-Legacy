package com.buildplan.app.analyzer.text

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds where a drawing prints text, and measures whether that text is
 * printable at all at the raster's resolution.
 *
 * This is deterministic morphology, not recognition. Small ink components of
 * roughly uniform height that sit on a shared baseline and stand a glyph's
 * width apart are a run of characters — an opening's "180/210", a level's
 * "+7,95", a room number. Finding the run is enough to answer two useful
 * questions without reading a single digit: *does the source label this
 * thing at all*, and *is the published raster big enough for anyone to read
 * the label*.
 *
 * On both benchmark drawings the answer to the second is no — the plans
 * dimension every opening and the section prints every level, at four to six
 * pixels a glyph — so every run comes back
 * [TextLegibility.TOO_SMALL_TO_READ] and every dimension stays MISSING with
 * the question attached. That is the honest result, and it is a stronger one
 * than silence: it says the numbers exist and names what would be needed to
 * read them.
 */
class GlyphRunLocator(
    /** A glyph is at least this tall; below it, ink is a hatch stroke or a dot. */
    private val minGlyphHeightPx: Int = 3,
    /** A glyph is at most this tall; above it, ink is a wall or a symbol. */
    private val maxGlyphHeightPx: Int = 40,
    /** Glyphs of one run sit within this fraction of a glyph height of each other's baseline. */
    private val baselineTolerance: Double = 0.45,
    /** Consecutive glyphs of one run stand at most this many glyph widths apart. */
    private val maxGapInWidths: Double = 1.2,
    /** A run of fewer glyphs than this is a tick mark or a stray symbol, not a label. */
    private val minGlyphsPerRun: Int = 2,
) : DrawingTextExtractor {

    override fun extract(assetUrl: String, image: RasterImage, region: PixelBox?): List<TextObservation> {
        val ink = BinaryMask.of(image) { x, y ->
            val inRegion = region == null || (x >= region.minX && x <= region.maxX && y >= region.minY && y <= region.maxY)
            inRegion && image.luma(x, y) < INK_MAX_LUMA && image.alpha(x, y) >= 128
        }
        val glyphs = glyphComponents(ink)
        val runs = groupIntoRuns(glyphs)
        return runs.map { run ->
            val bounds = PixelBox(run.minOf { it.minX }, run.minOf { it.minY }, run.maxOf { it.maxX }, run.maxOf { it.maxY })
            val height = run.map { it.height }.sorted().let { it[it.size / 2] }
            // The gate lives here and nowhere else: a run this small is never handed to a
            // recogniser, so no threshold further down can decide to read it anyway.
            val legibility = if (height < DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX) {
                TextLegibility.TOO_SMALL_TO_READ
            } else {
                TextLegibility.NO_RECOGNISER
            }
            TextObservation(
                sourceAsset = assetUrl,
                bounds = bounds,
                text = null,
                confidence = 0.0,
                method = "glyph-run morphology: ${run.size} components on one baseline, median glyph height $height px" +
                    when (legibility) {
                        TextLegibility.TOO_SMALL_TO_READ -> "; below the ${DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX} px floor, not read"
                        TextLegibility.NO_RECOGNISER -> "; large enough to read, but no recogniser is wired in"
                        TextLegibility.READ -> ""
                    },
                legibility = legibility,
                glyphCount = run.size,
                glyphHeightPx = height,
            )
        }
    }

    private fun glyphComponents(ink: BinaryMask): List<PixelBox> {
        val components = ink.components()
        val out = mutableListOf<PixelBox>()
        for (label in 1..components.count) {
            val box = components.boundingBox(label) ?: continue
            if (box.height < minGlyphHeightPx || box.height > maxGlyphHeightPx) continue
            // A glyph is taller than it is wide, or nearly square; a dash of a dimension chain
            // and a hatch stroke are many times wider than tall.
            if (box.width > box.height * 2) continue
            // A glyph's ink fills part of its box; a solid blob is a symbol or a filled marker.
            val fill = components.sizes[label].toDouble() / box.area
            if (fill > 0.92) continue
            out += box
        }
        return out
    }

    /**
     * Chains glyph boxes left to right into runs sharing a baseline.
     *
     * Sorting by baseline then by left edge makes the grouping independent of
     * component-labelling order, so the same drawing always yields the same
     * runs in the same order.
     */
    private fun groupIntoRuns(glyphs: List<PixelBox>): List<List<PixelBox>> {
        val sorted = glyphs.sortedWith(compareBy({ it.maxY }, { it.minX }))
        val used = BooleanArray(sorted.size)
        val runs = mutableListOf<List<PixelBox>>()
        for (i in sorted.indices) {
            if (used[i]) continue
            val run = mutableListOf(sorted[i])
            used[i] = true
            var last = sorted[i]
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val next = sorted[j]
                val h = max(last.height, next.height)
                if (abs(next.maxY - last.maxY) > h * baselineTolerance) continue
                if (min(last.height, next.height) * 2 < h) continue
                val gap = next.minX - last.maxX
                if (gap < -h || gap > max(last.width, next.width) * maxGapInWidths + 2) continue
                run += next
                used[j] = true
                last = next
            }
            if (run.size >= minGlyphsPerRun) runs += run.sortedBy { it.minX }
        }
        return runs.sortedWith(compareBy({ it.first().minY }, { it.first().minX }))
    }

    private companion object {
        const val INK_MAX_LUMA = 150
    }
}
