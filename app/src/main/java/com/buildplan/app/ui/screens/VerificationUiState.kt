package com.buildplan.app.ui.screens

import com.buildplan.app.analyzer.verification.PriorityTier
import com.buildplan.app.analyzer.verification.RootQuestion
import com.buildplan.app.analyzer.verification.VerificationBurden
import com.buildplan.app.analyzer.verification.VerificationSession
import com.buildplan.app.analyzer.verification.VerifiedCandidate
import com.buildplan.app.analyzer.verification.VerifiedQuantity

/**
 * What the verification workspace shows.
 *
 * Derived from the session rather than stored beside it: the session is the
 * decision log and the report, and everything on screen is a pure function of
 * those two. So an undo cannot leave the screen holding a number the engine
 * no longer computes.
 */
data class VerificationUiState(
    val session: VerificationSession,
    val activeQuestionId: String?,
    val showSummary: Boolean = false,
    /** The last change, for the undo affordance; null once undone or dismissed. */
    val lastChange: String? = null,
) {
    val verified: VerifiedCandidate get() = session.verified
    val questions: List<RootQuestion> get() = session.questions
    val active: RootQuestion? get() = activeQuestionId?.let { id -> questions.firstOrNull { it.id == id } }
    val canUndo: Boolean get() = session.canUndo

    val burden: VerificationBurden
        get() = com.buildplan.app.analyzer.verification.RootQuestions.burden(session.report, questions)

    fun isAnswered(id: String): Boolean = session.isAnswered(id)
    fun isDeferred(id: String): Boolean = session.isDeferred(id)

    val answeredCount: Int get() = questions.count { isAnswered(it.id) }

    /** Questions grouped by tier, in tier order, keeping each tier's own priority order. */
    val byTier: List<Pair<PriorityTier, List<RootQuestion>>>
        get() = PriorityTier.entries.mapNotNull { tier ->
            questions.filter { it.tier == tier }.takeIf { it.isNotEmpty() }?.let { tier to it }
        }

    /** The quantities the active question would settle, with what they are now. */
    fun affected(question: RootQuestion): List<VerifiedQuantity> =
        question.affectedQuantityKeys.mapNotNull { key -> verified.quantity(key) }

    /** Candidate ids the active question points at, for the model to highlight. */
    val highlighted: List<String> get() = active?.subjectIds.orEmpty()
}
