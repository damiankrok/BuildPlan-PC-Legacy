package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.plan.PlanCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023B-CAL — the scale, and what is allowed to disagree with it.
 *
 * The STAGE-023A failure this pins down: Project B's attic was reported
 * `CONFLICTING` at a 14.8 % scale disagreement that was not a scale problem at
 * all. The room-area anchor weighed *every* enclosed region against the
 * *published rooms*, and the plan also encloses a terrace, a stairwell void
 * and a few slivers, so it was comparing two different things.
 */
class PlanCalibratorTest {

    private fun exact(v: Double, unit: MeasureUnit) = Measured(v, unit, FactFidelity.SOURCE_EXACT, Provenance("t", "t", "t"))

    @Test
    fun `the footprint anchor sets the scale and the room total only checks it`() {
        // 100 m2 of footprint over 10000 px2 is 10 px/m; the room total implies the same.
        val c = PlanCalibrator.calibrate(
            footprintPixelArea = 10000,
            footprintAreaM2 = exact(100.0, MeasureUnit.SQUARE_METER),
            regionsPixelArea = 8000,
            publishedFloorTotalM2 = exact(80.0, MeasureUnit.SQUARE_METER),
            originPx = Pt(0.0, 0.0),
            sharedScale = null,
        )
        assertNotNull(c)
        assertEquals(10.0, c!!.pixelsPerMeter.value!!, 1e-9)
        assertTrue(c.method.contains("FOOTPRINT_AREA"))
        assertEquals(FactFidelity.SOURCE_DERIVED, c.confidence)
    }

    @Test
    fun `regions that are not rooms make the crude anchor disagree`() {
        // The same plan, but the segmentation also enclosed 40 m2 of terrace and voids.
        val c = PlanCalibrator.calibrate(
            footprintPixelArea = 10000,
            footprintAreaM2 = exact(100.0, MeasureUnit.SQUARE_METER),
            regionsPixelArea = 12000,
            publishedFloorTotalM2 = exact(80.0, MeasureUnit.SQUARE_METER),
            originPx = Pt(0.0, 0.0),
            sharedScale = null,
        )!!
        assertEquals(FactFidelity.CONFLICTING, c.confidence)
        assertTrue(c.residual!! > 0.08)
    }

    @Test
    fun `reconciling against the matched rooms removes a conflict that was a segmentation gap`() {
        val crude = PlanCalibrator.calibrate(
            footprintPixelArea = 10000,
            footprintAreaM2 = exact(100.0, MeasureUnit.SQUARE_METER),
            regionsPixelArea = 12000,
            publishedFloorTotalM2 = exact(80.0, MeasureUnit.SQUARE_METER),
            originPx = Pt(0.0, 0.0),
            sharedScale = null,
        )!!
        assertEquals(FactFidelity.CONFLICTING, crude.confidence)

        // Only the regions that were matched, against only the rooms they matched: 60 m2 of
        // published room over 6000 px2 is the same 10 px/m the footprint gave.
        val reconciled = PlanCalibrator.reconcile(crude, matchedPixels = 6000, matchedPublishedM2 = 60.0, matchedRooms = 5)
        assertEquals(10.0, reconciled.pixelsPerMeter.value!!, 1e-9)
        assertEquals(FactFidelity.SOURCE_DERIVED, reconciled.confidence)
        assertTrue(reconciled.residual!! < 0.01)
        assertTrue(reconciled.method.contains("MATCHED_ROOM_AREAS"))
        assertTrue("the crude anchor is replaced, not kept alongside", reconciled.anchors.none { it.kind == "PUBLISHED_ROOM_AREAS" })
    }

    @Test
    fun `reconciling never moves the scale itself`() {
        val crude = PlanCalibrator.calibrate(
            footprintPixelArea = 10000,
            footprintAreaM2 = exact(100.0, MeasureUnit.SQUARE_METER),
            regionsPixelArea = null,
            publishedFloorTotalM2 = null,
            originPx = Pt(0.0, 0.0),
            sharedScale = null,
        )!!
        // Matched rooms that disagree badly must raise a conflict, not re-scale the plan.
        val reconciled = PlanCalibrator.reconcile(crude, matchedPixels = 6000, matchedPublishedM2 = 40.0, matchedRooms = 5)
        assertEquals(10.0, reconciled.pixelsPerMeter.value!!, 1e-9)
        assertEquals(FactFidelity.CONFLICTING, reconciled.confidence)
    }

    @Test
    fun `too few matched rooms is not evidence and leaves the calibration alone`() {
        val crude = PlanCalibrator.calibrate(
            footprintPixelArea = 10000,
            footprintAreaM2 = exact(100.0, MeasureUnit.SQUARE_METER),
            regionsPixelArea = 12000,
            publishedFloorTotalM2 = exact(80.0, MeasureUnit.SQUARE_METER),
            originPx = Pt(0.0, 0.0),
            sharedScale = null,
        )!!
        val reconciled = PlanCalibrator.reconcile(crude, matchedPixels = 900, matchedPublishedM2 = 9.0, matchedRooms = 1)
        assertEquals(crude.confidence, reconciled.confidence)
        assertEquals(crude.residual!!, reconciled.residual!!, 1e-12)
    }

    @Test
    fun `no anchor at all is no calibration, not a guess`() {
        val c = PlanCalibrator.calibrate(null, null, null, null, Pt(0.0, 0.0), null)
        assertEquals(null, c)
    }
}
