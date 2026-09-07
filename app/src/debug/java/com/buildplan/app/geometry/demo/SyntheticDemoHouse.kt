package com.buildplan.app.geometry.demo

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.BuildingId
import com.buildplan.app.domain.model.Floor
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.Room
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.units.Quantity
import com.buildplan.app.domain.units.UnitOfMeasure
import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.BuildingGeometryPrimitive
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry

/**
 * A **synthetic** two-storey demo house: a semantic [Building] together with the
 * [BuildingGeometry] that draws it.
 *
 * # Synthetic preview geometry — not reconstructed from ARCHON plans
 *
 * Every number below is an invented engineering-demo value. Nothing here is
 * measured, traced, derived or guessed from any real house, any published
 * project, any drawing, any screenshot or any stated area. It is a technical
 * shape whose only purpose is to give the first renderer something with two
 * storeys, real wall thickness and a two-facet gable roof to put on screen
 * before a plan parser exists.
 *
 * In particular it is **not** the house behind
 * `com.buildplan.app.reference.MarcowkiReferenceProject`. That dataset carries
 * source-backed room names and areas and deliberately has **no geometry**,
 * because its source states none; attaching these coordinates to its ids would
 * turn an honest "we do not know the plan" into a fabricated plan. Their ids do
 * not overlap and neither refers to the other.
 *
 * # Why it lives in `src/debug`
 *
 * It is development scaffolding, not product content. The release variant
 * neither compiles nor ships it, so it cannot reach a user, and its tests live
 * in `src/testDebug` alongside it.
 *
 * # The shape
 *
 * A 10 × 8 m rectangle on the plan, walls given by their centrelines:
 *
 * ```
 *  z=8.55  ......... roof eave (north) .........
 *  z=8.00  +-------------------------------+  north wall
 *          |            Kuchnia            |
 *  z=5.00  +-------------------------------+  shared partition, two rooms
 *          |             Salon             |
 *  z=0.00  +-------------------------------+  south wall
 *  z=-0.55 ......... roof eave (south) .........
 *        x=0.00                         x=10.00
 * ```
 *
 * Vertically: a foundation plate from -0.40 to 0.00, ground-floor walls to 2.80,
 * a slab to 3.05, attic walls to 5.55, and a gable ridge at 7.95 over z = 4.00.
 */
object SyntheticDemoHouse {

    // --- Plan extents, metres. Centrelines unless stated otherwise. ---

    private const val PLAN_MIN_X = 0.0
    private const val PLAN_MAX_X = 10.0
    private const val PLAN_MIN_Z = 0.0
    private const val PLAN_MAX_Z = 8.0

    /** Where the partition between the two ground-floor rooms runs. */
    private const val PARTITION_Z = 5.0

    private const val EXTERIOR_WALL_THICKNESS = 0.30
    private const val PARTITION_THICKNESS = 0.12

    // --- Levels, metres. Y is up; 0.0 is the top of the foundation plate. ---

    private const val FOUNDATION_BOTTOM_Y = -0.40
    private const val FOUNDATION_THICKNESS = 0.40

    private const val GROUND_FLOOR_BASE_Y = 0.0
    private const val GROUND_FLOOR_WALL_HEIGHT = 2.80

    private const val SLAB_ELEVATION_Y = GROUND_FLOOR_BASE_Y + GROUND_FLOOR_WALL_HEIGHT
    private const val SLAB_THICKNESS = 0.25

    private const val ATTIC_BASE_Y = SLAB_ELEVATION_Y + SLAB_THICKNESS
    private const val ATTIC_WALL_HEIGHT = 2.50

    private const val EAVE_Y = ATTIC_BASE_Y + ATTIC_WALL_HEIGHT
    private const val RIDGE_Y = EAVE_Y + 2.40
    private const val RIDGE_Z = (PLAN_MIN_Z + PLAN_MAX_Z) / 2.0

