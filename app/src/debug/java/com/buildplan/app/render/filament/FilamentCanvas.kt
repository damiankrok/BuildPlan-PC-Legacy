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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.buildplan.app.ui.workspace.LocalMotionPolicy
import kotlinx.coroutines.delay

private const val TAG = "FilamentSpike"

/**
 * The surface the house is drawn on, and nothing else: Filament in a
 * `SurfaceView`, the camera gestures over it, and the cover that hides the
 * first black frames.
 *
 * Edge to edge by design. It is the workspace's background, not a widget in
 * it: no shape, no border, no padding. The chrome that sits over it is
 * composed by the caller and knows nothing of Filament; the two meet only in
 * the [ModelScene] both read.
 *
 * Debug-only, deliberately. A model traced off a third party's product
 * drawings is a development aid for one owner review, not product content.
 */
@Composable
internal fun FilamentCanvas(
    scene: ModelScene,
    modifier: Modifier = Modifier,
) {
    // Until the renderer has compiled its programs and drawn with them the
    // surface is undefined — black on most devices — so the canvas is covered
    // in the style's own backdrop and uncovered when the renderer says it is
    // showing the model. Should that word never come, the cover lifts on its
    // own after a while rather than hiding a working renderer for good.
    val renderer = scene.renderer
    LaunchedEffect(renderer) {
        if (renderer == null || scene.modelShown) return@LaunchedEffect
        delay(COVER_TIMEOUT_MILLIS)
        scene.modelShown = true
    }

    val visibleElementIds = scene.visibleElementIds
    LaunchedEffect(renderer, visibleElementIds) {
        renderer?.setVisibleElements(visibleElementIds)
    }
    val style = scene.style
    LaunchedEffect(renderer, style) {
        renderer?.setStyle(style)
    }
    // Outside composition on purpose: reading the camera during composition
    // would subscribe this composable to every frame of every drag.
    LaunchedEffect(renderer) {
        scene.pushPose()
    }
    val selected = scene.selected
    LaunchedEffect(renderer, selected) {
        renderer?.setSelectedElement(selected)
    }

    // Taken from the view tree rather than the deprecated composition local, so
    // the spike needs no extra lifecycle-compose dependency of its own.
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    DisposableEffect(lifecycleOwner, renderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer?.resume()
                Lifecycle.Event.ON_PAUSE -> renderer?.pause()
                else -> Unit
            }
        }
        val lifecycle = lifecycleOwner?.lifecycle
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            renderer?.pause()
        }
    }

    Box(
        modifier = modifier.pointerInput(renderer) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A finger on the canvas owns the camera from this moment; a
                // preset still on its way stops where it is.
                scene.interruptCamera()
                var multiTouch = false
                var travelled = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    if (pressed > 1) multiTouch = true

                    val pan = event.calculatePan()
                    travelled += pan.getDistance()

                    // Past the slop this is a move, not a tap: the picture no
                    // longer matches any named view.
                    if (multiTouch || travelled > viewConfiguration.touchSlop) scene.cameraMovedByHand()
                    if (multiTouch) {
                        scene.cameraState.zoomBy(event.calculateZoom())
                        // The field of view spans the shorter side — see
                        // FilamentModelRenderer.applyCamera — so that is the
                        // side a pan is measured against.
                        scene.cameraState.pan(pan.x, pan.y, minOf(size.width, size.height))
                    } else {
                        scene.cameraState.orbit(pan.x, pan.y)
                    }
                    scene.pushPose()

                    // Consuming keeps any surrounding scroll from stealing a
                    // drag that was meant for the camera.
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }

                if (!multiTouch && travelled <= viewConfiguration.touchSlop) {
                    // Filament's viewport origin is bottom-left; the touch
                    // arrives top-left.
                    renderer?.pick(
                        down.position.x.toInt(),
                        size.height - down.position.y.toInt(),
                    ) { picked ->
                        Log.d(TAG, "pick -> ${picked?.value ?: "background"}")
                        scene.select(picked, anchor = down.position)
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
                        meshes = scene.meshes,
                        grid = scene.grid,
                        surfaceRoles = scene.model.surfaceRoles,
                        initialStyle = scene.style,
                        roofCovers = scene.roofCovers,
                    )
                    created.setVisibleElements(scene.visibleElementIds)
                    created.onReady = { scene.modelShown = true }
                    created.resume()
                    scene.renderer = created
                }
            },
            onRelease = {
                scene.renderer?.destroy()
                scene.renderer = null
            },
        )

        // The cover says what it is waiting for once the wait is long enough to
        // notice: a quiet line and a progress bar, not a spinner in a void.
        var waitingNoticed by remember { mutableStateOf(false) }
        LaunchedEffect(scene.modelShown) {
            if (scene.modelShown) return@LaunchedEffect
            delay(LOADING_NOTICE_DELAY_MILLIS)
            waitingNoticed = true
        }
        val motion = LocalMotionPolicy.current
        AnimatedVisibility(
            visible = !scene.modelShown,
            enter = EnterTransition.None,
            exit = fadeOut(motion.exit()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(style.backdropColor()),
                contentAlignment = Alignment.Center,
            ) {
                if (waitingNoticed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.model_spike_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = style.loadingInkColor(),
                        )
                        // A loading sweep is allowed to loop — it is a signal, not
                        // decoration — but under reduced motion even that holds still.
                        if (motion.reduced) {
                            LinearProgressIndicator(
                                progress = { 0f },
                                modifier = Modifier.width(96.dp),
                                color = style.loadingInkColor(),
                                trackColor = style.loadingInkColor().copy(alpha = 0.18f),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.width(96.dp),
                                color = style.loadingInkColor(),
                                trackColor = style.loadingInkColor().copy(alpha = 0.18f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How long the cover waits for the renderer's word before lifting on its own. */
private const val COVER_TIMEOUT_MILLIS = 15_000L

/** How long a wait goes unremarked before the cover says what it is doing. */
private const val LOADING_NOTICE_DELAY_MILLIS = 600L

/**
 * Ink that reads on this style's backdrop: dark on the light study, light on
 * the dark line study — the one place the chrome takes its colour from the
 * renderer, because here it stands on the renderer's own ground.
 */
private fun RenderStyle.loadingInkColor(): Color =
    if (background[0] > 0.2f) Color(0xFF3A3B3D) else Color(0xFFC9CBCE)

/**
 * The style's backdrop as a Compose colour: Filament's linear values encoded
 * to sRGB, which is what the screen and Compose both speak.
 */
internal fun RenderStyle.backdropColor(): Color =
    Color(background[0].toSrgb(), background[1].toSrgb(), background[2].toSrgb())

private fun Float.toSrgb(): Float =
    if (this <= 0.0031308f) this * 12.92f else (1.055f * Math.pow(toDouble(), 1.0 / 2.4).toFloat() - 0.055f)
