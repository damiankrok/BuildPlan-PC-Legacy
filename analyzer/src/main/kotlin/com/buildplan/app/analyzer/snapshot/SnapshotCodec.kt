package com.buildplan.app.analyzer.snapshot

import com.buildplan.app.analyzer.asset.AssetManifest
import com.buildplan.app.analyzer.asset.AssetRecord
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.candidate.AnalysisIssue
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
        const val SCHEMA_VERSION = 1
        const val ANALYZER_VERSION = "0.1.0-stage023a"
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
    )

    private fun strings(o: JsonValue.Obj, key: String): List<String> = o.arr(key)?.items?.map { (it as JsonValue.Str).value }.orEmpty()

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
                "id" to r.id; "floorId" to r.floorId; "name" to r.name; "sourceOrdinal" to r.sourceOrdinal; "polygon" to r.polygon?.let(::polygonJson)
                "geometryState" to r.geometryState; "geometryNote" to r.geometryNote
                "perimeter" to measuredJson(r.perimeter); "plannedArea" to measuredJson(r.plannedArea); "sourceUsableArea" to measuredJson(r.sourceUsableArea); "sourceFloorArea" to measuredJson(r.sourceFloorArea)
                "boundary" toArray r.boundary.map { b -> json { "segment" to segJson(b.segment); "wallId" to b.wallId; "neighbourRoomId" to b.neighbourRoomId; "faceOutside" to b.faceOutside } }
                "matchConfidence" to r.matchConfidence; "matchNote" to r.matchNote; "matchAlternatives" toStrings r.matchAlternatives
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
            RoomCandidate(r.str("id")!!, r.str("floorId")!!, r.str("name")!!, r.int("sourceOrdinal"), r.arr("polygon")?.let(::polygonFrom),
                RoomGeometryState.valueOf(r.str("geometryState") ?: RoomGeometryState.VALID_SIMPLE_RING.name), r.str("geometryNote") ?: "",
                m(r, "perimeter"), m(r, "plannedArea"), m(r, "sourceUsableArea"), m(r, "sourceFloorArea"),
                r.arr("boundary")?.objects?.map { b -> RoomBoundarySegment(segFrom(b.obj("segment")!!), b.str("wallId"), b.str("neighbourRoomId"), b.bool("faceOutside") ?: false) }.orEmpty(),
                FactFidelity.valueOf(r.str("matchConfidence")!!), r.str("matchNote") ?: "", strings(r, "matchAlternatives"))
        }.orEmpty(),
        unmatchedRegions = o.arr("unmatchedRegions")?.objects?.map { u -> RegionCandidate(u.str("id")!!, polygonFrom(u.arr("polygon")!!), u.num("areaM2")!!, strings(u, "boundaryWallIds")) }.orEmpty(),
        planAssetUrl = o.str("planAssetUrl"),
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
        "exteriorJoinery" to measuredJson(q.exteriorJoinery); "facadeGross" to measuredJson(q.facadeGross); "facadeNet" to measuredJson(q.facadeNet); "facadeWallMaterial" to measuredJson(q.facadeWallMaterial); "floorsAndStairsArea" to measuredJson(q.floorsAndStairsArea)
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
        exteriorJoinery = m(o, "exteriorJoinery"), facadeGross = m(o, "facadeGross"), facadeNet = m(o, "facadeNet"), facadeWallMaterial = m(o, "facadeWallMaterial"), floorsAndStairsArea = m(o, "floorsAndStairsArea"),
        notes = strings(o, "notes"),
    )

    private fun validationJson(v: ValidationFinding) = json {
        "subject" to v.subject; "candidateValue" to v.candidateValue; "sourceValue" to v.sourceValue; "unit" to v.unit
        "absoluteDifference" to v.absoluteDifference; "relativeDifference" to v.relativeDifference; "status" to v.status; "semantics" to v.semantics; "candidateFidelity" to v.candidateFidelity
    }

    private fun validationFrom(o: JsonValue.Obj) = ValidationFinding(
        o.str("subject")!!, o.num("candidateValue"), o.num("sourceValue"), o.str("unit") ?: "", o.num("absoluteDifference"), o.num("relativeDifference"),
        ValidationStatus.valueOf(o.str("status")!!), o.str("semantics") ?: "", o.str("candidateFidelity")?.let(FactFidelity::valueOf),
    )
}
