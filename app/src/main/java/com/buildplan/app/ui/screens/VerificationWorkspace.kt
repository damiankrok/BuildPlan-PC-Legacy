package com.buildplan.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
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
import com.buildplan.app.ui.theme.GlassTintOpaque
import com.buildplan.app.ui.theme.ScrimModal
import com.buildplan.app.ui.workspace.LocalMotionPolicy
import java.util.Locale
import kotlinx.coroutines.delay

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

    // Back closes the summary, then lets go of the active question, then hands over.
    //
    // Enabled only while there is something of this screen's own to close. Left permanently on,
    // it would swallow the leaf case as well, and the system's back preview never plays for a
    // screen that intercepts every gesture — the workspace would be the one place in the app
    // where back stops showing where it goes.
    BackHandler(enabled = ui.showSummary || ui.activeQuestionId != null) {
        if (ui.showSummary) onShowSummary(false) else onSelectQuestion(null)
    }

    // The change notice takes itself away.
    //
    // It is a notice, not a dialogue: nothing waits on it and the decision it reports is already
    // applied. Left up until dismissed it becomes permanent furniture over the rail after the
    // first of forty-eight answers, and the undo it offers stops meaning "the last thing you did".
    LaunchedEffect(ui.lastChange) {
        if (ui.lastChange != null) {
            delay(ChangeNoticeMillis)
            onDismissChange()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The canvas fills the screen — that is the whole point of the workspace — but it is told
        // where the chrome sits, because anything it draws in *words* has to stay readable. The
        // release build's caption names the highlighted subject, and centred on the full screen it
        // lands under the rail: two texts in one place, both legible through the glass, neither
        // readable. The 3D canvas ignores this and keeps its own framing.
        VerificationCanvas(
            candidate = ui.verified.effectiveCandidate,
            highlighted = ui.highlighted,
            chromeInsets = PaddingValues(
                top = safeArea.calculateTopPadding(),
                end = RailWidth + WorkspaceLayout.EdgeInset * 2,
                bottom = insets.calculateBottomPadding() + WorkspaceLayout.EdgeInset,
            ),
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
                // Staged, not crossfaded, and clipped.
                //
                // This runs on the screen's most frequent action — every answer advances to the
                // next question — and two overlapping fades on two dense Polish paragraphs put
                // both of them on the same lines for the length of the overlap. The outgoing pane
                // leaves first and the incoming one waits for it. Clipping is on and the content
                // is anchored at the bottom because the container's bottom edge is what is pinned:
                // with clipping off, a full-height panel was drawn below the screen at the start
                // of the transition and slid up, which is a motion nobody designed.
                transitionSpec = {
                    (fadeIn(motion.enterDelayed()) togetherWith fadeOut(motion.exit()))
                        .using(SizeTransform(clip = true) { _, _ -> motion.enter() })
                },
                contentAlignment = Alignment.BottomStart,
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

        // The notice stays in the canvas column, out of the rail's.
        //
        // Centred on the screen it started 16 dp *inside* the rail, and a glass pane is solid to
        // touch by design — so it did not merely print over the top two questions, it ate the taps
        // meant for them. Chrome that floats over other chrome has to be laid out against it.
        AnimatedVisibility(
            visible = ui.lastChange != null,
            enter = fadeIn(motion.enter()),
            exit = fadeOut(motion.exit()),
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = WorkspaceLayout.EdgeInset, end = RailWidth + WorkspaceLayout.EdgeInset * 2)
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
            // A modal layer, and modal all the way down.
            //
            // Only the card used to be solid, so a tap beside it reached the canvas and — worse —
            // the question panel's own field and Apply button were still live underneath. A sheet
            // that claims the screen must own the screen: a scrim that consumes the gesture and
            // dismisses, and an opaque card so the rail's text does not read through it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScrimModal)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onShowSummary(false) },
                    ),
            ) {
                SummarySheet(ui = ui, onClose = { onShowSummary(false) }, safeArea = safeArea)
            }
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
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                    // A deferral is a decision about a question and the header used to hide it:
                    // defer one and the line still read "0 of 48 settled" while the summary,
                    // two taps away, reported one postponed.
                    text = if (ui.deferredCount > 0) {
                        stringResource(R.string.verify_progress_deferred, ui.answeredCount, ui.questions.size, ui.deferredCount)
                    } else {
                        stringResource(R.string.verify_progress, ui.answeredCount, ui.questions.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // Without this the noun was cut off mid-word at a 1.3 font scale and the
                    // sentence quietly lost its object: "Rozstrzygnięto 0 z 48".
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onShowSummary, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.verify_summary_open))
            }
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
    // Flattened once into headers and rows so the list can be virtualised and, more importantly,
    // addressed: answering a question advances to the next one, and with eight of forty-eight rows
    // on screen the row that just became active is usually not one of them. A rail whose "you are
    // here" is off-screen is a rail that has stopped saying where you are.
    val rows = remember(ui.byTier) {
        buildList {
            ui.byTier.forEach { (tier, questions) ->
                add(RailEntry.Header(tier, questions))
                questions.forEach { add(RailEntry.Question(it)) }
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.activeQuestionId, rows) {
        val index = rows.indexOfFirst { it is RailEntry.Question && it.question.id == ui.activeQuestionId }
        // Scroll only when the row is not already on screen. Scrolling to a row that is visible
        // moves the list for no reason — on the first question it pushed that tier's own heading
        // off the top, so the rail opened having already lost its first label.
        val visible = listState.layoutInfo.visibleItemsInfo
        val onScreen = visible.any { it.index == index } && visible.none { it.index == index && it.offset < 0 }
        if (index >= 0 && !onScreen) listState.animateScrollToItem(index)
    }

    GlassSurface(modifier = modifier.width(RailWidth).heightIn(max = maxHeight)) {
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows, key = { it.key }) { entry ->
                when (entry) {
                    is RailEntry.Header -> Text(
                        text = stringResource(entry.tier.labelRes(), entry.questions.count { ui.isAnswered(it.id) }, entry.questions.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .semantics { heading() }
                            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                    )
                    is RailEntry.Question -> QuestionRow(
                        question = entry.question,
                        selected = entry.question.id == ui.activeQuestionId,
                        answered = ui.isAnswered(entry.question.id),
                        deferred = ui.isDeferred(entry.question.id),
                        onClick = { onSelect(entry.question.id) },
                    )
                }
            }
        }
    }
}

