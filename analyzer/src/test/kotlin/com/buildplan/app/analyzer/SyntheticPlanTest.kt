package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.plan.FloorCandidateBuilder
import com.buildplan.app.analyzer.plan.PlanAnalyzer
import com.buildplan.app.analyzer.plan.PlanCalibrator
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.site.PublishedFloor
import com.buildplan.app.analyzer.site.PublishedRoom
import com.buildplan.app.analyzer.site.RoomKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023-PLAN — the plan pipeline on a drawn raster with known geometry: a
 * 10 x 8 m house at 40 px/m with 0.44 m exterior walls, one 0.12 m
 * partition splitting it into 6 x 7.12 and 3.32 x 7.12 rooms, a 0.9 m door
 * in the partition, a 1.5 m window (drawn with a thin glazing line) and a
 * 1.0 m entrance door in the exterior wall. No project, no source: the
 * expected values are the drawing's.
 */
class SyntheticPlanTest {

    private val ppm = 40.0
    private val w = 600
    private val h = 500

    private fun px(m: Double) = (m * ppm).toInt()

    private fun drawPlan(): RasterImage {
        val argb = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
            for (y in y0 until y1) for (x in x0 until x1) if (x in 0 until w && y in 0 until h) argb[y * w + x] = colour
        }
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val floor = 0xFFEBECEC.toInt()
        val ox = 100
        val oy = 60
        val t = px(0.44)
        val p = px(0.12)
        // Floor fill inside.
        fill(ox, oy, ox + px(10.0), oy + px(8.0), floor)
        // Exterior ring.
        fill(ox, oy, ox + px(10.0), oy + t, black)                       // north
        fill(ox, oy + px(8.0) - t, ox + px(10.0), oy + px(8.0), black)   // south
        fill(ox, oy, ox + t, oy + px(8.0), black)                         // west
        fill(ox + px(10.0) - t, oy, ox + px(10.0), oy + px(8.0), black)   // east
        // Partition at x = 0.44 + 6.0 = 6.44 m (centre 6.5 m), full depth, with a 0.9 m door gap at z 3..3.9.
        val px0 = ox + px(6.44)
        fill(px0, oy + t, px0 + p, oy + px(8.0) - t, black)
        fill(px0, oy + px(3.0), px0 + p, oy + px(3.9), floor)
        // Window in the north wall: gap x 2..3.5 m with a thin glazing line.
        fill(ox + px(2.0), oy, ox + px(3.5), oy + t, white)
        fill(ox + px(2.0), oy + t / 2, ox + px(3.5), oy + t / 2 + 1, black)
        // Entrance door in the south wall: gap x 8..9 m, no line.
        fill(ox + px(8.0), oy + px(8.0) - t, ox + px(9.0), oy + px(8.0), white)
        // Some furniture: a 1-px table outline and a gate-less symbol.
        fill(ox + px(1.0), oy + px(5.0), ox + px(2.2), oy + px(5.0) + 1, 0xFF808080.toInt())
        fill(ox + px(1.0), oy + px(5.0), ox + px(1.0) + 1, oy + px(5.8), 0xFF808080.toInt())
        // A red label (must be ignored as ink).
        fill(ox + px(3.0), oy + px(4.0), ox + px(3.0) + 8, oy + px(4.0) + 12, 0xFFCC2020.toInt())
        return RasterImage(w, h, argb)
    }

    private fun room(o: Int, name: String, area: Double, kind: RoomKind = RoomKind.OTHER) = PublishedRoom(
        o, name, Measured(area, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("p", "l", "m")), Measured.missing(MeasureUnit.SQUARE_METER, "none"), kind,
    )

    @Test
    fun `walls, openings, regions, calibration and rooms come out as drawn`() {
        val image = drawPlan()
        val big = 6.0 * 7.12
        val small = 3.0 * 7.12
        val floor = PublishedFloor(
            "PARTER", 0,
            Measured(big + small, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("p", "l", "m")),
            Measured.missing(MeasureUnit.SQUARE_METER, "none"),
            listOf(room(1, "Salon", big, RoomKind.LIVING), room(2, "Kuchnia", small, RoomKind.KITCHEN)),
        )
        val footprint = Measured(80.0, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("p", "l", "m"))
        val analysis = PlanAnalyzer().analyse("test://plan", image, floor, footprint, null, attic = false)

        val cal = analysis.calibration
        assertNotNull(cal)
        assertEquals(40.0, cal!!.pixelsPerMeter.requireValue(), 0.4)
        assertTrue("residual ${cal.residual}", (cal.residual ?: 0.0) < 0.03)

        // Footprint traced as 10 x 8.
        val outline = analysis.footprintOutlinePx.map { cal.toMeters(it) }
        assertEquals(4, outline.size)
        assertEquals(10.0, outline.maxOf { it.x } - outline.minOf { it.x }, 0.1)
        assertEquals(8.0, outline.maxOf { it.z } - outline.minOf { it.z }, 0.1)

        // Two regions, matched to the two rooms.
        assertEquals(2, analysis.regions.size)
        val matching = analysis.matching!!
        assertEquals(2, matching.matches.size)
        assertTrue(matching.unmatchedRooms.isEmpty())

        // Openings: the door in the partition (interior, door-like), the window (exterior, glazed), the entrance door (exterior, no glazing).
        val built = FloorCandidateBuilder.build(0, "PARTER", 0, floor, analysis, Measured.assumed(0.0, MeasureUnit.METER, "t"), Measured.assumed(2.7, MeasureUnit.METER, "t"))
        val openings = built.openings
        val widths = openings.map { it.width.requireValue() }
        assertTrue("door 0.9 in $widths", widths.any { kotlin.math.abs(it - 0.9) < 0.08 })
        assertTrue("window 1.5 in $widths", widths.any { kotlin.math.abs(it - 1.5) < 0.08 })
        assertTrue("entrance 1.0 in $widths", widths.any { kotlin.math.abs(it - 1.0) < 0.08 })
        val window = openings.first { kotlin.math.abs(it.width.requireValue() - 1.5) < 0.08 }
        assertTrue(window.exterior)
        assertEquals(com.buildplan.app.analyzer.candidate.OpeningType.WINDOW, window.type)
        assertEquals(FactFidelity.MISSING, window.height.fidelity)

        // Walls: exterior pieces are classed EXTERIOR, the partition PARTITION with its traced thickness.
        val partition = built.walls.first { it.wallClass == com.buildplan.app.analyzer.candidate.WallClass.PARTITION }
        assertEquals(0.12, partition.thickness.requireValue(), 0.05)
        assertTrue(built.walls.count { it.touchesOutside } >= 4)

        // Rooms: polygons with the drawn areas, and a shared boundary segment naming the other room.
        val salon = built.floor.rooms.first { it.name == "Salon" }
        val kuchnia = built.floor.rooms.first { it.name == "Kuchnia" }
        assertEquals(big, salon.plannedArea.requireValue(), big * 0.04)
        assertEquals(small, kuchnia.plannedArea.requireValue(), small * 0.04)
        assertTrue(salon.boundary.any { it.neighbourRoomId == kuchnia.id })
        assertTrue(salon.boundary.any { it.faceOutside })
        // No NaN anywhere in the candidate numbers.
        (built.walls.flatMap { listOf(it.length.value, it.thickness.value) } + built.floor.rooms.map { it.plannedArea.value }).forEach { assertTrue(it != null && it.isFinite()) }
    }

    @Test
    fun `calibration reports a residual between independent anchors and refuses without any`() {
        val none = PlanCalibrator.calibrate(1000, null, 800, null, Pt(0.0, 0.0), null)
        assertEquals(null, none)
        val exact = Measured(10.0, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("p", "l", "m"))
        val one = PlanCalibrator.calibrate(160_000, exact, null, null, Pt(3.0, 4.0), null)!!
        assertEquals(126.49, one.pixelsPerMeter.requireValue(), 0.01)
        assertEquals(null, one.residual)
        val checked = PlanCalibrator.calibrate(160_000, exact, 95_000, Measured(6.0, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("p", "l", "m")), Pt(0.0, 0.0), null)!!
        assertEquals(2, checked.anchors.size)
        assertTrue(checked.residual!! < 0.05)
        assertEquals(FactFidelity.SOURCE_DERIVED, checked.confidence)
        val conflicting = PlanCalibrator.calibrate(160_000, exact, 90_000, Measured(4.0, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_EXACT, Provenance("p", "l", "m")), Pt(0.0, 0.0), null)!!
        assertEquals(FactFidelity.CONFLICTING, conflicting.confidence)
        assertEquals(Pt(3.0, 4.0), one.originPx)
        assertEquals(Pt(0.0, 0.0), one.toMeters(Pt(3.0, 4.0)))
    }
}
