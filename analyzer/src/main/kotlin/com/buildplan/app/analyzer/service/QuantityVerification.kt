package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.validate.ValidationStatus

/**
 * How far a quantity may be trusted by something that would spend money on
 * it.
 *
 * [FactFidelity] answers "where did this number come from"; this answers
 * "may a cost rest on it yet". They are close but not the same question, and
 * the second one is the one a budget screen has to ask. Keeping it as its own
 * vocabulary is what stops a later stage from reading `SOURCE_TRACED` as
 * permission.
 *
 * [USER_CONFIRMED] exists here and **the analyzer never produces it**. It is
 * the state a person puts a quantity into in STAGE-025, and a run that could
 * mint it on its own would make human verification decorative. `ReportRules`
 * asserts the absence; a test asserts it over both benchmark houses.
 */
enum class VerificationState {

    /** Printed by the source. Nothing was inferred. */
    SOURCE_VERIFIED,

    /** Measured or computed from source values by a stated rule. Sound, but ours rather than theirs. */
    DERIVED_VERIFIED,

    /** Rests on a value the source never gave. Carries a reason and, normally, a question. */
    ASSUMED,

    /** Absent, unreadable or contradicted. Not a small number — no number. */
    UNRESOLVED,

    /** A person confirmed it. Never emitted by an analysis run. */
    USER_CONFIRMED,
    ;

    /** Whether a cost may be attached to a quantity in this state without a person looking first. */
    val safeForCosting: Boolean get() = this == SOURCE_VERIFIED || this == DERIVED_VERIFIED || this == USER_CONFIRMED

    companion object {

        fun of(fidelity: FactFidelity): VerificationState = when (fidelity) {
            FactFidelity.SOURCE_EXACT -> SOURCE_VERIFIED
            // Traced and derived are both "we worked it out from the source". A traced wall is
            // measured off a calibrated raster, which is stronger than a guess and weaker than
            // a printed number, and that is exactly what DERIVED_VERIFIED means here.
            FactFidelity.SOURCE_TRACED, FactFidelity.SOURCE_DERIVED -> DERIVED_VERIFIED
            FactFidelity.DISPLAY_ASSUMPTION -> ASSUMED
            FactFidelity.TRACE_UNCERTAIN, FactFidelity.MISSING, FactFidelity.CONFLICTING -> UNRESOLVED
            FactFidelity.USER_CONFIRMED -> USER_CONFIRMED
        }
    }
}

/**
 * One quantity as a later stage will read it: what it is, what it measures,
 * how far it can be trusted and whether the source agreed.
 *
 * [comparison] is null when the source publishes nothing to compare against —
 * which is most per-room quantities — and carries the cross-source verdict,
 * including `NOT_COMPARABLE`, when it does.
 */
data class QuantityVerificationEntry(
    /** Stable ASCII key, the same one [com.buildplan.app.analyzer.validate.ValidationFinding.key] uses. */
    val key: String,
    /** What the number is of: a room id, a floor id, or `project`. */
    val ownerId: String,
    /** The measurement itself, with its unit, fidelity, uncertainty and provenance. */
    val measured: Measured,
    val state: VerificationState,
    /** The cross-source verdict when the source published a figure for this, else null. */
    val comparison: ValidationStatus?,
    /** Why it is assumed or unresolved, in the reader's language; null when it is neither. */
    val caveat: String?,
) {
    val unit: MeasureUnit get() = measured.unit
    val fidelity: FactFidelity get() = measured.fidelity
    val provenance get() = measured.provenance
    val isMissing: Boolean get() = measured.fidelity == FactFidelity.MISSING
    val isAssumption: Boolean get() = measured.fidelity == FactFidelity.DISPLAY_ASSUMPTION
}
