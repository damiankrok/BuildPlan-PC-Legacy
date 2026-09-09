package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.plan.FloorRegistration
import com.buildplan.app.analyzer.raster.BinaryMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023C-REGISTER — putting two storeys of one building into one frame.
 *
 * Each plan is calibrated against its own footprint corner, and the sheets do
 * not draw the house in the same place, so "above" meant nothing between two
 * storeys until this existed: a stairwell drawn on both plans never lined up
 * with itself.
 */
class FloorRegistrationTest {

    private val ppm = 10.0

    private fun calibration(originX: Int, originY: Int) = PlanCalibration(
        pixelsPerMeter = Measured(ppm, MeasureUnit.PIXEL_PER_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("test")),
        originPx = Pt(originX.toDouble(), originY.toDouble()),
        method = "test",
        anchors = emptyList(),
        residual = null,
        confidence = FactFidelity.SOURCE_DERIVED,
    )

    /** A rectangular storey drawn at (x, y) with the given size, in a 300x300 sheet. */
    private fun storey(x: Int, y: Int, w: Int, h: Int, notch: Boolean = false): BinaryMask {
        val m = BinaryMask(300, 300)
        for (yy in y until y + h) for (xx in x until x + w) m[xx, yy] = true
        // An asymmetric notch, so the correlation has one clear answer rather than four.
        if (notch) for (yy in y until y + h / 3) for (xx in x until x + w / 3) m[xx, yy] = false
        return m
    }

    @Test
    fun `two drawings of the same storey register to their offset`() {
        val lower = storey(40, 30, 120, 100, notch = true)
        val upper = storey(70, 90, 120, 100, notch = true)
        // Each plan's own origin is its footprint corner, as the calibrator sets it.
        val alignment = FloorRegistration.register(lower, calibration(40, 30), upper, calibration(70, 90))
        assertTrue("confidence ${alignment.confidence}", alignment.isReliable)
        // The two frames describe the same building, so the offset between them is zero.
        assertEquals(alignment.note, 0.0, alignment.offset.x, 0.15)
        assertEquals(alignment.note, 0.0, alignment.offset.z, 0.15)
    }

    @Test
    fun `an upper storey inset from the lower one still registers`() {
        // One building, 16 x 14 m, with a 4 x 4 m bite out of its top-right corner. The lower
        // storey covers all of it; the upper is the same shape inset 2 m on the left. The shared
        // bite is what makes the alignment unique — without a feature in common, sliding the two
        // left walls together scores just as well, which is the trap this guards.
        fun draw(sheetX: Int, sheetY: Int, fromBuildingX: Int): BinaryMask = BinaryMask(300, 300).also { m ->
            val width = 160 - fromBuildingX * 10
            for (yy in 0 until 140) for (xx in 0 until width) {
                val buildingX = fromBuildingX * 10 + xx
                val bite = buildingX >= 120 && yy < 40
                if (!bite) m[sheetX + xx, sheetY + yy] = true
            }
        }
        val lower = draw(sheetX = 40, sheetY = 30, fromBuildingX = 0)
        val upper = draw(sheetX = 90, sheetY = 100, fromBuildingX = 2)

        val alignment = FloorRegistration.register(lower, calibration(40, 30), upper, calibration(90, 100))
        assertTrue("confidence ${alignment.confidence}", alignment.isReliable)
        // The upper storey's own origin is 2 m right of the lower one's in the shared frame.
        assertEquals(alignment.note, 2.0, alignment.offset.x, 0.15)
        assertEquals(alignment.note, 0.0, alignment.offset.z, 0.15)
    }

    @Test
    fun `two storeys with nothing to align report low confidence rather than a shift`() {
        val lower = storey(40, 30, 120, 100)
        val upper = BinaryMask(300, 300) // empty
        val alignment = FloorRegistration.register(lower, calibration(40, 30), upper, calibration(40, 30))
        assertFalse(alignment.isReliable)
        assertEquals(0.0, alignment.offset.x, 1e-9)
    }

    @Test
    fun `registration is deterministic`() {
        val lower = storey(40, 30, 120, 100, notch = true)
        val upper = storey(70, 90, 120, 100, notch = true)
        val a = FloorRegistration.register(lower, calibration(40, 30), upper, calibration(70, 90))
        val b = FloorRegistration.register(lower, calibration(40, 30), upper, calibration(70, 90))
        assertEquals(a.offset.x, b.offset.x, 1e-12)
        assertEquals(a.offset.z, b.offset.z, 1e-12)
        assertEquals(a.confidence, b.confidence, 1e-12)
    }

    @Test
    fun `boxes are compared in the shared frame`() {
        val lower = com.buildplan.app.analyzer.candidate.Box(0.0, 0.0, 2.0, 2.0)
        val upper = com.buildplan.app.analyzer.candidate.Box(0.0, 0.0, 2.0, 2.0)
        val none = com.buildplan.app.analyzer.plan.FloorAlignment.NONE
        assertEquals(1.0, FloorRegistration.overlapFraction(lower, upper, none), 1e-9)
        val shifted = none.copy(offset = Pt(5.0, 0.0))
        assertEquals(0.0, FloorRegistration.overlapFraction(lower, upper, shifted), 1e-9)
    }
}
