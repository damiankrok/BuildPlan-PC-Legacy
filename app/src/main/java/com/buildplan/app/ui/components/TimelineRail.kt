package com.buildplan.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.workspace.LocalMotionPolicy

/**
 * The timeline anchoring the foot of the workspace.
 *
 * Collapsed, it is one line of glass along the bottom edge: the name and the
 * state of the build, the track with its cursor, and a chevron. The track is
 * the product's main future surface — stages will stand on it, costs will
 * hang off it — and it is on screen at all times so that the house always
 * has a "when" under it. Expanded, the rail rises from the bottom edge like
 * a sheet and the detail is revealed beneath the header: what is on it
 * (nothing yet, said once), the way to plan, and the money in one line. The
 * chevron and the detail run on one transition, so one state has one clock.
 * Nothing here is a card: the glass runs to both screen edges and the house
 * is visible behind it.
 *
 * With no stages the track is an empty dashed line with the cursor at its
 * start. It draws no markers, because a marker would read as a stage and
 * there is none; the words beside it say so in the same breath.
 */
@Composable
fun TimelineRail(
    expanded: Boolean,
    onToggle: () -> Unit,
    onExpandChange: (Boolean) -> Unit,
    onPlan: () -> Unit,
    onAddCost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionPolicy.current
    val density = LocalDensity.current
    val transition = updateTransition(targetState = expanded, label = "timeline")
    val chevronTurn by transition.animateFloat(
        transitionSpec = { if (targetState) motion.enter() else motion.exit() },
        label = "timelineChevron",
    ) { open -> if (open) 180f else 0f }
    val toggleDescription = stringResource(
        if (expanded) R.string.timeline_collapse else R.string.timeline_expand,
    )

    GlassSurface(
        modifier = modifier
            .pointerInput(density) {
                // A swipe on the rail does what the chevron does: up opens,
                // down closes. Judged on the whole drag, so a hesitant finger
                // that comes back to where it started changes nothing.
                val threshold = with(density) { TimelineRailDefaults.SwipeThreshold.toPx() }
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        travelled += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            travelled < -threshold -> onExpandChange(true)
                            travelled > threshold -> onExpandChange(false)
                        }
                    },
                )
            },
        shape = GlassDefaults.PanelShape,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TimelineRailDefaults.CollapsedHeight)
                    .semantics { contentDescription = toggleDescription }
                    .clickable(onClick = onToggle, role = Role.Button)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.timeline_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.timeline_no_stages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                EmptyTrack(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .height(24.dp),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(chevronTurn),
                )
            }

            transition.AnimatedVisibility(
                visible = { open -> open },
                enter = motion.unfoldEnter(),
                exit = motion.unfoldExit(),
            ) {
                TimelineDetail(onPlan = onPlan, onAddCost = onAddCost)
            }
        }
    }
}

/**
 * What the timeline has to say when it is opened with nothing on it: the
 * fact and the action that changes it, twice — once for stages, once for
 * money. Two sentences, no figures: a row of dashes is not information.
 */
@Composable
private fun TimelineDetail(onPlan: () -> Unit, onAddCost: () -> Unit) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 10.dp)) {
        DetailLine(
            text = stringResource(R.string.timeline_empty),
            action = stringResource(R.string.timeline_plan),
            onAction = onPlan,
        )
        PanelRule(Modifier.padding(horizontal = 0.dp))
        DetailLine(
            text = stringResource(R.string.ledger_empty),
            action = stringResource(R.string.ledger_add_cost),
            onAction = onAddCost,
        )
    }
}

@Composable
private fun DetailLine(text: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        TextButton(onClick = onAction) {
            Text(action)
        }
    }
}

/**
 * The track with nothing on it: a dashed line, and the cursor — where the
 * build is now — standing at the start because no stage has been reached.
 * Markers will appear on it when the timeline module puts a stage there.
 */
@Composable
private fun EmptyTrack(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val cursorColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val stroke = 1.5.dp.toPx()
        val cursorHalf = 7.dp.toPx()
        val y = size.height / 2f
        val left = stroke
        val right = size.width - stroke
        drawLine(
            color = lineColor,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())),
        )
        drawLine(
            color = lineColor,
            start = Offset(right, y - cursorHalf * 0.5f),
            end = Offset(right, y + cursorHalf * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = cursorColor,
            start = Offset(left, y - cursorHalf),
            end = Offset(left, y + cursorHalf),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

object TimelineRailDefaults {
    val CollapsedHeight = 56.dp
    val SwipeThreshold = 24.dp
}
