package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import kotlin.math.max

/**
 * Finds stair flights as what they are on a plan: a run of thin, parallel,
 * evenly spaced lines.
 *
 * No arrow or symbol recognition, and no assumption that a flight is
 * straight. A house stair is usually two runs around a half-landing or a
 * winder, so the detector finds *runs* and leaves joining them into one
 * stair to the caller, which knows which room they fall in.
 *
 * Thresholds are physical. A tread's going is 0.16 m at the narrow end of a
 * winder and 0.42 m at the generous end of a main flight; nothing outside
 * that is a stair, and nothing inside it is excluded because one drawing
 * happened to space its lines differently.
 */
object StairDetector {

    data class StairZone(
        val box: PixelBox,
        val treadLines: Int,
        val axis: Axis,
        /** Median spacing between consecutive tread lines, in pixels. */
        val spacingPx: Double,
    )

    /**
     * Treads of one flight are cut to one going, so their spacings agree. A run
     * of furniture edges, a hatch or a tiled floor pattern also repeats, but
     * its pitch wanders; requiring the widest gap to stay within this much of
     * the narrowest is what separates a flight from a pattern without knowing
     * either drawing.
     */
    private const val MAX_SPACING_RATIO = 1.4

    fun detect(thinInk: BinaryMask, minTreadLengthPx: Int, spacingPx: IntRange, minTreads: Int = 4): List<StairZone> {
        val zones = mutableListOf<StairZone>()
        zones += detectAlong(thinInk, Axis.HORIZONTAL, minTreadLengthPx, spacingPx, minTreads)
        zones += detectAlong(thinInk, Axis.VERTICAL, minTreadLengthPx, spacingPx, minTreads)
        // A flight found on both axes is one flight seen twice; keep the reading with more treads.
        return zones
            .sortedWith(compareByDescending<StairZone> { it.treadLines }.thenBy { it.box.minY }.thenBy { it.box.minX })
            .fold(mutableListOf()) { kept, z ->
                if (kept.none { overlaps(it.box, z.box) }) kept.add(z)
                kept
            }
    }

    private fun overlaps(a: PixelBox, b: PixelBox): Boolean {
        val w = minOf(a.maxX, b.maxX) - max(a.minX, b.minX)
        val h = minOf(a.maxY, b.maxY) - max(a.minY, b.minY)
        if (w <= 0 || h <= 0) return false
        val inter = w.toDouble() * h
        return inter / minOf(a.area, b.area) > 0.4
    }

    private fun detectAlong(thin: BinaryMask, axis: Axis, minLength: Int, spacing: IntRange, minTreads: Int): List<StairZone> {
        // Thin lines along the axis: runs at least minLength long and at most 3 px thick.
        val half = minLength / 2
        val lines = if (axis == Axis.HORIZONTAL) thin.open(half, 0) else thin.open(0, half)
        val pieces = mutableListOf<WallPiece>()
        val components = lines.components()
        for (label in 1..components.count) {
            val box = components.boundingBox(label) ?: continue
            val thickness = if (axis == Axis.HORIZONTAL) box.height else box.width
            val length = if (axis == Axis.HORIZONTAL) box.width else box.height
            if (thickness <= 3 && length >= minLength) {
                pieces += if (axis == Axis.HORIZONTAL) WallPiece(axis, box.minX, box.maxX + 1, box.minY, box.maxY + 1)
                else WallPiece(axis, box.minY, box.maxY + 1, box.minX, box.maxX + 1)
            }
        }
        // Group lines that overlap along the axis and are spaced regularly across it.
        val sorted = pieces.sortedBy { it.centre }
        val used = BooleanArray(sorted.size)
        val zones = mutableListOf<StairZone>()
        for (i in sorted.indices) {
            if (used[i]) continue
            val group = mutableListOf(i)
            var last = sorted[i]
            val gaps = mutableListOf<Double>()
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val next = sorted[j]
                val gap = next.centre - last.centre
                if (gap > spacing.last) break
                if (gap < spacing.first) continue
                val overlap = minOf(last.to, next.to) - max(last.from, next.from)
                if (overlap < minLength / 2) continue
                // Treads in one flight are near enough the same length; a winder's are not, and
                // requiring similarity would throw away exactly the stairs that are hardest to
                // read. Four times is loose enough for a winder and tight enough to keep a
                // hatching pattern or a run of furniture edges out.
                if (max(next.length, last.length) > 4 * minOf(next.length, last.length)) continue
                gaps += gap
                group += j
                last = next
            }
            val regular = gaps.isNotEmpty() && gaps.max() <= gaps.min() * MAX_SPACING_RATIO
            if (group.size >= minTreads && regular) {
                group.forEach { used[it] = true }
                val members = group.map { sorted[it] }
                val from = members.minOf { it.from }
                val to = members.maxOf { it.to }
                val low = members.minOf { it.low }
                val high = members.maxOf { it.high }
                val box = if (axis == Axis.HORIZONTAL) PixelBox(from, low, to - 1, high - 1) else PixelBox(low, from, high - 1, to - 1)
                zones += StairZone(box, group.size, axis, gaps.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] })
            }
        }
        return zones
    }

    /**
     * Whether the tread lines of a zone get longer or shorter along the run.
     *
     * On a winder the treads fan out, and which end is narrow says which way
     * the flight turns — but not which way it climbs. Nothing on a plan says
     * that without reading the arrow, so this reports the run *axis* and the
     * caller leaves the climb direction unknown rather than guessing it.
     */
    fun runAxisLabel(zone: StairZone): String = when (zone.axis) {
        Axis.HORIZONTAL -> "runs north-south"
        Axis.VERTICAL -> "runs east-west"
    }
}
