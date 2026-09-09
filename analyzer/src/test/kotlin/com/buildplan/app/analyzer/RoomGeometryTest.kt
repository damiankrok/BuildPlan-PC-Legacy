package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RingValidity
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.plan.Axis
import com.buildplan.app.analyzer.plan.PieceGap
import com.buildplan.app.analyzer.plan.RoomGeometry
import com.buildplan.app.analyzer.raster.BinaryMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023B-ROOMGEOM — a room's outline must be the room.
 *
 * The STAGE-023A failure this pins down: a room the matcher assembled from
 * two regions either side of a doorway had its floor area measured from the
 * pixels and its ceiling from a ring traced around only the *first* region.
 * Reproduced here at 10 px/m, then required to come out consistent.
 */
class RoomGeometryTest {

    private val ppm = 10.0

    private fun calibration() = PlanCalibration(
        pixelsPerMeter = Measured(ppm, MeasureUnit.PIXEL_PER_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("test")),
        originPx = Pt(0.0, 0.0),
        method = "test",
        anchors = emptyList(),
        residual = null,
        confidence = FactFidelity.SOURCE_DERIVED,
    )

    private fun mask(width: Int, height: Int, vararg rects: IntArray): BinaryMask {
        val m = BinaryMask(width, height)
        rects.forEach { r ->
            for (y in r[1] until r[3]) for (x in r[0] until r[2]) m[x, y] = true
        }
        return m
    }

    @Test
    fun `a plain rectangular region resolves to a ring whose area matches its pixels`() {
        // 4.0 x 3.0 m at 10 px/m.
        val m = mask(80, 60, intArrayOf(10, 10, 50, 40))
        val resolved = RoomGeometry.resolve(m, calibration(), jogPx = 2)
        assertEquals(RoomGeometryState.VALID_SIMPLE_RING, resolved.state)
        assertEquals(12.0, resolved.rasterAreaM2, 1e-9)
        assertEquals(12.0, resolved.ringAreaM2, 1e-9)
        assertTrue(RingValidity.check(resolved.polygon!!.vertices).isValid)
    }

    @Test
    fun `two regions separated by a sealed gap are unresolved until the gap is bridged`() {
        // Two 3.0 x 3.0 m halves with a 2 px wall line between them at x = 40..42, and a
        // doorway gap in that line from y = 20 to y = 30.
        val left = intArrayOf(10, 10, 40, 40)
        val right = intArrayOf(42, 10, 72, 40)
        val split = mask(90, 60, left, right)
        val cal = calibration()

        // Unbridged: the trace can only follow one half, so the ring is half the pixels and the
        // room must be refused rather than reported at half size.
        val unbridged = RoomGeometry.resolve(split, cal, jogPx = 2)
        assertEquals(RoomGeometryState.UNRESOLVED_REGION, unbridged.state)
        assertNull(unbridged.polygon)
        assertEquals(2, unbridged.componentCount)

        // Bridged across exactly the gap that separates them: one shape, one ring, areas agree.
        val gap = PieceGap(Axis.VERTICAL, 20, 30, 40, 42, 0, 1)
        val regionAt: (Int, Int) -> Int? = { x, _ -> if (x < 40) 0 else if (x >= 42) 1 else null }
        val bridged = RoomGeometry.bridge(split, listOf(gap), regionAt, setOf(0, 1), probePx = 2)
        val resolved = RoomGeometry.resolve(bridged, cal, jogPx = 2)
        assertEquals(resolved.note, RoomGeometryState.VALID_SIMPLE_RING, resolved.state)
        assertNotNull(resolved.polygon)
        assertEquals(1, resolved.componentCount)
        // Ring and pixels agree: this is the invariant the ceiling collapse violated.
        assertEquals(resolved.rasterAreaM2, resolved.ringAreaM2, 1e-9)
        // And the room is now both halves plus the doorway strip, not one half.
        assertTrue("${resolved.ringAreaM2}", resolved.ringAreaM2 > 17.9)
    }

    @Test
    fun `bridging never joins regions that belong to different rooms`() {
        val left = intArrayOf(10, 10, 40, 40)
        val right = intArrayOf(42, 10, 72, 40)
        val onlyLeft = mask(90, 60, left)
        val gap = PieceGap(Axis.VERTICAL, 20, 30, 40, 42, 0, 1)
        val regionAt: (Int, Int) -> Int? = { x, _ -> if (x < 40) 0 else if (x >= 42) 1 else null }
        // Region 1 is another room, so it is not in the set and the strip stays closed.
        val bridged = RoomGeometry.bridge(onlyLeft, listOf(gap), regionAt, setOf(0), probePx = 2)
        assertEquals(onlyLeft.count(), bridged.count())
    }

    @Test
    fun `a region far larger than its traced ring is refused rather than shrunk`() {
        // Two lobes touching only diagonally: no gap joins them, so no ring covers both.
        val m = mask(90, 90, intArrayOf(10, 10, 40, 40), intArrayOf(40, 40, 70, 70))
        val resolved = RoomGeometry.resolve(m, calibration(), jogPx = 2)
        assertEquals(RoomGeometryState.UNRESOLVED_REGION, resolved.state)
        assertNull(resolved.polygon)
        assertTrue(resolved.note.contains("of its pixels") || resolved.note.contains("disconnected"))
    }

    @Test
    fun `jog smoothing may not shrink a room out of its own pixels`() {
        // An L with a shallow 3 px recess. At a 6 px jog tolerance smoothing would slide the
        // wall across it and lose real area; the resolver must fall back to a gentler trace.
        val m = mask(120, 120, intArrayOf(10, 10, 60, 110), intArrayOf(60, 10, 63, 60))
        val resolved = RoomGeometry.resolve(m, calibration(), jogPx = 6)
        assertEquals(RoomGeometryState.VALID_SIMPLE_RING, resolved.state)
        assertTrue(
            "coverage ${resolved.coverage} note ${resolved.note}",
            resolved.coverage >= RoomGeometry.MIN_COVERAGE && resolved.coverage <= RoomGeometry.MAX_COVERAGE,
        )
    }

    @Test
    fun `an empty region resolves to no polygon`() {
        val resolved = RoomGeometry.resolve(BinaryMask(20, 20), calibration(), jogPx = 2)
        assertEquals(RoomGeometryState.UNRESOLVED_REGION, resolved.state)
        assertNull(resolved.polygon)
    }
}
