package com.buildplan.app.analyzer.verification

import com.buildplan.app.analyzer.candidate.NormalizedBox
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.quantity.ProjectQuantities
import com.buildplan.app.analyzer.service.ReportIdentity
import com.buildplan.app.analyzer.service.VerificationState
import com.buildplan.app.analyzer.validate.ValidationFinding

/**
 * How urgently a root question needs a person.
 *
 * [REQUIRED] blocks readiness: identity and topology a cost target would
 * rest on. [HIGH_IMPACT] fans out into many quantities or changes what the
 * model looks like. [RECOMMENDED] is worth a minute. [OPTIONAL] is detail a
 * person may leave for later without anything downstream pretending it was
 * settled.
 */
enum class PriorityTier { REQUIRED, HIGH_IMPACT, RECOMMENDED, OPTIONAL }

/**
 * What a root question is about. Each kind names the *decision*, not the
 * quantity: one answer here settles every row that hangs off it.
 */
enum class RootQuestionKind {
    /** Two published rows fit one region; pick the row. */
    ROOM_IDENTITY,

    /** A traced region claims no row; pick the row it is, or say it is not a room. */
    ROOM_UNCLAIMED,

    /** A published row was placed nowhere; nothing on the plan can be offered. */
    ROOM_UNPLACED,

    /** A room without a proved outline: its surfaces stay open until a later tool. */
    ROOM_GEOMETRY,

    /** An open plan split proportionally to published areas; confirm the split. */
    OPEN_PLAN_BOUNDARY,

    /** A family of openings whose heights the source does not print. */
    OPENING_HEIGHT,

    /** One vertical level the chain assumed: terrain, upper floor, slab, attic ceiling. */
    LEVEL_ASSUMPTION,

    /** The ridge direction the solver assumed because the area could not tell. */
    ROOF_RIDGE_DIRECTION,

    /** A part of the footprint outside the main roof, given a flat roof at the storey level. */
    SECONDARY_MASS_ROOF,

    /** Evenly spaced lines: a flight of stairs or shelving. */
    STAIR_INTERPRETATION,

    /** Step count and direction of a stair the plan does not print legibly. */
    STAIR_DETAIL,

    /** Which scope of the envelope the published facade figure priced. */
    FACADE_SCOPE,

    /** A picture and the traced geometry disagree about something structural. */
    VISUAL_CONFLICT,

    /** A picture proposes a presentation feature the candidate does not carry. */
    APPEARANCE_FEATURE,

    /** The roof covering the pictures suggest. */
    ROOF_COVER,

    /** Facade features the pictures could not read; only a person can name them. */
    SIGNATURE_FEATURES,

    /** No dimension chain could be read; a stated width would check the calibration. */
    DIMENSION_CHECK,
}

/** What kind of answer the question takes. */
enum class QuestionInput {
    /** Pick one of [RootQuestion.options]. */
    CHOICE,

    /** A length in metres; the analyzer's assumption, if any, may be confirmed instead. */
    LENGTH,

    /** A whole number. */
    COUNT,

    /** Confirm the assumption or reject the observation; nothing to type. */
    CONFIRM,
}

/** One reading a choice question offers. Ids are stable across runs. */
data class QuestionOption(
    val id: String,
    val label: String,
    val evidence: String,
    /** The reading the candidate currently carries, so leaving it alone is a visible choice. */
    val isCurrent: Boolean = false,
)

/**
 * Where the evidence for a question is, so a screen can show it rather than
 * describe it.
 *
 * @property assetUrl the drawing or picture the question is about, when one is.
 * @property cropPx a pixel rectangle in that asset worth showing, when known.
 * @property cropNormalized the same as fractions of the asset, for pictures whose size the screen has to look up.
 */
data class QuestionEvidence(
    val assetUrl: String?,
    val cropPx: IntArray?,
    val cropNormalized: NormalizedBox?,
    /** What the analyzer read, in the reader's language. */
    val analyzerReading: String,
    val fidelity: FactFidelity?,
    /** The method behind the reading: provenance text, not a selector. */
    val method: String?,
) {
    override fun equals(other: Any?): Boolean = other is QuestionEvidence &&
        other.assetUrl == assetUrl && other.cropPx.contentEquals(cropPx) && other.cropNormalized == cropNormalized &&
        other.analyzerReading == analyzerReading && other.fidelity == fidelity && other.method == method

    override fun hashCode(): Int = listOf(assetUrl, cropPx?.toList(), cropNormalized, analyzerReading, fidelity, method).hashCode()
}

/**
 * One decision a person is asked to make, with everything that hangs off it.
 *
 * A root question is the unit of verification burden. It is *not* a quantity
 * row: answering it resolves [affectedQuantityKeys] at once, and the screen
 * shows that count so a person knows why the question is worth a minute.
 *
 * @property subjectIds candidate ids to highlight: room ids, opening ids, wall ids, `roof`.
 * @property text the question, in Polish.
 * @property why why it matters, in Polish.
 * @property currentAssumption what the model carries meanwhile, or null.
 * @property assumedValue the assumption as a number, when confirming it is a valid answer.
 * @property groupMemberIds for an [RootQuestionKind.OPENING_HEIGHT] family: every opening the answer may apply to.
 */
