package com.buildplan.app.geometry

import com.buildplan.app.domain.model.BuildingElementId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One piece of drawable shape belonging to one semantic building element.
 *
 * ## What a primitive is, and is not
 *
 * A primitive is *high-level truth*: a wall is a centreline with a height and a
 * thickness, not a baked triangle list. Baking is the renderer's job and depends
 * on the renderer — level of detail, winding, index buffers, whether openings
 * are subtracted — none of which the model can decide for it.
 *
 * ## The join to the semantic model
 *
 * A primitive carries exactly one thing from the domain: [elementId]. It does
 * **not** repeat the element's kind, its
 * [com.buildplan.app.domain.model.BuildingElementScope], the rooms it links or
 * whether it is currently hidden. Those already have one canonical answer on
 * [com.buildplan.app.domain.model.BuildingElement], and a second copy here could
 * only ever be a copy that disagrees. Which elements a view shows is decided by
 * the existing pure queries in `BuildingElementSelection.kt`; this package then
 * looks up their shapes — see `BuildingGeometrySelection.kt`.
 *
 * ## One element, many primitives
 *
 * The relation is one-to-many by design. A gable roof is one semantic `ROOF`
 * element — one thing to cost, name, hide and isolate — made of two planar
 * facets, because a single plane cannot be a gable. Splitting the roof into two
 * semantic elements to give each facet a home would put a rendering detail into
 * the cost model.
 */
sealed interface BuildingGeometryPrimitive {

    /** The one semantic element this shape belongs to. */
    val elementId: BuildingElementId

    /** The axis-aligned local box containing this shape, in metres. */
    val bounds: LocalBounds
}

/**
 * A straight wall: a centreline on the plan, given a base level, a height and a
 * thickness.
 *
 * [start] and [end] are the **centreline**, so the wall sticks out by half of
 * [thickness] on each side. Storing a centreline rather than a footprint keeps
 * one wall one fact: a footprint would have to be kept consistent with its own
 * thickness on every edit, and could be edited into a shape that is no longer a
 * wall.
 *
 * Openings are not represented. Doors and windows are their own semantic
 * elements with their own kinds, and subtracting them belongs to a later stage
 * that actually has door and window geometry to subtract.
 */
data class WallGeometry(
    override val elementId: BuildingElementId,
    val start: PlanPoint,
    val end: PlanPoint,
    /** Y of the wall's underside, in metres. Negative below the local origin. */
    val baseElevation: Double,
    val height: Double,
    val thickness: Double,
) : BuildingGeometryPrimitive {

    init {
        requireFiniteCoordinate(baseElevation, "WallGeometry.baseElevation")
        requirePositiveLength(height, "WallGeometry.height")
        requirePositiveLength(thickness, "WallGeometry.thickness")
        require(!start.coincidesWith(end)) {
            "WallGeometry ${elementId.value} has no length: $start and $end are the same point"
        }
    }

    /** Centreline length in metres. */
    val length: Double get() = start.distanceTo(end)

    /** Y of the wall's top, in metres. */
    val topElevation: Double get() = baseElevation + height

    /**
     * The four plan corners of the wall's footprint, walked around the outline:
     * both ends offset by half the thickness to one side, then back along the
     * other.
     */
    fun footprint(): List<PlanPoint> {
        val span = length
        val offsetX = -(end.z - start.z) / span * (thickness / 2.0)
        val offsetZ = (end.x - start.x) / span * (thickness / 2.0)
        return listOf(
            PlanPoint(start.x + offsetX, start.z + offsetZ),
            PlanPoint(end.x + offsetX, end.z + offsetZ),
            PlanPoint(end.x - offsetX, end.z - offsetZ),
            PlanPoint(start.x - offsetX, start.z - offsetZ),
        )
    }

    override val bounds: LocalBounds
        get() = LocalBounds.aroundExtrusion(footprint(), baseElevation, topElevation)
}

/**
 * A horizontal slab — a floor plate, a ceiling or a foundation plate — given by
 * its plan outline, the level of its underside and its thickness.
 *
 * [elevation] is the **underside**; the slab occupies `elevation` up to
 * `elevation + thickness`. Naming which face the number means is the whole
 * point: "the slab is at 2.80" is ambiguous by exactly one slab thickness, and
 * that ambiguity is a storey-height bug waiting to happen.
 *
 * Validation checks that the outline could enclose something: at least three
 * vertices that are not all the same point, and a non-degenerate area. It does
 * **not** run a full self-intersection solver. A bow-tie outline is a real
 * defect, but catching it needs a computational-geometry pass, and nothing in
 * the product yet produces outlines at all, let alone badly enough to need one.
 */
