package com.buildplan.app.analyzer.text

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.raster.PixelBox
import kotlin.math.abs
import kotlin.math.max

/** One label of a dimension chain, read and parsed. */
data class DimensionObservation(
    val observation: TextObservation,
    /** The printed value in centimetres, as the sheet writes it. */
    val centimetres: Int,
    /** Position along the chain's own axis, in pixels: the label's centre. */
    val alongPx: Double,
    /** Position across the chain's axis, in pixels: which dimension line it belongs to. */
    val acrossPx: Double,
) {
    val metres: Double get() = centimetres / 100.0
}

/** Why a chain was or was not believed. */
enum class ChainVerdict {
    /**
     * Three or more labels whose spacing agrees with their values.
     *
     * Three is the floor on purpose. Two labels leave exactly one gap, and one
     * gap can always be explained by one scale, so a pair is *always*
     * self-consistent and the verdict would mean nothing. Only a third label
     * gives the arithmetic something to disagree with.
     */
    SELF_CONSISTENT,

    /**
     * The scale the labels imply matches the scale the plan was calibrated at
     * by a wholly separate route (published built-up area over traced
     * footprint). Available to a pair, and strong: two independent derivations
     * of the same number.
     */
    AGREES_WITH_PLAN_SCALE,

    /**
     * The chain's total equals a span measured off the plan itself.
     *
     * The overall dimension of a sheet states the building's own size, so a
     * lone total — which no spacing can check — can still be corroborated by
     * the footprint it annotates. Independent in the way that matters: the
     * span comes from traced walls, the number from printed glyphs, and
     * neither knows the other.
     */
    AGREES_WITH_TRACED_SPAN,

    /** Nothing to check the reading against: one label, or a pair with no plan scale to compare. */
    UNCORROBORATED,

    /** The spacing contradicts the values, or the implied scale contradicts the plan's. */
    INCONSISTENT,
}

/**
 * A run of dimension labels along one line of the drawing, and the check that
 * decides whether their reading may become a fact.
 *
 * The check is the point. A digit recogniser at eleven pixels will sooner or
 * later turn a 5 into a 6, and no confidence score on a single glyph can rule
 * that out — but a *chain* carries its own redundancy: the labels are laid out
 * along the line in proportion to the very numbers they print. Two adjacent
 * parts sit half of each apart, so the gaps between label centres and the
 * values on those labels determine one scale, over and over, and a misread
 * digit throws its gap out of line with all the others.
 *
 * That is why a chain is believed for its *internal geometry* rather than for
 * its scores, and why a lone number — with nothing to disagree with — is
 * [ChainVerdict.UNCORROBORATED] no matter how cleanly it was read.
 */
data class DimensionChain(
    val orientation: TextOrientation,
    val parts: List<DimensionObservation>,
    val verdict: ChainVerdict,
    /** Pixels per centimetre implied by the labels' own spacing, when consistent. */
    val pixelsPerCm: Double?,
    /** Worst relative disagreement between a gap and the value it should represent. */
    val worstResidual: Double?,
    val note: String,
) {
    val totalCentimetres: Int get() = parts.sumOf { it.centimetres }

    /** Pixels per metre implied by this chain, for comparison with the plan calibration. */
    val pixelsPerMeter: Double? get() = pixelsPerCm?.times(100)

    val isTrusted: Boolean
        get() = verdict == ChainVerdict.SELF_CONSISTENT ||
            verdict == ChainVerdict.AGREES_WITH_PLAN_SCALE ||
            verdict == ChainVerdict.AGREES_WITH_TRACED_SPAN

    fun scaleMeasured(assetUrl: String): Measured? {
        val ppm = pixelsPerMeter ?: return null
        return Measured(
            value = ppm,
            unit = MeasureUnit.PIXEL_PER_METER,
            fidelity = FactFidelity.SOURCE_EXACT,
            provenance = Provenance(
                assetUrl,
                "dimension chain of ${parts.size} labels (${parts.joinToString("+") { it.centimetres.toString() }} cm)",
                "spacing of the printed labels against the values they print; worst disagreement ${pct(worstResidual)}",
            ),
        )
    }

    private fun pct(v: Double?) = v?.let { String.format(java.util.Locale.ROOT, "%.1f %%", it * 100) } ?: "n/a"
}

