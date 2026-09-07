package com.buildplan.app.domain

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

/**
 * A minimal two-storey house used as the behavioural reference for the
 * ownership contract.
 *
 * Its shape follows the owner's reference project (a ground floor with living
 * space, an attic storey above, a roof and a foundation belonging to neither)
 * but the content is synthetic: nothing is copied from, downloaded from or
 * dependent on any external catalogue. The tests are about who owns an element,
 * not about the real house.
 */
object ReferenceBuilding {

    val groundFloorId: FloorId = FloorId("f-parter")
    val atticId: FloorId = FloorId("f-poddasze")

    val livingRoomId: RoomId = RoomId("r-salon")
    val kitchenId: RoomId = RoomId("r-kuchnia")
    val bedroomId: RoomId = RoomId("r-sypialnia")

    /** Belongs to the whole building: there is no storey a foundation sits on. */
    val foundationId: BuildingElementId = BuildingElementId("e-fundament")

    /** Belongs to the whole building, and is found by kind rather than by floor. */
    val roofId: BuildingElementId = BuildingElementId("e-dach")

    /** One wall, two rooms. Not two walls. */
    val sharedWallId: BuildingElementId = BuildingElementId("e-sciana-dzialowa")

    val livingRoomWallId: BuildingElementId = BuildingElementId("e-sciana-salon")
    val kitchenWindowId: BuildingElementId = BuildingElementId("e-okno-kuchnia")

    /** Floor-scoped but linked to no room: a slab belongs to the storey itself. */
    val groundSlabId: BuildingElementId = BuildingElementId("e-strop-parter")

    val atticWindowId: BuildingElementId = BuildingElementId("e-okno-poddasze")

    val groundFloor: Floor = Floor(
        id = groundFloorId,
        name = "Parter",
        order = 0,
        rooms = listOf(
            Room(livingRoomId, "Salon"),
            Room(kitchenId, "Kuchnia"),
        ),
    )

    val attic: Floor = Floor(
        id = atticId,
        name = "Poddasze",
        order = 1,
        rooms = listOf(Room(bedroomId, "Sypialnia")),
    )

    fun build(): Building = Building(
        id = BuildingId("b-ref"),
        floors = listOf(groundFloor, attic),
        elements = listOf(
            BuildingElement(
                id = foundationId,
                kind = BuildingElementKind.FOUNDATION,
                name = "Fundament",
                scope = BuildingElementScope.WholeBuilding,
            ),
            BuildingElement(
                id = roofId,
                kind = BuildingElementKind.ROOF,
                name = "Dach",
                scope = BuildingElementScope.WholeBuilding,
            ),
            BuildingElement(
                id = sharedWallId,
                kind = BuildingElementKind.WALL,
                name = "Sciana miedzy salonem a kuchnia",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(livingRoomId, kitchenId),
            ),
            BuildingElement(
                id = livingRoomWallId,
                kind = BuildingElementKind.WALL,
                name = "Sciana zewnetrzna salonu",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(livingRoomId),
            ),
            BuildingElement(
                id = kitchenWindowId,
                kind = BuildingElementKind.WINDOW,
                name = "Okno kuchenne",
                scope = BuildingElementScope.OnFloor(groundFloorId),
                roomIds = setOf(kitchenId),
            ),
            BuildingElement(
                id = groundSlabId,
                kind = BuildingElementKind.SLAB,
                name = "Strop nad parterem",
                scope = BuildingElementScope.OnFloor(groundFloorId),
            ),
            BuildingElement(
                id = atticWindowId,
                kind = BuildingElementKind.WINDOW,
                name = "Okno poddasza",
                scope = BuildingElementScope.OnFloor(atticId),
                roomIds = setOf(bedroomId),
            ),
        ),
    )
}
