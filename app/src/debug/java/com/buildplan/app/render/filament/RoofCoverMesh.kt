package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.GeometryTolerance
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.presentation.RoofCoverBlocker
import com.buildplan.app.presentation.RoofCoverProfile
import com.buildplan.app.presentation.RoofCoverSpec
import com.buildplan.app.presentation.RoofCoverStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The covering of one roof facet, baked into one batch of triangles.
 *
 * ## One mesh, many tiles, one id
 *
 * Every tile of the facet is in this one buffer, in a fixed order, and the
 * whole batch answers with the facet's [elementId] — the roof's. There is no
 * tile entity, no tile id and no tile pick: a tap on a tile is a tap on the
 * roof, and hiding the roof hides its covering through the same id set that
 * hides its planes. That is the whole reason the covering is a mesh per facet
 * rather than a renderable per tile — two thousand entities would be two
 * thousand things the visibility path had to know about.
 *
 * ## What it is not
 *
 * Not geometry. `geometry/` keeps the facet's plane and area, and this batch
 * does not change either: it is drawn a centimetre or two *above* the plane,
 * derived from it, and thrown away with it. Not a [BuildingRenderMesh] either:
 * it carries no feature edges on purpose. A covering is read from its relief
 * and its shading; outlining every tile would put two thousand quads of ink
 * over a roof that STAGE-013E just cleaned of seams.
 *
 * ## The animation seam
 *
 * [tiles] and [signals] are what a later stage that wants the courses to move
 * needs and nothing more: each tile's deterministic row, column, ordinal and
 * phase, and per vertex the same phase plus where along the tile the vertex
 * sits. A vertex shader can displace on those without any tile becoming an
 * entity; nothing in this stage reads them.
 */
class RoofCoverMesh internal constructor(
    val elementId: BuildingElementId,
    /** Which of the element's facets this covers, in the geometry's own order. */
    val facetOrdinal: Int,
    val style: RoofCoverStyle,
    /** Flat `xyz` triples, in the building's local metres. */
    val positions: FloatArray,
    /** Flat `xyz` unit normals, one per vertex, following the tile's curve. */
    val normals: FloatArray,
    /**
     * Two floats per vertex: the owning tile's phase in `[0, 1)`, and how far
     * along the tile the vertex sits — `0` at the tail, `1` at the head.
     */
    val signals: FloatArray,
    val indices: IntArray,
    val tiles: List<RoofCoverTile>,
    /** The facet's own frame: across the slope, up the slope, and its normal. */
    val across: FloatArray,
    val upSlope: FloatArray,
    val normal: FloatArray,
    val boundsCenter: FloatArray,
    val boundsHalfExtent: FloatArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val tileCount: Int get() = tiles.size
}

/**
 * One tile's presentation identity: where it is in its course and on its
 * facet, deterministically, so that it can be found again.
 *
 * Presentation identity, not semantic identity. A tile has no
 * [BuildingElementId], no name and no cost; [ordinal] is its position in the
 * batch, and [row] and [column] are its course and its place in it. They are
 * unique within one facet's covering and stable between runs.
 */
data class RoofCoverTile(
    val facetOrdinal: Int,
    /** Course index, `0` at the eave. */
    val row: Int,
    /** Position across the course, `0` at the facet's low-`s` edge. */
    val column: Int,
    /** Position in the batch, and therefore in the vertex buffer. */
    val ordinal: Int,
    /** The tile's centre on the roof plane, before any offset. */
    val center: ModelPoint,
    /** A deterministic seed in `[0, 1)`, for a future phase. */
    val phase: Float,
    /** Index of the tile's first vertex in the batch. */
    val firstVertex: Int,
)

