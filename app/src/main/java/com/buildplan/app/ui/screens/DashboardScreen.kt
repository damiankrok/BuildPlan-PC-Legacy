package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.components.SummaryStrip
import com.buildplan.app.ui.components.TimelineStrip
import com.buildplan.app.ui.navigation.AppSection

/**
 * The home screen: the house in the middle, the timeline under it, and the
 * state of the money in one line at the foot.
 *
 * Nothing here is a card for a section. The sections are reached from the
 * drawer; the home screen only shows the object the product is about and
 * what is next for it. With no project data yet, what is next is said in one
 * compact line each rather than in a grid of empty tiles.
 */
@Composable
fun DashboardScreen(
    onOpenSection: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProjectHeader(onOpenModel = { onOpenSection(AppSection.Model) })

        // The hero. It takes whatever height the strips below leave it, so
        // the house is the largest thing on the screen on every phone.
        HomeModelHero(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 240.dp),
            onOpen = { onOpenSection(AppSection.Model) },
        )

        TimelineStrip(onOpen = { onOpenSection(AppSection.Timeline) })

        SummaryStrip(onAddCost = { onOpenSection(AppSection.Costs) })
    }
}

@Composable
private fun ProjectHeader(onOpenModel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dashboard_project_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.dashboard_project_status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOpenModel) {
            Text(stringResource(R.string.dashboard_open_model))
        }
    }
}
