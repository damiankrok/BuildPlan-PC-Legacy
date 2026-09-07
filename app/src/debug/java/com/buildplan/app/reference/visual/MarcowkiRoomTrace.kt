package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.reference.MarcowkiReferenceProject
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import kotlin.math.abs

/** How much a traced room zone can be relied on. */
enum class TraceCertainty {

    /**
     * The zone is bounded on every side by something the source actually draws:
     * a wall, or an outer face.
     */
    TRACED,

    /**
     * At least one boundary of the zone is not drawn in the source, so it was
     * read from the plan's furniture and room numbering instead.
     *
     * Only the open-plan kitchen, living room and hall are like this. Saying so
     * is the point: an unmarked guess in an otherwise traced model is worse than
     * either an honest guess or no zone at all.
     */
    TRACE_UNCERTAIN,
}

/**
 * One room of the reference project, given a plan outline traced from the
 * current ARCHON drawings.
 *
 * **Not a canonical room polygon.** These stay in the debug reference package
 * and are keyed by canonical [RoomId] without being promoted onto
 * [com.buildplan.app.domain.model.Room], because the domain has no room geometry
 * and a traced zone is not survey data. What they are for is the owner's visual
 * check now, and the room focus of a later stage.
 */
data class RoomTrace(
    val roomId: RoomId,
    val floorId: FloorId,
    /** Plan outline in metres, in the frame described by [MarcowkiPlanGrid]. */
    val outline: List<PlanPoint>,
    val certainty: TraceCertainty,
    /** The published usable area of this room, for comparison — never for derivation. */
    val statedAreaM2: Double,
    val note: String,
) {
    init {
        require(outline.size >= 3) {
            "RoomTrace ${roomId.value} needs at least 3 vertices, got ${outline.size}"
        }
        require(planAreaM2 > 0.1) {
            "RoomTrace ${roomId.value} is degenerate: $planAreaM2 m2"
        }
    }

    /** Enclosed area of the traced outline, regardless of winding. */
    val planAreaM2: Double
        get() = abs(
            outline.indices.sumOf { index ->
                val current = outline[index]
                val next = outline[(index + 1) % outline.size]
                current.x * next.z - next.x * current.z
            },
        ) / 2.0
}

/**
 * The traced plan zone of every room in the reference project.
 *
 * # Zones, not rooms
 *
 * A zone is the floor area a room occupies, bounded by the faces of the walls
 * around it. It is built from [MarcowkiPlanGrid] and nothing else, so it cannot
 * disagree with the walls [MarcowkiVisualModelV1] generates from the same lines.
 *
 * # Why the areas do not match the published ones, and why that is fine
 *
 * On the ground floor they very nearly do — every zone lands within about 6 % of
 * its published area, except the two open-plan ones that have no drawn boundary
 * to trace. On the attic they are systematically 20 to 25 % larger, and that is
 * the roof rather than the trace: see
 * [MarcowkiSourceEvidence.ATTIC_AREA_NOTE]. Neither number was adjusted to make
 * the other look better.
 */
object MarcowkiRoomTrace {

    private val groundFloorId: FloorId = MarcowkiReferenceProject.groundFloorId
    private val atticId: FloorId = MarcowkiReferenceProject.atticId

    // --- Ground-floor wall faces the zones are bounded by ---

