package com.buildplan.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R

/**
 * The launcher for every area of the app: the sections in their groups, the
 * active one marked, and settings on their own at the foot.
 *
 * Grouped rather than flat so that nine destinations read as three ideas —
 * the project, its money, the material around it — and the eye finds one in
 * a glance rather than by counting down a list.
 */
@Composable
fun AppDrawerSheet(
    currentSection: AppSection,
    onSectionClick: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier,
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp),
            )
            Text(
                text = stringResource(R.string.project_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 20.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            AppSectionGroup.entries.forEach { group ->
                Text(
                    text = stringResource(group.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 6.dp),
                )
                AppSection.inGroup(group).forEach { section ->
                    DrawerEntry(section, section == currentSection, onSectionClick)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        AppSection.ungrouped.forEach { section ->
            DrawerEntry(section, section == currentSection, onSectionClick)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerEntry(
    section: AppSection,
    selected: Boolean,
    onSectionClick: (AppSection) -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(stringResource(section.labelRes)) },
        selected = selected,
        onClick = { onSectionClick(section) },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
