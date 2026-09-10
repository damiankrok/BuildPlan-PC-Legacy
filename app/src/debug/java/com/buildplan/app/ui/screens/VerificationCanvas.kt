package com.buildplan.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.lab.CandidatePreview
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.render.filament.FilamentCanvas
import com.buildplan.app.render.filament.rememberModelScene

/**
 * The debug build's verification canvas: the candidate on the renderer, with
 * the active question's subject picked out.
 *
 * The preview is the Lab's own [CandidatePreview] — a throwaway `Building`
 * with `cand-` identifiers that is never written anywhere — so the question
 * and the shape agree by construction: a question about `f0-r3` highlights
 * the element the same candidate produced.
 *
 * Rebuilt whenever the *effective* candidate changes, which is what makes a
 * decision visible: supply an opening height and the pane it draws changes
 * with the number.
 */
@Composable
internal fun VerificationCanvas(
    candidate: ProjectAnalysisCandidate,
    highlighted: List<String>,
    modifier: Modifier = Modifier,
) {
    val preview = remember(candidate) { CandidatePreview.of(candidate) }
    if (preview == null) {
        Box(modifier = modifier) {
            Text(
                text = stringResource(R.string.verify_canvas_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        return
    }
    key(preview) {
        val scene = rememberModelScene(preview)
        Box(modifier = modifier) {
            if (scene == null) {
                Text(
                    text = stringResource(R.string.verify_canvas_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                // One subject at a time: the renderer tints the selected element, and a question
                // about a family of openings picks out the first of them rather than none.
                LaunchedEffect(scene, highlighted) {
                    scene.select(highlighted.firstOrNull()?.let { BuildingElementId("cand-$it") })
                }
                FilamentCanvas(scene = scene, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