object DimensionChains {

    /** A label whose value is outside this range in centimetres is not a plan dimension. */
    val PLAUSIBLE_CM = 20..9999

    /** Labels within this many pixels across the axis are on the same dimension line. */
    const val SAME_LINE_TOLERANCE_PX = 12.0

    /** How far a gap may sit from the value it should represent before the chain is disbelieved. */
    const val MAX_RESIDUAL = 0.06

    /**
     * How far a chain's implied scale may sit from the plan's calibration.
     *
     * Wide enough that a label centred a pixel or two off its interval still
     * agrees; far too tight for a run of digits picked up from somewhere else
     * on the sheet, which lands a whole multiple away.
     */
    const val MAX_SCALE_DISAGREEMENT = 0.08

    /**
     * How close a total must come to a traced span to count as annotating it.
     *
     * Tight on purpose: this is the only check a lone number gets, so it has
     * to be a statement about *this* building rather than a coincidence any
     * three-digit reading could satisfy.
     */
    const val MAX_SPAN_DISAGREEMENT = 0.03

    /**
     * Turns read text runs into chains along their own dimension lines.
     *
     * Grouping is by orientation and by the run's offset across its axis,
     * because that offset *is* the dimension line: labels of one chain are
     * printed on one line, and labels of the total sit on another line beyond
     * it. Nothing about either project decides the grouping.
     */
    fun assemble(
        observations: List<TextObservation>,
        planPixelsPerMeter: Double? = null,
        /** Traced building extent in metres along each axis: X for horizontal chains, Z for vertical. */
        tracedSpans: Map<TextOrientation, Double> = emptyMap(),
        /** Where the traced footprint sits on the sheet, if it was traced; see [outsideFootprint]. */
        footprintBoundsPx: PixelBox? = null,
    ): List<DimensionChain> {
        val parsed = observations.mapNotNull { toDimension(it) }
        return parsed.groupBy { it.observation.orientation }
            .flatMap { (orientation, group) ->
                groupByLine(group).map { line ->
                    evaluate(orientation, line, planPixelsPerMeter, tracedSpans[orientation], footprintBoundsPx)
                }
            }
            .sortedWith(compareBy({ it.orientation }, { it.parts.first().alongPx }))
    }

    /**
     * Whether a label is printed where an overall dimension is printed: clear
     * of the building it measures.
     *
     * This matters only for a lone number, whose sole check is that it equals
     * a span the plan traces. That check has one weakness — it accepts *any*
     * legible number anywhere on the sheet that happens to land within a few
     * per cent of the building's size, and a sheet carries plenty of numbers
     * that are not dimensions: room areas, a scale note, a drawing code. An
     * overall dimension, by drawing convention rather than by anything about a
     * particular publisher, is set outside the outline on its own extension
     * lines, so a number sitting *on* the plan is not one, however well it
     * happens to agree.
     *
     * A chain of several labels is not asked this: its arithmetic already
     * carries redundancy the position would only duplicate.
     */
    private fun outsideFootprint(bounds: PixelBox, footprint: PixelBox?): Boolean {
        if (footprint == null) return true
        return bounds.maxX < footprint.minX || bounds.minX > footprint.maxX ||
            bounds.maxY < footprint.minY || bounds.minY > footprint.maxY
    }

