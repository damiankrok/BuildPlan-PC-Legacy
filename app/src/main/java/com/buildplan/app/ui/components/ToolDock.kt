package com.buildplan.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.buildplan.app.R
import com.buildplan.app.ui.theme.GlassTint
import com.buildplan.app.ui.workspace.LocalMotionPolicy
import com.buildplan.app.ui.workspace.MotionPolicy

/**
 * One tool on the edge rail.
 *
 * A [momentary] tool acts on the tap and opens nothing — recentring the
 * camera is done the moment it is asked for; the others open a panel that
 * stays until it is closed or another tool takes its place.
 */
data class RailTool(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val momentary: Boolean = false,
)

/**
 * The edge tool rail and the panel that grows out of it.
 *
 * The rail is a single pane of glass down the screen's right edge with one
 * glyph per tool. Opening a tool does not navigate anywhere and does not put a
 * sheet over the house: **one** pane of glass unfolds beside the rail, pivoting
 * on the button that was pressed — so the eye reads it as that button
 * widening — and sits there with the house still visible to its left.
 * Switching tools keeps the same pane: its height eases to the new list while
 * the old words fade into the new. Two panes crossing over would be two
 * objects; a pane whose contents change is one.
 */
@Composable
fun ToolDock(
    tools: List<RailTool>,
    openKey: String?,
    onToolClick: (RailTool) -> Unit,
    modifier: Modifier = Modifier,
    panelMaxHeight: Dp = Dp.Unspecified,
    panel: @Composable ColumnScope.(RailTool) -> Unit,
) {
    val motion = LocalMotionPolicy.current
    val density = LocalDensity.current
    val openTool = tools.firstOrNull { it.key == openKey && !it.momentary }

    // The pane keeps its words while it leaves: the last tool shown is held
    // outside the snapshot and written after composition.
    val lastOpen = remember { LastTool() }
    SideEffect { if (openTool != null) lastOpen.tool = openTool }
    val shownTool = openTool ?: lastOpen.tool

    // The pane pivots on the rail button that opened it. The rail and the pane
    // share a top edge, so the button's centre in rail coordinates is its
    // centre in pane coordinates. The pivot is resolved inside the draw layer
    // from the pane's size *this* frame — not from a measured size held in
    // state, which arrives a frame late and would make every opening jump.
    val buttonCentrePx = with(density) {
        val index = tools.indexOf(shownTool).coerceAtLeast(0)
        (ToolDockDefaults.RailPadding +
            (ToolDockDefaults.ButtonSize + ToolDockDefaults.RailGap) * index +
            ToolDockDefaults.ButtonSize / 2).toPx()
    }

    // Presence and scale on one transition: the pane fades through
    // AnimatedVisibility and scales through its own layer, so that the
    // transform origin can be a function of the frame's size.
    val presence = updateTransition(targetState = openTool != null, label = "toolPane")
    val paneScale by presence.animateFloat(
        transitionSpec = { if (targetState) motion.enter() else motion.exit() },
        label = "toolPaneScale",
    ) { open -> if (open) 1f else MotionPolicy.PANEL_REST_SCALE }

    Row(
        // One traversal group, so a screen reader reaches the panel right
        // after the rail button that opened it rather than after the timeline.
        modifier = modifier.semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(ToolDockDefaults.Gap),
        verticalAlignment = Alignment.Top,
    ) {
        presence.AnimatedVisibility(
            visible = { open -> open },
            enter = fadeIn(motion.enter()),
            exit = fadeOut(motion.exit()),
        ) {
            if (shownTool != null) {
                GlassSurface(
                    modifier = Modifier
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(
                                pivotFractionX = 1f,
                                pivotFractionY = if (size.height > 0f) (buttonCentrePx / size.height).coerceIn(0f, 1f) else 0f,
                            )
                            scaleX = paneScale
                            scaleY = paneScale
                        }
                        .width(ToolDockDefaults.PanelWidth)
                        .then(if (panelMaxHeight.isSpecified) Modifier.heightIn(max = panelMaxHeight) else Modifier),
                ) {
                    // Inside the one pane, the content of the tool crossfades and
                    // the pane's height follows it — clipped, so the new list is
                    // revealed by the pane rather than drawn outside it.
                    AnimatedContent(
                        targetState = shownTool,
                        contentAlignment = Alignment.TopEnd,
                        transitionSpec = {
                            (fadeIn(motion.enter()) togetherWith fadeOut(motion.exit()))
                                .using(SizeTransform(clip = true) { _, _ -> motion.enter() })
                        },
                        label = "toolPanel",
                    ) { tool ->
                        Column {
                            PanelHeader(
                                title = tool.label,
                                onClose = { onToolClick(tool) },
                            )
                            val scroll = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fadeBelowFold(scroll)
                                    .verticalScroll(scroll)
                                    .selectableGroup()
                                    .padding(bottom = 8.dp),
                            ) {
                                panel(tool)
                            }
                        }
                    }
                }
            }
        }

        ToolRail(tools = tools, openKey = openTool?.key, onToolClick = onToolClick)
    }
}

