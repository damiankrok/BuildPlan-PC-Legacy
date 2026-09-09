package com.buildplan.app.analyzer.roof

import com.buildplan.app.analyzer.candidate.Pt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The straight skeleton of a rectilinear polygon whose edges move inward at
 * per-edge speeds, and the roof facets it defines.
 *
 * ## Why a wavefront and not a formula
 *
 * A gable roof over a rectangle is two planes; a hip roof over a rectangle
 * is four; a hip roof over an L-shaped or T-shaped footprint is a system of
 * ridges, hips and valleys that no closed formula gives. All of them are the
 * same construction: every eave edge sweeps inward at the roof's slope, and
 * the surface a sweeping edge leaves behind is its facet. Edges that do not
 * sweep — gable ends, where the wall rises vertically — have speed zero.
 *
 * ## Why rectilinear only
 *
 * With axis-aligned edges every offset polygon stays rectilinear, vertex
 * velocities are sums of unit normals, and the only events are an edge
 * shrinking to nothing and a reflex vertex running into an opposite edge.
 * That is a few hundred lines that can be reasoned about; a general straight
 * skeleton is a research library. Plan outlines from a raster are
 * rectilinear by construction, and an oblique bay is reported as such.
 *
 * ## Output
 *
 * [Result.arcs] are the skeleton segments (ridges, hips, valleys) plus the
 * original edges, each with the sweep time at both ends; time is horizontal
 * distance swept, so height is `eave + time * tan(pitch)`. [Result.facets]
 * are the closed polygons per original edge, assembled from those arcs.
 */
object RectilinearSkeleton {

    /** An original polygon edge, by its index in the input. */
    data class EdgeRef(val index: Int)

    /**
     * One skeleton segment with sweep times at its ends and the two facets it
     * separates. [outside] is true for an original eave edge, whose other side
     * is the outdoors.
     */
    data class Arc(val a: Pt, val ta: Double, val b: Pt, val tb: Double, val left: Int, val right: Int, val outside: Boolean) {
        val isRidgeLike: Boolean get() = !outside && abs(ta - tb) < EPS && ta > EPS
    }

    data class Facet(val edge: Int, val ring: List<Pt>, val times: List<Double>)

    data class Result(val arcs: List<Arc>, val facets: List<Facet>, val maxTime: Double, val complete: Boolean, val note: String?)

    private const val EPS = 1e-7

    private class Vertex(var p: Pt, val prevEdge: Int, val nextEdge: Int, var born: Double)
    private class Edge(val original: Int, val nx: Double, val nz: Double, val speed: Double, var c: Double)
    private class Poly(val vertices: MutableList<Vertex>, val edges: MutableList<Edge>)

    /**
     * @param polygon rectilinear, any winding, implicitly closed, no repeated points.
     * @param speeds per edge (edge i runs from vertex i to i+1): 1.0 for a sloping
     *   eave, 0.0 for a gable end. Values between scale the slope of that facet.
     */
    fun solve(polygon: List<Pt>, speeds: List<Double>): Result {
        require(polygon.size >= 4) { "rectilinear polygon needs at least 4 vertices" }
        require(speeds.size == polygon.size) { "one speed per edge" }
        val ring = if (signedArea(polygon) < 0) polygon.reversed() else polygon
        val speedRing = if (signedArea(polygon) < 0) speeds.reversed().let { rev -> rev.drop(1) + rev.first() }.let { it } else speeds
        // After reversing vertices, edge i of the new ring is old edge (n-1-i) reversed; re-align speeds.
        val n = ring.size
        val alignedSpeeds = if (signedArea(polygon) < 0) List(n) { i -> speeds[(n - 1 - i + n) % n] } else speeds
        if (speedRing.isEmpty()) return Result(emptyList(), emptyList(), 0.0, false, "empty")

        // Build edges with inward normals. Ring is CCW in a Y-up frame; on screen (Z down) that
        // means the interior is on the left of each edge when Z points down... We avoid the
        // convention trap by testing which side the centroid is on.
        val edges = ArrayList<Edge>(n)
        val centroid = centroidOf(ring)
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            val dx = b.x - a.x
            val dz = b.z - a.z
            require(abs(dx) < EPS || abs(dz) < EPS) { "edge $i is not axis-aligned: $a -> $b" }
            var nx = -dz
            var nz = dx
            val len = max(abs(nx), abs(nz))
            nx /= len
            nz /= len
            // Point the normal towards the interior: the midpoint offset by the normal must be
            // closer to being inside; use the centroid side test on this edge's line.
            val mid = Pt((a.x + b.x) / 2, (a.z + b.z) / 2)
            val toCentroid = (centroid.x - mid.x) * nx + (centroid.z - mid.z) * nz
            if (toCentroid < 0) { nx = -nx; nz = -nz }
            edges += Edge(i, nx, nz, alignedSpeeds[i], nx * a.x + nz * a.z)
        }
        // Verify: for a non-convex polygon the centroid test can fail on some edges; correct by
        // requiring consistency with winding: interior is on the same side for all edges.
        fixNormalsByWinding(ring, edges)