    private fun toDimension(observation: TextObservation): DimensionObservation? {
        if (observation.legibility != TextLegibility.READ) return null
        val text = observation.text ?: return null
        if (!text.all { it.isDigit() }) return null
        val value = text.toIntOrNull() ?: return null
        if (value !in PLAUSIBLE_CM) return null
        val b = observation.bounds
        // A vertical chain reads up the sheet, so its own axis is the image's Y.
        return if (observation.orientation == TextOrientation.HORIZONTAL) {
            DimensionObservation(observation, value, (b.minX + b.maxX) / 2.0, (b.minY + b.maxY) / 2.0)
        } else {
            DimensionObservation(observation, value, (b.minY + b.maxY) / 2.0, (b.minX + b.maxX) / 2.0)
        }
    }

    private fun groupByLine(parts: List<DimensionObservation>): List<List<DimensionObservation>> {
        val sorted = parts.sortedBy { it.acrossPx }
        val lines = mutableListOf<MutableList<DimensionObservation>>()
        sorted.forEach { p ->
            val line = lines.lastOrNull()
            if (line != null && abs(p.acrossPx - line.last().acrossPx) <= SAME_LINE_TOLERANCE_PX) line += p else lines += mutableListOf(p)
        }
        return lines.map { it.sortedBy { p -> p.alongPx } }
    }

