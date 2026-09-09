package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.candidate.FloorCandidate
import com.buildplan.app.analyzer.candidate.LevelsCandidate
import com.buildplan.app.analyzer.candidate.OpeningCandidate
import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RoomBoundarySegment
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.candidate.Segment
import com.buildplan.app.analyzer.candidate.WallCandidate
import com.buildplan.app.analyzer.candidate.WallClass
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.quantity.QuantityTakeoffEngine
import com.buildplan.app.analyzer.quantity.SurfaceType
import com.buildplan.app.analyzer.roof.RoofHeightField
import com.buildplan.app.analyzer.roof.RoofSolver
import com.buildplan.app.analyzer.site.PublishedRoofFamily
import com.buildplan.app.analyzer.site.RoomKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023-QTY — measurement semantics on a synthetic two-room storey and a
 * synthetic attic: one shared wall gives two faces, deductions subtract only
 * from the face they are on, and a sloped ceiling is counted on its slope.
 */
class QuantityTakeoffEngineTest {

    private fun m(v: Double, unit: MeasureUnit = MeasureUnit.METER) = Measured(v, unit, FactFidelity.SOURCE_TRACED, Provenance("test", "test", "test"))
    private fun missing() = Measured.missing(MeasureUnit.METER, "test")

    private fun rect(x0: Double, z0: Double, x1: Double, z1: Double) = Polygon(listOf(Pt(x0, z0), Pt(x1, z0), Pt(x1, z1), Pt(x0, z1)))

    private fun levels(groundClear: Double, upperFloor: Double? = null, attic: Double? = null) = LevelsCandidate(
        terrain = m(-0.3), groundFloor = m(0.0), upperFloor = upperFloor?.let(::m) ?: missing(), groundClearHeight = m(groundClear),
        upperClearHeight = missing(), upperSlabThickness = m(0.3), kneeWall = m(1.0), eave = missing(), ridge = missing(), buildingHeight = missing(),
        atticFlatCeilingHeight = attic?.let(::m) ?: missing(), notes = emptyList(),
    )

    @Test
    fun `a shared wall is one wall candidate and two room-facing surfaces, each with its own deduction`() {
        // Two 4 x 3 rooms side by side, separated by a wall at x = 4 with a 0.9 m door.
        val shared = WallCandidate("w-shared", "f0", Segment(Pt(4.0, 0.0), Pt(4.0, 3.0)), m(3.0), m(0.12), WallClass.PARTITION, m(0.0), m(2.5), listOf("r1"), listOf("r2"), false, listOf("o1"), FactFidelity.SOURCE_TRACED)
        val door = OpeningCandidate("o1", "w-shared", "f0", OpeningType.DOOR, m(1.0), m(0.9), missing(), missing(), listOf("r1", "r2"), false, FactFidelity.TRACE_UNCERTAIN)
        fun room(id: String, poly: Polygon, sharedSegment: Segment, neighbour: String) = RoomCandidate(
            id, "f0", id, null, RoomKind.OTHER, poly, RoomGeometryState.VALID_SIMPLE_RING, "test", m(poly.perimeter), m(poly.area, MeasureUnit.SQUARE_METER), m(poly.area, MeasureUnit.SQUARE_METER), missing(),
            poly.edges.map { e -> RoomBoundarySegment(e, if (e.a.x == 4.0 && e.b.x == 4.0) "w-shared" else null, if (e.a.x == 4.0 && e.b.x == 4.0) neighbour else null, false) },
            FactFidelity.SOURCE_TRACED, "test",
        )
        val r1 = room("r1", rect(0.0, 0.0, 4.0, 3.0), Segment(Pt(4.0, 0.0), Pt(4.0, 3.0)), "r2")
        val r2 = room("r2", rect(4.0, 0.0, 8.0, 3.0), Segment(Pt(4.0, 0.0), Pt(4.0, 3.0)), "r1")
        val floor = FloorCandidate("f0", "Parter", 0, null, rect(0.0, 0.0, 8.0, 3.0), null, m(0.0), m(2.5), listOf(r1, r2), emptyList(), null)
        val candidate = ProjectAnalysisCandidate(listOf(floor), listOf(shared), listOf(door), emptyList(), null, levels(2.5), emptyList(), emptyList())

        val q = QuantityTakeoffEngine(candidate, null).compute()

        val faces = q.surfaces.filter { it.type == SurfaceType.WALL_FACE && it.wallId == "w-shared" }
        assertEquals(2, faces.size)
        assertEquals(setOf("r1", "r2"), faces.map { it.roomId }.toSet())
        faces.forEach { f ->
            assertEquals(3.0 * 2.5, f.grossArea.value!!, 1e-6)
            assertEquals(0.9 * 2.05, f.deductions.value!!, 1e-6)
            assertEquals(FactFidelity.DISPLAY_ASSUMPTION, f.netArea.fidelity)
            assertTrue(f.semantics.contains("one side"))
        }
        // Each room: 4 faces, gross = perimeter x height, only the shared face deducted.
        val rq1 = q.rooms.first { it.roomId == "r1" }
        assertEquals(14.0 * 2.5, rq1.wallGross.value!!, 1e-6)
        assertEquals(0.9 * 2.05, rq1.wallOpenings.value!!, 1e-6)
        assertEquals(12.0, rq1.floorArea.value!!, 1e-6)
        assertEquals(12.0, rq1.ceilingFlat.value!!, 0.2)
        assertEquals(0.0, rq1.ceilingSloped.value!!, 1e-9)
        assertEquals(12.0 * 2.5, rq1.volume.value!!, 0.5)
        // Structural: the shared wall is counted once, on the storey.
        assertEquals(3.0 * (2.5 + 0.3), q.floors.first().partitionsStructural.value!!, 1e-6)
    }

