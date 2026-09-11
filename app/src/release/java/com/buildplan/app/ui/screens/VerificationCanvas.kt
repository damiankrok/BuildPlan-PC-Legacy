package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.ui.components.AutomaticHouseCanvas

/** Actual candidate geometry in release, without the debug Filament dependency. */
@Composable
internal fun VerificationCanvas(
    candidate: ProjectAnalysisCandidate,
    highlighted: List<String>,
    chromeInsets: PaddingValues,
    modifier: Modifier = Modifier,
) {
    AutomaticHouseCanvas(candidate, modifier)
}
