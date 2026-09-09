package com.buildplan.app.analyzer.text

import com.buildplan.app.analyzer.candidate.OpeningCandidate
import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import kotlin.math.abs
import kotlin.math.hypot

/** What one drawing's text runs amount to, before anything is read from them. */
data class DrawingTextReading(
    val assetUrl: String,
    val observations: List<TextObservation>,
) {
    val located: Int get() = observations.size
    val read: Int get() = observations.count { it.legibility == TextLegibility.READ }
    val tooSmall: Int get() = observations.count { it.legibility == TextLegibility.TOO_SMALL_TO_READ }
    val medianGlyphHeightPx: Int?
        get() = observations.map { it.glyphHeightPx }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** One line for the run log: what the drawing labels, and whether it can be read. */
    fun summary(): String = buildString {
        append("$located text runs")
        medianGlyphHeightPx?.let { append(", median glyph height $it px") }
        append(": $read read")
        if (tooSmall > 0) append(", $tooSmall below the ${DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX} px legibility floor")
        val noRecogniser = observations.count { it.legibility == TextLegibility.NO_RECOGNISER }
        if (noRecogniser > 0) append(", $noRecogniser large enough but unread (no recogniser)")
    }
}

/**
 * Ties the labels a plan prints to the openings they dimension.
 *
 * On these drawings an opening's size is printed in a leader-ended ellipse a
 * short way off the wall, so the nearest text run to an opening's centre —
 * within a bounded search — is its label. Association is done whether or not
 * the run could be read, because "this opening is dimensioned on the drawing
 * and the published raster is too small to carry it" is exactly the finding
 * the reader needs, and it is not the same finding as "this opening is not
 * dimensioned".
 */
object OpeningLabelMatcher {

    /** A label sits within this of the opening it dimensions; beyond it belongs to something else. */
    const val SEARCH_RADIUS_M = 2.5

    data class Association(
        val openingId: String,
        val observation: TextObservation,
        val distanceM: Double,
    )

    fun associate(
        openings: List<OpeningCandidate>,
        openingCentre: (OpeningCandidate) -> Pt?,
        reading: DrawingTextReading,
        calibration: PlanCalibration,
    ): List<Association> {
        val ppm = calibration.pixelsPerMeter.requireValue()
        val radiusPx = SEARCH_RADIUS_M * ppm
        val taken = HashSet<TextObservation>()
        return openings.mapNotNull { opening ->
            val centre = openingCentre(opening) ?: return@mapNotNull null
            val centrePx = calibration.toPixels(centre)
            val best = reading.observations
                .filter { it !in taken }
                .map { it to distance(centrePx, it) }
                .filter { it.second <= radiusPx }
                .minByOrNull { it.second }
                ?: return@mapNotNull null
            taken += best.first
            Association(opening.id, best.first, best.second / ppm)
        }
    }

    /**
     * Applies a read label to the opening it belongs to.
     *
     * The width the plan was traced at is the check: a label is only trusted
     * when the width it states agrees with the width measured off the raster,
     * because a leader line can point past its own opening to the next one and
     * a swapped label would otherwise put a confident, wrong height on a wall.
     */
    fun applyHeights(
        openings: List<OpeningCandidate>,
        associations: List<Association>,
        widthToleranceM: Double = 0.15,
    ): Pair<List<OpeningCandidate>, List<String>> {
        val byId = associations.associateBy { it.openingId }
        val notes = mutableListOf<String>()
        val updated = openings.map { opening ->
            val association = byId[opening.id] ?: return@map opening
            val parsed = DimensionLabelParser.parse(association.observation) as? ParsedLabel.OpeningSize ?: return@map opening
            val tracedWidth = opening.width.value
            if (tracedWidth != null && abs(tracedWidth - parsed.widthM) > widthToleranceM) {
                notes += "${opening.id}: label \"${association.observation.text}\" states ${parsed.widthM} m wide but the opening traces ${"%.2f".format(java.util.Locale.ROOT, tracedWidth)} m; label not applied"
                return@map opening
            }
            notes += "${opening.id}: height ${parsed.heightM} m read from the drawing label"
            opening.copy(height = DimensionLabelParser.measured(parsed.heightM, association.observation, "opening ${opening.id} height", MeasureUnit.METER))
        }
        return updated to notes
    }

    private fun distance(from: Pt, observation: TextObservation): Double {
        val b = observation.bounds
        val cx = (b.minX + b.maxX) / 2.0
        val cy = (b.minY + b.maxY) / 2.0
        return hypot(from.x - cx, from.z - cy)
    }
}
