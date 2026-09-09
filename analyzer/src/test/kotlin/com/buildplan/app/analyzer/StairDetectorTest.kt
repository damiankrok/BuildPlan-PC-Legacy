package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.plan.StairDetector
import com.buildplan.app.analyzer.raster.BinaryMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023B-STAIR — a flight is a run of evenly spaced tread lines, and a
 * repeating pattern that is not evenly spaced is not a flight.
 *
 * STAGE-023A detected zero stairs on both houses because its tread-spacing
 * window (0.20–0.36 m) was narrower than the goings drawings actually use.
 * Widening it alone turned furniture hatching into six false flights on
 * Project B, so regular spacing carries the weight that the narrow window
 * used to.
 */
class StairDetectorTest {

    /** Horizontal tread lines: `count` lines `length` px long, `pitch` px apart. */
    private fun treads(width: Int, height: Int, x0: Int, y0: Int, length: Int, count: Int, pitch: Int, jitter: IntArray? = null): BinaryMask {
        val m = BinaryMask(width, height)
        var y = y0
        for (i in 0 until count) {
            for (x in x0 until x0 + length) {
                if (y in 0 until height) m[x, y] = true
            }
            y += pitch + (jitter?.getOrNull(i) ?: 0)
        }
        return m
    }

    @Test
    fun `a straight flight of evenly spaced treads is found`() {
        // Ten treads 40 px long at a 10 px pitch: at 38 px/m that is a 1.05 m wide flight
        // with a 0.26 m going.
        val mask = treads(200, 200, 30, 20, 40, 10, 10)
        val zones = StairDetector.detect(mask, minTreadLengthPx = 15, spacingPx = 6..16)
        assertEquals(1, zones.size)
        assertEquals(10, zones.single().treadLines)
        assertEquals(10.0, zones.single().spacingPx, 0.5)
    }

    @Test
    fun `a run too short to be a flight is not one`() {
        val mask = treads(200, 200, 30, 20, 40, 3, 10)
        assertTrue(StairDetector.detect(mask, minTreadLengthPx = 15, spacingPx = 6..16).isEmpty())
    }

    @Test
    fun `an irregularly spaced pattern is not a flight`() {
        // The same ten lines, but the pitch wanders the way furniture edges and hatching do.
        val mask = treads(200, 200, 30, 20, 40, 10, 7, jitter = intArrayOf(0, 6, 0, 7, 1, 8, 0, 6, 2, 7))
        assertTrue(
            "irregular spacing must not read as treads",
            StairDetector.detect(mask, minTreadLengthPx = 15, spacingPx = 6..16).isEmpty(),
        )
    }

    @Test
    fun `lines spaced too far apart to be goings are not treads`() {
        // A 0.9 m pitch at 38 px/m: shelving, not steps.
        val mask = treads(400, 400, 30, 20, 40, 8, 34)
        assertTrue(StairDetector.detect(mask, minTreadLengthPx = 15, spacingPx = 6..16).isEmpty())
    }

    @Test
    fun `treads shorter than a stair is wide are not treads`() {
        val mask = treads(200, 200, 30, 20, 8, 10, 10)
        assertTrue(StairDetector.detect(mask, minTreadLengthPx = 15, spacingPx = 6..16).isEmpty())
    }

    @Test
    fun `a flight is reported once, not once per axis`() {
        val mask = treads(200, 200, 30, 20, 40, 10, 10)
        val zones = StairDetector.detect(mask, minTreadLengthPx = 15, spacingPx = 6..16)
        assertEquals(1, zones.size)
    }

    @Test
    fun `two flights around a landing are two runs`() {
        val a = treads(300, 300, 30, 20, 40, 6, 10)
        val b = treads(300, 300, 30, 160, 40, 6, 10)
        val zones = StairDetector.detect(a.or(b), minTreadLengthPx = 15, spacingPx = 6..16)
        assertEquals(2, zones.size)
        assertTrue(zones.all { it.treadLines == 6 })
    }

    @Test
    fun `the run axis is reported without claiming a climb direction`() {
        val mask = treads(200, 200, 30, 20, 40, 10, 10)
        val label = StairDetector.runAxisLabel(StairDetector.detect(mask, minTreadLengthPx = 15, spacingPx = 6..16).single())
        assertTrue(label.contains("runs"))
        assertTrue("no up or down may be claimed from tread lines", !label.contains("up") && !label.contains("down"))
    }
}
