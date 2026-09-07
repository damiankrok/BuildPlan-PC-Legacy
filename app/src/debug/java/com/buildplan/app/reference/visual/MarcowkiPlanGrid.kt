package com.buildplan.app.reference.visual

import com.buildplan.app.geometry.PlanPoint
import kotlin.math.tan

/**
 * Every coordinate traced from the current ARCHON drawings, in one place.
 *
 * # One truth, two consumers
 *
 * Both the wall elements in [MarcowkiVisualModelV1] and the room zones in
 * [MarcowkiRoomTrace] are built from these lines and from nothing else. That is
 * the whole reason the grid is a separate object: a set of hand-written wall
 * segments beside a set of hand-written room polygons is two descriptions of one
 * plan, and the first correction to either makes them disagree about where a
 * wall is. Here a partition moves once and the rooms on both sides of it move
 * with it.
 *
 * # The frame
 *
 * Metres, matching `geometry/`: X runs east, Z runs south, Y is up, and the
 * origin is the north-west **outer** wall corner of the main house. So the house
 * occupies X 0.00 to 7.89 and Z 0.00 to 12.60, with the garage attached to its
 * east side from Z 5.10 southwards.
 *
 * Wall lines are given as **centrelines**, because that is what
 * [com.buildplan.app.geometry.WallGeometry] stores; room zones are given by the
 * wall faces they actually meet, which is a face offset of half a thickness from
 * the same line.
 *
 * # How these numbers were obtained
 *
 * Both plan rasters were calibrated against their printed dimension anchors and
 * then measured — see [MarcowkiSourceEvidence.CALIBRATION]. A number here is
 * therefore [SourceFidelity.SOURCE_TRACED] unless it is one of the named
 * anchors, which are [SourceFidelity.SOURCE_EXACT] and marked as such below.
 * Nothing here is invented: where the source draws no line — the open-plan
 * kitchen and hall boundary — the model says so through
 * [TraceCertainty.TRACE_UNCERTAIN] rather than inventing a wall.
 */
object MarcowkiPlanGrid {

    // ---------------------------------------------------------------------
    // Thicknesses
    // ---------------------------------------------------------------------

    /**
     * Exterior wall thickness. Traced at 17 to 18 px on both plans, and
     * confirmed by arithmetic: the printed 700 clear width of the living room
     * inside the printed 790 outer width leaves exactly 0.45 m of wall between
     * them, split over one exterior and one party wall.
     */
    const val EXTERIOR_WALL_THICKNESS: Double = 0.44

    /** Interior partition thickness. Traced at 4 to 5 px on both plans. */
    const val PARTITION_THICKNESS: Double = 0.12

    // ---------------------------------------------------------------------
    // Outer faces — the SOURCE_EXACT anchors everything else was measured from
    // ---------------------------------------------------------------------

    /** Printed 790: the main house, west outer face to party wall east face. */
    const val HOUSE_WIDTH: Double = 7.89

    /** Printed 1205: both outer faces of the whole building. */
    const val BUILDING_WIDTH: Double = 12.05

    /** Printed 1260: north and south outer faces. */
    const val BUILDING_DEPTH: Double = 12.60

    /** Printed 510: north outer face to the north outer face of the garage. */
    const val GARAGE_NORTH_FACE: Double = 5.10

    // ---------------------------------------------------------------------
    // Perimeter wall centrelines
    // ---------------------------------------------------------------------

    private const val HALF_EXTERIOR = EXTERIOR_WALL_THICKNESS / 2.0

    const val X_WEST_WALL: Double = HALF_EXTERIOR
    const val X_HOUSE_EAST_WALL: Double = HOUSE_WIDTH - HALF_EXTERIOR
    const val X_GARAGE_EAST_WALL: Double = BUILDING_WIDTH - HALF_EXTERIOR

    const val Z_NORTH_WALL: Double = HALF_EXTERIOR
    const val Z_GARAGE_NORTH_WALL: Double = GARAGE_NORTH_FACE + HALF_EXTERIOR
    const val Z_SOUTH_WALL: Double = BUILDING_DEPTH - HALF_EXTERIOR

    // ---------------------------------------------------------------------
    // Ground-floor partition centrelines
    // ---------------------------------------------------------------------

    /** West side of the hall, dividing it from the bathroom and the bedroom. */
    const val X_GF_HALL_WEST: Double = 3.42

    /** East side of the hall, dividing it from the pantry, stairs and boiler room. */
    const val X_GF_HALL_EAST: Double = 5.43

    /** East side of the pantry. */
    const val X_GF_PANTRY_EAST: Double = 6.41

    /** South edge of the living room, where the kitchen zone begins. */
    const val Z_GF_LIVING_SOUTH: Double = 4.56

    /** South side of the pantry. */
    const val Z_GF_PANTRY_SOUTH: Double = 6.22

    /** Kitchen to bathroom; the printed 265 depth of the kitchen ends here. */
    const val Z_GF_KITCHEN_BATH: Double = 7.31

    /** Bathroom to bedroom; the printed 142 depth of the bathroom ends here. */
    const val Z_GF_BATH_BEDROOM: Double = 8.84

