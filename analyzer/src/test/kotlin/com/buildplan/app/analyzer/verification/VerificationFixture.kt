package com.buildplan.app.analyzer.verification

import com.buildplan.app.analyzer.ArchonFixture
import com.buildplan.app.analyzer.candidate.Box
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.RingValidity
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.candidate.RoomMatchAlternative
import com.buildplan.app.analyzer.candidate.StairCandidate
import com.buildplan.app.analyzer.candidate.StairEvidence
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.service.AnalyzeProjectRequest
import com.buildplan.app.analyzer.service.AnalyzerServices
import com.buildplan.app.analyzer.service.ProjectAnalysisReport
import com.buildplan.app.analyzer.service.ServiceFixture
import com.buildplan.app.analyzer.site.RoomKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A report to verify: the synthetic fixture house through the real service,
 * then dressed with the situations a real house produces and the synthetic
 * drawing does not — a room two rows fit, a run of lines that may be a
 * stair, a region no row claims.
 *
 * Injected by editing a *copy* of the durable snapshot and rebuilding the
 * report from it, which is exactly the path a cached run takes, so nothing
 * here bypasses the report's own derivations.
 */
object VerificationFixture {

    /** The fixture report, freshly analysed. Slow (seconds); tests share one via [shared]. */
    fun analyse(): ProjectAnalysisReport {
        val root = File.createTempFile("analyzer-verify", "").let { it.delete(); it.mkdirs(); it }
        try {
            return runBlocking {
                val service = AnalyzerServices.create(
                    platform = ServiceFixture.platform(ServiceFixture.fetcher()),
                    cacheRoot = root,
                    dispatcher = Dispatchers.Default,
                )
                val outcome = withTimeout(120_000) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)) }
                requireNotNull(outcome.reportOrNull) { "expected a report, got $outcome" }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    val shared: ProjectAnalysisReport by lazy { analyse() }

    /** The shared report with every injected situation. */
    val dressed: ProjectAnalysisReport by lazy { dress(shared) }

    fun dress(report: ProjectAnalysisReport): ProjectAnalysisReport {
        val candidate = requireNotNull(report.candidate)
        val attic = candidate.floors.maxByOrNull { it.order } ?: error("fixture needs two storeys")
        val ground = candidate.floors.minByOrNull { it.order }!!
        val published = report.source!!.floors[candidate.floors.indexOf(attic)].rooms
        // The attic's largest unclaimed region becomes a room that two published rows of equal
        // area fit — the situation a real attic produces and the synthetic drawing does not.
        val region = attic.unmatchedRegions.maxByOrNull { it.areaM2 } ?: error("fixture attic needs an unclaimed region")
        val check = RingValidity.check(region.polygon.vertices)
        require(check.isValid) { "fixture region must be a simple ring: ${check.detail}" }
        val row = published[0]
        val other = 1
        val ambiguous = RoomCandidate(
            id = "${attic.id}-r1",
            floorId = attic.id,
            name = row.name,
            sourceOrdinal = row.ordinal,
            kind = RoomKind.BEDROOM,
            polygon = region.polygon,
            geometryState = RoomGeometryState.VALID_SIMPLE_RING,
            geometryNote = "single region",
            perimeter = Measured.traced(region.polygon.perimeter, MeasureUnit.METER, Provenance(attic.planAssetUrl, "region", "traced")),
            plannedArea = Measured.traced(region.areaM2, MeasureUnit.SQUARE_METER, Provenance(attic.planAssetUrl, "region", "pixels")),
            sourceUsableArea = row.usableArea,
            sourceFloorArea = row.floorArea,
            boundary = emptyList(),
            matchConfidence = FactFidelity.TRACE_UNCERTAIN,
            matchNote = "two published rows of equal area fit this region; assigned by table order",
            matchAlternatives = listOf(RoomMatchAlternative(other, published[other].name, published[other].floorArea.value ?: 26.0, 0.0, "equal published area")),
        )
        // A run of lines that may be a stair, on the ground floor.
        val stair = StairCandidate(
            id = "${ground.id}-s9", floorId = ground.id, zone = Box(1.0, 1.0, 2.0, 3.0),
            treadCount = Measured(9.0, MeasureUnit.COUNT, FactFidelity.TRACE_UNCERTAIN, Provenance(null, "stair zone", "count of parallel lines")),
            direction = "unknown", fromFloorId = ground.id, toFloorId = null, flights = emptyList(), roomId = ground.rooms.firstOrNull()?.id,
            evidence = setOf(StairEvidence.TREAD_LINES), fidelity = FactFidelity.TRACE_UNCERTAIN, note = "run of 9 parallel lines",
            unresolved = listOf("Równo rozstawione linie mogą być półkami."),
        )
        // A second window of the same width beside the first, so one family has two members.
        val window = candidate.openings.first { it.exterior && it.type == com.buildplan.app.analyzer.candidate.OpeningType.WINDOW }
        val twin = window.copy(id = "${window.id}-twin", distanceAlongWall = window.distanceAlongWall.copy(value = (window.distanceAlongWall.value ?: 0.0) + (window.width.value ?: 1.0) + 0.6))
        val dressed = candidate.copy(
            openings = candidate.openings + twin,
            walls = candidate.walls.map { w -> if (w.id == window.wallId) w.copy(openingIds = w.openingIds + twin.id) else w },
            floors = candidate.floors.map { f ->
                when (f.id) {
                    attic.id -> f.copy(rooms = f.rooms + ambiguous, unmatchedRegions = f.unmatchedRegions.filter { it.id != region.id })
                    else -> f
                }
            },
            stairs = candidate.stairs + stair,
        )
        return rebuild(report, dressed)
    }

    fun rebuild(report: ProjectAnalysisReport, candidate: ProjectAnalysisCandidate): ProjectAnalysisReport =
        requireNotNull(ProjectAnalysisReport.of(report.snapshot.copy(candidate = candidate), report.identity.requestedUrl, servedFromCache = false, generatedAtEpochMillis = 0L))
}
