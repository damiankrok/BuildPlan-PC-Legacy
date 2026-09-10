package com.buildplan.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.analyzer.verification.PriorityTier
import com.buildplan.app.analyzer.verification.QuestionInput
import com.buildplan.app.analyzer.verification.RootQuestion
import com.buildplan.app.analyzer.verification.VerificationReadiness
import com.buildplan.app.ui.components.GlassDefaults
import com.buildplan.app.ui.components.GlassSurface
import com.buildplan.app.ui.components.PanelHeader
import com.buildplan.app.ui.components.WorkspaceLayout
import com.buildplan.app.ui.components.WorkspaceScrims
import com.buildplan.app.ui.workspace.LocalMotionPolicy
import java.util.Locale

/**
 * The verification workspace: the candidate model as the screen, the open
 * questions on the right edge, and the active one in a pane over the model.
 *
 * The shape follows STAGE-013H's workspace and for the same reason — the
 * house is the subject, and everything else is chrome that opens on request.
 * What is different is what the chrome is *for*: this screen asks a person a
 * bounded list of root questions, and every answer recomputes the candidate
 * rather than editing a number on screen.
 *
 * The rail is a list of decisions, never a list of quantities. Two hundred
 * rows resting on an assumption become a couple of dozen questions here, and
 * each one says how many rows it settles.
 */
@Composable
internal fun VerificationWorkspace(
    ui: VerificationUiState,
    onSelectQuestion: (String?) -> Unit,
    onChoose: (String, String) -> Unit,
    onProvide: (String, Double) -> Unit,
    onConfirm: (String) -> Unit,
    onDefer: (String) -> Unit,
    onReset: (String) -> Unit,
    onUndo: () -> Unit,
    onDismissChange: () -> Unit,
    onShowSummary: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionPolicy.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val safeArea = PaddingValues(
        start = WorkspaceLayout.EdgeInset,
        top = insets.calculateTopPadding() + WorkspaceLayout.TopChromeHeight + WorkspaceLayout.EdgeInset,
        end = WorkspaceLayout.EdgeInset,
        bottom = insets.calculateBottomPadding() + WorkspaceLayout.EdgeInset,
    )

    // Back closes the summary, then lets go of the active question, then leaves.
    BackHandler(enabled = true) {
        when {
            ui.showSummary -> onShowSummary(false)
            ui.activeQuestionId != null -> onSelectQuestion(null)
            else -> onClose()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        VerificationCanvas(
            candidate = ui.verified.effectiveCandidate,
            highlighted = ui.highlighted,
            modifier = Modifier.fillMaxSize(),
        )
        WorkspaceScrims(modifier = Modifier.fillMaxSize())

        VerificationTopChrome(
            ui = ui,
            onClose = onClose,
            onShowSummary = { onShowSummary(true) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = WorkspaceLayout.EdgeInset),
        )

        // The rail and the panel share one height budget rather than overlapping.
        //
        // Both are solid to touch — that is what keeps a drag off the model behind them — so a
        // panel drawn over the rail does not merely hide the questions under it, it swallows the
        // scroll that would reach them. Splitting the free height between the two is what makes
        // the whole list reachable while a question is open.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val free = maxHeight - safeArea.calculateTopPadding() - insets.calculateBottomPadding() - WorkspaceLayout.EdgeInset * 2
            val panelMax = minOf(QuestionPanelMaxHeight, free * PanelHeightShare)
            val railMax = free - panelMax - WorkspaceLayout.EdgeInset

            QuestionRail(
                ui = ui,
                onSelect = onSelectQuestion,
                maxHeight = railMax,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = safeArea.calculateTopPadding(), end = WorkspaceLayout.EdgeInset),
            )

            AnimatedContent(
                targetState = ui.active,
                transitionSpec = {
                    (fadeIn(motion.enter()) togetherWith fadeOut(motion.exit()))
                        .using(SizeTransform(clip = false) { _, _ -> motion.enter() })
                },
                label = "activeQuestion",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = WorkspaceLayout.EdgeInset)
                    .padding(bottom = insets.calculateBottomPadding() + WorkspaceLayout.EdgeInset),
            ) { question ->
                if (question != null) {
                    QuestionPanel(
                        ui = ui,
                        question = question,
                        maxHeight = panelMax,
                        onChoose = onChoose,
                        onProvide = onProvide,
                        onConfirm = onConfirm,
                        onDefer = onDefer,
                        onReset = onReset,
                        onClose = { onSelectQuestion(null) },
                    )
                } else {
                    Spacer(Modifier.size(0.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = ui.lastChange != null,
            enter = fadeIn(motion.enter()),
            exit = fadeOut(motion.exit()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = WorkspaceLayout.TopChromeHeight + WorkspaceLayout.EdgeInset * 2),
        ) {
            ChangeToast(text = ui.lastChange.orEmpty(), canUndo = ui.canUndo, onUndo = onUndo, onDismiss = onDismissChange)
        }

        AnimatedVisibility(
            visible = ui.showSummary,
            enter = fadeIn(motion.enter()),
            exit = fadeOut(motion.exit()),
            modifier = Modifier.fillMaxSize(),
        ) {
            SummarySheet(ui = ui, onClose = { onShowSummary(false) }, safeArea = safeArea)
        }
    }
}

@Composable
private fun VerificationTopChrome(
    ui: VerificationUiState,
    onClose: () -> Unit,
    onShowSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.heightIn(min = WorkspaceLayout.TopChromeHeight), shape = GlassDefaults.PillShape) {
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.verify_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = 200.dp)) {
                Text(
                    text = stringResource(R.string.verify_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.verify_progress, ui.answeredCount, ui.questions.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onShowSummary) { Text(stringResource(R.string.verify_summary_open)) }
        }
    }
}

