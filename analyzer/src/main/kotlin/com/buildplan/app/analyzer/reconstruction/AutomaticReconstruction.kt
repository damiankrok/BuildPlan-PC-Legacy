package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.roof.RoofHeightField
import com.buildplan.app.analyzer.site.SourcePackage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Bounded reconstruction over existing source traces. No oracle or remote interpreter. */
object AutomaticReconstruction {
    fun reconstruct(input: ProjectAnalysisCandidate, source: SourcePackage): ProjectAnalysisCandidate {
        val graph = EvidenceGraphBuilder.build(input, source)
        val policy = ReconstructionPolicy()
        val massHypotheses = MassSolver.hypotheses(input, graph, policy)
        val massBest = massHypotheses.firstOrNull { it.hardViolations.isEmpty() }
        val reconstructedInput = massBest?.candidate ?: input
        val alternatives = mutableListOf("plan-levels" to reconstructedInput)
        val diagnostics = mutableListOf<String>()
        // Lower roof levels are ambiguous when the section has no legible numeric labels.
        // A level is proposed only when two distinct elevations corroborate it.
        val roof = input.roof
        val lowerLevels = lowerRoofLevels(input)
        if (roof != null && roof.secondaryMasses.isNotEmpty() && lowerLevels.size >= 2) {
            val sorted = lowerLevels.sortedBy { it.second }
            val median = sorted[sorted.size / 2].second
            val corroborating = sorted.filter { abs(it.second - median) <= 0.35 }
            if (corroborating.size >= 2) {
                val level = corroborating.map { it.second }.average()
                val updated = roof.secondaryMasses.map { mass ->
                    val original = mass.topElevation.value ?: return@map mass
                    if (abs(level - original) > 0.8) return@map mass
                    val measured = Measured(level, MeasureUnit.METER, FactFidelity.TRACE_UNCERTAIN,
                        Provenance(corroborating.first().first, "lower roof line on independent elevations", "median-cluster elevation ratios calibrated by building height"),
                        note = "Corroborating assets: ${corroborating.joinToString { it.first }}; includes silhouette calibration uncertainty")
                    mass.copy(topElevation = measured, facets = mass.facets.map { f -> f.copy(vertices = f.vertices.map { it.copy(y = level) }) },
                        fidelity = FactFidelity.TRACE_UNCERTAIN, note = measured.note.orEmpty())
                }
                if (updated != roof.secondaryMasses) alternatives += "corroborated-lower-roofs" to input.copy(roof = roof.copy(secondaryMasses = updated))
            }
        }
        val scores = alternatives.map { (id, candidate) -> score(id, candidate, lowerLevels) }
        val ranked = scores.filter { it.hardViolations.isEmpty() }.sortedByDescending { it.score }
        val best = ranked.firstOrNull()
        val chosen = OpeningFusion.fuse(alternatives.firstOrNull { it.first == best?.id }?.second ?: input)
        if (best == null) diagnostics += "No hypothesis passed physical constraints; original trace retained for diagnostics, not approved for use"
        if (lowerLevels.size < 2 && roof?.secondaryMasses?.isNotEmpty() == true) diagnostics += "Secondary roof level lacks two corroborating elevations"
        diagnostics += "Silhouette score is a normalized orthographic profile comparison; occluded facade features may be absent"
        if (chosen.openings.any { it.height.value == null }) diagnostics += "Some opening heights remain internally unresolved"
        val masses = masses(chosen)
        val envelopes = envelopes(chosen)
        val matched = envelopes.flatMap { it.openingIds }.toSet()
        val outside = chosen.openings.filter { it.exterior && it.id !in matched }
        if (outside.isNotEmpty()) diagnostics += "${outside.size} exterior opening traces do not match a facade envelope"
        val coverage = linkedMapOf(
            "plans" to chosen.floors.count { it.calibration != null },
            "elevations" to chosen.visual.assets.count { it.viewpoint == VisualViewpoint.ORTHOGRAPHIC_ELEVATION },
            "renders" to chosen.visual.assets.count { it.viewpoint == VisualViewpoint.PERSPECTIVE_RENDER },
            "publishedFacts" to source.scalars.count { it.measured.value != null },
        )
        val margin = if (ranked.size > 1) ranked[0].score - ranked[1].score else 0.0
        if (ranked.size > 1 && margin < 0.03) diagnostics += "Low hypothesis margin; runner-up retained"
        val result = chosen.copy(masses = masses, facadeEnvelopes = envelopes,
            reconstruction = ReconstructionState(graph, massBest?.masses.orEmpty(), policy),
            selfVerification = SelfVerificationResult(coverage, best?.id ?: "unresolved", scores,
                // Six independent score families are needed for full source verification.
                // Available coarse scores cannot imply high overall confidence on their own.
                min(0.75, (best?.score ?: 0.0) * (best?.scores?.size ?: 0) / 6.0), margin, diagnostics, alternatives.size))
        val featured=FacadeReconstruction.reconstruct(result)
        val resolved=featured.copy(resolvedGeometry=GeometryResolver.facades(featured))
        val budget=ProjectionBudget(policy.cameraProjections)
        val qa=SourceScorer.score(resolved,source,budget)
        return resolved.copy(selfVerification=resolved.selfVerification!!.copy(sourceScores=qa.scores+("overallScore" to qa.overall),metricResiduals=qa.residuals,
            hardViolations=qa.hard,unresolvedDiagnostics=diagnostics+qa.diagnostics,searchCounts=mapOf("hypotheses" to massHypotheses.size,"cameraProjections" to budget.used),
            overallConfidence=qa.overall*min(1.0,qa.scores.size/12.0)))
    }

