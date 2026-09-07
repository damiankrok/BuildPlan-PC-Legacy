package com.buildplan.app.geometry

import com.buildplan.app.domain.model.BuildingElementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GEO011-01..05, GEO011-09 — the geometry primitives keep their own promises.
 *
 * These are the invariants a renderer will trust without re-checking: that a
 * coordinate is a real number of metres, that a wall has length, that a slab
 * outline encloses something and that a facet has area. A primitive that fails
 * one of them cannot be drawn, and finding that out inside a draw call is far
 * too late.
 */
class GeometryPrimitiveTest {

    private val elementId = BuildingElementId("e-1")
    private val tolerance = 1e-9

    // --- GEO011-01: metres, Y up, plan in XZ, finite values ---

    @Test
    fun `GEO011-01 the plan plane is XZ and Y is the vertical axis`() {
        val plan = PlanPoint(x = 3.0, z = 4.0)
        val lifted = plan.at(2.5)

        assertEquals(3.0, lifted.x, tolerance)
        assertEquals(2.5, lifted.y, tolerance)
        assertEquals(4.0, lifted.z, tolerance)

        // Dropping the height gets the same plan position back: Y carries no
        // plan information, which is why an outline does not store one.
        assertEquals(plan, lifted.onPlan())

        // Plan distance is measured in the XZ plane only.
        assertEquals(5.0, PlanPoint(0.0, 0.0).distanceTo(plan), tolerance)
    }