/**
 * The rail: every root decision, grouped by how much it settles.
 *
 * Icon-free and deliberately terse — one line a question — because the rail is
 * a map of the work, not the work. The tier is carried by a word as well as a
 * colour, and the answered state by a mark as well as ink, so neither is told
 * by colour alone.
 */
@Composable
private fun QuestionRail(
    ui: VerificationUiState,
    onSelect: (String) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.width(232.dp).heightIn(max = maxHeight)) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            ui.byTier.forEach { (tier, questions) ->
                Text(
                    text = stringResource(tier.labelRes(), questions.count { ui.isAnswered(it.id) }, questions.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                )
                questions.forEach { question ->
                    QuestionRow(
                        question = question,
                        selected = question.id == ui.activeQuestionId,
                        answered = ui.isAnswered(question.id),
                        deferred = ui.isDeferred(question.id),
                        onClick = { onSelect(question.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionRow(
    question: RootQuestion,
    selected: Boolean,
    answered: Boolean,
    deferred: Boolean,
    onClick: () -> Unit,
) {
    val motion = LocalMotionPolicy.current
    val accent = MaterialTheme.colorScheme.primary
    val ink by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        animationSpec = motion.settle(),
        label = "questionInk",
    )
    val state = when {
        answered -> stringResource(R.string.verify_state_answered)
        deferred -> stringResource(R.string.verify_state_deferred)
        else -> stringResource(R.string.verify_state_open)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = question.subjectLabel
                stateDescription = state
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A filled mark when answered, a ring when open, a half-height bar when deferred.
        Box(
            modifier = Modifier
                .size(9.dp)
                .then(
                    when {
                        answered -> Modifier.background(accent, CircleShape)
                        deferred -> Modifier.background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(1.dp))
                        else -> Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    },
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = question.subjectLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (question.affectedCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.verify_affects, question.affectedCount, question.affectedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The active question: what is being asked, why it matters, what the analyzer
 * read, and the ways to answer.
 *
 * The evidence line is the point of the panel. A person is being asked to
 * commit to a number, and the screen owes them what the analyzer saw, how far
 * it trusted it, and how many quantities move — never a bare prompt.
 */
@Composable
private fun QuestionPanel(
    ui: VerificationUiState,
    question: RootQuestion,
    maxHeight: androidx.compose.ui.unit.Dp,
    onChoose: (String, String) -> Unit,
    onProvide: (String, Double) -> Unit,
    onConfirm: (String) -> Unit,
    onDefer: (String) -> Unit,
    onReset: (String) -> Unit,
    onClose: () -> Unit,
) {
    val answered = ui.isAnswered(question.id)
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            PanelHeader(
                title = question.subjectLabel,
                supporting = stringResource(question.tier.chipRes()),
                onClose = onClose,
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(question.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(question.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                EvidenceLine(question)

                when (question.input) {
                    QuestionInput.CHOICE -> question.options.forEach { option ->
                        ChoiceRow(
                            label = option.label,
                            evidence = option.evidence,
                            current = option.isCurrent,
                            onClick = { onChoose(question.id, option.id) },
                        )
                    }
                    QuestionInput.LENGTH, QuestionInput.COUNT -> ValueEntry(question, onProvide, onConfirm)
                    QuestionInput.CONFIRM -> Unit
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (question.input == QuestionInput.CONFIRM && question.recomputes) {
                        Button(onClick = { onConfirm(question.id) }) { Text(stringResource(R.string.verify_confirm)) }
                    }
                    if (question.tier != PriorityTier.REQUIRED) {
                        OutlinedButton(onClick = { onDefer(question.id) }) { Text(stringResource(R.string.verify_defer)) }
                    }
                    if (answered || ui.isDeferred(question.id)) {
                        TextButton(onClick = { onReset(question.id) }) { Text(stringResource(R.string.verify_reset)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceLine(question: RootQuestion) {
    val evidence = question.evidence
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.verify_evidence_reading, evidence.analyzerReading.ifBlank { stringResource(R.string.verify_evidence_none) }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        question.currentAssumption?.let {
            Text(
                text = stringResource(R.string.verify_evidence_assumption, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (question.affectedCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.verify_affects_long, question.affectedCount, question.affectedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChoiceRow(label: String, evidence: String, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (current) stringResource(R.string.verify_option_current, label) else label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A number, or the analyzer's assumption confirmed as it stands.
 *
 * The field starts empty when the analyzer has nothing to offer. Pre-filling
 * it with a plausible-looking value is how an invented number gets confirmed
 * by a person who trusted the field.
 */
@Composable
private fun ValueEntry(question: RootQuestion, onProvide: (String, Double) -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(question.id) { mutableStateOf("") }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val unit = if (question.input == QuestionInput.COUNT) stringResource(R.string.verify_unit_count) else stringResource(R.string.verify_unit_meter)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.verify_value_label, unit)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { parsed?.let { onProvide(question.id, it) } }, enabled = parsed != null) {
                Text(stringResource(R.string.verify_apply))
            }
            question.assumedValue?.let { assumed ->
                OutlinedButton(onClick = { onConfirm(question.id) }) {
                    Text(stringResource(R.string.verify_confirm_assumption, fmt(assumed)))
                }
            }
        }
    }
}

@Composable
private fun ChangeToast(text: String, canUndo: Boolean, onUndo: () -> Unit, onDismiss: () -> Unit) {
    GlassSurface(shape = GlassDefaults.PillShape) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            if (canUndo) TextButton(onClick = onUndo) { Text(stringResource(R.string.verify_undo)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.verify_dismiss)) }
        }
    }
}

/**
 * What has been settled and what has not.
 *
 * Deliberately not a score. The readiness line says what remains open in
 * words, and the copy never claims the model is correct — only that it is
 * verified far enough to work on.
 */
@Composable
private fun SummarySheet(ui: VerificationUiState, onClose: () -> Unit, safeArea: PaddingValues) {
    val summary = ui.verified.summary
    val burden = ui.burden
    Box(modifier = Modifier.fillMaxSize().padding(safeArea), contentAlignment = Alignment.Center) {
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                PanelHeader(title = stringResource(R.string.verify_summary_title), onClose = onClose)
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val readiness = when (ui.verified.readiness) {
                        VerificationReadiness.NEEDS_REQUIRED_INPUT -> R.string.verify_readiness_required
                        VerificationReadiness.READY_FOR_OWNER_REVIEW -> R.string.verify_readiness_review
                        VerificationReadiness.READY_FOR_CANONICALIZATION_LATER -> R.string.verify_readiness_ready
                    }
                    Text(stringResource(readiness), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Line(stringResource(R.string.verify_summary_required, summary.requiredResolved, summary.required))
                    Line(stringResource(R.string.verify_summary_high, summary.highImpactResolved, summary.highImpact))
                    Line(stringResource(R.string.verify_summary_other, summary.recommendedResolved + summary.optionalResolved, summary.recommended + summary.optional))
                    Line(stringResource(R.string.verify_summary_deferred, summary.deferred))
                    Spacer(Modifier.height(4.dp))
                    Line(stringResource(R.string.verify_summary_confirmed, summary.quantitiesUserConfirmed))
                    Line(stringResource(R.string.verify_summary_derived, summary.quantitiesDerivedFromUser))
                    Line(stringResource(R.string.verify_summary_unsafe, summary.quantitiesStillUnsafe, summary.quantitiesTotal))
                    Line(stringResource(R.string.verify_summary_missing, summary.remainingMissing.size))
                    Spacer(Modifier.height(4.dp))
                    Line(stringResource(R.string.verify_summary_burden, burden.rawQuantitiesNeedingConfirmation, burden.rootDecisions))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.verify_summary_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** How tall the question pane may grow before it scrolls, and how much of the free height it may take. */
private val QuestionPanelMaxHeight = 340.dp
private const val PanelHeightShare = 0.46f

private fun PriorityTier.labelRes(): Int = when (this) {
    PriorityTier.REQUIRED -> R.string.verify_tier_required_count
    PriorityTier.HIGH_IMPACT -> R.string.verify_tier_high_count
    PriorityTier.RECOMMENDED -> R.string.verify_tier_recommended_count
    PriorityTier.OPTIONAL -> R.string.verify_tier_optional_count
}

private fun PriorityTier.chipRes(): Int = when (this) {
    PriorityTier.REQUIRED -> R.string.verify_tier_required
    PriorityTier.HIGH_IMPACT -> R.string.verify_tier_high
    PriorityTier.RECOMMENDED -> R.string.verify_tier_recommended
    PriorityTier.OPTIONAL -> R.string.verify_tier_optional
}

private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v).replace('.', ',')
