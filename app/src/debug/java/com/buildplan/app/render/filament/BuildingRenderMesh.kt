package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.geometry.BuildingGeometryPrimitive
import com.buildplan.app.geometry.GeometryTolerance
import com.buildplan.app.geometry.LocalBounds
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One geometry primitive baked into the triangles a GPU can draw.
 *
 * ## Why baking lives here and not in `geometry/`
 *
 * `geometry/` stores high-level truth — a wall is a centreline with a height and
 * a thickness. How many triangles that becomes, which way they wind and whether
 * their normals are shared or flat are renderer decisions, and a second renderer
 * would make them differently. So the model keeps the centreline, and this
 * adapter — renderer-facing, and living in `src/debug` with the spike — keeps
 * the triangles.
 *
 * ## The join stays the element id
 *
 * A mesh carries [elementId] and nothing else from the semantic model. It does
 * not know its floor, its rooms, its kind or whether it is currently hidden,
 * exactly as [BuildingGeometryPrimitive] does not. Which meshes are on screen is
 * decided upstream by `BuildingElementSelection.kt` and reaches the renderer
 * through the single `primitivesOf` bridge.
 *
 * ## One element, many meshes
 *
 * The relation is one-to-many, inherited unchanged from the geometry layer: the
 * gable roof is one [BuildingElementId] and two facets, so it bakes to two
 * meshes that answer with the same id. Nothing here collapses or splits that.
 *
 * Positions and normals are flat `xyz` triples in the building's local metres,
 * one vertex per corner per face — faces are never welded, so every triangle
 * keeps the flat normal of the face it belongs to, which is what makes a
 * technical model read as faceted rather than smoothed.
 */
