package com.buildplan.app.analyzer.verification

import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.service.ProjectAnalysisReport

/**
 * One person's verification of one report: the report, the questions it
 * raised, and the log of what was decided — nothing else.
 *
 * The session is a value. Every operation returns a new session with one
 * more (or one fewer) log entry, and the verified candidate is always the
 * engine's answer for the log as it stands. Undo drops the last entry; reset
 * drops every entry for one question; neither touches the report, which is
 * never edited by anything.
 *
 * Kept free of Compose and Android so the whole workflow — priority,
 * recompute, lineage, undo, replay — runs in a JVM test.
 */
class VerificationSession private constructor(
    val report: ProjectAnalysisReport,
    val questions: List<RootQuestion>,
    val decisions: List<VerificationDecision>,
    private val nextId: Int,
    private val clock: () -> Long?,
) {

    /** The engine's answer for the log as it stands. Computed once per session value. */
    val verified: VerifiedCandidate by lazy { VerificationEngine.verify(report, questions, decisions) }

    val canUndo: Boolean get() = decisions.isNotEmpty()

    fun question(id: String): RootQuestion = questions.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("no root question $id in this session")

    /** The last decision for [questionId], or null when none was made. */
    fun lastDecision(questionId: String): VerificationDecision? = decisions.lastOrNull { it.questionId == questionId }

    fun isAnswered(questionId: String): Boolean = lastDecision(questionId)?.kind?.let { it != DecisionKind.DEFER } == true

    fun isDeferred(questionId: String): Boolean = lastDecision(questionId)?.kind == DecisionKind.DEFER

    /** Questions still open, in priority order, deferred ones last. */
    val open: List<RootQuestion>
        get() = questions.filter { !isAnswered(it.id) }.sortedBy { if (isDeferred(it.id)) 1 else 0 }

    /** The first open question after [questionId] in priority order, wrapping round; null when everything is answered. */
    fun nextOpenAfter(questionId: String?): RootQuestion? {
        val openIds = questions.filter { !isAnswered(it.id) && !isDeferred(it.id) }.ifEmpty { questions.filter { !isAnswered(it.id) } }
        if (openIds.isEmpty()) return null
        val index = questions.indexOfFirst { it.id == questionId }
        return (questions.drop(index + 1) + questions.take(index + 1)).firstOrNull { q -> openIds.any { it.id == q.id } }
    }

    // ---------------------------------------------------------------- deciding

    /** Picks [optionId] for a choice question. */
    fun choose(questionId: String, optionId: String): VerificationSession {
        val q = question(questionId)
        require(q.input == QuestionInput.CHOICE) { "$questionId is not a choice question" }
        val option = q.options.firstOrNull { it.id == optionId } ?: throw IllegalArgumentException("$questionId has no option $optionId")
        val kind = when {
            q.kind == RootQuestionKind.STAIR_INTERPRETATION && optionId == "not-stair" -> DecisionKind.REJECT_OBSERVATION
            q.kind == RootQuestionKind.VISUAL_CONFLICT && optionId == "candidate" -> DecisionKind.REJECT_OBSERVATION
            q.kind == RootQuestionKind.APPEARANCE_FEATURE && optionId == "skip" -> DecisionKind.REJECT_OBSERVATION
            q.kind == RootQuestionKind.ROOF_COVER && optionId == "skip" -> DecisionKind.REJECT_OBSERVATION
            q.kind == RootQuestionKind.ROOM_UNCLAIMED && optionId == "not-a-room" -> DecisionKind.REJECT_OBSERVATION
            option.isCurrent -> DecisionKind.CONFIRM_VALUE
            else -> DecisionKind.CHOOSE_ALTERNATIVE
        }
        return record(q, kind, optionId = optionId, targetKeys = directTargets(q, null), reason = option.label)
    }

    /**
     * Supplies a value for a length or count question, for every member of
     * the group or for the subset in [onlyIds].
     */
    fun provide(questionId: String, value: Double, onlyIds: Set<String>? = null): VerificationSession {
        val q = question(questionId)
        require(q.input == QuestionInput.LENGTH || q.input == QuestionInput.COUNT) { "$questionId does not take a value" }
        require(value.isFinite() && value > 0.0 || q.kind == RootQuestionKind.LEVEL_ASSUMPTION && value.isFinite()) { "a value for $questionId must be a finite number" }
        val unit = q.unit ?: MeasureUnit.METER
        // An opening height is missing from the report — the assumption stood in for it in the
        // deductions only — so supplying one provides rather than overrides. A level the chain
        // placed is a value, and a different number replaces it.
        val kind = if (q.kind == RootQuestionKind.OPENING_HEIGHT || q.kind == RootQuestionKind.STAIR_DETAIL || q.assumedValue == null) DecisionKind.PROVIDE_MISSING_VALUE else DecisionKind.REPLACE_VALUE
        return record(q, kind, value = value, unit = unit, targetKeys = directTargets(q, onlyIds), reason = "wartość podana przez użytkownika")
    }

    /** Accepts the analyzer's assumption or reading as the answer. */
    fun confirm(questionId: String, onlyIds: Set<String>? = null): VerificationSession {
        val q = question(questionId)
        require(q.input != QuestionInput.CHOICE) { "confirm a choice question by choosing its current option" }
        val kind = if (q.assumedValue != null) DecisionKind.CONFIRM_ASSUMPTION else DecisionKind.CONFIRM_VALUE
        return record(q, kind, value = q.assumedValue, unit = q.unit, targetKeys = directTargets(q, onlyIds), reason = "potwierdzone")
    }

    /** The analyzer saw something that is not there. */
    fun reject(questionId: String): VerificationSession {
        val q = question(questionId)
        return record(q, DecisionKind.REJECT_OBSERVATION, targetKeys = emptyList(), reason = "obserwacja odrzucona")
    }

    /** Not now. Refused for a REQUIRED question: readiness cannot be reached round it. */
    fun defer(questionId: String): VerificationSession {
        val q = question(questionId)
        require(q.tier != PriorityTier.REQUIRED) { "a REQUIRED question cannot be deferred: $questionId" }
        return record(q, DecisionKind.DEFER, targetKeys = emptyList(), reason = "odłożone")
    }

    /** Drops the last log entry. */
    fun undoLast(): VerificationSession =
        if (decisions.isEmpty()) this else VerificationSession(report, questions, decisions.dropLast(1), nextId, clock)

    /** Drops every log entry for [questionId], returning it to the analyzer's state. */
    fun reset(questionId: String): VerificationSession =
        VerificationSession(report, questions, decisions.filter { it.questionId != questionId }, nextId, clock)

    /** The same session over the same report with [decisions] replayed verbatim. */
    fun replay(decisions: List<VerificationDecision>): VerificationSession =
        VerificationSession(report, questions, decisions, (decisions.mapNotNull { it.id.removePrefix("d").toIntOrNull() }.maxOrNull() ?: 0) + 1, clock)

    private fun record(
        q: RootQuestion,
        kind: DecisionKind,
        targetKeys: List<String>,
        optionId: String? = null,
        value: Double? = null,
        unit: MeasureUnit? = null,
        reason: String = "",
    ): VerificationSession {
        val decision = VerificationDecision(
            id = "d$nextId",
            questionId = q.id,
            kind = kind,
            targetKeys = targetKeys,
            previousReading = previousReading(q),
            previousFidelity = q.evidence.fidelity,
            optionId = optionId,
            value = value,
            unit = unit,
            reason = reason,
            createdAtEpochMillis = clock(),
        )
        return VerificationSession(report, questions, decisions + decision, nextId + 1, clock)
    }

    private fun previousReading(q: RootQuestion): String =
        q.currentAssumption ?: q.options.firstOrNull { it.isCurrent }?.label ?: q.evidence.analyzerReading

    /** The quantity keys a decision writes directly: an opening family's height rows, a level's own row, a mass's top. */
    private fun directTargets(q: RootQuestion, onlyIds: Set<String>?): List<String> = when (q.kind) {
        RootQuestionKind.OPENING_HEIGHT -> {
            val ids = onlyIds?.filter { it in q.groupMemberIds } ?: q.groupMemberIds
            require(ids.isNotEmpty()) { "the chosen openings are not members of ${q.id}" }
            ids.map { "opening:$it:height" }
        }
        RootQuestionKind.LEVEL_ASSUMPTION -> when (q.id.substringAfterLast(':')) {
            "terrain" -> listOf("project:terrainLevel")
            "upperFloor" -> emptyList()
            "slab" -> emptyList()
            "atticCeiling" -> emptyList()
            else -> emptyList()
        }
        RootQuestionKind.FACADE_SCOPE -> listOf("project:facadeInsulation")
        else -> emptyList()
    }

    companion object {
        fun start(report: ProjectAnalysisReport, clock: () -> Long? = { null }): VerificationSession =
            VerificationSession(report, RootQuestions.of(report), emptyList(), 1, clock)
    }
}
