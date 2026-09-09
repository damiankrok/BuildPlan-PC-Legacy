package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.candidate.RingValidity
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.pipeline.AnalysisRun
import com.buildplan.app.analyzer.validate.RequirementState
import com.buildplan.app.analyzer.validate.ValidationStatus
import java.util.Locale
import kotlin.math.abs

/**
 * One flat, greppable block of the numbers a hardening stage is judged on.
 *
 * The report next to it is for reading a house; this is for comparing two
 * runs. Every line is `key = value`, absolute counts before percentages, so
 * a regression in one room cannot hide behind a score that stayed the same.
 */
object EvaluationMetrics {

    fun render(project: EvaluationProjects.Project, run: AnalysisRun, snapshotBytes: Int): String = buildString {
        val c = run.candidate
        val q = run.quantities
        val publishedRooms = run.source?.floors?.sumOf { it.rooms.size } ?: 0
        val rooms = c?.rooms.orEmpty()
        val withRing = rooms.filter { it.geometryState == RoomGeometryState.VALID_SIMPLE_RING }
        val unresolved = rooms.filter { it.geometryState == RoomGeometryState.UNRESOLVED_REGION }

        fun f(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

        appendLine("# project ${project.label} ${run.source?.title}")
        appendLine("canonicalUrl = ${run.resolution.identity?.canonicalUrl}")
        appendLine("analyzerVersion = ${run.snapshot(0L).analyzerVersion}")
        appendLine()

        appendLine("## geometry / topology")
        appendLine("rooms.published = $publishedRooms")
        appendLine("rooms.emitted = ${rooms.size}")
        appendLine("rooms.validSimpleRing = ${withRing.size}")
        appendLine("rooms.unresolvedGeometry = ${unresolved.size}")
        appendLine("rooms.unmatchedPublished = ${publishedRooms - rooms.size}")
        // Independent re-check: never trust the flag, re-run the validator over what was emitted.
        val reChecked = withRing.mapNotNull { r -> r.polygon?.let { r.id to RingValidity.check(it.vertices) } }
        appendLine("rooms.emittedSelfIntersecting = ${reChecked.count { !it.second.isValid }}")
        reChecked.filter { !it.second.isValid }.forEach { appendLine("  INVALID ${it.first}: ${it.second.verdict} ${it.second.detail}") }
        unresolved.forEach { appendLine("  UNRESOLVED ${it.id} ${it.name}: ${it.geometryNote}") }
        appendLine("regions.unmatched = ${c?.floors?.sumOf { it.unmatchedRegions.size } ?: 0}")
        appendLine("stairs.zones = ${c?.stairs?.size ?: 0}")
        c?.stairs?.forEach { appendLine("  stair ${it.id} floor=${it.floorId} at=(${f(it.zone.minX)},${f(it.zone.minZ)}) zone=${f(it.zone.width)}x${f(it.zone.depth)} treads=${f(it.treadCount.value)} dir=${it.direction} room=${it.roomId} ev=${it.evidence.joinToString("+")} ${it.fidelity}") }
        appendLine("openings.total = ${c?.openings?.size ?: 0}")
        appendLine("openings.exterior = ${c?.openings?.count { it.exterior } ?: 0}")
        appendLine("openings.withSourceHeight = ${c?.openings?.count { it.height.value != null } ?: 0}")
        appendLine("walls.total = ${c?.walls?.size ?: 0}")
        appendLine("walls.byClass = ${c?.walls?.groupingBy { it.wallClass }?.eachCount()}")
        c?.floors?.forEach { fl ->
            appendLine("floor.${fl.id}.name = ${fl.name}")
            appendLine("floor.${fl.id}.rooms = ${fl.rooms.size}")
            appendLine("floor.${fl.id}.calibrationPpm = ${f(fl.calibration?.pixelsPerMeter?.value)}")
            appendLine("floor.${fl.id}.calibrationResidual = ${f(fl.calibration?.residual?.times(100))} %")
            appendLine("floor.${fl.id}.calibrationConfidence = ${fl.calibration?.confidence}")
            appendLine("floor.${fl.id}.footprintCorners = ${fl.footprint?.vertices?.size ?: 0}")
            appendLine("floor.${fl.id}.footprintArea = ${f(fl.footprint?.area)}")
        }
        appendLine()

        appendLine("## roof / vertical")
        c?.roof?.let { r ->
            appendLine("roof.family = ${r.family}")
            appendLine("roof.pitchDeg = ${f(r.pitchDegrees.value)}")
            appendLine("roof.facets = ${r.facets.size}")
            appendLine("roof.secondaryMasses = ${r.secondaryMasses.size}")
            appendLine("roof.ridgeLines = ${r.ridgeLines.size}")
            appendLine("roof.hipLines = ${r.hipLines.size}")
            appendLine("roof.areaPredicted = ${f(r.totalArea.value)}")
            appendLine("roof.areaPublished = ${f(project.observed["roof"])}")
            appendLine("roof.areaResidualPct = ${f(residualPct(r.totalArea.value, project.observed["roof"]))}")
            appendLine("roof.eaveLevel = ${f(r.eaveElevation.value)}")
            appendLine("roof.ridgeLevel = ${f(r.ridgeElevation.value)}")
        } ?: appendLine("roof = NONE")
        c?.levels?.let { l ->
            listOf("terrain" to l.terrain, "groundFloor" to l.groundFloor, "upperFloor" to l.upperFloor, "eave" to l.eave, "ridge" to l.ridge, "kneeWall" to l.kneeWall, "atticCeiling" to l.atticFlatCeilingHeight)
                .forEach { (n, m) -> appendLine("level.$n = ${f(m.value)} ${m.fidelity}") }
        }
        appendLine()

        appendLine("## quantities")
        appendLine("floorAreaSum = ${f(q?.rooms?.sumOf { it.floorArea.value ?: 0.0 })}")
        appendLine("ceilingResolved = ${q?.rooms?.count { it.ceilingTotal.value != null } ?: 0} of ${q?.rooms?.size ?: 0}")
        appendLine("volumeResolved = ${q?.rooms?.count { it.volume.value != null } ?: 0} of ${q?.rooms?.size ?: 0}")
        // The catastrophe class: a flat ceiling that is not the outline it sits under.
        //
        // Compared against the *ring*, not the floor. A room the segmentation split is joined by
        // spanning the doorway thresholds between its parts, so its outline is legitimately a
        // little larger than its floor; measuring the ceiling against the floor would report that
        // known, bounded difference as the same kind of failure as a ring that lost half the room.
        // The threshold overhead is reported on its own line below instead.
        val flatMismatch = q?.rooms.orEmpty().filter { rq ->
            val ring = c?.room(rq.roomId)?.polygon?.area ?: return@filter false
            val flat = rq.ceilingFlat.value ?: return@filter false
            val sloped = rq.ceilingSloped.value ?: 0.0
            sloped < 1e-6 && ring > 0.5 && abs(flat - ring) / ring > 0.10
        }
        appendLine("ceilingFlatVsRingMismatch = ${flatMismatch.size}")
        flatMismatch.forEach { appendLine("  MISMATCH ${it.roomId}: ring ${f(c?.room(it.roomId)?.polygon?.area)} vs flat ceiling ${f(it.ceilingFlat.value)}") }
        val overhead = rooms.mapNotNull { r ->
            val ring = r.polygon?.area ?: return@mapNotNull null
            val floorA = r.plannedArea.value ?: return@mapNotNull null
            if (floorA <= 0.5) null else r.id to (ring - floorA) / floorA
        }
        appendLine("floorToRingOverhead.max = ${f(overhead.maxOfOrNull { it.second }?.times(100))} %")
        appendLine("floorToRingOverhead.sum = ${f(rooms.sumOf { (it.polygon?.area ?: 0.0) } - rooms.sumOf { it.plannedArea.value ?: 0.0 })} m2 (doorway thresholds spanned to join split rooms)")
        // A sloped surface can never be smaller than what it projects onto. Both sides come off
        // the same 5 cm integration grid, whose cells are counted by their centres, so each is
        // short of the true area by up to about half a cell around the room's perimeter — a few
        // tenths of a percent on a room-sized polygon. Anything past one percent is a real
        // inversion; anything under it is the grid.
        val slopedBelowProjection = q?.rooms.orEmpty().filter { rq ->
            val sloped = rq.ceilingSloped.value ?: return@filter false
            val flat = rq.ceilingFlat.value ?: 0.0
            val floorA = rq.floorArea.value ?: return@filter false
            sloped > 1e-6 && (flat + sloped) < floorA * 0.99
        }
        appendLine("slopedCeilingBelowProjection = ${slopedBelowProjection.size}")
        slopedBelowProjection.forEach { appendLine("  BELOW ${it.roomId}: floor ${f(it.floorArea.value)} vs ceiling ${f((it.ceilingFlat.value ?: 0.0) + (it.ceilingSloped.value ?: 0.0))}") }
        appendLine("roomFacingWallSurfaces = ${q?.surfaces?.count { it.type.name == "WALL_FACE" } ?: 0}")
        appendLine("surfaces.total = ${q?.surfaces?.size ?: 0}")
        q?.floors?.forEach {
            appendLine("floorQ.${it.floorId}.extWallsStructural = ${f(it.exteriorWallsStructural.value)}")
            appendLine("floorQ.${it.floorId}.loadBearing = ${f(it.loadBearingWallsStructural.value)}")
            appendLine("floorQ.${it.floorId}.partitions = ${f(it.partitionsStructural.value)}")
        }
        appendLine("joinery = ${f(q?.exteriorJoinery?.value)} (published ${f(project.observed["joinery"])})")
        appendLine("facadeGross = ${f(q?.facadeGross?.value)} (published ${f(project.observed["facade"])})")
        appendLine("floorsAndStairs = ${f(q?.floorsAndStairsArea?.value)} (published ${f(project.observed["floorsAndStairs"])})")
        appendLine()

        appendLine("## quality")
        appendLine("completeness = ${f(run.gaps?.completenessScore?.times(100))} %")
        appendLine("gaps.missing = ${run.gaps?.statuses?.count { it.state == RequirementState.MISSING } ?: 0}")
        appendLine("gaps.assumed = ${run.gaps?.statuses?.count { it.state == RequirementState.ASSUMED } ?: 0}")
        appendLine("gaps.partial = ${run.gaps?.statuses?.count { it.state == RequirementState.PARTIAL } ?: 0}")
        appendLine("gaps.satisfied = ${run.gaps?.statuses?.count { it.state == RequirementState.SATISFIED } ?: 0}")
        run.gaps?.statuses?.filter { it.state == RequirementState.MISSING }?.forEach { appendLine("  MISSING ${it.requirement}: ${it.evidence}") }
        ValidationStatus.entries.forEach { s -> appendLine("validation.$s = ${run.validations.count { it.status == s }}") }
        run.validations.filter { it.status == ValidationStatus.MISMATCH }.forEach {
            appendLine("  MISMATCH ${it.subject}: cand ${f(it.candidateValue)} vs src ${it.sourceValue} (${f(it.relativeDifference?.times(100))} %)")
        }
        appendLine("questions = ${run.questions.size}")
        appendLine("questions.distinct = ${run.questions.map { it.text }.distinct().size}")
        appendLine()

        appendLine("## runtime")
        run.timingsMillis.forEach { (k, v) -> appendLine("timing.$k = $v ms") }
        appendLine("timing.TOTAL = ${run.timingsMillis.values.sum()} ms")
        appendLine("snapshot.bytes = $snapshotBytes")
    }

    private fun residualPct(candidate: Double?, published: Double?): Double? {
        if (candidate == null || published == null || published == 0.0) return null
        return (candidate - published) / published * 100
    }
}
