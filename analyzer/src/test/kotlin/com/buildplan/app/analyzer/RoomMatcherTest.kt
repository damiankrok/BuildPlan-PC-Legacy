package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.plan.RoomMatcher
import com.buildplan.app.analyzer.site.PublishedRoom
import com.buildplan.app.analyzer.site.RoomKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023B-MATCH — matching regions to the published room table on more than
 * area, and saying so when area alone cannot decide.
 */
class RoomMatcherTest {

    @Test fun `global assignment retains a constrained room instead of consuming its only region in a merge`() {
        val rooms=listOf(room("One",10.0),room("Two",9.0),room("Three",20.0))
        val result=RoomMatcher.match(listOf(9.1,10.1,19.9),listOf(0 to 1),rooms,false)
        assertEquals(3,result.matches.size)
        assertEquals(listOf(0),result.matches.single { it.roomIndices==listOf(1) }.regionIndices)
        assertEquals(result,RoomMatcher.match(listOf(9.1,10.1,19.9),listOf(0 to 1),rooms,false))
    }

    private fun room(name: String, usable: Double?, floor: Double? = null, kind: RoomKind = RoomKind.OTHER, ordinal: Int? = null) = PublishedRoom(
        ordinal = ordinal,
        name = name,
        usableArea = usable?.let { Measured(it, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("t", "t", "t")) }
            ?: Measured.missing(MeasureUnit.SQUARE_METER, "none"),
        floorArea = floor?.let { Measured(it, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("t", "t", "t")) }
            ?: Measured.missing(MeasureUnit.SQUARE_METER, "none"),
        kind = kind,
    )

    @Test
    fun `a unique area fit is matched and carries its residual as a signal`() {
        val result = RoomMatcher.match(listOf(12.0, 25.0), emptyList(), listOf(room("Mała", 12.1), room("Duża", 24.8)), attic = false)
        assertEquals(2, result.matches.size)
        assertTrue(result.unmatchedRooms.isEmpty())
        assertTrue(result.matches.all { it.signals.any { s -> s.contains("residual") } })
    }

    @Test
    fun `two rooms of the same area stay ambiguous and surface the alternative`() {
        val rooms = listOf(room("Hol", 9.18, ordinal = 1), room("Pokój", 9.18, ordinal = 2))
        val result = RoomMatcher.match(listOf(9.1, 30.0), emptyList(), rooms, attic = false)
        assertTrue("the tie must be reported", result.ambiguities.isNotEmpty())
        val match = result.matches.first { it.regionIndices == listOf(0) }
        assertEquals(FactFidelity.TRACE_UNCERTAIN, match.fidelity)
        // The room it was not assigned to must be visible, not silently dropped.
        assertTrue(match.alternatives.any { it.roomName == "Pokój" || it.roomName == "Hol" })
    }

    @Test
    fun `a garage takes the region beside the gate when areas alone cannot choose`() {
        val rooms = listOf(room("Garaż", 24.0, kind = RoomKind.GARAGE, ordinal = 1), room("Pokój", 24.0, ordinal = 2))
        val cues = mapOf(1 to setOf(RoomMatcher.RegionCue.GATE))
        val result = RoomMatcher.match(listOf(23.9, 24.1), emptyList(), rooms, attic = false, cues = cues)
        val garage = result.matches.first { it.roomIndices == listOf(0) }
        assertEquals("the gate region", listOf(1), garage.regionIndices)
        assertTrue(garage.signals.any { it.contains("GATE") })
    }

    @Test
    fun `the region the most others open onto is a circulation cue`() {
        // Region 0 touches 1, 2 and 3; the others touch only it.
        val adjacency = listOf(0 to 1, 0 to 2, 0 to 3)
        val cues = RoomMatcher.circulationCues(4, adjacency)
        assertEquals(setOf(RoomMatcher.RegionCue.CIRCULATION_HUB), cues[0])
        assertTrue("only the hub is cued", cues.keys == setOf(0))
    }

    @Test
    fun `a hall takes the circulation hub when two rooms have the same area`() {
        val rooms = listOf(room("Hol", 9.0, kind = RoomKind.HALL, ordinal = 1), room("Pokój", 9.0, ordinal = 2))
        val adjacency = listOf(0 to 1, 0 to 2, 0 to 3)
        val cues = RoomMatcher.circulationCues(4, adjacency)
        val result = RoomMatcher.match(listOf(9.0, 9.0, 40.0, 41.0), adjacency, rooms, attic = false, cues = cues)
        val hall = result.matches.first { it.roomIndices == listOf(0) }
        assertEquals(listOf(0), hall.regionIndices)
        // Settled by a second signal, so it is no longer a coin toss between two equal areas.
        assertEquals(FactFidelity.SOURCE_TRACED, hall.fidelity)
        assertTrue(result.ambiguities.isEmpty())
    }

    @Test
    fun `a cue never overrides an area that does not fit`() {
        // The gate is on a region far too small to be the garage; area wins and the garage
        // takes the region that fits.
        val rooms = listOf(room("Garaż", 24.0, kind = RoomKind.GARAGE, ordinal = 1))
        val cues = mapOf(0 to setOf(RoomMatcher.RegionCue.GATE))
        val result = RoomMatcher.match(listOf(2.0, 24.2), emptyList(), rooms, attic = false, cues = cues)
        assertEquals(listOf(1), result.matches.single().regionIndices)
    }

    @Test
    fun `a room split across a wall-line gap is joined and says so`() {
        val rooms = listOf(room("Hol", 9.0, ordinal = 1))
        val result = RoomMatcher.match(listOf(4.4, 4.5), listOf(0 to 1), rooms, attic = false)
        val match = result.matches.single()
        assertEquals(listOf(0, 1), match.regionIndices)
        assertTrue(match.signals.any { it.contains("joined across wall-line gaps") })
    }

    @Test
    fun `regions are never joined across a wall`() {
        // No adjacency: the two halves are separated by a drawn wall, so the room stays unmatched
        // rather than being assembled through it.
        val rooms = listOf(room("Hol", 9.0, ordinal = 1))
        val result = RoomMatcher.match(listOf(4.4, 4.5), emptyList(), rooms, attic = false)
        assertTrue(result.matches.isEmpty())
        assertEquals(listOf(0), result.unmatchedRooms)
    }

    @Test
    fun `an attic room compared by usable area may be larger, never smaller`() {
        // Usable area excludes the strip under the slope, so the drawn region exceeds it.
        val rooms = listOf(room("Pokój", 10.0, ordinal = 1))
        assertNotNull(RoomMatcher.match(listOf(13.0), emptyList(), rooms, attic = true).matches.firstOrNull())
        assertTrue(RoomMatcher.match(listOf(7.0), emptyList(), rooms, attic = true).matches.isEmpty())
    }
}
