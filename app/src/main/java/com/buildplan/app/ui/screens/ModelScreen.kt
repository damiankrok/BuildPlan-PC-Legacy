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
import com.buildplan.app.ui.components.BuildingWireframe
import com.buildplan.app.ui.components.PlaceholderPanel
import com.buildplan.app.ui.components.ScreenIntro
import com.buildplan.app.ui.navigation.AppSection

/** Reserved space for the future building model. No renderer exists yet. */
@Composable
fun ModelScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenIntro(description = stringResource(AppSection.Model.summaryRes))
        PlaceholderPanel(
            title = stringResource(R.string.model_panel_title),
            description = stringResource(R.string.model_screen_panel_description),
            modifier = Modifier.heightIn(min = 340.dp),
        ) {
            BuildingWireframe(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
