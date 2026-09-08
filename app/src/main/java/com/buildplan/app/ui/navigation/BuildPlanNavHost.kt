package com.buildplan.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.buildplan.app.ui.screens.SectionPlaceholderScreen
import com.buildplan.app.ui.screens.WorkspaceScreen
import com.buildplan.app.ui.workspace.LocalMotionPolicy

/**
 * One `NavHost`, one route per section. Section changes cross-fade on the
 * shell's own timing rather than Navigation's default seven hundred
 * milliseconds, so leaving the workspace obeys the same policy as opening a
 * panel in it — and cuts when the system asks for no animation.
 */
@Composable
fun BuildPlanNavHost(
    navController: NavHostController,
    onOpenDrawer: () -> Unit,
    onNavigateToSection: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionPolicy.current
    NavHost(
        navController = navController,
        startDestination = AppSection.Start.route,
        modifier = modifier,
        enterTransition = { fadeIn(motion.enter()) },
        exitTransition = { fadeOut(motion.exit()) },
        popEnterTransition = { fadeIn(motion.enter()) },
        popExitTransition = { fadeOut(motion.exit()) },
    ) {
        composable(AppSection.Home.route) {
            WorkspaceScreen(onOpenDrawer = onOpenDrawer, onOpenSection = onNavigateToSection)
        }
        AppSection.entries.filter { it != AppSection.Home }.forEach { section ->
            composable(section.route) {
                SectionPlaceholderScreen(section = section, onOpenDrawer = onOpenDrawer)
            }
        }
    }
}
