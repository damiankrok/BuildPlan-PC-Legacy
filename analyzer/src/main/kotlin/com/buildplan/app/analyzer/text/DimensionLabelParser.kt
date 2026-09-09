package com.buildplan.app.analyzer.text

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import java.util.Locale

/** What one recognised label turned out to mean. */
sealed interface ParsedLabel {
    /** An opening's leaf size, written width over height in centimetres. */
    data class OpeningSize(val widthM: Double, val heightM: Double) : ParsedLabel

    /** A levelled height above the ground floor datum, in metres. */
    data class Level(val elevationM: Double) : ParsedLabel

    /** A bare length in metres. */
    data class Length(val metres: Double) : ParsedLabel
}

/**
 * Turns a recognised drawing label into a measured fact — and refuses to,
 * loudly, whenever the reading was not good enough to be one.
 *
 * This is the gate between optical recognition and the model. Everything on
 * the far side of it carries [FactFidelity.SOURCE_EXACT], which is the
 * strongest claim the analyzer makes about any number, so the bar is set
 * here and not at each call site:
 *
 * - the observation must be [TextLegibility.READ] with text present;
 * - its confidence must clear [MIN_CONFIDENCE];
 * - the text must parse whole, with no character left over;
 * - the value must be physically possible for what it claims to be.
 *
 * A reading that fails any of these yields null, and the caller keeps the
 * MISSING it already had. There is deliberately no "probably" path: a label
 * read at half confidence is not a weaker fact, it is a different number.
 */
object DimensionLabelParser {

    /**
     * Below this, a reading is not a fact.
     *
     * A dimension label is a handful of digits with no redundancy — no
     * spell-check, no context to fall back on — so a character read at even
     * decent odds turns "180" into "160" silently. Nothing between this and
     * certainty is useful, which is why there is no lower tier.
     */
    const val MIN_CONFIDENCE = 0.90

    /** An opening leaf is between these, in metres; outside is a misread, not a door. */
    private val OPENING_DIMENSION_M = 0.30..6.00

    /** A storey level on a house section sits between these, relative to the ground floor. */
    private val LEVEL_M = -5.0..25.0

    private val OPENING = Regex("""^([0-9]{2,3})\s*/\s*([0-9]{2,3})$""")
    private val LEVEL = Regex("""^([+\-±])\s*([0-9]{1,2})[.,]([0-9]{2})$""")
    private val CENTIMETRES = Regex("""^([0-9]{2,4})$""")

    fun parse(observation: TextObservation): ParsedLabel? {
        if (observation.legibility != TextLegibility.READ) return null
        val text = observation.text?.trim() ?: return null
        if (observation.confidence < MIN_CONFIDENCE) return null

        OPENING.matchEntire(text)?.let { m ->
            val w = m.groupValues[1].toInt() / 100.0
            val h = m.groupValues[2].toInt() / 100.0
            return if (w in OPENING_DIMENSION_M && h in OPENING_DIMENSION_M) ParsedLabel.OpeningSize(w, h) else null
        }
        LEVEL.matchEntire(text)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val v = sign * (m.groupValues[2].toInt() + m.groupValues[3].toInt() / 100.0)
            return if (v in LEVEL_M) ParsedLabel.Level(v) else null
        }
        CENTIMETRES.matchEntire(text)?.let { m ->
            val v = m.groupValues[1].toInt() / 100.0
            return if (v in OPENING_DIMENSION_M) ParsedLabel.Length(v) else null
        }
        return null
    }

    /** A parsed length as a source fact, with the reading that produced it as its provenance. */
    fun measured(value: Double, observation: TextObservation, what: String, unit: MeasureUnit = MeasureUnit.METER): Measured = Measured(
        value = value,
        unit = unit,
        fidelity = FactFidelity.SOURCE_EXACT,
        provenance = Provenance(
            observation.sourceAsset,
            "$what at (${observation.bounds.minX}, ${observation.bounds.minY})",
            "read from the drawing as \"${observation.text}\" at ${String.format(Locale.ROOT, "%.2f", observation.confidence)} confidence by ${observation.method}",
        ),
    )
}
