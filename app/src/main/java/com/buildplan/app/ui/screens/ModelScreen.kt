package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The building model screen: the viewport and nothing around it.
 *
 * What fills the screen is variant-specific: a release build keeps the
 * reserved placeholder, and a debug build shows the Filament renderer with
 * its control dock. Both are declared as `ModelViewport` in their own source
 * set, so this screen does not know or care which renderer, if any, exists.
 */
@Composable
fun ModelScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        ModelViewport(modifier = Modifier.fillMaxSize())
    }
}