data class RootQuestion(
    val id: String,
    val kind: RootQuestionKind,
    val tier: PriorityTier,
    val input: QuestionInput,
    val subjectIds: List<String>,
    val subjectLabel: String,
    val floorId: String?,
    val text: String,
    val why: String,
    val currentAssumption: String?,
    val assumedValue: Double?,
    val unit: MeasureUnit?,
    val options: List<QuestionOption>,
    val affectedQuantityKeys: List<String>,
    val evidence: QuestionEvidence,
    /** The score the tier came from, kept so the order inside a tier is explainable. */
    val priorityScore: Double,
    val groupMemberIds: List<String> = emptyList(),
    /** Whether an answer changes geometry or quantities, or is only recorded for a later stage. */
    val recomputes: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "a root question needs an id" }
        require(text.isNotBlank()) { "a root question that asks nothing is not one" }
        require(input != QuestionInput.CHOICE || options.size >= 2) { "a choice question $id needs at least two options" }
    }

    val affectedCount: Int get() = affectedQuantityKeys.size
}

/** What a person did about a question. */
enum class DecisionKind {
    /** The analyzer's value is right as it stands. */
    CONFIRM_VALUE,

    /** The analyzer's value is replaced by the person's. */
    REPLACE_VALUE,

    /** One of the readings the source permitted was chosen. */
    CHOOSE_ALTERNATIVE,

    /** The assumption the analyzer made is accepted as the value. */
    CONFIRM_ASSUMPTION,

    /** The analyzer saw something that is not there. */
    REJECT_OBSERVATION,

    /** A value the source never gave, supplied by the person. */
    PROVIDE_MISSING_VALUE,

    /** Not now. The question stays open and is counted as such. */
    DEFER,
}

/**
 * One entry in the decision log.
 *
 * The analyzer's own reading is copied in at decision time so the log stands
 * on its own: undoing is dropping the entry, resetting is dropping every entry
 * for a question, and replaying the log on the original report gives the same
 * result every time. Nothing in the report is ever edited.
 *
 * @property targetKeys the candidate paths the decision applies to; for an
 *   opening family this is the subset the person chose (all, or one).
 * @property optionId the chosen option for [DecisionKind.CHOOSE_ALTERNATIVE].
 * @property value the number for a length, count or replacement.
 */
data class VerificationDecision(
    val id: String,
    val questionId: String,
    val kind: DecisionKind,
    val targetKeys: List<String>,
    val previousReading: String,
    val previousFidelity: FactFidelity?,
    val optionId: String? = null,
    val value: Double? = null,
    val unit: MeasureUnit? = null,
    val reason: String = "",
    val createdAtEpochMillis: Long? = null,
) {
    init {
        require(id.isNotBlank() && questionId.isNotBlank()) { "a decision needs its own id and its question's" }
        when (kind) {
            DecisionKind.CHOOSE_ALTERNATIVE -> require(!optionId.isNullOrBlank()) { "choosing needs an option id" }
            DecisionKind.REPLACE_VALUE, DecisionKind.PROVIDE_MISSING_VALUE -> {
                require(value != null && value.isFinite()) { "a provided value must be a finite number" }
                require(unit != null) { "a provided value needs a unit" }
            }
            else -> Unit
        }
    }
}

/**
 * How far a quantity may be trusted after verification. Richer than the
 * analyzer's [VerificationState] because a person has now been involved and
 * the ways they were involved differ.
 */
enum class VerifiedState {
    /** Printed by the source; nothing to verify. */
    SOURCE_FACT,

    /** Measured or computed from source values by a stated rule. */
    SOURCE_DERIVED,

    /** Rests on an assumption nobody has confirmed. */
    ASSUMPTION,

    /** Missing, uncertain or contradicted, and no decision touched it. */
    UNRESOLVED,

    /** A person confirmed this exact value. Only a decision can put a quantity here. */
    USER_CONFIRMED,

    /** Recomputed from roots a person confirmed; nobody looked at this number itself. */
    DERIVED_FROM_USER_CONFIRMED,

    /** A person replaced the analyzer's value with their own. */
    USER_OVERRIDDEN,

    /** The question this rests on was deferred; the value is the analyzer's and stays unverified. */
    DEFERRED,
    ;

    /** Whether a later stage may price this without a person looking first. */
    val safeForCosting: Boolean
        get() = this == SOURCE_FACT || this == SOURCE_DERIVED || this == USER_CONFIRMED ||
            this == DERIVED_FROM_USER_CONFIRMED || this == USER_OVERRIDDEN
}

/**
 * One quantity after verification: what it is now, what it was, and which
 * decisions it rests on.
 *
 * The lineage is the point. A costing stage that reads `wallFaceNet` has to
 * be able to say "this rests on decisions 7 and 12", and a screen that shows
 * a person the effect of their answer has to be able to say which rows moved.
 */
