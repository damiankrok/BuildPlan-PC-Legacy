package com.buildplan.app.render.filament

import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.GeometryTolerance
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.presentation.OpeningFrameProfile
import com.buildplan.app.presentation.OpeningFrameSpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lays a frame round a pane: one bar along each edge of the pane's polygon,
 * mitred where two bars meet, and a mullion at each leaf division.
 *
 * ## Pane-generic
 *
 * Nothing here knows a window from a door, a wall from a roof, or a house.
 * The generator is given one planar polygon and works in that polygon's own
 * plane: a rectangular window, a gable glazing whose head follows the roof,
 * and a rooflight lying on a slope are the same problem with a different
 * outline. The outline must be convex, which every pane the model produces
 * is: a bar is the strip of the polygon within one bar width of its edge and
 * nearer that edge than either neighbour, and for a convex polygon those
 * three clips leave one convex piece per edge that tile the border with no
 * overlap and no gap.
 *
 * ## What it produces
 *
 * An ordinary [BuildingRenderMesh], opaque, outlined and carrying the pane's
 * element id — so a frame is lit, inked, hidden, tinted and picked exactly as
 * the wall beside it is, and the renderer needs no word about frames. The
 * pane itself is not touched; the frame is drawn through it, half a bar depth
 * to either side.
 */
object OpeningFrameGenerator {

    /**
     * The frame of [pane], or null when the pane has no usable plane or no
     * bar survives — a sliver of glass too thin to frame is drawn bare.
     */
    fun frame(pane: OpeningPanelGeometry, spec: OpeningFrameSpec): BuildingRenderMesh? {
        val plane = PanePlane.of(pane.vertices) ?: return null
        val outline = plane.outline
        val width = spec.barWidth

        // Signed distance inside from each edge: positive within the polygon.
        val edgeCount = outline.size
        val inward = List(edgeCount) { index ->
            val from = outline[index]
            val to = outline[(index + 1) % edgeCount]
            val dx = to.a - from.a
            val dy = to.b - from.b
            val length = sqrt(dx * dx + dy * dy)
            PaneEdge(from, -dy / length, dx / length)
        }
        fun depthFrom(edge: Int): (PanePoint) -> Double = { p -> inward[edge].distance(p) }

        val bars = ArrayList<List<PanePoint>>()
        for (edge in 0 until edgeCount) {
            val previous = (edge + edgeCount - 1) % edgeCount
            val next = (edge + 1) % edgeCount
            val own = depthFrom(edge)
            var bar = outline.clippedBy { p -> width - own(p) }
            bar = bar.clippedBy { p -> depthFrom(previous)(p) - own(p) }
            bar = bar.clippedBy { p -> depthFrom(next)(p) - own(p) }
            if (bar.area() > MINIMUM_BAR_AREA) bars += bar
        }

        spec.maxLeafWidth?.let { maxLeaf ->
            var glass = outline
            for (edge in 0 until edgeCount) {
                glass = glass.clippedBy { p -> depthFrom(edge)(p) - width }
            }
            val aMin = outline.minOf { it.a }
            val aMax = outline.maxOf { it.a }
            val leaves = max(1, ceil((aMax - aMin) / maxLeaf - LEAF_ROUNDING).toInt())
            for (division in 1 until leaves) {
                val centre = aMin + (aMax - aMin) * division / leaves
                var mullion = glass.clippedBy { p -> p.a - (centre - width / 2.0) }
                mullion = mullion.clippedBy { p -> (centre + width / 2.0) - p.a }
                if (mullion.area() > MINIMUM_BAR_AREA) bars += mullion
            }
        }

        if (bars.isEmpty()) return null
        return bakeExtrudedPolygons(
            elementId = pane.elementId,
            polygons = bars.map { bar -> bar.map(plane::at) },
            axis = plane.normal,
            depth = spec.barDepth,
        )
    }

    /** Below this, a clipped bar is a rounding artefact rather than a bar: one square millimetre. */
    private const val MINIMUM_BAR_AREA = 1e-6

    /** How much of a leaf may be missing before one fewer is counted: a 2.4000001 m pane at 1.2 gets two leaves. */
    private const val LEAF_ROUNDING = 1e-9
}

/**
 * Every frame [profile] asks for, over every pane of every element it names,
 * in the geometry's own order.
 *
 * Only [OpeningPanelGeometry] is framed. An element's other shapes — a
 * roof's facets, a wall's prism — are not panes and get nothing.
 */
fun BuildingGeometry.openingFrameMeshes(profile: OpeningFrameProfile): List<BuildingRenderMesh> =
    profile.frames.entries.flatMap { (elementId, spec) ->
        primitivesFor(elementId)
            .filterIsInstance<OpeningPanelGeometry>()
            .mapNotNull { pane -> OpeningFrameGenerator.frame(pane, spec) }
    }

/** A point in a pane's own plane: [a] along its horizontal axis, [b] up it. */
private class PanePoint(val a: Double, val b: Double)

/** One edge of the pane outline with its unit inward normal. */
private class PaneEdge(private val origin: PanePoint, private val normalA: Double, private val normalB: Double) {
    /** How far inside the polygon [p] lies from this edge; negative outside. */
    fun distance(p: PanePoint): Double = (p.a - origin.a) * normalA + (p.b - origin.b) * normalB
}

