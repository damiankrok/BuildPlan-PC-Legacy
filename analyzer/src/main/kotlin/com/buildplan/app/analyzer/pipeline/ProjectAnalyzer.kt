package com.buildplan.app.analyzer.pipeline

import com.buildplan.app.analyzer.asset.AnalysisStorage
import com.buildplan.app.analyzer.asset.AssetFetcher
import com.buildplan.app.analyzer.asset.AssetPolicy
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.asset.FetchedAssets
import com.buildplan.app.analyzer.candidate.AnalysisIssue
import com.buildplan.app.analyzer.candidate.FloorCandidate
import com.buildplan.app.analyzer.candidate.IssueSeverity
import com.buildplan.app.analyzer.candidate.NamedDimension
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RoofCandidate
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.plan.FloorCandidateBuilder
import com.buildplan.app.analyzer.plan.FloorPlanAnalysis
import com.buildplan.app.analyzer.plan.PlanAnalyzer
import com.buildplan.app.analyzer.plan.PlanDebugSink
import com.buildplan.app.analyzer.plan.Regions
import com.buildplan.app.analyzer.quantity.ProjectQuantities
import com.buildplan.app.analyzer.quantity.QuantityTakeoffEngine
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.RasterCodec
import com.buildplan.app.analyzer.roof.RoofHeightField
import com.buildplan.app.analyzer.roof.RoofSolver
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.SiteAdapter
import com.buildplan.app.analyzer.site.SourcePackage
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.text.DrawingTextReading
import com.buildplan.app.analyzer.text.GlyphRunLocator
import com.buildplan.app.analyzer.text.OpeningLabelMatcher
import com.buildplan.app.analyzer.text.TextLegibility
import com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.ResourceFetcher
import com.buildplan.app.analyzer.source.SourceResolution
import com.buildplan.app.analyzer.source.SourceResolver
import com.buildplan.app.analyzer.validate.ClarificationQuestion
import com.buildplan.app.analyzer.validate.CrossSourceValidator
import com.buildplan.app.analyzer.validate.GapAnalysis
import com.buildplan.app.analyzer.validate.GapAnalyzer
import com.buildplan.app.analyzer.validate.ValidationFinding
import com.buildplan.app.analyzer.vertical.VerticalAnalyzer
import kotlin.math.max
import kotlin.math.roundToInt

/** The stages the pipeline reports, in order. */
enum class AnalysisStage { RESOLVE, READ_PAGE, FETCH_ASSETS, PLANS, ROOF, VERTICAL, CANDIDATE, QUANTITIES, VALIDATE, GAPS, SNAPSHOT }

/** Progress callback for a debug harness; called on the analysing thread. */
fun interface AnalysisListener {
    fun onStage(stage: AnalysisStage, message: String)

    companion object {
        val NONE = AnalysisListener { _, _ -> }
    }
}

/** What a run produced, in memory, with the plan analyses kept for evidence. */
class AnalysisRun(
    val input: ProjectInput,
    val resolution: SourceResolution,
    val source: SourcePackage?,
    val assets: FetchedAssets?,
    val plans: List<FloorPlanAnalysis>,
    val candidate: ProjectAnalysisCandidate?,
    val quantities: ProjectQuantities?,
    val validations: List<ValidationFinding>,
    val gaps: GapAnalysis?,
    val questions: List<ClarificationQuestion>,
    val log: List<String>,
    val timingsMillis: Map<String, Long>,
    val roomMasks: Map<String, BinaryMask>,
) {
    fun snapshot(now: Long = System.currentTimeMillis()): ProjectAnalysisSnapshot = ProjectAnalysisSnapshot(
        schemaVersion = ProjectAnalysisSnapshot.SCHEMA_VERSION,
        analyzerVersion = ProjectAnalysisSnapshot.ANALYZER_VERSION,
        createdAtEpochMillis = now,
        inputUrl = input.rawUrl,
        resolutionSteps = resolution.steps,
        source = source,
        candidate = candidate,
        quantities = quantities,
        validations = validations,
        gaps = gaps,
        questions = questions,
        log = log,
        timingsMillis = timingsMillis,
    )
}

