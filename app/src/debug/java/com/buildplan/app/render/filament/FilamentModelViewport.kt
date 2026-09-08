package com.buildplan.app.render.filament

import android.util.Log
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.buildplan.app.R
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.reference.visual.MarcowkiRoomTrace
import com.buildplan.app.reference.visual.MarcowkiSourceEvidence
import com.buildplan.app.reference.visual.SourceFidelity
import com.buildplan.app.reference.visual.TraceCertainty
import kotlinx.coroutines.delay

private const val TAG = "FilamentSpike"

/**
 * Which of the four debug views of the building is on screen.
 *
 * The enum carries no rule of its own: it turns straight into a
 * [BuildingVisibility], and the domain decides from there what survives. Adding
 * "hide the roof" logic here would be the second answer to a question the domain
 * already answers, and the one the user saw would be whichever the renderer
 * happened to consult.
 *
 * It is told which storey counts as the upper one rather than knowing, so that
 * the same four views mean the same thing on whichever model is loaded.
 */
internal enum class SpikeVisibility(val labelRes: Int) {
    EVERYTHING(R.string.model_spike_visibility_all),
    ROOF_HIDDEN(R.string.model_spike_visibility_no_roof),
    UPPER_FLOOR_HIDDEN(R.string.model_spike_visibility_no_upper_floor),
    ROOF_AND_UPPER_FLOOR_HIDDEN(R.string.model_spike_visibility_no_roof_no_upper_floor),
    ;

    fun toBuildingVisibility(atticId: FloorId): BuildingVisibility = when (this) {
        EVERYTHING -> BuildingVisibility.EVERYTHING
        ROOF_HIDDEN -> BuildingVisibility(roofHidden = true)
        UPPER_FLOOR_HIDDEN -> BuildingVisibility(hiddenFloorIds = setOf(atticId))
        ROOF_AND_UPPER_FLOOR_HIDDEN -> BuildingVisibility(
            hiddenFloorIds = setOf(atticId),
            roofHidden = true,
        )
    }
}

/**
 * How much of the viewport is shown around the model.
 *
 * [HERO] is the home screen: the house alone, orbitable, and a tap goes to
 * the model screen. [STUDIO] is the model screen: the same house with the
 * control dock under it — views, presets, style — and the developer details
 * folded away until asked for. One renderer, one model, two amounts of
 * furniture.
 */
enum class ViewportMode { HERO, STUDIO }

/**
 * The debug model viewport: a traced or synthetic building drawn by Filament,
 * with as much or as little around it as [mode] asks for.
 *
 * Debug-only, deliberately. It is reachable from the Model 3D screen and the
 * home screen in a debug build and absent from release: a model traced off a
 * third party's product drawings is a development aid for one owner review,
 * not product content.
 *
 * Nothing here is persisted. The model, the view and the selection start over
 * on every recreation; only the camera is remembered, and only because the
 * user aimed it by hand.
 */
@Composable
fun FilamentModelViewport(
    modifier: Modifier = Modifier,
    mode: ViewportMode = ViewportMode.STUDIO,
    onTap: (() -> Unit)? = null,
) {
    var model by remember { mutableStateOf(DebugModel.MARCOWKI) }
    var detailsShown by remember { mutableStateOf(false) }

    // Keyed on the model: switching it replaces every mesh, so the renderer
    // and its engine are torn down and rebuilt rather than being asked to
    // swap their buffers underneath themselves.
    key(model) {
        ModelStage(
            model = model,
            mode = mode,
            onTap = onTap,
            detailsShown = detailsShown,
            onToggleDetails = { detailsShown = !detailsShown },
            onSelectModel = { model = it },
            modifier = modifier,
        )
    }
}

