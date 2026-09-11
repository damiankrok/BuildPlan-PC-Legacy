package com.buildplan.app.analyzer.snapshot

import com.buildplan.app.analyzer.candidate.*

import com.buildplan.app.analyzer.asset.AssetManifest
import com.buildplan.app.analyzer.asset.AssetRecord
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.candidate.AffectedRegion
import com.buildplan.app.analyzer.candidate.BuildingMassCandidate
import com.buildplan.app.analyzer.candidate.FacadeEnvelopeCandidate
import com.buildplan.app.analyzer.candidate.ReconstructionHypothesisScore
import com.buildplan.app.analyzer.candidate.SelfVerificationResult
import com.buildplan.app.analyzer.candidate.AnalysisIssue
import com.buildplan.app.analyzer.candidate.AppearanceCandidate
import com.buildplan.app.analyzer.candidate.AppearanceKind
import com.buildplan.app.analyzer.candidate.EvidenceRef
import com.buildplan.app.analyzer.candidate.FacadeAssignment
import com.buildplan.app.analyzer.candidate.FacadeSide
import com.buildplan.app.analyzer.candidate.NormalizedBox
import com.buildplan.app.analyzer.candidate.VisualAssetEvidence
import com.buildplan.app.analyzer.candidate.VisualConflict
import com.buildplan.app.analyzer.candidate.VisualConflictKind
import com.buildplan.app.analyzer.candidate.VisualConflictSeverity
import com.buildplan.app.analyzer.candidate.VisualEvidence
import com.buildplan.app.analyzer.candidate.VisualObservation
import com.buildplan.app.analyzer.candidate.VisualObservationKind
import com.buildplan.app.analyzer.candidate.VisualViewpoint
import com.buildplan.app.analyzer.candidate.Box
import com.buildplan.app.analyzer.candidate.CalibrationAnchor
import com.buildplan.app.analyzer.candidate.FloorCandidate
import com.buildplan.app.analyzer.candidate.IssueSeverity
import com.buildplan.app.analyzer.candidate.LevelsCandidate
import com.buildplan.app.analyzer.candidate.NamedDimension
import com.buildplan.app.analyzer.candidate.OpeningCandidate
import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.Pt3
import com.buildplan.app.analyzer.candidate.RegionCandidate
import com.buildplan.app.analyzer.candidate.RoofCandidate
import com.buildplan.app.analyzer.candidate.RoofFacetCandidate
import com.buildplan.app.analyzer.candidate.RoofFamily
import com.buildplan.app.analyzer.candidate.RoomBoundarySegment
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.candidate.RoomMatchAlternative
import com.buildplan.app.analyzer.candidate.SecondaryRoofMass
import com.buildplan.app.analyzer.candidate.Segment
import com.buildplan.app.analyzer.candidate.Segment3
import com.buildplan.app.analyzer.candidate.StairCandidate
import com.buildplan.app.analyzer.candidate.StairEvidence
import com.buildplan.app.analyzer.candidate.WallCandidate
import com.buildplan.app.analyzer.candidate.WallClass
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.quantity.FacadeScope
import com.buildplan.app.analyzer.quantity.FloorQuantities
import com.buildplan.app.analyzer.quantity.MeasuredSurfaceCandidate
import com.buildplan.app.analyzer.quantity.ProjectQuantities
import com.buildplan.app.analyzer.quantity.RoomQuantities
import com.buildplan.app.analyzer.quantity.SurfaceType
import com.buildplan.app.analyzer.site.ConstructionFact
import com.buildplan.app.analyzer.site.PublishedFloor
import com.buildplan.app.analyzer.site.PublishedRoom
import com.buildplan.app.analyzer.site.PublishedScalar
import com.buildplan.app.analyzer.site.RelatedPage
import com.buildplan.app.analyzer.site.RelatedPageRole
import com.buildplan.app.analyzer.site.RoomKind
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.SourcePackage
import com.buildplan.app.analyzer.source.ResolutionStep
import com.buildplan.app.analyzer.source.SourceIdentity
import com.buildplan.app.analyzer.source.SupportedSite
import com.buildplan.app.analyzer.validate.ClarificationQuestion
import com.buildplan.app.analyzer.validate.GapAnalysis
import com.buildplan.app.analyzer.validate.Requirement
import com.buildplan.app.analyzer.validate.RequirementState
import com.buildplan.app.analyzer.validate.RequirementStatus
import com.buildplan.app.analyzer.validate.ValidationFinding
import com.buildplan.app.analyzer.validate.ValidationStatus

/**
 * Everything one analysis run produced, serialisable and durable.
 *
 * This is the "save all dimensions and surfaces" format. It is not project
 * persistence — no canonical `Project`/`Building` is written — but a
 * self-describing record a later stage can load, compare and promote from.
 * [schemaVersion] changes when the shape changes; [analyzerVersion] when the
 * algorithms do, so two snapshots of one page can be told apart.
 */
data class ProjectAnalysisSnapshot(
    val schemaVersion: Int,
    val analyzerVersion: String,
    /** Which site adapter read the page, and at which revision of its selectors. */
    val adapterId: String,
    val adapterVersion: String,
    val createdAtEpochMillis: Long,
    val inputUrl: String,
    val resolutionSteps: List<ResolutionStep>,
    val source: SourcePackage?,
    val candidate: ProjectAnalysisCandidate?,
    val quantities: ProjectQuantities?,
    val validations: List<ValidationFinding>,
    val gaps: GapAnalysis?,
    val questions: List<ClarificationQuestion>,
    val log: List<String>,
    val timingsMillis: Map<String, Long>,
) {
    companion object {
        const val SCHEMA_VERSION = 5
        const val ANALYZER_VERSION = "0.5.0-stage025b-5"
    }
}

/**
 * Deterministic JSON in, deterministic JSON out. Keys are written in a fixed
 * order and numbers in their shortest form, so the same analysis gives the
 * same bytes and a diff between two runs is a diff between two analyses.
 */
object SnapshotCodec {

    fun toJson(s: ProjectAnalysisSnapshot): JsonValue.Obj = json {
        "schemaVersion" to s.schemaVersion
        "analyzerVersion" to s.analyzerVersion
        "adapterId" to s.adapterId
        "adapterVersion" to s.adapterVersion
        "createdAtEpochMillis" to s.createdAtEpochMillis
        "inputUrl" to s.inputUrl
        "resolutionSteps" toArray s.resolutionSteps.map { json { "kind" to it.kind; "detail" to it.detail } }
        "source" to s.source?.let(::sourceJson)
        "candidate" to s.candidate?.let(::candidateJson)
        "quantities" to s.quantities?.let(::quantitiesJson)
        "validations" toArray s.validations.map(::validationJson)
        "gaps" to s.gaps?.let { g -> json { "completenessScore" to g.completenessScore; "statuses" toArray g.statuses.map { st -> json { "requirement" to st.requirement; "category" to st.requirement.category; "state" to st.state; "fidelity" to st.fidelity; "evidence" to st.evidence } } } }
        "questions" toArray s.questions.map { q -> json { "id" to q.id; "requirement" to q.requirement; "text" to q.text; "currentAssumption" to q.currentAssumption; "subject" to q.subject } }
        "log" toStrings s.log
        "timingsMillis" to json { s.timingsMillis.forEach { (k, v) -> k to v } }
    }

    fun write(s: ProjectAnalysisSnapshot): String = Json.write(toJson(s))