    @Test
    fun `an attic room under a gable gets a sloped ceiling, a flat ceiling and a usable area by the height rule`() {
        // Gable roof over an 8 x 10 outline at 45 degrees, eave at 3.0 m; attic floor at 3.0 m, flat ceiling 2.5 m above it.
        val outline = rect(0.0, 0.0, 8.0, 10.0)
        val roof = RoofSolver.solve(RoofSolver.Input(outline, PublishedRoofFamily.GABLE, m(45.0, MeasureUnit.DEGREE), m(3.0), null, emptyList(), m(3.0)))
        assertNotNull(roof)
        // Room spanning from the eave (x=0) to the ridge (x=4), 5 m long.
        val poly = rect(0.0, 2.0, 4.0, 7.0)
        val room = RoomCandidate("a1", "f1", "a1", null, RoomKind.OTHER, poly, RoomGeometryState.VALID_SIMPLE_RING, "test", m(poly.perimeter), m(poly.area, MeasureUnit.SQUARE_METER), missing(), missing(),
            poly.edges.map { RoomBoundarySegment(it, null, null, false) }, FactFidelity.SOURCE_TRACED, "test")
        val floor = FloorCandidate("f1", "Poddasze", 1, null, outline, outline, m(3.0), m(2.5), listOf(room), emptyList(), null)
        val candidate = ProjectAnalysisCandidate(listOf(floor), emptyList(), emptyList(), emptyList(), roof, levels(2.5, 3.0, 2.5), emptyList(), emptyList())

        val q = QuantityTakeoffEngine(candidate, RoofHeightField(roof!!)).compute()
        val rq = q.rooms.single()
        // Height h(x) = x (45 degrees) above the attic floor, capped at 2.5: flat where x >= 2.5 (1.5 m wide), sloped where 0 < x < 2.5.
        assertEquals(1.5 * 5.0, rq.ceilingFlat.value!!, 0.3)
        assertEquals(2.5 * 5.0 * Math.sqrt(2.0), rq.ceilingSloped.value!!, 0.5)
        // Usable: full above 2.2 m (x >= 2.2: 1.8 m), half between 1.4 and 2.2 (0.8 m), none below.
        assertEquals((1.8 + 0.4) * 5.0, rq.usableAreaByHeightRule.value!!, 0.4)
        assertTrue(rq.usableAreaByHeightRule.value!! < rq.floorArea.value!!)
        // The eave-side face of the room is under the slope, so its gross area is below length x 2.5.
        val faces = q.surfaces.filter { it.type == SurfaceType.WALL_FACE && it.roomId == "a1" }
        assertEquals(4, faces.size)
        val eaveFace = faces.first { it.basis.contains("from (0.00,") && it.basis.contains("to (0.00,") }
        val ridgeFace = faces.first { it.basis.contains("from (4.00,") && it.basis.contains("to (4.00,") }
        assertTrue(eaveFace.grossArea.value!! < 1.0)
        assertEquals(5.0 * 2.5, ridgeFace.grossArea.value!!, 0.3)
    }

    @Test
    fun `a room with unresolved geometry yields no ceiling, volume or wall faces`() {
        // Same room twice: once with a proved ring, once refused. The refused one must not
        // report a small-but-exact-looking ceiling; it must report nothing at all.
        val poly = rect(0.0, 0.0, 4.0, 3.0)
        fun room(polygon: Polygon?, state: RoomGeometryState) = RoomCandidate(
            "r1", "f0", "r1", null, RoomKind.OTHER, polygon, state, "test", m(poly.perimeter), m(poly.area, MeasureUnit.SQUARE_METER),
            m(poly.area, MeasureUnit.SQUARE_METER), missing(),
            if (polygon == null) emptyList() else poly.edges.map { RoomBoundarySegment(it, null, null, false) },
            FactFidelity.SOURCE_TRACED, "test",
        )
        fun quantities(r: RoomCandidate) = QuantityTakeoffEngine(
            ProjectAnalysisCandidate(
                listOf(FloorCandidate("f0", "Parter", 0, null, rect(0.0, 0.0, 4.0, 3.0), null, m(0.0), m(2.5), listOf(r), emptyList(), null)),
                emptyList(), emptyList(), emptyList(), null, levels(2.5), emptyList(), emptyList(),
            ),
            null,
        ).compute()

        val ok = quantities(room(poly, RoomGeometryState.VALID_SIMPLE_RING)).rooms.single()
        assertEquals(12.0, ok.ceilingFlat.value!!, 0.1)
        assertNotNull(ok.volume.value)

        val refused = quantities(room(null, RoomGeometryState.UNRESOLVED_REGION))
        val q = refused.rooms.single()
        assertNull("ceiling", q.ceilingFlat.value)
        assertNull("ceiling total", q.ceilingTotal.value)
        assertNull("volume", q.volume.value)
        assertNull("wall gross", q.wallGross.value)
        assertNull("usable by height rule", q.usableAreaByHeightRule.value)
        assertTrue(q.wallFaceIds.isEmpty())
        // The floor is a measurement of the region, not of the ring, so it survives — and it is
        // the only surface the room contributes.
        assertEquals(12.0, q.floorArea.value!!, 1e-9)
        assertTrue(refused.surfaces.none { it.type == SurfaceType.WALL_FACE || it.type == SurfaceType.CEILING_FLAT })
    }
}
