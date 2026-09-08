package com.buildplan.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R

/**
 * The timeline as it sits under the house on the home screen: a track with
 * its stage markers and one line saying where the build is.
 *
 * With no stages planned yet the track is drawn empty — hollow markers on a
 * dashed line — so the strip keeps the shape the timeline will have without
 * inventing a stage. It is not data: no marker stands for anything until the
 * timeline module puts a stage on it.
 */
@Composable
fun TimelineStrip(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_timeline_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.dashboard_timeline_open))
                }
            }
            EmptyTrack(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(20.dp),
            )
            Text(
                text = stringResource(R.string.dashboard_timeline_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A dashed line with hollow markers: the shape of a timeline with nothing on it. */
@Composable
private fun EmptyTrack(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val stroke = 1.5.dp.toPx()
        val radius = 5.dp.toPx()
        val y = size.height / 2f
        drawLine(
            color = lineColor,
            start = Offset(radius, y),
            end = Offset(size.width - radius, y),
            strokeWidth = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )
        repeat(EMPTY_MARKERS) { index ->
            val x = radius + (size.width - 2 * radius) * index / (EMPTY_MARKERS - 1)
            drawCircle(color = markerColor, radius = radius, center = Offset(x, y), style = Stroke(stroke))
        }
    }
}

/** How many hollow markers the empty track shows. Shape only, never stages. */
private const val EMPTY_MARKERS = 5
