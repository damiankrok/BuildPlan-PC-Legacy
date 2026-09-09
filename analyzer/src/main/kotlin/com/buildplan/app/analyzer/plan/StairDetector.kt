package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import kotlin.math.abs

/**
 * Finds stair flights as what they are on a plan: a run of thin, parallel,
 * evenly spaced lines. No arrow or symbol recognition — the direction of a
 * flight is reported as unknown and asked about.
 */
object StairDetector {

    data class StairZone(val box: PixelBox, val treadLines: Int, val axis: Axis)

    fun detect(thinInk: BinaryMask, minTreadLengthPx: Int, spacingPx: IntRange, minTreads: Int = 4): List<StairZone> {
        val zones = mutableListOf<StairZone>()
        zones += detectAlong(thinInk, Axis.HORIZONTAL, minTreadLengthPx, spacingPx, minTreads)
        zones += detectAlong(thinInk, Axis.VERTICAL, minTreadLengthPx, spacingPx, minTreads)
        return zones
    }

    private fun detectAlong(thin: BinaryMask, axis: Axis, minLength: Int, spacing: IntRange, minTreads: Int): List<StairZone> {
        // Thin lines along the axis: runs at least minLength long and at most 2 px thick.
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
            val group = mutableListOf(sorted[i])
            var last = sorted[i]
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val next = sorted[j]
                val gap = (next.centre - last.centre).toInt()
                if (gap > spacing.last) break
                if (gap < spacing.first) continue
                val overlap = minOf(last.to, next.to) - maxOf(last.from, next.from)
                if (overlap < minLength / 2) continue
                if (abs(next.length - last.length) > maxOf(last.length, next.length) * 0.5) continue
                group += next
                last = next
            }
            if (group.size >= minTreads) {
                group.forEach { g -> used[sorted.indexOf(g)] = true }
                val from = group.minOf { it.from }
                val to = group.maxOf { it.to }
                val low = group.minOf { it.low }
                val high = group.maxOf { it.high }
                val box = if (axis == Axis.HORIZONTAL) PixelBox(from, low, to - 1, high - 1) else PixelBox(low, from, high - 1, to - 1)
                zones += StairZone(box, group.size, axis)
            }
        }
        return zones
    }
}
