package com.buildplan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.ui.components.BuildingWireframe

/**
 * The release build's verification canvas: the reserved study, and in words
 * what the active question is about.
 *
 * The renderer is a debug-only spike, so a release build has no way to draw
 * the candidate. What it must not do is leave the question without a subject:
 * a person asked "which room is this?" needs to be told *which* region even
 * when nothing can be drawn, so the highlight becomes a caption.
 *
 * This is the release half of a variant-specific declaration; `src/debug` has
 * the other.
 */
@Composable
internal fun VerificationCanvas(
    candidate: ProjectAnalysisCandidate,
    highlighted: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BuildingWireframe(modifier = Modifier.padding(bottom = 20.dp))
            Text(
                text = stringResource(R.string.verify_canvas_reserved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (highlighted.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.verify_canvas_subject, subjectNames(candidate, highlighted)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** The names behind the candidate ids a question points at, so the caption is readable. */
private fun subjectNames(candidate: ProjectAnalysisCandidate, ids: List<String>): String =
    ids.map { id -> candidate.room(id)?.name ?: id }.distinct().joinToString(", ")
