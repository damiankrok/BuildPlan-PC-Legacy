package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.pipeline.AnalysisRun
import com.buildplan.app.analyzer.pipeline.ProjectAnalyzer
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.validate.ValidationStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVAL-023-E2E — the full pipeline on both evaluation projects, from the
 * owner's URL to a snapshot JSON, written to the evidence directory with a
 * human-readable report. Asserts the coarse invariants of a working
 * pipeline and that the snapshot round-trips byte for byte.
 */
class EndToEndEvaluationTest {

    @Test
    fun `project A end to end`() = run(EvaluationProjects.A)

    @Test
    fun `project B end to end`() = run(EvaluationProjects.B)

    private fun run(project: EvaluationProjects.Project) {
        val dir = EvidenceHarness.directoryOrSkip()
        val label = project.label.lowercase()
        val analyzer = ProjectAnalyzer(EvidenceHarness.fetcher(dir), EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-$label"))
        val run = analyzer.analyze(ProjectInput(project.ownerUrl))
        val snapshot = run.snapshot(now = 0L)
        val text = SnapshotCodec.write(snapshot)
        EvidenceHarness.write(dir, "iter/e2e-$label/snapshot.json", text)
        EvidenceHarness.write(dir, "iter/e2e-$label/report.txt", report(run))
        EvidenceHarness.write(dir, "iter/e2e-$label/metrics.txt", EvaluationMetrics.render(project, run, text.length))

        assertTrue(run.resolution.isResolved)
        assertEquals(project.projectKey, run.resolution.identity!!.projectKey)
        assertNotNull(run.candidate)
        assertTrue("roof", run.candidate!!.roof != null)
        assertTrue("rooms", run.candidate!!.rooms.size >= 8)
        // Round trip: parse, re-write, identical bytes.
        val again = SnapshotCodec.write(SnapshotCodec.read(text))
        assertEquals(text, again)
    }

    private fun report(run: AnalysisRun): String = buildString {
        appendLine("== log")
        run.log.forEach { appendLine(it) }
        appendLine("== timings ms: ${run.timingsMillis}")
        val c = run.candidate ?: return@buildString
        appendLine("== levels")
        listOf("terrain" to c.levels.terrain, "ridge" to c.levels.ridge, "eave" to c.levels.eave, "upperFloor" to c.levels.upperFloor, "groundClear" to c.levels.groundClearHeight, "kneeWall" to c.levels.kneeWall, "atticCeiling" to c.levels.atticFlatCeilingHeight)
            .forEach { (n, m) -> appendLine("  $n = ${m.value?.let { "%.2f".format(it) }} ${m.unit} ${m.fidelity} [${m.provenance.method}]") }
        c.roof?.let { r ->
            appendLine("== roof ${r.family} pitch=${r.pitchDegrees.value} eave=${"%.2f".format(r.eaveElevation.value)} ridge=${"%.2f".format(r.ridgeElevation.value)} area=${"%.1f".format(r.totalArea.value)} ${r.fidelity}")
            appendLine("  outline: ${r.outline.vertices.joinToString { "(${"%.2f".format(it.x)},${"%.2f".format(it.z)})" }}")
            appendLine("  note: ${r.note}")
            r.facets.forEach { f -> appendLine("  facet ${f.id}: ${"%.1f".format(f.areaM2)} m2, ${f.vertices.size} vertices") }
            appendLine("  ridges: ${r.ridgeLines.size} (${"%.1f".format(r.ridgeLines.sumOf { it.length })} m), hips: ${r.hipLines.size}, eave ${"%.1f".format(r.eaveLength.value)} m, gable edges ${r.gableEdgeIndices}")
            r.ridgeLines.forEach { l -> appendLine("    ridge ${"%.2f".format(l.length)} m at y=${"%.2f".format(l.a.y)} from (${"%.2f".format(l.a.x)},${"%.2f".format(l.a.z)}) to (${"%.2f".format(l.b.x)},${"%.2f".format(l.b.z)})") }
            r.secondaryMasses.forEach { appendLine("  secondary ${it.id}: ${"%.1f".format(it.outline.area)} m2 top ${it.topElevation.value}") }
        }
        c.floors.forEach { f ->
            appendLine("== floor ${f.id} ${f.name} elev=${f.floorElevation.value} clear=${f.clearHeight.value} cal=${f.calibration?.pixelsPerMeter?.value?.let { "%.2f".format(it) }} ${f.calibration?.confidence}")
            appendLine("  footprint: ${f.footprint?.vertices?.joinToString { "(${"%.2f".format(it.x)},${"%.2f".format(it.z)})" }}")
            f.rooms.forEach { r ->
                val q = run.quantities?.rooms?.firstOrNull { it.roomId == r.id }
                appendLine("  room ${r.id} ${r.name}: planned ${"%.2f".format(r.plannedArea.value)} m2 (pub usable ${r.sourceUsableArea.value}, floor ${r.sourceFloorArea.value}) ${r.matchConfidence} | walls gross ${"%.1f".format(q?.wallGross?.value)} net ${"%.1f".format(q?.wallNet?.value)} | ceiling flat ${"%.1f".format(q?.ceilingFlat?.value)} sloped ${"%.1f".format(q?.ceilingSloped?.value)} | usable(rule) ${"%.2f".format(q?.usableAreaByHeightRule?.value)} | vol ${"%.1f".format(q?.volume?.value)} | faces ${q?.wallFaceIds?.size}")
                r.boundary.forEachIndexed { i, b -> appendLine("      face ${i + 1}: ${"%.2f".format(b.segment.length)} m wall=${b.wallId} neighbour=${b.neighbourRoomId ?: if (b.faceOutside) "OUTSIDE" else "?"}") }
            }
            f.unmatchedRegions.forEach { appendLine("  unmatched region ${it.id}: ${"%.2f".format(it.areaM2)} m2") }
            run.source?.floors?.getOrNull(c.floors.indexOf(f))?.rooms?.filter { pr -> f.rooms.none { it.sourceOrdinal == pr.ordinal } }?.forEach { appendLine("  unmatched room ${it.ordinal}. ${it.name} (${it.usableArea.value} / ${it.floorArea.value})") }
        }
        appendLine("== walls ${c.walls.size}: exterior ${c.walls.count { it.touchesOutside }}, classes ${c.walls.groupingBy { it.wallClass }.eachCount()}")
        appendLine("== openings ${c.openings.size}: ${c.openings.groupingBy { it.type }.eachCount()} exterior ${c.openings.count { it.exterior }}")
        c.openings.filter { it.exterior }.forEach { o -> appendLine("  ${o.id} ${o.type} w=${"%.2f".format(o.width.value)} wall=${o.wallId} rooms=${o.linkedRoomIds}") }
        appendLine("== stairs ${c.stairs}")
        run.quantities?.let { q ->
            appendLine("== floor quantities")
            q.floors.forEach { appendLine("  ${it.floorId}: rooms ${"%.1f".format(it.roomFloorAreaSum.value)} m2, masonry ${"%.1f".format(it.exteriorWallsStructural.value)}, envelope ${"%.1f".format(it.exteriorEnvelopeGross.value)} gross / ${"%.1f".format(it.exteriorEnvelopeNet.value)} net, load-bearing ${"%.1f".format(it.loadBearingWallsStructural.value)}, partitions ${"%.1f".format(it.partitionsStructural.value)}, openings ${it.openingAreasByType.mapValues { e -> "%.1f".format(e.value) }}") }
            appendLine("== aggregates: roof ${"%.1f".format(q.roofTotal.value)}, ridge ${"%.1f".format(q.ridgeLength.value)}, hips ${"%.1f".format(q.hipLength.value)}, eaves ${"%.1f".format(q.eaveLength.value)}, joinery ${"%.1f".format(q.exteriorJoinery.value)}, facade gross ${"%.1f".format(q.facadeGross.value)} net ${"%.1f".format(q.facadeNet.value)}, floors+stairs ${"%.1f".format(q.floorsAndStairsArea.value)}")
            appendLine("== surfaces ${q.surfaces.size} (${q.surfaces.groupingBy { it.type }.eachCount()})")
        }
        appendLine("== validations")
        run.validations.forEach { v -> appendLine("  ${v.status.name.padEnd(19)} ${v.subject}: cand ${v.candidateValue?.let { "%.2f".format(it) }} vs src ${v.sourceValue} ${v.unit} ${v.relativeDifference?.let { "(%.1f %%)".format(it * 100) } ?: ""} — ${v.semantics}") }
        appendLine("== validation summary: ${run.validations.groupingBy { it.status }.eachCount()}")
        appendLine("== gaps score ${run.gaps?.completenessScore?.let { "%.0f %%".format(it * 100) }}")
        run.gaps?.statuses?.forEach { appendLine("  ${it.state.name.padEnd(14)} ${it.requirement}: ${it.evidence}") }
        appendLine("== questions ${run.questions.size}")
        run.questions.forEach { appendLine("  [${it.id}] ${it.text}${it.currentAssumption?.let { a -> " (założenie: $a)" } ?: ""}") }
        appendLine("== issues")
        c.issues.forEach { appendLine("  ${it.severity} ${it.stage} ${it.subject ?: ""}: ${it.message}") }
        appendLine("== dimensions ${c.dimensions.size}")
        c.dimensions.take(40).forEach { appendLine("  ${it.scope} / ${it.name} = ${it.measured.value?.let { v -> "%.2f".format(v) }} ${it.measured.unit} ${it.measured.fidelity}") }
        val mismatches = run.validations.count { it.status == ValidationStatus.MISMATCH }
        appendLine("== mismatches: $mismatches")
    }
}
