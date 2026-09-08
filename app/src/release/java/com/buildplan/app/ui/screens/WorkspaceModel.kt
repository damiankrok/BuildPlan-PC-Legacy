package com.buildplan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.components.BuildingWireframe
import com.buildplan.app.ui.components.WorkspaceScrims
import com.buildplan.app.ui.workspace.WorkspaceChromeState

/**
 * The release build's canvas: the reserved study where the house will be.
 *
 * The renderer spike is deliberately debug-only, so a release build shows the
 * same quiet drawing it always has — now edge to edge, with the workspace's
 * chrome around it and no tool rail, because there is nothing yet to point
 * the tools at. This is the release half of a variant-specific declaration;
 * `src/debug` has the other.
 */
@Composable
internal fun WorkspaceModel(
    chrome: WorkspaceChromeState,
    safeArea: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(safeArea)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BuildingWireframe(modifier = Modifier.padding(bottom = 20.dp))
            Text(
                text = stringResource(R.string.model_reserved_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        WorkspaceScrims(modifier = Modifier.fillMaxSize())
    }
}
