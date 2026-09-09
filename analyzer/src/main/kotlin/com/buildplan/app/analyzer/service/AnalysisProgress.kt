package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.pipeline.AnalysisStage

/**
 * The coarse phases a caller may show while an analysis runs.
 *
 * Every entry here is a phase the service actually emits. The stage brief
 * listed a few finer ones — "fetching page", "discovering assets", "decoding
 * plans", "reading dimensions" — and they are not separate entries because
 * the pipeline does not signal them separately: fetching the page is part of
 * resolving the project, asset discovery is part of reading the page's facts,
 * decoding happens inside the asset download, and dimension reading happens
 * inside plan reconstruction. A phase that never fires is worse than a
 * coarser one that does, because a UI would sit on it waiting.
 *
 * Within [DOWNLOADING_ASSETS] the progress detail counts the individual
 * drawings, which is where the wait actually is.
 *
 * Labels are the caller's business: this is a stable enum, and the Polish
 * strings live in the app's resources.
 */
enum class AnalysisPhase {

    /** URL safety: scheme, credentials, port, host allowlist, address class. Before any socket. */
    VALIDATING_URL,

    /** Fetching the typed URL, following its redirects, and settling the canonical project page. */
    RESOLVING_PROJECT,

    /** Reading the page's published facts, room tables and the drawings it refers to. */
    READING_FACTS,

    /** Downloading and decoding those drawings. */
    DOWNLOADING_ASSETS,

    /** Tracing walls, regions and openings off each plan, and reading what dimension text is legible. */
    RECONSTRUCTING_GEOMETRY,

    /** Solving the roof over the top storey's outline. */
    SOLVING_ROOF,

    /** Closing the vertical chain: terrain, storey levels, knee wall, eave, ridge. */
    CLOSING_VERTICAL_CHAIN,

    /** Assembling floors, rooms, walls, openings and stairs into the candidate. */
    BUILDING_CANDIDATE,

    /** Taking off floor, wall-face, ceiling, roof and facade quantities. */
    CALCULATING_QUANTITIES,

    /** Comparing the candidate against what the page and the cost page publish. */
    VALIDATING,

    /** Scoring what is missing and turning it into questions for the user. */
    BUILDING_QUESTIONS,

    /** Writing the durable snapshot and assembling the report. */
    FINALIZING,
    ;

    companion object {

        /** How a pipeline stage shows up to a caller of the service. */
        fun of(stage: AnalysisStage): AnalysisPhase = when (stage) {
            AnalysisStage.RESOLVE -> RESOLVING_PROJECT
            AnalysisStage.READ_PAGE -> READING_FACTS
            AnalysisStage.FETCH_ASSETS -> DOWNLOADING_ASSETS
            AnalysisStage.PLANS -> RECONSTRUCTING_GEOMETRY
            AnalysisStage.ROOF -> SOLVING_ROOF
            AnalysisStage.VERTICAL -> CLOSING_VERTICAL_CHAIN
            AnalysisStage.CANDIDATE -> BUILDING_CANDIDATE
            AnalysisStage.QUANTITIES -> CALCULATING_QUANTITIES
            AnalysisStage.VALIDATE -> VALIDATING
            AnalysisStage.GAPS -> BUILDING_QUESTIONS
            AnalysisStage.SNAPSHOT -> FINALIZING
        }
    }
}

/**
 * One progress report.
 *
 * [fraction] is stated rather than invented: it is how many phases have been
 * *entered* out of how many exist, and nothing pretends it tracks elapsed
 * time. Plan reconstruction takes most of the wall clock on a real house, so
 * a caller that wants an honest bar should lean on [phase] and treat the
 * number as ordering, not duration.
 */
data class AnalysisProgress(
    val phase: AnalysisPhase,
    /** Diagnostic text from the pipeline, for a debug harness. Not a user-facing label. */
    val detail: String?,
) {
    val fraction: Double get() = (phase.ordinal + 1).toDouble() / AnalysisPhase.entries.size
}

/** What a running analysis emits: progress, then exactly one terminal outcome. */
sealed interface AnalysisEvent {
    data class Progress(val progress: AnalysisProgress) : AnalysisEvent
    data class Completed(val outcome: AnalysisOutcome) : AnalysisEvent
}
