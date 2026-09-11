package com.buildplan.app.analyzer.candidate

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.Measured

enum class EvidenceClass { EXACT_METRIC, PLAN_SECTION, ELEVATION, MULTI_VIEW, RENDER, ASSUMPTION }
enum class EvidenceKind { SOURCE_ASSET, PUBLISHED_FACT, DIMENSION, PLAN_REGION, WALL_LINE, OPENING, ROOM_LABEL, STOREY, FACADE, ELEVATION_FEATURE, RENDER_FEATURE, MASS_REGION, ROOF_REGION, LEVEL, STAIR, APPEARANCE }
enum class EvidenceRelation { SUPPORTS, CONTRADICTS, SAME_PHYSICAL_FEATURE, PROJECTS_TO, BELONGS_TO_FACADE, BELONGS_TO_STOREY, BOUNDS_MASS, ADJACENT_TO, REPEATED_FAMILY, OCCLUDED_IN_VIEW, DERIVED_FROM, CONSTRAINS }
enum class ContradictionStatus { NONE, UNRESOLVED, RESOLVED_BY_PRECEDENCE }

/** Coordinates are explicitly tagged: model metres or normalized source image. */
data class EvidenceNode(
    val id: String,
    val kind: EvidenceKind,
    val sourceId: String,
    val sourceClass: EvidenceClass,
    val fidelity: FactFidelity,
    val confidence: Double,
    val method: String,
    val uncertainty: Double?,
    val contradiction: ContradictionStatus = ContradictionStatus.NONE,
    val geometry: List<Pt> = emptyList(),
    val frame: String = "model-metres",
    val measurement: Measured? = null,
    val semantic: String = "",
) {
    init { require(id.isNotBlank() && method.isNotBlank()); require(confidence in 0.0..1.0); require(uncertainty == null || uncertainty >= 0) }
}
data class EvidenceEdge(val from: String, val to: String, val relation: EvidenceRelation, val confidence: Double, val reason: String) {
    init { require(confidence in 0.0..1.0 && reason.isNotBlank()) }
}

/** Immutable graph. Relations preserve disagreements and occlusion rather than discarding evidence. */
data class EvidenceGraph(val nodes: List<EvidenceNode>, val edges: List<EvidenceEdge>) {
    init {
        require(nodes.map { it.id }.distinct().size == nodes.size)
        val ids = nodes.map { it.id }.toSet()
        require(edges.all { it.from in ids && it.to in ids })
    }
    fun related(id: String, relation: EvidenceRelation): List<EvidenceNode> {
        val ids = edges.filter { it.relation == relation && (it.from == id || it.to == id) }.map { if (it.from == id) it.to else it.from }.toSet()
        return nodes.filter { it.id in ids }
    }
    fun sameFeature(id: String): Set<String> {
        val seen = linkedSetOf(id)
        val pending = ArrayDeque<String>(); pending.add(id)
        while (pending.isNotEmpty()) related(pending.removeFirst(), EvidenceRelation.SAME_PHYSICAL_FEATURE).forEach { if (seen.add(it.id)) pending.add(it.id) }
        return seen
    }
    fun visibleIn(feature: String, view: String): Boolean = edges.none { it.from in sameFeature(feature) && it.to == view && it.relation == EvidenceRelation.OCCLUDED_IN_VIEW }
}

enum class MassUse { ENCLOSED, OPEN_COVERED, SLAB, TERRACE, FACADE_PROJECTION }
data class MassRegion(
    val id: String, val outline: Polygon, val base: Double, val top: Double,
    val use: MassUse, val roof: RoofFamily, val floorId: String,
    val evidenceIds: List<String>, val confidence: Double,
    val adjacentIds: List<String> = emptyList(), val overlappingIds: List<String> = emptyList(),
) {
    init { require(base.isFinite() && top.isFinite() && top > base); require(confidence in 0.0..1.0) }
}

/** Search configuration is source-neutral, versioned, finite and frozen before a holdout run. */
data class ReconstructionPolicy(
    val version: String = "025b-6",
    val initialHypotheses: Int = 32, val beamWidth: Int = 8, val repairCycles: Int = 4,
    val localAlternatives: Int = 8, val hypothesisEvaluations: Int = 288, val cameraProjections: Int = 2048,
    val rasterSize: Int = 128, val improvementEpsilon: Double = 0.0001,
    val weights: Map<EvidenceClass, Double> = linkedMapOf(EvidenceClass.EXACT_METRIC to 4.0, EvidenceClass.PLAN_SECTION to 4.0,
        EvidenceClass.ELEVATION to 3.0, EvidenceClass.MULTI_VIEW to 2.0, EvidenceClass.RENDER to 1.0, EvidenceClass.ASSUMPTION to 0.25),
) {
    init { require(initialHypotheses in 1..128 && beamWidth in 1..32 && repairCycles in 0..8 && localAlternatives in 1..32)
        require(hypothesisEvaluations in 1..1024 && cameraProjections in 1..8192 && rasterSize in 16..256)
        require(improvementEpsilon > 0 && weights.keys.containsAll(EvidenceClass.entries) && weights.values.all { it > 0 && it.isFinite() }) }
}

data class BuildingHypothesis(val id: String, val masses: List<MassRegion>, val candidate: ProjectAnalysisCandidate,
    val evidenceLinks: List<String>, val scoreBreakdown: Map<String, Double> = emptyMap(), val hardViolations: List<String> = emptyList())

data class ReconstructionState(val graph: EvidenceGraph, val masses: List<MassRegion>, val policy: ReconstructionPolicy = ReconstructionPolicy())
