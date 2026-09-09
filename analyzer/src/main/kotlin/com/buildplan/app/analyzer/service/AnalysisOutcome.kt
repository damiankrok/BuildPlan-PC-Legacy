package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.candidate.AnalysisIssue
import com.buildplan.app.analyzer.source.ResolutionStep
import com.buildplan.app.analyzer.source.UrlSafety

/**
 * What one analysis run ended as.
 *
 * The families are separate because a caller has to do different things with
 * them, and a single `Result<Report, Throwable>` would flatten exactly the
 * distinctions that matter: an unsupported host is the user's mistake and is
 * fixable by pasting a different link; a changed page is our problem and
 * fixable only by us; a partial candidate is a usable product output that the
 * user should see rather than an error to swallow.
 */
sealed interface AnalysisOutcome {

    /**
     * Everything the analyzer set out to read, it read.
     *
     * Not "everything is certain". A successful run on a real house still
     * carries questions and ambiguities — opening heights are not published
     * anywhere in these rasters, and two rooms of the same area cannot be told
     * apart — and those live on the report. Success means the pipeline reached
     * the end with the page's structure intact and its drawings decoded; it
     * has never meant that the house is settled.
     */
    data class Success(val report: ProjectAnalysisReport) : AnalysisOutcome

    /**
     * A candidate the user can act on, with a part of the work missing.
     *
     * Raised when something *structural* did not happen: a drawing the page
     * names would not decode, an optional signal of the page is gone, the roof
     * could not be solved. Never raised merely because the result is
     * uncertain — if ambiguity made a run partial, every run over a real house
     * would be partial and the word would stop meaning anything.
     */
    data class Partial(val report: ProjectAnalysisReport, val issues: List<AnalysisIssue>) : AnalysisOutcome

    /**
     * The URL is safe and well-formed but names a site, or a page, this build
     * cannot read.
     *
     * [supportedHosts] is data rather than prose because it is the useful half
     * of the answer and a caller has to be able to put it in its own sentence,
     * in its own language. [reason] is diagnostic English for a log or a bug
     * report; a UI that shows it to a person ends up with half a Polish
     * sentence and half an English one.
     */
    data class UnsupportedSource(
        val url: String,
        val host: String?,
        val reason: String,
        val supportedHosts: List<String> = emptyList(),
    ) : AnalysisOutcome

    /** The URL was refused before any socket opened. */
    data class UnsafeUrl(val url: String, val rejection: UrlSafety.Rejection, val detail: String) : AnalysisOutcome

    /** The network, or the site, did not give us the page. */
    data class FetchFailed(val url: String, val detail: String, val steps: List<ResolutionStep>) : AnalysisOutcome

    /**
     * The page no longer has the shape the adapter reads.
     *
     * Whatever *was* extracted is kept in [report], because a page that lost
     * its room tables may still have published its parameters, and that is
     * worth showing beside the warning. What must not happen is a near-empty
     * candidate returned as [Success]: a house with no rooms looks like a
     * result and is a silence.
     */
    data class SourceChanged(val health: AdapterHealth, val report: ProjectAnalysisReport?) : AnalysisOutcome

    /** The page was read, and the drawings it names could not be downloaded or decoded. */
    data class AssetFailure(val report: ProjectAnalysisReport, val failures: List<AssetFailureDetail>) : AnalysisOutcome

    /** The pipeline threw. A bug, or a page shaped in a way no rule anticipated. */
    data class AnalysisFailed(val phase: AnalysisPhase?, val detail: String) : AnalysisOutcome

    /** The caller cancelled. No report: a half-analysed candidate is not a partial one. */
    data object Cancelled : AnalysisOutcome

    /** The report this outcome carries, when it carries one. */
    val reportOrNull: ProjectAnalysisReport?
        get() = when (this) {
            is Success -> report
            is Partial -> report
            is SourceChanged -> report
            is AssetFailure -> report
            else -> null
        }
}

/** One drawing the page named that never became pixels. */
data class AssetFailureDetail(
    val url: String,
    val role: String,
    val detail: String,
)
