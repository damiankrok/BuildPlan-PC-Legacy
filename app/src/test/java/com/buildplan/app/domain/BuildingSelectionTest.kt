package com.buildplan.app.domain

import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.model.isolateRoom
import com.buildplan.app.domain.model.visibleElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3D002A-09..12 - hiding and isolating, decided as pure queries.
 *
 * These are the answers the later 3D model needs. Nothing here touches a
 * camera, a mesh or an alpha value, and nothing is stored: visibility is an
 * argument to a question, not a field on the building.
 */
class BuildingSelectionTest {

    private val building = ReferenceBuilding.build()

    @Test
    fun `D3D002A-09 hiding the roof removes the roof and nothing else`() {
        val visible = building.visibleElements(BuildingVisibility(roofHidden = true))

        assertFalse(visible.any { it.id == ReferenceBuilding.roofId })

        // Ordinary floor elements and the foundation are untouched.
        assertEquals(
            building.elements.filter { it.id != ReferenceBuilding.roofId },
            visible,
        )
        assertTrue(visible.any { it.id == ReferenceBuilding.foundationId })
        assertTrue(visible.any { it.id == ReferenceBuilding.atticWindowId })

        // Hiding nothing hides nothing.
        assertEquals(building.elements, building.visibleElements(BuildingVisibility.EVERYTHING))
    }

    @Test
    fun `D3D002A-10 hiding a floor removes only that floor`() {
        val visible = building.visibleElements(
            BuildingVisibility(hiddenFloorIds = setOf(ReferenceBuilding.atticId)),
        )

        assertFalse(visible.any { it.id == ReferenceBuilding.atticWindowId })

        // Every ground-floor element survives.
        assertTrue(
            visible.map { it.id }.containsAll(
                listOf(
                    ReferenceBuilding.sharedWallId,
                    ReferenceBuilding.livingRoomWallId,
                    ReferenceBuilding.kitchenWindowId,
                    ReferenceBuilding.groundSlabId,
                ),
            ),
        )

        // Hiding a storey does not take the building's own elements with it.
        assertTrue(visible.any { it.id == ReferenceBuilding.foundationId })
        assertTrue(visible.any { it.id == ReferenceBuilding.roofId })
    }

    @Test
    fun `D3D002A-11 hiding the roof and the upper floor leaves the lower floor`() {
        val visible = building.visibleElements(
            BuildingVisibility(
                hiddenFloorIds = setOf(ReferenceBuilding.atticId),
                roofHidden = true,
            ),
        )

        assertEquals(
            listOf(
                ReferenceBuilding.foundationId,
                ReferenceBuilding.sharedWallId,
                ReferenceBuilding.livingRoomWallId,
                ReferenceBuilding.kitchenWindowId,
                ReferenceBuilding.groundSlabId,
            ),
            visible.map { it.id },
        )

        // This is the owner's case: roof off, upper storey off, the walls of the
        // storey below still standing.
        assertTrue(visible.any { it.id == ReferenceBuilding.sharedWallId })
        assertFalse(visible.any { it.id == ReferenceBuilding.roofId })
        assertFalse(visible.any { it.id == ReferenceBuilding.atticWindowId })
    }

    @Test
    fun `D3D002A-12 isolating a room returns its elements, the shared wall once`() {
        val isolation = building.isolateRoom(ReferenceBuilding.livingRoomId)

        assertEquals(ReferenceBuilding.livingRoomId, isolation.room.id)
        assertEquals("Salon", isolation.room.name)
        assertEquals(ReferenceBuilding.groundFloorId, isolation.floor.id)

        assertEquals(
            listOf(ReferenceBuilding.sharedWallId, ReferenceBuilding.livingRoomWallId),
            isolation.elements.map { it.id },
        )

        // The wall the living room shares with the kitchen appears exactly once,
        // and nothing that belongs to another room leaks in.
        assertEquals(1, isolation.elements.count { it.id == ReferenceBuilding.sharedWallId })
        assertFalse(isolation.elements.any { it.id == ReferenceBuilding.kitchenWindowId })
        assertFalse(isolation.elements.any { it.id == ReferenceBuilding.atticWindowId })

        // Isolating a room the building does not have is a programming error.
        assertThrows(IllegalArgumentException::class.java) {
            building.isolateRoom(RoomId("r-nieistniejacy"))
        }
    }
}
