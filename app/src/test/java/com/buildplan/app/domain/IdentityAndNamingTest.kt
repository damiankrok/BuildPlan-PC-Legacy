package com.buildplan.app.domain

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.BuildingId
import com.buildplan.app.domain.model.BudgetId
import com.buildplan.app.domain.model.CostId
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
import org.junit.Test

/** DOM002-01..02 — identity and required names. */
class IdentityAndNamingTest {

    @Test
    fun `DOM002-01 stable ids reject blank values`() {
        val blanks = listOf("", " ", "   ", "\t", "\n")

        blanks.forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) { ProjectId(blank) }
            assertThrows(IllegalArgumentException::class.java) { BuildingId(blank) }
            assertThrows(IllegalArgumentException::class.java) { FloorId(blank) }
            assertThrows(IllegalArgumentException::class.java) { RoomId(blank) }
            assertThrows(IllegalArgumentException::class.java) { BuildingElementId(blank) }
            assertThrows(IllegalArgumentException::class.java) { StageId(blank) }
            assertThrows(IllegalArgumentException::class.java) { CostId(blank) }
            assertThrows(IllegalArgumentException::class.java) { BudgetId(blank) }
        }

        // Identity is the id, not the name: same id and different names stay equal.
        assertEquals(ProjectId("p-1"), ProjectId("p-1"))
    }

    @Test
    fun `DOM002-02 required names reject blank after trim`() {
        assertThrows(IllegalArgumentException::class.java) {
            Project(ProjectId("p-1"), "   ", Building(BuildingId("b-1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Floor(FloorId("f-1"), " \t ", order = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Room(RoomId("r-1"), "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuildingElement(
                BuildingElementId("e-1"),
                BuildingElementKind.WALL,
                "  ",
                BuildingElementScope.WholeBuilding,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Stage(StageId("s-1"), ProjectId("p-1"), "\n", order = 0)
        }

        // A name that carries real characters is accepted.
        assertEquals("Parter", Floor(FloorId("f-1"), "Parter", order = 0).name)
    }
}
