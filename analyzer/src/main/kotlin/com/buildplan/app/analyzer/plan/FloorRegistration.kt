package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.raster.BinaryMask
import kotlin.math.abs
import kotlin.math.max

/**
 * How one storey's model frame sits over another's.
 *
 * [offset] is added to a point in the *upper* storey's frame to place it in
 * the lower storey's. [confidence] is how far the winning alignment stood
 * above the average one, so a flat correlation surface — two storeys with
 * nothing in common to align — reports itself instead of returning a shift
 * that happens to have won.
 */
data class FloorAlignment(
    val offset: Pt,
    val overlapScore: Double,
    val confidence: Double,
    val note: String,
) {
    val isReliable: Boolean get() = confidence >= MIN_CONFIDENCE

    companion object {
        /** How far above the mean the peak must stand for the alignment to be believed. */
        const val MIN_CONFIDENCE = 0.25

        val NONE = FloorAlignment(Pt(0.0, 0.0), 0.0, 0.0, "storeys were not registered")
    }
}

/**
 * Puts two storeys of one building into a common frame.
 *
 * Each plan is calibrated against its own footprint, so each storey's model
 * origin is its own bounding corner — and the sheets do not draw the building
 * in the same place, so those corners are different points of the house.
 * Anything that compares storeys in model metres is therefore comparing two
 * frames that are metres apart: it is why a stairwell drawn on both plans was
 * never recognised as one shaft, and why nothing above could be said to sit
 * over anything below.
 *
 * Registration aligns the two **wall outlines** rather than the filled
 * footprints. A smaller upper storey sits inside a larger lower one at many
 * translations, all of which overlap almost equally; the outlines only
 * coincide where walls actually stack, which is the alignment being looked
 * for. The search is coarse-to-fine so the cost stays a few hundred thousand
 * comparisons rather than the tens of millions a full-resolution sweep needs.
 */
object FloorRegistration {

    /** How far the storeys may be apart on their sheets, as a fraction of the plan's size. */
    private const val SEARCH_FRACTION = 0.25

    private const val COARSE_STEP = 4

    fun register(
        lower: BinaryMask,
        lowerCalibration: PlanCalibration,
        upper: BinaryMask,
        upperCalibration: PlanCalibration,
    ): FloorAlignment {
        val a = outline(lower)
        val b = outline(upper)
        val aPoints = points(a)
        val bPoints = points(b)
        if (aPoints.size < 50 || bPoints.size < 50) {
            return FloorAlignment.NONE.copy(note = "too little outline to register (${aPoints.size} and ${bPoints.size} pixels)")
        }

        val range = (max(lower.width, lower.height) * SEARCH_FRACTION).toInt()
        // The coarse sweep matches against a *thickened* outline. An outline is one pixel wide, so
        // a sweep in steps of four steps straight over the true alignment and scores it no better
        // than noise — which is exactly what happened: a peak of 41 out of 440 outline pixels, at
        // a shift 5 m from the right one. Thickening by half the step means every coarse position
        // sees the peak if it is anywhere near, and the fine sweep then finds it exactly.
        val coarseTarget = a.dilate(COARSE_STEP / 2, COARSE_STEP / 2)
        val coarse = search(coarseTarget, bPoints, -range..range, -range..range, COARSE_STEP)
        val fine = search(
            a,
            bPoints,
            (coarse.dx - COARSE_STEP)..(coarse.dx + COARSE_STEP),
            (coarse.dy - COARSE_STEP)..(coarse.dy + COARSE_STEP),
            1,
        )
        // Sharpness of the coarse peak against its own background: a surface with no peak — two
        // storeys with nothing in common — reports itself rather than returning whatever won.
        val confidence = if (coarse.best <= 0.0) 0.0 else (coarse.best - coarse.mean) / coarse.best

        // The shift is measured in the upper plan's pixels; both frames share a scale, so it
        // converts once. Model offset = (upper origin + shift) expressed in the lower frame.
        val upperOriginInLower = lowerCalibration.toMeters(Pt(upperCalibration.originPx.x + fine.dx, upperCalibration.originPx.z + fine.dy))
        return FloorAlignment(
            offset = upperOriginInLower,
            overlapScore = fine.best,
            confidence = confidence,
            note = "wall outlines aligned at a shift of (${fine.dx}, ${fine.dy}) px; peak ${"%.0f".format(java.util.Locale.ROOT, fine.best)} against a mean of ${"%.0f".format(java.util.Locale.ROOT, coarse.mean)}",
        )
    }

    private data class Search(val dx: Int, val dy: Int, val best: Double, val mean: Double)

    private fun search(target: BinaryMask, movingPoints: List<Int>, xs: IntRange, ys: IntRange, step: Int): Search {
        var bestDx = 0
        var bestDy = 0
        var best = -1.0
        var sum = 0.0
        var count = 0
        val width = target.width
        var dy = ys.first
        while (dy <= ys.last) {
            var dx = xs.first
            while (dx <= xs.last) {
                var hits = 0
                movingPoints.forEach { p ->
                    val x = (p % width) + dx
                    val y = (p / width) + dy
                    if (target[x, y]) hits++
                }
                val score = hits.toDouble()
                sum += score
                count++
                if (score > best) {
                    best = score
                    bestDx = dx
                    bestDy = dy
                }
                dx += step
            }
            dy += step
        }
        return Search(bestDx, bestDy, best, if (count == 0) 0.0 else sum / count)
    }

    /** One-pixel outline of a filled mask: what is set and has an unset four-neighbour. */
    private fun outline(mask: BinaryMask): BinaryMask {
        val out = BinaryMask(mask.width, mask.height)
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (!mask[x, y]) continue
            if (!mask[x - 1, y] || !mask[x + 1, y] || !mask[x, y - 1] || !mask[x, y + 1]) out[x, y] = true
        }
        return out
    }

    /**
     * Outline pixels as flat indices, thinned so the correlation costs the same
     * whatever resolution the sheet was published at.
     */
    private fun points(mask: BinaryMask): List<Int> {
        val all = mutableListOf<Int>()
        for (i in mask.bits.indices) if (mask.bits[i]) all += i
        if (all.size <= MAX_POINTS) return all
        val stride = all.size / MAX_POINTS + 1
        return all.filterIndexed { i, _ -> i % stride == 0 }
    }

    private const val MAX_POINTS = 1200

    /** Whether two boxes overlap once the upper one is placed in the lower one's frame. */
    fun overlapFraction(lower: com.buildplan.app.analyzer.candidate.Box, upper: com.buildplan.app.analyzer.candidate.Box, alignment: FloorAlignment): Double {
        val shifted = com.buildplan.app.analyzer.candidate.Box(
            upper.minX + alignment.offset.x,
            upper.minZ + alignment.offset.z,
            upper.maxX + alignment.offset.x,
            upper.maxZ + alignment.offset.z,
        )
        val w = minOf(lower.maxX, shifted.maxX) - max(lower.minX, shifted.minX)
        val h = minOf(lower.maxZ, shifted.maxZ) - max(lower.minZ, shifted.minZ)
        if (w <= 0 || h <= 0) return 0.0
        val smaller = minOf(lower.area, shifted.area)
        return if (smaller <= 0) 0.0 else abs(w * h) / smaller
    }
}