    fun read(text: String): ProjectAnalysisSnapshot = fromJson(Json.parse(text) as JsonValue.Obj)

    fun fromJson(o: JsonValue.Obj): ProjectAnalysisSnapshot = ProjectAnalysisSnapshot(
        schemaVersion = o.int("schemaVersion") ?: 0,
        analyzerVersion = o.str("analyzerVersion") ?: "",
        adapterId = o.str("adapterId") ?: "",
        adapterVersion = o.str("adapterVersion") ?: "",
        createdAtEpochMillis = o.num("createdAtEpochMillis")?.toLong() ?: 0L,
        inputUrl = o.str("inputUrl") ?: "",
        resolutionSteps = o.arr("resolutionSteps")?.objects?.map { ResolutionStep(ResolutionStep.Kind.valueOf(it.str("kind")!!), it.str("detail") ?: "") }.orEmpty(),
        source = o.obj("source")?.let(::sourceFrom),
        candidate = o.obj("candidate")?.let(::candidateFrom),
        quantities = o.obj("quantities")?.let(::quantitiesFrom),
        validations = o.arr("validations")?.objects?.map(::validationFrom).orEmpty(),
        gaps = o.obj("gaps")?.let { g -> GapAnalysis(g.arr("statuses")?.objects?.map { st -> RequirementStatus(Requirement.valueOf(st.str("requirement")!!), RequirementState.valueOf(st.str("state")!!), st.str("fidelity")?.let(FactFidelity::valueOf), st.str("evidence") ?: "") }.orEmpty()) },
        questions = o.arr("questions")?.objects?.map { q -> ClarificationQuestion(q.str("id")!!, Requirement.valueOf(q.str("requirement")!!), q.str("text")!!, q.str("currentAssumption"), q.str("subject")) }.orEmpty(),
        log = o.arr("log")?.items?.map { (it as JsonValue.Str).value }.orEmpty(),
        timingsMillis = o.obj("timingsMillis")?.fields?.mapValues { (it.value as JsonValue.Num).value.toLong() }.orEmpty(),
    )

    // ------------------------------------------------------------ measured

    private fun measuredJson(m: Measured): JsonValue.Obj = json {
        "value" to m.value
        "unit" to m.unit
        "fidelity" to m.fidelity
        "uncertainty" to m.uncertainty
        "note" to m.note
        "provenance" to json { "sourceUrl" to m.provenance.sourceUrl; "locator" to m.provenance.locator; "method" to m.provenance.method }
    }

    private fun measuredFrom(o: JsonValue.Obj): Measured {
        val p = o.obj("provenance")!!
        return Measured(o.num("value"), MeasureUnit.valueOf(o.str("unit")!!), FactFidelity.valueOf(o.str("fidelity")!!), Provenance(p.str("sourceUrl"), p.str("locator")!!, p.str("method")!!), o.num("uncertainty"), o.str("note"))
    }

    private fun m(o: JsonValue.Obj, key: String): Measured = measuredFrom(o.obj(key)!!)

    private fun ptJson(p: Pt) = json { "x" to p.x; "z" to p.z }
    private fun ptFrom(o: JsonValue.Obj) = Pt(o.num("x")!!, o.num("z")!!)
    private fun pt3Json(p: Pt3) = json { "x" to p.x; "y" to p.y; "z" to p.z }
    private fun pt3From(o: JsonValue.Obj) = Pt3(o.num("x")!!, o.num("y")!!, o.num("z")!!)
    private fun polygonJson(p: Polygon) = JsonValue.Arr(p.vertices.map(::ptJson))
    private fun polygonFrom(a: JsonValue.Arr) = Polygon(a.objects.map(::ptFrom))
    private fun segJson(s: Segment) = json { "a" to ptJson(s.a); "b" to ptJson(s.b) }
    private fun segFrom(o: JsonValue.Obj) = Segment(ptFrom(o.obj("a")!!), ptFrom(o.obj("b")!!))
    private fun seg3Json(s: Segment3) = json { "a" to pt3Json(s.a); "b" to pt3Json(s.b) }
    private fun seg3From(o: JsonValue.Obj) = Segment3(pt3From(o.obj("a")!!), pt3From(o.obj("b")!!))
    private fun boxJson(b: Box) = json { "minX" to b.minX; "minZ" to b.minZ; "maxX" to b.maxX; "maxZ" to b.maxZ }
    private fun boxFrom(o: JsonValue.Obj) = Box(o.num("minX")!!, o.num("minZ")!!, o.num("maxX")!!, o.num("maxZ")!!)

    // -------------------------------------------------------------- source

    private fun sourceJson(s: SourcePackage): JsonValue.Obj = json {
        "identity" to json { "site" to s.identity.site; "projectKey" to s.identity.projectKey; "canonicalUrl" to s.identity.canonicalUrl }
        "title" to s.title
        "retrievedAtEpochMillis" to s.retrievedAtEpochMillis
        "scalars" toArray s.scalars.map { sc -> json { "key" to sc.key; "rawLabel" to sc.rawLabel; "rawValue" to sc.rawValue; "measured" to measuredJson(sc.measured) } }
        "construction" toArray s.construction.map { c -> json { "label" to c.label; "text" to c.text } }
        "floors" toArray s.floors.map { f ->
            json {
                "name" to f.name; "printedIndex" to f.printedIndex
                "usableAreaTotal" to measuredJson(f.usableAreaTotal); "floorAreaTotal" to measuredJson(f.floorAreaTotal)
                "rooms" toArray f.rooms.map { r -> json { "ordinal" to r.ordinal; "name" to r.name; "kind" to r.kind; "usableArea" to measuredJson(r.usableArea); "floorArea" to measuredJson(r.floorArea) } }
            }
        }
        "assets" toArray s.assets.assets.map { a ->
            json {
                "role" to a.role; "url" to a.url; "sourcePageUrl" to a.sourcePageUrl; "locator" to a.locator; "altText" to a.altText
                "declaredMediaType" to a.declaredMediaType; "retrieval" to a.retrieval; "byteCount" to a.byteCount; "sha256" to a.sha256
                "widthPx" to a.widthPx; "heightPx" to a.heightPx; "storagePath" to a.storagePath; "failure" to a.failure; "floorIndex" to a.floorIndex
            }
        }
        "relatedPages" toArray s.relatedPages.map { r -> json { "role" to r.role; "url" to r.url; "statusCode" to r.statusCode; "retrievedAtEpochMillis" to r.retrievedAtEpochMillis } }
        "siteTags" to json { s.siteTags.forEach { (k, v) -> k to v } }
    }