        val vertices = ArrayList<Vertex>(n)
        for (i in 0 until n) vertices += Vertex(ring[i], (i - 1 + n) % n, i, 0.0)

        val arcs = mutableListOf<Arc>()
        // Original edges as outside arcs.
        for (i in 0 until n) arcs += Arc(ring[i], 0.0, ring[(i + 1) % n], 0.0, i, -1, outside = true)

        val active = ArrayDeque<Poly>()
        active += Poly(vertices, edges)
        var time = 0.0
        var maxTime = 0.0
        var steps = 0
        var complete = true
        var note: String? = null
        while (active.isNotEmpty() && steps < 10_000) {
            steps++
            val poly = active.removeFirst()
            val result = advance(poly, arcs)
            when (result) {
                is Step.Finished -> { maxTime = max(maxTime, result.time) }
                is Step.Split -> { active += result.a; active += result.b }
                is Step.Continue -> active += poly
                is Step.Stuck -> { complete = false; note = result.reason }
            }
            time = max(time, result.time)
        }
        if (steps >= 10_000) { complete = false; note = "event loop did not terminate" }

        val facets = assembleFacets(arcs, n, alignedSpeeds)
        return Result(arcs, facets, max(maxTime, time), complete, note)
    }

    private sealed class Step(val time: Double) {
        class Continue(t: Double) : Step(t)
        class Finished(t: Double) : Step(t)
        class Split(t: Double, val a: Poly, val b: Poly) : Step(t)
        class Stuck(t: Double, val reason: String) : Step(t)
    }

    /** Vertex velocity: sum of the adjacent edges' speed-scaled normals. */
    private fun velocity(v: Vertex, edges: List<Edge>): Pt {
        val e0 = edges[v.prevEdge]
        val e1 = edges[v.nextEdge]
        return Pt(e0.speed * e0.nx + e1.speed * e1.nx, e0.speed * e0.nz + e1.speed * e1.nz)
    }

    private fun currentTime(poly: Poly): Double = poly.vertices.maxOf { it.born }

    /** Positions at time t, from each vertex's birth position and velocity. */
    private fun positionAt(v: Vertex, edges: List<Edge>, t: Double): Pt {
        val vel = velocity(v, edges)
        return Pt(v.p.x + vel.x * (t - v.born), v.p.z + vel.z * (t - v.born))
    }

    private fun advance(poly: Poly, arcs: MutableList<Arc>): Step {
        val vs = poly.vertices
        val es = poly.edges
        val t0 = currentTime(poly)
        val n = vs.size
        if (n <= 2) {
            // A polygon reduced to two vertices by a split is already a ridge between them.
            if (n == 2) {
                val u = vs[0]
                val w = vs[1]
                if (abs(u.p.x - w.p.x) > EPS || abs(u.p.z - w.p.z) > EPS) {
                    arcs += Arc(u.p, t0, w.p, t0, es[u.prevEdge].original, es[u.nextEdge].original, outside = false)
                }
            }
            return Step.Finished(t0)
        }

        // Earliest edge collapse.
        var bestT = Double.POSITIVE_INFINITY
        var bestKind = 0
        var bestIndex = -1
        var bestTarget = -1
        for (i in 0 until n) {
            val a = vs[i]
            val b = vs[(i + 1) % n]
            val e = es[a.nextEdge]
            val pa = positionAt(a, es, t0)
            val pb = positionAt(b, es, t0)
            val dx = pb.x - pa.x
            val dz = pb.z - pa.z
            val len = max(abs(dx), abs(dz))
            if (len < EPS) { bestT = t0; bestKind = 1; bestIndex = i; break }
            val ux = dx / len
            val uz = dz / len
            val va = velocity(a, es)
            val vb = velocity(b, es)
            val rate = (vb.x - va.x) * ux + (vb.z - va.z) * uz
            if (rate < -EPS) {
                val t = t0 + len / -rate
                if (t < bestT - EPS) { bestT = t; bestKind = 1; bestIndex = i }
            }
        }
        // Earliest split: a reflex vertex reaching a non-adjacent edge.
        for (j in 0 until n) {
            val v = vs[j]
            if (!isReflex(v, es)) continue
            val vel = velocity(v, es)
            val p0 = positionAt(v, es, t0)
            for (k in 0 until n) {
                val e = es[vs[k].nextEdge]
                if (vs[k].nextEdge == v.prevEdge || vs[k].nextEdge == v.nextEdge) continue
                val denom = e.nx * vel.x + e.nz * vel.z - e.speed
                if (abs(denom) < EPS) continue
                val t = (e.c + e.speed * t0 - (e.nx * p0.x + e.nz * p0.z)) / denom + t0
                // A split at the current time is allowed: simultaneous events are processed one
                // at a time, and a reflex vertex that already sits on the opposite edge must split now.
                if (t < t0 - EPS || t >= bestT - EPS) continue
                // The hit point must lie within the edge's extent at time t.
                val hit = positionAt(v, es, t)
                val ea = positionAt(vs[k], es, t)
                val eb = positionAt(vs[(k + 1) % n], es, t)
                val within = if (abs(e.nx) > 0.5) hit.z in (min(ea.z, eb.z) - 1e-6)..(max(ea.z, eb.z) + 1e-6)
                else hit.x in (min(ea.x, eb.x) - 1e-6)..(max(ea.x, eb.x) + 1e-6)
                if (!within) continue
                bestT = t; bestKind = 2; bestIndex = j; bestTarget = k
            }
        }
        if (bestIndex < 0) return Step.Stuck(t0, "no further event on a polygon with $n vertices")

        // Move every vertex to bestT, recording its arc.
        val t = bestT
        for (v in vs) {
            val p = positionAt(v, es, t)
            if (t - v.born > EPS && (abs(p.x - v.p.x) > EPS || abs(p.z - v.p.z) > EPS)) {
                arcs += Arc(v.p, v.born, p, t, es[v.prevEdge].original, es[v.nextEdge].original, outside = false)
            }
            v.p = p
            v.born = t
        }
        // Shift edge offsets to time t.
        // (c is the offset at time 0; positions are absolute, so nothing to shift.)

        if (bestKind == 1) {
            // Edge between vs[i] and vs[i+1] collapses: merge the two vertices.
            val i = bestIndex
            val a = vs[i]
            val b = vs[(i + 1) % n]
            val merged = Vertex(a.p, a.prevEdge, b.nextEdge, t)
            vs.remove(a)
            vs.remove(b)
            if (vs.isEmpty()) return Step.Finished(t)
            vs.add(min(i, vs.size), merged)
            if (vs.size == 2) {
                val u = vs[0]
                val w = vs[1]
                // Two remaining vertices joined by two coincident edges: a ridge.
                if (abs(u.p.x - w.p.x) > EPS || abs(u.p.z - w.p.z) > EPS) {
                    arcs += Arc(u.p, t, w.p, t, es[u.prevEdge].original, es[u.nextEdge].original, outside = false)
                }
                return Step.Finished(t)
            }
            // Adjacent collinear edges of one original line merge naturally: nothing to do,
            // because the merged vertex simply joins prevEdge and nextEdge; if those two are
            // parallel and same-direction the vertex is a degenerate straight joint that later
            // events handle (its velocity is the common normal).
            return Step.Continue(t)
        }

        // Split: reflex vertex j on edge k.
        val j = bestIndex
        val k = bestTarget
        val v = vs[j]
        val edgeK = es[vs[k].nextEdge]
        // Polygon A: from v (as new vertex joining edgeK and v.nextEdge) forward to vs[k].
        val idxJ = j
        val idxK = k
        val orderA = mutableListOf<Vertex>()
        var idx = idxJ
        val vA = Vertex(v.p, vs[k].nextEdge, v.nextEdge, t)
        orderA += vA
        idx = (idxJ + 1) % n
        while (idx != (idxK + 1) % n) {
            orderA += vs[idx]
            idx = (idx + 1) % n
        }
        // Polygon B: from vs[k+1] forward to v (as new vertex joining v.prevEdge and edgeK).
        val orderB = mutableListOf<Vertex>()
        idx = (idxK + 1) % n
        while (idx != idxJ) {
            orderB += vs[idx]
            idx = (idx + 1) % n
        }
        val vB = Vertex(v.p, v.prevEdge, vs[k].nextEdge, t)
        orderB += vB
        // Both polygons share the edge list (edge objects are immutable in geometry; indices stay valid).
        val polyA = Poly(orderA, es)
        val polyB = Poly(orderB, es)
        return Step.Split(t, polyA, polyB)
    }

    private fun isReflex(v: Vertex, edges: List<Edge>): Boolean {
        // Reflex when the two adjacent inward normals point "away" such that the vertex moves
        // outward-diagonally relative to the corner: the interior angle is 270 degrees when the
        // cross of (prev edge direction, next edge direction) has the sign opposite to convex.
        val e0 = edges[v.prevEdge]
        val e1 = edges[v.nextEdge]
        // Edge directions are the normals rotated: d = (nz, -nx) for a CCW ring... compute from
        // normals: convex corner has n0 x n1 > 0 for a CCW ring with interior on the left.
        val cross = e0.nx * e1.nz - e0.nz * e1.nx
        return cross < -EPS
    }

    private fun fixNormalsByWinding(ring: List<Pt>, edges: MutableList<Edge>) {
        // For a CCW ring (positive signed area in the X-right/Z-up sense), the interior lies to
        // the left of each directed edge: left normal = (-dz, dx). Our signedArea convention
        // treats (x, z) like (x, y); enforce left normals for positive area.
        val n = ring.size
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            val dx = b.x - a.x
            val dz = b.z - a.z
            val len = max(abs(dx), abs(dz))
            val lx = -dz / len
            val lz = dx / len
            val e = edges[i]
            edges[i] = Edge(e.original, lx, lz, e.speed, lx * a.x + lz * a.z)
        }
    }

    private fun signedArea(p: List<Pt>): Double =
        0.5 * p.indices.sumOf { i -> val a = p[i]; val b = p[(i + 1) % p.size]; a.x * b.z - b.x * a.z }

    private fun centroidOf(p: List<Pt>): Pt = Pt(p.sumOf { it.x } / p.size, p.sumOf { it.z } / p.size)

    /**
     * Facet polygons: for each original edge, the cycle of arcs bounding it,
     * linked by shared endpoints.
     */
    private fun assembleFacets(arcs: List<Arc>, edgeCount: Int, speeds: List<Double>): List<Facet> {
        val facets = mutableListOf<Facet>()
        for (e in 0 until edgeCount) {
            // A gable end does not sweep: it is a vertical wall, not a facet.
            if (speeds[e] <= EPS) continue
            val mine = arcs.filter { it.left == e || it.right == e }
            if (mine.isEmpty()) continue
            // Start from the original edge arc, walk to matching endpoints.
            val start = mine.firstOrNull { it.outside } ?: mine.first()
            val ring = mutableListOf<Pt>()
            val times = mutableListOf<Double>()
            val used = BooleanArray(mine.size)
            var current = start.b
            var currentT = start.tb
            ring += start.a; times += start.ta
            ring += start.b; times += start.tb
            used[mine.indexOf(start)] = true
            var guard = 0
            while (guard++ < mine.size + 2) {
                var found = false
                for ((idx, arc) in mine.withIndex()) {
                    if (used[idx]) continue
                    val next = when {
                        near(arc.a, current) -> arc.b to arc.tb
                        near(arc.b, current) -> arc.a to arc.ta
                        else -> null
                    } ?: continue
                    used[idx] = true
                    current = next.first
                    currentT = next.second
                    found = true
                    if (near(current, start.a)) break
                    ring += current; times += currentT
                    break
                }
                if (!found || near(current, start.a)) break
            }
            if (ring.size >= 3) facets += Facet(e, dedupe(ring, times).first, dedupe(ring, times).second)
        }
        return facets
    }

    private fun dedupe(ring: List<Pt>, times: List<Double>): Pair<List<Pt>, List<Double>> {
        val outP = mutableListOf<Pt>()
        val outT = mutableListOf<Double>()
        ring.forEachIndexed { i, p -> if (outP.isEmpty() || !near(outP.last(), p)) { outP += p; outT += times[i] } }
        if (outP.size > 1 && near(outP.first(), outP.last())) { outP.removeAt(outP.size - 1); outT.removeAt(outT.size - 1) }
        return outP to outT
    }

    private fun near(a: Pt, b: Pt): Boolean = abs(a.x - b.x) < 1e-6 && abs(a.z - b.z) < 1e-6
}
