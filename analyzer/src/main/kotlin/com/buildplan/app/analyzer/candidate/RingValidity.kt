package com.buildplan.app.analyzer.candidate

import kotlin.math.abs
import kotlin.math.max

/**
 * Why a traced ring is, or is not, a polygon a quantity may be computed from.
 *
 * A ring that crosses itself still has a shoelace area and still answers
 * `contains`, and both answers are wrong in ways nothing downstream can
 * notice: the even-odd rule silently discards the doubled lobe, so a room
 * whose floor measures 14.65 m2 on the raster reports a 4.2 m2 ceiling. The
 * verdict exists so that "invalid" is a state the pipeline carries rather
 * than a number it prints.
 */
enum class RingVerdict {
    VALID_SIMPLE_RING,
    TOO_FEW_VERTICES,
    NON_FINITE_VERTEX,
    DEGENERATE_AREA,
    ZERO_LENGTH_EDGE,
    SPIKE,
    SELF_INTERSECTING,
}

data class RingCheck(val verdict: RingVerdict, val detail: String) {
    val isValid: Boolean get() = verdict == RingVerdict.VALID_SIMPLE_RING
}

/**
 * The outcome of a deterministic repair attempt: the ring that survived (or
 * null when none did), what was done to it, and how much area the repair
 * moved, so a caller can refuse a repair that changed the room rather than
 * tidied it.
 */
data class RingRepair(
    val ring: List<Pt>?,
    val check: RingCheck,
    val originalArea: Double,
    val repairedArea: Double,
    val steps: List<String>,
) {
    /** Share of the original area the repaired ring keeps, or 0 when nothing survived. */
    val areaRetained: Double get() = if (originalArea <= 1e-12) 0.0 else repairedArea / originalArea
}

/**
 * Simple-ring validation and deterministic repair for traced plan geometry.
 *
 * The invariant this enforces is the one the rest of the analyzer assumes
 * everywhere and never checked: **an emitted ring is a simple closed
 * polygon**. Rings reach here from a crack-following raster trace, so the
 * failures are the ones a raster trace produces — a pinch where two lobes of
 * a region meet at one lattice corner, a spike left by jog smoothing, a
 * zero-length edge from a duplicated vertex — not the arbitrary tangles of
 * hand-drawn input.
 *
 * Repair only ever *removes*: a spike is dropped, and a pinched ring is cut
 * at the pinch and reduced to its largest loop. Nothing is ever bridged,
 * merged or extended here, so a repair cannot connect two regions that the
 * source draws apart. What repair costs is reported as [RingRepair.areaRetained]
 * and it is the caller's job to reject a repair that lost the room.
 */
object RingValidity {

    /** Edges shorter than this are a duplicated vertex, not a wall: half a pixel at any plan scale. */
    const val MIN_EDGE_M = 1e-4

    /** A ring enclosing less than this is a trace artefact rather than a space. */
    const val MIN_AREA_M2 = 1e-6

    /** Orientation tolerance in m2; a cross product under it is collinear at plan scale. */
    private const val CROSS_EPS = 1e-10

