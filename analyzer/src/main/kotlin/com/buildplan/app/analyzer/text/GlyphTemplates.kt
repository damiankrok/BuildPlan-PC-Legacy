package com.buildplan.app.analyzer.text

/**
 * The digit shapes, drawn on the recogniser's own 8 x 12 cell grid.
 *
 * These describe *digits*, not a publisher: a 3 has two right-hand bowls and
 * an open left side in every technical font ever cut. Nothing here is tuned to
 * a project, and no expected value from either benchmark informs a stroke —
 * what checks a reading is arithmetic and geometry downstream, never an
 * answer key.
 *
 * Two things about the grid are worth knowing before editing a template:
 *
 * - Glyphs are normalised by their **bounding box**, so a narrow digit is
 *   stretched to the full eight columns. That is why `1` is drawn as a broad
 *   band rather than a hairline: after normalisation that is what a 1 is. Its
 *   [narrow] flag carries the width information the stretch throws away.
 * - [holes] is the count of enclosed background regions, which survives the
 *   resolution far better than any single stroke and splits the alphabet into
 *   groups a stroke-level score cannot confuse: `8` alone has two, `0 4 6 9`
 *   have one, the rest have none.
 */
object GlyphTemplates {

    data class Template(val cells: BooleanArray, val narrow: Boolean) {
        /**
         * The counters of this very drawing, measured rather than declared.
         *
         * Derived from [cells] by the same finder the recogniser runs over a
         * raster, so a template cannot claim a counter it does not draw, and
         * editing a shape updates what it expects for free.
         */
        val holes: List<GlyphHole> = GlyphHoles.find(TemplateDigitRecogniser.COLS, TemplateDigitRecogniser.ROWS) { x, y ->
            cells[y * TemplateDigitRecogniser.COLS + x]
        }

        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    private fun of(vararg rows: String, narrow: Boolean = false): Template {
        require(rows.size == TemplateDigitRecogniser.ROWS) { "template needs ${TemplateDigitRecogniser.ROWS} rows, got ${rows.size}" }
        rows.forEach { require(it.length == TemplateDigitRecogniser.COLS) { "template row must be ${TemplateDigitRecogniser.COLS} wide: '$it'" } }
        val cells = BooleanArray(TemplateDigitRecogniser.COLS * TemplateDigitRecogniser.ROWS)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> cells[y * TemplateDigitRecogniser.COLS + x] = c == '#' } }
        return Template(cells, narrow)
    }

    val digits: Map<Char, Template> = linkedMapOf(
        '0' to of(
            "..####..",
            ".##..##.",
            "##....##",
            "##....##",
            "##....##",
            "##....##",
            "##....##",
            "##....##",
            "##....##",
            "##....##",
            ".##..##.",
            "..####..",
        ),
        '1' to of(
            "....####",
            "...#####",
            ".#######",
            ".#######",
            "....####",
            "....####",
            "....####",
            "....####",
            "....####",
            "....####",
            "....####",
            "....####",
            narrow = true,
        ),
        // The bowl is small and the base is a single stroke. Drawn wider and heavier — as a
        // first guess had it — a 2 scores like a 7, because all a 7 needs is a top and a
        // long diagonal, and the 2's own bowl and base were being swamped.
        '2' to of(
            "...##...",
            "..####..",
            "..#..##.",
            ".##...##",
            "......##",
            ".....##.",
            "....##..",
            "...##...",
            "..##....",
            ".##.....",
            "##......",
            "#######.",
        ),
        '3' to of(
            ".#####..",
            "##...##.",
            ".....##.",
            ".....##.",
            "..####..",
            "..####..",
            ".....##.",
            ".....##.",
            ".....##.",
            "##...##.",
            "##...##.",
            ".#####..",
        ),
        '4' to of(
            ".....##.",
            "....###.",
            "...####.",
            "..##.##.",
            ".##..##.",
            "##...##.",
            "########",
            "########",
            ".....##.",
            ".....##.",
            ".....##.",
            ".....##.",
        ),
        '5' to of(
            "..#####.",
            ".##.....",
            ".##.....",
            ".##.....",
            ".#####..",
            "##...##.",
            "......##",
            "......##",
            "......##",
            "##...##.",
            "##..##..",
            "#####...",
        ),
        '6' to of(
            "..####..",
            ".##..##.",
            "##......",
            "##......",
            "######..",
            "#######.",
            "##....##",
            "##....##",
            "##....##",
            "##....##",
            ".##..##.",
            "..####..",
        ),
        '7' to of(
            "########",
            "########",
            ".....##.",
            ".....##.",
            "....##..",
            "....##..",
            "...##...",
            "...##...",
            "..##....",
            "..##....",
            ".##.....",
            ".##.....",
        ),
        '8' to of(
            "..####..",
            ".##..##.",
            "##....##",
            ".##..##.",
            "..####..",
            "..####..",
            ".##..##.",
            "##....##",
            "##....##",
            "##....##",
            ".##..##.",
            "..####..",
        ),
        '9' to of(
            "..####..",
            ".##..##.",
            "##....##",
            "##....##",
            "##....##",
            ".#######",
            "..#####.",
            "......##",
            "......##",
            "##...##.",
            "##...##.",
            ".#####..",
        ),
    )

    /** A glyph box narrower than this fraction of its height is a `1`-shaped glyph. */
    const val NARROW_ASPECT = 0.42
}
