package com.buildplan.app.reference

import com.buildplan.app.domain.units.UnitOfMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REF010A-01..08 - the reference dataset says what the source says.
 *
 * The point of these tests is not that the domain works — [com.buildplan.app.domain]
 * owns that - but that this fixture stays a faithful, stable input. A silent
 * edit to a room area or an id would quietly change the ground truth every later
 * stage is developed against.
 *
 * The test lives in the `testDebug` source set because the fixture lives in
 * `debug`: the release variant compiles neither.
 */
class MarcowkiReferenceProjectTest {

    /** Areas are stated to two decimals; this only absorbs Double summation drift. */
    private val areaTolerance = 1e-9

    @Test
    fun `REF010A-01 provenance is recorded outside the canonical domain`() {
        val source = MarcowkiReferenceProject.source

        assertEquals("ARCHON+ — Dom w marcówkach (GE)", source.title)
        assertEquals(
            "https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca",
            source.url,
        )

        // Provenance must not have leaked into the domain entities themselves.
        val project = MarcowkiReferenceProject.build()
        val domainFields = listOf(project.name, project.building.id.value) +
            project.building.floors.map { it.name }
        assertTrue(
            "The source URL must not be stored on a domain entity",
            domainFields.none { it.contains("archon.pl") },
        )
    }

    @Test
    fun `REF010A-02 the building has exactly two floors, Parter then Poddasze`() {
        val floors = MarcowkiReferenceProject.build().building.floors

        assertEquals(listOf("Parter", "Poddasze"), floors.map { it.name })
        assertEquals(listOf(0, 1), floors.map { it.order })
    }

    @Test
    fun `REF010A-03 Parter holds the nine stated rooms with their stated areas`() {
        assertRooms(
            floorName = "Parter",
            expected = listOf(
                "Wiatrołap" to 3.70,
                "Hol" to 9.18,
                "Kuchnia" to 9.63,
                "Salon + Jadalnia" to 29.52,
                "Spiżarnia" to 1.44,
                "Łazienka" to 3.95,
                "Pokój" to 9.18,
                "Kotłownia" to 5.80,
                "Garaż" to 24.10,
            ),
        )
    }

    @Test
    fun `REF010A-04 Poddasze holds the nine stated rooms with their stated areas`() {
        assertRooms(
            floorName = "Poddasze",
            expected = listOf(
                "Korytarz" to 6.17,
                "Pokój" to 10.25,
                "Garderoba" to 5.19,
                "Łazienka" to 6.40,
                "Pralnia" to 5.59,
                "Pokój" to 12.57,
                "Pokój" to 9.02,
                "Garderoba" to 1.62,
                "Schody" to 5.63,
            ),
        )
    }

    @Test
    fun `REF010A-05 Parter room areas sum to the stated floor total`() {
        assertEquals(
            MarcowkiReferenceProject.groundFloorStatedArea.amount,
            totalArea("Parter"),
            areaTolerance,
        )
        assertEquals(96.50, totalArea("Parter"), areaTolerance)
    }

    @Test
    fun `REF010A-06 Poddasze room areas sum to the stated floor total`() {
        assertEquals(
            MarcowkiReferenceProject.atticStatedArea.amount,
            totalArea("Poddasze"),
            areaTolerance,
        )
        assertEquals(62.44, totalArea("Poddasze"), areaTolerance)
    }

    @Test
    fun `REF010A-07 every identifier is deterministic and unique`() {
        val first = MarcowkiReferenceProject.build()
        val second = MarcowkiReferenceProject.build()

        assertEquals(first, second)
        assertEquals(first.id, second.id)

        val roomIds = first.building.floors.flatMap { floor -> floor.rooms.map { it.id.value } }
        assertEquals(18, roomIds.size)
        assertEquals(roomIds.size, roomIds.toSet().size)

        val floorIds = first.building.floors.map { it.id.value }
        assertEquals(floorIds.size, floorIds.toSet().size)

        // Repeated names must not collapse into one room: three "Pokój" upstairs.
        val attic = floorNamed("Poddasze")
        assertEquals(3, attic.rooms.count { it.name == "Pokój" })
        assertEquals(3, attic.rooms.filter { it.name == "Pokój" }.map { it.id }.toSet().size)
    }

    @Test
    fun `REF010A-08 no alternate bracketed area is encoded as a room area`() {
        val rooms = MarcowkiReferenceProject.build().building.floors.flatMap { it.rooms }

        // Every room carries exactly one area, in square metres. There is no
        // second area field for the parenthesised values to hide in.
        assertEquals(18, rooms.count { it.area != null })
        assertTrue(rooms.all { it.area?.unit == UnitOfMeasure.SQUARE_METER })

        // The two floors together account for the stated totals and nothing else.
        assertEquals(96.50 + 62.44, rooms.sumOf { it.area?.amount ?: 0.0 }, areaTolerance)
    }

    @Test
    fun `REF010A-09 no geometry is invented for the reference building`() {
        val building = MarcowkiReferenceProject.build().building

        assertTrue(
            "The source states no element topology, so none may be encoded",
            building.elements.isEmpty(),
        )
        assertTrue(building.floors.all { it.elevation == null && it.height == null })
    }

    private fun floorNamed(name: String) =
        MarcowkiReferenceProject.build().building.floors.single { it.name == name }

    private fun totalArea(floorName: String): Double =
        floorNamed(floorName).rooms.sumOf { requireNotNull(it.area).amount }

    private fun assertRooms(floorName: String, expected: List<Pair<String, Double>>) {
        val rooms = floorNamed(floorName).rooms

        assertEquals(9, rooms.size)
        assertEquals(expected.map { it.first }, rooms.map { it.name })

        expected.zip(rooms).forEach { (want, room) ->
            val area = requireNotNull(room.area) { "${room.name} on $floorName has no area" }
            assertEquals(room.name, UnitOfMeasure.SQUARE_METER, area.unit)
            assertEquals(room.name, want.second, area.amount, areaTolerance)
        }
    }
}
