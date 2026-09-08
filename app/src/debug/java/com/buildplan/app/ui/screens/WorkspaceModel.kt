package com.buildplan.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.reference.visual.MarcowkiRoomTrace
import com.buildplan.app.reference.visual.MarcowkiSourceEvidence
import com.buildplan.app.reference.visual.SourceFidelity
import com.buildplan.app.reference.visual.TraceCertainty
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.FilamentCanvas
import com.buildplan.app.render.filament.ModelScene
import com.buildplan.app.render.filament.ModelViewPreset
import com.buildplan.app.render.filament.PresetGroup
import com.buildplan.app.render.filament.RenderStyle
import com.buildplan.app.render.filament.SpikeVisibility
import com.buildplan.app.render.filament.rememberModelScene
import com.buildplan.app.ui.components.ContextPanel
import com.buildplan.app.ui.components.PanelGroupLabel
import com.buildplan.app.ui.components.PanelOption
import com.buildplan.app.ui.components.PanelRule
import com.buildplan.app.ui.components.RailTool
import com.buildplan.app.ui.components.ToolDock
import com.buildplan.app.ui.components.ToolDockDefaults
import com.buildplan.app.ui.components.WorkspaceGlyphs
import com.buildplan.app.ui.components.WorkspaceScrims
import com.buildplan.app.ui.components.labelRes
import com.buildplan.app.ui.workspace.LocalMotionPolicy
import com.buildplan.app.ui.workspace.WorkspaceChromeState
import com.buildplan.app.ui.workspace.WorkspaceSurface

/**
 * The debug build's canvas: the Filament renderer edge to edge, the model's
 * tools on the right edge, and the inspector for whatever is picked.
 *
 * This is the debug half of a variant-specific declaration — `src/release`
 * has the other, which keeps the reserved study. The seam exists so that the
 * renderer can fill the workspace without the renderer, its native libraries
 * or its scaffolding vocabulary appearing anywhere in a release build.
 *
 * The tools are chrome over the model, never a frame round it. Each is a
 * glyph on the rail that opens a panel beside it; the panel changes the
 * scene through [ModelScene] and the canvas follows. What is visible is still
 * decided by the domain, narrowed by the presentation profile and bridged by
 * `primitivesOf` — the rail only chooses which question to ask.
 */