/**
 * Lays a covering over planar facets, one facet at a time.
 *
 * ## Facet-generic
 *
 * Nothing here knows a gable, a ridge direction or a house. The generator is
 * given one planar polygon and works out its own frame: the normal, the
 * direction straight up the slope, and the direction across it. Courses run
 * across, from the facet's lowest edge to its highest, and every tile is
 * trimmed to the polygon's edge on its own course. A hip facet, a shed roof and
 * the second slope of a gable are the same problem with a different polygon.
 *
 * Curved facets are not a problem this solves: the input is a
 * [RoofFacetGeometry], which is planar by contract.
 *
 * ## The tile
 *
 * [VERTICES_ACROSS] vertices at the tail and the same at the head: a shallow
 * barrel across its width, a rounded tail, and a tilt along its length so the
 * tail stands higher than the head and rests, visually, on the course below.
 * Eight triangles. Nothing underneath: the tile is a sheet seen from above,
 * and the plane it lies on is drawn by the facet itself.
 *
 * Courses are laid on a gauge that divides the slope exactly, so the top
 * course ends at the ridge rather than a sliver short of it; tiles within a
 * course are aligned, not staggered, so the rolls run straight down the slope
 * and a tile's edge never has to clear the crest of the tile beneath it.
 */
object RoofCoverGenerator {

    /** Vertices across one tile: four segments of barrel. */
    const val VERTICES_ACROSS: Int = 5

    /** Vertex rows along one tile: the tail and the head. */
    const val VERTICES_ALONG: Int = 2

    const val VERTICES_PER_TILE: Int = VERTICES_ACROSS * VERTICES_ALONG

    /** Two triangles per segment of barrel. */
    const val TRIANGLES_PER_TILE: Int = (VERTICES_ACROSS - 1) * 2

    /**
     * The covering of [facet], or null when the facet has no usable plane or
     * no tile survives trimming — a facet too small for one tile is drawn as
     * its own plane and nothing else.
     */
    fun cover(
        facet: RoofFacetGeometry,
        facetOrdinal: Int,
        spec: RoofCoverSpec,
        blockers: List<RoofCoverBlocker> = emptyList(),
    ): RoofCoverMesh? {
        // Exhaustive on purpose: a second style has to be laid here before it
        // can be named anywhere else.
        when (spec.style) {
            RoofCoverStyle.CURVED_TILE -> Unit
        }
        val frame = FacetFrame.of(facet.vertices) ?: return null
        val outline = frame.outline
        val tMin = outline.minOf { it.t }
        val tMax = outline.maxOf { it.t }
        val sMin = outline.minOf { it.s }
        val sMax = outline.maxOf { it.s }
        val slopeLength = tMax - tMin
        val facetWidth = sMax - sMin
        if (slopeLength <= GeometryTolerance.LENGTH_METERS || facetWidth <= GeometryTolerance.LENGTH_METERS) return null

        // The gauge divides the slope exactly, never exceeding the exposure.
        val rowCount = max(1, ceil(slopeLength / spec.exposure - GAUGE_ROUNDING).toInt())
        val gauge = slopeLength / rowCount
        val columnCount = max(1, ceil(facetWidth / spec.moduleWidth - GAUGE_ROUNDING).toInt())

        val batch = CoverBatch(frame, spec)
        for (row in 0 until rowCount) {
            val tail = tMin + row * gauge
            val head = min(tail + spec.moduleLength, tMax)
            for (column in 0 until columnCount) {
                val start = sMin + column * spec.moduleWidth
                batch.layTile(row, column, start, tail, head, facetOrdinal, blockers)
            }
        }
        return batch.build(facet.elementId, facetOrdinal, spec.style)
    }

    /**
     * How much of a course or a column may be missing before one fewer is
     * laid: a slope that is 18.0000001 gauges long gets 18 courses, not 19.
     */
    private const val GAUGE_ROUNDING = 1e-9
}

/**
 * Every covering [profile] asks for, over every facet of every element it
 * names, in the geometry's own order.
 *
 * Only [RoofFacetGeometry] is covered. An element's other shapes — the
 * rooflight panels the Marcówki roof carries on its own id, a stack's box —
 * are neither planes to lay tiles on nor things a covering may change.
 */
