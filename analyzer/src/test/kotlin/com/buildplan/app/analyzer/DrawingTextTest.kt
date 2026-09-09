package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.text.DimensionLabelParser
import com.buildplan.app.analyzer.text.DrawingTextExtractor
import com.buildplan.app.analyzer.text.GlyphRunLocator
import com.buildplan.app.analyzer.text.ParsedLabel
import com.buildplan.app.analyzer.text.TextLegibility
import com.buildplan.app.analyzer.text.TextObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023B-TEXT — the drawing-text seam, and the gate that stops it inventing
 * numbers.
 *
 * The published benchmark drawings print their opening sizes and floor levels
 * at four to six pixels a glyph. The contract below is what makes that a
 * reported fact rather than a silent guess: runs are located, measured, and
 * refused, and nothing without a confident reading can become a source fact.
 */
class DrawingTextTest {

    /** A white image with black rectangles: crude glyphs of a stated size. */
    private fun imageWith(width: Int, height: Int, glyphs: List<PixelBox>): RasterImage {
        val argb = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        glyphs.forEach { g ->
            for (y in g.minY..g.maxY) for (x in g.minX..g.maxX) {
                // A hollow box, so the component is not a solid blob (which the locator drops).
                if (x == g.minX || x == g.maxX || y == g.minY || y == g.maxY) argb[y * width + x] = 0xFF000000.toInt()
            }
        }
        return RasterImage(width, height, argb)
    }

    private fun glyphRun(x0: Int, y0: Int, glyphWidth: Int, glyphHeight: Int, count: Int, gap: Int = 2): List<PixelBox> =
        (0 until count).map { i ->
            val x = x0 + i * (glyphWidth + gap)
            PixelBox(x, y0, x + glyphWidth - 1, y0 + glyphHeight - 1)
        }

    @Test
    fun `a run of glyphs is located and measured`() {
        val image = imageWith(200, 60, glyphRun(20, 20, 8, 14, 4))
        val runs = GlyphRunLocator().extract("test", image)
        assertEquals(1, runs.size)
        assertEquals(4, runs.single().glyphCount)
        assertEquals(14, runs.single().glyphHeightPx)
    }

    @Test
    fun `glyphs below the legibility floor are refused, not read`() {
        // Six pixels tall: the height the benchmark plans print their opening labels at.
        val image = imageWith(200, 60, glyphRun(20, 20, 4, 6, 5))
        val run = GlyphRunLocator().extract("test", image).single()
        assertEquals(TextLegibility.TOO_SMALL_TO_READ, run.legibility)
        assertNull(run.text)
        assertEquals(0.0, run.confidence, 1e-9)
        assertTrue(run.method.contains("${DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX} px"))
    }

    @Test
    fun `glyphs above the floor are reported as unread rather than guessed`() {
        val image = imageWith(200, 60, glyphRun(20, 20, 8, 14, 4))
        val run = GlyphRunLocator().extract("test", image).single()
        assertEquals(TextLegibility.NO_RECOGNISER, run.legibility)
        assertNull(run.text)
    }

    @Test
    fun `glyphs on different baselines are different runs`() {
        val image = imageWith(200, 120, glyphRun(20, 20, 8, 14, 3) + glyphRun(20, 70, 8, 14, 3))
        assertEquals(2, GlyphRunLocator().extract("test", image).size)
    }

    @Test
    fun `wide flat strokes are not glyphs`() {
        // A dimension chain's leader line: many times wider than tall.
        val image = imageWith(200, 60, listOf(PixelBox(20, 30, 180, 33)))
        assertTrue(GlyphRunLocator().extract("test", image).isEmpty())
    }

    @Test
    fun `an observation may not carry text unless it was read`() {
        val boom = runCatching {
            TextObservation("a", PixelBox(0, 0, 5, 10), "180/210", 0.99, "m", TextLegibility.TOO_SMALL_TO_READ, 6, 6)
        }
        assertTrue("a non-READ observation must not be allowed to carry text", boom.isFailure)
    }

    // ---- the parser gate

    private fun read(text: String, confidence: Double) =
        TextObservation("a", PixelBox(0, 0, 20, 12), text, confidence, "test", TextLegibility.READ, text.length, 12)

    @Test
    fun `a confident opening label parses to width and height`() {
        val parsed = DimensionLabelParser.parse(read("180/210", 0.97)) as ParsedLabel.OpeningSize
        assertEquals(1.80, parsed.widthM, 1e-9)
        assertEquals(2.10, parsed.heightM, 1e-9)
    }

    @Test
    fun `a confident level label parses to an elevation`() {
        assertEquals(7.95, (DimensionLabelParser.parse(read("+7,95", 0.95)) as ParsedLabel.Level).elevationM, 1e-9)
        assertEquals(-0.32, (DimensionLabelParser.parse(read("-0,32", 0.95)) as ParsedLabel.Level).elevationM, 1e-9)
    }

    @Test
    fun `an uncertain reading never becomes a fact`() {
        // The same characters, read at odds no dimension label can afford.
        assertNotNull(DimensionLabelParser.parse(read("180/210", DimensionLabelParser.MIN_CONFIDENCE)))
        assertNull(DimensionLabelParser.parse(read("180/210", DimensionLabelParser.MIN_CONFIDENCE - 0.01)))
        assertNull(DimensionLabelParser.parse(read("180/210", 0.5)))
    }

    @Test
    fun `a located but unread run yields nothing`() {
        val located = TextObservation("a", PixelBox(0, 0, 20, 6), null, 0.0, "m", TextLegibility.TOO_SMALL_TO_READ, 3, 6)
        assertNull(DimensionLabelParser.parse(located))
    }

    @Test
    fun `a physically impossible reading is refused`() {
        // A misread that turns a leader tick into a digit must not become a 90 m door.
        assertNull(DimensionLabelParser.parse(read("9000/210", 0.99)))
        assertNull(DimensionLabelParser.parse(read("18/21", 0.99)))
        assertNull(DimensionLabelParser.parse(read("+99,95", 0.99)))
    }

    @Test
    fun `trailing rubbish is not silently dropped`() {
        assertNull(DimensionLabelParser.parse(read("180/210x", 0.99)))
        assertNull(DimensionLabelParser.parse(read("1 180/210", 0.99)))
    }

    @Test
    fun `a parsed value carries the reading as its provenance`() {
        val observation = read("180/210", 0.97)
        val m = DimensionLabelParser.measured(2.10, observation, "opening height")
        assertEquals(2.10, m.value!!, 1e-9)
        assertTrue(m.provenance.method.contains("180/210"))
        assertTrue(m.provenance.method.contains("0.97"))
    }
}
