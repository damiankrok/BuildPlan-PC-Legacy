package com.buildplan.app.analyzer.pipeline

/**
 * How a caller asks a running analysis to stop.
 *
 * The pipeline is a long synchronous computation over bytes, so cancellation
 * is cooperative: the caller flips this signal and the pipeline notices at the
 * next checkpoint. Checkpoints sit at stage boundaries and inside the two
 * loops that can run for a while on their own — asset download and per-plan
 * analysis — which is close enough for a user who has left the screen and far
 * cheaper than making every pixel loop interruptible.
 *
 * Deliberately not a coroutine type: the core stays a plain JVM computation
 * and `service/` bridges structured concurrency onto this one method.
 */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        /** Never cancels. The default for tests and for the debug harness. */
        val NONE = CancellationSignal { false }
    }
}

/**
 * Thrown at a checkpoint when the caller has cancelled.
 *
 * A cancelled run produces no result at all — a half-analysed candidate is
 * not a partial candidate, it is an unfinished one, and the difference
 * matters because `Partial` is a product output the user may act on.
 */
class AnalysisCancelledException(val stage: AnalysisStage?) :
    RuntimeException("Analysis cancelled" + (stage?.let { " at $it" } ?: ""))

/** Throws [AnalysisCancelledException] when [signal] has been raised. */
internal fun CancellationSignal.checkpoint(stage: AnalysisStage? = null) {
    if (isCancelled()) throw AnalysisCancelledException(stage)
}
