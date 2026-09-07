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
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.geometry.primitivesOf

private const val TAG = "FilamentSpike"

/**
 * Which of the four debug views of the building is on screen.
 *
 * The enum carries no rule of its own: it turns straight into a
 * [BuildingVisibility], and the domain decides from there what survives. Adding
 * "hide the roof" logic here would be the second answer to a question the domain
 * already answers, and the one the user saw would be whichever the renderer
 * happened to consult.
 */
internal enum class SpikeVisibility(val labelRes: Int) {
    EVERYTHING(R.string.model_spike_visibility_all),
    ROOF_HIDDEN(R.string.model_spike_visibility_no_roof),
    UPPER_FLOOR_HIDDEN(R.string.model_spike_visibility_no_upper_floor),
    ROOF_AND_UPPER_FLOOR_HIDDEN(R.string.model_spike_visibility_no_roof_no_upper_floor),
    ;

    fun toBuildingVisibility(): BuildingVisibility = when (this) {
        EVERYTHING -> BuildingVisibility.EVERYTHING
        ROOF_HIDDEN -> BuildingVisibility(roofHidden = true)
        UPPER_FLOOR_HIDDEN -> BuildingVisibility(hiddenFloorIds = setOf(SyntheticDemoHouse.atticId))
        ROOF_AND_UPPER_FLOOR_HIDDEN -> BuildingVisibility(
            hiddenFloorIds = setOf(SyntheticDemoHouse.atticId),
            roofHidden = true,
        )
    }
}

/**
 * The STAGE-012 renderer spike: the synthetic demo house drawn by Filament,
 * with just enough controls around it to prove the things the spike exists to
 * prove.
 *
 * Debug-only, deliberately. It is reachable from the Model 3D screen in a debug
 * build and absent from release, because this is a renderer *selection* exercise
 * and the production renderer host is STAGE-013's to design.
 *
 * Nothing here is persisted. Visibility and selection start over on every
 * recreation; only the camera is remembered, and only because the user aimed it.
 */
@Composable
fun FilamentModelViewport(modifier: Modifier = Modifier) {
    val bounds = remember { SyntheticDemoHouse.geometry.bounds }
    if (bounds == null) {
        Text(
            text = stringResource(R.string.model_spike_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val meshes = remember { SyntheticDemoHouse.geometry.primitives.toRenderMeshes() }
    val elementNames = remember {
        SyntheticDemoHouse.building.elements.associate { it.id to it.name }
    }
    val cameraState = rememberOrbitCameraState(bounds)

    var visibility by remember { mutableStateOf(SpikeVisibility.EVERYTHING) }
    var selected by remember { mutableStateOf<BuildingElementId?>(null) }
    var renderer by remember { mutableStateOf<FilamentModelRenderer?>(null) }

    // The single decision path: the domain says which elements survive, the
    // geometry layer's one bridge says which shapes draw them, and their element
    // ids are what the renderer shows.
    val visibleElementIds = remember(visibility) {
        SyntheticDemoHouse.geometry
            .primitivesOf(SyntheticDemoHouse.building.visibleElements(visibility.toBuildingVisibility()))
            .mapTo(LinkedHashSet()) { it.elementId }
    }

    val currentRenderer = renderer
    LaunchedEffect(currentRenderer, visibleElementIds) {
        currentRenderer?.setVisibleElements(visibleElementIds)
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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            val created = FilamentModelRenderer(surfaceView, meshes)
                            created.setVisibleElements(visibleElementIds)
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

        VisibilityControls(
            selected = visibility,
            onSelect = { visibility = it },
            onResetCamera = {
                cameraState.reset()
                currentRenderer?.pose = cameraState.pose()
            },
        )

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
    }
}

@Composable
private fun VisibilityControls(
    selected: SpikeVisibility,
    onSelect: (SpikeVisibility) -> Unit,
    onResetCamera: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpikeVisibility.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
        TextButton(onClick = onResetCamera) {
            Text(stringResource(R.string.model_spike_reset_camera))
        }
    }
}

private const val VIEWPORT_HEIGHT_DP = 420