    /** How far the roof reaches past the outer wall face. */
    private const val ROOF_OVERHANG = 0.40

    private const val OUTER_FACE_OFFSET = EXTERIOR_WALL_THICKNESS / 2.0
    private const val ROOF_MIN_X = PLAN_MIN_X - OUTER_FACE_OFFSET - ROOF_OVERHANG
    private const val ROOF_MAX_X = PLAN_MAX_X + OUTER_FACE_OFFSET + ROOF_OVERHANG
    private const val ROOF_MIN_Z = PLAN_MIN_Z - OUTER_FACE_OFFSET - ROOF_OVERHANG
    private const val ROOF_MAX_Z = PLAN_MAX_Z + OUTER_FACE_OFFSET + ROOF_OVERHANG

    // --- Identity ---

    val buildingId: BuildingId = BuildingId("b-dom-demo")

    val groundFloorId: FloorId = FloorId("f-demo-parter")
    val atticId: FloorId = FloorId("f-demo-poddasze")

    val livingRoomId: RoomId = RoomId("r-demo-salon")
    val kitchenId: RoomId = RoomId("r-demo-kuchnia")
    val bedroomId: RoomId = RoomId("r-demo-sypialnia")

    /** Belongs to the whole building; drawn as one slab. */
    val foundationId: BuildingElementId = BuildingElementId("e-demo-fundament")

    /** Belongs to the whole building; drawn as **two** facets. */
    val roofId: BuildingElementId = BuildingElementId("e-demo-dach")

    /** One wall, two rooms, one primitive. Not a copy per room. */
    val partitionWallId: BuildingElementId = BuildingElementId("e-demo-sciana-dzialowa")

    val southWallId: BuildingElementId = BuildingElementId("e-demo-sciana-parter-pd")
    val westWallLivingRoomId: BuildingElementId = BuildingElementId("e-demo-sciana-parter-zach-a")
    val eastWallLivingRoomId: BuildingElementId = BuildingElementId("e-demo-sciana-parter-wsch-a")
    val westWallKitchenId: BuildingElementId = BuildingElementId("e-demo-sciana-parter-zach-b")
    val eastWallKitchenId: BuildingElementId = BuildingElementId("e-demo-sciana-parter-wsch-b")
    val northWallId: BuildingElementId = BuildingElementId("e-demo-sciana-parter-pn")

    /** Floor-scoped but linked to no room: the slab belongs to the storey itself. */
    val groundCeilingSlabId: BuildingElementId = BuildingElementId("e-demo-strop-parteru")

    val atticSouthWallId: BuildingElementId = BuildingElementId("e-demo-sciana-poddasze-pd")
    val atticEastWallId: BuildingElementId = BuildingElementId("e-demo-sciana-poddasze-wsch")
    val atticNorthWallId: BuildingElementId = BuildingElementId("e-demo-sciana-poddasze-pn")
    val atticWestWallId: BuildingElementId = BuildingElementId("e-demo-sciana-poddasze-zach")

    // --- Semantic model ---

