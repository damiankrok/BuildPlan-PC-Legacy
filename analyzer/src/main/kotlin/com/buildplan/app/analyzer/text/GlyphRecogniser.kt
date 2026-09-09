package com.buildplan.app.analyzer.text

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One recognised character with how well it matched and what it beat. */
data class GlyphReading(
    val character: Char,
    /** 0..1 agreement with the winning template. */
    val score: Double,
    /** How far ahead of the runner-up, in the same units; small means the two look alike. */
    val margin: Double,
    val runnerUp: Char?,
)

/**
 * Reads one glyph bitmap, or refuses to.
 *
 * Refusal is a first-class answer. A dimension label has no redundancy — no
 * word shape, no spell-check — so a character guessed at even good odds
 * silently turns 180 into 160, and there is no later stage that can catch it.
 */
interface GlyphRecogniser {
    /**
     * The best reading of [glyph] within [box], or null when nothing matched.
     *
     * [shear] is the slant to straighten out, measured once for the whole run
     * the glyph belongs to. It is not optional in practice: measured per
     * glyph, the criterion straightens a 7's diagonal into a stem and reads it
     * as a 1, because for a single glyph "make the strokes vertical" cannot
     * tell a font's lean from a digit's own shape. Across a run of digits the
     * true stems outvote the diagonals and the estimate is the font's.
     */
    fun recognise(glyph: BinaryMask, box: PixelBox, shear: Double): GlyphReading?

    /** The slant shared by a run of glyphs, to be passed back to [recognise]. */
    fun estimateShear(glyph: BinaryMask, boxes: List<PixelBox>): Double
}

/**
 * Deterministic digit recognition by normalised template matching.
 *
 * The drawings publish their dimension chains in one oblique technical font at
 * eleven to sixteen pixels a glyph, with one- and two-pixel strokes that drop
 * out here and there. Two choices make that readable without inventing digits:
 *
 * - **Coarse normalisation.** Every glyph is resampled to an 8 x 12 cell grid
 *   by ink coverage, so a stroke that wanders a pixel — which the font's slant
 *   and the raster's quantisation both guarantee — lands in the same cell it
 *   would have anyway. Matching at native resolution would make the slant look
 *   like a different letter.
 * - **Tolerant scoring with a hard margin.** A template cell counts as met if
 *   ink falls in it *or in a neighbour*, which absorbs the slant; but the
 *   winner must also beat the runner-up by [MIN_MARGIN], which is what stops
 *   the pairs that genuinely look alike at this size — 1/7, 3/8, 5/6, 0/8 —
 *   from being resolved by a coin toss dressed as a score.
 *
 * The templates describe digits, not this publisher: they are the shapes any
 * technical font draws. Nothing here knows a project, and the numbers a chain
 * is expected to produce are never consulted — a reading is checked against
 * *arithmetic and geometry* downstream ([DimensionChain]), never against an
 * expected answer.
 */