@Composable
internal fun WorkspaceModel(
    chrome: WorkspaceChromeState,
    safeArea: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var model by rememberSaveable { mutableStateOf(DebugModel.MARCOWKI) }

    // Keyed on the model: switching it replaces every mesh, so the renderer
    // and its engine are torn down and rebuilt rather than being asked to
    // swap their buffers underneath themselves.
    key(model) {
        val scene = rememberModelScene(model)
        Box(modifier = modifier.fillMaxSize()) {
            if (scene == null) {
                Text(
                    text = stringResource(R.string.model_spike_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(safeArea),
                )
            } else {
                FilamentCanvas(scene = scene, modifier = Modifier.fillMaxSize())
            }
            WorkspaceScrims(modifier = Modifier.fillMaxSize())
            if (scene != null) {
                // Back lets go of a picked element once nothing else is open:
                // the shell's own handler takes the open surface first, and
                // only then does this one see the gesture.
                BackHandler(enabled = scene.selected != null && chrome.surface == WorkspaceSurface.None) {
                    scene.clearSelection()
                }
                ModelTools(
                    scene = scene,
                    chrome = chrome,
                    onSelectModel = { model = it },
                    safeArea = safeArea,
                    modifier = Modifier.fillMaxSize(),
                )
                SelectionInspector(
                    scene = scene,
                    timelineExpanded = chrome.timelineExpanded,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(safeArea),
                )
            }
        }
    }
}

private const val TOOL_LAYERS = "layers"
private const val TOOL_VIEWS = "views"
private const val TOOL_STYLE = "style"
private const val TOOL_RECENTER = "recenter"
private const val TOOL_SOURCE = "source"

/**
 * The rail and its panels, in the order a reviewer reaches for them: what is
 * shown, from where, in which presentation, the camera home, and — last —
 * where the numbers came from.
 */
@Composable
private fun ModelTools(
    scene: ModelScene,
    chrome: WorkspaceChromeState,
    onSelectModel: (DebugModel) -> Unit,
    safeArea: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionPolicy.current
    val tools = listOf(
        RailTool(TOOL_LAYERS, WorkspaceGlyphs.Layers, stringResource(R.string.tool_layers)),
        RailTool(TOOL_VIEWS, WorkspaceGlyphs.Views, stringResource(R.string.tool_views)),
        RailTool(TOOL_STYLE, WorkspaceGlyphs.Style, stringResource(R.string.tool_style)),
        RailTool(TOOL_RECENTER, WorkspaceGlyphs.Recenter, stringResource(R.string.model_spike_reset_camera), momentary = true),
        RailTool(TOOL_SOURCE, WorkspaceGlyphs.Source, stringResource(R.string.tool_source)),
    )

    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier = modifier) {
        val freeHeight = maxHeight - safeArea.calculateTopPadding() - safeArea.calculateBottomPadding()
        val panelMaxHeight = freeHeight * ToolDockDefaults.PanelHeightShare
        ToolDock(
            tools = tools,
            openKey = chrome.openTool,
            onToolClick = { tool ->
                if (tool.momentary) scene.resetCamera(motion) else chrome.toggleTool(tool.key)
            },
            panelMaxHeight = panelMaxHeight,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = safeArea.calculateTopPadding(),
                    end = safeArea.calculateEndPadding(direction),
                    start = safeArea.calculateStartPadding(direction),
                ),
        ) { tool ->
            when (tool.key) {
                TOOL_LAYERS -> SpikeVisibility.entries.forEach { option ->
                    PanelOption(
                        label = stringResource(option.labelRes),
                        selected = option == scene.visibility,
                        onClick = { scene.showVisibility(option) },
                    )
                }
                TOOL_VIEWS -> ViewsPanel(scene)
                TOOL_STYLE -> RenderStyle.entries.forEach { option ->
                    PanelOption(
                        label = stringResource(option.labelRes),
                        selected = option == scene.style,
                        onClick = { scene.showStyle(option) },
                    )
                }
                TOOL_SOURCE -> SourcePanel(scene = scene, onSelectModel = onSelectModel)
            }
        }
    }
}

/**
 * The named views, four short lists instead of one long one: the mass, the
 * inside, the facades, the details. The grouping is the list's, not the
 * camera's — see [PresetGroup].
 */
@Composable
private fun ViewsPanel(scene: ModelScene) {
    val motion = LocalMotionPolicy.current
    PresetGroup.entries.forEachIndexed { index, group ->
        if (index > 0) PanelRule()
        PanelGroupLabel(stringResource(group.labelRes))
        ModelViewPreset.entries.filter { it.group == group }.forEach { option ->
            PanelOption(
                label = stringResource(option.labelRes),
                selected = option == scene.preset,
                onClick = { scene.applyPreset(option, motion) },
            )
        }
    }
}

/**
 * Where the model came from and how far to trust it: which model is loaded,
 * how to move it, and the fidelity counts behind the traced one.
 *
 * Developer-facing and text-only. It deliberately shows no drawing: the plans
 * behind these numbers are ARCHON's, and putting one in the app would ship
 * somebody else's copyrighted work in an APK. A URL and a count of how many
 * numbers were measured rather than published says the same thing about
 * trustworthiness, and is ours to ship.
 */