    /** The canonical semantic building. Geometry-free, exactly like a real one. */
    val building: Building = Building(
        id = buildingId,
        floors = listOf(
            Floor(
                id = groundFloorId,
                name = "Parter",
                order = 0,
                elevation = meters(GROUND_FLOOR_BASE_Y),
                height = meters(GROUND_FLOOR_WALL_HEIGHT),
                rooms = listOf(
                    Room(livingRoomId, "Salon"),
                    Room(kitchenId, "Kuchnia"),
                ),
            ),
            Floor(
                id = atticId,
                name = "Poddasze",
                order = 1,
                elevation = meters(ATTIC_BASE_Y),
                height = meters(ATTIC_WALL_HEIGHT),
                rooms = listOf(Room(bedroomId, "Sypialnia")),
            ),
        ),
        elements = listOf(
            BuildingElement(
                id = foundationId,
                kind = BuildingElementKind.FOUNDATION,
                name = "Płyta fundamentowa",
                scope = BuildingElementScope.WholeBuilding,
            ),
            BuildingElement(
                id = southWallId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna parteru — południe",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(livingRoomId),
            ),
            BuildingElement(
                id = westWallLivingRoomId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna salonu — zachód",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(livingRoomId),
            ),
            BuildingElement(
                id = eastWallLivingRoomId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna salonu — wschód",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(livingRoomId),
            ),
            BuildingElement(
                id = partitionWallId,
                kind = BuildingElementKind.WALL,
                name = "Ściana działowa między salonem a kuchnią",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(livingRoomId, kitchenId),
            ),
            BuildingElement(
                id = westWallKitchenId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna kuchni — zachód",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(kitchenId),
            ),
            BuildingElement(
                id = eastWallKitchenId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna kuchni — wschód",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(kitchenId),
            ),
            BuildingElement(
                id = northWallId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna parteru — północ",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(kitchenId),
            ),
            BuildingElement(
                id = groundCeilingSlabId,
                kind = BuildingElementKind.SLAB,
                name = "Strop nad parterem",
                scope = BuildingElementScope.OnFloor(groundFloorId),
            ),
            BuildingElement(
                id = atticSouthWallId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna poddasza — południe",
                scope = BuildingElementScope.OnFloor(atticId),
                roomIds = setOf(bedroomId),
            ),
            BuildingElement(
                id = atticEastWallId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna poddasza — wschód",
                scope = BuildingElementScope.OnFloor(atticId),
                roomIds = setOf(bedroomId),
            ),
            BuildingElement(
                id = atticNorthWallId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna poddasza — północ",
                scope = BuildingElementScope.OnFloor(atticId),
                roomIds = setOf(bedroomId),
            ),
            BuildingElement(
                id = atticWestWallId,
                kind = BuildingElementKind.WALL,
                name = "Ściana zewnętrzna poddasza — zachód",
                scope = BuildingElementScope.OnFloor(atticId),
                roomIds = setOf(bedroomId),
            ),
            BuildingElement(
                id = roofId,
                kind = BuildingElementKind.ROOF,
                name = "Dach dwuspadowy",
                scope = BuildingElementScope.WholeBuilding,
            ),
        ),
    )

    // --- Geometry ---