fun BuildingGeometry.roofCoverMeshes(profile: RoofCoverProfile): List<RoofCoverMesh> =
    profile.covers.entries.flatMap { (elementId, spec) ->
        primitivesFor(elementId)
            .filterIsInstance<RoofFacetGeometry>()
            .mapIndexedNotNull { ordinal, facet ->
                RoofCoverGenerator.cover(facet, ordinal, spec, profile.blockers)
            }
    }

/** A facet vertex in the facet's own plane: across the slope and up it. */
private class FacetPoint(val s: Double, val t: Double)

/**
 * The orthonormal frame of one planar facet: [across] the slope, [up] the
 * slope and the upward [normal], with [across] × [up] = [normal].
 */
private class FacetFrame private constructor(
    val origin: ModelPoint,
    val across: DoubleArray,
    val up: DoubleArray,
    val normal: DoubleArray,
    val outline: List<FacetPoint>,
) {

    /** The point at [s] across, [t] up and [offset] along the normal. */
    fun at(s: Double, t: Double, offset: Double): DoubleArray = doubleArrayOf(
        origin.x + across[0] * s + up[0] * t + normal[0] * offset,
        origin.y + across[1] * s + up[1] * t + normal[1] * offset,
        origin.z + across[2] * s + up[2] * t + normal[2] * offset,
    )

    /**
     * The range of `s` the outline spans on the line at [t], or null when the
     * line misses it. Exact for a convex outline; the hull for any other.
     */
    fun spanAt(t: Double): DoubleArray? {
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        outline.indices.forEach { index ->
            val from = outline[index]
            val to = outline[(index + 1) % outline.size]
            val crosses = (from.t <= t && to.t >= t) || (to.t <= t && from.t >= t)
            if (!crosses) return@forEach
            if (abs(to.t - from.t) <= GeometryTolerance.LENGTH_METERS) {
                low = min(low, min(from.s, to.s))
                high = max(high, max(from.s, to.s))
            } else {
                val fraction = (t - from.t) / (to.t - from.t)
                val s = from.s + fraction * (to.s - from.s)
                low = min(low, s)
                high = max(high, s)
            }
        }
        return if (low <= high) doubleArrayOf(low, high) else null
    }

    companion object {

        /**
         * The frame of [vertices], or null when they enclose no plane.
         *
         * "Up the slope" is the roof plane's steepest ascent; on a flat facet,
         * which has none, it is the direction of the first edge, so a flat roof
         * still gets straight courses rather than none.
         */
        fun of(vertices: List<ModelPoint>): FacetFrame? {
            val usable = vertices.withoutRepeats()
            if (usable.size < 3) return null
            val normal = usable.newellNormal()
            val length = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
            if (length <= GeometryTolerance.LENGTH_METERS) return null
            val sign = if (normal[1] < 0.0) -1.0 else 1.0
            val n = doubleArrayOf(sign * normal[0] / length, sign * normal[1] / length, sign * normal[2] / length)

            val horizontal = sqrt(n[0] * n[0] + n[2] * n[2])
            val up = if (horizontal > FLAT_FACET_TOLERANCE) {
                doubleArrayOf(-n[0] * n[1] / horizontal, (1.0 - n[1] * n[1]) / horizontal, -n[2] * n[1] / horizontal)
            } else {
                val a = usable[0]
                val b = usable[1]
                val edge = doubleArrayOf(b.x - a.x, b.y - a.y, b.z - a.z)
                val edgeLength = sqrt(edge[0] * edge[0] + edge[1] * edge[1] + edge[2] * edge[2])
                doubleArrayOf(edge[0] / edgeLength, edge[1] / edgeLength, edge[2] / edgeLength)
            }
            val across = doubleArrayOf(
                up[1] * n[2] - up[2] * n[1],
                up[2] * n[0] - up[0] * n[2],
                up[0] * n[1] - up[1] * n[0],
            )
            val origin = usable.first()
            val outline = usable.map { vertex ->
                val dx = vertex.x - origin.x
                val dy = vertex.y - origin.y
                val dz = vertex.z - origin.z
                FacetPoint(
                    s = dx * across[0] + dy * across[1] + dz * across[2],
                    t = dx * up[0] + dy * up[1] + dz * up[2],
                )
            }
            return FacetFrame(origin, across, up, n, outline)
        }

        /** Below this much horizontal normal a facet counts as flat. */
        private const val FLAT_FACET_TOLERANCE = 1e-6
    }
}