    private fun lowerRoofLevels(c: ProjectAnalysisCandidate): List<Pair<String, Double>> {
        if (c.roof?.secondaryMasses.isNullOrEmpty()) return emptyList()
        val terrain = c.levels.terrain.value ?: return emptyList()
        val height = (c.levels.ridge.value ?: return emptyList()) - terrain
        return c.visual.assets.filter { it.viewpoint == VisualViewpoint.ORTHOGRAPHIC_ELEVATION && it.confidence >= 0.6 }.mapNotNull { asset ->
            val silhouette = asset.silhouette ?: return@mapNotNull null
            val mass = asset.of(VisualObservationKind.DARK_MASS).filter { it.confidence >= 0.6 }
                .maxByOrNull { it.bounds.width } ?: return@mapNotNull null
            val box = mass.bounds.relativeTo(silhouette)
            if (box.width < 0.25 || box.bottom < 0.90 || box.top !in 0.45..0.85) return@mapNotNull null
            asset.assetUrl to (terrain + height * (1.0 - box.top))
        }
    }

    private fun masses(c: ProjectAnalysisCandidate): List<BuildingMassCandidate> {
        val roof = c.roof ?: return emptyList()
        val main = c.floors.maxByOrNull { it.order }?.footprint ?: return emptyList()
        val urls = c.floors.mapNotNull { it.planAssetUrl }
        return listOf(BuildingMassCandidate("main", main, c.levels.groundFloor, roof.eaveElevation, roof.family,
            roof.secondaryMasses.map { it.id }, urls, 0.75)) + roof.secondaryMasses.map { mass ->
            BuildingMassCandidate(mass.id, mass.outline, c.levels.groundFloor, mass.topElevation, mass.family,
                listOf("main"), urls + listOfNotNull(mass.topElevation.provenance.sourceUrl),
                if (mass.fidelity == FactFidelity.SOURCE_DERIVED) 0.7 else 0.45)
        }
    }

    /** Facade assignment uses actual gap coordinates, never the clamped point on a wall fragment. */
    fun openingSegment(c: ProjectAnalysisCandidate, o: OpeningCandidate): Segment? {
        val wall = c.wall(o.wallId) ?: return null
        val length = wall.centreline.length
        if (length <= 1e-6) return null
        val distance = o.distanceAlongWall.value ?: return null
        val width = o.width.value ?: return null
        fun at(d: Double) = Pt(wall.centreline.a.x + (wall.centreline.b.x - wall.centreline.a.x) * d / length,
            wall.centreline.a.z + (wall.centreline.b.z - wall.centreline.a.z) * d / length)
        return Segment(at(distance), at(distance + width))
    }

