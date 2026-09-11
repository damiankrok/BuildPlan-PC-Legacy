package com.buildplan.app.analyzer.candidate

import com.buildplan.app.analyzer.fidelity.Measured

/** Source-derived mass regions, distinct from roof overhangs and room partitions. */
data class BuildingMassCandidate(
    val id: String,
    val footprint: Polygon,
    val baseLevel: Measured,
    val topLevel: Measured,
    val roofKind: RoofFamily,
    val adjacentMassIds: List<String>,
    val sourceAssets: List<String>,
    val confidence: Double,
) {
    init { require(confidence in 0.0..1.0); require(id.isNotBlank()) }
}

/** A facade's vertical envelope; the profile follows the owning roof, not room ceiling height. */
data class FacadeEnvelopeCandidate(
    val id: String,
    val floorId: String,
    val segment: Segment,
    val baseLevel: Measured,
    val thickness: Measured,
    val topProfile: List<Pt3>,
    val openingIds: List<String>,
)

data class ReconstructionHypothesisScore(
    val id: String,
    val score: Double,
    val hardViolations: List<String>,
    val scores: Map<String, Double>,
) {
    init { require(score in 0.0..1.0); require(scores.values.all { it in 0.0..1.0 }) }
}

/** Runtime QA, never a questionnaire or a claim of human verification. Missing scores stay absent. */
data class SelfVerificationResult(
    val sourceCoverage: Map<String, Int>,
    val selectedHypothesis: String,
    val hypotheses: List<ReconstructionHypothesisScore>,
    /** Heuristic evidence confidence, not a calibrated probability of architectural correctness. */
    val overallConfidence: Double,
    val selectionMargin: Double,
    val unresolvedDiagnostics: List<String>,
    val iterations: Int,
) {
    init {
        require(overallConfidence in 0.0..1.0 && selectionMargin in 0.0..1.0)
        require(iterations > 0 && sourceCoverage.values.all { it >= 0 })
    }
}
