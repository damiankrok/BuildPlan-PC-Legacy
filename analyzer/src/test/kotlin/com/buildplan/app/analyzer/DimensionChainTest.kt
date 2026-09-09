package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.text.ChainVerdict
import com.buildplan.app.analyzer.text.DimensionChains
import com.buildplan.app.analyzer.text.TextLegibility
import com.buildplan.app.analyzer.text.TextObservation
import com.buildplan.app.analyzer.text.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023C-CHAIN — a read number becomes evidence only when something
 * independent agrees with it.
 *
 * The recogniser will eventually turn a 5 into a 6 at eleven pixels, and no
 * per-glyph confidence can rule that out. What can is the drawing itself: the
 * labels are spaced in proportion to the values they print, the plan has a
 * scale derived without reading a character, and the building has a traced
 * extent. These tests pin down which of those count as corroboration and which
 * do not.
 */
class DimensionChainTest {

    /** A read label at a position, `along` the chain and `across` it (image pixels). */
    private fun label(
        text: String,
        along: Int,
        across: Int,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ): TextObservation {
        val bounds = if (orientation == TextOrientation.HORIZONTAL) {
            PixelBox(along - 6, across - 6, along + 6, across + 6)
        } else {
            PixelBox(across - 6, along - 6, across + 6, along + 6)
        }
        return TextObservation("plan", bounds, text, 0.95, "test", TextLegibility.READ, text.length, 12, orientation)
    }

    /**
     * Places labels so their spacing matches their values at `pxPerCm`:
     * consecutive centres sit half of each part apart.
     */
    private fun chainAt(values: List<Int>, pxPerCm: Double, across: Int, start: Int = 100): List<TextObservation> {
        var centre = start.toDouble()
        return values.mapIndexed { i, v ->
            if (i > 0) centre += (values[i - 1] + v) / 2.0 * pxPerCm
            label(v.toString(), centre.toInt(), across)
        }
    }

    @Test
    fun `three labels whose spacing matches their values are self-consistent`() {
        val chain = DimensionChains.assemble(chainAt(listOf(400, 300, 500), 0.4, across = 50)).single()
        assertEquals(ChainVerdict.SELF_CONSISTENT, chain.verdict)
        assertEquals(1200, chain.totalCentimetres)
        assertEquals(40.0, chain.pixelsPerMeter!!, 1.0)
        assertTrue(chain.isTrusted)
    }

    @Test
    fun `a misread end label breaks the chain on its own spacing`() {
        // Placed for 400 but read as 800: the first gap no longer fits, the second still does.
        val placed = chainAt(listOf(400, 300, 500), 0.4, across = 50)
        val broken = listOf(label("800", (placed[0].bounds.minX + placed[0].bounds.maxX) / 2, 50), placed[1], placed[2])
        val chain = DimensionChains.assemble(broken).single()
        assertEquals(ChainVerdict.INCONSISTENT, chain.verdict)
        assertFalse(chain.isTrusted)
        assertEquals(null, chain.pixelsPerMeter)
    }

    @Test
    fun `a misread middle label survives spacing but is caught by the plan scale`() {
        // A middle error stretches the gaps either side of it almost equally, so the two
        // scales still agree with each other — and agree on a scale the plan does not share.
        val placed = chainAt(listOf(400, 300, 500), 0.4, across = 50)
        val broken = listOf(placed[0], label("800", (placed[1].bounds.minX + placed[1].bounds.maxX) / 2, 50), placed[2])

        val unchecked = DimensionChains.assemble(broken).single()
        assertEquals("spacing alone cannot see it", ChainVerdict.SELF_CONSISTENT, unchecked.verdict)

        val checked = DimensionChains.assemble(broken, planPixelsPerMeter = 40.0).single()
        assertEquals(ChainVerdict.INCONSISTENT, checked.verdict)
        assertFalse(checked.isTrusted)
    }

    @Test
    fun `a pair is never self-consistent, because one gap cannot disagree with itself`() {
        val chain = DimensionChains.assemble(chainAt(listOf(400, 300), 0.4, across = 50)).single()
        assertEquals(ChainVerdict.UNCORROBORATED, chain.verdict)
        assertFalse(chain.isTrusted)
    }

    @Test
    fun `a pair is corroborated when its implied scale matches the plan's`() {
        val labels = chainAt(listOf(491, 921), 0.4, across = 50)
        val chain = DimensionChains.assemble(labels, planPixelsPerMeter = 40.0).single()
        assertEquals(ChainVerdict.AGREES_WITH_PLAN_SCALE, chain.verdict)
        assertTrue(chain.isTrusted)
        assertEquals(1412, chain.totalCentimetres)
    }

    @Test
    fun `a reading whose implied scale contradicts the plan is rejected`() {
        // Correctly spaced for 0.4 px/cm, but the plan is calibrated at a third of that.
        val labels = chainAt(listOf(610, 100), 0.4, across = 50)
        val chain = DimensionChains.assemble(labels, planPixelsPerMeter = 13.0).single()
        assertEquals(ChainVerdict.INCONSISTENT, chain.verdict)
        assertFalse(chain.isTrusted)
    }