    fun envelopes(c: ProjectAnalysisCandidate): List<FacadeEnvelopeCandidate> {
        val field = c.roof?.let(::RoofHeightField)
        val topFloor = c.floors.maxByOrNull { it.order }?.id
        return c.floors.flatMap { floor ->
            val footprint = floor.footprint ?: return@flatMap emptyList()
            val base = floor.floorElevation.value ?: return@flatMap emptyList()
            val thickness = c.walls.filter { it.floorId == floor.id && it.touchesOutside }.mapNotNull { it.thickness.value }
                .sorted().let { if (it.isEmpty()) 0.25 else it[it.size / 2] }.coerceIn(0.1, 0.7)
            footprint.edges.mapIndexed { index, edge ->
                val steps = max(2, ceil(edge.length / 0.2).toInt())
                val profile = (0..steps).map { n ->
                    val t = n.toDouble() / steps
                    val p = Pt(edge.a.x + (edge.b.x - edge.a.x) * t, edge.a.z + (edge.b.z - edge.a.z) * t)
                    val ceiling = base + (floor.clearHeight.value ?: 2.7)
                    val y = if (floor.id == topFloor) field?.heightAt(p) ?: ceiling else {
                        val secondary = c.roof?.secondaryMasses?.firstOrNull { mass -> mass.outline.contains(p) || mass.outline.edges.any { onEdge(Segment(p,p),it,0.05) } }
                        secondary?.topElevation?.value ?: c.floors.filter { it.order > floor.order }.minByOrNull { it.order }?.floorElevation?.value ?: ceiling
                    }
                    Pt3(p.x, max(base + 0.1, y), p.z)
                }
                val simplified = profile.filterIndexed { n, p ->
                    if (n == 0 || n == profile.lastIndex) true else abs(profile[n - 1].y + profile[n + 1].y - 2 * p.y) > 1e-6
                }
                val ids = c.openings.filter { o ->
                    if (!o.exterior || o.floorId != floor.id) false else {
                        val gap = openingSegment(c, o)
                        gap != null && onEdge(gap, edge, thickness * 0.75 + 0.05)
                    }
                }.map { it.id }
                FacadeEnvelopeCandidate("${floor.id}-facade-$index", floor.id, edge, floor.floorElevation,
                    Measured.assumed(thickness, MeasureUnit.METER, "median exterior trace thickness for facade envelope"), simplified, ids)
            }
        }
    }

    fun onEdge(gap: Segment, edge: Segment, tolerance: Double): Boolean {
        val dx = edge.b.x - edge.a.x
        val dz = edge.b.z - edge.a.z
        val len = edge.length
        if (len < 1e-6) return false
        fun distance(p: Pt) = abs((p.x - edge.a.x) * dz - (p.z - edge.a.z) * dx) / len
        fun along(p: Pt) = ((p.x - edge.a.x) * dx + (p.z - edge.a.z) * dz) / len
        return distance(gap.a) <= tolerance && distance(gap.b) <= tolerance &&
            min(along(gap.a), along(gap.b)) >= -tolerance && max(along(gap.a), along(gap.b)) <= len + tolerance
    }

