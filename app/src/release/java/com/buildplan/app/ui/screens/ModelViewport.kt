package com.buildplan.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.components.BuildingWireframe
import com.buildplan.app.ui.components.PlaceholderPanel

/**
 * The release build's model area: still the reserved placeholder.
 *
 * The renderer spike is deliberately debug-only, so a release build shows
 * exactly what it showed before the spike existed. This is the release half
 * of a variant-specific declaration; `src/debug` has the other.
 */
@Composable
internal fun ModelViewport(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        PlaceholderPanel(
            title = stringResource(R.string.model_panel_title),
            description = stringResource(R.string.model_screen_panel_description),
        ) {
            BuildingWireframe(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/**
 * The home screen's hero in a release build: the reserved area the model will
 * occupy, tappable through to the model screen. The release half of the
 * variant-specific declaration; `src/debug` draws the renderer here.
 */
@Composable
internal fun HomeModelHero(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BuildingWireframe(modifier = Modifier.padding(bottom = 16.dp))
            Text(
                text = stringResource(R.string.model_panel_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