@Composable
private fun ModelStage(
    model: DebugModel,
    mode: ViewportMode,
    onTap: (() -> Unit)?,
    detailsShown: Boolean,
    onToggleDetails: () -> Unit,
    onSelectModel: (DebugModel) -> Unit,
    modifier: Modifier,
) {
    val bounds = remember(model) { model.geometry.bounds }
    if (bounds == null) {
        Text(
            text = stringResource(R.string.model_spike_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    // The frames round the facade panes are meshes like any other: baked
    // from the panes the model's presentation names, carrying the panes'
    // own ids, and uploaded beside the primitives' meshes.
    val meshes = remember(model) {
        model.geometry.primitives.toRenderMeshes() + model.geometry.openingFrameMeshes(model.openingFrames)
    }

    // The roof coverings, laid once over the facets the model's presentation
    // names and handed to the renderer beside the meshes. They carry the
    // roof's own id, so the visibility path below needs no word about them.
    val roofCovers = remember(model) { model.geometry.roofCoverMeshes(model.roofCover) }

    // Built from the model's extent and handed to the renderer, so the ruled
    // plane is a fact about how big this model is rather than a constant that
    // would be the wrong size for the other one.
    val grid = remember(bounds) { PresentationGrid.under(bounds) }
    val elementNames = remember(model) { model.building.elements.associate { it.id to it.name } }
    val cameraState = rememberOrbitCameraState(bounds)

    var preset by remember { mutableStateOf(ModelViewPreset.FULL_AXON) }
    var visibility by remember { mutableStateOf(ModelViewPreset.FULL_AXON.visibility) }
    var style by remember { mutableStateOf(RenderStyle.DEFAULT) }
    var selected by remember { mutableStateOf<BuildingElementId?>(null) }
    var renderer by remember { mutableStateOf<FilamentModelRenderer?>(null) }

    // Until the renderer has compiled its programs and drawn with them the
    // surface is undefined — black on most devices — so the viewport is
    // covered in the style's own backdrop and uncovered when the renderer
    // says it is showing the model. The model appears; nothing flashes.
    // Should that word never come — a driver that never reports a compile —
    // the cover lifts on its own after a while rather than hiding a working
    // renderer for good.
    var modelShown by remember { mutableStateOf(false) }
    LaunchedEffect(currentRendererKey(renderer)) {
        if (renderer == null || modelShown) return@LaunchedEffect
        delay(COVER_TIMEOUT_MILLIS)
        modelShown = true
    }

    // The single decision path: the domain says which elements survive, the
    // model's presentation profile narrows that to what the view is meant to
    // expose, the geometry layer's one bridge says which shapes draw them, and
    // their element ids are what the renderer shows. What is left out is not
    // drawn at all — see FilamentModelRenderer.setVisibleElements.
    val visibleElementIds = remember(model, visibility) {
        model.visibleElementIds(visibility.toBuildingVisibility(model.atticId))
    }

    val currentRenderer = renderer
    LaunchedEffect(currentRenderer, visibleElementIds) {
        currentRenderer?.setVisibleElements(visibleElementIds)
    }
    LaunchedEffect(currentRenderer, style) {
        currentRenderer?.setStyle(style)
    }
    // Outside composition on purpose: reading the camera during composition
    // would subscribe this composable to every frame of every drag.
    LaunchedEffect(currentRenderer) {
        currentRenderer?.pose = cameraState.pose()
    }
    LaunchedEffect(currentRenderer, selected) {
        currentRenderer?.setSelectedElement(selected)
    }

    // Taken from the view tree rather than the deprecated composition local, so
    // the spike needs no extra lifecycle-compose dependency of its own.
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    DisposableEffect(lifecycleOwner, currentRenderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> currentRenderer?.resume()
                Lifecycle.Event.ON_PAUSE -> currentRenderer?.pause()
                else -> Unit
            }
        }
        val lifecycle = lifecycleOwner?.lifecycle
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            currentRenderer?.pause()
        }
    }

    val surface: @Composable (Modifier) -> Unit = { surfaceModifier ->
        Box(
            modifier = surfaceModifier
                .clip(MaterialTheme.shapes.large)
                .pointerInput(currentRenderer, onTap) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var multiTouch = false
                        var travelled = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed > 1) multiTouch = true

                            val pan = event.calculatePan()
                            travelled += pan.getDistance()

                            if (multiTouch) {
                                cameraState.zoomBy(event.calculateZoom())
                                // The field of view spans the shorter side —
                                // see FilamentModelRenderer.applyCamera — so
                                // that is the side a pan is measured against.
                                cameraState.pan(pan.x, pan.y, minOf(size.width, size.height))
                            } else {
                                cameraState.orbit(pan.x, pan.y)
                            }
                            currentRenderer?.pose = cameraState.pose()

                            // Consuming keeps any surrounding scroll from
                            // stealing a drag that was meant for the camera.
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }

                        if (!multiTouch && travelled <= viewConfiguration.touchSlop) {
                            if (onTap != null) {
                                // The hero: a tap is a request to open the
                                // model, not a pick.
                                onTap()
                            } else {
                                // Filament's viewport origin is bottom-left;
                                // the touch arrives top-left.
                                currentRenderer?.pick(
                                    down.position.x.toInt(),
                                    size.height - down.position.y.toInt(),
                                ) { picked ->
                                    Log.d(TAG, "pick -> ${picked?.value ?: "background"}")
                                    selected = picked
                                }
                            }
                        }
                    }
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).also { surfaceView ->
                        val created = FilamentModelRenderer(
                            surfaceView = surfaceView,
                            meshes = meshes,
                            grid = grid,
                            surfaceRoles = model.surfaceRoles,
                            initialStyle = style,
                            roofCovers = roofCovers,
                        )
                        created.setVisibleElements(visibleElementIds)
                        created.onReady = { modelShown = true }
                        created.resume()
                        renderer = created
                    }
                },
                onRelease = {
                    renderer?.destroy()
                    renderer = null
                },
            )

            AnimatedVisibility(
                visible = !modelShown,
                enter = EnterTransition.None,
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(style.backdropColor()),
                )
            }

            val selectedId = selected
            if (selectedId != null) {
                SelectionPill(
                    name = elementNames[selectedId] ?: selectedId.value,
                    onClear = { selected = null },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                )
            }
        }
    }

    when (mode) {
        ViewportMode.HERO -> surface(modifier.fillMaxSize())
        ViewportMode.STUDIO -> Column(modifier = modifier.fillMaxSize()) {
            surface(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            ControlDock(
                model = model,
                preset = preset,
                visibility = visibility,
                style = style,
                selected = selected,
                detailsShown = detailsShown,
                onPreset = { option ->
                    preset = option
                    visibility = option.visibility
                    cameraState.apply(option.framing(bounds, model.focusBounds(option.focus, bounds)))
                    currentRenderer?.pose = cameraState.pose()
                },
                onVisibility = { visibility = it },
                onStyle = { style = it },
                onResetCamera = {
                    cameraState.reset()
                    currentRenderer?.pose = cameraState.pose()
                },
                onToggleDetails = onToggleDetails,
                onSelectModel = onSelectModel,
            )
        }
    }
}