    @Test
    fun `GEO011-01 points reject NaN and the infinities`() {
        val broken = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        broken.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { PlanPoint(value, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { PlanPoint(0.0, value) }
            assertThrows(IllegalArgumentException::class.java) { ModelPoint(value, 0.0, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { ModelPoint(0.0, value, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { ModelPoint(0.0, 0.0, value) }
        }
    }

    @Test
    fun `GEO011-01 coincidence is decided by one explicit epsilon`() {
        val origin = PlanPoint(0.0, 0.0)
        val withinEpsilon = PlanPoint(GeometryTolerance.LENGTH_METERS / 2.0, 0.0)
        val beyondEpsilon = PlanPoint(GeometryTolerance.LENGTH_METERS * 10.0, 0.0)

        assertTrue(origin.coincidesWith(withinEpsilon))
        assertFalse(origin.coincidesWith(beyondEpsilon))
        // Equality is not the same question: two distinct values still coincide.
        assertNotEquals(origin, withinEpsilon)
    }

    // --- GEO011-02: wall validation ---

    @Test
    fun `GEO011-02 a wall accepts a straight run with real thickness and height`() {
        val wall = WallGeometry(
            elementId = elementId,
            start = PlanPoint(0.0, 0.0),
            end = PlanPoint(4.0, 0.0),
            baseElevation = 0.0,
            height = 2.5,
            thickness = 0.3,
        )

        assertEquals(4.0, wall.length, tolerance)
        assertEquals(2.5, wall.topElevation, tolerance)
    }

    @Test
    fun `GEO011-02 a wall rejects zero length, non-finite and non-positive values`() {
        assertThrows(IllegalArgumentException::class.java) {
            wall(start = PlanPoint(1.0, 1.0), end = PlanPoint(1.0, 1.0))
        }
        // Distinct Doubles, but closer together than the geometry epsilon.
        assertThrows(IllegalArgumentException::class.java) {
            wall(start = PlanPoint(0.0, 0.0), end = PlanPoint(1e-9, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) { wall(height = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { wall(height = -2.5) }
        assertThrows(IllegalArgumentException::class.java) { wall(thickness = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { wall(thickness = -0.3) }
        assertThrows(IllegalArgumentException::class.java) { wall(baseElevation = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { wall(height = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `GEO011-02 a wall footprint is its centreline widened by half the thickness`() {
        val footprint = wall().footprint()

        assertEquals(4, footprint.size)
        assertEquals(
            listOf(
                PlanPoint(0.0, 0.15),
                PlanPoint(4.0, 0.15),
                PlanPoint(4.0, -0.15),
                PlanPoint(0.0, -0.15),
            ),
            footprint,
        )
    }

    // --- GEO011-03: slab validation ---

    @Test
    fun `GEO011-03 a slab requires a finite, non-degenerate plan outline`() {
        val slab = SlabGeometry(
            elementId = elementId,
            outline = rectangle(10.0, 8.0),
            elevation = 2.8,
            thickness = 0.25,
        )

        assertEquals(80.0, slab.planArea, tolerance)
        assertEquals(3.05, slab.topElevation, tolerance)

        // Fewer than three corners cannot enclose anything.
        assertThrows(IllegalArgumentException::class.java) {
            slab(outline = listOf(PlanPoint(0.0, 0.0), PlanPoint(1.0, 0.0)))
        }
        // Three vertices that are really one.
        assertThrows(IllegalArgumentException::class.java) {
            slab(outline = List(3) { PlanPoint(0.0, 0.0) })
        }
        // Collinear: three corners, no area.
        assertThrows(IllegalArgumentException::class.java) {
            slab(
                outline = listOf(
                    PlanPoint(0.0, 0.0),
                    PlanPoint(1.0, 0.0),
                    PlanPoint(2.0, 0.0),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { slab(thickness = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { slab(elevation = Double.NaN) }
    }

    @Test
    fun `GEO011-03 a closed outline describes the same slab as an open one`() {
        val open = slab(outline = rectangle(10.0, 8.0))
        val closed = slab(outline = rectangle(10.0, 8.0) + PlanPoint(0.0, 0.0))

        assertEquals(open.planArea, closed.planArea, tolerance)
        assertEquals(open.bounds, closed.bounds)
    }

    @Test
    fun `GEO011-03 slab area does not depend on the winding direction`() {
        val clockwise = slab(outline = rectangle(10.0, 8.0))
        val counterClockwise = slab(outline = rectangle(10.0, 8.0).reversed())

        assertEquals(clockwise.planArea, counterClockwise.planArea, tolerance)
    }

    // --- GEO011-04: roof facet validation ---

    @Test
    fun `GEO011-04 a roof facet requires a finite, non-degenerate 3D polygon`() {
        val facet = RoofFacetGeometry(
            elementId = elementId,
            vertices = listOf(
                ModelPoint(0.0, 0.0, 0.0),
                ModelPoint(4.0, 0.0, 0.0),
                ModelPoint(0.0, 3.0, 0.0),
            ),
        )
        assertEquals(6.0, facet.area, tolerance)

        assertThrows(IllegalArgumentException::class.java) {
            facet(vertices = listOf(ModelPoint(0.0, 0.0, 0.0), ModelPoint(1.0, 0.0, 0.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            facet(vertices = List(3) { ModelPoint(1.0, 2.0, 3.0) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            facet(
                vertices = listOf(
                    ModelPoint(0.0, 0.0, 0.0),
                    ModelPoint(1.0, 1.0, 1.0),
                    ModelPoint(2.0, 2.0, 2.0),
                ),
            )
        }
    }

    @Test
    fun `GEO011-04 a pitched facet is measured on its own plane, not its plan shadow`() {
        // A 10 m wide slope running 4 m horizontally while rising 3 m: the
        // sloping surface is 50 m2 even though it shades only 40 m2 of plan.
        val facet = facet(
            vertices = listOf(
                ModelPoint(0.0, 0.0, 0.0),
                ModelPoint(10.0, 0.0, 0.0),
                ModelPoint(10.0, 3.0, 4.0),
                ModelPoint(0.0, 3.0, 4.0),
            ),
        )

        assertEquals(50.0, facet.area, tolerance)
    }

    // --- GEO013-01: a vertical gable panel ---

    @Test
    fun `GEO013-01 a gable panel accepts a vertical polygon and measures its own plane`() {
        // The gable of a 8 m wide house rising 3 m from its eaves to its ridge.
        val panel = GablePanelGeometry(
            elementId = elementId,
            vertices = listOf(
                ModelPoint(0.0, 4.0, 2.0),
                ModelPoint(8.0, 4.0, 2.0),
                ModelPoint(4.0, 7.0, 2.0),
            ),
        )

        assertEquals(12.0, panel.area, tolerance)
        assertEquals(4.0, panel.bounds.min.y, tolerance)
        assertEquals(7.0, panel.bounds.max.y, tolerance)
        // A panel has no thickness, so its box is flat on the axis it faces.
        assertEquals(0.0, panel.bounds.sizeZ, tolerance)
    }

    @Test
    fun `GEO013-01 a gable panel rejects a polygon that is not vertical`() {
        // The apex pushed 3 m out of the wall plane: a sloping surface, which
        // is a roof facet's job and not a wall's. Note that moving a vertex is
        // not enough on its own — three points whose plan positions stay in a
        // line still describe a vertical plane, however their heights differ.
        val error = assertThrows(IllegalArgumentException::class.java) {
            GablePanelGeometry(
                elementId = elementId,
                vertices = listOf(
                    ModelPoint(0.0, 4.0, 2.0),
                    ModelPoint(8.0, 4.0, 2.0),
                    ModelPoint(4.0, 7.0, 5.0),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("not vertical"))

        assertThrows(IllegalArgumentException::class.java) {
            GablePanelGeometry(
                elementId = elementId,
                vertices = listOf(
                    ModelPoint(0.0, 4.0, 2.0),
                    ModelPoint(8.0, 4.0, 2.0),
                ),
            )
        }
    }

    // --- GEO011-05: one element, many primitives ---

    @Test
    fun `GEO011-05 several primitives may carry the same element id`() {
        val roofId = BuildingElementId("e-dach")
        val west = facet(elementId = roofId)
        val east = facet(
            elementId = roofId,
            vertices = listOf(
                ModelPoint(0.0, 0.0, 8.0),
                ModelPoint(4.0, 0.0, 8.0),
                ModelPoint(0.0, 3.0, 8.0),
            ),
        )

        // Same element, different shapes: the id is a join, not an identity.
        assertEquals(west.elementId, east.elementId)
        assertNotEquals(west, east)
    }

    // --- GEO011-09: per-primitive bounds ---

    @Test
    fun `GEO011-09 a wall is bounded by its footprint and its two levels`() {
        val bounds = wall(baseElevation = 0.5).bounds

        assertEquals(ModelPoint(0.0, 0.5, -0.15), bounds.min)
        assertEquals(ModelPoint(4.0, 3.0, 0.15), bounds.max)
    }

    @Test
    fun `GEO011-09 a slab is bounded by its outline and its own thickness`() {
        val bounds = slab(elevation = 2.8, thickness = 0.25).bounds

        assertEquals(ModelPoint(0.0, 2.8, 0.0), bounds.min)
        assertEquals(10.0, bounds.max.x, tolerance)
        assertEquals(3.05, bounds.max.y, tolerance)
        assertEquals(8.0, bounds.max.z, tolerance)
    }

    @Test
    fun `GEO011-09 bounds report their own size and centre`() {
        val bounds = LocalBounds(ModelPoint(-1.0, 0.0, 2.0), ModelPoint(3.0, 4.0, 8.0))

        assertEquals(4.0, bounds.sizeX, tolerance)
        assertEquals(4.0, bounds.sizeY, tolerance)
        assertEquals(6.0, bounds.sizeZ, tolerance)
        assertEquals(ModelPoint(1.0, 2.0, 5.0), bounds.center)
    }

    @Test
    fun `GEO011-09 bounds refuse an inverted box and an empty point set`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalBounds(ModelPoint(1.0, 0.0, 0.0), ModelPoint(0.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalBounds.around(emptyList())
        }
    }

    private fun wall(
        start: PlanPoint = PlanPoint(0.0, 0.0),
        end: PlanPoint = PlanPoint(4.0, 0.0),
        baseElevation: Double = 0.0,
        height: Double = 2.5,
        thickness: Double = 0.3,
    ) = WallGeometry(elementId, start, end, baseElevation, height, thickness)

    private fun slab(
        outline: List<PlanPoint> = rectangle(10.0, 8.0),
        elevation: Double = 0.0,
        thickness: Double = 0.25,
    ) = SlabGeometry(elementId, outline, elevation, thickness)

    private fun facet(
        elementId: BuildingElementId = this.elementId,
        vertices: List<ModelPoint> = listOf(
            ModelPoint(0.0, 0.0, 0.0),
            ModelPoint(4.0, 0.0, 0.0),
            ModelPoint(0.0, 3.0, 0.0),
        ),
    ) = RoofFacetGeometry(elementId, vertices)

    private fun rectangle(width: Double, depth: Double): List<PlanPoint> = listOf(
        PlanPoint(0.0, 0.0),
        PlanPoint(width, 0.0),
        PlanPoint(width, depth),
        PlanPoint(0.0, depth),
    )
}
