package com.buildplan.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buildplan.app.R
import com.buildplan.app.analyzer.service.AdapterSeverity
import com.buildplan.app.analyzer.service.AdapterSignal
import com.buildplan.app.analyzer.service.AnalysisOutcome
import com.buildplan.app.analyzer.service.AnalysisPhase
import com.buildplan.app.analyzer.service.CachePolicy
import com.buildplan.app.analyzer.service.ProjectAnalysisReport
import com.buildplan.app.ui.components.PlaceholderPanel
import com.buildplan.app.ui.components.ScreenIntro
import com.buildplan.app.ui.components.SectionTopBar
import com.buildplan.app.ui.navigation.AppSection

/**
 * The product's seam onto the analyzer: a link in, a summary of what was found
 * out, and the questions that came with it.
 *
 * Deliberately a read-only summary. Choosing between two rooms of the same
 * area, confirming an assumed opening height and promoting any of this into
 * the building model is verification work, and verification is a stage of its
 * own — a half-built version of it here would be the thing people used, and it
 * would let unverified numbers into a budget.
 */
@Composable
fun ProjectImportScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { SectionTopBar(title = stringResource(AppSection.Import.labelRes), onOpenDrawer = onOpenDrawer) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenIntro(description = stringResource(AppSection.Import.summaryRes))

            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.running,
                label = { Text(stringResource(R.string.import_url_label)) },
            )
            Text(
                text = stringResource(R.string.import_supported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.analyze() },
                    enabled = !state.running && state.url.isNotBlank(),
                ) { Text(stringResource(R.string.import_run)) }
                if (state.running) {
                    OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.import_cancel)) }
                } else if (state.outcome != null) {
                    OutlinedButton(
                        onClick = { viewModel.analyze(CachePolicy.REFRESH) },
                        enabled = state.url.isNotBlank(),
                    ) { Text(stringResource(R.string.import_refresh)) }
                }
            }

            if (state.running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Column {
                        Text(
                            text = stringResource(state.phase?.let(::labelOf) ?: R.string.import_phase_validating_url),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            state.outcome?.let { Outcome(it) }
        }
    }
}

@Composable
private fun Outcome(outcome: AnalysisOutcome) {
    when (outcome) {
        is AnalysisOutcome.Success -> ReportSummary(outcome.report, R.string.import_result_complete)
        is AnalysisOutcome.Partial -> ReportSummary(outcome.report, R.string.import_result_partial, outcome.issues.map { it.message })
        is AnalysisOutcome.SourceChanged -> Problem(
            stringResource(
                R.string.import_error_source_changed,
                outcome.health.absent.map { stringResource(labelOf(it.signal)) }.joinToString(),
            ),
        )
        is AnalysisOutcome.AssetFailure -> Problem(stringResource(R.string.import_error_assets, outcome.failures.size))
        // The reason the analyzer gives is diagnostic English. What a person needs is the list of
        // addresses that would have worked, in a Polish sentence.
        is AnalysisOutcome.UnsupportedSource -> Problem(
            stringResource(R.string.import_error_unsupported, outcome.supportedHosts.joinToString()),
        )
        is AnalysisOutcome.UnsafeUrl -> Problem(stringResource(R.string.import_error_unsafe, outcome.rejection.name))
        is AnalysisOutcome.FetchFailed -> Problem(stringResource(R.string.import_error_fetch), outcome.detail)
        is AnalysisOutcome.AnalysisFailed -> Problem(stringResource(R.string.import_error_failed), outcome.detail)
        AnalysisOutcome.Cancelled -> Problem(stringResource(R.string.import_error_cancelled))
    }
}