class BuildingRenderMesh internal constructor(
    val elementId: BuildingElementId,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    /** Centre of the axis-aligned box the renderer culls against. */
    val boundsCenter: FloatArray,
    /** Half-size of that box, never zero on any axis — see [nonDegenerateHalfExtent]. */
    val boundsHalfExtent: FloatArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

/** Bakes every primitive in order, so draw order stays the geometry's order. */
fun List<BuildingGeometryPrimitive>.toRenderMeshes(): List<BuildingRenderMesh> =
    map { it.toRenderMesh() }

/**
 * Bakes one primitive.
 *
 * Walls and slabs are prisms: a plan outline extruded between two levels and
 * closed with a cap at each end. A roof facet is already planar, so it is
 * triangulated where it is.
 *
 * Caps are fan-triangulated from the first vertex, which is correct for a convex
 * outline and wrong for a concave one. Every outline the model can currently
 * produce is a rectangle; a real plan parser will bring concave rooms with it,
 * and an ear-clipping pass along with them. Writing one now would be untestable
 * against anything.
 */
fun BuildingGeometryPrimitive.toRenderMesh(): BuildingRenderMesh {
    val mesh = MeshAccumulator()
    when (this) {
        is WallGeometry -> mesh.addPrism(footprint(), baseElevation, topElevation)
        is SlabGeometry -> mesh.addPrism(outline, elevation, topElevation)
        is RoofFacetGeometry -> mesh.addPlanarFacet(vertices)
    }
    return mesh.build(elementId, bounds)
}

/**
 * Collects faces into flat vertex, normal and index arrays.
 *
 * Every face is added with the outward normal it should be shaded by, and its
 * winding is then corrected to match that normal rather than assumed: the
 * spike's material is double-sided, and a double-sided material decides which
 * way to flip a normal from the triangle's facing. A face wound against its own
 * normal would be lit from inside the wall.
 */
private class MeshAccumulator {

    private val positions = ArrayList<Float>()
    private val normals = ArrayList<Float>()
    private val indices = ArrayList<Int>()

    /** A plan outline extruded from [bottomY] to [topY], with both caps closed. */
    fun addPrism(plan: List<PlanPoint>, bottomY: Double, topY: Double) {
        val outline = plan.withoutRepeatedVertices()
        if (outline.size < 3) return

        val positiveWinding = outline.signedPlanArea() > 0.0
        addPolygon(outline.map { it.at(topY) }, floatArrayOf(0f, 1f, 0f))
        addPolygon(outline.map { it.at(bottomY) }, floatArrayOf(0f, -1f, 0f))

        outline.indices.forEach { index ->
            val from = outline[index]
            val to = outline[(index + 1) % outline.size]
            val normal = outwardSideNormal(from, to, positiveWinding) ?: return@forEach
            addPolygon(
                listOf(from.at(bottomY), to.at(bottomY), to.at(topY), from.at(topY)),
                normal,
            )
        }
    }

    /**
     * A single planar polygon, shaded by its own normal flipped to point
     * upwards: a roof facet is seen from above, and which way its vertices
     * happened to be written is not a fact about the roof.
     */
    fun addPlanarFacet(vertices: List<ModelPoint>) {
        val usable = vertices.withoutRepeatedVertices()
        if (usable.size < 3) return
        val normal = usable.newellUnitNormal() ?: return
        val upwards = if (normal[1] < 0f) {
            floatArrayOf(-normal[0], -normal[1], -normal[2])
        } else {
            normal
        }
        addPolygon(usable, upwards)
    }

    private fun addPolygon(points: List<ModelPoint>, normal: FloatArray) {
        if (points.size < 3) return
        val ordered = if (points.newellDot(normal) < 0.0) points.reversed() else points
        val base = positions.size / 3

        ordered.forEach { point ->
            positions += point.x.toFloat()
            positions += point.y.toFloat()
            positions += point.z.toFloat()
            normals += normal[0]
            normals += normal[1]
            normals += normal[2]
        }

        for (corner in 1 until ordered.size - 1) {
            indices += base
            indices += base + corner
            indices += base + corner + 1
        }
    }

    fun build(elementId: BuildingElementId, bounds: LocalBounds): BuildingRenderMesh =
        BuildingRenderMesh(
            elementId = elementId,
            positions = positions.toFloatArray(),
            normals = normals.toFloatArray(),
            indices = indices.toIntArray(),
            boundsCenter = floatArrayOf(
                bounds.center.x.toFloat(),
                bounds.center.y.toFloat(),
                bounds.center.z.toFloat(),
            ),
            boundsHalfExtent = floatArrayOf(
                nonDegenerateHalfExtent(bounds.sizeX),
                nonDegenerateHalfExtent(bounds.sizeY),
                nonDegenerateHalfExtent(bounds.sizeZ),
            ),
        )
}

/**
 * Half of [size], never zero.
 *
 * A roof facet is a surface with no thickness, so its box is flat on one axis. A
 * zero half-extent is a box a culler can decide the camera never sees, which
 * shows up as a roof that vanishes at certain angles.
 */
internal fun nonDegenerateHalfExtent(size: Double): Float =
    maxOf(size / 2.0, GeometryTolerance.LENGTH_METERS).toFloat()

/**
 * The outward horizontal normal of the side face on edge [from] to [to], or null
 * for a degenerate edge.
 *
 * For an outline whose signed plan area is positive, `(dz, -dx)` points away
 * from the interior; a negatively wound outline is the same edge walked the
 * other way, so the normal flips with it.
 */
private fun outwardSideNormal(
    from: PlanPoint,
    to: PlanPoint,
    positiveWinding: Boolean,
): FloatArray? {
    val dx = to.x - from.x
    val dz = to.z - from.z
    val length = hypot(dx, dz)
    if (length <= GeometryTolerance.LENGTH_METERS) return null
    val sign = if (positiveWinding) 1.0 else -1.0
    return floatArrayOf((sign * dz / length).toFloat(), 0f, (-sign * dx / length).toFloat())
}

/** Signed shoelace area: positive for one winding, negative for the other. */
private fun List<PlanPoint>.signedPlanArea(): Double =
    indices.sumOf { index ->
        val current = this[index]
        val next = this[(index + 1) % size]
        current.x * next.z - next.x * current.z
    } / 2.0

/** Newell's normal, normalised, or null when the polygon encloses no area. */
private fun List<ModelPoint>.newellUnitNormal(): FloatArray? {
    val normal = newellNormal()
    val length = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
    if (length <= GeometryTolerance.LENGTH_METERS) return null
    return floatArrayOf(
        (normal[0] / length).toFloat(),
        (normal[1] / length).toFloat(),
        (normal[2] / length).toFloat(),
    )
}

/** How far this polygon's own normal agrees with [normal]; negative means wound the other way. */
private fun List<ModelPoint>.newellDot(normal: FloatArray): Double {
    val own = newellNormal()
    return own[0] * normal[0] + own[1] * normal[1] + own[2] * normal[2]
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

/**
 * Drops vertices repeating the one before them, and a last vertex closing back
 * onto the first. An outline may be written open or closed; a repeated corner
 * bakes a zero-area side face whose normal cannot be normalised.
 */
private fun List<PlanPoint>.withoutRepeatedVertices(): List<PlanPoint> {
    val kept = filterIndexed { index, point -> index == 0 || !point.coincidesWith(this[index - 1]) }
    return if (kept.size > 1 && kept.first().coincidesWith(kept.last())) kept.dropLast(1) else kept
}

/** [withoutRepeatedVertices] for 3D vertices. */
@JvmName("withoutRepeatedModelVertices")
private fun List<ModelPoint>.withoutRepeatedVertices(): List<ModelPoint> {
    val kept = filterIndexed { index, point -> index == 0 || !point.coincidesWith(this[index - 1]) }
    return if (kept.size > 1 && kept.first().coincidesWith(kept.last())) kept.dropLast(1) else kept
}