/**
 * The name of the picked element, floating over the viewport, with one way
 * to let go of it. Shown only while something is picked: an empty prompt
 * under the model is furniture, and the model is the point.
 */
@Composable
private fun SelectionPill(name: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.model_selection_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The control system under the viewport, in the order a reviewer reaches for
 * it: what is shown, from where, in which presentation — and, folded away,
 * the developer's details.
 *
 * Three rows of one visual language. The view state is a segmented control,
 * because it is one choice of four. The presets are chips in a scrolling
 * row, because there are fifteen and any may be next. The style is a
 * segmented pair beside the camera reset and the details toggle, because
 * those are the things touched once. Everything else — which model, how to
 * gesture, where the numbers came from — is behind the toggle.
 */
@Composable
private fun ControlDock(
    model: DebugModel,
    preset: ModelViewPreset,
    visibility: SpikeVisibility,
    style: RenderStyle,
    selected: BuildingElementId?,
    detailsShown: Boolean,
    onPreset: (ModelViewPreset) -> Unit,
    onVisibility: (SpikeVisibility) -> Unit,
    onStyle: (RenderStyle) -> Unit,
    onResetCamera: () -> Unit,
    onToggleDetails: () -> Unit,
    onSelectModel: (DebugModel) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                val options = SpikeVisibility.entries
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == visibility,
                        onClick = { onVisibility(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            activeContentColor = MaterialTheme.colorScheme.onSurface,
                            inactiveContainerColor = MaterialTheme.colorScheme.background,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        icon = {},
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                        label = {
                            Text(
                                text = stringResource(option.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.model_controls_presets),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelViewPreset.entries.forEach { option ->
                    FilterChip(
                        selected = option == preset,
                        onClick = { onPreset(option) },
                        label = { ChipLabel(stringResource(option.labelRes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.width(200.dp)) {
                    val options = RenderStyle.entries
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option == style,
                            onClick = { onStyle(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                activeContentColor = MaterialTheme.colorScheme.onSurface,
                                inactiveContainerColor = MaterialTheme.colorScheme.background,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            icon = {},
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                            label = {
                                Text(
                                    text = stringResource(option.labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onResetCamera) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.model_spike_reset_camera),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleDetails) {
                    Icon(
                        imageVector = if (detailsShown) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(
                            if (detailsShown) R.string.model_controls_details_hide else R.string.model_controls_details_show,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = detailsShown) {
                Details(model = model, selected = selected, onSelectModel = onSelectModel)
            }
        }
    }
}

/**
 * The developer's details: which model, how to gesture, and where the
 * numbers came from. Folded away by default because none of it is the house.
 */
@Composable
private fun Details(
    model: DebugModel,
    selected: BuildingElementId?,
    onSelectModel: (DebugModel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.model_debug_source_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DebugModel.entries.forEach { option ->
                FilterChip(
                    selected = option == model,
                    onClick = { onSelectModel(option) },
                    label = { ChipLabel(stringResource(option.labelRes)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.model_spike_gesture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model == DebugModel.MARCOWKI) {
            Text(
                text = stringResource(R.string.model_spike_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceFidelityPanel(selectedElementId = selected)
        }
    }
}

/**
 * What the traced model claims, and how strongly.
 *
 * Developer-facing and text-only. It deliberately shows no drawing: the plans
 * behind these numbers are ARCHON's, and putting one in the app would ship
 * somebody else's copyrighted work in an APK. A URL and a count of how many
 * numbers were measured rather than published says the same thing about
 * trustworthiness, and is ours to ship.
 */
@Composable
private fun SourceFidelityPanel(selectedElementId: BuildingElementId?) {
    val uncertain = remember {
        MarcowkiRoomTrace.all.filter { it.certainty == TraceCertainty.TRACE_UNCERTAIN }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelLine(stringResource(R.string.model_fidelity_title), emphasised = true)
            PanelLine(MarcowkiSourceEvidence.PAGE_URL)
            PanelLine(
                stringResource(
                    R.string.model_fidelity_retrieved,
                    MarcowkiSourceEvidence.RETRIEVED_AT,
                ),
            )
            PanelLine(
                stringResource(
                    R.string.model_fidelity_counts,
                    MarcowkiSourceEvidence.countOf(SourceFidelity.SOURCE_EXACT),
                    MarcowkiSourceEvidence.countOf(SourceFidelity.SOURCE_TRACED),
                    MarcowkiSourceEvidence.countOf(SourceFidelity.DISPLAY_ASSUMPTION),
                ),
            )
            PanelLine(
                stringResource(
                    R.string.model_fidelity_rooms,
                    MarcowkiRoomTrace.all.size,
                    uncertain.size,
                ),
            )
            if (selectedElementId != null) {
                val rooms = remember(selectedElementId) { roomsOf(selectedElementId) }
                PanelLine(
                    stringResource(
                        R.string.model_fidelity_selected_rooms,
                        if (rooms.isEmpty()) "—" else rooms,
                    ),
                )
            }
            PanelLine(MarcowkiSourceEvidence.DISCLAIMER)
        }
    }
}

/**
 * The rooms the picked element links, as their canonical ids.
 *
 * Read off the element rather than off the geometry: the shapes carry only an
 * element id, and looking a room up through them would be the renderer forming
 * its own opinion about who a wall belongs to.
 */
private fun roomsOf(elementId: BuildingElementId): String =
    DebugModel.MARCOWKI.building.elements
        .firstOrNull { it.id == elementId }
        ?.roomIds
        .orEmpty()
        .joinToString { it.value }

@Composable
private fun PanelLine(text: String, emphasised: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasised) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** The identity a renderer's cover timer is keyed on: the instance, or none. */
private fun currentRendererKey(renderer: FilamentModelRenderer?): Any? = renderer

/** How long the cover waits for the renderer's word before lifting on its own. */
private const val COVER_TIMEOUT_MILLIS = 15_000L

/**
 * The style's backdrop as a Compose colour: Filament's linear values encoded
 * to sRGB, which is what the screen and Compose both speak.
 */
private fun RenderStyle.backdropColor(): Color =
    Color(background[0].toSrgb(), background[1].toSrgb(), background[2].toSrgb())

private fun Float.toSrgb(): Float =
    if (this <= 0.0031308f) this * 12.92f else (1.055f * Math.pow(toDouble(), 1.0 / 2.4).toFloat() - 0.055f)

/**
 * A chip label that is always one line.
 *
 * Compose would otherwise wrap a long label onto a second line and let the chip
 * clip it to its own height, which reads as a label with its descenders cut off
 * rather than as a label that is too long.
 */
@Composable
private fun ChipLabel(text: String) {
    Text(text = text, maxLines = 1, softWrap = false)
}
