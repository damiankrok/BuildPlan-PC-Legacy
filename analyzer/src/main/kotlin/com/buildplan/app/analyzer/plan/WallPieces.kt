package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.raster.BinaryMask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Axis of a wall piece on the raster. */
enum class Axis { HORIZONTAL, VERTICAL }

/**
 * One straight, axis-aligned run of structure on the raster, in pixels.
 *
 * @property axis along which the piece runs.
 * @property from,to extent along the axis, [from, to) in pixels.
 * @property low,high extent across the axis, [low, high) in pixels — so
 *   `high - low` is the thickness and `(low + high) / 2` the centreline.
 */
data class WallPiece(val axis: Axis, val from: Int, val to: Int, val low: Int, val high: Int) {
    val length: Int get() = to - from
    val thickness: Int get() = high - low
    val centre: Double get() = (low + high) / 2.0

    fun minX(): Int = if (axis == Axis.HORIZONTAL) from else low
    fun maxX(): Int = if (axis == Axis.HORIZONTAL) to else high
    fun minY(): Int = if (axis == Axis.HORIZONTAL) low else from
    fun maxY(): Int = if (axis == Axis.HORIZONTAL) high else to
}

/** A gap between two collinear pieces: an opening candidate in pixels. */
data class PieceGap(val axis: Axis, val from: Int, val to: Int, val low: Int, val high: Int, val beforeIndex: Int, val afterIndex: Int) {
    val width: Int get() = to - from
}

/**
 * Turns the structure mask into axis-aligned pieces.
 *
 * Method: a directional opening keeps only runs at least `minLength` long
 * along one axis, which separates horizontal from vertical structure without
 * any line detector. Each surviving component is then cut wherever its
 * cross-section changes, so a thick exterior wall continuing into a thin
 * partition on the same line becomes two pieces with two thicknesses.
 * Everything is integer pixels; metres come later, from calibration.
 */
object WallPieces {

    fun extract(structure: BinaryMask, minLengthPx: Int, minThicknessPx: Int, maxThicknessPx: Int): List<WallPiece> {
        val horizontal = structure.openWindow(minLengthPx, 1)
        val vertical = structure.openWindow(1, minLengthPx)
        val pieces = mutableListOf<WallPiece>()
        pieces += piecesOf(horizontal, Axis.HORIZONTAL, minLengthPx, minThicknessPx)
        pieces += piecesOf(vertical, Axis.VERTICAL, minLengthPx, minThicknessPx)
        // A wall run is longer than it is thick, and no wall in a house is thicker than
        // maxThicknessPx: what fails either test is the cross-section of a perpendicular
        // wall that happened to be as long as the opening window, not a wall of this axis.
        return pieces
            .filter { it.length >= it.thickness && it.thickness <= maxThicknessPx }
            .sortedWith(compareBy({ it.axis }, { it.low }, { it.from }))
    }

    /**
     * Scans the bars mask position by position along the axis. Each position
     * holds any number of runs across the axis — a column of a plan crosses
     * several horizontal walls — and every run is tracked as its own group,
     * extended while the next position has a run whose edges stay within the
     * tolerance of the group's running medians. A group that finds no run to
     * continue it is closed and becomes a piece if it is long enough.
     *
     * The median, not the first edge, is what the next run is compared with:
     * a wall edge dimmed by a watermark wanders by a pixel or two, and a
     * partition meeting a thicker wall changes by many.
     */
    private fun piecesOf(bars: BinaryMask, axis: Axis, minLength: Int, minThickness: Int): List<WallPiece> {
        class Group(val from: Int, val los: MutableList<Int>, val his: MutableList<Int>) {
            val medianLo: Int get() = los.sorted()[los.size / 2]
            val medianHi: Int get() = his.sorted()[his.size / 2]
        }

        val alongExtent = if (axis == Axis.HORIZONTAL) bars.width else bars.height
        val out = mutableListOf<WallPiece>()
        var open = mutableListOf<Group>()
        for (a in 0..alongExtent) {
            val runs = if (a >= alongExtent) emptyList() else if (axis == Axis.HORIZONTAL) bars.columnRuns(a) else bars.rowRuns(a)
            val next = mutableListOf<Group>()
            val taken = BooleanArray(runs.size)
            for (group in open) {
                val lo = group.medianLo
                val hi = group.medianHi
                val match = runs.indices.firstOrNull { i ->
                    !taken[i] && abs(runs[i].first - lo) <= CROSS_SECTION_TOLERANCE_PX && abs(runs[i].last + 1 - hi) <= CROSS_SECTION_TOLERANCE_PX
                }
                if (match != null) {
                    taken[match] = true
                    group.los += runs[match].first
                    group.his += runs[match].last + 1
                    next += group
                } else {
                    val length = a - group.from
                    val lo2 = group.medianLo
                    val hi2 = group.medianHi
                    if (length >= minLength && hi2 - lo2 >= minThickness) out += WallPiece(axis, group.from, a, lo2, hi2)
                }
            }
            runs.indices.filter { !taken[it] }.forEach { i ->
                next += Group(a, mutableListOf(runs[i].first), mutableListOf(runs[i].last + 1))
            }
            open = next
        }
        return mergeCollinearTouching(out)
    }

