package com.buildplan.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.theme.Scrim

/**
 * The top edge of the workspace: the way into the rest of the app, and the
 * name of what is on screen. Nothing else — the house is the header, and
 * the state of the build is said once, by the timeline that owns it.
 *
 * One pane of glass for the button and the name: the same material as the
 * rail and the timeline, so the three edges read as one system, and a
 * ground the title stays legible on whatever the backdrop is.
 */
@Composable
fun WorkspaceTopChrome(
    title: String,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.heightIn(min = WorkspaceLayout.TopChromeHeight),
        shape = GlassDefaults.PillShape,
    ) {
        Row(
            modifier = Modifier.padding(end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.open_navigation),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * The soft shadow that settles the top edge of the canvas: dark enough at
 * the very edge for the status bar's light icons to read over a light study
 * backdrop, gone well before the house. Tonal layering, not an effect — the
 * renderer's image is untouched underneath, and the bottom edge needs none
 * because the timeline's own glass carries its text.
 */
@Composable
fun WorkspaceScrims(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WorkspaceLayout.TopScrimHeight)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Scrim.copy(alpha = 0.55f), Scrim.copy(alpha = 0.18f), Color.Transparent),
                    ),
                ),
        )
    }
}

/**
 * Where the chrome sits and how much room it leaves. One place, so the
 * canvas, the rail and the panels agree on it without measuring each other.
 */
object WorkspaceLayout {
    val EdgeInset: Dp = 16.dp
    val TopChromeHeight: Dp = 48.dp
    val TopScrimHeight: Dp = 128.dp
}
