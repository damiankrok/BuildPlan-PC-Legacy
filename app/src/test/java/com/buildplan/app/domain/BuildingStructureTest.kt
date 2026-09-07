package com.buildplan.app.domain

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.BuildingId
import com.buildplan.app.domain.model.Floor
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.Project
import com.buildplan.app.domain.model.ProjectId
import com.buildplan.app.domain.model.Room
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.model.Stage
import com.buildplan.app.domain.model.StageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DOM002-08..09 - ordering and ownership inside the building structure.
 *
 * Which element belongs to which floor or room is a contract of its own and is
 * tested in [BuildingElementOwnershipTest].
 */
class BuildingStructureTest {

    @Test
    fun `DOM002-08 explicit order rejects negative values`() {
        assertThrows(IllegalArgumentException::class.java) {
            Floor(FloorId("f-1"), "Parter", order = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Stage(StageId("s-1"), ProjectId("p-1"), "Fundamenty", order = -1)
        }

        assertEquals(0, Floor(FloorId("f-1"), "Parter", order = 0).order)
        assertEquals(0, Stage(StageId("s-1"), ProjectId("p-1"), "Fundamenty", order = 0).order)
    }

    @Test
    fun `DOM002-09 duplicate owned ids are rejected`() {
        // Two floors sharing an id make the contents of the building ambiguous.
        assertThrows(IllegalArgumentException::class.java) {
            Building(
                id = BuildingId("b-1"),
                floors = listOf(
                    Floor(FloorId("f-1"), "Parter", order = 0),
                    Floor(FloorId("f-1"), "Pietro", order = 1),
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            Floor(
                id = FloorId("f-1"),
                name = "Parter",
                order = 0,
                rooms = listOf(Room(RoomId("r-1"), "Salon"), Room(RoomId("r-1"), "Kuchnia")),
            )
        }

        // Rooms are unique across the whole building, not merely within a floor:
        // elements reach rooms by id, and a repeated id would make that ambiguous.
        assertThrows(IllegalArgumentException::class.java) {
            Building(
                id = BuildingId("b-1"),
                floors = listOf(
                    Floor(FloorId("f-1"), "Parter", 0, rooms = listOf(Room(RoomId("r-1"), "Salon"))),
                    Floor(FloorId("f-2"), "Pietro", 1, rooms = listOf(Room(RoomId("r-1"), "Sypialnia"))),
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            Building(
                id = BuildingId("b-1"),
                elements = listOf(
                    BuildingElement(
                        BuildingElementId("e-1"),
                        BuildingElementKind.WALL,
                        "Sciana",
                        BuildingElementScope.WholeBuilding,
                    ),
                    BuildingElement(
                        BuildingElementId("e-1"),
                        BuildingElementKind.ROOF,
                        "Dach",
                        BuildingElementScope.WholeBuilding,
                    ),
                ),
            )
        }
    }

    @Test
    fun `floors are held in one canonical order`() {
        // A list order that contradicts the stored order would leave two
        // different answers to the question of which floor comes first.
        assertThrows(IllegalArgumentException::class.java) {
            Building(
                id = BuildingId("b-1"),
                floors = listOf(
                    Floor(FloorId("f-2"), "Pietro", order = 1),
                    Floor(FloorId("f-1"), "Parter", order = 0),
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            Building(
                id = BuildingId("b-1"),
                floors = listOf(
                    Floor(FloorId("f-1"), "Parter", order = 0),
                    Floor(FloorId("f-2"), "Antresola", order = 0),
                ),
            )
        }
    }

    @Test
    fun `the building owns its elements and the floors own their rooms`() {
        // Ownership is stated once. A floor has no element list that could
        // disagree with an element's own scope.
        assertTrue(
            Floor::class.java.declaredFields.none { it.name == "elements" },
        )

        val building = ReferenceBuilding.build()
        assertEquals(7, building.elements.size)
        assertEquals(3, building.floors.sumOf { it.rooms.size })
    }

    @Test
    fun `a project owns exactly one building`() {
        val project = Project(
            id = ProjectId("p-1"),
            name = "Dom jednorodzinny",
            building = Building(
                id = BuildingId("b-1"),
                floors = listOf(
                    Floor(FloorId("f-1"), "Parter", order = 0),
                    Floor(FloorId("f-2"), "Pietro", order = 1),
                ),
            ),
        )

        assertEquals(BuildingId("b-1"), project.building.id)
        assertEquals(2, project.building.floors.size)
    }
}