    private fun sourceFrom(o: JsonValue.Obj): SourcePackage {
        val id = o.obj("identity")!!
        return SourcePackage(
            identity = SourceIdentity(SupportedSite.valueOf(id.str("site")!!), id.str("projectKey")!!, id.str("canonicalUrl")!!),
            title = o.str("title") ?: "",
            retrievedAtEpochMillis = o.num("retrievedAtEpochMillis")?.toLong() ?: 0L,
            scalars = o.arr("scalars")?.objects?.map { sc -> PublishedScalar(ScalarKey.valueOf(sc.str("key")!!), sc.str("rawLabel")!!, sc.str("rawValue")!!, m(sc, "measured")) }.orEmpty(),
            construction = o.arr("construction")?.objects?.map { ConstructionFact(it.str("label")!!, it.str("text")!!) }.orEmpty(),
            floors = o.arr("floors")?.objects?.map { f ->
                PublishedFloor(f.str("name")!!, f.int("printedIndex")!!, m(f, "usableAreaTotal"), m(f, "floorAreaTotal"),
                    f.arr("rooms")?.objects?.map { r -> PublishedRoom(r.int("ordinal"), r.str("name")!!, m(r, "usableArea"), m(r, "floorArea"), r.str("kind")?.let(RoomKind::valueOf) ?: RoomKind.OTHER) }.orEmpty())
            }.orEmpty(),
            assets = AssetManifest(o.arr("assets")?.objects?.map { a ->
                AssetRecord(AssetRole.valueOf(a.str("role")!!), a.str("url")!!, a.str("sourcePageUrl")!!, a.str("locator")!!, a.str("altText"), a.str("declaredMediaType"),
                    RetrievalState.valueOf(a.str("retrieval")!!), a.num("byteCount")?.toLong(), a.str("sha256"), a.int("widthPx"), a.int("heightPx"), a.str("storagePath"), a.str("failure"), a.int("floorIndex"))
            }.orEmpty()),
            relatedPages = o.arr("relatedPages")?.objects?.map { RelatedPage(RelatedPageRole.valueOf(it.str("role")!!), it.str("url")!!, it.int("statusCode")!!, it.num("retrievedAtEpochMillis")?.toLong() ?: 0L) }.orEmpty(),
            siteTags = o.obj("siteTags")?.fields?.mapValues { (it.value as JsonValue.Str).value }.orEmpty(),
        )
    }

    // ----------------------------------------------------------- candidate

    private fun candidateJson(c: ProjectAnalysisCandidate): JsonValue.Obj = json {
        "floors" toArray c.floors.map(::floorJson)
        "walls" toArray c.walls.map { w ->
            json {
                "id" to w.id; "floorId" to w.floorId; "centreline" to segJson(w.centreline); "length" to measuredJson(w.length); "thickness" to measuredJson(w.thickness)
                "wallClass" to w.wallClass; "baseElevation" to measuredJson(w.baseElevation); "height" to measuredJson(w.height)
                "roomIdsLeft" toStrings w.roomIdsLeft; "roomIdsRight" toStrings w.roomIdsRight; "touchesOutside" to w.touchesOutside; "openingIds" toStrings w.openingIds; "fidelity" to w.fidelity
            }
        }
        "openings" toArray c.openings.map { o ->
            json {
                "id" to o.id; "wallId" to o.wallId; "floorId" to o.floorId; "type" to o.type; "distanceAlongWall" to measuredJson(o.distanceAlongWall); "width" to measuredJson(o.width)
                "height" to measuredJson(o.height); "sillHeight" to measuredJson(o.sillHeight); "linkedRoomIds" toStrings o.linkedRoomIds; "exterior" to o.exterior; "typeFidelity" to o.typeFidelity
            }
        }
        "stairs" toArray c.stairs.map { s -> json { "id" to s.id; "floorId" to s.floorId; "zone" to boxJson(s.zone); "treadCount" to measuredJson(s.treadCount); "direction" to s.direction; "fromFloorId" to s.fromFloorId; "toFloorId" to s.toFloorId; "flights" toArray s.flights.map(::boxJson); "roomId" to s.roomId; "evidence" toStrings s.evidence.map { e -> e.name }; "fidelity" to s.fidelity; "note" to s.note; "unresolved" toStrings s.unresolved } }
        "roof" to c.roof?.let(::roofJson)
        "levels" to levelsJson(c.levels)
        "dimensions" toArray c.dimensions.map { d -> json { "scope" to d.scope; "name" to d.name; "measured" to measuredJson(d.measured) } }
        "issues" toArray c.issues.map { i -> json { "severity" to i.severity; "stage" to i.stage; "subject" to i.subject; "message" to i.message } }
        "visual" to visualJson(c.visual)
        "reconstruction" to c.reconstruction?.let(::reconstructionJson)
        "openingGroups" toArray c.openingGroups.map { g -> json {
            "id" to g.id; "facadeId" to g.facadeId; "floorId" to g.floorId; "outer" to polygonJson(g.outer)
            "memberOpeningIds" toStrings g.memberOpeningIds; "panels" toArray g.panels.map(::polygonJson); "doorLeaf" to g.doorLeaf?.let(::polygonJson)
            "mullions" toArray g.mullions.map(::segJson); "evidenceIds" toStrings g.evidenceIds; "confidence" to g.confidence; "fidelity" to g.fidelity
        } }
        "facadeFeatures" toArray c.facadeFeatures.map { f -> json {
            "id" to f.id; "kind" to f.kind; "facadeId" to f.facadeId; "floorId" to f.floorId; "profile" to polygonJson(f.profile)
            "depth" to f.depth; "evidenceIds" toStrings f.evidenceIds; "confidence" to f.confidence; "fidelity" to f.fidelity; "depthFidelity" to f.depthFidelity
        } }
        "resolvedGeometry" to c.resolvedGeometry?.let { g -> json {
            "lineage" to g.lineage; "diagnostics" toStrings g.diagnostics
            "stairTopology" toArray g.stairTopology.map { s -> json {
                "stairId" to s.stairId; "fromFloorId" to s.fromFloorId; "toFloorId" to s.toFloorId; "stairwell" to polygonJson(s.stairwell)
                "flights" toArray s.flights.map(::polygonJson); "landings" toArray s.landings.map(::polygonJson); "slabOpenings" toArray s.slabOpenings.map(::polygonJson)
                "direction" to s.direction; "accepted" to s.accepted; "diagnostics" toStrings s.diagnostics
            } }
            "surfaces" toArray g.surfaces.map { s -> json {
                "id" to s.id; "ownerId" to s.ownerId; "floorId" to s.floorId; "kind" to s.kind
                "vertices" toArray s.vertices.map { p -> json { "x" to p.x; "y" to p.y; "z" to p.z } }
                "thickness" to s.thickness; "evidenceIds" toStrings s.evidenceIds; "fidelity" to s.fidelity; "roomId" to s.roomId; "exterior" to s.exterior
            } }
        } }
        "roofElements" toArray c.roofElements.map { e -> json {
            "id" to e.id; "kind" to e.kind; "roofFacetId" to e.roofFacetId; "footprint" to polygonJson(e.footprint)
            "vertices" toArray e.vertices.map { p -> json { "x" to p.x; "y" to p.y; "z" to p.z } }
            "evidenceIds" toStrings e.evidenceIds; "confidence" to e.confidence; "fidelity" to e.fidelity
        } }
        "masses" toArray c.masses.map { mass -> json {
            "id" to mass.id; "footprint" to polygonJson(mass.footprint); "baseLevel" to measuredJson(mass.baseLevel)
            "topLevel" to measuredJson(mass.topLevel); "roofKind" to mass.roofKind
            "adjacentMassIds" toStrings mass.adjacentMassIds; "sourceAssets" toStrings mass.sourceAssets; "confidence" to mass.confidence
        } }
        "facadeEnvelopes" toArray c.facadeEnvelopes.map { facade -> json {
            "id" to facade.id; "floorId" to facade.floorId; "segment" to segJson(facade.segment)
            "baseLevel" to measuredJson(facade.baseLevel); "thickness" to measuredJson(facade.thickness)
            "topProfile" toArray facade.topProfile.map { p -> json { "x" to p.x; "y" to p.y; "z" to p.z } }
            "openingIds" toStrings facade.openingIds
        } }
        "selfVerification" to c.selfVerification?.let { qa -> json {
            "sourceCoverage" to json { qa.sourceCoverage.forEach { (key, value) -> key to value } }
            "selectedHypothesis" to qa.selectedHypothesis
            "hypotheses" toArray qa.hypotheses.map { h -> json {
                "id" to h.id; "score" to h.score; "hardViolations" toStrings h.hardViolations
                "scores" to json { h.scores.forEach { (key, value) -> key to value } }
            } }
            "overallConfidence" to qa.overallConfidence; "selectionMargin" to qa.selectionMargin
            "unresolvedDiagnostics" toStrings qa.unresolvedDiagnostics; "iterations" to qa.iterations
            "sourceScores" to json { qa.sourceScores.forEach { (k,v)->k to v } }
            "metricResiduals" to json { qa.metricResiduals.forEach { (k,v)->k to v } }
            "hardViolations" toStrings qa.hardViolations
            "searchCounts" to json { qa.searchCounts.forEach { (k,v)->k to v } }
            "repairTrace" toArray qa.repairTrace.map { r -> json { "cycle" to r.cycle; "action" to r.action; "before" to r.before; "after" to r.after; "accepted" to r.accepted; "reason" to r.reason } }
        } }
    }