@Composable
private fun ReportSummary(
    report: ProjectAnalysisReport,
    @StringRes headlineRes: Int,
    issues: List<String> = emptyList(),
) {
    val candidate = report.candidate
    val lines = buildList {
        add(stringResource(headlineRes))
        add(if (report.generation.servedFromCache) stringResource(R.string.import_result_from_cache) else stringResource(R.string.import_result_fresh))
        add(stringResource(R.string.import_stat_floors, candidate?.floors?.size ?: 0))
        add(stringResource(R.string.import_stat_rooms, candidate?.rooms?.size ?: 0))
        add(stringResource(R.string.import_stat_openings, candidate?.openings?.size ?: 0))
        add(stringResource(R.string.import_stat_quantities, report.quantityVerification.size))
        add(stringResource(R.string.import_stat_needs_confirmation, report.unsafeForCosting.size))
        if (report.adapterHealth.severity != AdapterSeverity.HEALTHY) {
            add(report.adapterHealth.degraded.map { stringResource(labelOf(it.signal)) }.joinToString())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaceholderPanel(
            title = stringResource(R.string.import_result_title, report.source?.title.orEmpty().ifBlank { report.identity.projectKey }),
            description = lines.joinToString("\n"),
        )
        Text(
            text = stringResource(R.string.import_candidate_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Listing(stringResource(R.string.import_section_questions, report.questions.size), report.questions.map { it.text })
        Listing(
            stringResource(R.string.import_section_ambiguities, report.ambiguities.size),
            report.ambiguities.map { ambiguity ->
                if (ambiguity.alternatives.isEmpty()) {
                    ambiguity.question
                } else {
                    ambiguity.question + "\n" + stringResource(
                        R.string.import_ambiguity_alternatives,
                        ambiguity.alternatives.joinToString { it.label },
                    )
                }
            },
        )
        if (issues.isNotEmpty()) Listing(stringResource(R.string.import_section_issues, issues.size), issues)
    }
}

@Composable
private fun Listing(title: String, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.import_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            items.forEach {
                Text(
                    text = "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A failure in the reader's language, with the analyzer's own words underneath
 * when there are any.
 *
 * The split matters: "Nie udało się pobrać strony." is the message, and the
 * exception text is evidence for whoever gets shown a screenshot. Splicing the
 * second into the first produces a sentence that is half Polish and half
 * English, which is what the first version of this screen did.
 */
@Composable
private fun Problem(message: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (!detail.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.import_error_detail, detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The Polish name of a page signal the adapter looks for. */
@StringRes
private fun labelOf(signal: AdapterSignal): Int = when (signal) {
    AdapterSignal.TITLE -> R.string.import_signal_title
    AdapterSignal.SCALARS -> R.string.import_signal_scalars
    AdapterSignal.ROOM_TABLES -> R.string.import_signal_room_tables
    AdapterSignal.PLAN_ASSETS -> R.string.import_signal_plan_assets
    AdapterSignal.COST_PAGE -> R.string.import_signal_cost_page
    AdapterSignal.SITE_TAGS -> R.string.import_signal_site_tags
}

/** The Polish name of a phase. The analyzer emits an enum; every label the user reads is a resource. */
@StringRes
private fun labelOf(phase: AnalysisPhase): Int = when (phase) {
    AnalysisPhase.VALIDATING_URL -> R.string.import_phase_validating_url
    AnalysisPhase.RESOLVING_PROJECT -> R.string.import_phase_resolving_project
    AnalysisPhase.READING_FACTS -> R.string.import_phase_reading_facts
    AnalysisPhase.DOWNLOADING_ASSETS -> R.string.import_phase_downloading_assets
    AnalysisPhase.RECONSTRUCTING_GEOMETRY -> R.string.import_phase_reconstructing_geometry
    AnalysisPhase.SOLVING_ROOF -> R.string.import_phase_solving_roof
    AnalysisPhase.CLOSING_VERTICAL_CHAIN -> R.string.import_phase_closing_vertical_chain
    AnalysisPhase.BUILDING_CANDIDATE -> R.string.import_phase_building_candidate
    AnalysisPhase.CALCULATING_QUANTITIES -> R.string.import_phase_calculating_quantities
    AnalysisPhase.VALIDATING -> R.string.import_phase_validating
    AnalysisPhase.BUILDING_QUESTIONS -> R.string.import_phase_building_questions
    AnalysisPhase.FINALIZING -> R.string.import_phase_finalizing
}