/** Accumulates tiles into one facet's buffers. */
private class CoverBatch(private val frame: FacetFrame, private val spec: RoofCoverSpec) {

    private val positions = ArrayList<Float>()
    private val normals = ArrayList<Float>()
    private val signals = ArrayList<Float>()
    private val indices = ArrayList<Int>()
    private val tiles = ArrayList<RoofCoverTile>()

    /**
     * Lays one tile between [start] and `start + moduleWidth` across, from
     * [tail] to [head] up the slope, trimmed to the facet and dropped if a
     * blocker stands in it or too little of it is left.
     */
    fun layTile(
        row: Int,
        column: Int,
        start: Double,
        tail: Double,
        head: Double,
        facetOrdinal: Int,
        blockers: List<RoofCoverBlocker>,
    ) {
        val across = RoofCoverGenerator.VERTICES_ACROSS
        val width = spec.moduleWidth
        val length = head - tail
        if (length <= GeometryTolerance.LENGTH_METERS) return
        val round = min(spec.tailRound, length / 2.0)
        val centre = start + width / 2.0

        // Each vertex: its nominal across position, its own t (the tail is
        // rounded), and its s trimmed to the facet. The tail's vertices sit
        // at slightly different t, so they share the span the facet keeps
        // over the whole of that short range — for a convex outline the left
        // edge is convex and the right concave in t, so the intersection of
        // the spans at its two ends is inside the outline all the way along.
        // One interval for the whole row keeps its vertices in order, and a
        // trimmed tile cannot fold over itself or step outside the facet.
        val tailSpan = frame.spanAt(tail)?.intersect(frame.spanAt(tail + round) ?: return) ?: return
        val headSpan = frame.spanAt(head) ?: return
        val tailT = DoubleArray(across)
        val tailS = DoubleArray(across)
        val headS = DoubleArray(across)
        for (i in 0 until across) {
            val x = -1.0 + 2.0 * i / (across - 1)
            val nominal = start + width * i / (across - 1)
            tailT[i] = tail + round * (1.0 - sqrt(max(0.0, 1.0 - x * x)))
            tailS[i] = tailSpan.clampInto(nominal)
            headS[i] = headSpan.clampInto(nominal)
        }
        val tailWidth = tailS[across - 1] - tailS[0]
        val headWidth = headS[across - 1] - headS[0]
        if ((tailWidth + headWidth) / 2.0 < spec.minimumWidthFraction * width) return

        val footprint = listOf(
            frame.planPoint(tailS[0], tail),
            frame.planPoint(tailS[across - 1], tail),
            frame.planPoint(headS[across - 1], head),
            frame.planPoint(headS[0], head),
        )
        if (blockers.any { it.overlaps(footprint) }) return

        val ordinal = tiles.size
        val firstVertex = positions.size / 3
        val phase = phaseOf(facetOrdinal, row, column)

        // Along the tile the offset falls from tail to head at a constant rate
        // — the tilt — and across it the barrel rises to the middle.
        val tilt = -spec.step / spec.moduleLength
        fun vertex(s: Double, t: Double, along: Float) {
            val x = ((s - centre) * 2.0 / width).coerceIn(-1.0, 1.0)
            val barrel = spec.camber * (1.0 - x * x)
            val offset = spec.lift + spec.step * (head - t) / spec.moduleLength + barrel
            val position = frame.at(s, t, offset)
            positions += position[0].toFloat()
            positions += position[1].toFloat()
            positions += position[2].toFloat()

            val dBarrel = -4.0 * spec.camber * x / width
            val normal = doubleArrayOf(
                frame.normal[0] - tilt * frame.up[0] - dBarrel * frame.across[0],
                frame.normal[1] - tilt * frame.up[1] - dBarrel * frame.across[1],
                frame.normal[2] - tilt * frame.up[2] - dBarrel * frame.across[2],
            )
            val magnitude = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
            normals += (normal[0] / magnitude).toFloat()
            normals += (normal[1] / magnitude).toFloat()
            normals += (normal[2] / magnitude).toFloat()
            signals += phase
            signals += along
        }
        for (i in 0 until across) vertex(tailS[i], tailT[i], 0f)
        for (i in 0 until across) vertex(headS[i], head, 1f)

        // Wound about the facet normal: (tail i, tail i+1, head i+1) and
        // (tail i, head i+1, head i) both turn the same way as across × up.
        // A segment trimmed to nothing is skipped rather than baked flat.
        for (i in 0 until across - 1) {
            val tailA = firstVertex + i
            val tailB = firstVertex + i + 1
            val headA = firstVertex + across + i
            val headB = firstVertex + across + i + 1
            addTriangle(tailA, tailB, headB)
            addTriangle(tailA, headB, headA)
        }

        val centreOnPlane = frame.at((tailS[0] + headS[across - 1]) / 2.0, (tail + head) / 2.0, 0.0)
        tiles += RoofCoverTile(
            facetOrdinal = facetOrdinal,
            row = row,
            column = column,
            ordinal = ordinal,
            center = ModelPoint(centreOnPlane[0], centreOnPlane[1], centreOnPlane[2]),
            phase = phase,
            firstVertex = firstVertex,
        )
    }