    /** North side of the boiler room; the printed 312 depth begins here. */
    const val Z_GF_BOILER_NORTH: Double = 8.96

    /** Hall to vestibule; the printed 203 depth of the vestibule begins here. */
    const val Z_GF_HALL_VESTIBULE: Double = 10.06

    // ---------------------------------------------------------------------
    // Attic partition centrelines
    // ---------------------------------------------------------------------

    /**
     * West block boundary south of the corridor, where the printed 290 width of
     * the wardrobe ends. The block is 0.54 m narrower here than it is further
     * north, which the trace shows on both plans and the model keeps.
     */
    const val X_UF_WARDROBE_EAST: Double = 3.39

    /** West side of the corridor, north of [Z_UF_CORRIDOR_SOUTH]. */
    const val X_UF_CORRIDOR_WEST: Double = 3.93

    /** East side of the corridor, dividing it from the wardrobe and the stairs. */
    const val X_UF_CORRIDOR_EAST: Double = 5.20

    /** South side of the north-east bedroom; its printed 325 depth ends here. */
    const val Z_UF_BEDROOM_WARDROBE: Double = 3.70

    /** South side of the north-west bedroom; its printed 449 depth ends here. */
    const val Z_UF_BEDROOM_LAUNDRY: Double = 4.99

    /** Laundry to bathroom; the printed 202 depth of the laundry ends here. */
    const val Z_UF_LAUNDRY_BATH: Double = 7.13

    /** South side of the corridor and the stairwell. */
    const val Z_UF_CORRIDOR_SOUTH: Double = 8.82

    /** Bathroom to wardrobe; the printed 248 depth of the bathroom ends here. */
    const val Z_UF_BATH_WARDROBE: Double = 9.73

    // ---------------------------------------------------------------------
    // Levels — all SOURCE_EXACT, read off the current cross-section
    // ---------------------------------------------------------------------

    /** Terrain outside the wall, and the underside of the drawn foundation plate. */
    const val TERRAIN_Y: Double = -0.32

    /** Finished ground floor level, and the Y origin of the model. */
    const val GROUND_FLOOR_Y: Double = 0.0

    /** Dimensioned 272 on the section. */
    const val GROUND_CLEAR_HEIGHT: Double = 2.72

    /** Levelled +3.06 on the section. */
    const val UPPER_FLOOR_Y: Double = 3.06

    /** Dimensioned 266 on the section; the height of the attic partitions. */
    const val ATTIC_CLEAR_HEIGHT: Double = 2.66

    /** Stated on the page and dimensioned 130 on the section. */
    const val KNEE_WALL_HEIGHT: Double = 1.30

    /** Levelled +7.95 on the section. */
    const val RIDGE_Y: Double = 7.95

    /** Stated on the page and annotated on the section. */
    const val ROOF_PITCH_DEGREES: Double = 40.0

    /** Dimensioned 252 in the garage on the section. */
    const val GARAGE_CLEAR_HEIGHT: Double = 2.52

    /** Stated on the page, and equal to [RIDGE_Y] above [TERRAIN_Y]. */
    const val BUILDING_HEIGHT: Double = 8.27

    /** Ceiling of the ground floor, and the underside of the attic floor. */
    const val GROUND_CEILING_Y: Double = GROUND_FLOOR_Y + GROUND_CLEAR_HEIGHT

    /**
     * Thickness of the attic floor structure.
     *
     * Derived, not assumed: it is what the section's +3.06 level leaves above
     * its 272 clear height, so both ends of it are stated.
     */
    const val UPPER_SLAB_THICKNESS: Double = UPPER_FLOOR_Y - GROUND_CEILING_Y

    /** Top of the published knee wall. Not where the model stops the wall — see below. */
    const val KNEE_WALL_TOP_Y: Double = UPPER_FLOOR_Y + KNEE_WALL_HEIGHT

    // ---------------------------------------------------------------------
    // Roof
    // ---------------------------------------------------------------------

    /**
     * Where the ridge runs, on the plan.
     *
     * North-south, over the middle of the 7.89 m house width. The direction was
     * not read off the marketing render but settled by arithmetic: only a ridge
     * this way round reproduces the stated 150.57 m2 roof area, and the
     * cross-section — whose house width scales to 7.89 m against its own level
     * dimensions, not to the 12.60 m depth — cuts across a gable, which is what
     * a section perpendicular to this ridge would show.
     */
    const val RIDGE_X: Double = HOUSE_WIDTH / 2.0

    /**
     * How far the roof reaches past the gable walls, north and south.
     *
     * Traced from the 100 anchors at the top and bottom of the plan's right-hand
     * dimension chain, and confirmed by the stated roof area: 1.00 m at the
     * gables with nothing at the eaves gives 150.4 m2 against a stated 150.57.
     * A flush eaves and a deep gable overhang is also what the section draws and
     * what this barn-form house looks like.
     */
    const val GABLE_OVERHANG: Double = 1.00