/** A plain holder, deliberately not snapshot state: writing it must not recompose anything. */
private class LastTool {
    var tool: RailTool? = null
}

/**
 * Darkens the last rows of a list that has more below the fold, so that a
 * list which happens to end flush with the panel's edge never looks
 * complete when it is not.
 */
private fun Modifier.fadeBelowFold(scroll: ScrollState): Modifier = drawWithContent {
    drawContent()
    if (scroll.canScrollForward) {
        val fade = ToolDockDefaults.FoldFadeHeight.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, GlassTint.copy(alpha = 0.9f)),
                startY = size.height - fade,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade),
        )
    }
}

/** The rail alone: a vertical glass pill of glyph buttons. */
@Composable
fun ToolRail(
    tools: List<RailTool>,
    openKey: String?,
    onToolClick: (RailTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier, shape = GlassDefaults.RailShape) {
        Column(
            modifier = Modifier.padding(ToolDockDefaults.RailPadding),
            verticalArrangement = Arrangement.spacedBy(ToolDockDefaults.RailGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tools.forEach { tool ->
                RailButton(
                    tool = tool,
                    selected = tool.key == openKey,
                    onClick = { onToolClick(tool) },
                )
            }
        }
    }
}

/**
 * One glyph on the rail. Icon-only on screen, so it carries its name three
 * ways: as its accessibility label, as its open/closed state, and as a
 * tooltip on a long press — the platform's answer for an unlabeled action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailButton(tool: RailTool, selected: Boolean, onClick: () -> Unit) {
    val motion = LocalMotionPolicy.current
    val accent = MaterialTheme.colorScheme.primary
    val tint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        animationSpec = motion.settle(),
        label = "railTint",
    )
    val halo by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.settle(),
        label = "railHalo",
    )
    val openState = stringResource(R.string.tool_state_open)
    val closedState = stringResource(R.string.tool_state_closed)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tool.label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier = Modifier
                .size(ToolDockDefaults.ButtonSize)
                .clip(CircleShape)
                .semantics {
                    contentDescription = tool.label
                    if (!tool.momentary) stateDescription = if (selected) openState else closedState
                }
                .selectable(selected = selected, onClick = onClick, role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            if (halo > 0f) {
                // The halo lights up at near its final size rather than
                // inflating from a point: a state coming on, not a bubble.
                Box(
                    modifier = Modifier
                        .size(ToolDockDefaults.HaloSize)
                        .scale(ToolDockDefaults.HaloRestScale + (1f - ToolDockDefaults.HaloRestScale) * halo)
                        .background(accent.copy(alpha = ToolDockDefaults.HaloAlpha * halo), CircleShape),
                )
            }
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(ToolDockDefaults.GlyphSize),
            )
        }
    }
}

/**
 * The first row of every panel: what it is, and the one way to close it.
 * The title sits where the rail button's label would, so the panel names
 * itself without an eyebrow above it.
 */
@Composable
fun PanelHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(ToolDockDefaults.ButtonSize)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.panel_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One choice in a panel: a mark on the left that fills when chosen, the
 * label, and an optional second line. A list of these is one radio group —
 * the panel is a choice of view, layer or style, never a form.
 *
 * Every label is full ink; the chosen one is told by its mark and by taking
 * the accent, not by the others fading. Fourteen dimmed rows out of fifteen
 * was a list of things that looked unavailable.
 */
@Composable
fun PanelOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val motion = LocalMotionPolicy.current
    val accent = MaterialTheme.colorScheme.primary
    val markColor by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motion.settle(),
        label = "optionMark",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        animationSpec = motion.settle(),
        label = "optionLabel",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ToolDockDefaults.OptionHeight)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A filled dot when chosen, a ring when not: the same mark in two states.
        Box(
            modifier = Modifier
                .size(8.dp)
                .then(
                    if (selected) {
                        Modifier.background(markColor, CircleShape)
                    } else {
                        Modifier.border(1.dp, markColor.copy(alpha = 0.8f), CircleShape)
                    },
                ),
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The name of a group of options, set above them in the panel. Sentence
 * case and the same ink as the options' supporting text — a heading for
 * four rows, not an eyebrow.
 */
@Composable
fun PanelGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** A quiet hairline between groups of options. */
@Composable
fun PanelRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

object ToolDockDefaults {
    val ButtonSize = 48.dp
    val GlyphSize = 22.dp
    val HaloSize = 36.dp
    const val HaloAlpha = 0.22f

    /** Where the halo starts: most of its size already, so it lights rather than inflates. */
    const val HaloRestScale = 0.7f
    val RailPadding = 4.dp
    val RailGap = 4.dp
    val Gap = 8.dp
    val PanelWidth = 216.dp
    val OptionHeight = 48.dp
    val FoldFadeHeight = 40.dp

    /**
     * How much of the free height a panel may take before it scrolls. Under
     * two thirds, so that with the tallest list open the house is still the
     * larger thing on the screen.
     */
    const val PanelHeightShare = 0.6f
}
