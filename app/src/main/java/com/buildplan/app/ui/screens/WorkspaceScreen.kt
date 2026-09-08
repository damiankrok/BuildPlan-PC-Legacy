package com.buildplan.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import com.buildplan.app.R
import com.buildplan.app.ui.components.TimelineRail
import com.buildplan.app.ui.components.TimelineRailDefaults
import com.buildplan.app.ui.components.WorkspaceLayout
import com.buildplan.app.ui.components.WorkspaceTopChrome
import com.buildplan.app.ui.navigation.AppSection
import com.buildplan.app.ui.workspace.WorkspaceSurface
import com.buildplan.app.ui.workspace.rememberWorkspaceChromeState

/**
 * The workspace: the house, edge to edge, with the chrome laid over it.
 *
 * This is the start screen and the model screen in one. There is no card
 * around the model and no dashboard around the card: the canvas is the
 * screen, the top edge names the project, the right edge holds the model's
 * tools, and the bottom edge is the timeline. Everything that is not the
 * house opens on request and closes with the back gesture.
 *
 * What fills the canvas is variant-specific — `WorkspaceModel` in
 * `src/debug` is the Filament renderer with its tool rail and inspector, in
 * `src/release` the reserved study without them — so this screen composes
 * the shell and never learns which renderer, if any, exists.
 */
@Composable
fun WorkspaceScreen(
    onOpenDrawer: () -> Unit,
    onOpenSection: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = rememberWorkspaceChromeState()

    // System Back closes whatever is open before it may leave the screen.
    BackHandler(enabled = chrome.surface != WorkspaceSurface.None) { chrome.dismiss() }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    // The area the model's own chrome may use: inside the system bars, under
    // the top chrome and above the collapsed timeline, in from both edges.
    val safeArea = PaddingValues(
        start = insets.calculateStartPadding(direction) + WorkspaceLayout.EdgeInset,
        top = insets.calculateTopPadding() + WorkspaceLayout.TopChromeHeight + WorkspaceLayout.EdgeInset,
        end = insets.calculateEndPadding(direction) + WorkspaceLayout.EdgeInset,
        bottom = insets.calculateBottomPadding() + TimelineRailDefaults.CollapsedHeight + WorkspaceLayout.EdgeInset * 2,
    )

    Box(modifier = modifier.fillMaxSize()) {
        WorkspaceModel(
            chrome = chrome,
            safeArea = safeArea,
            modifier = Modifier.fillMaxSize(),
        )

        WorkspaceTopChrome(
            title = stringResource(R.string.project_title),
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = WorkspaceLayout.EdgeInset),
        )

        TimelineRail(
            expanded = chrome.timelineExpanded,
            onToggle = chrome::toggleTimeline,
            onExpandChange = chrome::setTimelineExpanded,
            onPlan = { onOpenSection(AppSection.Timeline) },
            onAddCost = { onOpenSection(AppSection.Costs) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = WorkspaceLayout.EdgeInset, vertical = WorkspaceLayout.EdgeInset)
                .fillMaxWidth(),
        )
    }
}
