package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.ui.components.ScreenIntro
import com.buildplan.app.ui.navigation.AppSection

/**
 * The building model screen.
 *
 * What fills the model area is variant-specific: a release build keeps the
 * reserved placeholder, and a debug build shows the STAGE-012 Filament renderer
 * spike. Both are declared as `ModelViewport` in their own source set, so this
 * screen does not know or care which renderer, if any, exists.
 */
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
        ModelViewport()
    }
}
