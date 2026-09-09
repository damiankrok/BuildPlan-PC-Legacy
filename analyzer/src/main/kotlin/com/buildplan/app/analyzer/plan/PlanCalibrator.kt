package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.candidate.CalibrationAnchor
import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pixels per metre for one plan raster, from evidence the source states in
 * numbers rather than from anything the analyzer would like to be true.
 *
 * Two anchor kinds are supported today:
 *
 * - **Footprint area.** The site publishes the built-up area; the raster
 *   gives the footprint's pixel area. `ppm = sqrt(px / m2)`. Strong because
 *   the footprint is one closed shape and the published figure is exact.
 * - **Published room areas.** The sum of the plan's enclosed regions against
 *   the storey's published total (floor area when printed, else usable area).
 *   Used as an *independent check* of the first anchor, never to force room
 *   polygons to match their labels.
 *
 * Printed dimension chains are not read: they are text, and this stage has
 * no OCR. When neither anchor exists the calibration is MISSING and the
 * floor stays in pixels — a question, not a guess.
 */
object PlanCalibrator {

    fun calibrate(
        footprintPixelArea: Int?,
        footprintAreaM2: Measured?,
        regionsPixelArea: Int?,
        publishedFloorTotalM2: Measured?,
        originPx: Pt,
        sharedScale: Measured?,
    ): PlanCalibration? {
        val anchors = mutableListOf<CalibrationAnchor>()
        if (footprintPixelArea != null && footprintPixelArea > 0 && footprintAreaM2?.value != null) {
            val ppm = sqrt(footprintPixelArea / footprintAreaM2.value)
            anchors += CalibrationAnchor("FOOTPRINT_AREA", footprintAreaM2, footprintPixelArea.toDouble(), ppm)
        }
        if (regionsPixelArea != null && regionsPixelArea > 0 && publishedFloorTotalM2?.value != null) {
            val ppm = sqrt(regionsPixelArea / publishedFloorTotalM2.value)
            anchors += CalibrationAnchor("PUBLISHED_ROOM_AREAS", publishedFloorTotalM2, regionsPixelArea.toDouble(), ppm)
        }
        if (sharedScale?.value != null) {
            anchors += CalibrationAnchor("SHARED_PLAN_SCALE", sharedScale, Double.NaN, sharedScale.value)
        }
        if (anchors.isEmpty()) return null

        // The footprint anchor leads when present; otherwise the shared scale from another plan of
        // the same project (the site renders all plans at one scale); room areas only as a last resort.
        val primary = anchors.firstOrNull { it.kind == "FOOTPRINT_AREA" }
            ?: anchors.firstOrNull { it.kind == "SHARED_PLAN_SCALE" }
            ?: anchors.first()
        val others = anchors.filter { it !== primary && !it.impliedPixelsPerMeter.isNaN() }
        val residual = others.maxOfOrNull { abs(it.impliedPixelsPerMeter - primary.impliedPixelsPerMeter) / primary.impliedPixelsPerMeter }

        val confidence = when {
            primary.kind == "FOOTPRINT_AREA" && (residual == null || residual <= 0.03) -> FactFidelity.SOURCE_DERIVED
            primary.kind == "SHARED_PLAN_SCALE" && (residual == null || residual <= 0.05) -> FactFidelity.SOURCE_DERIVED
            residual != null && residual > 0.08 -> FactFidelity.CONFLICTING
            else -> FactFidelity.TRACE_UNCERTAIN
        }
        val scale = Measured(
            value = primary.impliedPixelsPerMeter,
            unit = MeasureUnit.PIXEL_PER_METER,
            fidelity = confidence,
            provenance = com.buildplan.app.analyzer.fidelity.Provenance.derived(
                "sqrt(pixel area / published area) on anchor ${primary.kind}",
                listOf(primary.sourceValue.provenance.method),
            ),
            uncertainty = residual?.let { it * primary.impliedPixelsPerMeter },
            note = "anchors: " + anchors.joinToString { "${it.kind}=${"%.2f".format(java.util.Locale.ROOT, it.impliedPixelsPerMeter)} px/m" },
        )
        return PlanCalibration(
            pixelsPerMeter = scale,
            originPx = originPx,
            method = "Area anchor: ${primary.kind}; checks: ${others.joinToString { it.kind }.ifBlank { "none" }}",
            anchors = anchors,
            residual = residual,
            confidence = confidence,
        )
    }
}
