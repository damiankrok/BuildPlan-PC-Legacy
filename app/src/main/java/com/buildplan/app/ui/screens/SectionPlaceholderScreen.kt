package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.ui.components.PlaceholderPanel
import com.buildplan.app.ui.components.ScreenIntro
import com.buildplan.app.ui.navigation.AppSection

/** Shared body for modules that have not been implemented yet. */
@Composable
fun SectionPlaceholderScreen(
    section: AppSection,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenIntro(description = stringResource(section.summaryRes))
        PlaceholderPanel(
            title = stringResource(R.string.placeholder_module_title),
            description = stringResource(R.string.placeholder_module_description),
            modifier = Modifier.heightIn(min = 180.dp),
        )
    }
}
