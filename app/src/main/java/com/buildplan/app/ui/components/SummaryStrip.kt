package com.buildplan.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R

/**
 * The state of the money in one strip: what has been spent, what the budget
 * is and how many costs there are, with one line and one action when all of
 * that is still empty.
 *
 * Three small figures in a row rather than four large cards. An empty
 * figure is one dash; the sentence under the row says why, and the button
 * leads to the place that fills it.
 */
@Composable
fun SummaryStrip(
    onAddCost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Figure(
                    label = stringResource(R.string.dashboard_summary_spent),
                    value = stringResource(R.string.placeholder_value),
                    modifier = Modifier.weight(1f),
                )
                FigureDivider()
                Figure(
                    label = stringResource(R.string.dashboard_summary_budget),
                    value = stringResource(R.string.placeholder_value),
                    modifier = Modifier.weight(1f),
                )
                FigureDivider()
                Figure(
                    label = stringResource(R.string.dashboard_summary_costs),
                    value = stringResource(R.string.placeholder_count_zero),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_summary_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddCost) {
                    Text(stringResource(R.string.dashboard_summary_open_costs))
                }
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun FigureDivider() {
    VerticalDivider(
        modifier = Modifier.height(32.dp).padding(end = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