    private fun addTriangle(a: Int, b: Int, c: Int) {
        if (triangleDoubleArea(a, b, c) <= DEGENERATE_TRIANGLE_DOUBLE_AREA) return
        indices += a
        indices += b
        indices += c
    }

    private fun triangleDoubleArea(a: Int, b: Int, c: Int): Double {
        val ux = positions[b * 3] - positions[a * 3].toDouble()
        val uy = positions[b * 3 + 1] - positions[a * 3 + 1].toDouble()
        val uz = positions[b * 3 + 2] - positions[a * 3 + 2].toDouble()
        val vx = positions[c * 3] - positions[a * 3].toDouble()
        val vy = positions[c * 3 + 1] - positions[a * 3 + 1].toDouble()
        val vz = positions[c * 3 + 2] - positions[a * 3 + 2].toDouble()
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    fun build(elementId: BuildingElementId, facetOrdinal: Int, style: RoofCoverStyle): RoofCoverMesh? {
        if (tiles.isEmpty() || indices.isEmpty()) return null
        val packed = positions.toFloatArray()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (vertex in 0 until packed.size / 3) {
            minX = min(minX, packed[vertex * 3])
            maxX = max(maxX, packed[vertex * 3])
            minY = min(minY, packed[vertex * 3 + 1])
            maxY = max(maxY, packed[vertex * 3 + 1])
            minZ = min(minZ, packed[vertex * 3 + 2])
            maxZ = max(maxZ, packed[vertex * 3 + 2])
        }
        return RoofCoverMesh(
            elementId = elementId,
            facetOrdinal = facetOrdinal,
            style = style,
            positions = packed,
            normals = normals.toFloatArray(),
            signals = signals.toFloatArray(),
            indices = indices.toIntArray(),
            tiles = tiles.toList(),
            across = frame.across.toFloats(),
            upSlope = frame.up.toFloats(),
            normal = frame.normal.toFloats(),
            boundsCenter = floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f),
            boundsHalfExtent = floatArrayOf(
                nonDegenerateHalfExtent((maxX - minX).toDouble()),
                nonDegenerateHalfExtent((maxY - minY).toDouble()),
                nonDegenerateHalfExtent((maxZ - minZ).toDouble()),
            ),
        )
    }

