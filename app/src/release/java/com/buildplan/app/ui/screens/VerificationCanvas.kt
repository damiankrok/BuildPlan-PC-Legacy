package com.buildplan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
    chromeInsets: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // The study fills the canvas; the sentence sits in the corner the chrome leaves.
    //
    // Centred on the whole screen it landed behind the rail, and behind the question panel too:
    // at a glass pane's alpha the words underneath keep showing through, so two texts shared the
    // same lines and neither could be read. The free area between the panels is a narrow column,
    // which is the wrong shape for a paragraph, so the line goes to the top of it and stays short.
    //
    // There used to be a second line here naming the highlighted subject. It is gone on purpose:
    // the question panel's own header already names the subject, and this one had no names for
    // anything but a room — an opening fell through to its candidate id and printed
    // "Pytanie dotyczy: f0-o5, f0-o7" at a person who has never heard of f0-o5.
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(chromeInsets)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BuildingWireframe(modifier = Modifier.padding(bottom = 20.dp))
            Text(
                text = stringResource(R.string.verify_canvas_reserved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