    @Test
    fun `a lone total is corroborated by the span it annotates`() {
        val chain = DimensionChains.assemble(
            listOf(label("1205", 400, 20)),
            tracedSpans = mapOf(TextOrientation.HORIZONTAL to 11.96),
        ).single()
        assertEquals(ChainVerdict.AGREES_WITH_TRACED_SPAN, chain.verdict)
        assertTrue(chain.isTrusted)
    }

    @Test
    fun `a lone number printed over the plan is not an overall dimension`() {
        // Same reading, same span agreement, but sitting inside the traced outline: a room area
        // or a drawing code that happens to land near the building's size. One coincidence is
        // not evidence, and the position says this number does not dimension anything.
        val chain = DimensionChains.assemble(
            listOf(label("1205", 400, 20)),
            tracedSpans = mapOf(TextOrientation.HORIZONTAL to 11.96),
            footprintBoundsPx = PixelBox(100, 0, 700, 500),
        ).single()
        assertEquals(ChainVerdict.UNCORROBORATED, chain.verdict)
        assertFalse(chain.isTrusted)
    }

    @Test
    fun `a lone number clear of the outline still annotates the span`() {
        val chain = DimensionChains.assemble(
            listOf(label("1205", 400, 20)),
            tracedSpans = mapOf(TextOrientation.HORIZONTAL to 11.96),
            footprintBoundsPx = PixelBox(100, 60, 700, 500),
        ).single()
        assertEquals(ChainVerdict.AGREES_WITH_TRACED_SPAN, chain.verdict)
        assertTrue(chain.isTrusted)
    }

    @Test
    fun `a chain of several labels is not asked where it sits`() {
        // The arithmetic already carries the redundancy; requiring position too would only
        // discard good chains printed inside a courtyard or between two wings.
        val chain = DimensionChains.assemble(
            // Centres 270 px apart: half of 4.91 m plus half of 9.21 m at the plan's 38.28 px/m.
            listOf(label("491", 200, 20), label("921", 470, 20)),
            planPixelsPerMeter = 38.28,
            footprintBoundsPx = PixelBox(0, 0, 900, 500),
        ).single()
        assertEquals(ChainVerdict.AGREES_WITH_PLAN_SCALE, chain.verdict)
        assertTrue(chain.isTrusted)
    }

    @Test
    fun `a lone number that matches nothing stays untrusted`() {
        val chain = DimensionChains.assemble(
            listOf(label("90", 400, 20)),
            tracedSpans = mapOf(TextOrientation.HORIZONTAL to 11.96),
        ).single()
        assertEquals(ChainVerdict.UNCORROBORATED, chain.verdict)
        assertFalse(chain.isTrusted)
    }

    @Test
    fun `labels on different dimension lines are different chains`() {
        val inner = chainAt(listOf(400, 300, 500), 0.4, across = 50)
        val outer = listOf(label("1200", 340, 90))
        val chains = DimensionChains.assemble(inner + outer)
        assertEquals(2, chains.size)
        assertTrue(chains.any { it.parts.size == 3 })
        assertTrue(chains.any { it.parts.size == 1 })
    }

    @Test
    fun `horizontal and vertical chains never mix`() {
        val horizontal = chainAt(listOf(400, 300, 500), 0.4, across = 50)
        val vertical = (0 until 3).map { label(listOf("400", "300", "500")[it], 100 + it * 140, 50, TextOrientation.VERTICAL) }
        val chains = DimensionChains.assemble(horizontal + vertical)
        assertEquals(2, chains.size)
        assertEquals(setOf(TextOrientation.HORIZONTAL, TextOrientation.VERTICAL), chains.map { it.orientation }.toSet())
    }

    @Test
    fun `only read observations become dimensions`() {
        val unread = TextObservation("plan", PixelBox(0, 0, 20, 12), null, 0.0, "m", TextLegibility.AMBIGUOUS, 3, 12, TextOrientation.HORIZONTAL)
        assertTrue(DimensionChains.assemble(listOf(unread)).isEmpty())
    }

    @Test
    fun `values outside the plausible range are not dimensions`() {
        // A room number and a five-digit run are not lengths in centimetres.
        assertTrue(DimensionChains.assemble(listOf(label("7", 100, 20))).isEmpty())
        assertTrue(DimensionChains.assemble(listOf(label("12345", 100, 20))).isEmpty())
    }

    @Test
    fun `assembly is deterministic`() {
        val labels = chainAt(listOf(400, 300, 500), 0.4, across = 50)
        val first = DimensionChains.assemble(labels, 40.0)
        val second = DimensionChains.assemble(labels.reversed(), 40.0)
        assertEquals(first.map { it.parts.map { p -> p.centimetres } }, second.map { it.parts.map { p -> p.centimetres } })
        assertEquals(first.map { it.verdict }, second.map { it.verdict })
    }
}
