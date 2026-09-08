package com.buildplan.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The workspace's own tool glyphs: thin line drawings on a 24 dp grid, in
 * the same hand as the feature edges the renderer draws on the house.
 *
 * Drawn here rather than pulled from the extended Material icon set because
 * the set is several megabytes for four symbols, and because a layers glyph
 * built from the same axonometric the model is seen in belongs to this
 * product in a way a generic one does not. Stroke 1.6, round joins, single
 * weight — one family.
 */
object WorkspaceGlyphs {

    /** Three storeys of a house seen from the model's own axonometric angle. */
    val Layers: ImageVector by lazy {
        glyph("Layers") {
            rhombus(centerY = 7.5f)
            moveTo(4f, 12f); lineTo(12f, 16f); lineTo(20f, 12f)
            moveTo(4f, 16f); lineTo(12f, 20f); lineTo(20f, 16f)
        }
    }

    /** A viewfinder: the four corners of a frame. */
    val Views: ImageVector by lazy {
        glyph("Views") {
            moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
            moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
            moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
            moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
            moveTo(9f, 12f); arc(12f, 12f, 3f)
        }
    }

    /** A disc lit from one side: the presentation, not the model. */
    val Style: ImageVector by lazy {
        glyph("Style") {
            moveTo(4.5f, 12f); arc(12f, 12f, 7.5f)
            moveTo(12f, 4.5f); lineTo(12f, 19.5f)
            moveTo(12f, 8f); lineTo(16.5f, 8f)
            moveTo(12f, 12f); lineTo(19.5f, 12f)
            moveTo(12f, 16f); lineTo(16.5f, 16f)
        }
    }

    /** A camera returning to its mark: a ring with a point at its centre. */
    val Recenter: ImageVector by lazy {
        glyph("Recenter") {
            moveTo(5f, 12f); arc(12f, 12f, 7f)
            moveTo(12f, 2.5f); lineTo(12f, 6f)
            moveTo(12f, 18f); lineTo(12f, 21.5f)
            moveTo(2.5f, 12f); lineTo(6f, 12f)
            moveTo(18f, 12f); lineTo(21.5f, 12f)
            moveTo(10.6f, 12f); arc(12f, 12f, 1.4f)
        }
    }

    /** A page with two lines: where the numbers came from. */
    val Source: ImageVector by lazy {
        glyph("Source") {
            moveTo(7f, 3.5f); lineTo(14.5f, 3.5f); lineTo(18f, 7f); lineTo(18f, 20.5f)
            lineTo(7f, 20.5f); close()
            moveTo(14.5f, 3.5f); lineTo(14.5f, 7f); lineTo(18f, 7f)
            moveTo(9.5f, 12f); lineTo(15.5f, 12f)
            moveTo(9.5f, 15.5f); lineTo(15.5f, 15.5f)
        }
    }

    private fun glyph(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "workspace.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero,
                pathBuilder = draw,
            )
        }.build()

    /** A closed rhombus 16 wide and 8 tall centred on x = 12. */
    private fun PathBuilder.rhombus(centerY: Float) {
        moveTo(4f, centerY)
        lineTo(12f, centerY - 4f)
        lineTo(20f, centerY)
        lineTo(12f, centerY + 4f)
        close()
    }

    /** A full circle drawn as two arcs, starting from the current point at (cx - r, cy). */
    private fun PathBuilder.arc(cx: Float, cy: Float, r: Float) {
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
    }
}
