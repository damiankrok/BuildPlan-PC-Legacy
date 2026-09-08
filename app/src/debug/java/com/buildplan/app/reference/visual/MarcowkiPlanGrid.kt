package com.buildplan.app.reference.visual

import com.buildplan.app.geometry.PlanPoint
import kotlin.math.cos
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

    /**
     * The plan range over which the roof underside is above [y], or `null` when
     * it never is.
     *
     * The inverse of [roofUndersideAt], and the reason a gable can be glazed
     * without the glazing coming out through the roof: the answer is always one
     * interval centred on the ridge, because the roof rises to the ridge from
     * both eaves at the same pitch.
     */
    fun roofAbove(y: Double): ClosedFloatingPointRange<Double>? {
        if (y >= RIDGE_Y) return null
        val inset = ((y - EAVES_Y) / tan(Math.toRadians(ROOF_PITCH_DEGREES))).coerceAtLeast(0.0)
        if (inset >= RIDGE_X) return null
        return inset..(HOUSE_WIDTH - inset)
    }

    // ---------------------------------------------------------------------
    // Gable portals
    // ---------------------------------------------------------------------

    /**
     * How far the eaves walls run past each gable wall, north and south.
     *
     * Equal to [GABLE_OVERHANG] and traced independently of it: on both plans
     * the west and east wall hatch continues 38 px past the north outer face and
     * 36 px past the south one, on both storeys. So the roof's 1.00 m projection
     * is not a bare soffit — the walls come with it, and each gable end is a
     * portal with two solid cheeks. It is the trait that makes this house look
     * like itself, and the first model had none of it.
     */
    const val PORTAL_DEPTH: Double = GABLE_OVERHANG

    /** Outer face of the north portal, one portal depth north of the house. */
    const val Z_PORTAL_NORTH_FACE: Double = -PORTAL_DEPTH

    /** Outer face of the south portal. */
    const val Z_PORTAL_SOUTH_FACE: Double = BUILDING_DEPTH + PORTAL_DEPTH

    /**
     * Thickness of the portal cheeks — the four wall ends that run past the
     * gables — and therefore of the frame they continue into.
     *
     * Thicker than the eaves wall they extend, and the source says so twice.
     * On the upper-floor plan the cheek hatch spans 24 to 25 px at 37.77 px/m
     * in all four portals while the eaves wall beside it spans 17, so the
     * cheek is 0.64 m against the wall's 0.44; and on the front and garden
     * elevations the frame leg reads 15 to 16 px at 25.4 px/m, 0.59 to
     * 0.63 m. The ground-floor plan draws its cheeks narrower, at 18 px, and
     * the model does not follow it there: the elevations draw each leg as one
     * straight band from the ground to the mitre, and a leg that stepped by
     * 0.2 m at the storey line would be a jog the source does not have. The
     * upper-plan reading is used for both storeys and the disagreement is
     * recorded in [MarcowkiSourceEvidence.tracedFeatures].
     */
    const val PORTAL_CHEEK_THICKNESS: Double = 0.64

    /**
     * Where the south balcony, its band and its guarding begin.
     *
     * The upper-floor plan stipples the south portal floor only east of the
     * line at 173.5 px, which is 3.35 m from the west outer face at the plan
     * scale and lies on the partition between the wardrobe and the bedroom
     * ([X_UF_WARDROBE_EAST]); the front elevation agrees, starting the dark
     * band at 3.2 m. West of it the portal is open through both storeys: the
     * ground plan puts a planting bed there, in front of the bedroom window,
     * and the elevation shows the gable cladding running from the ground to
     * the roof. STAGE-013C floored the whole width to avoid a slot in the
     * cheek; the slot is now closed by the slab pieces inside the cheeks, so
     * the balcony can stop where the source stops it.
     */
    const val X_SOUTH_BALCONY_WEST: Double = X_UF_WARDROBE_EAST

    /**
     * Height of the balustrade across each portal at attic level.
     *
     * A display assumption. Both plans draw the balcony edge as a single line
     * with no height against it, and the published renders show a frameless
     * glass balustrade; 1.10 m is the usual guarding height and is what the
     * model draws, so the portal reads as a balcony rather than as a hole.
     */
    const val BALUSTRADE_HEIGHT: Double = 1.10

    /**
     * Thickness of that balustrade. Display only — glass has no traced section,
     * and 0.02 m is a sheet rather than a wall, which is what the renders show
     * and what the transparent presentation needs to read as glass.
     */
    const val BALUSTRADE_THICKNESS: Double = 0.02

    // ---------------------------------------------------------------------
    // Facade bands — traced from the four current elevations
    // ---------------------------------------------------------------------

    /**
     * Width of the frame that runs around each gable portal.
     *
     * The trait the owner names first. On the front and garden elevations each
     * gable is a picture frame: a band of constant width up both cheeks and
     * along both slopes, with the facade recessed a metre behind it. It is the
     * cheek's own thickness rather than a number of its own, because the frame
     * *is* the cheek carried up over the roof — one continuous band in one
     * plane, mitred where the leg meets the slope. STAGE-013C carried the
     * 0.44 m eaves wall here; the cheeks are traced at 0.64 m and the frame
     * follows them — see [PORTAL_CHEEK_THICKNESS].
     */
    const val GABLE_FRAME_WIDTH: Double = PORTAL_CHEEK_THICKNESS

    /** How far the frame's inner edge sits below the roof, measured vertically. */
    val GABLE_FRAME_VERTICAL_DROP: Double =
        GABLE_FRAME_WIDTH / cos(Math.toRadians(ROOF_PITCH_DEGREES))

    /**
     * Y of the inner mitre corner, where the frame's raking inner edge meets the
     * leg's inner face at x = [GABLE_FRAME_WIDTH].
     *
     * Below the eaves, not at them: the inner edge runs parallel to the roof
     * one frame width beneath it, and at the leg's inner face the roof is only
     * 0.54 m above the eaves line while the frame is 0.84 m deep vertically.
     * So the corner falls 0.30 m down the leg, which is where both elevations
     * draw it — a sharp mitre, not a horizontal shelf at the eaves.
     */
    val GABLE_FRAME_INNER_CORNER_Y: Double =
        EAVES_Y + GABLE_FRAME_WIDTH * tan(Math.toRadians(ROOF_PITCH_DEGREES)) -
            GABLE_FRAME_VERTICAL_DROP

    /**
     * How far the frame's front face stands in front of the portal face.
     *
     * The frame's mitre overlaps the top corner of the cheek's end face, and
     * two surfaces in one plane flicker against each other. Five millimetres
     * is invisible at any distance the model is viewed from and enough for the
     * depth test to settle it. A display value, not a trace.
     */
    const val GABLE_FRAME_PROUD: Double = 0.005

    /**
     * How far below the roof plane the frame bar's top face lies.
     *
     * The bar's top *is* the roof over the portal, and the roof facet already
     * draws that plane; a second face in it would flicker. One centimetre
     * lower it is hidden under the roof and appears only when the roof is
     * taken off, which is when a bar with no top would read as a channel.
     */
    const val GABLE_FRAME_TOP_DROP: Double = 0.01

    // ---------------------------------------------------------------------
    // Eaves fascia — traced from both side elevations
    // ---------------------------------------------------------------------

    /**
     * Depth of the dark band both side elevations draw between the tiles and
     * the render along the whole length of each long facade.
     *
     * The roof's own edge seen end-on, on a roof the page calls eaveless: 6 px
     * at 25.4 px/m on both elevations, running the full 14.6 m from portal
     * face to portal face. It is the line that makes the roof read as a thin
     * plate sitting on the walls rather than as a solid wedge, and it is drawn
     * as a trim on the wall face rather than as a thickness given to the roof
     * facets, so that the 150.4 m2 the facets reconcile with the published
     * roof area stays exactly what it was.
     */
    const val FASCIA_DEPTH: Double = 0.24

    /**
     * How far the fascia stands proud of the wall face. Display only: a trim
     * laid flat on the wall would share its plane, and a trim has to stand off
     * it by something to be a trim at all.
     */
    const val FASCIA_PROUD: Double = 0.04

    /** Underside of the fascia: its top is the eaves line. */
    val FASCIA_BASE_Y: Double = EAVES_Y - FASCIA_DEPTH

    // ---------------------------------------------------------------------
    // Roof stacks — traced across three views
    // ---------------------------------------------------------------------

    /**
     * The plan footprint of one stack above the roof, as a rectangle.
     *
     * Two of them, and each is placed by three views agreeing: the garage-side
     * elevation shows both, 15 px wide at 25.4 px/m and centred 10.2 m and
     * 5.7 m south of the north portal face; the front and garden elevations
     * each show exactly one, which is what two stacks on one X do, at 1.5 to
     * 2.1 m east of the ridge; and the section draws the same stack 1.5 to
     * 2.1 m east of its apex. The one over the living room is the fireplace
     * flue the ground plan marks at that spot; the one over the boiler room is
     * the boiler's.
     */
    data class StackTrace(val minX: Double, val minZ: Double, val maxX: Double, val maxZ: Double)

    /** Over the living room fireplace. */
    val STACK_LIVING_ROOM: StackTrace = StackTrace(5.45, 4.40, 6.05, 5.00)

    /** Over the boiler room. */
    val STACK_BOILER_ROOM: StackTrace = StackTrace(5.45, 8.90, 6.05, 9.50)

    val allStacks: List<StackTrace> = listOf(STACK_LIVING_ROOM, STACK_BOILER_ROOM)

    /**
     * Top of both stacks. The front elevation puts the visible stack 6 px above
     * the ridge apex at 25.4 px/m; the section agrees to within a pixel.
     */
    const val STACK_TOP_ABOVE_RIDGE: Double = 0.24

    val STACK_TOP_Y: Double = RIDGE_Y + STACK_TOP_ABOVE_RIDGE

    /**
     * How far below the roof plane a stack is started, so that it emerges from
     * the slope instead of balancing on it. Display only: the source shows the
     * stacks above the roof and nothing of their shafts.
     */
    const val STACK_BURIED_DEPTH: Double = 0.30

    /**
     * Top of the horizontal band that ties the house and the garage together.
     *
     * All four elevations draw one unbroken line at this level: it is the front
     * edge of the balcony inside each gable portal, and it is the top of the
     * garage. Traced at 3.06 to 3.08 m above the drawn ground line on the front
     * and garden elevations, which is the attic floor level the section states —
     * so the band's top is a published level rather than a measured one.
     */
    const val STOREY_BAND_TOP_Y: Double = UPPER_FLOOR_Y

    /**
     * Underside of that band, and the garage's dimensioned clear height.
     *
     * Derived from two stated levels rather than chosen: the band reaches from
     * the ceiling the section dimensions inside the garage up to the attic floor
     * it levels. The elevations trace the visible depth at 0.47 to 0.72 m
     * depending on which of them is measured, and 0.54 m sits inside that spread
     * while owing nothing to the raster.
     */
    const val STOREY_BAND_BASE_Y: Double = GARAGE_CLEAR_HEIGHT

    /** Depth of the band on the facade. */
    val STOREY_BAND_DEPTH: Double = STOREY_BAND_TOP_Y - STOREY_BAND_BASE_Y

    /**
     * How thick the band is where it stands free of the storey slab behind it.
     *
     * The band replaces the outermost 0.12 m of the balcony slab rather than
     * being laid on top of it: a fascia in front of a slab face would be two
     * surfaces in the same plane, which flicker against each other as the camera
     * moves. Taking the slab back by exactly this much leaves one face where the
     * source draws one line.
     */
    const val STOREY_BAND_THICKNESS: Double = PARTITION_THICKNESS

    // ---------------------------------------------------------------------
    // Openings — traced from the window and door schedule on both plans
    // ---------------------------------------------------------------------

    /**
     * One opening as the plans dimension it.
     *
     * [nearEdge] is an absolute plan coordinate on the wall's own axis — X for a
     * wall running east-west, Z for one running north-south — rather than a
     * distance along the wall. The distance is what
     * [com.buildplan.app.geometry.WallOpening] wants, but it is measured from
     * whichever end the model happens to start that wall at, and this stage
     * extends four walls past their old ends to build the portals. A coordinate
     * survives that; a distance would have silently moved every window on those
     * walls by a metre.
     *
     * The circled labels on the plans read *width/height* in centimetres, and
     * those two numbers are [width] and [height]. [sill] is not in the schedule:
     * it is 0.00 for everything that reaches the floor, which is every door and
     * every full-height glazing here, and is derived once for the one window
     * that does not — see [MarcowkiSourceEvidence.displayAssumptions].
     */
    data class OpeningTrace(
        val nearEdge: Double,
        val width: Double,
        val sill: Double,
        val height: Double,
        /** The label the plan prints for this opening, kept for the evidence ledger. */
        val label: String,
    ) {
        val farEdge: Double get() = nearEdge + width
        val head: Double get() = sill + height
    }

    /**
     * The head every full-height opening on the ground floor reaches.
     *
     * Not assumed: five of the nine ground-floor openings are dimensioned 230
     * high and sit on the floor, so the 2.30 line is printed rather than chosen.
     * It matters because it is what the one raised sill is derived from.
     */
    const val GROUND_OPENING_HEAD: Double = 2.30

    /** Room 4's glazed wall onto the north terrace. Printed 470/230. */
    val GF_LIVING_NORTH_DOOR: OpeningTrace =
        OpeningTrace(nearEdge = 2.28, width = 4.70, sill = 0.0, height = 2.30, label = "470/230")

    /** Room 4's glazed door onto the east terrace. Printed 300/230. */
    val GF_LIVING_EAST_DOOR: OpeningTrace =
        OpeningTrace(nearEdge = 0.90, width = 3.00, sill = 0.0, height = 2.30, label = "300/230")

    /** Room 4's west window. Printed 90/230, and full height, as the side elevation draws it. */
    val GF_LIVING_WEST_WINDOW: OpeningTrace =
        OpeningTrace(nearEdge = 3.67, width = 0.90, sill = 0.0, height = 2.30, label = "90/230")

    /**
     * Room 3's west window. Printed 140/140 — the only opening here that does
     * not reach the floor, so its 0.90 sill is the one derived level.
     */
    val GF_KITCHEN_WINDOW: OpeningTrace = OpeningTrace(
        nearEdge = 5.39,
        width = 1.40,
        sill = GROUND_OPENING_HEAD - 1.40,
        height = 1.40,
        label = "140/140",
    )

    /** Room 7's south window. Printed 110/230. */
    val GF_BEDROOM_WINDOW: OpeningTrace =
        OpeningTrace(nearEdge = 1.40, width = 1.10, sill = 0.0, height = 2.30, label = "110/230")

    /** The front door into room 1. Printed 105/210. */
    val GF_ENTRANCE_DOOR: OpeningTrace =
        OpeningTrace(nearEdge = 4.18, width = 1.05, sill = 0.0, height = 2.10, label = "105/210")

    /** The garage door. Printed 275/225, inside the dimensioned 252 clear height. */
    val GF_GARAGE_DOOR: OpeningTrace =
        OpeningTrace(nearEdge = 8.58, width = 2.75, sill = 0.0, height = 2.25, label = "275/225")

    /** The garage's own door to the outside, in its north wall. Printed 100/210. */
    val GF_GARAGE_SIDE_DOOR: OpeningTrace =
        OpeningTrace(nearEdge = 9.93, width = 1.00, sill = 0.0, height = 2.10, label = "100/210")

    /**
     * The door between the garage and the boiler room.
     *
     * The only opening here the schedule does not label — internal doors are not
     * scheduled on this plan — so its 0.90 m width is measured from the gap in
     * the wall hatch and its 2.10 head is taken from the labelled doors beside
     * it. It is modelled because it is the one thing that makes the cutaway show
     * how the garage and the house connect.
     */
    val GF_BOILER_GARAGE_DOOR: OpeningTrace =
        OpeningTrace(nearEdge = 10.46, width = 0.90, sill = 0.0, height = 2.10, label = "—")

    /**
     * How tall a gable glazing is allowed to get.
     *
     * The gable openings are the one place where the printed height is a
     * *maximum* rather than a constant: their heads follow the roof, as both
     * published renders show and as the arithmetic requires — a 3.03 m opening
     * standing at x = 0.95 would come out through a roof plane that is only
     * 2.38 m above the attic floor there. So the model reads 303 and 320 as the
     * height the glazing reaches where the roof is high enough to allow it, and
     * clips it to the roof everywhere else.
     */
    val UF_NORTH_GABLE_WEST_DOOR: OpeningTrace = OpeningTrace(
        nearEdge = 0.95,
        width = 2.34,
        sill = UPPER_FLOOR_Y,
        height = 3.03,
        label = "234/303",
    )

    /** Its mirror image on the other side of the ridge. Printed 234/303. */
    val UF_NORTH_GABLE_EAST_DOOR: OpeningTrace = OpeningTrace(
        nearEdge = 4.61,
        width = 2.34,
        sill = UPPER_FLOOR_Y,
        height = 3.03,
        label = "234/303",
    )

    /** The front gable's glazing, starting at the ridge. Printed 270/320. */
    val UF_SOUTH_GABLE_DOOR: OpeningTrace = OpeningTrace(
        nearEdge = 3.95,
        width = 2.70,
        sill = UPPER_FLOOR_Y,
        height = 3.20,
        label = "270/320",
    )

    /** Every opening the plans schedule, on the facades and the party wall. */
    val scheduledOpenings: List<OpeningTrace> = listOf(
        GF_LIVING_NORTH_DOOR,
        GF_LIVING_EAST_DOOR,
        GF_LIVING_WEST_WINDOW,
        GF_KITCHEN_WINDOW,
        GF_BEDROOM_WINDOW,
        GF_ENTRANCE_DOOR,
        GF_GARAGE_DOOR,
        GF_GARAGE_SIDE_DOOR,
        GF_BOILER_GARAGE_DOOR,
        UF_NORTH_GABLE_WEST_DOOR,
        UF_NORTH_GABLE_EAST_DOOR,
        UF_SOUTH_GABLE_DOOR,
    )

    // ---------------------------------------------------------------------
    // Internal doors — traced from the swing arcs on both plans
    // ---------------------------------------------------------------------

    /**
     * Height of every internal door.
     *
     * The plans schedule no internal door, so the height is a display
     * assumption — see [MarcowkiSourceEvidence.displayAssumptions]. The
     * *positions* are not: each door below is a swing arc both plans draw
     * against a partition, read at the calibrated scale, and each width is
     * the chord of that arc rounded to the nearer of the two leaf sizes the
     * arcs fall into. A door is a fact about circulation, not a decoration:
     * without it the hall is walled from the bathroom and the corridor from
     * every bedroom, which is a plan that does not exist.
     */
    const val INTERNAL_DOOR_HEIGHT: Double = 2.00

    /** Hall to vestibule, in the wall at [Z_GF_HALL_VESTIBULE]; the arc swings north. */
    val GF_DOOR_HALL_VESTIBULE: OpeningTrace = internalDoor(nearEdge = 4.40, width = 0.90, sill = GROUND_FLOOR_Y)

    /** Hall to bathroom, in the wall at [X_GF_HALL_WEST]; the arc swings east into the hall. */
    val GF_DOOR_HALL_BATHROOM: OpeningTrace = internalDoor(nearEdge = 7.45, width = 0.90, sill = GROUND_FLOOR_Y)

    /** Hall to bedroom, in the wall at [X_GF_HALL_WEST]; the arc swings west into the room. */
    val GF_DOOR_HALL_BEDROOM: OpeningTrace = internalDoor(nearEdge = 8.95, width = 0.90, sill = GROUND_FLOOR_Y)

    /** Hall to pantry, in the wall at [X_GF_HALL_EAST]; the arc swings east into the closet. */
    val GF_DOOR_HALL_PANTRY: OpeningTrace = internalDoor(nearEdge = 5.30, width = 0.80, sill = GROUND_FLOOR_Y)

    /** Vestibule to boiler room, in the wall at [X_GF_HALL_EAST]; the arc swings east. */
    val GF_DOOR_VESTIBULE_BOILER: OpeningTrace = internalDoor(nearEdge = 10.40, width = 0.90, sill = GROUND_FLOOR_Y)

    /** Corridor to the north-east bedroom, in the wall at [Z_UF_BEDROOM_WARDROBE]; swings north. */
    val UF_DOOR_CORRIDOR_BEDROOM_NE: OpeningTrace = internalDoor(nearEdge = 4.10, width = 0.90, sill = UPPER_FLOOR_Y)

    /** North-east bedroom to its walk-in, in the same wall; swings south into the closet. */
    val UF_DOOR_BEDROOM_NE_WARDROBE: OpeningTrace = internalDoor(nearEdge = 5.30, width = 0.80, sill = UPPER_FLOOR_Y)

    /**
     * Corridor to the north-west bedroom, in the wall at [X_UF_CORRIDOR_WEST].
     * The two rooms share only 1.17 m of wall, so the arc's position is fixed
     * within a few centimetres by the wall itself.
     */
    val UF_DOOR_CORRIDOR_BEDROOM_NW: OpeningTrace = internalDoor(nearEdge = 3.90, width = 0.90, sill = UPPER_FLOOR_Y)

    /** Corridor to laundry, in the wall at [X_UF_CORRIDOR_WEST]; swings west. */
    val UF_DOOR_CORRIDOR_LAUNDRY: OpeningTrace = internalDoor(nearEdge = 5.15, width = 0.80, sill = UPPER_FLOOR_Y)

    /** Corridor to bathroom, in the wall at [X_UF_CORRIDOR_WEST]; swings west. */
    val UF_DOOR_CORRIDOR_BATHROOM: OpeningTrace = internalDoor(nearEdge = 7.75, width = 0.80, sill = UPPER_FLOOR_Y)

    /** Corridor to the south-east bedroom, in the wall at [Z_UF_CORRIDOR_SOUTH]; swings south. */
    val UF_DOOR_CORRIDOR_BEDROOM_SE: OpeningTrace = internalDoor(nearEdge = 4.10, width = 0.90, sill = UPPER_FLOOR_Y)

    /** South-east bedroom to its wardrobe, in the wall at [X_UF_WARDROBE_EAST]; swings west. */
    val UF_DOOR_BEDROOM_SE_WARDROBE: OpeningTrace = internalDoor(nearEdge = 9.90, width = 0.80, sill = UPPER_FLOOR_Y)

    /** Every internal door, ground floor first. */
    val allInternalDoors: List<OpeningTrace> = listOf(
        GF_DOOR_HALL_VESTIBULE,
        GF_DOOR_HALL_BATHROOM,
        GF_DOOR_HALL_BEDROOM,
        GF_DOOR_HALL_PANTRY,
        GF_DOOR_VESTIBULE_BOILER,
        UF_DOOR_CORRIDOR_BEDROOM_NE,
        UF_DOOR_BEDROOM_NE_WARDROBE,
        UF_DOOR_CORRIDOR_BEDROOM_NW,
        UF_DOOR_CORRIDOR_LAUNDRY,
        UF_DOOR_CORRIDOR_BATHROOM,
        UF_DOOR_CORRIDOR_BEDROOM_SE,
        UF_DOOR_BEDROOM_SE_WARDROBE,
    )

    /** Every traced opening — scheduled and internal — for the fidelity ledger and its tests. */
    val allOpenings: List<OpeningTrace> = scheduledOpenings + allInternalDoors

    private fun internalDoor(nearEdge: Double, width: Double, sill: Double): OpeningTrace =
        OpeningTrace(
            nearEdge = nearEdge,
            width = width,
            sill = sill,
            height = INTERNAL_DOOR_HEIGHT,
            label = "—",
        )

    // ---------------------------------------------------------------------
    // Rooflights
    // ---------------------------------------------------------------------

    /**
     * The plan projection of one rooflight, as the upper plan dashes it in.
     *
     * Three of them, all labelled 78/118. They are given as plan rectangles
     * because that is how they were traced; their height comes from the roof
     * plane they lie in, which is why they are not [OpeningTrace]s.
     */
    data class RooflightTrace(val minX: Double, val minZ: Double, val maxX: Double, val maxZ: Double)

    /** Over room 5, in the west slope. */
    val ROOFLIGHT_WEST_NORTH: RooflightTrace = RooflightTrace(0.45, 5.60, 1.30, 6.39)

    /** Over room 4, in the west slope. */
    val ROOFLIGHT_WEST_SOUTH: RooflightTrace = RooflightTrace(0.45, 7.74, 1.30, 8.53)

    /** Over the stairwell, in the east slope. */
    val ROOFLIGHT_EAST: RooflightTrace = RooflightTrace(6.59, 7.74, 7.44, 8.53)

    val allRooflights: List<RooflightTrace> =
        listOf(ROOFLIGHT_WEST_NORTH, ROOFLIGHT_WEST_SOUTH, ROOFLIGHT_EAST)

    /** How far a rooflight is drawn proud of the roof plane it sits in. */
    const val ROOFLIGHT_PROUD_OF_ROOF: Double = 0.08

    // ---------------------------------------------------------------------
    // Stair
    // ---------------------------------------------------------------------

    /**
     * The stair's footprint, and the core it wraps.
     *
     * Both plans draw the same three flights turning twice around a rectangular
     * core, and both agree on where they are: the flight the ground plan draws
     * across the south of the stairwell lands within 5 cm of the one the upper
     * plan draws there. The travel direction is not guessed either — the upper
     * plan's arrow on the top flight points west, into the attic corridor, which
     * fixes the whole sequence backwards from its arrival.
     */
    const val STAIR_WEST_X: Double = 5.35
    const val STAIR_EAST_X: Double = 7.47
    const val STAIR_NORTH_Z: Double = 5.18
    const val STAIR_SOUTH_Z: Double = 8.80

    /** East face of the core the flights turn around; its west face is [STAIR_WEST_X]. */
    const val STAIR_CORE_EAST_X: Double = 6.46
    const val STAIR_CORE_NORTH_Z: Double = 6.18
    const val STAIR_CORE_SOUTH_Z: Double = 7.77

    /**
     * How the seventeen risers are shared between the three straight runs.
     *
     * South run, east run, north run — in climbing order. The plans draw the
     * treads, and at the calibrated scale the south and north runs carry four
     * each and the east run five, at a going of about 0.28 m; the watermark
     * crosses the middle of the east run, so its count is the one read from
     * the two runs beside it and the length that is left. Together with the
     * two winders at each turn that is seventeen, which is what 3.06 m of rise
     * at a climbable 0.18 m needs — the two readings agree, which is why the
     * count is listed as a display subdivision and not as a guess.
     */
    val STAIR_RUN_RISERS: List<Int> = listOf(4, 5, 4)

    /**
     * How many winders turn each corner.
     *
     * Both plans draw the corner squares of the stairwell cut by a diagonal
     * rather than left as landings: the stair turns on winders, not on
     * quarter landings, and a model with flat landings there would teach the
     * wrong turning logic. Two per corner — the square split on its diagonal
     * — is the simplest shape that says so.
     */
    const val STAIR_WINDERS_PER_TURN: Int = 2

    /** Every riser from 0.00 to +3.06: three runs and two turns. */
    val STAIR_RISER_COUNT: Int = STAIR_RUN_RISERS.sum() + 2 * STAIR_WINDERS_PER_TURN

    /** Rise of one step, from the two levels the section states. */
    val STAIR_RISER_HEIGHT: Double = (UPPER_FLOOR_Y - GROUND_FLOOR_Y) / STAIR_RISER_COUNT

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
     * attic partition centrelines, the two traced thicknesses, the traced gable
     * overhang and the portal depth measured beside it, both edges of every
     * traced opening, all four edges of every rooflight, the seven lines that
     * place the stair and its core, the two lines the elevations added in
     * STAGE-013C — the inner edge of the gable frame and the lower edge of the
     * storey band — and the four STAGE-013D added: the cheek thickness, the
     * west edge of the south balcony, the underside of the eaves fascia and
     * the top of the roof stacks, plus all four edges of each stack. The
     * internal doors STAGE-013E traced are counted with the openings, two
     * edges each.
     */
    val TRACED_LINE_COUNT: Int =
        6 + 9 + 8 + 2 + 1 + 1 +
            2 * allOpenings.size +
            4 * allRooflights.size +
            7 + 2 +
            4 + 4 * allStacks.size

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
