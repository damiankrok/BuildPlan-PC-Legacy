package com.buildplan.app.geometry.demo

import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.isolateRoom
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.BuildingGeometryPrimitive
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.primitivesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GEO011-12..15 — the semantic selection reaches the geometry, and nothing
 * decides visibility twice.
 *
 * Every test here is the same two-step composition: the domain decides which
 * elements survive, and the geometry is then looked up for exactly those. That
 * is the whole bridge. If any of these assertions could be made to pass by
 * changing something in the `geometry` package, the rule would have leaked out
 * of the domain and the two layers could start to disagree.
 */
class SyntheticDemoHouseSelectionTest {

    private val building = SyntheticDemoHouse.building
    private val geometry = SyntheticDemoHouse.geometry

    private fun drawn(visibility: BuildingVisibility): List<BuildingGeometryPrimitive> =
        geometry.primitivesOf(building.visibleElements(visibility))

    @Test
    fun `GEO011-12 hiding the roof removes every roof facet and nothing else`() {
        val everything = drawn(BuildingVisibility.EVERYTHING)
        val withoutRoof = drawn(BuildingVisibility(roofHidden = true))

        assertEquals(geometry.primitives, everything)
        assertTrue(
            "Both roof facets must disappear together",
            withoutRoof.none { it is RoofFacetGeometry },
        )
        assertEquals(
            "Hiding the roof must remove exactly the two roof facets",
            everything.size - 2,
            withoutRoof.size,
        )
        // Everything that is not the roof is untouched, in the same order.
        assertEquals(everything.filter { it.elementId != SyntheticDemoHouse.roofId }, withoutRoof)
    }

    @Test
    fun `GEO011-13 hiding the upper storey keeps the lower storey's geometry`() {
        val visible = drawn(BuildingVisibility(hiddenFloorIds = setOf(SyntheticDemoHouse.atticId)))

        val atticElementIds = building.elements
            .filter { it.floorId == SyntheticDemoHouse.atticId }
            .mapTo(mutableSetOf()) { it.id }
        assertTrue(
            "No attic geometry may survive hiding the attic",
            visible.none { it.elementId in atticElementIds },
        )

        // The ground floor, the foundation and the roof are all still drawn:
        // hiding a storey is not the same operation as hiding what spans them.
        assertTrue(visible.any { it.elementId == SyntheticDemoHouse.partitionWallId })
        assertTrue(visible.any { it.elementId == SyntheticDemoHouse.groundCeilingSlabId })
        assertTrue(visible.any { it.elementId == SyntheticDemoHouse.foundationId })
        assertTrue(visible.any { it.elementId == SyntheticDemoHouse.roofId })
    }

    @Test
    fun `GEO011-14 hiding roof and upper storey leaves the lower walls and slab`() {
        val visible = drawn(
            BuildingVisibility(
                hiddenFloorIds = setOf(SyntheticDemoHouse.atticId),
                roofHidden = true,
            ),
        )

        assertFalse(visible.any { it is RoofFacetGeometry })

        val groundWallIds = setOf(
            SyntheticDemoHouse.southWallId,
            SyntheticDemoHouse.westWallLivingRoomId,
            SyntheticDemoHouse.eastWallLivingRoomId,
            SyntheticDemoHouse.partitionWallId,
            SyntheticDemoHouse.westWallKitchenId,
            SyntheticDemoHouse.eastWallKitchenId,
            SyntheticDemoHouse.northWallId,
        )
        assertEquals(
            "Every ground-floor wall must still be drawn",
            groundWallIds,
            visible.filterIsInstance<WallGeometry>().mapTo(mutableSetOf()) { it.elementId },
        )
        assertTrue(visible.any { it.elementId == SyntheticDemoHouse.groundCeilingSlabId })

        // What is left is the lower shell: still a solid, finite thing to frame.
        val bounds = requireNotNull(
            visible.map { it.bounds }.reduceOrNull { total, next -> total.encompass(next) },
        )
        assertEquals(3.05, bounds.max.y, 1e-9)
    }

    @Test
    fun `GEO011-15 isolating a room draws its elements, and a shared wall only once`() {
        val isolation = building.isolateRoom(SyntheticDemoHouse.livingRoomId)
        val visible = geometry.primitivesOf(isolation.elements)

        assertEquals(SyntheticDemoHouse.groundFloorId, isolation.floor.id)
        assertEquals(
            setOf(
                SyntheticDemoHouse.southWallId,
                SyntheticDemoHouse.westWallLivingRoomId,
                SyntheticDemoHouse.eastWallLivingRoomId,
                SyntheticDemoHouse.partitionWallId,
            ),
            visible.mapTo(mutableSetOf()) { it.elementId },
        )

        // The partition is one element linked to two rooms, so isolating either
        // room draws it exactly once — not one copy per room.
        assertEquals(1, visible.count { it.elementId == SyntheticDemoHouse.partitionWallId })

        val fromKitchen = geometry.primitivesOf(
            building.isolateRoom(SyntheticDemoHouse.kitchenId).elements,
        )
        assertEquals(1, fromKitchen.count { it.elementId == SyntheticDemoHouse.partitionWallId })
        assertEquals(
            "Both rooms must reach the very same wall primitive",
            visible.single { it.elementId == SyntheticDemoHouse.partitionWallId },
            fromKitchen.single { it.elementId == SyntheticDemoHouse.partitionWallId },
        )
    }
}