    private fun score(id: String, c: ProjectAnalysisCandidate, lower: List<Pair<String, Double>>): ReconstructionHypothesisScore {
        val hard = mutableListOf<String>()
        val roof = c.roof
        if (roof != null) {
            if (roof.facets.any { f -> f.areaM2 <= 0 || f.vertices.any { it.y < (c.levels.groundFloor.value ?: 0.0) } }) hard += "Invalid roof facet"
            if (roof.secondaryMasses.any { (it.topElevation.value ?: 0.0) <= (c.levels.groundFloor.value ?: 0.0) }) hard += "Non-positive secondary mass height"
            if (id == "corroborated-lower-roofs") {
                // A dark facade's top is not necessarily its roof plane: it may be
                // a parapet, cladding or a railing. Repeated colour boundaries do
                // not resolve that semantic ambiguity, even on independent views.
                val terrain = c.levels.terrain.value ?: 0.0
                val buildingHeight = (c.levels.ridge.value ?: terrain) - terrain
                val roofEdgeLevels = c.visual.assets.filter { it.viewpoint == VisualViewpoint.ORTHOGRAPHIC_ELEVATION }.flatMap { asset ->
                    val silhouette = asset.silhouette ?: return@flatMap emptyList()
                    asset.of(VisualObservationKind.EAVE_LINE).filter { it.confidence >= 0.65 }.map {
                        terrain + buildingHeight * (1 - it.bounds.relativeTo(silhouette).centreY)
                    }
                }
                if (roof.secondaryMasses.any { mass -> roofEdgeLevels.none { abs(it - (mass.topElevation.value ?: Double.NEGATIVE_INFINITY)) <= buildingHeight * 0.025 } }) {
                    hard += "Lower facade boundary may be parapet or cladding; no structural roof edge corroborates the proposed roof plane"
                }
            }
        }
        val scores = linkedMapOf<String, Double>()
        scores["planTopology"] = if (c.floors.all { it.footprint != null }) 1.0 else 0.0
        elevationScore(c)?.let { scores["elevationSilhouette"] = it }
        if (lower.isNotEmpty() && roof?.secondaryMasses?.isNotEmpty() == true) {
            val errors = lower.map { (_, level) -> roof.secondaryMasses.minOf { abs((it.topElevation.value ?: 0.0) - level) / 1.0 } }
            scores["lowerRoofLevels"] = (1.0 - errors.sorted().take(max(1, errors.size - 1)).average()).coerceIn(0.0, 1.0)
        }
        val weights = mapOf("planTopology" to 0.35, "elevationSilhouette" to 0.40, "lowerRoofLevels" to 0.25)
        val total = scores.entries.sumOf { it.value * weights.getValue(it.key) } / scores.keys.sumOf { weights.getValue(it) }
        return ReconstructionHypothesisScore(id, if (hard.isEmpty()) total else 0.0, hard, scores)
    }

    /** Normalized solid-silhouette IoU, sampled by facade column. Not RGB similarity. */
    fun elevationScore(c: ProjectAnalysisCandidate): Double? {
        val roof = c.roof ?: return null
        val bounds = c.floors.mapNotNull { it.footprint?.bounds }.reduceOrNull { a, b -> a.union(b) } ?: return null
        val ground = c.levels.terrain.value ?: 0.0
        val height = (c.levels.ridge.value ?: return null) - ground
        if (height <= 0) return null
        val values = c.visual.facades.filter { it.isSettled }.mapNotNull { facade ->
            val asset = c.visual.assets.firstOrNull { it.assetUrl == facade.assetUrl } ?: return@mapNotNull null
            val box = asset.silhouette ?: return@mapNotNull null
            val segments = asset.of(VisualObservationKind.ROOFLINE_SEGMENT)
            if (segments.isEmpty()) return@mapNotNull null
            val side = facade.sides.single()
            val xAxis = side == FacadeSide.NORTH || side == FacadeSide.SOUTH
            val reverse = side == FacadeSide.NORTH || side == FacadeSide.EAST
            val minU = if (xAxis) bounds.minX else bounds.minZ
            val span = if (xAxis) bounds.width else bounds.depth
            var intersection = 0.0
            var union = 0.0
            for (i in 0 until 64) {
                val u = (i + 0.5) / 64
                val segment = segments.firstOrNull { val b = it.bounds.relativeTo(box); u >= b.left && u <= b.right } ?: continue
                val b = segment.bounds.relativeTo(box)
                val t = if (b.width > 1e-6) (u - b.left) / b.width else 0.5
                val sourceTop = if (segment.note == "rising to the right") b.bottom - t * b.height else b.top + t * b.height
                val coordinate = minU + (if (reverse) 1.0 - u else u) * span
                val roofHeights = (roof.facets + roof.secondaryMasses.flatMap { it.facets }).flatMap { f ->
                    f.vertices.indices.mapNotNull { k ->
                        val a = f.vertices[k]; val end = f.vertices[(k + 1) % f.vertices.size]
                        val au = if (xAxis) a.x else a.z; val bu = if (xAxis) end.x else end.z
                        if (abs(bu - au) < 1e-9 || coordinate < min(au, bu) || coordinate > max(au, bu)) null
                        else a.y + (end.y - a.y) * (coordinate - au) / (bu - au)
                    }
                }
                val candidateHeight = ((roofHeights.maxOrNull() ?: ground) - ground) / height
                val sourceHeight = (1.0 - sourceTop).coerceIn(0.0, 1.0)
                intersection += min(candidateHeight, sourceHeight)
                union += max(candidateHeight, sourceHeight)
            }
            if (union > 0) intersection / union else null
        }
        return values.takeIf { it.isNotEmpty() }?.average()
    }
}