    /**
     * The shapes, in draw order. Element ids match [building] exactly, and the
     * roof deliberately owns two primitives.
     */
    val geometry: BuildingGeometry = BuildingGeometry(
        listOf(
            SlabGeometry(
                elementId = foundationId,
                outline = rectangle(
                    minX = PLAN_MIN_X - OUTER_FACE_OFFSET,
                    maxX = PLAN_MAX_X + OUTER_FACE_OFFSET,
                    minZ = PLAN_MIN_Z - OUTER_FACE_OFFSET,
                    maxZ = PLAN_MAX_Z + OUTER_FACE_OFFSET,
                ),
                elevation = FOUNDATION_BOTTOM_Y,
                thickness = FOUNDATION_THICKNESS,
            ),
            groundWall(southWallId, PLAN_MIN_X, PLAN_MIN_Z, PLAN_MAX_X, PLAN_MIN_Z),
            groundWall(westWallLivingRoomId, PLAN_MIN_X, PLAN_MIN_Z, PLAN_MIN_X, PARTITION_Z),
            groundWall(eastWallLivingRoomId, PLAN_MAX_X, PLAN_MIN_Z, PLAN_MAX_X, PARTITION_Z),
            groundWall(
                id = partitionWallId,
                startX = PLAN_MIN_X,
                startZ = PARTITION_Z,
                endX = PLAN_MAX_X,
                endZ = PARTITION_Z,
                thickness = PARTITION_THICKNESS,
            ),
            groundWall(westWallKitchenId, PLAN_MIN_X, PARTITION_Z, PLAN_MIN_X, PLAN_MAX_Z),
            groundWall(eastWallKitchenId, PLAN_MAX_X, PARTITION_Z, PLAN_MAX_X, PLAN_MAX_Z),
            groundWall(northWallId, PLAN_MIN_X, PLAN_MAX_Z, PLAN_MAX_X, PLAN_MAX_Z),
            SlabGeometry(
                elementId = groundCeilingSlabId,
                outline = rectangle(PLAN_MIN_X, PLAN_MAX_X, PLAN_MIN_Z, PLAN_MAX_Z),
                elevation = SLAB_ELEVATION_Y,
                thickness = SLAB_THICKNESS,
            ),
            atticWall(atticSouthWallId, PLAN_MIN_X, PLAN_MIN_Z, PLAN_MAX_X, PLAN_MIN_Z),
            atticWall(atticEastWallId, PLAN_MAX_X, PLAN_MIN_Z, PLAN_MAX_X, PLAN_MAX_Z),
            atticWall(atticNorthWallId, PLAN_MAX_X, PLAN_MAX_Z, PLAN_MIN_X, PLAN_MAX_Z),
            atticWall(atticWestWallId, PLAN_MIN_X, PLAN_MAX_Z, PLAN_MIN_X, PLAN_MIN_Z),
            // One semantic roof, two facets: a gable cannot be one plane.
            RoofFacetGeometry(
                elementId = roofId,
                vertices = listOf(
                    ModelPoint(ROOF_MIN_X, EAVE_Y, ROOF_MIN_Z),
                    ModelPoint(ROOF_MAX_X, EAVE_Y, ROOF_MIN_Z),
                    ModelPoint(ROOF_MAX_X, RIDGE_Y, RIDGE_Z),
                    ModelPoint(ROOF_MIN_X, RIDGE_Y, RIDGE_Z),
                ),
            ),
            RoofFacetGeometry(
                elementId = roofId,
                vertices = listOf(
                    ModelPoint(ROOF_MIN_X, RIDGE_Y, RIDGE_Z),
                    ModelPoint(ROOF_MAX_X, RIDGE_Y, RIDGE_Z),
                    ModelPoint(ROOF_MAX_X, EAVE_Y, ROOF_MAX_Z),
                    ModelPoint(ROOF_MIN_X, EAVE_Y, ROOF_MAX_Z),
                ),
            ),
        ),
    )

    private fun groundWall(
        id: BuildingElementId,
        startX: Double,
        startZ: Double,
        endX: Double,
        endZ: Double,
        thickness: Double = EXTERIOR_WALL_THICKNESS,
    ): BuildingGeometryPrimitive = WallGeometry(
        elementId = id,
        start = PlanPoint(startX, startZ),
        end = PlanPoint(endX, endZ),
        baseElevation = GROUND_FLOOR_BASE_Y,
        height = GROUND_FLOOR_WALL_HEIGHT,
        thickness = thickness,
    )

    private fun atticWall(
        id: BuildingElementId,
        startX: Double,
        startZ: Double,
        endX: Double,
        endZ: Double,
    ): BuildingGeometryPrimitive = WallGeometry(
        elementId = id,
        start = PlanPoint(startX, startZ),
        end = PlanPoint(endX, endZ),
        baseElevation = ATTIC_BASE_Y,
        height = ATTIC_WALL_HEIGHT,
        thickness = EXTERIOR_WALL_THICKNESS,
    )

    private fun rectangle(
        minX: Double,
        maxX: Double,
        minZ: Double,
        maxZ: Double,
    ): List<PlanPoint> = listOf(
        PlanPoint(minX, minZ),
        PlanPoint(maxX, minZ),
        PlanPoint(maxX, maxZ),
        PlanPoint(minX, maxZ),
    )

    private fun meters(value: Double): Quantity = Quantity(value, UnitOfMeasure.METER)
}
