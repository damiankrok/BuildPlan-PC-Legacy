package com.buildplan.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buildplan.app.analyzer.BuildPlanAnalyzer
import com.buildplan.app.analyzer.service.AnalysisEvent
import com.buildplan.app.analyzer.service.AnalysisOutcome
import com.buildplan.app.analyzer.service.AnalysisPhase
import com.buildplan.app.analyzer.service.AnalyzeProjectRequest
import com.buildplan.app.analyzer.service.CachePolicy
import com.buildplan.app.analyzer.service.ProjectAnalyzerService
import com.buildplan.app.analyzer.verification.VerificationSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the import screen shows.
 *
 * [phase] is the analyzer's own coarse phase rather than a percentage: the
 * work is not uniform — reconstructing two plans takes most of the wall clock
 * — so a bar filling smoothly would be a fiction. [outcome] is the analyzer's
 * typed result, unflattened, because the screen has to say different things
 * about an unsupported link and a page that changed shape.
 */
data class ProjectImportState(
    val url: String = "",
    val running: Boolean = false,
    val phase: AnalysisPhase? = null,
    val detail: String? = null,
    val outcome: AnalysisOutcome? = null,
    /**
     * The verification workspace, once a person has opened it on this run's
     * candidate. Null while the screen is the read-only summary.
     *
     * It lives here rather than in a screen of its own because it is *this*
     * analysis being verified: leaving the screen and coming back to a fresh
     * empty session would silently discard a person's decisions, and a second
     * view model would have to be handed the report to avoid that.
     */
    val verification: VerificationUiState? = null,
)

/**
 * Holds one analysis across configuration changes.
 *
 * The run lives in [viewModelScope], not in a composition: rotating the device
 * must not restart a two-minute download, and leaving the screen must cancel
 * it rather than leave it writing into a cache nobody is waiting for. The
 * service itself is process-wide and holds no Activity.
 *
 * This screen reads a result and writes nothing. The candidate it shows is not
 * saved into the building model, and no cost is created from its quantities —
 * that is a separate, user-verified step and it does not exist yet.
 */
class ProjectImportViewModel @JvmOverloads constructor(
    application: Application,
    private val service: ProjectAnalyzerService = BuildPlanAnalyzer.service(application),
) : AndroidViewModel(application) {

    // @JvmOverloads is load-bearing, not decoration. `viewModel()` falls back to
    // AndroidViewModelFactory, which reflects for a constructor taking exactly one
    // Application; a Kotlin default argument compiles to a synthetic constructor with a
    // different signature, so without the generated overload this class instantiates fine
    // everywhere except on a device, where it throws NoSuchMethodException the moment the
    // screen opens. ProjectImportViewModelTest holds the line.

    private val _state = MutableStateFlow(ProjectImportState())
    val state: StateFlow<ProjectImportState> = _state.asStateFlow()

    private var job: Job? = null

    fun onUrlChanged(url: String) {
        _state.update { it.copy(url = url) }
    }

    fun analyze(policy: CachePolicy = CachePolicy.PREFER_CACHE) {
        val url = _state.value.url.trim()
        if (url.isEmpty() || _state.value.running) return
        job?.cancel()
        _state.update { it.copy(running = true, phase = null, detail = null, outcome = null) }
        job = viewModelScope.launch {
            service.analyze(AnalyzeProjectRequest(url, policy)).collect { event ->
                when (event) {
                    is AnalysisEvent.Progress -> _state.update {
                        it.copy(phase = event.progress.phase, detail = event.progress.detail)
                    }
                    is AnalysisEvent.Completed -> _state.update {
                        it.copy(running = false, outcome = event.outcome)
                    }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, outcome = AnalysisOutcome.Cancelled, verification = null) }
    }

    // ------------------------------------------------------------ verification

    /**
     * Opens the verification workspace on the candidate this run produced.
     *
     * Does nothing when the run produced no candidate: there is nothing to
     * verify, and a workspace over an empty report would be a screen that
     * asks a person to confirm nothing.
     */
    fun startVerification() {
        val report = _state.value.outcome?.reportOrNull ?: return
        if (report.candidate == null) return
        val session = VerificationSession.start(report) { System.currentTimeMillis() }
        _state.update {
            it.copy(
                verification = VerificationUiState(
                    session = session,
                    activeQuestionId = session.questions.firstOrNull()?.id,
                ),
            )
        }
    }

    fun closeVerification() {
        _state.update { it.copy(verification = null) }
    }

    fun selectQuestion(id: String?) = updateVerification { it.copy(activeQuestionId = id, showSummary = false) }

    fun showSummary(show: Boolean) = updateVerification { it.copy(showSummary = show) }

    fun choose(questionId: String, optionId: String) = decide(questionId) { it.choose(questionId, optionId) }

    fun provide(questionId: String, value: Double, onlyIds: Set<String>? = null) =
        decide(questionId) { it.provide(questionId, value, onlyIds) }

    fun confirm(questionId: String) = decide(questionId) { it.confirm(questionId) }

    fun defer(questionId: String) = decide(questionId, advance = true) { it.defer(questionId) }

    /**
     * Applies one decision, then moves to the next open question.
     *
     * The advance is what keeps the workspace from asking a person to hunt for
     * their next task, and it is deliberately *after* the decision so the
     * change summary the screen shows is the one they just made.
     */
    private fun decide(questionId: String, advance: Boolean = true, action: (VerificationSession) -> VerificationSession) {
        updateVerification { ui ->
            val next = try {
                action(ui.session)
            } catch (e: IllegalArgumentException) {
                // A value the session refuses — an empty field, a member outside the family — is
                // a no-op rather than a crash; the field's own validation is what tells the user.
                return@updateVerification ui
            }
            ui.copy(
                session = next,
                activeQuestionId = if (advance) next.nextOpenAfter(questionId)?.id ?: questionId else questionId,
                lastChange = next.verified.changeSummary.firstOrNull(),
            )
        }
    }

    fun undo() = updateVerification { ui ->
        val next = ui.session.undoLast()
        ui.copy(session = next, lastChange = null, activeQuestionId = ui.activeQuestionId ?: next.questions.firstOrNull()?.id)
    }

    fun resetQuestion(questionId: String) = updateVerification { ui ->
        ui.copy(session = ui.session.reset(questionId), lastChange = null, activeQuestionId = questionId)
    }

    fun dismissChange() = updateVerification { it.copy(lastChange = null) }

    private fun updateVerification(transform: (VerificationUiState) -> VerificationUiState) {
        _state.update { state -> state.verification?.let { state.copy(verification = transform(it)) } ?: state }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
