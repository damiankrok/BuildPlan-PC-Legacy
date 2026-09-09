package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RingValidity
import com.buildplan.app.analyzer.candidate.RingVerdict
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023B-RING — the simple-ring invariant.
 *
 * The bug this exists to prevent is quiet: a ring that crosses itself still
 * has a shoelace area and still answers `contains`, so an invalid outline
 * produces confident, wrong quantities. Every case below is a shape a
 * crack-following raster trace actually produces.
 */
class RingValidityTest {

    private fun p(x: Double, z: Double) = Pt(x, z)

    @Test
    fun `a rectangle is a simple ring`() {
        val ring = listOf(p(0.0, 0.0), p(4.0, 0.0), p(4.0, 3.0), p(0.0, 3.0))
        val check = RingValidity.check(ring)
        assertTrue(check.detail, check.isValid)
        assertEquals(12.0, abs(RingValidity.signedArea(ring)), 1e-9)
    }

    @Test
    fun `a bow tie is rejected as self-intersecting`() {
        // The classic crossing quadrilateral: shoelace reports 0 m2, `contains` reports nonsense.
        val bowTie = listOf(p(0.0, 0.0), p(4.0, 4.0), p(4.0, 0.0), p(0.0, 4.0))
        val check = RingValidity.check(bowTie)
        assertEquals(RingVerdict.SELF_INTERSECTING, check.verdict)
        // A lopsided bow tie has a non-zero shoelace area, so nothing downstream would notice it
        // without this check — the even-odd rule just quietly drops the smaller lobe.
        val lopsided = listOf(p(0.0, 0.0), p(6.0, 4.0), p(6.0, 0.0), p(0.0, 2.0))
        assertEquals(RingVerdict.SELF_INTERSECTING, RingValidity.check(lopsided).verdict)
        assertTrue(abs(RingValidity.signedArea(lopsided)) > 1.0)
    }

    @Test
    fun `a ring that touches itself at one vertex is not simple`() {
        // Two lobes pinched at (2,2): exactly what a region joined at a lattice corner traces as.
        val pinched = listOf(
            p(0.0, 0.0), p(2.0, 0.0), p(2.0, 2.0), p(4.0, 2.0), p(4.0, 4.0), p(2.0, 4.0),
            p(2.0, 2.0), p(0.0, 2.0),
        )
        assertEquals(RingVerdict.SELF_INTERSECTING, RingValidity.check(pinched).verdict)
    }

    @Test
    fun `a spike is rejected and repaired away`() {
        val spiked = listOf(p(0.0, 0.0), p(4.0, 0.0), p(4.0, 3.0), p(2.0, 3.0), p(3.0, 3.0), p(0.0, 3.0))
        assertEquals(RingVerdict.SPIKE, RingValidity.check(spiked).verdict)
        val repair = RingValidity.repair(spiked)
        assertNotNull(repair.ring)
        assertTrue(repair.check.isValid)
        assertEquals(12.0, repair.repairedArea, 1e-9)
    }

    @Test
    fun `a duplicated vertex is a zero-length edge and is repaired away`() {
        val doubled = listOf(p(0.0, 0.0), p(4.0, 0.0), p(4.0, 0.0), p(4.0, 3.0), p(0.0, 3.0))
        assertEquals(RingVerdict.ZERO_LENGTH_EDGE, RingValidity.check(doubled).verdict)
        val repair = RingValidity.repair(doubled)
        assertTrue(repair.check.isValid)
        assertEquals(4, repair.ring!!.size)
        assertEquals(12.0, repair.repairedArea, 1e-9)
    }

    @Test
    fun `repairing a pinched ring keeps the largest loop and reports what it cost`() {
        // A 4 m2 lobe pinched onto a 16 m2 one. The repair must keep the 16 and say so.
        val pinched = listOf(
            p(0.0, 0.0), p(4.0, 0.0), p(4.0, 4.0), p(0.0, 4.0),
            p(0.0, 0.0), p(-2.0, 0.0), p(-2.0, -2.0), p(0.0, -2.0),
        )
        val repair = RingValidity.repair(pinched)
        assertTrue(repair.check.isValid)
        assertEquals(16.0, repair.repairedArea, 1e-9)
        // The caller must be able to see that a fifth of the shape was dropped.
        assertTrue("areaRetained ${repair.areaRetained}", repair.areaRetained < 0.9)
    }

    @Test
    fun `a degenerate collinear ring is rejected rather than given a zero area`() {
        val flat = listOf(p(0.0, 0.0), p(4.0, 0.0), p(8.0, 0.0))
        val verdict = RingValidity.check(flat).verdict
        assertTrue("$verdict", verdict == RingVerdict.DEGENERATE_AREA || verdict == RingVerdict.SPIKE)
    }

    @Test
    fun `winding is normalised without changing the shape`() {
        val clockwise = listOf(p(0.0, 0.0), p(0.0, 3.0), p(4.0, 3.0), p(4.0, 0.0))
        val normalised = RingValidity.normaliseWinding(clockwise)
        assertTrue(RingValidity.signedArea(normalised) > 0)
        assertEquals(Polygon(clockwise).area, Polygon(normalised).area, 1e-9)
    }

    @Test
    fun `repair never invents area`() {
        val pinched = listOf(
            p(0.0, 0.0), p(4.0, 0.0), p(4.0, 4.0), p(0.0, 4.0),
            p(0.0, 0.0), p(-2.0, 0.0), p(-2.0, -2.0), p(0.0, -2.0),
        )
        val repair = RingValidity.repair(pinched)
        assertTrue(repair.repairedArea <= repair.originalArea + 1e-9)
    }

    @Test
    fun `an unrepairable ring yields no polygon at all`() {
        val degenerate = listOf(p(0.0, 0.0), p(1.0, 0.0))
        val repair = RingValidity.repair(degenerate)
        assertNull(repair.ring)
        assertEquals(0.0, repair.areaRetained, 1e-12)
    }
}