    /**
     * Checks a line of labels against its own spacing and against the plan.
     *
     * Consecutive labels are centred in their own intervals, so the distance
     * between two centres covers half of each — which fixes the scale, once per
     * adjacent pair. Agreement across every pair is what makes the reading
     * evidence; a misread digit on an *end* label moves one gap and only one,
     * and shows up here immediately.
     *
     * The one case internal spacing is weak against is a misread in the
     * *middle* of a chain: it enlarges the gaps on both sides of itself by
     * nearly the same factor, so the two scales still agree with each other
     * while both are wrong. That is exactly what the comparison with the
     * plan's own calibration catches, because those agreeing scales are then
     * agreeing on a number the plan does not share. The two checks are kept
     * separate and both applied for that reason — neither subsumes the other.
     */
    private fun evaluate(
        orientation: TextOrientation,
        parts: List<DimensionObservation>,
        planPixelsPerMeter: Double?,
        tracedSpanMeters: Double?,
        footprintBoundsPx: PixelBox?,
    ): DimensionChain {
        val totalM = parts.sumOf { it.centimetres } / 100.0
        val vsSpan = tracedSpanMeters?.takeIf { it > 0 }?.let { (totalM - it) / it }
        val annotatesSpan = vsSpan != null && abs(vsSpan) <= MAX_SPAN_DISAGREEMENT
        if (parts.size < 2) {
            val placedLikeADimension = outsideFootprint(parts.first().observation.bounds, footprintBoundsPx)
            if (annotatesSpan && !placedLikeADimension) {
                return DimensionChain(
                    orientation, parts, ChainVerdict.UNCORROBORATED, null, null,
                    "a single label matching the ${fmt(tracedSpanMeters!!)} m traced span (${pct(vsSpan!!)}), but printed " +
                        "over the plan rather than outside it on an extension line; a number on the drawing is not an " +
                        "overall dimension, and one agreement is not enough to overrule where it sits",
                )
            }
            return if (annotatesSpan) {
                DimensionChain(
                    orientation, parts, ChainVerdict.AGREES_WITH_TRACED_SPAN, null, null,
                    "a single label, but $totalM m matches the ${fmt(tracedSpanMeters!!)} m the plan traces along this axis (${pct(vsSpan!!)})",
                )
            } else {
                DimensionChain(
                    orientation, parts, ChainVerdict.UNCORROBORATED, null, null,
                    "a single label has nothing to check it against" +
                        (vsSpan?.let { "; it does not match the traced ${fmt(tracedSpanMeters!!)} m span (${pct(it)})" } ?: "") +
                        "; read but not trusted",
                )
            }
        }
        val scales = (0 until parts.size - 1).map { i ->
            val gapPx = parts[i + 1].alongPx - parts[i].alongPx
            val expectedCm = (parts[i].centimetres + parts[i + 1].centimetres) / 2.0
            gapPx / expectedCm
        }
        val median = scales.sorted()[scales.size / 2]
        if (median <= 0) {
            return DimensionChain(orientation, parts, ChainVerdict.INCONSISTENT, null, null, "labels are not ordered along the line")
        }
        val worstInternal = scales.maxOf { abs(it - median) / median }
        val impliedPpm = median * 100
        val vsPlan = planPixelsPerMeter?.takeIf { it > 0 }?.let { (impliedPpm - it) / it }
        val agreesWithPlan = vsPlan != null && abs(vsPlan) <= MAX_SCALE_DISAGREEMENT
        val planDetail = vsPlan?.let { " implied scale ${fmt(impliedPpm)} px/m against the plan's ${fmt(planPixelsPerMeter!!)} (${pct(it)})" } ?: ""

        // A pair leaves one gap, and one gap cannot disagree with itself: only the plan's own
        // scale can corroborate it. Three labels can, and then the plan is a second opinion.
        val internallyChecked = parts.size >= 3 && worstInternal <= MAX_RESIDUAL
        val internallyBroken = parts.size >= 3 && worstInternal > MAX_RESIDUAL

        val verdict = when {
            internallyBroken -> ChainVerdict.INCONSISTENT
            vsPlan != null && !agreesWithPlan -> ChainVerdict.INCONSISTENT
            internallyChecked -> ChainVerdict.SELF_CONSISTENT
            agreesWithPlan -> ChainVerdict.AGREES_WITH_PLAN_SCALE
            annotatesSpan -> ChainVerdict.AGREES_WITH_TRACED_SPAN
            else -> ChainVerdict.UNCORROBORATED
        }
        val note = when (verdict) {
            ChainVerdict.SELF_CONSISTENT -> "${parts.size} labels, spacing agrees with their values to ${pct(worstInternal)}.$planDetail"
            ChainVerdict.AGREES_WITH_PLAN_SCALE -> "${parts.size} labels;$planDetail"
            ChainVerdict.INCONSISTENT -> if (internallyBroken) {
                "${parts.size} labels, but a gap disagrees with its value by ${pct(worstInternal)} — at least one is misread"
            } else {
                "${parts.size} labels, but$planDetail — the reading cannot be of this drawing's dimensions"
            }
            ChainVerdict.AGREES_WITH_TRACED_SPAN -> "${parts.size} labels totalling $totalM m, matching the ${fmt(tracedSpanMeters!!)} m the plan traces along this axis (${pct(vsSpan!!)})"
            ChainVerdict.UNCORROBORATED -> "${parts.size} labels leave one gap, which no single scale can contradict, and nothing external was available to check it"
        }
        return DimensionChain(
            orientation, parts, verdict,
            if (verdict == ChainVerdict.SELF_CONSISTENT || verdict == ChainVerdict.AGREES_WITH_PLAN_SCALE) median else null,
            if (parts.size >= 3) worstInternal else null,
            note,
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)
    private fun pct(v: Double) = String.format(java.util.Locale.ROOT, "%+.1f %%", v * 100)

    /**
     * Whether a chain's own scale agrees with the plan's calibration.
     *
     * Two independent routes to the same number: one from the published
     * built-up area over the traced footprint, one from printed dimensions
     * over the pixels between them. Agreement is worth stating and so is
     * disagreement — neither is averaged into the other.
     */
    fun compareWithCalibration(chain: DimensionChain, planPixelsPerMeter: Double): Double? {
        val ppm = chain.pixelsPerMeter ?: return null
        if (planPixelsPerMeter <= 0) return null
        return (ppm - planPixelsPerMeter) / planPixelsPerMeter
    }

    /** Relative difference between a chain's total and a span measured off the plan, in metres. */
    fun compareWithSpan(chain: DimensionChain, spanMeters: Double): Double? {
        if (spanMeters <= 0) return null
        val total = chain.totalCentimetres / 100.0
        return (total - spanMeters) / max(1e-9, spanMeters)
    }
}