    private const val CROSS_SECTION_TOLERANCE_PX = 2

    /** Adjacent pieces with the same cross-section that merely got cut at a junction are one piece. */
    private fun mergeCollinearTouching(pieces: List<WallPiece>): List<WallPiece> {
        val sorted = pieces.sortedWith(compareBy({ it.low }, { it.from }))
        val out = mutableListOf<WallPiece>()
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.axis == p.axis &&
                abs(last.low - p.low) <= CROSS_SECTION_TOLERANCE_PX && abs(last.high - p.high) <= CROSS_SECTION_TOLERANCE_PX &&
                p.from <= last.to + 1
            ) {
                out[out.size - 1] = last.copy(to = max(last.to, p.to), low = min(last.low, p.low), high = max(last.high, p.high))
            } else {
                out += p
            }
        }
        return out
    }

    /**
     * Gaps at the ends of pieces: from each end of each piece, along its own
     * line, to the next structure pixel — a collinear piece across a window,
     * or a perpendicular wall a door is hung against. That is where doors,
     * windows, gates and open passages are. A gap shorter than [minGapPx] is
     * a junction, not an opening; longer than [maxGapPx] is not one wall line.
     *
     * [afterIndex] names the piece the march landed on, or -1 when it landed
     * on structure no piece represents (a wall that is not axis-aligned).
     */
    fun endGaps(pieces: List<WallPiece>, structure: BinaryMask, minGapPx: Int, maxGapPx: Int): List<PieceGap> {
        val pieceMask = rasterise(structure.width, structure.height, pieces)
        val pieceIndex = IntArray(structure.width * structure.height) { -1 }
        pieces.forEachIndexed { index, p ->
            for (y in max(0, p.minY()) until min(structure.height, p.maxY())) for (x in max(0, p.minX()) until min(structure.width, p.maxX())) {
                pieceIndex[y * structure.width + x] = index
            }
        }
        val out = mutableListOf<PieceGap>()
        pieces.forEachIndexed { index, p ->
            for (forward in listOf(true, false)) {
                val step = if (forward) 1 else -1
                // Probe the middle third of the cross-section, so a jamb reveal on one face
                // does not end the march early.
                val across = (p.low + p.thickness / 3)..(p.high - 1 - p.thickness / 3)
                fun solidAt(a: Int): Boolean? {
                    for (c in across) {
                        val x = if (p.axis == Axis.HORIZONTAL) a else c
                        val y = if (p.axis == Axis.HORIZONTAL) c else a
                        if (x < 0 || y < 0 || x >= structure.width || y >= structure.height) return null
                        if (structure[x, y] || pieceMask[x, y]) return true
                    }
                    return false
                }
                // The wall may continue past the piece as a fragment too short or too
                // ragged to be a piece of its own; that is still wall. Skip it, then
                // measure the empty run to the next solid pixel. A piece of the other
                // axis under the probe is a junction: the wall line ends there, and a
                // gap on the far side of that wall belongs to another room's wall line.
                var a = if (forward) p.to else p.from - 1
                var skipped = 0
                var junction = false
                while (skipped <= maxGapPx) {
                    val solid = solidAt(a) ?: break
                    if (!solid) break
                    val under = pieceIndexAt(pieceIndex, structure.width, p, a, across)
                    if (under >= 0 && under != index && pieces[under].axis != p.axis) { junction = true; break }
                    a += step
                    skipped++
                }
                if (junction) continue
                val gapStart = a
                var d = 0
                var hitIndex = -2
                while (d <= maxGapPx) {
                    val solid = solidAt(a) ?: break
                    if (solid) {
                        val x = if (p.axis == Axis.HORIZONTAL) a else across.first
                        val y = if (p.axis == Axis.HORIZONTAL) across.first else a
                        hitIndex = pieceIndexAt(pieceIndex, structure.width, p, a, across)
                        if (hitIndex == -1 && pieceMask[x, y]) hitIndex = pieceIndex[y * structure.width + x]
                        break
                    }
                    a += step
                    d++
                }
                if (hitIndex == -2 || d < minGapPx || d > maxGapPx) continue
                if (hitIndex == index) continue
                val from = if (forward) gapStart else gapStart - d + 1
                val to = if (forward) gapStart + d else gapStart + 1
                out += PieceGap(p.axis, from, to, p.low, p.high, if (forward) index else hitIndex, if (forward) hitIndex else index)
            }
        }
        // The same opening is usually found from both ends; keep one record per span.
        return out
            .sortedWith(compareBy({ it.axis }, { it.low }, { it.from }))
            .fold(mutableListOf()) { acc, g ->
                val dup = acc.lastOrNull()?.let { l -> l.axis == g.axis && abs(l.from - g.from) <= 2 && abs(l.to - g.to) <= 2 && abs(l.low - g.low) <= 2 }
                if (dup == true) acc else acc.apply { add(g) }
            }
    }

    /** The index of the piece under the probe line at position [a], or -1 when none. */
    private fun pieceIndexAt(pieceIndex: IntArray, width: Int, p: WallPiece, a: Int, across: IntRange): Int {
        for (c in across) {
            val x = if (p.axis == Axis.HORIZONTAL) a else c
            val y = if (p.axis == Axis.HORIZONTAL) c else a
            val idx = pieceIndex[y * width + x]
            if (idx >= 0) return idx
        }
        return -1
    }

    /**
     * Which regions lie on either side of a gap, probed a few pixels beyond
     * its two faces at its midpoint. Zero when a side is not a region.
     */
    fun sidesOf(gap: PieceGap, regionLabelAt: (Int, Int) -> Int, probePx: Int): Pair<Int, Int> {
        val mid = (gap.from + gap.to) / 2
        fun labelAt(offsetAcross: Int): Int {
            val c = if (offsetAcross < 0) gap.low + offsetAcross else gap.high - 1 + offsetAcross
            val x = if (gap.axis == Axis.HORIZONTAL) mid else c
            val y = if (gap.axis == Axis.HORIZONTAL) c else mid
            return regionLabelAt(x, y)
        }
        return labelAt(-probePx) to labelAt(probePx)
    }

    /** Rasterises pieces (and optionally gaps) as filled rectangles. */
    fun rasterise(width: Int, height: Int, pieces: List<WallPiece>, gaps: List<PieceGap> = emptyList()): BinaryMask {
        val mask = BinaryMask(width, height)
        fun fill(minX: Int, minY: Int, maxX: Int, maxY: Int) {
            for (y in max(0, minY) until min(height, maxY)) for (x in max(0, minX) until min(width, maxX)) mask[x, y] = true
        }
        pieces.forEach { fill(it.minX(), it.minY(), it.maxX(), it.maxY()) }
        gaps.forEach { g ->
            if (g.axis == Axis.HORIZONTAL) fill(g.from, g.low, g.to, g.high) else fill(g.low, g.from, g.high, g.to)
        }
        return mask
    }
}
