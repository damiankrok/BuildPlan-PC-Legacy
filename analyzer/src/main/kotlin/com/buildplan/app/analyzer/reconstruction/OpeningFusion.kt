package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import com.buildplan.app.analyzer.roof.RoofHeightField
import com.buildplan.app.analyzer.visual.FacadeMapper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Elevations supply verticals only after their horizontal interval agrees with a plan gap. */
object OpeningFusion {
    fun fuse(candidate: ProjectAnalysisCandidate): ProjectAnalysisCandidate {
        val bounds = candidate.floors.mapNotNull { it.footprint?.bounds }.reduceOrNull { a, b -> a.union(b) } ?: return candidate
        val terrain = candidate.levels.terrain.value ?: return candidate
        val buildingHeight = (candidate.levels.ridge.value ?: return candidate) - terrain
        if (buildingHeight <= 0) return candidate
        val sides = FacadeMapper.of(candidate)
        val roof = candidate.roof?.let(::RoofHeightField)
        val claimed = mutableSetOf<Pair<String, Int>>()
        val openings = candidate.openings.map { opening ->
            // Numeric labels outrank image ratios. No change to metric plan width or position.
            if (!opening.exterior || opening.height.value != null || opening.linkedRoomIds.isEmpty()) return@map opening
            val side = sides.sideOf(opening) ?: return@map opening
            val gap = AutomaticReconstruction.openingSegment(candidate, opening) ?: return@map opening
            val floor = candidate.floor(opening.floorId) ?: return@map opening
            val base = floor.floorElevation.value ?: return@map opening
            val xAxis = side == FacadeSide.NORTH || side == FacadeSide.SOUTH
            val reverse = side == FacadeSide.NORTH || side == FacadeSide.EAST
            val span = if (xAxis) bounds.width else bounds.depth
            if (span <= 0) return@map opening
            fun u(p: Pt): Double {
                val value = if (xAxis) (p.x - bounds.minX) / span else (p.z - bounds.minZ) / span
                return if (reverse) 1 - value else value
            }
            val left = min(u(gap.a), u(gap.b)); val right = max(u(gap.a), u(gap.b))
            data class Match(val key: Pair<String, Int>, val sill: Double, val height: Double, val score: Double)
            val matches = candidate.visual.facades.filter { it.isSettled && it.sides.single() == side && it.confidence >= 0.6 }.flatMap { facade ->
                val asset = candidate.visual.assets.firstOrNull { it.assetUrl == facade.assetUrl } ?: return@flatMap emptyList()
                val silhouette = asset.silhouette ?: return@flatMap emptyList()
                asset.observations.mapIndexedNotNull { index, observation ->
                    val key = asset.assetUrl to index
                    if (key in claimed || observation.confidence < 0.65 || observation.kind !in setOf(VisualObservationKind.OPENING_RECTANGLE, VisualObservationKind.DOOR_RECTANGLE, VisualObservationKind.GARAGE_GATE_RECTANGLE)) return@mapIndexedNotNull null
                    val box = observation.bounds.relativeTo(silhouette)
                    val overlap = (min(right, box.right) - max(left, box.left)).coerceAtLeast(0.0)
                    val union = max(right, box.right) - min(left, box.left)
                    val agreement = if (union > 0) overlap / union else 0.0
                    if (agreement < 0.45 || abs(box.centreX - (left + right) / 2) > 0.08) return@mapIndexedNotNull null
                    val sillY = terrain + (1 - box.bottom) * buildingHeight
                    val headY = terrain + (1 - box.top) * buildingHeight
                    val sill = (sillY - base).coerceAtLeast(0.0)
                    val height = headY - (base + sill)
                    if (sillY < base - 0.25 || sill !in 0.0..1.8 || height !in 0.6..3.8) return@mapIndexedNotNull null
                    val nextFloor = candidate.floors.filter { it.order > floor.order }.minByOrNull { it.order }?.floorElevation?.value
                    val ceiling = nextFloor ?: roof?.heightAt(Pt((gap.a.x + gap.b.x) / 2, (gap.a.z + gap.b.z) / 2)) ?: base + (floor.clearHeight.value ?: 2.7)
                    if (headY > ceiling + 0.15) return@mapIndexedNotNull null
                    Match(key, sill, min(height, ceiling - base - sill), agreement * observation.confidence)
                }
            }.sortedByDescending { it.score }
            val best = matches.firstOrNull() ?: return@map opening
            if (matches.size > 1 && best.score - matches[1].score < 0.08) return@map opening
            claimed += best.key
            fun measured(value: Double, field: String) = Measured(value, MeasureUnit.METER, FactFidelity.TRACE_UNCERTAIN,
                Provenance(best.key.first, "elevation observation ${best.key.second}", "plan-aligned elevation ratio for $field"),
                uncertainty = 0.20, note = "Image ratio uses inferred terrain/ridge calibration; bounding box may include reflections or occlusion")
            opening.copy(height = measured(best.height, "height"), sillHeight = measured(best.sill, "sill"))
        }
        // Propagate only when two independently measured peers agree, on the same floor and width family.
        val resolved = openings.map { opening ->
            if (opening.height.value != null || !opening.exterior || opening.type != OpeningType.WINDOW) return@map opening
            val peers = openings.filter { other -> other.exterior && other.floorId == opening.floorId && other.type == opening.type &&
                other.height.provenance.method == "plan-aligned elevation ratio for height" && abs((other.width.value ?: -10.0) - (opening.width.value ?: 10.0)) < 0.10 }
            if (peers.size < 2 || peers.maxOf { it.height.value!! } - peers.minOf { it.height.value!! } > 0.15 || peers.maxOf { it.sillHeight.value!! } - peers.minOf { it.sillHeight.value!! } > 0.15) return@map opening
            opening.copy(height = peers.first().height.copy(value = peers.map { it.height.value!! }.average(), note = "Repeated width family corroborated by ${peers.joinToString { it.id }}"),
                sillHeight = peers.first().sillHeight.copy(value = peers.map { it.sillHeight.value!! }.average()))
        }
        return candidate.copy(openings = resolved)
    }
}
