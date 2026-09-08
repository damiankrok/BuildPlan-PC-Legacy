package com.buildplan.app.render.filament

import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.primitivesOf
import com.buildplan.app.reference.visual.MarcowkiRoomTrace
import com.buildplan.app.reference.visual.MarcowkiSourceEvidence
import com.buildplan.app.reference.visual.SourceFidelity
import com.buildplan.app.reference.visual.TraceCertainty

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
 * The debug model viewport: a traced or synthetic building drawn by Filament,
 * with just enough controls around it to answer the questions this stage exists
 * to answer.
 *
 * Debug-only, deliberately. It is reachable from the Model 3D screen in a debug
 * build and absent from release: a model traced off a third party's product
 * drawings is a development aid for one owner review, not product content.
 *
 * Nothing here is persisted. The model, the view and the selection start over on
 * every recreation; only the camera is remembered, and only because the user
 * aimed it by hand.
 */
@Composable
fun FilamentModelViewport(modifier: Modifier = Modifier) {
    var model by remember { mutableStateOf(DebugModel.MARCOWKI) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChipRow {
            DebugModel.entries.forEach { option ->
                FilterChip(
                    selected = option == model,
                    onClick = { model = option },
                    label = { ChipLabel(stringResource(option.labelRes)) },
                )
            }
        }

        // Keyed on the model: switching it replaces every mesh, so the renderer
        // and its engine are torn down and rebuilt rather than being asked to
        // swap their buffers underneath themselves.
        key(model) {
            ModelStage(model)
        }
    }
}

@Composable
private fun ModelStage(model: DebugModel) {
    val bounds = remember(model) { model.geometry.bounds }
    if (bounds == null) {
        Text(
            text = stringResource(R.string.model_spike_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val meshes = remember(model) { model.geometry.primitives.toRenderMeshes() }

    // Built from the model's extent and handed to the renderer, so the ruled
    // plane is a fact about how big this model is rather than a constant that
    // would be the wrong size for the other one.
    val grid = remember(bounds) { PresentationGrid.under(bounds) }
    val elementNames = remember(model) { model.building.elements.associate { it.id to it.name } }
    val cameraState = rememberOrbitCameraState(bounds)

    var preset by remember { mutableStateOf(ModelViewPreset.FULL_AXON) }
    var visibility by remember { mutableStateOf(ModelViewPreset.FULL_AXON.visibility) }
    var selected by remember { mutableStateOf<BuildingElementId?>(null) }
    var renderer by remember { mutableStateOf<FilamentModelRenderer?>(null) }

    // The single decision path: the domain says which elements survive, the
    // geometry layer's one bridge says which shapes draw them, and their element
    // ids are what the renderer shows.
    val visibleElementIds = remember(model, visibility) {
        model.geometry
            .primitivesOf(model.building.visibleElements(visibility.toBuildingVisibility(model.atticId)))
            .mapTo(LinkedHashSet()) { it.elementId }
    }

    // What the same answer left out, drawn as a wireframe so that hiding a layer
    // reads as this house with a layer removed rather than as a different, lower
    // building. It is a *complement*, computed from the domain's own answer —
    // not a second rule about roofs and storeys, which would be the one thing
    // the renderer must never own.
    val removedElementIds = remember(model, visibleElementIds) {
        model.geometry.elementIds.filterNotTo(LinkedHashSet()) { it in visibleElementIds }
    }

    val currentRenderer = renderer
    LaunchedEffect(currentRenderer, visibleElementIds) {
        currentRenderer?.setVisibleElements(visibleElementIds, removedElementIds)
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(VIEWPORT_HEIGHT_DP.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentRenderer) {
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
                                    cameraState.pan(pan.x, pan.y, size.height)
                                } else {
                                    cameraState.orbit(pan.x, pan.y)
                                }
                                currentRenderer?.pose = cameraState.pose()

                                // Consuming keeps the surrounding scroll from
                                // stealing a drag that was meant for the camera.
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }

                            if (!multiTouch && travelled <= viewConfiguration.touchSlop) {
                                // Filament's viewport origin is bottom-left; the
                                // touch arrives top-left.
                                currentRenderer?.pick(
                                    down.position.x.toInt(),
                                    size.height - down.position.y.toInt(),
                                ) { picked ->
                                    Log.d(TAG, "pick -> ${picked?.value ?: "background"}")
                                    selected = picked
                                }
                            }
                        }
                    },
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        SurfaceView(context).also { surfaceView ->
                            val created =
                                FilamentModelRenderer(surfaceView, meshes, grid, model.surfaceRoles)
                            created.setVisibleElements(visibleElementIds, removedElementIds)
                            created.resume()
                            renderer = created
                        }
                    },
                    onRelease = {
                        renderer?.destroy()
                        renderer = null
                    },
                )
            }
        }

        // A preset moves the camera as well as the visibility, which is what
        // makes the owner evidence reproducible.
        ChipRow {
            ModelViewPreset.entries.forEach { option ->
                FilterChip(
                    selected = option == preset,
                    onClick = {
                        preset = option
                        visibility = option.visibility
                        cameraState.apply(option.framing(bounds, model.focusBounds(option.focus, bounds)))
                        currentRenderer?.pose = cameraState.pose()
                    },
                    label = { ChipLabel(stringResource(option.labelRes)) },
                )
            }
        }

        // The visibility chips stay, because hiding something without moving the
        // camera is exactly what you want when checking one wall.
        ChipRow {
            SpikeVisibility.entries.forEach { option ->
                FilterChip(
                    selected = option == visibility,
                    onClick = { visibility = option },
                    label = { ChipLabel(stringResource(option.labelRes)) },
                )
            }
        }
        TextButton(
            onClick = {
                cameraState.reset()
                currentRenderer?.pose = cameraState.pose()
            },
        ) {
            Text(stringResource(R.string.model_spike_reset_camera))
        }

        val selectedId = selected
        Text(
            text = if (selectedId == null) {
                stringResource(R.string.model_spike_selection_none)
            } else {
                stringResource(
                    R.string.model_spike_selection,
                    elementNames[selectedId] ?: selectedId.value,
                    selectedId.value,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.model_spike_gesture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (model == DebugModel.MARCOWKI) {
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

/**
 * A horizontally scrollable row of chips.
 *
 * The padding is not decoration. Chips are laid out against the row's own edge,
 * and a chip that starts exactly at it has its label's first glyph on the
 * clipping boundary — which is what the owner's screenshots showed as truncated
 * labels. A little room at each end also leaves the half-chip that says the row
 * scrolls actually visible.
 */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = CHIP_ROW_EDGE_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

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

/** How much room a chip row leaves at each end. */
private const val CHIP_ROW_EDGE_DP = 4

private const val VIEWPORT_HEIGHT_DP = 420
