package com.buildplan.app.render.filament

import com.buildplan.app.geometry.LocalBounds
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The reference grid drawn under the model, the way a modelling application
 * draws one.
 *
 * ## Why it is here and not in the model
 *
 * It is not part of the building. It is not terrain, a site, a plot or a
 * landscape — it has no thickness, no material and no position in the world; it
 * is a ruled plane put under the model so the eye has something to measure the
 * massing against. A house floating on a dark background reads as an object; the
 * same house standing on a ruled plane reads as a building of a certain size,
 * and that difference is presentation, not geometry.
 *
 * So it is built here, from nothing but a [LocalBounds], and it never reaches
 * `geometry/` or the domain. It carries no
 * [com.buildplan.app.domain.model.BuildingElementId] either, which is what makes
 * it unpickable: a tap that lands on it resolves to no element rather than to a
 * phantom one, because the renderer only ever answers a pick from the meshes it
 * was given ids for.
 *
 * ## Two densities
 *
 * Lines every [MINOR_SPACING_METERS] and a stronger line every
 * [MAJOR_EVERY] of them. One density alone is either too coarse to read a
 * dimension off or too busy to see the building through; a metre grid with a
 * five-metre emphasis is what a modelling viewport uses, and it lets the eye
 * count without the grid competing with the model.
 */
internal class PresentationGrid private constructor(
    /** Flat `xyz` triples, shared by both index lists. */
    val positions: FloatArray,
    /** Line pairs for the one-metre lines. */
    val minorIndices: IntArray,
    /** Line pairs for the emphasised lines, including the two through the centre. */
    val majorIndices: IntArray,
    val boundsCenter: FloatArray,
    val boundsHalfExtent: FloatArray,
) {

    val lineCount: Int get() = (minorIndices.size + majorIndices.size) / 2

    companion object {

        /** Spacing of the fine lines, in the building's own metres. */
        const val MINOR_SPACING_METERS: Double = 1.0

        /** How many fine lines fall between two emphasised ones. */
        const val MAJOR_EVERY: Int = 5

        /**
         * How far the grid reaches past the model, as a fraction of the larger
         * plan dimension.
         *
         * Enough that the building sits *in* the plane rather than filling it —
         * a grid cropped to the footprint reads as a floor slab — and not so far
         * that the model becomes a detail in the middle of a ruler.
         */
        const val MARGIN_FRACTION: Double = 0.55

        /**
         * How far below the model's lowest point the plane sits.
         *
         * Not zero. The foundation plate's underside is the model's lowest face,
         * and a grid drawn in exactly that plane is two surfaces at one depth:
         * the lines would break up into a dashed pattern that reshuffles itself
         * whenever the camera moves.
         */
        const val DROP_BELOW_MODEL_METERS: Double = 0.02

        /**
         * The grid under [bounds].
         *
         * Centred on the model's plan centre and snapped to whole multiples of
         * the spacing from the origin, so the lines land on round coordinates
         * rather than on the accident of where the building happens to be. That
         * is what makes it a ruler instead of a texture.
         */
        fun under(bounds: LocalBounds): PresentationGrid {
            val reach = max(bounds.sizeX, bounds.sizeZ) * (0.5 + MARGIN_FRACTION)
            val centerX = bounds.center.x
            val centerZ = bounds.center.z
            val y = (bounds.min.y - DROP_BELOW_MODEL_METERS).toFloat()

            val firstX = ceil((centerX - reach) / MINOR_SPACING_METERS).toInt()
            val lastX = floor((centerX + reach) / MINOR_SPACING_METERS).toInt()
            val firstZ = ceil((centerZ - reach) / MINOR_SPACING_METERS).toInt()
            val lastZ = floor((centerZ + reach) / MINOR_SPACING_METERS).toInt()

            val minX = (firstX * MINOR_SPACING_METERS).toFloat()
            val maxX = (lastX * MINOR_SPACING_METERS).toFloat()
            val minZ = (firstZ * MINOR_SPACING_METERS).toFloat()
            val maxZ = (lastZ * MINOR_SPACING_METERS).toFloat()

            val positions = ArrayList<Float>()
            val minor = ArrayList<Int>()
            val major = ArrayList<Int>()

            fun line(fromX: Float, fromZ: Float, toX: Float, toZ: Float, emphasised: Boolean) {
                val first = positions.size / 3
                positions += fromX; positions += y; positions += fromZ
                positions += toX; positions += y; positions += toZ
                val into = if (emphasised) major else minor
                into += first
                into += first + 1
            }

            (firstX..lastX).forEach { step ->
                val x = (step * MINOR_SPACING_METERS).toFloat()
                line(x, minZ, x, maxZ, step % MAJOR_EVERY == 0)
            }
            (firstZ..lastZ).forEach { step ->
                val z = (step * MINOR_SPACING_METERS).toFloat()
                line(minX, z, maxX, z, step % MAJOR_EVERY == 0)
            }

            return PresentationGrid(
                positions = positions.toFloatArray(),
                minorIndices = minor.toIntArray(),
                majorIndices = major.toIntArray(),
                boundsCenter = floatArrayOf((minX + maxX) / 2f, y, (minZ + maxZ) / 2f),
                boundsHalfExtent = floatArrayOf(
                    nonDegenerateHalfExtent((maxX - minX).toDouble()),
                    nonDegenerateHalfExtent(0.0),
                    nonDegenerateHalfExtent((maxZ - minZ).toDouble()),
                ),
            )
        }
    }
}