class TemplateDigitRecogniser(
    private val minScore: Double = MIN_SCORE,
    private val minMargin: Double = MIN_MARGIN,
) : GlyphRecogniser {

    override fun recognise(glyph: BinaryMask, box: PixelBox, shear: Double): GlyphReading? {
        val scored = rank(glyph, box, shear) ?: return null
        val (best, bestScore) = scored.first()
        val (runnerUp, runnerUpScore) = scored.getOrNull(1) ?: (null to 0.0)
        val margin = bestScore - runnerUpScore
        if (bestScore < minScore || margin < minMargin) return null
        return GlyphReading(best, bestScore, margin, runnerUp)
    }

    /** The normalised cell grid as text, for the developer probe. */
    fun normalisedRows(glyph: BinaryMask, box: PixelBox, shear: Double): List<String>? {
        val straight = deslant(glyph, box, shear) ?: return null
        val cells = normalise(straight.first, straight.second) ?: return null
        return (0 until ROWS).map { y -> (0 until COLS).map { x -> if (cells[y * COLS + x]) '#' else '.' }.joinToString("") }
    }

    /** Enclosed background regions, for the developer probe. */
    fun holesOf(glyph: BinaryMask, box: PixelBox): Int = countHoles(glyph, box).size

    /** Where the counters sit, 0 at the top of the glyph and 1 at its foot; for the probe. */
    fun holeCentresOf(glyph: BinaryMask, box: PixelBox): List<Double> = countHoles(glyph, box).map { it.centreY }

    /** Every digit scored, best first, with the gates *not* applied. For diagnostics only. */
    fun rank(glyph: BinaryMask, box: PixelBox, shear: Double): List<Pair<Char, Double>>? {
        val straight = deslant(glyph, box, shear) ?: return null
        val cells = normalise(straight.first, straight.second) ?: return null
        val holes = countHoles(glyph, box)
        // Width is measured after straightening: a slant adds columns that are the font's, not
        // the digit's, and on a narrow glyph it adds enough to make a 1 look as wide as a 7.
        val narrow = aspect(straight.second) <= GlyphTemplates.NARROW_ASPECT
        return GlyphTemplates.digits
            .map { (ch, t) ->
                ch to (
                    score(cells, t.cells) +
                        holeAgreement(holes.size, t.holes.size) +
                        holePlacement(holes, t.holes) +
                        narrowAgreement(narrow, t.narrow)
                    )
            }
            .sortedByDescending { it.second }
    }

    /**
     * Agreement on *where* the counter sits, not just how many there are.
     *
     * This is what separates the pairs a stroke score cannot: a 9 carries its
     * counter high, a 6 carries it low, and a 0 carries one that fills the
     * body — three digits with one hole each, identical on that count alone,
     * and 9 against 0 was decided by seven thousandths before this existed.
     * Like the count, a preference rather than a veto: a broken stroke moves a
     * centre, and a glyph pushed into ambiguity is dropped, never mis-read.
     */
    private fun holePlacement(observed: List<GlyphHole>, expected: List<GlyphHole>): Double {
        if (expected.isEmpty() || observed.isEmpty() || observed.size != expected.size) return 0.0
        val worstPlace = observed.indices.maxOf { abs(observed[it].centreY - expected[it].centreY) }
        // Size as well as place: a 4 and a 9 both carry one counter high, and only the 4's
        // narrow triangle against the 9's round bowl separates them once slant is gone.
        val worstSize = observed.indices.maxOf { abs(observed[it].areaFraction - expected[it].areaFraction) }
        return HOLE_PLACEMENT_WEIGHT * (1.0 - min(1.0, worstPlace / HOLE_PLACEMENT_TOLERANCE)) +
            HOLE_SIZE_WEIGHT * (1.0 - min(1.0, worstSize / HOLE_SIZE_TOLERANCE))
    }

    /**
     * Enclosed background regions inside the glyph box.
     *
     * At eleven pixels this is the single most reliable thing about a digit:
     * an 8 has two, `0 4 6 9` have one, the rest have none, and no amount of
     * slant or stroke dropout moves a digit between those groups — while a
     * one-pixel gap easily makes a 3 score like an 8 on strokes alone. Applied
     * as a prior rather than a filter, because a stroke that *does* drop out
     * can open a counter, and a glyph that then goes ambiguous is dropped —
     * which is the safe outcome, not a wrong digit.
     */
    private fun countHoles(glyph: BinaryMask, box: PixelBox): List<GlyphHole> =
        GlyphHoles.find(box.width, box.height) { x, y -> glyph[box.minX + x, box.minY + y] }

    private fun holeAgreement(observed: Int, expected: Int): Double =
        if (observed == expected) HOLE_BONUS else -HOLE_PENALTY * min(2, abs(observed - expected))

    private fun narrowAgreement(observed: Boolean, expected: Boolean): Double =
        if (observed == expected) 0.0 else -NARROW_PENALTY

    /**
     * Straightens the glyph, then resamples it onto the template grid by ink
     * coverage — so the result is independent both of the drawing's font slant
     * and of how many pixels tall it printed the digit.
     *
     * The slant is not cosmetic at this size: the sheet's oblique technical
     * font moves a `0` three of the grid's eight columns between its top and
     * its bottom, which is most of the difference between one digit and
     * another. Matching upright templates against un-straightened glyphs put
     * `2` a six-thousandth ahead of `7` on a chain digit — a margin that is
     * noise, and one the gate correctly refused, so the reading was lost
     * rather than wrong.
     *
     * The shear is measured, not assumed: of a bounded set of candidate
     * shears, the one chosen packs the ink into the fewest columns, which is
     * what happens exactly when the stems come upright. Estimating it per
     * glyph rather than per sheet costs nothing and survives a drawing that
     * mixes fonts.
     */
    /** The glyph straightened, with the box it now occupies. */
    private fun deslant(glyph: BinaryMask, box: PixelBox, shear: Double): Pair<BinaryMask, PixelBox>? {
        val w = box.width
        val h = box.height
        if (w < 2 || h < MIN_GLYPH_ROWS) return null
        val shift = (shear * (h - 1)).toInt()
        val sheared = BinaryMask(w + abs(shift) + 2, h)
        val originX = if (shift < 0) -shift else 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!glyph[box.minX + x, box.minY + y]) continue
            val sx = originX + x + (shear * (h - 1 - y)).toInt()
            if (sx in 0 until sheared.width) sheared[sx, y] = true
        }
        val shearedBox = sheared.boundingBox() ?: return null
        return sheared to shearedBox
    }

    private fun normalise(sheared: BinaryMask, shearedBox: PixelBox): BooleanArray? {
        val sw = shearedBox.width
        val sh = shearedBox.height
        if (sw < 1 || sh < 1) return null
        val cells = BooleanArray(COLS * ROWS)
        for (cy in 0 until ROWS) for (cx in 0 until COLS) {
            val x0 = shearedBox.minX + cx * sw / COLS
            val x1 = max(x0 + 1, shearedBox.minX + (cx + 1) * sw / COLS)
            val y0 = shearedBox.minY + cy * sh / ROWS
            val y1 = max(y0 + 1, shearedBox.minY + (cy + 1) * sh / ROWS)
            var ink = 0
            var total = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                total++
                if (sheared[x, y]) ink++
            }
            cells[cy * COLS + cx] = total > 0 && ink.toDouble() / total >= CELL_INK_FRACTION
        }
        return cells
    }

    /**
     * The shear that concentrates the glyph's ink into the fewest columns.
     *
     * Shearing moves pixels only sideways, so the total ink is fixed and the
     * sum of squared column counts is maximised precisely when vertical
     * strokes line up — the standard deslanting criterion, and cheap enough to
     * evaluate exhaustively over a bounded range.
     */
    override fun estimateShear(glyph: BinaryMask, boxes: List<PixelBox>): Double {
        if (boxes.isEmpty()) return 0.0
        var best = 0.0
        var bestEnergy = -1.0
        var s = MIN_SHEAR
        while (s <= MAX_SHEAR + 1e-9) {
            // Energy is summed over the run, and each glyph is normalised by its own ink so a
            // wide digit cannot outvote a narrow one on size alone.
            var energy = 0.0
            boxes.forEach { box ->
                val columns = IntArray(box.width * 3 + 8)
                val origin = box.width
                var ink = 0
                for (y in 0 until box.height) for (x in 0 until box.width) {
                    if (!glyph[box.minX + x, box.minY + y]) continue
                    ink++
                    val sx = origin + x + (s * (box.height - 1 - y)).toInt()
                    if (sx in columns.indices) columns[sx]++
                }
                if (ink > 0) energy += columns.sumOf { it.toDouble() * it } / (ink.toDouble() * ink)
            }
            if (energy > bestEnergy) {
                bestEnergy = energy
                best = s
            }
            s += SHEAR_STEP
        }
        return best
    }

    /**
     * Agreement between a normalised glyph and a template.
     *
     * Ink cells carry the shape and blank cells carry the counter-shape, so
     * both are scored, but a template's ink cell is allowed to be met by a
     * neighbouring glyph cell: at this size the difference between a stroke
     * on a cell boundary and a stroke one cell over is the font's slant, not
     * the digit's identity. Blank cells are matched strictly, which is what
     * still separates an 8 from a 0.
     */
    private fun score(cells: BooleanArray, template: BooleanArray): Double {
        var hit = 0.0
        var inkCells = 0
        var blankHit = 0.0
        var blankCells = 0
        for (cy in 0 until ROWS) for (cx in 0 until COLS) {
            val i = cy * COLS + cx
            if (template[i]) {
                inkCells++
                if (cells[i]) hit += 1.0 else if (neighbourInk(cells, cx, cy)) hit += NEIGHBOUR_CREDIT
            } else {
                blankCells++
                if (!cells[i]) blankHit += 1.0
            }
        }
        val inkScore = if (inkCells == 0) 0.0 else hit / inkCells
        val blankScore = if (blankCells == 0) 0.0 else blankHit / blankCells
        return INK_WEIGHT * inkScore + (1 - INK_WEIGHT) * blankScore
    }

    private fun neighbourInk(cells: BooleanArray, cx: Int, cy: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = cx + dx
            val y = cy + dy
            if (x in 0 until COLS && y in 0 until ROWS && cells[y * COLS + x]) return true
        }
        return false
    }

    companion object {
        const val COLS = 8
        const val ROWS = 12

        /** Below this many source rows a glyph cannot fill the grid meaningfully. */
        const val MIN_GLYPH_ROWS = 8

        /** A grid cell is ink when this much of it is ink. */
        const val CELL_INK_FRACTION = 0.30

        /** A template ink cell met by a neighbour rather than exactly is worth this much. */
        const val NEIGHBOUR_CREDIT = 0.65

        /** Shape matters more than counter-shape, but not so much that a blob scores well. */
        const val INK_WEIGHT = 0.6

        /** A reading below this is not a digit at all. */
        const val MIN_SCORE = 0.80

        /**
         * How far the winner must beat the runner-up.
         *
         * The whole point of the gate: at eleven pixels a 1 and a 7, a 3 and an
         * 8, a 5 and a 6 differ by a stroke or two, and a recogniser that
         * reports the higher of two near-equal scores is guessing. Below this
         * the glyph is AMBIGUOUS and the run it belongs to is dropped whole.
         */
        const val MIN_MARGIN = 0.045

        /** Agreeing on the number of counters is strong evidence; disagreeing is strong against. */
        const val HOLE_BONUS = 0.06
        const val HOLE_PENALTY = 0.10

        /** A 1 is narrow and a 7 is not; after normalisation only this remembers that. */
        const val NARROW_PENALTY = 0.08

        /** How much a counter in the right place is worth, and how far off stops counting. */
        const val HOLE_PLACEMENT_WEIGHT = 0.07
        const val HOLE_PLACEMENT_TOLERANCE = 0.28

        /** And how much a counter of the right size is worth. */
        const val HOLE_SIZE_WEIGHT = 0.07
        const val HOLE_SIZE_TOLERANCE = 0.10

        /**
         * Candidate shears, in columns of shift per row of height.
         *
         * Symmetric, because which way a font leans is not something to assume:
         * a one-sided range silently left every right-leaning glyph slanted,
         * and the criterion below is sign-agnostic anyway.
         */
        const val MIN_SHEAR = -0.60
        const val MAX_SHEAR = 0.60
        const val SHEAR_STEP = 0.05

        private val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}

/** Aspect ratio of a glyph box, used to keep a narrow 1 from matching a wide digit. */
internal fun aspect(box: PixelBox): Double = box.width.toDouble() / max(1, box.height)


