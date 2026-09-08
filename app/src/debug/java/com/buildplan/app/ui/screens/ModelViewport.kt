package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.buildplan.app.render.filament.FilamentModelViewport
import com.buildplan.app.render.filament.ViewportMode

/**
 * The debug build's model area: the Filament renderer with its control dock.
 *
 * This is the debug half of a variant-specific declaration — `src/release`
 * has the other, which keeps the reserved placeholder the screen has always
 * shown. The seam exists so that the renderer can be reached from the real
 * Model 3D screen without the renderer, its native libraries or its
 * scaffolding vocabulary appearing anywhere in a release build.
 */
@Composable
internal fun ModelViewport(modifier: Modifier = Modifier) {
    FilamentModelViewport(modifier = modifier.fillMaxSize(), mode = ViewportMode.STUDIO)
}

/**
 * The home screen's hero in a debug build: the same renderer with no controls,
 * orbitable in place and tappable through to the model screen.
 */
@Composable
internal fun HomeModelHero(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    FilamentModelViewport(modifier = modifier, mode = ViewportMode.HERO, onTap = onOpen)
}
