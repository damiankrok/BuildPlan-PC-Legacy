package com.buildplan.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.components.BuildingWireframe
import com.buildplan.app.ui.components.MetricCard
import com.buildplan.app.ui.components.PlaceholderPanel
import com.buildplan.app.ui.components.ScreenIntro

/**
 * UI placeholder only. These entries are not domain objects and there is no
 * data source behind them: every value renders as an em dash until real
 * project, cost and stage data exists.
 */
private data class PlaceholderMetric(
    @param:StringRes val labelRes: Int,
    @param:StringRes val hintRes: Int,
)

private val PlaceholderMetrics = listOf(
    PlaceholderMetric(R.string.metric_spent, R.string.metric_spent_hint),
    PlaceholderMetric(R.string.metric_budget, R.string.metric_budget_hint),
    PlaceholderMetric(R.string.metric_stage, R.string.metric_stage_hint),
    PlaceholderMetric(R.string.metric_last_cost, R.string.metric_last_cost_hint),
)

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenIntro(
            title = stringResource(R.string.dashboard_project_title),
            description = stringResource(R.string.dashboard_project_description),
        )

        MetricGrid()

        // The building model is the visual centre of the product, so it gets
        // the largest area on the dashboard.
        PlaceholderPanel(
            title = stringResource(R.string.model_panel_title),
            description = stringResource(R.string.model_panel_description),
            modifier = Modifier.heightIn(min = 280.dp),
        ) {
            BuildingWireframe(modifier = Modifier.padding(bottom = 24.dp))
        }

        PlaceholderPanel(
            title = stringResource(R.string.dashboard_recent_costs_title),
            description = stringResource(R.string.dashboard_recent_costs_description),
            modifier = Modifier.heightIn(min = 140.dp),
        )

        PlaceholderPanel(
            title = stringResource(R.string.dashboard_upcoming_stages_title),
            description = stringResource(R.string.dashboard_upcoming_stages_description),
            modifier = Modifier.heightIn(min = 140.dp),
        )
    }
}

/** Two columns on a phone, four on a wider screen. */
@Composable
private fun MetricGrid(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 4 else 2
        val placeholderValue = stringResource(R.string.placeholder_value)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlaceholderMetrics.chunked(columns).forEach { rowMetrics ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowMetrics.forEach { metric ->
                        MetricCard(
                            label = stringResource(metric.labelRes),
                            value = placeholderValue,
                            hint = stringResource(metric.hintRes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowMetrics.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