    private fun candidateFrom(o: JsonValue.Obj): ProjectAnalysisCandidate = ProjectAnalysisCandidate(
        floors = o.arr("floors")?.objects?.map(::floorFrom).orEmpty(),
        walls = o.arr("walls")?.objects?.map { w ->
            WallCandidate(w.str("id")!!, w.str("floorId")!!, segFrom(w.obj("centreline")!!), m(w, "length"), m(w, "thickness"), WallClass.valueOf(w.str("wallClass")!!), m(w, "baseElevation"), m(w, "height"),
                strings(w, "roomIdsLeft"), strings(w, "roomIdsRight"), w.bool("touchesOutside") ?: false, strings(w, "openingIds"), FactFidelity.valueOf(w.str("fidelity")!!))
        }.orEmpty(),
        openings = o.arr("openings")?.objects?.map { op ->
            OpeningCandidate(op.str("id")!!, op.str("wallId")!!, op.str("floorId")!!, OpeningType.valueOf(op.str("type")!!), m(op, "distanceAlongWall"), m(op, "width"), m(op, "height"), m(op, "sillHeight"),
                strings(op, "linkedRoomIds"), op.bool("exterior") ?: false, FactFidelity.valueOf(op.str("typeFidelity")!!))
        }.orEmpty(),
        stairs = o.arr("stairs")?.objects?.map { s ->
            StairCandidate(
                s.str("id")!!, s.str("floorId")!!, boxFrom(s.obj("zone")!!), m(s, "treadCount"), s.str("direction")!!,
                s.str("fromFloorId"), s.str("toFloorId"), s.arr("flights")?.objects?.map(::boxFrom).orEmpty(), s.str("roomId"),
                strings(s, "evidence").map(StairEvidence::valueOf).toSet(),
                FactFidelity.valueOf(s.str("fidelity")!!), s.str("note") ?: "", strings(s, "unresolved"),
            )
        }.orEmpty(),
        roof = o.obj("roof")?.let(::roofFrom),
        levels = levelsFrom(o.obj("levels")!!),
        dimensions = o.arr("dimensions")?.objects?.map { d -> NamedDimension(d.str("scope")!!, d.str("name")!!, m(d, "measured")) }.orEmpty(),
        issues = o.arr("issues")?.objects?.map { i -> AnalysisIssue(IssueSeverity.valueOf(i.str("severity")!!), i.str("stage")!!, i.str("subject"), i.str("message")!!) }.orEmpty(),
        visual = o.obj("visual")?.let(::visualFrom) ?: VisualEvidence.NONE,
        reconstruction = o.obj("reconstruction")?.let(::reconstructionFrom),
        openingGroups = o.arr("openingGroups")?.objects?.map { g -> OpeningGroupCandidate(g.str("id")!!,g.str("facadeId")!!,g.str("floorId")!!,polygonFrom(g.arr("outer")!!),
            strings(g,"memberOpeningIds"),g.arr("panels")!!.items.map { polygonFrom(it as JsonValue.Arr) },g.arr("doorLeaf")?.let(::polygonFrom),g.arr("mullions")!!.objects.map(::segFrom),strings(g,"evidenceIds"),g.num("confidence")!!,FactFidelity.valueOf(g.str("fidelity")!!)) }.orEmpty(),
        facadeFeatures = o.arr("facadeFeatures")?.objects?.map { f -> FacadeFeatureCandidate(f.str("id")!!,FacadeFeatureKind.valueOf(f.str("kind")!!),f.str("facadeId")!!,f.str("floorId")!!,polygonFrom(f.arr("profile")!!),
            f.num("depth")!!,strings(f,"evidenceIds"),f.num("confidence")!!,FactFidelity.valueOf(f.str("fidelity")!!),FactFidelity.valueOf(f.str("depthFidelity")!!)) }.orEmpty(),
        resolvedGeometry = o.obj("resolvedGeometry")?.let { g -> ResolvedBuildingGeometry(g.arr("surfaces")!!.objects.map { s -> ResolvedSurface(s.str("id")!!,s.str("ownerId")!!,s.str("floorId"),ResolvedSurfaceKind.valueOf(s.str("kind")!!),
            s.arr("vertices")!!.objects.map { Pt3(it.num("x")!!,it.num("y")!!,it.num("z")!!) },s.num("thickness")!!,strings(s,"evidenceIds"),FactFidelity.valueOf(s.str("fidelity")!!),s.str("roomId"),s.bool("exterior")!!) },g.str("lineage")!!,strings(g,"diagnostics"),g.arr("stairTopology")?.objects?.map { s ->
                StairTopologyCandidate(s.str("stairId")!!,s.str("fromFloorId"),s.str("toFloorId"),polygonFrom(s.arr("stairwell")!!),s.arr("flights")!!.items.map { polygonFrom(it as JsonValue.Arr) },s.arr("landings")!!.items.map { polygonFrom(it as JsonValue.Arr) },s.arr("slabOpenings")!!.items.map { polygonFrom(it as JsonValue.Arr) },s.str("direction")!!,s.bool("accepted")!!,strings(s,"diagnostics"))
            }.orEmpty()) },
        roofElements = o.arr("roofElements")?.objects?.map { e -> RoofElementCandidate(e.str("id")!!,RoofElementKind.valueOf(e.str("kind")!!),e.str("roofFacetId")!!,
            e.arr("vertices")!!.objects.map { Pt3(it.num("x")!!,it.num("y")!!,it.num("z")!!) },polygonFrom(e.arr("footprint")!!),strings(e,"evidenceIds"),e.num("confidence")!!,FactFidelity.valueOf(e.str("fidelity")!!)) }.orEmpty(),
        masses = o.arr("masses")?.objects?.map { mass -> BuildingMassCandidate(
            mass.str("id")!!, polygonFrom(mass.arr("footprint")!!), m(mass, "baseLevel"), m(mass, "topLevel"),
            RoofFamily.valueOf(mass.str("roofKind")!!), strings(mass, "adjacentMassIds"), strings(mass, "sourceAssets"), mass.num("confidence")!!,
        ) }.orEmpty(),
        facadeEnvelopes = o.arr("facadeEnvelopes")?.objects?.map { f -> FacadeEnvelopeCandidate(
            f.str("id")!!, f.str("floorId")!!, segFrom(f.obj("segment")!!), m(f, "baseLevel"), m(f, "thickness"),
            f.arr("topProfile")!!.objects.map { p -> Pt3(p.num("x")!!, p.num("y")!!, p.num("z")!!) }, strings(f, "openingIds"),
        ) }.orEmpty(),
        selfVerification = o.obj("selfVerification")?.let { qa -> SelfVerificationResult(
            qa.obj("sourceCoverage")!!.fields.mapValues { (_, v) -> (v as JsonValue.Num).value.toInt() }, qa.str("selectedHypothesis")!!,
            qa.arr("hypotheses")!!.objects.map { h -> ReconstructionHypothesisScore(h.str("id")!!, h.num("score")!!, strings(h, "hardViolations"),
                h.obj("scores")!!.fields.mapValues { (_, v) -> (v as JsonValue.Num).value }) },
            qa.num("overallConfidence")!!, qa.num("selectionMargin")!!, strings(qa, "unresolvedDiagnostics"), qa.int("iterations")!!,
            qa.obj("sourceScores")?.fields?.mapValues { (_,v)->(v as JsonValue.Num).value }.orEmpty(),
            qa.obj("metricResiduals")?.fields?.mapValues { (_,v)->(v as JsonValue.Num).value }.orEmpty(),strings(qa,"hardViolations"),
            qa.arr("repairTrace")?.objects?.map { r -> RepairRecord(r.int("cycle")!!,r.str("action")!!,r.num("before")!!,r.num("after")!!,r.bool("accepted")!!,r.str("reason")!!) }.orEmpty(),
            qa.obj("searchCounts")?.fields?.mapValues { (_,v)->(v as JsonValue.Num).value.toInt() }.orEmpty(),
        ) },
    )

