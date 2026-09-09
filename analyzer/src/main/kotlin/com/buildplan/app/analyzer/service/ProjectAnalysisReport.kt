package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.candidate.AnalysisIssue
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.quantity.ProjectQuantities
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.SourcePackage
import com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.analyzer.source.ResolutionStep
import com.buildplan.app.analyzer.source.SupportedSite
import com.buildplan.app.analyzer.validate.ClarificationQuestion
import com.buildplan.app.analyzer.validate.GapAnalysis
import com.buildplan.app.analyzer.validate.ValidationFinding
import com.buildplan.app.analyzer.validate.ValidationStatus

/**
 * Which build, which adapter and which project this report is about.
 *
 * All four versions are here because they invalidate different things. The
 * schema version changes the shape a stored report can be read back into; the
 * analyzer version changes the numbers a rerun would produce; the adapter
 * version changes what could be extracted from the page at all. A cache that
 * keyed on only one of them would replay an old parser's output and it would
 * look current.
 */
data class ReportIdentity(
    val schemaVersion: Int,
    val analyzerVersion: String,
    val adapterId: String,
    val adapterVersion: String,
    val site: SupportedSite,
    val projectKey: String,
    val canonicalUrl: String,
    /** The URL the user actually typed, kept beside the canonical one it resolved to. */
    val requestedUrl: String,
) {
    /**
     * The versions a cached artefact must still match to be replayable, as one
     * filesystem-safe token.
     */
    val cacheTag: String get() = "s$schemaVersion-a$analyzerVersion-$adapterId$adapterVersion".replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/**
 * What was true about *this run* rather than about the house.
 *
 * Kept apart from the structural data on purpose: a timestamp and a duration
 * differ between two runs of the same analysis over the same bytes, and if
 * they sat inside the candidate then no two runs could ever be compared. See
 * [ProjectAnalysisReport.deterministicJson].
 */
data class ReportGeneration(
    val generatedAtEpochMillis: Long,
    val sourceRetrievedAtEpochMillis: Long?,
    /** Whether the bytes this run read came from the app-private cache rather than the network. */
    val servedFromCache: Boolean,
    val durationsMillis: Map<String, Long>,
    val resolutionSteps: List<ResolutionStep>,
)

/**
 * One analysis, in the shape the application consumes.
 *
 * The durable part is [snapshot] — the same versioned record `SnapshotCodec`
 * writes and reads, and the only thing the cache stores. Everything else on
 * this type is *derived from it by a pure function*, which is what makes a
 * cached run and a fresh run indistinguishable to a caller, and what keeps
 * this boundary from becoming a second copy of the candidate model that has
 * to be maintained beside the first.
 *
 * What a consumer may rely on: this type, the value types it exposes
 * (`Measured`, `FactFidelity`, `Provenance`, the candidate and quantity
 * models) and the rest of `service/`. Not the raster thresholds, the skeleton
 * solver, the glyph recogniser or any ARCHON selector.
 */
data class ProjectAnalysisReport(
    val identity: ReportIdentity,
    val generation: ReportGeneration,
    val snapshot: ProjectAnalysisSnapshot,
    val adapterHealth: AdapterHealth,
    /** Every quantity a later stage might cost, with how far it may be trusted. */
    val quantityVerification: List<QuantityVerificationEntry>,
    /** Everything the source leaves open, with each reading it permits. Never collapsed. */
    val ambiguities: List<CandidateAmbiguity>,
) {
    val source: SourcePackage? get() = snapshot.source
    val candidate: ProjectAnalysisCandidate? get() = snapshot.candidate
    val quantities: ProjectQuantities? get() = snapshot.quantities
    val questions: List<ClarificationQuestion> get() = snapshot.questions
    val validations: List<ValidationFinding> get() = snapshot.validations
    val gaps: GapAnalysis? get() = snapshot.gaps
    val issues: List<AnalysisIssue> get() = candidate?.issues.orEmpty()

    fun verification(key: String): QuantityVerificationEntry? = quantityVerification.firstOrNull { it.key == key }

    /** Quantities no cost may rest on until a person has looked at them. */
    val unsafeForCosting: List<QuantityVerificationEntry> get() = quantityVerification.filterNot { it.state.safeForCosting }

    /**
     * The report as JSON with everything that varies between two runs of the
     * same analysis removed: timestamps, durations, the log, and the local
     * storage paths of downloaded drawings.
     *
     * Two runs over the same bytes must produce identical output here. That is
     * the strongest statement the analyzer can make about being deterministic,
     * and it is also the form that is safe to write into a bug report: no
     * device filesystem path leaves through it.
     */
    fun deterministicJson(): String = SnapshotCodec.write(withoutRunMetadata(snapshot))

    companion object {

        /** Strips everything that is about the run rather than about the house. */
        fun withoutRunMetadata(s: ProjectAnalysisSnapshot): ProjectAnalysisSnapshot = s.copy(
            createdAtEpochMillis = 0L,
            timingsMillis = emptyMap(),
            log = emptyList(),
            source = s.source?.let { src ->
                src.copy(
                    retrievedAtEpochMillis = 0L,
                    relatedPages = src.relatedPages.map { it.copy(retrievedAtEpochMillis = 0L) },
                    // A storage path names a directory on this device. It is a fact about where
                    // the file landed, not about the project, and it must not travel.
                    assets = src.assets.copy(assets = src.assets.assets.map { it.copy(storagePath = null) }),
                )
            },
        )

        /**
         * Builds the app-facing report from a durable snapshot.
         *
         * Returns null when the snapshot never got as far as reading a page:
         * without a source there is no identity to report, and the outcome
         * families already say what happened instead.
         */
        fun of(
            snapshot: ProjectAnalysisSnapshot,
            requestedUrl: String,
            servedFromCache: Boolean,
            generatedAtEpochMillis: Long,
        ): ProjectAnalysisReport? {
            val source = snapshot.source ?: return null
            val identity = ReportIdentity(
                schemaVersion = snapshot.schemaVersion,
                analyzerVersion = snapshot.analyzerVersion,
                adapterId = snapshot.adapterId,
                adapterVersion = snapshot.adapterVersion,
                site = source.identity.site,
                projectKey = source.identity.projectKey,
                canonicalUrl = source.identity.canonicalUrl,
                requestedUrl = requestedUrl,
            )
            val publishedFacade = source.scalars.firstOrNull { it.key == ScalarKey.FACADE_INSULATION_AREA }?.measured?.value
            return ProjectAnalysisReport(
                identity = identity,
                generation = ReportGeneration(
                    generatedAtEpochMillis = generatedAtEpochMillis,
                    sourceRetrievedAtEpochMillis = source.retrievedAtEpochMillis,
                    servedFromCache = servedFromCache,
                    durationsMillis = snapshot.timingsMillis,
                    resolutionSteps = snapshot.resolutionSteps,
                ),
                snapshot = snapshot,
                adapterHealth = AdapterHealth.of(snapshot.adapterId, snapshot.adapterVersion, source),
                quantityVerification = QuantityVerificationLedger.of(snapshot.candidate, snapshot.quantities, snapshot.validations),
                ambiguities = CandidateAmbiguities.of(snapshot.candidate, snapshot.quantities, publishedFacade),
            )
        }
    }
}

/**
 * Turns the candidate and its takeoff into one flat list of quantities, each
 * with a stable key, a verification state and the source's verdict where the
 * source published one.
 *
 * Flat on purpose. A costing screen asks "what may I price, and what must a
 * person confirm first?", and answering that by walking a tree of floors,
 * rooms, walls and facets is how an unverified assumption ends up in a budget
 * because one branch was missed.
 */
object QuantityVerificationLedger {

    fun of(
        candidate: ProjectAnalysisCandidate?,
        quantities: ProjectQuantities?,
        validations: List<ValidationFinding>,
    ): List<QuantityVerificationEntry> {
        if (candidate == null) return emptyList()
        val comparisons: Map<String, ValidationStatus> = validations.associate { it.key to it.status }
        val out = mutableListOf<QuantityVerificationEntry>()

        fun add(key: String, ownerId: String, measured: Measured, caveat: String? = null) {
            out += QuantityVerificationEntry(
                key = key,
                ownerId = ownerId,
                measured = measured,
                state = VerificationState.of(measured.fidelity),
                comparison = comparisons[key],
                caveat = caveat ?: caveatFor(measured),
            )
        }

        quantities?.rooms?.forEach { r ->
            add("room:${r.roomId}:floorArea", r.roomId, r.floorArea)
            add("room:${r.roomId}:perimeter", r.roomId, r.perimeter)
            add("room:${r.roomId}:wallFaceGross", r.roomId, r.wallGross)
            add("room:${r.roomId}:wallFaceOpenings", r.roomId, r.wallOpenings)
            add("room:${r.roomId}:wallFaceNet", r.roomId, r.wallNet)
            add("room:${r.roomId}:ceilingFlat", r.roomId, r.ceilingFlat)
            add("room:${r.roomId}:ceilingSloped", r.roomId, r.ceilingSloped)
            add("room:${r.roomId}:ceilingTotal", r.roomId, r.ceilingTotal)
            add("room:${r.roomId}:volume", r.roomId, r.volume)
            add("room:${r.roomId}:usableAreaByHeightRule", r.roomId, r.usableAreaByHeightRule)
        }

        quantities?.floors?.forEach { f ->
            add("floor:${f.floorId}:roomFloorAreaSum", f.floorId, f.roomFloorAreaSum)
            add("floor:${f.floorId}:exteriorWallsStructural", f.floorId, f.exteriorWallsStructural)
            add("floor:${f.floorId}:loadBearingWallsStructural", f.floorId, f.loadBearingWallsStructural)
            add("floor:${f.floorId}:partitionsStructural", f.floorId, f.partitionsStructural)
            add("floor:${f.floorId}:exteriorEnvelopeGross", f.floorId, f.exteriorEnvelopeGross)
            add("floor:${f.floorId}:exteriorEnvelopeNet", f.floorId, f.exteriorEnvelopeNet)
        }

        quantities?.let { q ->
            add(PROJECT_ROOF_AREA, PROJECT, q.roofTotal)
            add("project:ridgeLength", PROJECT, q.ridgeLength)
            add("project:hipLength", PROJECT, q.hipLength)
            add("project:eaveLength", PROJECT, q.eaveLength)
            add("project:exteriorJoinery", PROJECT, q.exteriorJoinery)
            add("project:facadeGross", PROJECT, q.facadeGross)
            add("project:facadeNet", PROJECT, q.facadeNet)
            add("project:exteriorWallMaterial", PROJECT, q.facadeWallMaterial)
            add("project:floorsAndStairs", PROJECT, q.floorsAndStairsArea)
            add("project:facadeInsulation", PROJECT, q.facadeScope.finishNet, caveat = FACADE_SCOPE_CAVEAT)
            add("project:loadBearingWalls", PROJECT, sum(q.floors.map { it.loadBearingWallsStructural }, "internal load-bearing walls, all storeys"))
            add("project:exteriorEnvelopeGross", PROJECT, sum(q.floors.map { it.exteriorEnvelopeGross }, "exterior envelope over the openings, all storeys"))
        }

        // Openings carry the stage's sharpest gap: the source prints their heights as text too
        // small to read, so a width that was traced sits beside a height that is simply absent.
        // Both are listed, and the absent one is listed as absent rather than filled in.
        candidate.openings.forEach { o ->
            add("opening:${o.id}:width", o.id, o.width)
            add("opening:${o.id}:height", o.id, o.height)
            add("opening:${o.id}:sillHeight", o.id, o.sillHeight)
        }

        candidate.levels.let { l ->
            add("project:terrainLevel", PROJECT, l.terrain)
            add("project:ridgeLevel", PROJECT, l.ridge)
            add("project:eaveLevel", PROJECT, l.eave)
            add("project:buildingHeight", PROJECT, l.buildingHeight)
            add("project:kneeWall", PROJECT, l.kneeWall)
            add("project:groundClearHeight", PROJECT, l.groundClearHeight)
            add("project:upperClearHeight", PROJECT, l.upperClearHeight)
        }
        return out
    }

    private const val PROJECT = "project"
    private const val PROJECT_ROOF_AREA = "project:roofArea"

    private const val FACADE_SCOPE_CAVEAT =
        "the envelope net of its openings; which scope the published facade figure priced may itself " +
            "be undecided — see the facade:scope ambiguity when one is reported"

    private fun caveatFor(m: Measured): String? = when (m.fidelity) {
        FactFidelity.DISPLAY_ASSUMPTION -> m.provenance.method
        FactFidelity.MISSING -> m.provenance.method
        FactFidelity.TRACE_UNCERTAIN -> m.note ?: m.provenance.method
        FactFidelity.CONFLICTING -> m.note ?: m.provenance.method
        else -> null
    }

    /**
     * Sums measurements that share a unit, inheriting the weakest fidelity.
     *
     * A sum in which one term is missing is not a smaller sum, it is an
     * incomplete one, so the result goes MISSING rather than quietly counting
     * the absent term as zero.
     */
    private fun sum(parts: List<Measured>, rule: String): Measured {
        if (parts.isEmpty()) return Measured.missing(MeasureUnit.SQUARE_METER, "$rule: nothing to sum")
        if (parts.any { it.value == null }) {
            return Measured.missing(parts.first().unit, "$rule: one or more storeys are missing this quantity")
        }
        return Measured(
            value = parts.sumOf { it.value ?: 0.0 },
            unit = parts.first().unit,
            fidelity = FactFidelity.weakest(parts.map { it.fidelity }),
            provenance = Provenance.derived(rule, parts.map { it.provenance.method }.distinct()),
        )
    }
}
