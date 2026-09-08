package com.buildplan.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.buildplan.app.ui.navigation.AppDrawerSheet
import com.buildplan.app.ui.navigation.AppSection
import com.buildplan.app.ui.navigation.BuildPlanNavHost
import com.buildplan.app.ui.workspace.LocalMotionPolicy
import com.buildplan.app.ui.workspace.rememberSystemMotionPolicy
import kotlinx.coroutines.launch

/**
 * Application frame: a modal drawer that is the launcher for every section,
 * and the sections themselves.
 *
 * There is no app-wide top bar. The workspace — the start screen — is the
 * house edge to edge with its own minimal chrome, and a bar above it would
 * make the house content inside a frame again. The other sections carry
 * their own bar with the drawer button; the drawer is the one thing shared.
 */
@Composable
fun BuildPlanApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentSection = AppSection.fromRoute(backStackEntry?.destination?.route)

    val motion = rememberSystemMotionPolicy()
    // The drawer is Material's and animates on its own; under "Remove
    // animations" it is the one slide left, so it is snapped instead.
    fun openDrawer() = scope.launch { if (motion.reduced) drawerState.snapTo(DrawerValue.Open) else drawerState.open() }
    fun closeDrawer() = scope.launch { if (motion.reduced) drawerState.snapTo(DrawerValue.Closed) else drawerState.close() }

    CompositionLocalProvider(LocalMotionPolicy provides motion) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                // System Back closes an open drawer before it may leave the
                // screen. Composed inside the drawer content, so it sits above
                // the sections' own back handlers whenever the drawer is open.
                BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
                AppDrawerSheet(
                    currentSection = currentSection,
                    onSectionClick = { section ->
                        closeDrawer()
                        if (section != currentSection) navController.navigateToSection(section)
                    },
                )
            },
        ) {
            BuildPlanNavHost(
                navController = navController,
                onOpenDrawer = { openDrawer() },
                onNavigateToSection = { section ->
                    if (section != currentSection) navController.navigateToSection(section)
                },
            )
        }
    }
}

/**
 * The one way a section is entered, from the drawer or from a link in the
 * workspace: the start destination stays on the back stack, and a section
 * already open is brought back rather than stacked again.
 */
private fun NavHostController.navigateToSection(section: AppSection) {
    navigate(section.route) {
        popUpTo(AppSection.Start.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
