package com.buildplan.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Static line drawing standing in for the future building model.
 * Purely illustrative: it is not a renderer and holds no model data.
 */
@Composable
fun BuildingWireframe(modifier: Modifier = Modifier) {
    val strokeColor = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .width(120.dp)
            .height(96.dp),
    ) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1.5.dp.toPx()

        fun point(x: Float, y: Float) = Offset(w * x, h * y)

        val apex = point(0.5f, 0.08f)
        val right = point(0.88f, 0.33f)
        val left = point(0.12f, 0.33f)
        val front = point(0.5f, 0.58f)
        val leftBottom = point(0.12f, 0.67f)
        val rightBottom = point(0.88f, 0.67f)
        val frontBottom = point(0.5f, 0.92f)

        val segments = listOf(
            apex to right,
            right to front,
            front to left,
            left to apex,
            left to leftBottom,
            right to rightBottom,
            front to frontBottom,
            leftBottom to frontBottom,
            frontBottom to rightBottom,
        )

        segments.forEach { (start, end) ->
            drawLine(
                color = strokeColor,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
