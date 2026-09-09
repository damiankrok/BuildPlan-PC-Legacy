package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.text.DimensionChains
import com.buildplan.app.analyzer.text.GlyphRunLocator
import com.buildplan.app.analyzer.text.GlyphTemplates
import com.buildplan.app.analyzer.text.TemplateDigitRecogniser
import com.buildplan.app.analyzer.text.TextLegibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023C-ADVERSARIAL — trying to make the reader say something wrong.
 *
 * A false dimension is worse than no dimension: it enters the model as
 * SOURCE_EXACT and there is no later stage that can catch it. So these tests
 * are written to *break* the recogniser rather than to confirm it, and what
 * they assert is mostly a refusal.
 */
class GlyphAdversarialTest {

    private val recogniser = TemplateDigitRecogniser()

    /** Cells wide a narrow digit occupies on the sheet before normalisation stretches it. */
    private val NARROW_COLS = 3

    /** Draws a string of digits into a sheet at plan scale, sheared like the real font. */
    private fun sheet(
        text: String,
        scale: Int = 1,
        shear: Double = 0.25,
        originX: Int = 20,
        originY: Int = 20,
        gap: Int = 2,
        damage: (x: Int, y: Int) -> Boolean = { _, _ -> false },
    ): RasterImage {
        val w = 400
        val h = 200
        val argb = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        var penX = originX
        text.forEach { ch ->
            val t = GlyphTemplates.digits.getValue(ch)
            // Wide digits are drawn at full template width; a digit the templates mark narrow is
            // drawn narrow, because that width is what normalisation later throws away.
            val cols = if (t.narrow) NARROW_COLS else TemplateDigitRecogniser.COLS
            val rows = TemplateDigitRecogniser.ROWS
            for (y in 0 until rows * scale) for (x in 0 until cols * scale) {
                val cx = (x / scale) * TemplateDigitRecogniser.COLS / cols
                val cy = y / scale
                if (!t.cells[cy * TemplateDigitRecogniser.COLS + cx]) continue
                val sx = penX + x + (shear * (rows * scale - 1 - y)).toInt()
                val sy = originY + y
                if (sx in 0 until w && sy in 0 until h && !damage(sx, sy)) argb[sy * w + sx] = 0xFF202020.toInt()
            }
            // The slant leans a glyph's top over its neighbour's cell; advance past it, or the
            // fixture prints digits that touch and the test is measuring its own overlap.
            penX += cols * scale + gap + (shear * rows * scale).toInt()
        }
        return RasterImage(w, h, argb)
    }

    private fun readOne(image: RasterImage): String? =
        GlyphRunLocator(recogniser).extract("t", image)
            .filter { it.legibility == TextLegibility.READ }
            .maxByOrNull { it.glyphCount }
            ?.text

    @Test
    fun `a clean number is read`() {
        assertEquals("1205", readOne(sheet("1205", scale = 2)))
    }

    @Test
    fun `the pairs that look alike are never silently swapped`() {
        // 1/7, 3/8, 5/6 and 0/8 are the confusions this type size invites. Each must come back
        // either right or not at all — never as the other one.
        listOf("17", "71", "38", "83", "56", "65", "08", "80").forEach { pair ->
            val read = readOne(sheet(pair, scale = 2))
            if (read != null) assertEquals("misread $pair", pair, read)
        }
    }

    @Test
    fun `a glyph with a stroke cut out is refused rather than guessed`() {
        // A horizontal band wiped out of the middle: the shape is no longer any digit.
        val damaged = sheet("305", scale = 2) { _, y -> y in 32..36 }
        val read = readOne(damaged)
        if (read != null) assertEquals("a damaged number must not change value", "305", read)
    }

    @Test
    fun `a run clipped at the sheet edge does not become a shorter number`() {
        // Half the first glyph is off the sheet; the run must not read as its remaining digits.
        val clipped = sheet("1205", scale = 2, originX = -6)
        val read = readOne(clipped)
        assertNotEquals("205", read)
    }