/** The rail flattened: a tier heading, or a question under it. */
private sealed interface RailEntry {
    val key: String

    data class Header(val tier: PriorityTier, val questions: List<RootQuestion>) : RailEntry {
        override val key get() = "tier:${tier.name}"
    }

    data class Question(val question: RootQuestion) : RailEntry {
        override val key get() = question.id
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
    val accent = MaterialTheme.colorScheme.primary
    val state = when {
        answered -> stringResource(R.string.verify_state_answered)
        deferred -> stringResource(R.string.verify_state_deferred)
        else -> stringResource(R.string.verify_state_open)
    }
    val openState = stringResource(R.string.verify_state_active)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Which row is open is said three ways: a bar at the leading edge, a filled ground,
            // and the state a screen reader is given. It used to be said once, in the ink colour,
            // which is exactly the channel a person who cannot separate these two blues does not
            // have — and which TalkBack was never told about at all.
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent)
            .heightIn(min = 48.dp)
            // No contentDescription here. An explicit one replaces the merged descendants, which
            // silently deleted "78 wielkości" — the single most useful thing the row says — from
            // everything a screen reader announces.
            .semantics { stateDescription = if (selected) "$state, $openState" else state }
            .selectable(selected = selected, onClick = onClick)
            .padding(end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .background(if (selected) accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        // A filled disc when answered, a ring when open, a short bar when deferred — three shapes,
        // so the three states do not rest on two greys.
        Box(
            modifier = Modifier
                .size(width = 9.dp, height = if (deferred) 4.dp else 9.dp)
                .then(
                    when {
                        answered -> Modifier.background(accent, CircleShape)
                        deferred -> Modifier.background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp))
                        else -> Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    },
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = question.subjectLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                // Two lines, because at a 1.3 font scale one line turned every subject into a
                // prefix: "Grubość stropu nad partere…", "1 × brama garażow…".
                maxLines = 2,
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
    val chosen = ui.chosenOptionId(question.id)
    val scroll = rememberScrollState()
    // The prose scrolls; the title and the way to answer do not.
    //
    // With everything in one scroll container, the panel's own maximum height decided what a
    // person could see, and on a REQUIRED question it clipped the second option — the only way to
    // answer it — off the bottom with no scrollbar, no fade and no hint that anything was there.
    // The header went the other way and scrolled out of the top, leaving a paragraph with no
    // title and no close button. Both are chrome, so both sit outside the scroll.
    GlassSurface(modifier = Modifier.fillMaxWidth().widthIn(max = PanelMaxWidth)) {
        Column(modifier = Modifier.heightIn(max = maxHeight)) {
            PanelHeader(
                title = question.subjectLabel,
                supporting = stringResource(question.tier.chipRes()),
                onClose = onClose,
            )
            Box(modifier = Modifier.weight(1f, fill = false)) {
                Column(
                    modifier = Modifier.verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(question.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(question.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EvidenceLine(question)
                }
                // There is more below: say so where the text is cut, not in a scrollbar nobody
                // sees on a touch screen.
                if (scroll.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(20.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, GlassTintOpaque))),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (question.input) {
                    QuestionInput.CHOICE -> question.options.forEach { option ->
                        ChoiceRow(
                            label = option.label,
                            evidence = option.evidence,
                            current = option.isCurrent,
                            chosen = option.id == chosen,
                            onClick = { onChoose(question.id, option.id) },
                        )
                    }
                    QuestionInput.LENGTH, QuestionInput.COUNT -> ValueEntry(question, onProvide, onConfirm)
                    QuestionInput.CONFIRM -> Unit
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (question.input == QuestionInput.CONFIRM && question.recomputes) {
                        Button(onClick = { onConfirm(question.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.verify_confirm))
                        }
                    }
                    if (question.tier != PriorityTier.REQUIRED) {
                        OutlinedButton(onClick = { onDefer(question.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.verify_defer))
                        }
                    }
                    if (answered || ui.isDeferred(question.id)) {
                        TextButton(onClick = { onReset(question.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.verify_reset))
                        }
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

/**
 * One reading the source permits, and whether the person took it.
 *
 * [current] is what the analyzer is carrying; [chosen] is what the person
 * decided. They are different facts and the row shows both, because a screen
 * that replaced "the analyzer read this" with "you picked this" would erase
 * the source history the decision is supposed to preserve. Answering a
 * question and coming back to it has to show the answer — a decision log
 * nobody can read is not a decision log.
 */
@Composable
private fun ChoiceRow(label: String, evidence: String, current: Boolean, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = chosen, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    chosen -> stringResource(R.string.verify_option_chosen, label)
                    current -> stringResource(R.string.verify_option_current, label)
                    else -> label
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
    val count = question.input == QuestionInput.COUNT
    val unit = if (count) stringResource(R.string.verify_unit_count) else stringResource(R.string.verify_unit_meter)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.verify_value_label, unit)) },
            // A metre needs a separator and `Number` does not offer one: on an IME that honours
            // the class strictly there is no comma key at all, and the value the rest of the panel
            // prints as 2,05 cannot be typed. Counts stay whole, so they keep the plain digits.
            keyboardOptions = KeyboardOptions(keyboardType = if (count) KeyboardType.Number else KeyboardType.Decimal),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { parsed?.let { onProvide(question.id, it) } },
                enabled = parsed != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.verify_apply))
            }
            question.assumedValue?.let { assumed ->
                OutlinedButton(onClick = { onConfirm(question.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    // A tread count is a whole number of steps. Printed through the length
                    // formatter it read "Potwierdź 17,00", which is not a number of stairs.
                    Text(stringResource(R.string.verify_confirm_assumption, if (count) fmtCount(assumed) else fmt(assumed)))
                }
            }
        }
    }
}

@Composable
private fun ChangeToast(text: String, canUndo: Boolean, onUndo: () -> Unit, onDismiss: () -> Unit) {
    // A rounded panel, not a pill, and opaque.
    //
    // A pill is a circle at both ends, which is right for one short line and wrong for three in a
    // narrow column — it inflated into a disc with the sentence floating in the middle of it. And
    // it sits over the canvas caption, so like every other second layer it stops being glass.
    GlassSurface(shape = GlassDefaults.PanelShape, tint = GlassDefaults.OpaqueTint) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canUndo) {
                    TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.verify_undo))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.verify_dismiss))
                }
            }
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
    val scroll = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize().padding(safeArea), contentAlignment = Alignment.Center) {
        // Opaque, because this pane sits over the rail and the question panel, and the clickable
        // scrim above must not carry its taps through to the card either.
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = PanelMaxWidth)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            tint = GlassDefaults.OpaqueTint,
        ) {
            Column(modifier = Modifier.heightIn(max = 560.dp)) {
                PanelHeader(title = stringResource(R.string.verify_summary_title), onClose = onClose)
                Column(
                    modifier = Modifier
                        .verticalScroll(scroll)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
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

/**
 * How wide the rail is, how tall the question pane may grow before it
 * scrolls, and how much of the free height it may take.
 *
 * The rail is narrower than it was because it was not slim: at 232 dp it took
 * more than half the width of the evidence phone, and a canvas reduced to a
 * strip beside a list is a two-pane form with a picture in it. The subject
 * gets two lines instead of one to pay for the loss.
 *
 * The panel's height budget scales with the font, because 30 % larger text in
 * the same window is 30 % less of the question visible, and a cap in dp is a
 * cap on how much a person who needs bigger type is allowed to read.
 */
private val RailWidth = 184.dp
private val PanelMaxWidth = 420.dp
private val QuestionPanelMaxHeight = 340.dp
private const val PanelHeightShare = 0.46f

/** How long the change notice stays up before taking itself away. */
private const val ChangeNoticeMillis = 6_000L

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

/**
 * A length, in the one decimal convention the whole screen uses.
 *
 * The minus is U+2212, matching the sign the analyzer writes into its own
 * sentences; an ASCII hyphen beside it in the same paragraph reads as two
 * different numbers formatted by two different programs, which it was.
 */
private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v).replace('.', ',').replace('-', '−')

/** A count: a whole number of things. "17,00 steps" is not a number of steps. */
private fun fmtCount(v: Double) = String.format(Locale.ROOT, "%d", Math.round(v))