    fun check(vertices: List<Pt>, minEdge: Double = MIN_EDGE_M, minArea: Double = MIN_AREA_M2): RingCheck {
        if (vertices.any { !it.x.isFinite() || !it.z.isFinite() }) {
            return RingCheck(RingVerdict.NON_FINITE_VERTEX, "a vertex is not finite")
        }
        if (vertices.size < 3) {
            return RingCheck(RingVerdict.TOO_FEW_VERTICES, "${vertices.size} vertices, at least 3 needed")
        }
        val n = vertices.size
        for (i in 0 until n) {
            val a = vertices[i]
            val b = vertices[(i + 1) % n]
            if (a.distanceTo(b) < minEdge) {
                return RingCheck(RingVerdict.ZERO_LENGTH_EDGE, "edge $i is ${a.distanceTo(b)} m long")
            }
        }
        // Shape before size. A symmetric bow tie has a shoelace area of exactly zero, so a
        // degeneracy test placed first would report "no area" for a ring whose real problem is
        // that it crosses itself — true, but useless to whoever has to fix the trace.
        // Adjacent edges may only meet at the vertex they share. Collinear and doubling back
        // over each other is a spike: zero width, and it makes `contains` answer nonsense.
        for (i in 0 until n) {
            val a = vertices[i]
            val b = vertices[(i + 1) % n]
            val c = vertices[(i + 2) % n]
            if (orientation(a, b, c) == 0 && (a.x - b.x) * (c.x - b.x) + (a.z - b.z) * (c.z - b.z) > 0) {
                return RingCheck(RingVerdict.SPIKE, "vertex ${(i + 1) % n} doubles back along the previous edge")
            }
        }
        // Non-adjacent edges may not meet at all — touching is enough to break the ring, because
        // a ring that touches itself has two lobes and the even-odd rule keeps only one of them.
        for (i in 0 until n) {
            val a = vertices[i]
            val b = vertices[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) continue
                val c = vertices[j]
                val d = vertices[(j + 1) % n]
                if (segmentsIntersect(a, b, c, d)) {
                    return RingCheck(RingVerdict.SELF_INTERSECTING, "edge $i meets edge $j")
                }
            }
        }
        val area = abs(signedArea(vertices))
        if (area < minArea) {
            return RingCheck(RingVerdict.DEGENERATE_AREA, "encloses $area m2")
        }
        return RingCheck(RingVerdict.VALID_SIMPLE_RING, "simple ring, ${vertices.size} vertices, $area m2")
    }

    /**
     * Removes what a raster trace adds, in a fixed order, and stops as soon as
     * the ring is simple: duplicate vertices, then spikes, then — only if the
     * ring still crosses itself — the smaller loops of a pinched ring.
     */
    fun repair(vertices: List<Pt>, minEdge: Double = MIN_EDGE_M, minArea: Double = MIN_AREA_M2): RingRepair {
        val steps = mutableListOf<String>()
        val originalArea = if (vertices.size >= 3) abs(signedArea(vertices)) else 0.0

        var ring = dropShortEdges(vertices, minEdge)
        if (ring.size != vertices.size) steps += "dropped ${vertices.size - ring.size} duplicate or zero-length vertices"
        var verdict = check(ring, minEdge, minArea)
        if (verdict.isValid) return RingRepair(ring, verdict, originalArea, abs(signedArea(ring)), steps)

        if (verdict.verdict == RingVerdict.SPIKE) {
            val before = ring.size
            ring = dropSpikes(ring, minEdge)
            if (ring.size != before) steps += "dropped ${before - ring.size} spike vertices"
            verdict = check(ring, minEdge, minArea)
            if (verdict.isValid) return RingRepair(ring, verdict, originalArea, abs(signedArea(ring)), steps)
        }

        if (verdict.verdict == RingVerdict.SELF_INTERSECTING) {
            val loop = largestPinchLoop(ring, minEdge, minArea)
            if (loop != null) {
                steps += "cut the ring at its pinch points and kept the largest loop"
                val loopVerdict = check(loop, minEdge, minArea)
                return RingRepair(
                    if (loopVerdict.isValid) loop else null,
                    loopVerdict,
                    originalArea,
                    if (loopVerdict.isValid) abs(signedArea(loop)) else 0.0,
                    steps,
                )
            }
        }
        return RingRepair(null, verdict, originalArea, 0.0, steps)
    }

    /** Counter-clockwise in a Y-up frame, for one stable winding on every emitted ring. */
    fun normaliseWinding(vertices: List<Pt>): List<Pt> = if (signedArea(vertices) < 0) vertices.reversed() else vertices

    fun signedArea(vertices: List<Pt>): Double = 0.5 * vertices.indices.sumOf { i ->
        val a = vertices[i]
        val b = vertices[(i + 1) % vertices.size]
        a.x * b.z - b.x * a.z
    }

    private fun dropShortEdges(vertices: List<Pt>, minEdge: Double): List<Pt> {
        if (vertices.size < 3) return vertices
        val out = mutableListOf<Pt>()
        for (p in vertices) {
            if (out.isEmpty() || out.last().distanceTo(p) >= minEdge) out += p
        }
        while (out.size >= 2 && out.first().distanceTo(out.last()) < minEdge) out.removeAt(out.size - 1)
        return out
    }

    private fun dropSpikes(vertices: List<Pt>, minEdge: Double): List<Pt> {
        var current = vertices.toMutableList()
        var changed = true
        while (changed && current.size > 3) {
            changed = false
            val n = current.size
            for (i in 0 until n) {
                val a = current[(i - 1 + n) % n]
                val b = current[i]
                val c = current[(i + 1) % n]
                if (orientation(a, b, c) == 0 && (a.x - b.x) * (c.x - b.x) + (a.z - b.z) * (c.z - b.z) > 0) {
                    current.removeAt(i)
                    current = dropShortEdges(current, minEdge).toMutableList()
                    changed = true
                    break
                }
            }
        }
        return current
    }

    /**
     * A crack-following trace of a region whose lobes meet at a single lattice
     * corner emits one ring that visits that corner twice. Cutting the ring at
     * the repeat yields the lobes as separate loops; the largest is the room
     * and the rest is what the pinch attached to it.
     *
     * Deterministic: the first repeated vertex in traversal order is the cut,
     * and loops are compared by absolute area with the vertex list as a
     * tie-break, so the same ring always reduces to the same loop.
     */
    private fun largestPinchLoop(vertices: List<Pt>, minEdge: Double, minArea: Double): List<Pt>? {
        val loops = mutableListOf<List<Pt>>()
        val stack = mutableListOf<Pt>()
        for (p in vertices) {
            val repeat = stack.indexOfFirst { it.distanceTo(p) < minEdge }
            if (repeat >= 0) {
                val loop = stack.subList(repeat, stack.size).toList()
                if (loop.size >= 3) loops += loop
                while (stack.size > repeat) stack.removeAt(stack.size - 1)
            }
            stack += p
        }
        if (stack.size >= 3) loops += stack.toList()
        val best = loops
            .map { dropShortEdges(it, minEdge) }
            .filter { it.size >= 3 && abs(signedArea(it)) >= minArea }
            .maxWithOrNull(compareBy({ abs(signedArea(it)) }, { it.size }))
        return best
    }

    /** 1 counter-clockwise, -1 clockwise, 0 collinear, in the screen frame (X right, Z down). */
    private fun orientation(a: Pt, b: Pt, c: Pt): Int {
        val v = (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)
        return if (v > CROSS_EPS) 1 else if (v < -CROSS_EPS) -1 else 0
    }

    private fun onSegment(a: Pt, b: Pt, p: Pt): Boolean =
        p.x >= minOf(a.x, b.x) - CROSS_EPS && p.x <= max(a.x, b.x) + CROSS_EPS &&
            p.z >= minOf(a.z, b.z) - CROSS_EPS && p.z <= max(a.z, b.z) + CROSS_EPS

    /** Any shared point at all, proper crossings and collinear overlaps alike. */
    fun segmentsIntersect(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(a, b, c)) return true
        if (o2 == 0 && onSegment(a, b, d)) return true
        if (o3 == 0 && onSegment(c, d, a)) return true
        if (o4 == 0 && onSegment(c, d, b)) return true
        return false
    }
}

/** Whether a room's plan geometry is usable as a polygon, or is only a raster region. */
enum class RoomGeometryState {
    /** A simple closed ring whose area agrees with the pixels it was traced from. */
    VALID_SIMPLE_RING,

    /** No ring survived validation, or the ring that did covers the wrong pixels. */
    UNRESOLVED_REGION,
}
