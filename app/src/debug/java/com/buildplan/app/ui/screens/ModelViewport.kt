package com.buildplan.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.render.filament.FilamentModelViewport

/**
 * The debug build's model area: the STAGE-012 Filament renderer spike.
 *
 * This is the debug half of a variant-specific declaration — `src/release` has
 * the other, which keeps the reserved placeholder the screen has always shown.
 * The seam exists so that the spike can be reached from the real Model 3D screen
 * without the renderer, its native libraries or its scaffolding vocabulary
 * appearing anywhere in a release build.
 */
@Composable
internal fun ModelViewport(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.model_spike_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.model_spike_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilamentModelViewport(modifier = Modifier.padding(top = 8.dp))
        }
    }
}
