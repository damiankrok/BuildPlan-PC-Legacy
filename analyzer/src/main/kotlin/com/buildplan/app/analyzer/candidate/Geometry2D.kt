package com.buildplan.app.analyzer.candidate

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The analyzer's own plane geometry, in metres.
 *
 * Deliberately not the app's `geometry/` types. Candidate geometry is an
 * intermediate product that a later, verified step maps onto the canonical
 * contract; importing that contract here would invite the shortcut of
 * writing candidates straight into it. The frame matches it anyway so the
 * mapping is a rename, not a transform: X east, Z south (down the page),
 * Y up, metres.
 */
data class Pt(val x: Double, val z: Double) {
    init {
        require(x.isFinite() && z.isFinite()) { "Pt must be finite, was ($x, $z)" }
    }

    fun distanceTo(other: Pt): Double = hypot(x - other.x, z - other.z)
    operator fun plus(o: Pt) = Pt(x + o.x, z + o.z)
    operator fun minus(o: Pt) = Pt(x - o.x, z - o.z)
    operator fun times(k: Double) = Pt(x * k, z * k)
}

/** An axis-aligned rectangle in plan metres, min-inclusive. */
data class Box(val minX: Double, val minZ: Double, val maxX: Double, val maxZ: Double) {
    init {
        require(minX <= maxX && minZ <= maxZ) { "Box min must not exceed max: $this" }
    }

    val width: Double get() = maxX - minX
    val depth: Double get() = maxZ - minZ
    val area: Double get() = width * depth
    val center: Pt get() = Pt((minX + maxX) / 2, (minZ + maxZ) / 2)

    fun contains(p: Pt): Boolean = p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ
    fun intersects(o: Box): Boolean = minX < o.maxX && o.minX < maxX && minZ < o.maxZ && o.minZ < maxZ
    fun union(o: Box) = Box(min(minX, o.minX), min(minZ, o.minZ), max(maxX, o.maxX), max(maxZ, o.maxZ))
    fun inflate(d: Double) = Box(minX - d, minZ - d, maxX + d, maxZ + d)

    companion object {
        fun around(points: List<Pt>): Box {
            require(points.isNotEmpty()) { "Box needs points" }
            return Box(points.minOf { it.x }, points.minOf { it.z }, points.maxOf { it.x }, points.maxOf { it.z })
        }
    }
}

/** A simple polygon in plan metres, vertices in order, implicitly closed. */
data class Polygon(val vertices: List<Pt>) {
    init {
        require(vertices.size >= 3) { "Polygon needs at least 3 vertices, got ${vertices.size}" }
    }

    /** Signed shoelace area: positive when the vertices run clockwise on screen (X right, Z down). */
    val signedArea: Double
        get() = 0.5 * vertices.indices.sumOf { i ->
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            a.x * b.z - b.x * a.z
        }

    val area: Double get() = abs(signedArea)

    val perimeter: Double get() = edges.sumOf { it.length }

    val bounds: Box get() = Box.around(vertices)

    val edges: List<Segment>
        get() = vertices.indices.map { i -> Segment(vertices[i], vertices[(i + 1) % vertices.size]) }

    val centroid: Pt
        get() {
            val a = signedArea
            if (abs(a) < 1e-12) return bounds.center
            var cx = 0.0
            var cz = 0.0
            vertices.indices.forEach { i ->
                val p = vertices[i]
                val q = vertices[(i + 1) % vertices.size]
                val cross = p.x * q.z - q.x * p.z
                cx += (p.x + q.x) * cross
                cz += (p.z + q.z) * cross
            }
            return Pt(cx / (6 * a), cz / (6 * a))
        }

    /** Even-odd point-in-polygon. */
    fun contains(p: Pt): Boolean {
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[j]
            if ((a.z > p.z) != (b.z > p.z) && p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** The same polygon with consecutive collinear vertices removed and duplicates dropped. */
    fun simplified(tolerance: Double = 1e-6): Polygon {
        val distinct = vertices.filterIndexed { i, p -> i == 0 || p.distanceTo(vertices[i - 1]) > tolerance }
            .let { if (it.size > 1 && it.first().distanceTo(it.last()) <= tolerance) it.dropLast(1) else it }
        if (distinct.size < 3) return this
        val kept = distinct.filterIndexed { i, p ->
            val prev = distinct[(i - 1 + distinct.size) % distinct.size]
            val next = distinct[(i + 1) % distinct.size]
            val cross = (p.x - prev.x) * (next.z - prev.z) - (p.z - prev.z) * (next.x - prev.x)
            abs(cross) > tolerance
        }
        return if (kept.size >= 3) Polygon(kept) else this
    }

    /** Vertices reordered to run counter-clockwise in a Y-up frame (clockwise on screen), for a stable convention. */
    fun normalisedWinding(): Polygon = if (signedArea < 0) Polygon(vertices.reversed()) else this
}

data class Segment(val a: Pt, val b: Pt) {
    val length: Double get() = a.distanceTo(b)
    val midpoint: Pt get() = Pt((a.x + b.x) / 2, (a.z + b.z) / 2)
    val isHorizontal: Boolean get() = abs(a.z - b.z) <= abs(a.x - b.x)
    val bounds: Box get() = Box.around(listOf(a, b))
}
