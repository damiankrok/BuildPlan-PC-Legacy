package com.buildplan.app.geometry.demo

import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.reference.MarcowkiReferenceProject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GEO011-10, GEO011-11, GEO011-16, GEO011-17 — the synthetic demo house.
 *
 * The fixture exists so the first renderer has a real two-storey shell to draw
 * before any plan parser exists. These tests hold it to two things: that it
 * actually contains that shell, and that it never starts pretending to be the
 * source-backed reference project, whose plan nobody knows.
 *
 * The tests live in `testDebug` because the fixture lives in `debug`: the
 * release variant compiles neither.
 */
class SyntheticDemoHouseTest {

    private val building = SyntheticDemoHouse.building
    private val geometry = SyntheticDemoHouse.geometry

    // --- GEO011-10: the shell is actually there ---

    @Test
    fun `GEO011-10 the demo house has two storeys with rooms on both`() {
        assertEquals(listOf("Parter", "Poddasze"), building.floors.map { it.name })
        assertEquals(listOf(0, 1), building.floors.map { it.order })

        val groundRooms = building.floors.first { it.id == SyntheticDemoHouse.groundFloorId }.rooms
        val atticRooms = building.floors.first { it.id == SyntheticDemoHouse.atticId }.rooms

        assertTrue("The lower storey needs at least two rooms", groundRooms.size >= 2)
        assertTrue("The upper storey needs at least one room", atticRooms.isNotEmpty())
    }

    @Test
    fun `GEO011-10 both storeys have wall geometry, plus a slab and two roof facets`() {
        val groundWalls = wallsOn(SyntheticDemoHouse.groundFloorId)
        val atticWalls = wallsOn(SyntheticDemoHouse.atticId)

        assertTrue("The lower storey needs walls with geometry", groundWalls.isNotEmpty())
        assertTrue("The upper storey needs walls with geometry", atticWalls.isNotEmpty())

        // Real construction, not zero-thickness cardboard.
        (groundWalls + atticWalls).forEach { wall ->
            assertTrue("Wall ${wall.elementId.value} has no thickness", wall.thickness > 0.0)
            assertTrue("Wall ${wall.elementId.value} has no height", wall.height > 0.0)
        }

        // The upper storey genuinely sits above the lower one.
        assertTrue(
            "The attic walls must start at or above the top of the ground-floor walls",
            atticWalls.minOf { it.baseElevation } >= groundWalls.maxOf { it.topElevation },
        )

        val slabs = geometry.primitives.filterIsInstance<SlabGeometry>()
        assertTrue("The demo house needs at least one slab", slabs.isNotEmpty())
        assertTrue(
            "The slab over the ground floor must have geometry",
            slabs.any { it.elementId == SyntheticDemoHouse.groundCeilingSlabId },
        )

        assertEquals(2, geometry.primitives.filterIsInstance<RoofFacetGeometry>().size)
    }

    @Test
    fun `GEO011-10 one wall is shared by both ground-floor rooms and exists once`() {
        val partition = building.elements.first { it.id == SyntheticDemoHouse.partitionWallId }

        assertEquals(
            setOf(SyntheticDemoHouse.livingRoomId, SyntheticDemoHouse.kitchenId),
            partition.roomIds,
        )
        assertEquals(1, building.elements.count { it.id == SyntheticDemoHouse.partitionWallId })
        assertEquals(1, geometry.primitivesFor(SyntheticDemoHouse.partitionWallId).size)
    }

    @Test
    fun `GEO011-10 every shape belongs to an element of this very building`() {
        geometry.requireElementsIn(building)

        val bounds = requireNotNull(geometry.bounds)
        assertTrue(bounds.sizeX > 0.0 && bounds.sizeY > 0.0 && bounds.sizeZ > 0.0)
        // Bottom of the foundation to the top of the ridge.
        assertEquals(-0.40, bounds.min.y, 1e-9)
        assertEquals(7.95, bounds.max.y, 1e-9)
    }

    // --- GEO011-11: one semantic roof, two facets ---

    @Test
    fun `GEO011-11 the gable roof is one semantic element with two facets`() {
        val roofElements = building.elements.filter { it.kind == BuildingElementKind.ROOF }
        assertEquals(1, roofElements.size)
        assertEquals(SyntheticDemoHouse.roofId, roofElements.single().id)

        val facets = geometry.primitivesFor(SyntheticDemoHouse.roofId)
        assertEquals(2, facets.size)
        assertTrue(facets.all { it is RoofFacetGeometry })

        // Two facets, not the same facet twice: a gable has two slopes.
        assertNotEquals(facets[0], facets[1])
        facets.filterIsInstance<RoofFacetGeometry>().forEach { facet ->
            assertTrue("A roof facet must have area", facet.area > 1.0)
        }
    }

    // --- GEO011-16 / GEO011-17: it is a demo, and it stays one ---

    @Test
    fun `GEO011-16 the fixture declares itself synthetic in its own source`() {
        val source = locateFixtureSource().readText()

        assertTrue(
            "The fixture must state in its own source that the geometry is synthetic",
            source.contains("Synthetic preview geometry — not reconstructed from ARCHON plans"),
        )
    }

    @Test
    fun `GEO011-16 the fixture shares no identity with the source-backed reference project`() {
        val referenceProject = MarcowkiReferenceProject.build()
        val referenceIds = buildSet {
            add(referenceProject.building.id.value)
            referenceProject.building.floors.forEach { floor ->
                add(floor.id.value)
                floor.rooms.forEach { add(it.id.value) }
            }
        }

        val demoIds = buildSet {
            add(building.id.value)
            building.floors.forEach { floor ->
                add(floor.id.value)
                floor.rooms.forEach { add(it.id.value) }
            }
            building.elements.forEach { add(it.id.value) }
        }

        assertEquals(
            "The demo house must not reuse a single reference-project id",
            emptySet<String>(),
            demoIds intersect referenceIds,
        )
        // And no shape may claim to draw a reference-project element.
        assertTrue(geometry.elementIds.none { it.value in referenceIds })
    }

    @Test
    fun `GEO011-17 the source-backed reference project still has no geometry`() {
        val referenceBuilding = MarcowkiReferenceProject.build().building

        assertEquals(
            "The reference project states no plan, so it must declare no elements",
            emptyList<Any>(),
            referenceBuilding.elements,
        )
        // Two floors and eighteen rooms, untouched by this stage.
        assertEquals(2, referenceBuilding.floors.size)
        assertEquals(18, referenceBuilding.floors.sumOf { it.rooms.size })
    }

    private fun wallsOn(floorId: FloorId): List<WallGeometry> {
        val elementIds = building.elements
            .filter { it.floorId == floorId && it.kind == BuildingElementKind.WALL }
            .mapTo(mutableSetOf()) { it.id }
        return geometry.primitivesFor(elementIds).filterIsInstance<WallGeometry>()
    }

    /** Walks up from the working directory so the test does not depend on where Gradle runs it. */
    private fun locateFixtureSource(): File {
        val relativePaths = listOf(
            "src/debug/java/com/buildplan/app/geometry/demo/SyntheticDemoHouse.kt",
            "app/src/debug/java/com/buildplan/app/geometry/demo/SyntheticDemoHouse.kt",
        )
        var directory: File? = File("").absoluteFile

        while (directory != null) {
            relativePaths
                .map { File(directory, it) }
                .firstOrNull { it.isFile }
                ?.let { return it }
            directory = directory.parentFile
        }

        throw AssertionError("Could not locate the fixture source from ${File("").absolutePath}")
    }
}