    private fun strings(o: JsonValue.Obj, key: String): List<String> = o.arr(key)?.items?.map { (it as JsonValue.Str).value }.orEmpty()

    private fun reconstructionJson(s: ReconstructionState): JsonValue.Obj = json {
        "nodes" toArray s.graph.nodes.map { n -> json {
            "id" to n.id; "kind" to n.kind; "sourceId" to n.sourceId; "sourceClass" to n.sourceClass
            "fidelity" to n.fidelity; "confidence" to n.confidence; "method" to n.method; "uncertainty" to n.uncertainty
            "contradiction" to n.contradiction; "geometry" toArray n.geometry.map { p -> json { "x" to p.x; "z" to p.z } }
            "frame" to n.frame; "measurement" to n.measurement?.let(::measuredJson); "semantic" to n.semantic
        } }
        "edges" toArray s.graph.edges.map { e -> json { "from" to e.from; "to" to e.to; "relation" to e.relation; "confidence" to e.confidence; "reason" to e.reason } }
        "regions" toArray s.masses.map { m -> json {
            "id" to m.id; "outline" to polygonJson(m.outline); "base" to m.base; "top" to m.top; "use" to m.use; "roof" to m.roof; "floorId" to m.floorId
            "evidenceIds" toStrings m.evidenceIds; "confidence" to m.confidence; "adjacentIds" toStrings m.adjacentIds; "overlappingIds" toStrings m.overlappingIds
        } }
        "policy" to json {
            val p=s.policy
            "version" to p.version; "initialHypotheses" to p.initialHypotheses; "beamWidth" to p.beamWidth; "repairCycles" to p.repairCycles
            "localAlternatives" to p.localAlternatives; "hypothesisEvaluations" to p.hypothesisEvaluations; "cameraProjections" to p.cameraProjections
            "rasterSize" to p.rasterSize; "improvementEpsilon" to p.improvementEpsilon
            "weights" to json { p.weights.forEach { (k,v) -> k.name to v } }
        }
    }
    private fun reconstructionFrom(o: JsonValue.Obj): ReconstructionState {
        val nodes=o.arr("nodes")!!.objects.map { n -> EvidenceNode(n.str("id")!!,EvidenceKind.valueOf(n.str("kind")!!),n.str("sourceId")!!,
            EvidenceClass.valueOf(n.str("sourceClass")!!),FactFidelity.valueOf(n.str("fidelity")!!),n.num("confidence")!!,n.str("method")!!,n.num("uncertainty"),
            ContradictionStatus.valueOf(n.str("contradiction")!!),n.arr("geometry")!!.objects.map { Pt(it.num("x")!!,it.num("z")!!) },n.str("frame")!!,
            n.obj("measurement")?.let(::measuredFrom),n.str("semantic")!!) }
        val edges=o.arr("edges")!!.objects.map { e -> EvidenceEdge(e.str("from")!!,e.str("to")!!,EvidenceRelation.valueOf(e.str("relation")!!),e.num("confidence")!!,e.str("reason")!!) }
        val regions=o.arr("regions")!!.objects.map { m -> MassRegion(m.str("id")!!,polygonFrom(m.arr("outline")!!),m.num("base")!!,m.num("top")!!,
            MassUse.valueOf(m.str("use")!!),RoofFamily.valueOf(m.str("roof")!!),m.str("floorId")!!,strings(m,"evidenceIds"),m.num("confidence")!!,strings(m,"adjacentIds"),strings(m,"overlappingIds")) }
        val p=o.obj("policy")!!
        return ReconstructionState(EvidenceGraph(nodes,edges),regions,ReconstructionPolicy(p.str("version")!!,p.int("initialHypotheses")!!,p.int("beamWidth")!!,p.int("repairCycles")!!,
            p.int("localAlternatives")!!,p.int("hypothesisEvaluations")!!,p.int("cameraProjections")!!,p.int("rasterSize")!!,p.num("improvementEpsilon")!!,
            p.obj("weights")!!.fields.map { (k,v) -> EvidenceClass.valueOf(k) to (v as JsonValue.Num).value }.toMap()))
    }

    private fun floorJson(f: FloorCandidate): JsonValue.Obj = json {
        "id" to f.id; "name" to f.name; "order" to f.order
        "calibration" to f.calibration?.let { c ->
            json {
                "pixelsPerMeter" to measuredJson(c.pixelsPerMeter); "originPx" to ptJson(c.originPx); "method" to c.method
                "anchors" toArray c.anchors.map { a -> json { "kind" to a.kind; "sourceValue" to measuredJson(a.sourceValue); "measuredPixels" to (if (a.measuredPixels.isNaN()) null else a.measuredPixels); "impliedPixelsPerMeter" to a.impliedPixelsPerMeter } }
                "residual" to c.residual; "confidence" to c.confidence
            }
        }
        "footprint" to f.footprint?.let(::polygonJson)
        "roofOutline" to f.roofOutline?.let(::polygonJson)
        "floorElevation" to measuredJson(f.floorElevation); "clearHeight" to measuredJson(f.clearHeight)
        "rooms" toArray f.rooms.map { r ->
            json {
                "id" to r.id; "floorId" to r.floorId; "name" to r.name; "sourceOrdinal" to r.sourceOrdinal; "kind" to r.kind; "polygon" to r.polygon?.let(::polygonJson)
                "geometryState" to r.geometryState; "geometryNote" to r.geometryNote
                "perimeter" to measuredJson(r.perimeter); "plannedArea" to measuredJson(r.plannedArea); "sourceUsableArea" to measuredJson(r.sourceUsableArea); "sourceFloorArea" to measuredJson(r.sourceFloorArea)
                "boundary" toArray r.boundary.map { b -> json { "segment" to segJson(b.segment); "wallId" to b.wallId; "neighbourRoomId" to b.neighbourRoomId; "faceOutside" to b.faceOutside } }
                "matchConfidence" to r.matchConfidence; "matchNote" to r.matchNote
                "matchAlternatives" toArray r.matchAlternatives.map { a -> json { "sourceRowIndex" to a.sourceRowIndex; "roomName" to a.roomName; "publishedAreaM2" to a.publishedAreaM2; "relativeError" to a.relativeError; "why" to a.why } }
            }
        }
        "unmatchedRegions" toArray f.unmatchedRegions.map { u -> json { "id" to u.id; "polygon" to polygonJson(u.polygon); "areaM2" to u.areaM2; "boundaryWallIds" toStrings u.boundaryWallIds } }
        "planAssetUrl" to f.planAssetUrl
    }

