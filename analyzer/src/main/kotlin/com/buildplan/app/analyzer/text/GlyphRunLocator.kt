package com.buildplan.app.analyzer.text

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds where a drawing prints text, measures whether the raster can carry it,
 * and — when a recogniser is supplied and the glyphs are big enough — reads it.
 *
 * Locating is deterministic morphology, not recognition. Small ink components
 * of roughly uniform height that sit on a shared baseline and stand a glyph's
 * width apart are a run of characters — an opening's "180/210", a dimension
 * chain's "1205", a room number.
 *
 * Two things the raster forces, both learned by measuring these drawings
 * rather than by assuming a convention:
 *
 * - **Text is printed in colour as often as in black.** The dimension chains
 *   are a mid-tone blue-grey, so the ink test here is luma alone; a saturation
 *   test of the kind the wall classifier needs would discard every dimension
 *   on the sheet.
 * - **Half the chains run vertically.** A digit rotated a quarter turn is
 *   wider than it is tall, so the glyph filter that keeps hatching out throws
 *   every one of them away. The sheet is therefore scanned twice, once
 *   through a quarter-turn, and the second pass's boxes are mapped back — one
 *   code path, two orientations, no orientation-specific rules.
 */
class GlyphRunLocator(
    private val recogniser: GlyphRecogniser? = null,
    /** A glyph is at least this tall; below it, ink is a hatch stroke or a dot. */
    private val minGlyphHeightPx: Int = 3,
    /** A glyph is at most this tall; above it, ink is a wall or a symbol. */
    private val maxGlyphHeightPx: Int = 40,
    /** Glyphs of one run sit within this fraction of a glyph height of each other's baseline. */
    private val baselineTolerance: Double = 0.35,
    /** Consecutive glyphs of one run stand at most this many glyph *heights* apart. */
    private val maxGapInHeights: Double = 0.55,
    /** A run of fewer glyphs than this is a tick mark or a stray symbol, not a label. */
    private val minGlyphsPerRun: Int = 2,
) : DrawingTextExtractor {

    override fun extract(assetUrl: String, image: RasterImage, region: PixelBox?): List<TextObservation> =
        scan(assetUrl, image, region, TextOrientation.HORIZONTAL) +
            scan(assetUrl, image, region, TextOrientation.VERTICAL)

    /**
     * The glyph boxes and the ink they were found in, per run, in the frame
     * the scan used. For the developer probe only: it is what lets a template
     * be checked against real strokes instead of against an assumption.
     */
    fun debugRuns(image: RasterImage, orientation: TextOrientation): Pair<BinaryMask, List<List<PixelBox>>> {
        val source = if (orientation == TextOrientation.HORIZONTAL) image else rotateQuarterTurn(image)
        val ink = glyphInk(BinaryMask.of(source) { x, y -> source.luma(x, y) < INK_MAX_LUMA && source.alpha(x, y) >= 128 })
        return ink to groupIntoRuns(glyphComponents(ink))
    }

    private fun scan(assetUrl: String, image: RasterImage, region: PixelBox?, orientation: TextOrientation): List<TextObservation> {
        val source = if (orientation == TextOrientation.HORIZONTAL) image else rotateQuarterTurn(image)
        val searchRegion = if (orientation == TextOrientation.HORIZONTAL) region else region?.let { rotateBox(it, image.height) }
        val ink = glyphInk(BinaryMask.of(source) { x, y ->
            val inRegion = searchRegion == null || (x >= searchRegion.minX && x <= searchRegion.maxX && y >= searchRegion.minY && y <= searchRegion.maxY)
            inRegion && source.luma(x, y) < INK_MAX_LUMA && source.alpha(x, y) >= 128
        })
        val glyphs = glyphComponents(ink)
        return groupIntoRuns(glyphs).map { run -> observe(assetUrl, ink, run, orientation, image.height) }
    }

    private fun observe(assetUrl: String, ink: BinaryMask, run: List<PixelBox>, orientation: TextOrientation, imageHeight: Int): TextObservation {
        val bounds = PixelBox(run.minOf { it.minX }, run.minOf { it.minY }, run.maxOf { it.maxX }, run.maxOf { it.maxY })
        val height = run.map { it.height }.sorted().let { it[it.size / 2] }
        val tooSmall = height < DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX

        var text: String? = null
        var confidence = 0.0
        var legibility = if (tooSmall) TextLegibility.TOO_SMALL_TO_READ else TextLegibility.NO_RECOGNISER
        var detail = ""
        val separator = if (tooSmall) null else markAtBaselineBetween(ink, run)
        if (!tooSmall && separator != null) {
            // See [markAtBaselineBetween]: the digits are legible but the number is not.
            legibility = TextLegibility.AMBIGUOUS
            detail = "; ink sits on the baseline between glyph ${separator.first} and ${separator.second}, " +
                "where a decimal comma or a thousands mark is printed; the digits alone would read as a whole " +
                "number a hundred times too large, so the run is not read"
        } else if (!tooSmall && recogniser != null) {
            // One slant for the whole run: the font's, not each digit's own diagonal.
            val shear = recogniser.estimateShear(ink, run)
            val readings = run.map { recogniser.recognise(ink, it, shear) }
            if (readings.all { it != null }) {
                text = readings.joinToString("") { it!!.character.toString() }
                // A template score is a match quality on the recogniser's own scale, and the
                // counter-agreement bonuses can carry a near-perfect glyph past 1.0; a
                // confidence is a bounded 0..1 quantity. Saturating here loses nothing — above
                // a perfect stroke match the difference between 1.03 and 1.19 means nothing —
                // and the margin gate still ranks on the raw, unclamped scores.
                confidence = readings.minOf { it!!.score }.coerceIn(0.0, 1.0)
                legibility = TextLegibility.READ
                detail = "; margins " + readings.joinToString(",") { fmt(it!!.margin) }
            } else {
                // One unreadable glyph condemns the whole run: "18?" is not a number, and a
                // number with a hole in it must never be completed by inference.
                legibility = TextLegibility.AMBIGUOUS
                detail = "; ${readings.count { it == null }} of ${run.size} glyphs did not clear the score or margin gate"
            }
        }
        return TextObservation(
            sourceAsset = assetUrl,
            bounds = if (orientation == TextOrientation.HORIZONTAL) bounds else unrotateBox(bounds, imageHeight),
            text = text,
            confidence = confidence,
            method = "glyph-run morphology: ${run.size} components on one baseline, median glyph height $height px, $orientation" +
                when (legibility) {
                    TextLegibility.TOO_SMALL_TO_READ -> "; below the ${DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX} px floor, not read"
                    TextLegibility.NO_RECOGNISER -> "; large enough to read, but no recogniser is wired in"
                    TextLegibility.AMBIGUOUS -> detail
                    TextLegibility.READ -> "; template digit match$detail"
                },
            legibility = legibility,
            glyphCount = run.size,
            glyphHeightPx = height,
            orientation = orientation,
        )
    }

    /**
     * Finds a mark sitting on the baseline between two glyphs of a run.
     *
     * A comma is two or three pixels at this type size — below the glyph height
     * floor, so it is never a glyph and never joins a run. Its absence is not
     * harmless: the digits either side stand a normal letter-space apart, get
     * chained, and `12,05` is read as `1205`. That is a hundredfold error
     * arriving as a confident whole number, and no downstream check would see
     * anything odd about it, because a plan dimension of 1205 cm is entirely
     * ordinary.
     *
     * So the gap is inspected rather than assumed empty. Only the band at the
     * foot of the glyphs is examined — the quarter of the glyph height above
     * the baseline and a little below — because that is where a decimal comma,
     * a full stop and a thousands mark all sit, and because a slanted font
     * leans its glyphs into the neighbouring column at the *top*, not there.
     * Ink found in that band is unexplained: the run is legible and its meaning
     * is not, which is a refusal rather than a reading.
     *
     * Returns the 1-based indices of the two glyphs the mark sits between.
     */
    private fun markAtBaselineBetween(ink: BinaryMask, run: List<PixelBox>): Pair<Int, Int>? {
        for (i in 0 until run.size - 1) {
            val left = run[i]
            val right = run[i + 1]
            if (right.minX - left.maxX < 2) continue
            val h = max(left.height, right.height)
            val baseline = max(left.maxY, right.maxY)
            val top = baseline - (h * BASELINE_BAND).toInt()
            val bottom = baseline + (h * BASELINE_BAND / 2).toInt()
            for (x in left.maxX + 1 until right.minX) {
                for (y in max(0, top)..min(ink.height - 1, bottom)) {
                    if (ink[x, y]) return (i + 1) to (i + 2)
                }
            }
        }
        return null
    }

    private fun glyphComponents(ink: BinaryMask): List<PixelBox> {
        val components = ink.components()
        val out = mutableListOf<PixelBox>()
        for (label in 1..components.count) {
            val box = components.boundingBox(label) ?: continue
            if (box.height < minGlyphHeightPx || box.height > maxGlyphHeightPx) continue
            // A glyph is taller than it is wide, or nearly square; a dash of a dimension chain
            // and a hatch stroke are many times wider than tall.
            if (box.width > box.height * 1.3) continue
            val ratio = components.sizes[label].toDouble() / box.area
            // A glyph's ink fills part of its box: a solid blob is a filled marker, and a
            // component that barely marks its box is a stray hairline or a piece of a plant.
            if (ratio > 0.92 || ratio < 0.14) continue
            out += box
        }
        return out
    }

    /**
     * Chains glyph boxes left to right into runs sharing a baseline.
     *
     * The gap between two characters of one number is a fraction of their
     * *height*, not of their width — which matters because a `1` is a third
     * the width of a `0`, and a width-relative rule split every number that
     * began with one into two runs.
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
            var changed = true
            while (changed) {
                changed = false
                for (j in sorted.indices) {
                    if (used[j]) continue
                    val next = sorted[j]
                    if (next.minX < last.minX) continue
                    val h = max(last.height, next.height)
                    if (abs(next.maxY - last.maxY) > h * baselineTolerance) continue
                    if (min(last.height, next.height) * 2 < h) continue
                    val gap = next.minX - last.maxX
                    if (gap < -2 || gap > h * maxGapInHeights + 2) continue
                    run += next
                    used[j] = true
                    last = next
                    changed = true
                    break
                }
            }
            if (run.size >= minGlyphsPerRun) runs += run.sortedBy { it.minX }
        }
        return runs.sortedWith(compareBy({ it.first().minY }, { it.first().minX }))
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.3f", v)

    private companion object {
        const val INK_MAX_LUMA = 150

        /** Fraction of the glyph height, above the baseline, that a separator mark occupies. */
        const val BASELINE_BAND = 0.25

        /**
         * Closes vertical gaps of one or two pixels before anything is grouped.
         *
         * These digits are drawn with one-pixel strokes and a slant, so a
         * stroke drops a row here and there and the glyph arrives as several
         * components — the base bar of a 2 separated from its diagonal, which
         * then scores as a 7. The closing is vertical only on purpose: a
         * symmetric one would bridge the two-pixel space between adjacent
         * digits and fuse a whole number into one blob.
         */
        fun glyphInk(ink: BinaryMask): BinaryMask = ink.close(0, 1)

        /** A quarter turn clockwise: (x, y) -> (height - 1 - y, x). */
        fun rotateQuarterTurn(image: RasterImage): RasterImage {
            val w = image.height
            val h = image.width
            val out = IntArray(w * h)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                out[x * w + (image.height - 1 - y)] = image.argb[y * image.width + x]
            }
            return RasterImage(w, h, out)
        }

        fun rotateBox(box: PixelBox, imageHeight: Int): PixelBox =
            PixelBox(imageHeight - 1 - box.maxY, box.minX, imageHeight - 1 - box.minY, box.maxX)

        fun unrotateBox(box: PixelBox, imageHeight: Int): PixelBox =
            PixelBox(box.minY, imageHeight - 1 - box.maxX, box.maxY, imageHeight - 1 - box.minX)
    }
}
