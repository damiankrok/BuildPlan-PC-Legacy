package com.buildplan.app.geometry

import com.buildplan.app.domain.ReferenceBuilding
import com.buildplan.app.domain.model.BuildingElementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GEO011-05, GEO011-06, GEO011-08, GEO011-09 — the geometry collection.
 *
 * The collection is deliberately dull: it owns an ordered list, answers "what
 * draws this element", and can be checked against the canonical building it
 * claims to draw. What it must never do is start deciding *which* elements to
 * show — that belongs to `BuildingElementSelection.kt` — so the tests here are
 * about lookup, order, bounds and the id join, and nothing about visibility.
 */
class BuildingGeometryTest {

    private val building = ReferenceBuilding.build()

    private val foundationSlab = SlabGeometry(
        elementId = ReferenceBuilding.foundationId,
        outline = rectangle(10.0, 8.0),
        elevation = -0.4,
        thickness = 0.4,
    )

    private val sharedWall = WallGeometry(
        elementId = ReferenceBuilding.sharedWallId,
        start = PlanPoint(0.0, 4.0),
        end = PlanPoint(10.0, 4.0),
        baseElevation = 0.0,
        height = 2.8,
        thickness = 0.12,
    )

    private val roofWest = RoofFacetGeometry(
        elementId = ReferenceBuilding.roofId,
        vertices = listOf(
            ModelPoint(0.0, 2.8, 0.0),
            ModelPoint(10.0, 2.8, 0.0),
            ModelPoint(10.0, 5.2, 4.0),
            ModelPoint(0.0, 5.2, 4.0),
        ),
    )

    private val roofEast = RoofFacetGeometry(
        elementId = ReferenceBuilding.roofId,
        vertices = listOf(
            ModelPoint(0.0, 5.2, 4.0),
            ModelPoint(10.0, 5.2, 4.0),
            ModelPoint(10.0, 2.8, 8.0),
            ModelPoint(0.0, 2.8, 8.0),
        ),
    )

    private val geometry = BuildingGeometry(
        listOf(foundationSlab, sharedWall, roofWest, roofEast),
    )

    // --- GEO011-05 / GEO011-08: lookup by element, in declaration order ---

    @Test
    fun `GEO011-05 one element id owns every primitive that names it`() {
        assertEquals(
            listOf(roofWest, roofEast),
            geometry.primitivesFor(ReferenceBuilding.roofId),
        )
        // One semantic roof, two facets — and the collection says so.
        assertEquals(1, geometry.elementIds.count { it == ReferenceBuilding.roofId })
    }

    @Test
    fun `GEO011-08 filtering preserves declaration order and never duplicates`() {
        val selected = setOf(ReferenceBuilding.roofId, ReferenceBuilding.foundationId)

        // Declaration order, not the order the ids were asked for.
        assertEquals(listOf(foundationSlab, roofWest, roofEast), geometry.primitivesFor(selected))
        assertEquals(
            geometry.primitivesFor(selected),
            geometry.primitivesFor(selected.reversed()),
        )
    }

    @Test
    fun `GEO011-08 an element with no geometry yields nothing rather than failing`() {
        assertEquals(emptyList<BuildingGeometryPrimitive>(), geometry.primitivesFor(ReferenceBuilding.kitchenWindowId))
        assertEquals(
            emptyList<BuildingGeometryPrimitive>(),
            geometry.primitivesFor(emptySet<BuildingElementId>()),
        )
    }

    // --- GEO011-06: the id join is checked, not assumed ---

    @Test
    fun `GEO011-06 geometry naming an element outside the building is refused`() {
        val stray = BuildingElementId("e-nie-istnieje")
        val broken = BuildingGeometry(
            geometry.primitives + sharedWall.copy(elementId = stray),
        )

        assertEquals(setOf(stray), broken.unknownElementIds(building))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            broken.requireElementsIn(building)
        }
        assertTrue(
            "The failure must name the offending element, was: ${failure.message}",
            failure.message.orEmpty().contains(stray.value),
        )
    }

    @Test
    fun `GEO011-06 geometry drawing only known elements passes validation`() {
        assertEquals(emptySet<BuildingElementId>(), geometry.unknownElementIds(building))
        geometry.requireElementsIn(building)

        // A building may legitimately have elements with no shape yet; that is
        // an incomplete model, not an invalid one.
        assertTrue(building.elements.size > geometry.elementIds.size)
    }

    // --- GEO011-09: bounds over the whole collection ---

    @Test
    fun `GEO011-09 collection bounds contain every primitive`() {
        val bounds = requireNotNull(geometry.bounds)

        assertEquals(ModelPoint(0.0, -0.4, 0.0), bounds.min)
        assertEquals(ModelPoint(10.0, 5.2, 8.0), bounds.max)

        // Every primitive's own box sits inside the union.
        geometry.primitives.forEach { primitive ->
            assertEquals(bounds, bounds.encompass(primitive.bounds))
        }
    }

    @Test
    fun `GEO011-09 empty geometry has no bounds rather than a box at the origin`() {
        assertNull(BuildingGeometry().bounds)
    }

    private fun rectangle(width: Double, depth: Double): List<PlanPoint> = listOf(
        PlanPoint(0.0, 0.0),
        PlanPoint(width, 0.0),
        PlanPoint(width, depth),
        PlanPoint(0.0, depth),
    )

    private fun <T> Set<T>.reversed(): Set<T> = toList().asReversed().toSet()
}