    private companion object {
        /** Twice the area, in square metres, below which a triangle is not drawn: one square millimetre. */
        const val DEGENERATE_TRIANGLE_DOUBLE_AREA = 2e-6
    }
}

/**
 * A deterministic seed in `[0, 1)` from a tile's place on its facet.
 *
 * Integer mixing only, so it is the same on every JVM and every device: the
 * seam a future animation hangs on must not move between a test and a phone.
 */
private fun phaseOf(facetOrdinal: Int, row: Int, column: Int): Float {
    var mixed = facetOrdinal * 0x45D9F3B + row * 0x2545F491 + column * 0x1B873593
    mixed = mixed xor (mixed ushr 16)
    mixed *= 0x7FEB352D
    mixed = mixed xor (mixed ushr 15)
    mixed *= -0x7B935975
    mixed = mixed xor (mixed ushr 16)
    return (mixed and 0xFFFF) / 65536f
}

private fun DoubleArray.clampInto(value: Double): Double = value.coerceIn(this[0], this[1])

/** The common part of two spans, or null when they share nothing. */
private fun DoubleArray.intersect(other: DoubleArray): DoubleArray? {
    val low = max(this[0], other[0])
    val high = min(this[1], other[1])
    return if (low <= high) doubleArrayOf(low, high) else null
}

private fun DoubleArray.toFloats(): FloatArray = FloatArray(size) { this[it].toFloat() }

private fun FacetFrame.planPoint(s: Double, t: Double): PlanPoint {
    val point = at(s, t, 0.0)
    return PlanPoint(point[0], point[2])
}

/**
 * Whether this blocker and the convex plan [quad] share any area, by the
 * separating-axis test: two convex polygons are apart exactly when some edge
 * normal of one of them separates their projections.
 */
private fun RoofCoverBlocker.overlaps(quad: List<PlanPoint>): Boolean {
    val polygons = listOf(outline, quad)
    polygons.forEach { polygon ->
        polygon.indices.forEach { index ->
            val from = polygon[index]
            val to = polygon[(index + 1) % polygon.size]
            val axisX = -(to.z - from.z)
            val axisZ = to.x - from.x
            if (abs(axisX) <= GeometryTolerance.LENGTH_METERS && abs(axisZ) <= GeometryTolerance.LENGTH_METERS) return@forEach
            val (minA, maxA) = outline.projectOnto(axisX, axisZ)
            val (minB, maxB) = quad.projectOnto(axisX, axisZ)
            if (maxA < minB || maxB < minA) return false
        }
    }
    return true
}

private fun List<PlanPoint>.projectOnto(axisX: Double, axisZ: Double): Pair<Double, Double> {
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    forEach { point ->
        val projection = point.x * axisX + point.z * axisZ
        low = min(low, projection)
        high = max(high, projection)
    }
    return low to high
}

private fun List<ModelPoint>.withoutRepeats(): List<ModelPoint> {
    val kept = filterIndexed { index, point -> index == 0 || !point.coincidesWith(this[index - 1]) }
    return if (kept.size > 1 && kept.first().coincidesWith(kept.last())) kept.dropLast(1) else kept
}

private fun List<ModelPoint>.newellNormal(): DoubleArray {
    var nx = 0.0
    var ny = 0.0
    var nz = 0.0
    indices.forEach { index ->
        val current = this[index]
        val next = this[(index + 1) % size]
        nx += (current.y - next.y) * (current.z + next.z)
        ny += (current.z - next.z) * (current.x + next.x)
        nz += (current.x - next.x) * (current.y + next.y)
    }
    return doubleArrayOf(nx, ny, nz)
}
