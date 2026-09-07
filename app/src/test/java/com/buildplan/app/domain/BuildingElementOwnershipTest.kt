package com.buildplan.app.domain

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.BuildingId
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.model.buildingScopedElements
import com.buildplan.app.domain.model.elementsInRoom
import com.buildplan.app.domain.model.elementsOnFloor
import com.buildplan.app.domain.model.roofElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** D3D002A-01..08 - one element, one owner, many rooms. */
class BuildingElementOwnershipTest {

    private val building = ReferenceBuilding.build()

    @Test
    fun `D3D002A-01 the roof belongs to the building, not to an invented floor`() {
        val roof = building.elements.single { it.id == ReferenceBuilding.roofId }

        assertEquals(BuildingElementScope.WholeBuilding, roof.scope)
        assertEquals(null, roof.floorId)
        assertTrue("The roof needs no room to hang from", roof.roomIds.isEmpty())

        // No storey was invented to hold it, and no room either.
        assertEquals(2, building.floors.size)
        assertEquals(3, building.floors.sumOf { it.rooms.size })
        building.floors.forEach { floor ->
            assertTrue(
                "The roof must not appear on floor " + floor.name,
                building.elementsOnFloor(floor.id).none { it.id == ReferenceBuilding.roofId },
            )
        }

        // It is still reachable without walking floors, by kind.
        assertEquals(listOf(ReferenceBuilding.roofId), building.roofElements().map { it.id })
    }

    @Test
    fun `D3D002A-02 the foundation belongs to the building`() {
        val foundation = building.elements.single { it.id == ReferenceBuilding.foundationId }

        assertEquals(BuildingElementScope.WholeBuilding, foundation.scope)
        assertEquals(null, foundation.floorId)
        assertTrue(foundation.roomIds.isEmpty())

        assertEquals(
            listOf(ReferenceBuilding.foundationId, ReferenceBuilding.roofId),
            building.buildingScopedElements().map { it.id },
        )
    }

    @Test
    fun `D3D002A-03 a floor element names its floor explicitly`() {
        val slab = building.elements.single { it.id == ReferenceBuilding.groundSlabId }

        // Scope is stated on the element, never inferred from which list it sits in.
        assertEquals(
            BuildingElementScope.OnFloor(ReferenceBuilding.groundFloorId),
            slab.scope,
        )
        assertEquals(ReferenceBuilding.groundFloorId, slab.floorId)

        // A floor-scoped element need not belong to any room.
        assertTrue(slab.roomIds.isEmpty())
    }

    @Test
    fun `D3D002A-04 a floor element cannot link a room on another floor`() {
        val reachingUpstairs = BuildingElement(
            id = BuildingElementId("e-bledna"),
            kind = BuildingElementKind.WALL,
            name = "Sciana siegajaca na inna kondygnacje",
            scope = BuildingElementScope.OnFloor(ReferenceBuilding.groundFloorId),
            roomIds = setOf(ReferenceBuilding.bedroomId),
        )

        assertThrows(IllegalArgumentException::class.java) {
            building.copy(elements = building.elements + reachingUpstairs)
        }

        // A room that is on no floor at all is refused just as firmly.
        assertThrows(IllegalArgumentException::class.java) {
            building.copy(
                elements = building.elements +
                    reachingUpstairs.copy(roomIds = setOf(RoomId("r-nieistniejacy"))),
            )
        }

        // And so is an element scoped to a floor the building does not have.
        assertThrows(IllegalArgumentException::class.java) {
            building.copy(
                elements = building.elements +
                    reachingUpstairs.copy(
                        scope = BuildingElementScope.OnFloor(FloorId("f-brak")),
                        roomIds = emptySet(),
                    ),
            )
        }
    }

    @Test
    fun `D3D002A-05 one wall can belong to two rooms on the same floor`() {
        val sharedWall = building.elements.single { it.id == ReferenceBuilding.sharedWallId }

        assertEquals(
            setOf(ReferenceBuilding.livingRoomId, ReferenceBuilding.kitchenId),
            sharedWall.roomIds,
        )
        assertEquals(ReferenceBuilding.groundFloorId, sharedWall.floorId)
    }

    @Test
    fun `D3D002A-06 the shared wall stays one element with one identity`() {
        // Both rooms return the very same element, not a copy per room.
        val fromLivingRoom = building
            .elementsInRoom(ReferenceBuilding.livingRoomId)
            .single { it.id == ReferenceBuilding.sharedWallId }
        val fromKitchen = building
            .elementsInRoom(ReferenceBuilding.kitchenId)
            .single { it.id == ReferenceBuilding.sharedWallId }

        assertTrue("Two rooms must share one wall object", fromLivingRoom === fromKitchen)

        assertEquals(1, building.elements.count { it.id == ReferenceBuilding.sharedWallId })
        assertEquals(building.elements.size, building.elements.map { it.id }.toSet().size)

        // Two elements sharing an id would make the contents of the building ambiguous.
        assertThrows(IllegalArgumentException::class.java) {
            building.copy(elements = building.elements + fromLivingRoom)
        }
    }

    @Test
    fun `D3D002A-07 elements for a floor are the same list every time`() {
        val expectedGroundFloor = listOf(
            ReferenceBuilding.sharedWallId,
            ReferenceBuilding.livingRoomWallId,
            ReferenceBuilding.kitchenWindowId,
            ReferenceBuilding.groundSlabId,
        )

        assertEquals(
            expectedGroundFloor,
            building.elementsOnFloor(ReferenceBuilding.groundFloorId).map { it.id },
        )
        // Asking twice, and asking a freshly built equal building, answers the same.
        assertEquals(
            building.elementsOnFloor(ReferenceBuilding.groundFloorId),
            building.elementsOnFloor(ReferenceBuilding.groundFloorId),
        )
        assertEquals(
            building.elementsOnFloor(ReferenceBuilding.groundFloorId),
            ReferenceBuilding.build().elementsOnFloor(ReferenceBuilding.groundFloorId),
        )

        assertEquals(
            listOf(ReferenceBuilding.atticWindowId),
            building.elementsOnFloor(ReferenceBuilding.atticId).map { it.id },
        )

        // A floor holds only its own elements: the roof and the foundation are on none.
        assertEquals(
            emptyList<BuildingElement>(),
            building.elementsOnFloor(FloorId("f-brak")),
        )
    }

    @Test
    fun `D3D002A-08 a room returns every element linked to it, each once`() {
        assertEquals(
            listOf(ReferenceBuilding.sharedWallId, ReferenceBuilding.livingRoomWallId),
            building.elementsInRoom(ReferenceBuilding.livingRoomId).map { it.id },
        )
        assertEquals(
            listOf(ReferenceBuilding.sharedWallId, ReferenceBuilding.kitchenWindowId),
            building.elementsInRoom(ReferenceBuilding.kitchenId).map { it.id },
        )

        val kitchenElements = building.elementsInRoom(ReferenceBuilding.kitchenId)
        assertEquals(kitchenElements.size, kitchenElements.map { it.id }.toSet().size)

        // A room with nothing linked yet is an empty answer, not a failure.
        assertEquals(
            emptyList<BuildingElement>(),
            Building(BuildingId("b-pusty")).elementsInRoom(ReferenceBuilding.kitchenId),
        )
    }
}
