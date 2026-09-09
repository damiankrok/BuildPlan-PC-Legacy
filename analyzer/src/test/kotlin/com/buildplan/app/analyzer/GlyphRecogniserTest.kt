package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.text.GlyphTemplates
import com.buildplan.app.analyzer.text.TemplateDigitRecogniser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023C-GLYPH — digit recognition, and the refusals that keep it honest.
 *
 * Glyphs are rendered from the recogniser's own templates at plan scale, with
 * and without the slant real drawings print. That is a fair test of everything
 * between a bitmap and a character — scaling, deslanting, zoning, scoring and
 * the gates — without asserting that a particular ARCHON sheet says a
 * particular number, which would be an answer key rather than a test.
 */
class GlyphRecogniserTest {

    /**
     * Renders a template into a mask at `scale` pixels per cell, optionally
     * sheared like an oblique font, with an optional stroke dropout.
     *
     * A digit the templates mark narrow is drawn narrow, because that is what
     * a drawing prints and what normalisation then stretches back out. Drawing
     * a `1` eight cells wide would be testing the recogniser against a glyph no
     * sheet contains, and would quietly invert the one feature that tells a 1
     * from a 7 once both have been stretched to the same box.
     */
    private fun render(digit: Char, scale: Int = 1, shear: Double = 0.0, dropRow: Int = -1): Pair<BinaryMask, PixelBox> {
        val template = GlyphTemplates.digits.getValue(digit)
        val cols = if (template.narrow) NARROW_COLS else TemplateDigitRecogniser.COLS
        val h = TemplateDigitRecogniser.ROWS * scale
        val w = cols * scale
        val extra = (shear * h).toInt() + 2
        val mask = BinaryMask(w + extra + 8, h + 8)
        for (y in 0 until h) for (x in 0 until w) {
            val cy = y / scale
            val cx = (x / scale) * TemplateDigitRecogniser.COLS / cols
            if (!template.cells[cy * TemplateDigitRecogniser.COLS + cx]) continue
            if (cy == dropRow) continue
            val sx = 4 + x + (shear * (h - 1 - y)).toInt()
            if (sx < mask.width) mask[sx, 4 + y] = true
        }
        return mask to (mask.boundingBox() ?: PixelBox(0, 0, 1, 1))
    }

    /** Cells wide a narrow digit occupies on the sheet before normalisation stretches it. */
    private val NARROW_COLS = 3

    private val recogniser = TemplateDigitRecogniser()

    @Test
    fun `every digit is read back from its own shape at plan scale`() {
        GlyphTemplates.digits.keys.forEach { digit ->
            val (mask, box) = render(digit, scale = 1)
            val reading = recogniser.recognise(mask, box, -0.0)
            assertNotNull("digit $digit was not read at all; ranking " + recogniser.rank(mask, box, -0.0) + " grid " + recogniser.normalisedRows(mask, box, -0.0), reading)
            assertEquals("digit $digit", digit, reading!!.character)
        }
    }

    @Test
    fun `every digit survives the slant a technical font prints`() {
        // A quarter of a cell of shift per row: what the benchmark sheets actually use.
        GlyphTemplates.digits.keys.forEach { digit ->
            val (mask, box) = render(digit, scale = 1, shear = 0.25)
            val reading = recogniser.recognise(mask, box, -0.25)
            assertNotNull("slanted $digit was not read", reading)
            assertEquals("slanted $digit", digit, reading!!.character)
        }
    }

    @Test
    fun `recognition does not depend on how large the drawing printed the digit`() {
        GlyphTemplates.digits.keys.forEach { digit ->
            listOf(1, 2, 3).forEach { scale ->
                val (mask, box) = render(digit, scale = scale)
                assertEquals("digit $digit at scale $scale", digit, recogniser.recognise(mask, box, 0.0)?.character)
            }
        }
    }

    @Test
    fun `a shape that is not a digit is refused rather than assigned`() {
        // A solid block: high ink everywhere, resembling nothing in particular.
        val mask = BinaryMask(30, 30)
        for (y in 5..20) for (x in 5..14) mask[x, y] = true
        assertNull(recogniser.recognise(mask, PixelBox(5, 5, 14, 20), 0.0))
    }

    @Test
    fun `an ambiguous glyph is refused, not resolved by a hair`() {
        // Half a 3 and half an 8: neither template can win by the required margin.
        val three = GlyphTemplates.digits.getValue('3')
        val eight = GlyphTemplates.digits.getValue('8')
        val mask = BinaryMask(30, 30)
        for (y in 0 until TemplateDigitRecogniser.ROWS) for (x in 0 until TemplateDigitRecogniser.COLS) {
            val i = y * TemplateDigitRecogniser.COLS + x
            val on = if (y < TemplateDigitRecogniser.ROWS / 2) three.cells[i] else eight.cells[i]
            if (on) mask[4 + x, 4 + y] = true
        }
        val box = mask.boundingBox()!!
        val ranked = recogniser.rank(mask, box, 0.0)!!
        val margin = ranked[0].second - ranked[1].second
        // Either it is refused outright, or the margin it wins by is under the gate.
        val reading = recogniser.recognise(mask, box, 0.0)
        assertTrue(
            "a hybrid must not be read confidently (margin $margin, reading $reading)",
            reading == null || margin >= TemplateDigitRecogniser.MIN_MARGIN,
        )
    }

    @Test
    fun `a glyph too small to carry its strokes is refused`() {
        val mask = BinaryMask(20, 20)
        for (y in 2..6) for (x in 2..4) mask[x, y] = true
        assertNull(recogniser.recognise(mask, PixelBox(2, 2, 4, 6), 0.0))
    }

    @Test
    fun `counters are counted and placed`() {
        // 8 has two counters, 0 one through the middle, 1 none.
        val (eight, eightBox) = render('8', scale = 2)
        assertEquals(2, recogniser.holesOf(eight, eightBox))
        val (zero, zeroBox) = render('0', scale = 2)
        assertEquals(1, recogniser.holesOf(zero, zeroBox))
        assertEquals(0.5, recogniser.holeCentresOf(zero, zeroBox).single(), 0.15)
        val (one, oneBox) = render('1', scale = 2)
        assertEquals(0, recogniser.holesOf(one, oneBox))
        // A 9 carries its counter high and a 6 low: the only thing telling them from a 0.
        val (nine, nineBox) = render('9', scale = 2)
        val (six, sixBox) = render('6', scale = 2)
        assertTrue(recogniser.holeCentresOf(nine, nineBox).single() < recogniser.holeCentresOf(six, sixBox).single())
    }

    @Test
    fun `reading is deterministic`() {
        GlyphTemplates.digits.keys.forEach { digit ->
            val (mask, box) = render(digit, scale = 2, shear = 0.2)
            val first = recogniser.recognise(mask, box, -0.2)
            val second = recogniser.recognise(mask, box, -0.2)
            assertEquals(first?.character, second?.character)
            assertEquals(first?.score ?: 0.0, second?.score ?: 0.0, 1e-12)
        }
    }

    @Test
    fun `every template is well formed`() {
        GlyphTemplates.digits.forEach { (digit, t) ->
            assertEquals("$digit cell count", TemplateDigitRecogniser.COLS * TemplateDigitRecogniser.ROWS, t.cells.size)
            assertTrue("$digit counters are in range", t.holes.all { h -> h.centreY in 0.0..1.0 && h.areaFraction > 0.0 })
            assertTrue("$digit has ink", t.cells.any { it })
        }
    }
}