@Composable
private fun SourcePanel(scene: ModelScene, onSelectModel: (DebugModel) -> Unit) {
    DebugModel.entries.forEach { option ->
        PanelOption(
            label = stringResource(option.labelRes),
            selected = option == scene.model,
            onClick = { onSelectModel(option) },
        )
    }
    PanelRule()
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Note(stringResource(R.string.model_spike_gesture_hint))
        if (scene.model == DebugModel.MARCOWKI) {
            val uncertain = remember {
                MarcowkiRoomTrace.all.count { it.certainty == TraceCertainty.TRACE_UNCERTAIN }
            }
            Note(stringResource(R.string.model_spike_description))
            Note(MarcowkiSourceEvidence.PAGE_URL)
            Note(stringResource(R.string.model_fidelity_retrieved, MarcowkiSourceEvidence.RETRIEVED_AT))
            Note(stringResource(R.string.model_fidelity_exact, MarcowkiSourceEvidence.countOf(SourceFidelity.SOURCE_EXACT)))
            Note(stringResource(R.string.model_fidelity_traced, MarcowkiSourceEvidence.countOf(SourceFidelity.SOURCE_TRACED)))
            Note(
                stringResource(
                    R.string.model_fidelity_assumed,
                    MarcowkiSourceEvidence.countOf(SourceFidelity.DISPLAY_ASSUMPTION),
                ),
            )
            Note(stringResource(R.string.model_fidelity_rooms, MarcowkiRoomTrace.all.size, uncertain))
            Note(MarcowkiSourceEvidence.DISCLAIMER)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the picked element is, beside the house it was picked from: its name,
 * its kind and where it lives, and the rooms it touches.
 *
 * Read off the element rather than off the geometry: the shapes carry only an
 * element id, and looking a room up through them would be the renderer forming
 * its own opinion about who a wall belongs to.
 */
@Composable
private fun SelectionInspector(
    scene: ModelScene,
    timelineExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionPolicy.current

    // While the timeline is unfolded over this corner the inspector fades
    // rather than folding: the rail is the one thing moving there, and an
    // invisible pane must not go on stopping touches, so once faded it is
    // not composed at all.
    // The pane grows towards the thing that was touched: the pivot is the
    // tap's position inside the pane's own bounds, clamped to its edges, so
    // a pick on the roof above and to the right pulls the pane out of its
    // top-right corner. Before the pane has ever been measured it uses that
    // corner — the one facing the house — outright. Remembered above the
    // early return below, so a timeline cycle does not forget where the pane
    // sits.
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val presence by animateFloatAsState(
        targetValue = if (timelineExpanded) 0f else 1f,
        animationSpec = if (timelineExpanded) motion.exit() else motion.enter(),
        label = "inspectorPresence",
    )
    if (timelineExpanded && presence == 0f) return

    val anchor = scene.selectionAnchor
    val origin = remember(anchor, bounds) {
        val box = bounds
        if (anchor == null || box == null || box.width <= 0f || box.height <= 0f) {
            TransformOrigin(1f, 0f)
        } else {
            TransformOrigin(
                ((anchor.x - box.left) / box.width).coerceIn(0f, 1f),
                ((anchor.y - box.top) / box.height).coerceIn(0f, 1f),
            )
        }
    }

    // The panel keeps its words while it leaves: the last element shown is
    // held outside the snapshot and updated after composition, so the frame
    // that starts the exit still has something to draw.
    val lastShown = remember { LastShown() }
    val current = scene.selectedElement
    SideEffect { if (current != null) lastShown.element = current }
    val element = current ?: lastShown.element

    AnimatedVisibility(
        visible = element != null,
        enter = motion.panelEnter(origin),
        exit = motion.panelExit(origin),
        modifier = modifier
            .graphicsLayer { alpha = presence }
            .onGloballyPositioned { bounds = it.boundsInRoot() },
    ) {
        if (element != null) {
            val building = scene.model.building
            val place = when (val scope = element.scope) {
                BuildingElementScope.WholeBuilding -> stringResource(R.string.inspector_scope_whole_building)
                is BuildingElementScope.OnFloor ->
                    building.floors.firstOrNull { it.id == scope.floorId }?.name ?: scope.floorId.value
            }
            val rooms = building.floors
                .flatMap { it.rooms }
                .filter { it.id in element.roomIds }
                .map { it.name }

            ContextPanel(
                title = element.name,
                supporting = stringResource(R.string.inspector_kind_place, stringResource(element.kind.labelRes), place),
                onClose = scene::clearSelection,
            ) {
                Text(
                    text = if (rooms.isEmpty()) {
                        stringResource(R.string.inspector_no_rooms)
                    } else {
                        stringResource(R.string.inspector_rooms, rooms.joinToString())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A plain holder, deliberately not snapshot state: writing it must not recompose anything. */
private class LastShown {
    var element: BuildingElement? = null
}