    private fun floorFrom(o: JsonValue.Obj): FloorCandidate = FloorCandidate(
        id = o.str("id")!!, name = o.str("name")!!, order = o.int("order")!!,
        calibration = o.obj("calibration")?.let { c ->
            PlanCalibration(m(c, "pixelsPerMeter"), ptFrom(c.obj("originPx")!!), c.str("method")!!,
                c.arr("anchors")?.objects?.map { a -> CalibrationAnchor(a.str("kind")!!, m(a, "sourceValue"), a.num("measuredPixels") ?: Double.NaN, a.num("impliedPixelsPerMeter")!!) }.orEmpty(),
                c.num("residual"), FactFidelity.valueOf(c.str("confidence")!!))
        },
        footprint = o.arr("footprint")?.let(::polygonFrom),
        roofOutline = o.arr("roofOutline")?.let(::polygonFrom),
        floorElevation = m(o, "floorElevation"), clearHeight = m(o, "clearHeight"),
        rooms = o.arr("rooms")?.objects?.map { r ->
            RoomCandidate(r.str("id")!!, r.str("floorId")!!, r.str("name")!!, r.int("sourceOrdinal"), r.str("kind")?.let(RoomKind::valueOf) ?: RoomKind.OTHER, r.arr("polygon")?.let(::polygonFrom),
                RoomGeometryState.valueOf(r.str("geometryState") ?: RoomGeometryState.VALID_SIMPLE_RING.name), r.str("geometryNote") ?: "",
                m(r, "perimeter"), m(r, "plannedArea"), m(r, "sourceUsableArea"), m(r, "sourceFloorArea"),
                r.arr("boundary")?.objects?.map { b -> RoomBoundarySegment(segFrom(b.obj("segment")!!), b.str("wallId"), b.str("neighbourRoomId"), b.bool("faceOutside") ?: false) }.orEmpty(),
                FactFidelity.valueOf(r.str("matchConfidence")!!), r.str("matchNote") ?: "",
                r.arr("matchAlternatives")?.objects?.map { a -> RoomMatchAlternative(a.int("sourceRowIndex") ?: -1, a.str("roomName") ?: "", a.num("publishedAreaM2") ?: 0.0, a.num("relativeError") ?: 0.0, a.str("why") ?: "") }.orEmpty())
        }.orEmpty(),
        unmatchedRegions = o.arr("unmatchedRegions")?.objects?.map { u -> RegionCandidate(u.str("id")!!, polygonFrom(u.arr("polygon")!!), u.num("areaM2")!!, strings(u, "boundaryWallIds")) }.orEmpty(),
        planAssetUrl = o.str("planAssetUrl"),
    )

    private fun facadeScopeJson(s: FacadeScope) = json {
        "exteriorStructuralWall" to measuredJson(s.exteriorStructuralWall); "finishGross" to measuredJson(s.finishGross); "finishNet" to measuredJson(s.finishNet)
        "openingDeduction" to measuredJson(s.openingDeduction); "gableFace" to measuredJson(s.gableFace); "garageExterior" to measuredJson(s.garageExterior)
        "secondaryMassExterior" to measuredJson(s.secondaryMassExterior); "plinth" to measuredJson(s.plinth)
    }

    private fun facadeScopeFrom(o: JsonValue.Obj) = FacadeScope(
        m(o, "exteriorStructuralWall"), m(o, "finishGross"), m(o, "finishNet"), m(o, "openingDeduction"),
        m(o, "gableFace"), m(o, "garageExterior"), m(o, "secondaryMassExterior"), m(o, "plinth"),
    )

    private fun facetJson(f: RoofFacetCandidate) = json { "id" to f.id; "vertices" toArray f.vertices.map(::pt3Json); "areaM2" to f.areaM2; "eaveEdge" to segJson(f.eaveEdge) }
    private fun facetFrom(o: JsonValue.Obj) = RoofFacetCandidate(o.str("id")!!, o.arr("vertices")!!.objects.map(::pt3From), o.num("areaM2")!!, segFrom(o.obj("eaveEdge")!!))

    private fun roofJson(r: RoofCandidate): JsonValue.Obj = json {
        "family" to r.family; "pitchDegrees" to measuredJson(r.pitchDegrees); "outline" to polygonJson(r.outline)
        "eaveElevation" to measuredJson(r.eaveElevation); "ridgeElevation" to measuredJson(r.ridgeElevation)
        "facets" toArray r.facets.map(::facetJson); "totalArea" to measuredJson(r.totalArea)
        "ridgeLines" toArray r.ridgeLines.map(::seg3Json); "hipLines" toArray r.hipLines.map(::seg3Json); "eaveLength" to measuredJson(r.eaveLength)
        "gableEdgeIndices" toNumbers r.gableEdgeIndices.map { it.toDouble() }; "fidelity" to r.fidelity; "note" to r.note
        "secondaryMasses" toArray r.secondaryMasses.map { s -> json { "id" to s.id; "outline" to polygonJson(s.outline); "family" to s.family; "facets" toArray s.facets.map(::facetJson); "topElevation" to measuredJson(s.topElevation); "fidelity" to s.fidelity; "note" to s.note } }
    }

    private fun roofFrom(o: JsonValue.Obj): RoofCandidate = RoofCandidate(
        RoofFamily.valueOf(o.str("family")!!), m(o, "pitchDegrees"), polygonFrom(o.arr("outline")!!), m(o, "eaveElevation"), m(o, "ridgeElevation"),
        o.arr("facets")!!.objects.map(::facetFrom), m(o, "totalArea"), o.arr("ridgeLines")!!.objects.map(::seg3From), o.arr("hipLines")!!.objects.map(::seg3From), m(o, "eaveLength"),
        o.arr("gableEdgeIndices")!!.items.map { (it as JsonValue.Num).value.toInt() }, FactFidelity.valueOf(o.str("fidelity")!!), o.str("note") ?: "",
        o.arr("secondaryMasses")?.objects?.map { s -> SecondaryRoofMass(s.str("id")!!, polygonFrom(s.arr("outline")!!), RoofFamily.valueOf(s.str("family")!!), s.arr("facets")!!.objects.map(::facetFrom), m(s, "topElevation"), FactFidelity.valueOf(s.str("fidelity")!!), s.str("note") ?: "") }.orEmpty(),
    )