data class SlabGeometry(
    override val elementId: BuildingElementId,
    val outline: List<PlanPoint>,
    val elevation: Double,
    val thickness: Double,
) : BuildingGeometryPrimitive {

    init {
        requireFiniteCoordinate(elevation, "SlabGeometry.elevation")
        requirePositiveLength(thickness, "SlabGeometry.thickness")

        val usable = outline.withoutRepeatedPlanVertices()
        require(usable.size >= 3) {
            "SlabGeometry ${elementId.value} needs at least 3 distinct outline vertices, " +
                "got ${usable.size} usable of ${outline.size}"
        }
        require(planArea > GeometryTolerance.AREA_SQUARE_METERS) {
            "SlabGeometry ${elementId.value} has a degenerate outline: plan area $planArea m2"
        }
    }

    /** Y of the slab's top face, in metres. */
    val topElevation: Double get() = elevation + thickness

    /** Enclosed plan area in square metres, regardless of winding direction. */
    val planArea: Double get() = abs(outline.shoelaceDoubleArea()) / 2.0

    override val bounds: LocalBounds
        get() = LocalBounds.aroundExtrusion(outline, elevation, topElevation)
}

/**
 * One planar facet of a roof, given by its ordered 3D vertices.
 *
 * A facet, not a roof: a gable roof is two of these, a hip roof four, and all of
 * them belong to the same semantic `ROOF` element. Pitch, ridge and eaves are
 * not stored — they are consequences of the vertices, and storing a consequence
 * gives it a chance to contradict its cause.
 *
 * Covering, battens, tiles and finishes are not here. They are material and
 * cost, not shape.
 */
data class RoofFacetGeometry(
    override val elementId: BuildingElementId,
    val vertices: List<ModelPoint>,
) : BuildingGeometryPrimitive {

    init {
        val usable = vertices.withoutRepeatedModelVertices()
        require(usable.size >= 3) {
            "RoofFacetGeometry ${elementId.value} needs at least 3 distinct vertices, " +
                "got ${usable.size} usable of ${vertices.size}"
        }
        require(area > GeometryTolerance.AREA_SQUARE_METERS) {
            "RoofFacetGeometry ${elementId.value} has a degenerate facet: area $area m2"
        }
    }

    /**
     * Facet area in square metres, measured on the facet's own plane — the
     * pitched roof surface, not its plan projection.
     */
    val area: Double
        get() {
            val (nx, ny, nz) = vertices.newellNormal()
            return sqrt(nx * nx + ny * ny + nz * nz) / 2.0
        }

    override val bounds: LocalBounds get() = LocalBounds.around(vertices)
}

/**
 * Drops vertices that repeat the position of the one before them, and the last
 * one when it closes back onto the first. An outline may legitimately be written
 * closed or open; neither spelling should change how many corners it has.
 */
private fun List<PlanPoint>.withoutRepeatedPlanVertices(): List<PlanPoint> {
    val kept = filterIndexed { index, point -> index == 0 || !point.coincidesWith(this[index - 1]) }
    return if (kept.size > 1 && kept.first().coincidesWith(kept.last())) kept.dropLast(1) else kept
}

/** [withoutRepeatedPlanVertices] for 3D vertices. */
private fun List<ModelPoint>.withoutRepeatedModelVertices(): List<ModelPoint> {
    val kept = filterIndexed { index, point -> index == 0 || !point.coincidesWith(this[index - 1]) }
    return if (kept.size > 1 && kept.first().coincidesWith(kept.last())) kept.dropLast(1) else kept
}

/** Twice the signed shoelace area of a plan outline, in square metres. */
private fun List<PlanPoint>.shoelaceDoubleArea(): Double =
    indices.sumOf { index ->
        val current = this[index]
        val next = this[(index + 1) % size]
        current.x * next.z - next.x * current.z
    }

/**
 * Newell's normal of a 3D polygon, whose magnitude is twice the polygon's area.
 * Newell is used rather than a single cross product because it stays correct for
 * more than three vertices and stable when consecutive edges are nearly parallel.
 */
private fun List<ModelPoint>.newellNormal(): Triple<Double, Double, Double> {
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
    return Triple(nx, ny, nz)
}
