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
 * [openings] are the holes cut through it. They are positions and sizes only:
 * what *fills* each hole is a separate semantic element with its own kind, its
 * own name and its own shape, named by [WallOpening.elementId]. Keeping the hole
 * on the wall and the pane on the window is what stops the two from disagreeing
 * — a wall cannot be drawn solid while a window is drawn in it — and it is why
 * hiding the window element leaves the hole, which is what a hole is.
 */
data class WallGeometry(
    override val elementId: BuildingElementId,
    val start: PlanPoint,
    val end: PlanPoint,
    /** Y of the wall's underside, in metres. Negative below the local origin. */
    val baseElevation: Double,
    val height: Double,
    val thickness: Double,
    /** Holes through this wall, in no required order. See [WallOpening]. */
    val openings: List<WallOpening> = emptyList(),
) : BuildingGeometryPrimitive {

    init {
        requireFiniteCoordinate(baseElevation, "WallGeometry.baseElevation")
        requirePositiveLength(height, "WallGeometry.height")
        requirePositiveLength(thickness, "WallGeometry.thickness")
        require(!start.coincidesWith(end)) {
            "WallGeometry ${elementId.value} has no length: $start and $end are the same point"
        }
        openings.forEach { opening -> requireOpeningFits(opening) }
        requireOpeningsDoNotOverlap()
    }

    /**
     * Requires [opening] to be a hole in *this* wall rather than beside it.
     *
     * An opening that hangs off the end of a wall, or above its top, is not a
     * window that will look slightly wrong — it is a subtraction the renderer
     * cannot perform, and the wall it produces has a piece missing where there
     * was never any wall. Rejecting it at construction is the only place the
     * mistake is still attributable to the line that made it.
     */
    private fun requireOpeningFits(opening: WallOpening) {
        val tolerance = GeometryTolerance.LENGTH_METERS
        require(opening.distanceFromStart >= -tolerance) {
            "WallOpening ${opening.elementId.value} starts ${-opening.distanceFromStart} m " +
                "before wall ${elementId.value}"
        }
        require(opening.distanceToEnd <= length + tolerance) {
            "WallOpening ${opening.elementId.value} ends ${opening.distanceToEnd} m along " +
                "wall ${elementId.value}, which is only $length m long"
        }
        require(opening.sillElevation >= baseElevation - tolerance) {
            "WallOpening ${opening.elementId.value} has its sill at ${opening.sillElevation} m, " +
                "below the base of wall ${elementId.value} at $baseElevation m"
        }
        require(opening.headElevation <= topElevation + tolerance) {
            "WallOpening ${opening.elementId.value} has its head at ${opening.headElevation} m, " +
                "above the top of wall ${elementId.value} at $topElevation m"
        }
    }

    /**
     * Requires no two openings to share wall. Overlapping holes are not a
     * drawing artefact: the bake splits the wall at opening edges, and two
     * openings crossing one another describe a pier of negative width.
     */
    private fun requireOpeningsDoNotOverlap() {
        val sorted = openings.sortedBy { it.distanceFromStart }
        sorted.zipWithNext { earlier, later ->
            require(later.distanceFromStart >= earlier.distanceToEnd - GeometryTolerance.LENGTH_METERS) {
                "WallOpenings ${earlier.elementId.value} and ${later.elementId.value} overlap " +
                    "on wall ${elementId.value}"
            }
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
    fun footprint(): List<PlanPoint> = footprintBetween(0.0, length)

    /**
     * The same four corners, for the stretch of wall between [fromDistance] and
     * [toDistance] measured along the centreline from [start].
     *
     * This is what makes an opening a hole rather than a decal: a renderer bakes
     * a wall with windows as the solid stretches between them, and those
     * stretches are this. Deriving them here rather than in the renderer keeps
     * the arithmetic that turns a centreline and a thickness into a footprint in
     * one place — a second copy of it in a baking adapter is a second chance to
     * get the offset sign wrong, and it would be wrong only for walls running
     * one particular direction.
     */
    fun footprintBetween(fromDistance: Double, toDistance: Double): List<PlanPoint> {
        val span = length
        val directionX = (end.x - start.x) / span
        val directionZ = (end.z - start.z) / span
        val offsetX = -directionZ * (thickness / 2.0)
        val offsetZ = directionX * (thickness / 2.0)
        val fromX = start.x + directionX * fromDistance
        val fromZ = start.z + directionZ * fromDistance
        val toX = start.x + directionX * toDistance
        val toZ = start.z + directionZ * toDistance
        return listOf(
            PlanPoint(fromX + offsetX, fromZ + offsetZ),
            PlanPoint(toX + offsetX, toZ + offsetZ),
            PlanPoint(toX - offsetX, toZ - offsetZ),
            PlanPoint(fromX - offsetX, fromZ - offsetZ),
        )
    }

    override val bounds: LocalBounds
        get() = LocalBounds.aroundExtrusion(footprint(), baseElevation, topElevation)
}

/**
 * One rectangular hole through a [WallGeometry], and the element that fills it.
 *
 * ## Why it is measured along the wall
 *
 * [distanceFromStart] runs along the wall's own centreline from
 * [WallGeometry.start], rather than being a pair of plan coordinates. A window
 * is a fact *about a wall*: it is 1.40 m wide and it sits 5.39 m along. Written
 * as world coordinates it would be a second, independent description of where
 * the wall is, and moving the wall by a corrected trace would leave its windows
 * floating in the air beside it.
 *
 * ## What it does not carry
 *
 * Not the kind of opening. A door and a window differ here only in that a door's
 * sill is the floor, and that is already said by [sillElevation]; repeating
 * [com.buildplan.app.domain.model.BuildingElementKind] in this package would be
 * a second copy of an answer the domain already owns. Not glazing, frames,
 * mullions or opening direction either — those are neither shape nor cost at
 * this stage.
 *
 * @property elementId the window or door element that fills this hole. It is a
 *   *different* element from the wall the hole is in, so that the pane can be
 *   named, picked and priced on its own while the wall keeps one identity.
 */
data class WallOpening(
    val elementId: BuildingElementId,
    /** Distance along the wall centreline from `start` to the opening's near edge. */
    val distanceFromStart: Double,
    val width: Double,
    /** Y of the opening's underside. A door's sill is its storey's floor level. */
    val sillElevation: Double,
    val height: Double,
) {
    init {
        requireFiniteCoordinate(distanceFromStart, "WallOpening.distanceFromStart")
        requirePositiveLength(width, "WallOpening.width")
        requireFiniteCoordinate(sillElevation, "WallOpening.sillElevation")
        requirePositiveLength(height, "WallOpening.height")
    }

    /** Distance along the wall centreline to the opening's far edge. */
    val distanceToEnd: Double get() = distanceFromStart + width

    /** Y of the opening's top. */
    val headElevation: Double get() = sillElevation + height
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
 * One vertical planar panel closing the space between the top of a storey's
 * walls and the roof above them — the triangle at a gable end, or the pentagon
 * a gable becomes once a knee wall raises its base.
 *
 * Neither of the existing planar shapes can hold it honestly. [WallGeometry] is
 * a centreline extruded between two *constant* levels, and a gable's top edge
 * follows the roof rather than a level. [RoofFacetGeometry] is a surface seen
 * and shaded from above, which a vertical panel never is. Forcing the shape into
 * either type would put it in a type whose invariants it does not satisfy, and
 * the renderer would light it as the wrong kind of surface.
 *
 * It is not a semantic element of its own. A gable belongs to the wall it closes
 * — the same [elementId] as that wall's [WallGeometry] — which is the same
 * one-element-many-primitives relation a gable roof already has.
 *
 * The polygon must be **vertical**: its plane contains the Y axis. A panel that
 * had drifted off vertical would be a sloped surface pretending to be a wall.
 */
data class GablePanelGeometry(
    override val elementId: BuildingElementId,
    val vertices: List<ModelPoint>,
) : BuildingGeometryPrimitive {

    init {
        val usable = vertices.withoutRepeatedModelVertices()
        require(usable.size >= 3) {
            "GablePanelGeometry ${elementId.value} needs at least 3 distinct vertices, " +
                "got ${usable.size} usable of ${vertices.size}"
        }
        require(area > GeometryTolerance.AREA_SQUARE_METERS) {
            "GablePanelGeometry ${elementId.value} has a degenerate panel: area $area m2"
        }

        // A vertical plane has a horizontal normal, so the normal's Y component
        // is zero. Compared against the normal's own magnitude rather than an
        // absolute epsilon, so the check means the same for a 1 m2 panel and a
        // 100 m2 one.
        val (nx, ny, nz) = usable.newellNormal()
        val magnitude = sqrt(nx * nx + ny * ny + nz * nz)
        require(abs(ny) <= magnitude * VERTICALITY_TOLERANCE) {
            "GablePanelGeometry ${elementId.value} is not vertical: its plane tilts by " +
                "${abs(ny) / magnitude} of a right angle"
        }
    }

    /** Panel area in square metres, measured on its own vertical plane. */
    val area: Double
        get() {
            val (nx, ny, nz) = vertices.newellNormal()
            return sqrt(nx * nx + ny * ny + nz * nz) / 2.0
        }

    override val bounds: LocalBounds get() = LocalBounds.around(vertices)

    private companion object {
        /** How far off vertical a panel may be and still count as one. */
        const val VERTICALITY_TOLERANCE = 1e-6
    }
}

/**
 * The planar fill of one opening: a window pane, a door leaf, a rooflight.
 *
 * ## Why it is a panel and not a box
 *
 * The hole is already the truth — it is subtracted from the wall by
 * [WallOpening], and the reveal faces the bake produces around it are the real
 * jambs, head and sill. What is left to draw is the sheet inside the hole, and a
 * sheet is what this is: a single planar polygon, with no thickness, no frame
 * profile and no glazing bars. A model that has not been told a frame section
 * cannot draw one without inventing it.
 *
 * ## Why it is not a [GablePanelGeometry]
 *
 * A gable panel closes the space between a storey's walls and the roof: it is
 * always vertical, and it belongs to the wall it closes. This is neither. It is
 * seen and lit from both sides, it may be sloped — a rooflight lies in the roof
 * plane — and it belongs to its own window or door element rather than to the
 * wall the hole is in. Sharing one type would mean an invariant that holds for
 * only half of its values, which is the same as no invariant.
 *
 * ## Shape
 *
 * Any planar polygon, so the sloped-headed glazing a gable takes is one shape
 * rather than a rectangle plus an apology. Planarity *is* checked: a pane bent
 * across its own diagonal would be shaded as two surfaces meeting at an angle,
 * which is exactly what glass is not.
 */
data class OpeningPanelGeometry(
    override val elementId: BuildingElementId,
    val vertices: List<ModelPoint>,
) : BuildingGeometryPrimitive {

    init {
        val usable = vertices.withoutRepeatedModelVertices()
        require(usable.size >= 3) {
            "OpeningPanelGeometry ${elementId.value} needs at least 3 distinct vertices, " +
                "got ${usable.size} usable of ${vertices.size}"
        }
        require(area > GeometryTolerance.AREA_SQUARE_METERS) {
            "OpeningPanelGeometry ${elementId.value} has a degenerate panel: area $area m2"
        }
        requirePlanar(usable)
    }

    /** Panel area in square metres, measured on its own plane. */
    val area: Double
        get() {
            val (nx, ny, nz) = vertices.newellNormal()
            return sqrt(nx * nx + ny * ny + nz * nz) / 2.0
        }

    override val bounds: LocalBounds get() = LocalBounds.around(vertices)

    /**
     * Requires every vertex to lie on the plane the polygon's own normal
     * defines, within a tolerance scaled by the panel's size so that the check
     * means the same for a rooflight and for a full gable glazing.
     */
    private fun requirePlanar(usable: List<ModelPoint>) {
        val (nx, ny, nz) = usable.newellNormal()
        val magnitude = sqrt(nx * nx + ny * ny + nz * nz)
        val origin = usable.first()
        val extent = sqrt(magnitude)
        usable.forEach { vertex ->
            val distance = abs(
                (vertex.x - origin.x) * nx + (vertex.y - origin.y) * ny + (vertex.z - origin.z) * nz,
            ) / magnitude
            require(distance <= extent * PLANARITY_TOLERANCE) {
                "OpeningPanelGeometry ${elementId.value} is not planar: $vertex lies " +
                    "$distance m off the plane of its other vertices"
            }
        }
    }

    private companion object {
        /** How far off its own plane a vertex may sit, relative to the panel's size. */
        const val PLANARITY_TOLERANCE = 1e-6
    }
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
