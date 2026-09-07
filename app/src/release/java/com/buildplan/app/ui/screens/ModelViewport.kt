package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.components.BuildingWireframe
import com.buildplan.app.ui.components.PlaceholderPanel

/**
 * The release build's model area: still the reserved placeholder.
 *
 * The STAGE-012 renderer spike is deliberately debug-only, so a release build
 * shows exactly what it showed before the spike existed. This is the release
 * half of a variant-specific declaration; `src/debug` has the other.
 */
@Composable
internal fun ModelViewport(modifier: Modifier = Modifier) {
    PlaceholderPanel(
        title = stringResource(R.string.model_panel_title),
        description = stringResource(R.string.model_screen_panel_description),
        modifier = modifier.heightIn(min = 340.dp),
    ) {
        BuildingWireframe(modifier = Modifier.padding(bottom = 24.dp))
    }
}
