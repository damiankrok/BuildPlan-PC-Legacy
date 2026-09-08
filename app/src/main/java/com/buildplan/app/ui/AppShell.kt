package com.buildplan.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.buildplan.app.R
import com.buildplan.app.ui.navigation.AppDrawerSheet
import com.buildplan.app.ui.navigation.AppSection
import com.buildplan.app.ui.navigation.BuildPlanNavHost
import kotlinx.coroutines.launch

/**
 * Application frame: a quiet top bar naming where the user is, and a modal
 * drawer that is the launcher for every section.
 *
 * The home screen is not a menu. It shows the house, the timeline under it
 * and the state of the money, and every other area is reached from the
 * drawer or from the one link on the home screen that leads into it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildPlanApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentSection = AppSection.fromRoute(backStackEntry?.destination?.route)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerSheet(
                currentSection = currentSection,
                onSectionClick = { section ->
                    scope.launch { drawerState.close() }
                    if (section != currentSection) navController.navigateToSection(section)
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (currentSection == AppSection.Start) {
                                stringResource(R.string.app_name)
                            } else {
                                stringResource(currentSection.labelRes)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.open_navigation),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            },
        ) { innerPadding ->
            BuildPlanNavHost(
                navController = navController,
                onNavigateToSection = { section ->
                    if (section != currentSection) navController.navigateToSection(section)
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * The one way a section is entered, from the drawer or from a link on the
 * home screen: the start destination stays on the back stack, and a section
 * already open is brought back rather than stacked again.
 */
private fun NavHostController.navigateToSection(section: AppSection) {
    navigate(section.route) {
        popUpTo(AppSection.Start.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
