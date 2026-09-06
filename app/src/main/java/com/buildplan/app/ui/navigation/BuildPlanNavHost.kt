package com.buildplan.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.buildplan.app.ui.screens.DashboardScreen
import com.buildplan.app.ui.screens.ModelScreen
import com.buildplan.app.ui.screens.SectionPlaceholderScreen

@Composable
fun BuildPlanNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppSection.Start.route,
        modifier = modifier,
    ) {
        composable(AppSection.Dashboard.route) { DashboardScreen() }
        composable(AppSection.Model.route) { ModelScreen() }
        composable(AppSection.Timeline.route) { SectionPlaceholderScreen(AppSection.Timeline) }
        composable(AppSection.Costs.route) { SectionPlaceholderScreen(AppSection.Costs) }
        composable(AppSection.Documents.route) { SectionPlaceholderScreen(AppSection.Documents) }
        composable(AppSection.Statistics.route) { SectionPlaceholderScreen(AppSection.Statistics) }
        composable(AppSection.Budget.route) { SectionPlaceholderScreen(AppSection.Budget) }
        composable(AppSection.Market.route) { SectionPlaceholderScreen(AppSection.Market) }
        composable(AppSection.Settings.route) { SectionPlaceholderScreen(AppSection.Settings) }
    }
}