    private fun levelsJson(l: LevelsCandidate) = json {
        "terrain" to measuredJson(l.terrain); "groundFloor" to measuredJson(l.groundFloor); "upperFloor" to measuredJson(l.upperFloor)
        "groundClearHeight" to measuredJson(l.groundClearHeight); "upperClearHeight" to measuredJson(l.upperClearHeight); "upperSlabThickness" to measuredJson(l.upperSlabThickness)
        "kneeWall" to measuredJson(l.kneeWall); "eave" to measuredJson(l.eave); "ridge" to measuredJson(l.ridge); "buildingHeight" to measuredJson(l.buildingHeight)
        "atticFlatCeilingHeight" to measuredJson(l.atticFlatCeilingHeight); "notes" toStrings l.notes
    }

    private fun levelsFrom(o: JsonValue.Obj) = LevelsCandidate(
        m(o, "terrain"), m(o, "groundFloor"), m(o, "upperFloor"), m(o, "groundClearHeight"), m(o, "upperClearHeight"), m(o, "upperSlabThickness"),
        m(o, "kneeWall"), m(o, "eave"), m(o, "ridge"), m(o, "buildingHeight"), m(o, "atticFlatCeilingHeight"), strings(o, "notes"),
    )

    // ---------------------------------------------------------- quantities

    private fun surfaceJson(s: MeasuredSurfaceCandidate) = json {
        "id" to s.id; "type" to s.type; "ownerId" to s.ownerId; "roomId" to s.roomId; "wallId" to s.wallId; "neighbourRoomId" to s.neighbourRoomId; "facesOutside" to s.facesOutside
        "basis" to s.basis; "grossArea" to measuredJson(s.grossArea); "deductions" to measuredJson(s.deductions); "netArea" to measuredJson(s.netArea); "semantics" to s.semantics
    }

    private fun surfaceFrom(o: JsonValue.Obj) = MeasuredSurfaceCandidate(
        o.str("id")!!, SurfaceType.valueOf(o.str("type")!!), o.str("ownerId")!!, o.str("roomId"), o.str("wallId"), o.str("neighbourRoomId"), o.bool("facesOutside") ?: false,
        o.str("basis") ?: "", m(o, "grossArea"), m(o, "deductions"), m(o, "netArea"), o.str("semantics") ?: "",
    )

    private fun quantitiesJson(q: ProjectQuantities): JsonValue.Obj = json {
        "surfaces" toArray q.surfaces.map(::surfaceJson)
        "rooms" toArray q.rooms.map { r ->
            json {
                "roomId" to r.roomId; "floorArea" to measuredJson(r.floorArea); "perimeter" to measuredJson(r.perimeter); "wallFaceIds" toStrings r.wallFaceIds
                "wallGross" to measuredJson(r.wallGross); "wallOpenings" to measuredJson(r.wallOpenings); "wallNet" to measuredJson(r.wallNet)
                "ceilingFlat" to measuredJson(r.ceilingFlat); "ceilingSloped" to measuredJson(r.ceilingSloped); "ceilingTotal" to measuredJson(r.ceilingTotal)
                "volume" to measuredJson(r.volume); "usableAreaByHeightRule" to measuredJson(r.usableAreaByHeightRule); "meanHeight" to measuredJson(r.meanHeight); "boundaryLengths" toNumbers r.boundaryLengths
            }
        }
        "floors" toArray q.floors.map { f ->
            json {
                "floorId" to f.floorId; "roomFloorAreaSum" to measuredJson(f.roomFloorAreaSum); "exteriorWallsStructural" to measuredJson(f.exteriorWallsStructural)
                "loadBearingWallsStructural" to measuredJson(f.loadBearingWallsStructural); "partitionsStructural" to measuredJson(f.partitionsStructural); "exteriorEnvelopeGross" to measuredJson(f.exteriorEnvelopeGross); "exteriorEnvelopeNet" to measuredJson(f.exteriorEnvelopeNet)
                "openingAreasByType" to json { f.openingAreasByType.forEach { (k, v) -> k.name to v } }
            }
        }
        "roofFacetAreas" toArray q.roofFacetAreas.map { (id, a) -> json { "id" to id; "areaM2" to a } }
        "roofTotal" to measuredJson(q.roofTotal); "ridgeLength" to measuredJson(q.ridgeLength); "hipLength" to measuredJson(q.hipLength); "eaveLength" to measuredJson(q.eaveLength)
        "exteriorJoinery" to measuredJson(q.exteriorJoinery); "facadeGross" to measuredJson(q.facadeGross); "facadeNet" to measuredJson(q.facadeNet); "facadeWallMaterial" to measuredJson(q.facadeWallMaterial); "facadeScope" to facadeScopeJson(q.facadeScope); "floorsAndStairsArea" to measuredJson(q.floorsAndStairsArea)
        "notes" toStrings q.notes
    }

    private fun quantitiesFrom(o: JsonValue.Obj): ProjectQuantities = ProjectQuantities(
        surfaces = o.arr("surfaces")?.objects?.map(::surfaceFrom).orEmpty(),
        rooms = o.arr("rooms")?.objects?.map { r ->
            RoomQuantities(r.str("roomId")!!, m(r, "floorArea"), m(r, "perimeter"), strings(r, "wallFaceIds"), m(r, "wallGross"), m(r, "wallOpenings"), m(r, "wallNet"),
                m(r, "ceilingFlat"), m(r, "ceilingSloped"), m(r, "ceilingTotal"), m(r, "volume"), m(r, "usableAreaByHeightRule"), m(r, "meanHeight"),
                r.arr("boundaryLengths")?.items?.map { (it as JsonValue.Num).value }.orEmpty())
        }.orEmpty(),
        floors = o.arr("floors")?.objects?.map { f ->
            FloorQuantities(f.str("floorId")!!, m(f, "roomFloorAreaSum"), m(f, "exteriorWallsStructural"), m(f, "loadBearingWallsStructural"), m(f, "partitionsStructural"), m(f, "exteriorEnvelopeGross"), m(f, "exteriorEnvelopeNet"),
                f.obj("openingAreasByType")?.fields?.map { (k, v) -> OpeningType.valueOf(k) to (v as JsonValue.Num).value }?.toMap().orEmpty())
        }.orEmpty(),
        roofFacetAreas = o.arr("roofFacetAreas")?.objects?.map { it.str("id")!! to it.num("areaM2")!! }.orEmpty(),
        roofTotal = m(o, "roofTotal"), ridgeLength = m(o, "ridgeLength"), hipLength = m(o, "hipLength"), eaveLength = m(o, "eaveLength"),
        exteriorJoinery = m(o, "exteriorJoinery"), facadeGross = m(o, "facadeGross"), facadeNet = m(o, "facadeNet"), facadeWallMaterial = m(o, "facadeWallMaterial"), facadeScope = facadeScopeFrom(o.obj("facadeScope")!!), floorsAndStairsArea = m(o, "floorsAndStairsArea"),
        notes = strings(o, "notes"),
    )

    private fun validationJson(v: ValidationFinding) = json {
        "key" to v.key; "subject" to v.subject; "candidateValue" to v.candidateValue; "sourceValue" to v.sourceValue; "unit" to v.unit
        "absoluteDifference" to v.absoluteDifference; "relativeDifference" to v.relativeDifference; "status" to v.status; "semantics" to v.semantics; "candidateFidelity" to v.candidateFidelity
    }

    private fun validationFrom(o: JsonValue.Obj) = ValidationFinding(
        o.str("key") ?: "", o.str("subject")!!, o.num("candidateValue"), o.num("sourceValue"), o.str("unit") ?: "", o.num("absoluteDifference"), o.num("relativeDifference"),
        ValidationStatus.valueOf(o.str("status")!!), o.str("semantics") ?: "", o.str("candidateFidelity")?.let(FactFidelity::valueOf),
    )
}

