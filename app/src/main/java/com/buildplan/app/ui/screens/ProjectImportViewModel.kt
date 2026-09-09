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
        _state.update { it.copy(running = false, outcome = AnalysisOutcome.Cancelled) }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