    @Test
    fun `a number under a watermark is refused rather than half-read`() {
        // A diagonal wash across the glyphs, as a tinted watermark leaves.
        val washed = sheet("790", scale = 2) { x, y -> (x + y) % 3 == 0 }
        val read = readOne(washed)
        if (read != null) assertEquals("790", read)
    }

    @Test
    fun `unrelated numerals nearby do not join one number`() {
        // Two numbers a long way apart on one baseline stay two runs.
        val image = sheet("12", scale = 2, originX = 20).let { first ->
            val second = sheet("34", scale = 2, originX = 200)
            RasterImage(first.width, first.height, IntArray(first.argb.size) { i -> if (second.argb[i] != 0xFFFFFFFF.toInt()) second.argb[i] else first.argb[i] })
        }
        val runs = GlyphRunLocator(recogniser).extract("t", image).filter { it.legibility == TextLegibility.READ }
        assertTrue("a gap of 180 px must not join two numbers", runs.none { (it.text?.length ?: 0) > 2 })
    }

    @Test
    fun `a lone read number never becomes a trusted dimension`() {
        val runs = GlyphRunLocator(recogniser).extract("t", sheet("1205", scale = 2))
        val chains = DimensionChains.assemble(runs)
        assertTrue("nothing corroborates a single label", chains.none { it.isTrusted })
    }

    @Test
    fun `a total contradicting the plan scale is rejected however cleanly it was read`() {
        val runs = GlyphRunLocator(recogniser).extract("t", sheet("1205", scale = 2))
        // Corroborated only if the number matches a span the plan actually traces.
        val wrong = DimensionChains.assemble(runs, tracedSpans = mapOf(com.buildplan.app.analyzer.text.TextOrientation.HORIZONTAL to 30.0))
        assertTrue(wrong.none { it.isTrusted })
        val right = DimensionChains.assemble(runs, tracedSpans = mapOf(com.buildplan.app.analyzer.text.TextOrientation.HORIZONTAL to 12.0))
        assertTrue(right.any { it.isTrusted })
    }

    @Test
    fun `a decimal comma is not silently dropped`() {
        // "12,05" with the comma printed as the two or three pixels this type size gives it. The
        // comma is far below the glyph floor, so it can never be a glyph — and if the gap were
        // assumed empty the run would read 1205, an ordinary-looking plan dimension a hundred
        // times too large. It must not be read at all.
        val image = sheet("1205", scale = 2)
        val argb = image.argb.copyOf()
        // Between the second and third glyph, on the baseline.
        val boxes = GlyphRunLocator(recogniser).debugRuns(image, com.buildplan.app.analyzer.text.TextOrientation.HORIZONTAL).second
        val run = boxes.first { it.size == 4 }
        val cx = (run[1].maxX + run[2].minX) / 2
        val cy = run[1].maxY
        for (dy in 0..2) for (dx in 0..1) argb[(cy + dy) * image.width + cx + dx] = 0xFF202020.toInt()
        val read = readOne(RasterImage(image.width, image.height, argb))
        assertNotEquals("a decimal number must never read as its digits run together", "1205", read)
    }

    @Test
    fun `a plain number is still read once the gaps are checked`() {
        // The guard above must not cost the ordinary case: nothing sits between these glyphs.
        assertEquals("491", readOne(sheet("491", scale = 2)))
    }

    @Test
    fun `hatching is not read as a number`() {
        // Evenly spaced short strokes: the shape a hatch and a stair both make.
        val w = 200
        val h = 120
        val argb = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (i in 0 until 12) for (y in 20..34) {
            val x = 20 + i * 6
            argb[y * w + x] = 0xFF202020.toInt()
        }
        val runs = GlyphRunLocator(recogniser).extract("t", RasterImage(w, h, argb))
        assertTrue("hatching must not read as digits", runs.none { it.legibility == TextLegibility.READ })
    }

    @Test
    fun `a solid marker is not a digit`() {
        val mask = BinaryMask(40, 40)
        for (y in 5..25) for (x in 5..16) mask[x, y] = true
        assertTrue(recogniser.recognise(mask, PixelBox(5, 5, 16, 25), 0.0) == null)
    }
}