data class VerifiedQuantity(
    val key: String,
    val ownerId: String,
    val before: Measured,
    val after: Measured,
    val state: VerifiedState,
    /** Root question ids this quantity depends on, whether or not they were answered. */
    val rootQuestionIds: List<String>,
    /** Decisions that changed or confirmed this quantity. */
    val decisionIds: List<String>,
    val caveat: String?,
) {
    val changed: Boolean get() = before.value != after.value || before.fidelity != after.fidelity
}

/** Readiness of the verified candidate for what comes after this stage. */
enum class VerificationReadiness {
    /** A REQUIRED question is open. Nothing downstream may proceed. */
    NEEDS_REQUIRED_INPUT,

    /** Every REQUIRED question is answered; high-impact ones may still be open. */
    READY_FOR_OWNER_REVIEW,

    /** Every REQUIRED and HIGH_IMPACT question is answered. Canonicalisation is a later stage regardless. */
    READY_FOR_CANONICALIZATION_LATER,
}

/** The counts a final summary shows. Every number here is about root decisions, never rows. */
data class VerificationSummary(
    val rootQuestions: Int,
    val required: Int,
    val requiredResolved: Int,
    val highImpact: Int,
    val highImpactResolved: Int,
    val recommended: Int,
    val recommendedResolved: Int,
    val optional: Int,
    val optionalResolved: Int,
    val deferred: Int,
    val assumptionsConfirmed: Int,
    val assumptionsReplaced: Int,
    val userOverrides: Int,
    val observationsRejected: Int,
    val quantitiesTotal: Int,
    val quantitiesUserConfirmed: Int,
    val quantitiesDerivedFromUser: Int,
    val quantitiesStillUnsafe: Int,
    /** Quantity families (room, floor, project, opening) that at least one decision touched. */
    val quantityFamiliesAffected: List<String>,
    /** What the source never gave and no decision supplied. */
    val remainingMissing: List<String>,
) {
    val resolved: Int get() = requiredResolved + highImpactResolved + recommendedResolved + optionalResolved
    val open: Int get() = rootQuestions - resolved
}

/**
 * The analyzer's candidate after a person's decisions have been applied.
 *
 * Distinct from the report on purpose: the report is immutable evidence of
 * what the analyzer read, and this is what a person made of it. Nothing here
 * is written to the domain; [effectiveCandidate] carries `USER_CONFIRMED`
 * fidelity where a decision put it, and only there.
 */
data class VerifiedCandidate(
    val source: ReportIdentity,
    val decisions: List<VerificationDecision>,
    val questions: List<RootQuestion>,
    val effectiveCandidate: ProjectAnalysisCandidate,
    val effectiveQuantities: ProjectQuantities?,
    val validations: List<ValidationFinding>,
    val quantities: List<VerifiedQuantity>,
    val summary: VerificationSummary,
    val unresolved: List<RootQuestion>,
    val deferred: List<RootQuestion>,
    val readiness: VerificationReadiness,
    /** What each decision changed, in the reader's language, newest first. */
    val changeSummary: List<String>,
) {
    fun quantity(key: String): VerifiedQuantity? = quantities.firstOrNull { it.key == key }

    fun question(id: String): RootQuestion? = questions.firstOrNull { it.id == id }

    /** Whether [questionId] has a decision other than a deferral. */
    fun isResolved(questionId: String): Boolean = decisions.any { it.questionId == questionId && it.kind != DecisionKind.DEFER }

    fun isDeferred(questionId: String): Boolean =
        decisions.lastOrNull { it.questionId == questionId }?.kind == DecisionKind.DEFER
}

/**
 * How much verification costs a person, measured in decisions rather than
 * rows — and how much each decision buys.
 */
data class VerificationBurden(
    val rawQuantities: Int,
    val rawQuantitiesNeedingConfirmation: Int,
    val rawAnalyzerQuestions: Int,
    val rawAmbiguities: Int,
    val rootDecisions: Int,
    val required: Int,
    val highImpact: Int,
    val recommended: Int,
    val optional: Int,
    /** Root question id → how many quantity rows it resolves. */
    val resolvedPerRoot: Map<String, Int>,
) {
    /** Rows needing confirmation per root decision: the reduction factor. */
    val reductionFactor: Double get() = if (rootDecisions == 0) 0.0 else rawQuantitiesNeedingConfirmation.toDouble() / rootDecisions

    fun render(): String = buildString {
        appendLine("rawQuantities = $rawQuantities")
        appendLine("rawQuantitiesNeedingConfirmation = $rawQuantitiesNeedingConfirmation")
        appendLine("rawAnalyzerQuestions = $rawAnalyzerQuestions")
        appendLine("rawAmbiguities = $rawAmbiguities")
        appendLine("rootDecisions = $rootDecisions")
        appendLine("required = $required")
        appendLine("highImpact = $highImpact")
        appendLine("recommended = $recommended")
        appendLine("optional = $optional")
        appendLine("reductionFactor = ${String.format(java.util.Locale.ROOT, "%.1f", reductionFactor)}")
        resolvedPerRoot.forEach { (id, n) -> appendLine("resolves.$id = $n") }
    }
}
