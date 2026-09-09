package com.buildplan.app.analyzer.fidelity

/**
 * Where one value came from: which source, which locator inside it, and by
 * which method it was read.
 *
 * A value without provenance is a magic number. Every [Measured] value the
 * analyzer emits carries one of these so that a reviewer can answer "where did
 * this come from?" without re-running the pipeline.
 *
 * @property sourceUrl the page or asset the value was read from, or null for a
 *   value that has no single source (an assumption, a derivation over many).
 * @property locator how to find it inside that source: a CSS-ish path for HTML,
 *   a pixel region for a raster, an algorithm name for a derivation.
 * @property method the reading method, in words. Kept free-text on purpose:
 *   it is evidence for a person, not a key for a program.
 */
data class Provenance(
    val sourceUrl: String?,
    val locator: String,
    val method: String,
) {
    init {
        require(locator.isNotBlank()) { "Provenance locator must not be blank" }
        require(method.isNotBlank()) { "Provenance method must not be blank" }
    }

    companion object {
        /** Provenance for a value the analyzer chose rather than read. */
        fun assumption(reason: String): Provenance =
            Provenance(sourceUrl = null, locator = "assumption", method = reason)

        /** Provenance for a value computed from other values by a named rule. */
        fun derived(rule: String, inputs: List<String> = emptyList()): Provenance =
            Provenance(
                sourceUrl = null,
                locator = "derived",
                method = if (inputs.isEmpty()) rule else "$rule from ${inputs.joinToString()}",
            )
    }
}

/**
 * One value with its unit, fidelity and provenance — the atom of every
 * analyzer output.
 *
 * [value] is null exactly when [fidelity] is [FactFidelity.MISSING]; a missing
 * value is a real state of the model, not an exception, because the analyzer's
 * job includes saying what it could not find.
 *
 * [uncertainty] is an absolute half-width in [unit] when known (a raster's
 * pixel size after calibration, a rounding of the published figure), or null
 * when the source gives no basis for one.
 */
data class Measured(
    val value: Double?,
    val unit: MeasureUnit,
    val fidelity: FactFidelity,
    val provenance: Provenance,
    val uncertainty: Double? = null,
    val note: String? = null,
) {
    init {
        if (fidelity == FactFidelity.MISSING) {
            require(value == null) { "A MISSING measurement must not carry a value, had $value" }
        } else {
            requireNotNull(value) { "A $fidelity measurement needs a value" }
            require(value.isFinite()) { "Measured value must be finite, was $value $unit" }
        }
        uncertainty?.let { require(it.isFinite() && it >= 0.0) { "Uncertainty must be finite and non-negative" } }
    }

    /** The value, or throws when missing. For code paths that have checked [FactFidelity.hasValue]. */
    fun requireValue(): Double = requireNotNull(value) { "Measurement is MISSING: ${provenance.method}" }

    companion object {
        fun missing(unit: MeasureUnit, why: String): Measured = Measured(
            value = null,
            unit = unit,
            fidelity = FactFidelity.MISSING,
            provenance = Provenance(sourceUrl = null, locator = "missing", method = why),
        )

        fun exact(value: Double, unit: MeasureUnit, provenance: Provenance, uncertainty: Double? = null): Measured =
            Measured(value, unit, FactFidelity.SOURCE_EXACT, provenance, uncertainty)

        fun traced(value: Double, unit: MeasureUnit, provenance: Provenance, uncertainty: Double? = null, note: String? = null): Measured =
            Measured(value, unit, FactFidelity.SOURCE_TRACED, provenance, uncertainty, note)

        fun derived(
            value: Double,
            unit: MeasureUnit,
            rule: String,
            inputs: List<Measured>,
            uncertainty: Double? = null,
            note: String? = null,
        ): Measured = Measured(
            value = value,
            unit = unit,
            fidelity = FactFidelity.weakest(inputs.map { it.fidelity } + FactFidelity.SOURCE_DERIVED),
            provenance = Provenance.derived(rule, inputs.map { it.provenance.method }),
            uncertainty = uncertainty,
            note = note,
        )

        fun assumed(value: Double, unit: MeasureUnit, reason: String): Measured =
            Measured(value, unit, FactFidelity.DISPLAY_ASSUMPTION, Provenance.assumption(reason))
    }
}

/**
 * The units the analyzer measures in. Deliberately a small closed set, like
 * the domain's `UnitOfMeasure`, and deliberately separate from it: the
 * analyzer's outputs are candidates, and mapping them onto the domain is a
 * later, verified step.
 */
enum class MeasureUnit(val symbol: String) {
    METER("m"),
    SQUARE_METER("m2"),
    CUBIC_METER("m3"),
    DEGREE("deg"),
    PIXEL("px"),
    PIXEL_PER_METER("px/m"),
    COUNT("szt"),
    RATIO("ratio"),
}