/**
 * The whole pipeline, URL to snapshot, with no project-specific branch in
 * it: resolve, read, fetch assets, analyse each plan, solve the roof over
 * the top storey's outline, close the vertical chain, build candidates,
 * take off quantities, validate against the page, score the gaps.
 *
 * Every dependency that touches the world is injected: the fetcher (network
 * or cache), the raster codec (Bitmap or ImageIO), the storage (app-private
 * or a temp dir). The core stays a pure function of bytes and facts.
 */
class ProjectAnalyzer(
    private val fetcher: ResourceFetcher,
    private val codec: RasterCodec,
    private val storage: AnalysisStorage,
    private val adapter: SiteAdapter = ArchonSiteAdapter(),
    private val assetPolicy: AssetPolicy = AssetPolicy(),
    private val planDebug: PlanDebugSink = PlanDebugSink.NONE,
    private val listener: AnalysisListener = AnalysisListener.NONE,
) {

    fun analyze(input: ProjectInput): AnalysisRun {
        val log = mutableListOf<String>()
        val timings = LinkedHashMap<String, Long>()
        fun <T> timed(stage: AnalysisStage, message: String, block: () -> T): T {
            listener.onStage(stage, message)
            val start = System.nanoTime()
            val result = block()
            timings[stage.name] = (System.nanoTime() - start) / 1_000_000
            return result
        }

        val resolution = timed(AnalysisStage.RESOLVE, "Rozpoznawanie adresu") { SourceResolver(fetcher).resolve(input) }
        resolution.steps.forEach { log += "${it.kind}: ${it.detail}" }
        if (!resolution.isResolved) {
            val gaps = GapAnalyzer.analyse(resolution, null, null)
            return AnalysisRun(input, resolution, null, null, emptyList(), null, null, emptyList(), gaps.gaps, gaps.questions, log, timings, emptyMap())
        }
        val identity = resolution.identity!!
        val page = resolution.page!!

        val source = timed(AnalysisStage.READ_PAGE, "Odczyt strony projektu") { adapter.read(identity, page, fetcher) }
        log += "page: ${source.title}, ${source.scalars.count { it.measured.value != null }} numeric facts, ${source.floors.size} storeys, ${source.assets.assets.size} assets"

        val assets = timed(AnalysisStage.FETCH_ASSETS, "Pobieranie rysunków") {
            AssetFetcher(fetcher, codec, storage, assetPolicy).fetchAll(source.assets, "${identity.projectKey}/assets")
        }
        log += "assets: ${assets.manifest.assets.count { it.retrieval == com.buildplan.app.analyzer.asset.RetrievalState.DECODED }} decoded of ${source.assets.assets.size}"

        // ---- plans, lowest storey first as the page prints them
        val planRecords = listOfNotNull(assets.manifest.firstWithRole(AssetRole.PLAN_GROUND), assets.manifest.firstWithRole(AssetRole.PLAN_UPPER)) +
            assets.manifest.withRole(AssetRole.PLAN_FLOOR_N)
        val footprintArea = source.scalar(ScalarKey.FOOTPRINT_AREA)?.measured
        var shared: Measured? = null
        val analyses = mutableListOf<FloorPlanAnalysis>()
        val analyzer = PlanAnalyzer(planDebug)
        planRecords.forEachIndexed { index, record ->
            val image = assets.image(record) ?: return@forEachIndexed
            val analysis = timed(AnalysisStage.PLANS, "Analiza rzutu ${index + 1}") {
                analyzer.analyse(record.url, image, source.floors.getOrNull(index), if (index == 0) footprintArea else null, shared, attic = index > 0, debugPrefix = "floor$index")
            }
            if (index == 0) shared = analysis.calibration?.pixelsPerMeter
            analyses += analysis
            log += "plan ${index + 1}: ${analysis.pieces.size} wall pieces, ${analysis.gaps.size} gaps, ${analysis.regions.size} regions, calibration ${analysis.calibration?.pixelsPerMeter?.value?.let { "%.2f px/m".format(java.util.Locale.ROOT, it) } ?: "none"} (${analysis.calibration?.confidence})"
            analysis.issues.forEach { log += "plan ${index + 1} ${it.severity}: ${it.message}" }
        }

        // ---- roof over the top storey's outline
        val topIndex = analyses.lastIndex
        val topAnalysis = analyses.getOrNull(topIndex)
        val groundAnalysis = analyses.firstOrNull()
        val groundCal = groundAnalysis?.calibration
        val roofOutline: Polygon? = topAnalysis?.let { a ->
            val cal = a.calibration ?: return@let null
            (a.roofBandOutlinePx ?: a.footprintOutlinePx).let { Regions.toPolygon(it.map { p -> cal.toMeters(p) }) }
        }
        val groundFootprint: Polygon? = groundAnalysis?.calibration?.let { cal -> Regions.toPolygon(groundAnalysis.footprintOutlinePx.map { cal.toMeters(it) }) }
        val exteriorThickness = analyses.flatMap { a -> a.pieces.filterIndexed { i, _ -> i in a.exteriorPieceIndices }.map { it.thickness / (a.calibration?.pixelsPerMeter?.value ?: 1.0) } }
            .sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            ?.let { Measured.traced(it, MeasureUnit.METER, Provenance(groundAnalysis?.assetUrl, "median exterior piece thickness", "traced"), 0.03) }

        val pitch = source.scalar(ScalarKey.ROOF_PITCH)?.measured
        val family = ArchonSiteAdapter.roofFamily(source.siteTags["projectRoof"] ?: source.scalar(ScalarKey.ROOF_PITCH)?.rawValue)
        // The roof needs an eave level, which the vertical chain needs the roof's rise for: solve
        // the roof at eave 0 for its rise, close the chain, then place the roof at the real eave.
        var roof: RoofCandidate? = null
        var rise: Double? = null
        if (roofOutline != null && pitch?.value != null) {
            timed(AnalysisStage.ROOF, "Dach: szkielet połaci") {
                val trial = RoofSolver.solve(RoofSolver.Input(roofOutline, family, pitch, Measured.assumed(0.0, MeasureUnit.METER, "trial"), source.scalars.firstOrNull { it.key == ScalarKey.ROOF_AREA }?.measured, emptyList(), Measured.assumed(0.0, MeasureUnit.METER, "trial")))
                // The published height is measured to the highest ridge. Over a compound footprint
                // a uniform-slope skeleton may peak where wings meet rather than over the main body;
                // that is a known limit of the roof model, recorded in the log, not corrected by hand.
                val tanP = kotlin.math.tan(Math.toRadians(pitch.value))
                rise = trial?.let { r ->
                    val highestRidge = r.ridgeLines.maxByOrNull { it.a.y }
                    val ridgeY = highestRidge?.a?.y ?: r.ridgeElevation.value ?: 0.0
                    ridgeY / tanP
                }
                trial?.let { log += "roof trial: ${it.facets.size} facets, ${it.ridgeLines.size} ridges, highest ridge rise ${"%.2f".format(java.util.Locale.ROOT, rise ?: 0.0)} m (skeleton max ${"%.2f".format(java.util.Locale.ROOT, (it.ridgeElevation.value ?: 0.0) / tanP)} m)" }
            }
        }
        val overhang = topAnalysis?.let { a ->
            val cal = a.calibration ?: return@let null
            val band = a.roofBandOutlinePx ?: return@let null
            val walls = Regions.toPolygon(a.footprintOutlinePx.map { cal.toMeters(it) }) ?: return@let null
            val outline = Regions.toPolygon(band.map { cal.toMeters(it) }) ?: return@let null
            eavesOverhang(outline, walls)?.let { Measured.traced(it, MeasureUnit.METER, Provenance(a.assetUrl, "roof band outline vs wall outline", "median distance between parallel edges within 1.5 m"), 1.0 / cal.pixelsPerMeter.requireValue()) }
        }
        overhang?.let { log += "roof overhang traced at ${"%.2f".format(java.util.Locale.ROOT, it.value)} m beyond the top storey's walls" }
        val levels = timed(AnalysisStage.VERTICAL, "Rzędne") {
            VerticalAnalyzer.analyse(
                VerticalAnalyzer.Input(
                    buildingHeight = source.scalar(ScalarKey.BUILDING_HEIGHT)?.measured,
                    kneeWall = source.scalar(ScalarKey.KNEE_WALL_HEIGHT)?.measured,
                    pitchDegrees = pitch,
                    roofRiseM = rise,
                    roofOverhang = overhang,
                    exteriorWallThickness = exteriorThickness,
                    storeyCount = max(1, analyses.size),
                ),
            )
        }
        if (roofOutline != null && pitch?.value != null && levels.eave.value != null) {
            val uncovered = groundFootprint?.let { uncoveredMasses(it, roofOutline) }.orEmpty()
            val topLevel = levels.upperFloor.value?.let { Measured.derived(it, MeasureUnit.METER, "top of the single storey below the attic floor", listOf(levels.upperFloor)) } ?: levels.groundClearHeight
            roof = RoofSolver.solve(RoofSolver.Input(roofOutline, family, pitch, levels.eave, source.scalars.firstOrNull { it.key == ScalarKey.ROOF_AREA }?.measured, uncovered, topLevel))
            roof?.let { log += "roof: ${it.family}, ${it.facets.size} facets, ${"%.1f".format(java.util.Locale.ROOT, it.totalArea.value)} m2, ridge ${"%.2f".format(java.util.Locale.ROOT, it.ridgeElevation.value)} m; ${it.note}" }
        } else {
            log += "roof: not solved (outline ${roofOutline != null}, pitch ${pitch?.value}, eave ${levels.eave.value})"
        }

        // ---- candidates
        val built = timed(AnalysisStage.CANDIDATE, "Kandydat budynku") {
            analyses.mapIndexed { index, a ->
                val elevation = if (index == 0) levels.groundFloor else levels.upperFloor.takeIf { it.value != null } ?: Measured.assumed(3.0 * index, MeasureUnit.METER, "storey level assumed at 3.0 m per storey; chain not closed")
                val clear = if (index == 0) levels.groundClearHeight else (levels.atticFlatCeilingHeight.takeIf { it.value != null } ?: levels.upperClearHeight)
                FloorCandidateBuilder.build(index, source.floors.getOrNull(index)?.name ?: "Kondygnacja ${index + 1}", index, source.floors.getOrNull(index), a, elevation, clear)
            }
        }
        // ---- what the drawings print as text
        //
        // Located, then read only if the raster can carry it. Both benchmark sources dimension
        // every opening and level every storey, and publish those drawings at four to six pixels
        // a glyph — so the runs are found, none is read, and every height stays MISSING with the
        // question attached. Locating them is still worth the pass: "the source states this and
        // the published image is too small to carry it" is a different answer for the reader than
        // "the source does not state it", and only one of them is fixed by a bigger drawing.
        val textExtractor = GlyphRunLocator()
        val readings = mutableListOf<DrawingTextReading>()
        analyses.forEachIndexed { index, a ->
            val reading = DrawingTextReading(a.assetUrl, textExtractor.extract(a.assetUrl, a.image))
            readings += reading
            log += "text plan ${index + 1}: ${reading.summary()}"
        }
        assets.manifest.firstWithRole(AssetRole.SECTION)?.let { record ->
            assets.image(record)?.let { image ->
                val reading = DrawingTextReading(record.url, textExtractor.extract(record.url, image))
                readings += reading
                log += "text section: ${reading.summary()}"
            }
        }

        val openingsWithLabels = built.mapIndexed { index, b ->
            val analysis = analyses.getOrNull(index)
            val calibration = analysis?.calibration
            val reading = readings.getOrNull(index)
            if (calibration == null || reading == null) return@mapIndexed b.openings to emptyList<String>()
            val associations = OpeningLabelMatcher.associate(
                b.openings,
                { o -> openingCentre(o, b.walls) },
                reading,
                calibration,
            )
            val (updated, notes) = OpeningLabelMatcher.applyHeights(b.openings, associations)
            val labelled = associations.size
            val readable = associations.count { it.observation.legibility == TextLegibility.READ }
            log += "text plan ${index + 1}: $labelled of ${b.openings.size} openings have a label within ${OpeningLabelMatcher.SEARCH_RADIUS_M} m, $readable readable"
            notes.forEach { log += "  $it" }
            updated to notes
        }

        val issues = built.flatMap { it.issues }.toMutableList()
        val stairs = linkStairsAcrossFloors(built.map { it.floor }, built.flatMap { it.stairs })
        if (stairs.isNotEmpty()) {
            log += "stairs: " + stairs.joinToString { "${it.id} on ${it.floorId} (${it.evidence.joinToString("+") { e -> e.name }})" }
        }
        val dimensions = dimensions(built.map { it.floor }, roof, levels, groundFootprint)
        val candidate = ProjectAnalysisCandidate(
            floors = built.map { it.floor },
            walls = built.flatMap { it.walls },
            openings = openingsWithLabels.flatMap { it.first },
            stairs = stairs,
            roof = roof,
            levels = levels,
            dimensions = dimensions,
            issues = issues,
        )
        log += "candidate: ${candidate.rooms.size} rooms, ${candidate.walls.size} walls, ${candidate.openings.size} openings, ${candidate.stairs.size} stair zones"

        val quantities = timed(AnalysisStage.QUANTITIES, "Przedmiar") { QuantityTakeoffEngine(candidate, roof?.let { RoofHeightField(it) }).compute() }
        log += "quantities: ${quantities.surfaces.size} surfaces, roof ${"%.1f".format(java.util.Locale.ROOT, quantities.roofTotal.value ?: 0.0)} m2"
        val validations = timed(AnalysisStage.VALIDATE, "Porównanie ze źródłem") { CrossSourceValidator.validate(source, candidate, quantities) }
        val gapOutput = timed(AnalysisStage.GAPS, "Braki i pytania") { GapAnalyzer.analyse(resolution, source, candidate) }
        log += "completeness ${"%.0f".format(java.util.Locale.ROOT, gapOutput.gaps.completenessScore * 100)} %, ${gapOutput.questions.size} questions"
        listener.onStage(AnalysisStage.SNAPSHOT, "Gotowe")

        return AnalysisRun(input, resolution, source, assets, analyses, candidate, quantities, validations, gapOutput.gaps, gapOutput.questions, log, timings, built.flatMap { it.roomMasks.entries }.associate { it.key to it.value })
    }

    /**
     * Joins each flight to the storey it reaches.
     *
     * A stair on one storey and a stair on the next that stand over each other
     * are the two ends of one flight — the plan draws the same shaft twice,
     * once climbing and once arriving. Overlap in plan is the whole test; no
     * arrow is read and no direction of travel is inferred from it, so a
     * linked pair says *which storeys are connected* and still leaves "up or
     * down" to the question list.
     */
    private fun linkStairsAcrossFloors(floors: List<FloorCandidate>, stairs: List<com.buildplan.app.analyzer.candidate.StairCandidate>): List<com.buildplan.app.analyzer.candidate.StairCandidate> {
        val order = floors.associate { it.id to it.order }
        return stairs.map { s ->
            val myOrder = order[s.floorId] ?: return@map s
            val above = stairs.firstOrNull { other ->
                order[other.floorId] == myOrder + 1 && overlapFraction(s.zone, other.zone) > 0.25
            }
            if (above == null) s else s.copy(
                toFloorId = above.floorId,
                evidence = s.evidence + com.buildplan.app.analyzer.candidate.StairEvidence.CROSS_FLOOR_ALIGNMENT,
                note = s.note + "; aligned with ${above.id} on the storey above",
            )
        }
    }

    private fun overlapFraction(a: com.buildplan.app.analyzer.candidate.Box, b: com.buildplan.app.analyzer.candidate.Box): Double {
        val w = kotlin.math.min(a.maxX, b.maxX) - max(a.minX, b.minX)
        val h = kotlin.math.min(a.maxZ, b.maxZ) - max(a.minZ, b.minZ)
        if (w <= 0 || h <= 0) return 0.0
        val smaller = kotlin.math.min(a.area, b.area)
        return if (smaller <= 0) 0.0 else w * h / smaller
    }

    /**
     * The eaves overhang: for each edge of the roof outline, the distance to the
     * nearest parallel wall-outline edge that overlaps it along its length,
     * when that distance is under 1.5 m; the median of those. Null when fewer
     * than two edges have a wall beside them (the outline is not a roof band).
     */
    private fun eavesOverhang(roofOutline: Polygon, walls: Polygon): Double? {
        val distances = roofOutline.edges.mapNotNull { re ->
            val horizontal = kotlin.math.abs(re.a.z - re.b.z) < 1e-9
            walls.edges.filter { we -> (kotlin.math.abs(we.a.z - we.b.z) < 1e-9) == horizontal }.mapNotNull { we ->
                val (r0, r1) = if (horizontal) minOf(re.a.x, re.b.x) to maxOf(re.a.x, re.b.x) else minOf(re.a.z, re.b.z) to maxOf(re.a.z, re.b.z)
                val (w0, w1) = if (horizontal) minOf(we.a.x, we.b.x) to maxOf(we.a.x, we.b.x) else minOf(we.a.z, we.b.z) to maxOf(we.a.z, we.b.z)
                if (minOf(r1, w1) - maxOf(r0, w0) < 0.5) return@mapNotNull null
                val d = if (horizontal) kotlin.math.abs(re.a.z - we.a.z) else kotlin.math.abs(re.a.x - we.a.x)
                if (d > 1.5) null else d
            }.minOrNull()
        }.sorted()
        return if (distances.size < 2) null else distances[distances.size / 2]
    }

    /** Parts of the ground footprint the roof outline leaves uncovered, as rectilinear polygons (raster difference at 2 cm). */
    private fun uncoveredMasses(footprint: Polygon, roofOutline: Polygon): List<Polygon> {
        val res = 0.02
        val b = footprint.bounds.union(roofOutline.bounds).inflate(0.1)
        val w = ((b.width) / res).roundToInt().coerceIn(1, 4000)
        val h = ((b.depth) / res).roundToInt().coerceIn(1, 4000)
        val mask = BinaryMask(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = Pt(b.minX + (x + 0.5) * res, b.minZ + (y + 0.5) * res)
            if (footprint.contains(p) && !roofOutline.contains(p)) mask[x, y] = true
        }
        val opened = mask.openWindow(10, 10) // ignore slivers under 0.2 m
        val comps = opened.components()
        return (1..comps.count).mapNotNull { label ->
            if (comps.sizes[label] * res * res < 2.0) return@mapNotNull null
            val box = comps.boundingBox(label) ?: return@mapNotNull null
            val outline = Regions.smoothJogs(Regions.traceOutline(comps.maskOf(label), box), 0.3 / res)
            Regions.toPolygon(outline.map { Pt(b.minX + it.x * res, b.minZ + it.z * res) })
        }
    }

    private fun dimensions(floors: List<FloorCandidate>, roof: RoofCandidate?, levels: com.buildplan.app.analyzer.candidate.LevelsCandidate, footprint: Polygon?): List<NamedDimension> {
        val out = mutableListOf<NamedDimension>()
        footprint?.let { f ->
            val b = f.bounds
            out += NamedDimension("project", "Szerokość budynku (obrys)", Measured.derived(b.width, MeasureUnit.METER, "footprint bounding box", emptyList()))
            out += NamedDimension("project", "Głębokość budynku (obrys)", Measured.derived(b.depth, MeasureUnit.METER, "footprint bounding box", emptyList()))
            out += NamedDimension("project", "Powierzchnia obrysu", Measured.derived(f.area, MeasureUnit.SQUARE_METER, "footprint polygon", emptyList()))
            out += NamedDimension("project", "Obwód obrysu", Measured.derived(f.perimeter, MeasureUnit.METER, "footprint polygon", emptyList()))
        }
        out += NamedDimension("project", "Rzędna terenu", levels.terrain)
        out += NamedDimension("project", "Rzędna kalenicy", levels.ridge)
        out += NamedDimension("project", "Rzędna okapu", levels.eave)
        out += NamedDimension("project", "Rzędna poddasza", levels.upperFloor)
        out += NamedDimension("project", "Wysokość parteru w świetle", levels.groundClearHeight)
        out += NamedDimension("project", "Ścianka kolankowa", levels.kneeWall)
        roof?.let {
            out += NamedDimension("roof", "Kąt dachu", it.pitchDegrees)
            out += NamedDimension("roof", "Długość kalenic", Measured.derived(it.ridgeLines.sumOf { r -> r.length }, MeasureUnit.METER, "skeleton", emptyList()))
            out += NamedDimension("roof", "Długość okapów", it.eaveLength)
        }
        floors.forEach { f ->
            f.footprint?.let { fp ->
                out += NamedDimension(f.id, "Szerokość kondygnacji", Measured.derived(fp.bounds.width, MeasureUnit.METER, "storey footprint bounding box", emptyList()))
                out += NamedDimension(f.id, "Głębokość kondygnacji", Measured.derived(fp.bounds.depth, MeasureUnit.METER, "storey footprint bounding box", emptyList()))
                fp.edges.forEachIndexed { i, e -> out += NamedDimension(f.id, "Odcinek obrysu ${i + 1}", Measured.derived(e.length, MeasureUnit.METER, "storey footprint edge", emptyList())) }
            }
            out += NamedDimension(f.id, "Rzędna podłogi", f.floorElevation)
            out += NamedDimension(f.id, "Wysokość w świetle", f.clearHeight)
            f.rooms.forEach { r ->
                val b = r.polygon?.bounds ?: return@forEach
                out += NamedDimension(r.id, "Wymiary obrysu pomieszczenia", Measured.derived(b.width, MeasureUnit.METER, "room bounding box width", emptyList(), note = "depth ${"%.2f".format(java.util.Locale.ROOT, b.depth)} m"))
            }
        }
        return out
    }
}

/** Convenience for issues raised outside a stage. */
internal fun issue(stage: String, message: String, severity: IssueSeverity = IssueSeverity.WARNING) = AnalysisIssue(severity, stage, null, message)

/**
 * The mid-point of an opening on its wall, in model metres.
 *
 * The opening records how far along the wall it starts and how wide it is;
 * walking that distance from the wall's own start puts the label search where
 * the drawing actually prints the leader.
 */
private fun openingCentre(opening: com.buildplan.app.analyzer.candidate.OpeningCandidate, walls: List<com.buildplan.app.analyzer.candidate.WallCandidate>): Pt? {
    val wall = walls.firstOrNull { it.id == opening.wallId } ?: return null
    val d = opening.distanceAlongWall.value ?: return null
    val w = opening.width.value ?: 0.0
    val a = wall.centreline.a
    val b = wall.centreline.b
    val length = a.distanceTo(b)
    if (length < 1e-9) return null
    val t = ((d + w / 2) / length).coerceIn(0.0, 1.0)
    return Pt(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)
}