    private val westFace = Grid.exteriorFace(Grid.X_WEST_WALL, towardsPositive = true)
    private val northFace = Grid.exteriorFace(Grid.Z_NORTH_WALL, towardsPositive = true)
    private val southFace = Grid.exteriorFace(Grid.Z_SOUTH_WALL, towardsPositive = false)
    private val houseEastFace = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false)
    private val garageWestFace = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = true)
    private val garageEastFace = Grid.exteriorFace(Grid.X_GARAGE_EAST_WALL, towardsPositive = false)
    private val garageNorthFace = Grid.exteriorFace(Grid.Z_GARAGE_NORTH_WALL, towardsPositive = true)

    /** Parter — nine zones, one per canonical ground-floor room. */
    val groundFloor: List<RoomTrace> = listOf(
        zone(
            floorId = groundFloorId,
            slug = "salon-jadalnia",
            statedAreaM2 = 29.52,
            minX = westFace,
            minZ = northFace,
            maxX = houseEastFace,
            maxZ = Grid.Z_GF_LIVING_SOUTH,
            note = "The printed 700 x 414 of room 4, taken between the west and party " +
                "wall faces. Open to the kitchen on its south side.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "kuchnia",
            statedAreaM2 = 9.63,
            minX = westFace,
            minZ = Grid.Z_GF_LIVING_SOUTH,
            maxX = Grid.partitionFace(Grid.X_GF_HALL_WEST, towardsPositive = true),
            maxZ = Grid.partitionFace(Grid.Z_GF_KITCHEN_BATH, towardsPositive = false),
            certainty = TraceCertainty.TRACE_UNCERTAIN,
            note = "Depth is the printed 265 of room 3. Its east and north boundaries are " +
                "open-plan and undrawn, so the split against the hall and living room is " +
                "read from the furniture and room numbers, not traced from a wall.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "hol",
            statedAreaM2 = 9.18,
            minX = Grid.partitionFace(Grid.X_GF_HALL_WEST, towardsPositive = true),
            minZ = Grid.Z_GF_LIVING_SOUTH,
            maxX = Grid.partitionFace(Grid.X_GF_HALL_EAST, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_GF_HALL_VESTIBULE, towardsPositive = false),
            certainty = TraceCertainty.TRACE_UNCERTAIN,
            note = "Walled east and south, open north and west into the kitchen and " +
                "living room. The traced zone runs about 1.1 m2 over the published area " +
                "for the same reason the kitchen runs under it.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "spizarnia",
            statedAreaM2 = 1.44,
            minX = Grid.partitionFace(Grid.X_GF_HALL_EAST, towardsPositive = true),
            minZ = Grid.Z_GF_LIVING_SOUTH,
            maxX = Grid.partitionFace(Grid.X_GF_PANTRY_EAST, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_GF_PANTRY_SOUTH, towardsPositive = false),
            note = "The printed 160 depth of room 5, in the closet between the hall and " +
                "the stairwell.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "lazienka",
            statedAreaM2 = 3.95,
            minX = westFace,
            minZ = Grid.partitionFace(Grid.Z_GF_KITCHEN_BATH, towardsPositive = true),
            maxX = Grid.partitionFace(Grid.X_GF_HALL_WEST, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_GF_BATH_BEDROOM, towardsPositive = false),
            note = "The printed 142 depth of room 6, walled on all four sides.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "pokoj",
            statedAreaM2 = 9.18,
            minX = westFace,
            minZ = Grid.partitionFace(Grid.Z_GF_BATH_BEDROOM, towardsPositive = true),
            maxX = Grid.partitionFace(Grid.X_GF_HALL_WEST, towardsPositive = false),
            maxZ = southFace,
            note = "The printed 290 x 325 of room 7, walled on all four sides.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "wiatrolap",
            statedAreaM2 = 3.70,
            minX = Grid.partitionFace(Grid.X_GF_HALL_WEST, towardsPositive = true),
            minZ = Grid.partitionFace(Grid.Z_GF_HALL_VESTIBULE, towardsPositive = true),
            maxX = Grid.partitionFace(Grid.X_GF_HALL_EAST, towardsPositive = false),
            maxZ = southFace,
            note = "The printed 190 x 203 of room 1, at the front door.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "kotlownia",
            statedAreaM2 = 5.80,
            minX = Grid.partitionFace(Grid.X_GF_HALL_EAST, towardsPositive = true),
            minZ = Grid.partitionFace(Grid.Z_GF_BOILER_NORTH, towardsPositive = true),
            maxX = houseEastFace,
            maxZ = southFace,
            note = "The printed 196 x 312 of room 8, between the vestibule and the garage.",
        ),
        zone(
            floorId = groundFloorId,
            slug = "garaz",
            statedAreaM2 = 24.10,
            minX = garageWestFace,
            minZ = garageNorthFace,
            maxX = garageEastFace,
            maxZ = southFace,
            note = "The printed 375 x 660 of room 9, taken between the garage wall faces.",
        ),
    )

    /**
     * Poddasze — nine zones.
     *
     * The bathroom is the one non-rectangle: the west block is 0.54 m wider
     * north of the corridor wall than south of it, which both plans show and the
     * zone follows rather than squaring off.
     */
    val attic: List<RoomTrace> = listOf(
        zone(
            floorId = atticId,
            slug = "korytarz",
            statedAreaM2 = 6.17,
            minX = Grid.partitionFace(Grid.X_UF_CORRIDOR_WEST, towardsPositive = true),
            minZ = Grid.partitionFace(Grid.Z_UF_BEDROOM_WARDROBE, towardsPositive = true),
            maxX = Grid.partitionFace(Grid.X_UF_CORRIDOR_EAST, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_UF_CORRIDOR_SOUTH, towardsPositive = false),
            note = "Room 1, the circulation spine between the two bedroom pairs. Walled " +
                "on all four sides and the only attic zone whose traced area lands within " +
                "7 % of the published one, because a corridor has no roof slope to lose.",
        ),
        zone(
            floorId = atticId,
            slug = "pokoj-1",
            statedAreaM2 = 10.25,
            minX = Grid.partitionFace(Grid.X_UF_WARDROBE_EAST, towardsPositive = true),
            minZ = Grid.partitionFace(Grid.Z_UF_CORRIDOR_SOUTH, towardsPositive = true),
            maxX = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
            maxZ = southFace,
            note = "The printed 398 x 325 of room 2, in the south-east corner.",
        ),
        zone(
            floorId = atticId,
            slug = "garderoba-1",
            statedAreaM2 = 5.19,
            minX = westFace,
            minZ = Grid.partitionFace(Grid.Z_UF_BATH_WARDROBE, towardsPositive = true),
            maxX = Grid.partitionFace(Grid.X_UF_WARDROBE_EAST, towardsPositive = false),
            maxZ = southFace,
            note = "The printed 290 x 235 of room 3, in the south-west corner.",
        ),
        RoomTrace(
            roomId = roomId(atticId, "lazienka"),
            floorId = atticId,
            outline = listOf(
                PlanPoint(westFace, Grid.partitionFace(Grid.Z_UF_LAUNDRY_BATH, true)),
                PlanPoint(
                    Grid.partitionFace(Grid.X_UF_CORRIDOR_WEST, false),
                    Grid.partitionFace(Grid.Z_UF_LAUNDRY_BATH, true),
                ),
                PlanPoint(
                    Grid.partitionFace(Grid.X_UF_CORRIDOR_WEST, false),
                    Grid.partitionFace(Grid.Z_UF_CORRIDOR_SOUTH, false),
                ),
                PlanPoint(
                    Grid.partitionFace(Grid.X_UF_WARDROBE_EAST, false),
                    Grid.partitionFace(Grid.Z_UF_CORRIDOR_SOUTH, false),
                ),
                PlanPoint(
                    Grid.partitionFace(Grid.X_UF_WARDROBE_EAST, false),
                    Grid.partitionFace(Grid.Z_UF_BATH_WARDROBE, false),
                ),
                PlanPoint(westFace, Grid.partitionFace(Grid.Z_UF_BATH_WARDROBE, false)),
            ),
            certainty = TraceCertainty.TRACED,
            statedAreaM2 = 6.40,
            note = "The printed 248 depth of room 4. Its east boundary steps west by " +
                "0.54 m where the corridor wall ends, which is why this zone is an L.",
        ),
        zone(
            floorId = atticId,
            slug = "pralnia",
            statedAreaM2 = 5.59,
            minX = westFace,
            minZ = Grid.partitionFace(Grid.Z_UF_BEDROOM_LAUNDRY, towardsPositive = true),
            maxX = Grid.partitionFace(Grid.X_UF_CORRIDOR_WEST, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_UF_LAUNDRY_BATH, towardsPositive = false),
            note = "The printed 202 depth of room 5, between the bedroom and the bathroom.",
        ),
        zone(
            floorId = atticId,
            slug = "pokoj-2",
            statedAreaM2 = 12.57,
            minX = westFace,
            minZ = northFace,
            maxX = Grid.partitionFace(Grid.X_UF_CORRIDOR_WEST, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_UF_BEDROOM_LAUNDRY, towardsPositive = false),
            note = "The printed 344 x 449 of room 6, the largest attic bedroom.",
        ),
        zone(
            floorId = atticId,
            slug = "pokoj-3",
            statedAreaM2 = 9.02,
            minX = Grid.partitionFace(Grid.X_UF_CORRIDOR_WEST, towardsPositive = true),
            minZ = northFace,
            maxX = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_UF_BEDROOM_WARDROBE, towardsPositive = false),
            note = "The printed 344 x 325 of room 7, in the north-east corner.",
        ),
        zone(
            floorId = atticId,
            slug = "garderoba-2",
            statedAreaM2 = 1.62,
            minX = Grid.partitionFace(Grid.X_UF_CORRIDOR_EAST, towardsPositive = true),
            minZ = Grid.partitionFace(Grid.Z_UF_BEDROOM_WARDROBE, towardsPositive = true),
            maxX = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_UF_BEDROOM_LAUNDRY, towardsPositive = false),
            note = "Room 8, the small walk-in off the north-east bedroom. The published " +
                "1.62 m2 is a little over half the traced floor, which is what the roof " +
                "slope takes from a space this close to the eaves.",
        ),
        zone(
            floorId = atticId,
            slug = "schody",
            statedAreaM2 = 5.63,
            minX = Grid.partitionFace(Grid.X_UF_CORRIDOR_EAST, towardsPositive = true),
            minZ = Grid.partitionFace(Grid.Z_UF_BEDROOM_LAUNDRY, towardsPositive = true),
            maxX = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
            maxZ = Grid.partitionFace(Grid.Z_UF_CORRIDOR_SOUTH, towardsPositive = false),
            note = "Room 9, the stairwell. Counted on this storey by the source even " +
                "though the flight starts below, and the ground-floor void beneath it " +
                "traces to 5.4 m2 against the published 5.63 — the check that told us " +
                "the two plans were being read at the same scale.",
        ),
    )

    /** Every traced zone, ground floor first. */
    val all: List<RoomTrace> = groundFloor + attic

    /** The zone of one room, or null when it has none. */
    fun of(roomId: RoomId): RoomTrace? = all.firstOrNull { it.roomId == roomId }

    private fun zone(
        floorId: FloorId,
        slug: String,
        statedAreaM2: Double,
        minX: Double,
        minZ: Double,
        maxX: Double,
        maxZ: Double,
        certainty: TraceCertainty = TraceCertainty.TRACED,
        note: String,
    ): RoomTrace = RoomTrace(
        roomId = roomId(floorId, slug),
        floorId = floorId,
        outline = Grid.rectangle(minX, minZ, maxX, maxZ),
        certainty = certainty,
        statedAreaM2 = statedAreaM2,
        note = note,
    )

    /**
     * The canonical room id, spelled the way
     * [com.buildplan.app.reference.MarcowkiReferenceProject] spells it.
     *
     * Not re-declared as literals: an id typed out here that drifted from the
     * canonical one would attach a zone to a room that does not exist, and
     * [com.buildplan.app.domain.model.Building] rejects exactly that — so the
     * model refuses to build rather than silently losing a room.
     */
    private fun roomId(floorId: FloorId, slug: String): RoomId =
        RoomId("${floorId.value}-$slug")
}