/**
 * The orthonormal frame of one planar pane: its [normal], a horizontal
 * direction across it and the in-plane direction up it, with the outline
 * expressed in those two and walked counter-clockwise about the normal.
 */
private class PanePlane private constructor(
    private val origin: ModelPoint,
    private val across: DoubleArray,
    private val up: DoubleArray,
    val normal: DoubleArray,
    val outline: List<PanePoint>,
) {

    /** The 3D position of an in-plane point. */
    fun at(point: PanePoint): ModelPoint = ModelPoint(
        origin.x + across[0] * point.a + up[0] * point.b,
        origin.y + across[1] * point.a + up[1] * point.b,
        origin.z + across[2] * point.a + up[2] * point.b,
    )

    companion object {

        /**
         * The plane of [vertices], or null when they enclose none.
         *
         * "Across" is horizontal — the pane's sill direction — whenever the
         * pane is not itself horizontal, so mullions stand upright on a wall
         * and run down the slope on a rooflight. A horizontal pane has no
         * such direction and takes its first edge instead.
         */
        fun of(vertices: List<ModelPoint>): PanePlane? {
            val usable = vertices.withoutRepeats()
            if (usable.size < 3) return null
            val newell = usable.newellNormal()
            val length = sqrt(newell[0] * newell[0] + newell[1] * newell[1] + newell[2] * newell[2])
            if (length <= GeometryTolerance.LENGTH_METERS) return null
            val n = doubleArrayOf(newell[0] / length, newell[1] / length, newell[2] / length)

            val horizontal = sqrt(n[0] * n[0] + n[2] * n[2])
            val across = if (horizontal > HORIZONTAL_PANE_TOLERANCE) {
                doubleArrayOf(n[2] / horizontal, 0.0, -n[0] / horizontal)
            } else {
                val a = usable[0]
                val b = usable[1]
                val edge = doubleArrayOf(b.x - a.x, b.y - a.y, b.z - a.z)
                val edgeLength = sqrt(edge[0] * edge[0] + edge[1] * edge[1] + edge[2] * edge[2])
                doubleArrayOf(edge[0] / edgeLength, edge[1] / edgeLength, edge[2] / edgeLength)
            }
            val up = doubleArrayOf(
                n[1] * across[2] - n[2] * across[1],
                n[2] * across[0] - n[0] * across[2],
                n[0] * across[1] - n[1] * across[0],
            )
            val origin = usable.first()
            val projected = usable.map { vertex ->
                val dx = vertex.x - origin.x
                val dy = vertex.y - origin.y
                val dz = vertex.z - origin.z
                PanePoint(
                    a = dx * across[0] + dy * across[1] + dz * across[2],
                    b = dx * up[0] + dy * up[1] + dz * up[2],
                )
            }
            val outline = if (projected.signedArea() < 0.0) projected.reversed() else projected
            return PanePlane(origin, across, up, n, outline)
        }

        /** Below this much horizontal normal a pane counts as lying flat. */
        private const val HORIZONTAL_PANE_TOLERANCE = 1e-6
    }
}

/**
 * This convex polygon cut by the half-plane where [keep] is non-negative,
 * with the cut edge inserted where [keep] crosses zero. Sutherland–Hodgman
 * against one line.
 */
private fun List<PanePoint>.clippedBy(keep: (PanePoint) -> Double): List<PanePoint> {
    if (isEmpty()) return this
    val result = ArrayList<PanePoint>(size + 2)
    for (index in indices) {
        val current = this[index]
        val next = this[(index + 1) % size]
        val currentKeep = keep(current)
        val nextKeep = keep(next)
        if (currentKeep >= -CLIP_TOLERANCE) result += current
        val crosses = (currentKeep < -CLIP_TOLERANCE && nextKeep > CLIP_TOLERANCE) ||
            (currentKeep > CLIP_TOLERANCE && nextKeep < -CLIP_TOLERANCE)
        if (crosses) {
            val t = currentKeep / (currentKeep - nextKeep)
            result += PanePoint(current.a + (next.a - current.a) * t, current.b + (next.b - current.b) * t)
        }
    }
    return result.withoutRepeats()
}

/** Twice the signed shoelace area, positive for counter-clockwise. */
private fun List<PanePoint>.signedArea(): Double =
    indices.sumOf { index ->
        val current = this[index]
        val next = this[(index + 1) % size]
        current.a * next.b - next.a * current.b
    } / 2.0

private fun List<PanePoint>.area(): Double = abs(signedArea())

@JvmName("withoutRepeatedPanePoints")
private fun List<PanePoint>.withoutRepeats(): List<PanePoint> {
    val kept = filterIndexed { index, point ->
        index == 0 || abs(point.a - this[index - 1].a) > CLIP_TOLERANCE || abs(point.b - this[index - 1].b) > CLIP_TOLERANCE
    }
    return if (kept.size > 1 &&
        abs(kept.first().a - kept.last().a) <= CLIP_TOLERANCE &&
        abs(kept.first().b - kept.last().b) <= CLIP_TOLERANCE
    ) {
        kept.dropLast(1)
    } else {
        kept
    }
}

/** How close to a clipping line a vertex may lie and still count as on it. */
private const val CLIP_TOLERANCE = 1e-9

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
