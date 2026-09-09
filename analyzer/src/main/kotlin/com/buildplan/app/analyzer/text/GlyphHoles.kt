package com.buildplan.app.analyzer.text

import kotlin.math.max

/**
 * One enclosed background region of a glyph — a counter — described by where
 * it sits and how much of the glyph it takes up.
 *
 * Both numbers survive eleven-pixel type and a font's slant, which almost no
 * single stroke does, and between them they separate the digits a stroke score
 * cannot: `4` and `9` both carry one counter high in the body, and only its
 * *size* tells the 4's narrow triangle from the 9's round bowl.
 */
data class GlyphHole(
    /** Centre height, 0 at the cap height and 1 at the baseline. */
    val centreY: Double,
    /** Share of the glyph's bounding box the counter occupies. */
    val areaFraction: Double,
)

/**
 * Finds the counters of a glyph, given only a way to ask whether a cell is ink
 * and how big the glyph is.
 *
 * Shared by the recogniser, which asks it about a raster, and by the templates,
 * which ask it about themselves — so a template's expected counters are
 * *derived from its own drawing* rather than hand-copied beside it, and cannot
 * drift out of step with the shape they describe.
 */
object GlyphHoles {

    fun find(width: Int, height: Int, isInk: (Int, Int) -> Boolean): List<GlyphHole> {
        if (width <= 0 || height <= 0) return emptyList()
        val seen = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val holes = mutableListOf<GlyphHole>()
        for (start in 0 until width * height) {
            if (seen[start] || isInk(start % width, start / width)) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var touchesBorder = false
            var sumY = 0.0
            var count = 0
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % width
                val y = idx / width
                sumY += y
                count++
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) touchesBorder = true
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val n = ny * width + nx
                    if (seen[n] || isInk(nx, ny)) continue
                    seen[n] = true
                    stack[sp++] = n
                }
            }
            if (!touchesBorder && count > 0) {
                holes += GlyphHole(
                    centreY = (sumY / count) / max(1, height - 1),
                    areaFraction = count.toDouble() / (width * height),
                )
            }
        }
        return holes.sortedBy { it.centreY }
    }

    private val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