    /**
     * Where the roof plane meets the outer wall face.
     *
     * Derived from the exact ridge and the exact pitch rather than read off the
     * section's own +4.67 marker, so that the published 40 degrees survives
     * exactly. The two disagree by 0.03 m, which is the rounding in the
     * published pitch; keeping the angle keeps the roof the shape the source
     * names it.
     */
    val EAVES_Y: Double = RIDGE_Y - RIDGE_X * tan(Math.toRadians(ROOF_PITCH_DEGREES))

    /**
     * Height of the attic perimeter wall as drawn.
     *
     * Taller than [KNEE_WALL_HEIGHT] on purpose, and the difference is recorded
     * as a display assumption: the wall is built up to the roof plane so the
     * massing has no open slot along either eaves. See
     * [MarcowkiSourceEvidence.displayAssumptions].
     */
    val ATTIC_PERIMETER_WALL_HEIGHT: Double = EAVES_Y - UPPER_FLOOR_Y

    /**
     * Y of the roof underside directly above plan position [x].
     *
     * The two facets form a tent over the house width, so this rises linearly
     * from [EAVES_Y] at either outer face to [RIDGE_Y] over [RIDGE_X]. It exists
     * because the attic is a room inside a roof rather than under one: a
     * partition that stood its full [ATTIC_CLEAR_HEIGHT] near an eaves would come
     * out through the roof, which is both wrong and the first thing an owner
     * would notice.
     */
    fun roofUndersideAt(x: Double): Double =
        EAVES_Y + minOf(x, HOUSE_WIDTH - x).coerceAtLeast(0.0) *
            tan(Math.toRadians(ROOF_PITCH_DEGREES))

    // ---------------------------------------------------------------------
    // Derived faces, for the room zones
    // ---------------------------------------------------------------------

    /** The inner face of an exterior wall whose centreline is at [centre]. */
    fun exteriorFace(centre: Double, towardsPositive: Boolean): Double =
        if (towardsPositive) centre + EXTERIOR_WALL_THICKNESS / 2.0 else centre - EXTERIOR_WALL_THICKNESS / 2.0

    /** The face of a partition whose centreline is at [centre]. */
    fun partitionFace(centre: Double, towardsPositive: Boolean): Double =
        if (towardsPositive) centre + PARTITION_THICKNESS / 2.0 else centre - PARTITION_THICKNESS / 2.0

    /**
     * How many traced grid lines the model rests on.
     *
     * Counted rather than listed, so it cannot fall out of step with the grid it
     * describes: the six perimeter centrelines, the nine ground-floor and eight
     * attic partition centrelines, the two traced thicknesses and the traced
     * gable overhang.
     */
    const val TRACED_LINE_COUNT: Int = 6 + 9 + 8 + 2 + 1

    /** An axis-aligned rectangle on the plan, walked from its north-west corner. */
    fun rectangle(minX: Double, minZ: Double, maxX: Double, maxZ: Double): List<PlanPoint> =
        listOf(
            PlanPoint(minX, minZ),
            PlanPoint(maxX, minZ),
            PlanPoint(maxX, maxZ),
            PlanPoint(minX, maxZ),
        )

    /**
     * Outer footprint of the main house.
     *
     * The building is L-shaped and is kept as two rectangles rather than one
     * concave outline, because the renderer fan-triangulates a plan outline from
     * its first vertex and a fan across a reflex corner leaves the building.
     * Two convex pieces of one element say the same thing and bake correctly.
     */
    val houseFootprint: List<PlanPoint> = rectangle(0.0, 0.0, HOUSE_WIDTH, BUILDING_DEPTH)

    /** Outer footprint of the attached garage — the other half of the L. */
    val garageFootprint: List<PlanPoint> =
        rectangle(HOUSE_WIDTH, GARAGE_NORTH_FACE, BUILDING_WIDTH, BUILDING_DEPTH)

    /** Whether [point] lies inside the outer walls of the whole ground floor. */
    fun isWithinBuildingFootprint(point: PlanPoint): Boolean =
        point.isWithin(houseFootprint) || point.isWithin(garageFootprint)

    /** Whether [point] lies inside the outer walls of the attic, which is the house alone. */
    fun isWithinHouseFootprint(point: PlanPoint): Boolean = point.isWithin(houseFootprint)

    private fun PlanPoint.isWithin(rectangle: List<PlanPoint>): Boolean {
        val minX = rectangle.minOf { it.x }
        val maxX = rectangle.maxOf { it.x }
        val minZ = rectangle.minOf { it.z }
        val maxZ = rectangle.maxOf { it.z }
        return x >= minX - EDGE_TOLERANCE && x <= maxX + EDGE_TOLERANCE &&
            z >= minZ - EDGE_TOLERANCE && z <= maxZ + EDGE_TOLERANCE
    }

    /**
     * How far outside a footprint a traced vertex may fall and still count as on
     * its edge. One centimetre: a room zone is bounded by wall faces derived
     * from the same grid, so it can only reach the footprint edge exactly, and
     * anything beyond a centimetre is a trace that escaped the building.
     */
    private const val EDGE_TOLERANCE = 0.01
}