/**
 * The visual block: what the pictures showed, which facade each shows, the
 * conflicts and the appearance proposals. Kept in the same codec so the
 * snapshot stays one record that round-trips byte for byte.
 */
private object VisualCodec {

    fun boxJson(b: NormalizedBox) = json { "left" to b.left; "top" to b.top; "right" to b.right; "bottom" to b.bottom }
    fun boxFrom(o: JsonValue.Obj) = NormalizedBox(o.num("left")!!, o.num("top")!!, o.num("right")!!, o.num("bottom")!!)

    fun observationJson(v: VisualObservation) = json {
        "kind" to v.kind; "bounds" to boxJson(v.bounds); "confidence" to v.confidence; "method" to v.method; "fidelity" to v.fidelity; "note" to v.note
        "rejectedAlternatives" toStrings v.rejectedAlternatives
        "outline" toArray v.outline.map { json { "x" to it.x; "z" to it.z } }
    }

    fun observationFrom(o: JsonValue.Obj) = VisualObservation(
        VisualObservationKind.valueOf(o.str("kind")!!), boxFrom(o.obj("bounds")!!), o.num("confidence") ?: 0.0, o.str("method") ?: "", FactFidelity.valueOf(o.str("fidelity")!!),
        o.str("note") ?: "", o.arr("rejectedAlternatives")?.items?.map { (it as JsonValue.Str).value }.orEmpty(),
        o.arr("outline")?.objects?.map { Pt(it.num("x")!!,it.num("z")!!) }.orEmpty(),
    )

    fun assetJson(a: VisualAssetEvidence) = json {
        "assetUrl" to a.assetUrl; "role" to a.role; "viewpoint" to a.viewpoint; "widthPx" to a.widthPx; "heightPx" to a.heightPx
        "observations" toArray a.observations.map(::observationJson); "confidence" to a.confidence; "fidelity" to a.fidelity; "notes" toStrings a.notes
        "structuralMask" to a.structuralMask?.let { m -> json { "size" to m.size; "runs" toNumbers m.runs.map { it.toDouble() }; "excludedRuns" toNumbers m.excludedRuns.map { it.toDouble() } } }
    }

    fun assetFrom(o: JsonValue.Obj) = VisualAssetEvidence(
        o.str("assetUrl")!!, AssetRole.valueOf(o.str("role")!!), VisualViewpoint.valueOf(o.str("viewpoint")!!), o.int("widthPx") ?: 0, o.int("heightPx") ?: 0,
        o.arr("observations")?.objects?.map(::observationFrom).orEmpty(), o.num("confidence") ?: 0.0, FactFidelity.valueOf(o.str("fidelity")!!),
        o.arr("notes")?.items?.map { (it as JsonValue.Str).value }.orEmpty(),
        o.obj("structuralMask")?.let { m -> SourceMask(m.int("size")!!,m.arr("runs")!!.items.map { (it as JsonValue.Num).value.toInt() },m.arr("excludedRuns")!!.items.map { (it as JsonValue.Num).value.toInt() }) },
    )

    fun conflictJson(c: VisualConflict) = json {
        "id" to c.id; "kind" to c.kind; "severity" to c.severity; "subjectIds" toStrings c.subjectIds; "facade" to c.facade; "assetUrl" to c.assetUrl
        "observationIndex" to c.observationIndex; "candidateReading" to c.candidateReading; "sourceReading" to c.sourceReading; "impact" to c.impact
        "recommendedAction" to c.recommendedAction; "confidence" to c.confidence
    }

    fun conflictFrom(o: JsonValue.Obj) = VisualConflict(
        o.str("id")!!, VisualConflictKind.valueOf(o.str("kind")!!), VisualConflictSeverity.valueOf(o.str("severity")!!),
        o.arr("subjectIds")?.items?.map { (it as JsonValue.Str).value }.orEmpty(), o.str("facade")?.let(FacadeSide::valueOf), o.str("assetUrl")!!,
        o.int("observationIndex") ?: -1, o.str("candidateReading") ?: "", o.str("sourceReading") ?: "", o.str("impact") ?: "", o.str("recommendedAction") ?: "", o.num("confidence") ?: 0.0,
    )

    fun appearanceJson(a: AppearanceCandidate) = json {
        "featureId" to a.featureId; "kind" to a.kind
        "sourceEvidence" toArray a.sourceEvidence.map { r -> json { "assetUrl" to r.assetUrl; "observationIndex" to r.observationIndex; "viewpoint" to r.viewpoint } }
        "affectedRegion" to json { "facade" to a.affectedRegion.facade; "floorId" to a.affectedRegion.floorId; "facadeFraction" to a.affectedRegion.facadeFraction?.let(::boxJson) }
        "confidence" to a.confidence; "fidelity" to a.fidelity
        "presentationParameters" to json { a.presentationParameters.forEach { (k, v) -> k to v } }
        "note" to a.note
    }

    fun appearanceFrom(o: JsonValue.Obj): AppearanceCandidate {
        val region = o.obj("affectedRegion")
        return AppearanceCandidate(
            o.str("featureId")!!, AppearanceKind.valueOf(o.str("kind")!!),
            o.arr("sourceEvidence")?.objects?.map { r -> EvidenceRef(r.str("assetUrl")!!, r.int("observationIndex") ?: -1, VisualViewpoint.valueOf(r.str("viewpoint")!!)) }.orEmpty(),
            AffectedRegion(region?.str("facade")?.let(FacadeSide::valueOf), region?.str("floorId"), region?.obj("facadeFraction")?.let(::boxFrom)),
            o.num("confidence") ?: 0.0, FactFidelity.valueOf(o.str("fidelity")!!),
            o.obj("presentationParameters")?.fields?.mapValues { (it.value as JsonValue.Num).value }.orEmpty(),
            o.str("note") ?: "",
        )
    }

    fun evidenceJson(v: VisualEvidence) = json {
        "assets" toArray v.assets.map(::assetJson)
        "facades" toArray v.facades.map { f -> json { "role" to f.role; "assetUrl" to f.assetUrl; "sides" toStrings f.sides.map { it.name }; "confidence" to f.confidence; "reason" to f.reason } }
        "conflicts" toArray v.conflicts.map(::conflictJson)
        "appearance" toArray v.appearance.map(::appearanceJson)
        "notes" toStrings v.notes
    }

    fun evidenceFrom(o: JsonValue.Obj) = VisualEvidence(
        o.arr("assets")?.objects?.map(::assetFrom).orEmpty(),
        o.arr("facades")?.objects?.map { f -> FacadeAssignment(AssetRole.valueOf(f.str("role")!!), f.str("assetUrl")!!, f.arr("sides")?.items?.map { FacadeSide.valueOf((it as JsonValue.Str).value) }.orEmpty(), f.num("confidence") ?: 0.0, f.str("reason") ?: "") }.orEmpty(),
        o.arr("conflicts")?.objects?.map(::conflictFrom).orEmpty(),
        o.arr("appearance")?.objects?.map(::appearanceFrom).orEmpty(),
        o.arr("notes")?.items?.map { (it as JsonValue.Str).value }.orEmpty(),
    )
}

private fun visualJson(v: VisualEvidence): JsonValue.Obj = VisualCodec.evidenceJson(v)
private fun visualFrom(o: JsonValue.Obj): VisualEvidence = VisualCodec.evidenceFrom(o)
