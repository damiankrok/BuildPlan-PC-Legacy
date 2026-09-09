package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.candidate.AnalysisIssue
import com.buildplan.app.analyzer.candidate.Box
import com.buildplan.app.analyzer.candidate.IssueSeverity
import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.Segment
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.site.PublishedFloor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything one plan raster yields, in pixels and — once calibrated — in
 * metres. Kept as one result so the vertical, roof and quantity stages read
 * the same trace and so the evaluation can print all of it.
 */
class FloorPlanAnalysis(
    val assetUrl: String,
    val image: RasterImage,
    val pieces: List<WallPiece>,
    val gaps: List<PieceGap>,
    /** Gaps that were sealed for the room segmentation (exterior openings, door-like interior gaps). */
    val sealedGaps: List<PieceGap>,
    val exteriorPieceIndices: Set<Int>,
    val footprintPixelArea: Int,
    val footprintOutlinePx: List<Pt>,
    val roofBandOutlinePx: List<Pt>?,
    val regions: List<PixelRegion>,
    val regionLabelAt: (Int, Int) -> Int,
    val regionIndexByLabel: Map<Int, Int>,
    val stairs: List<StairDetector.StairZone>,
    /** Ink that is not structure, for opening classification (glazing lines cross a window gap). */
    val thinInk: BinaryMask,
    val calibration: PlanCalibration?,
    val matching: RoomMatcher.Result?,
    /** Structure pixels no axis-aligned piece explains (oblique walls, thick symbols). */
    val unexplainedStructurePixels: Int,
    val issues: List<AnalysisIssue>,
) {
    /** Region pixel area in m2 under the calibration, or null. */
    fun regionAreaM2(region: PixelRegion): Double? = calibration?.let { region.pixelArea / (it.pixelsPerMeter.requireValue() * it.pixelsPerMeter.requireValue()) }

    fun toMeters(p: Pt): Pt = calibration?.toMeters(p) ?: p
    fun toMeters(points: List<Pt>): List<Pt> = points.map { toMeters(it) }
    fun lengthM(px: Double): Double = calibration?.let { px / it.pixelsPerMeter.requireValue() } ?: px
}

/**
 * Deterministic plan analysis for one storey.
 *
 * The pipeline, every step of which is a pure function of the raster and
 * the published facts:
 *
 * 1. classify pixels ([PlanRaster]);
 * 2. extract axis-aligned wall pieces and the gaps at their ends
 *    ([WallPieces]);
 * 3. seal every gap, flood the outside from the image border (and from a
 *    roof band, on an attic plan), and take the footprint as what is not
 *    outside — which also tells which pieces are exterior;
 * 4. re-seal only exterior gaps and short interior gaps (doors), leaving
 *    wide interior gaps open, and extract the enclosed regions ([Regions]);
 * 5. calibrate on the published footprint area with the published room
 *    total as an independent check ([PlanCalibrator]);
 * 6. match regions to the room table by area ([RoomMatcher]);
 * 7. find stair flights as runs of thin parallel lines ([StairDetector]).
 *
 * Thresholds are physical, in metres. The whole pass runs twice: first with
 * a provisional scale taken from the extent of the structure itself, which
 * only sizes kernels, and then again with the calibrated scale — so no
 * threshold is ever a pixel count chosen for one drawing.
 */
class PlanAnalyzer(private val debug: PlanDebugSink = PlanDebugSink.NONE) {

    data class Thresholds(
        val minWallLengthM: Double = 0.40,
        /** A piece of thick (≥ [thickWallM]) structure counts from this length: the stretch of masonry between a window and a corner is short but is wall. */
        val minThickWallLengthM: Double = 0.15,
        /** Anything thinner than this is a line, not a wall. Partitions in a house start at ~0.10 m. */
        val minWallThicknessM: Double = 0.10,
        /** Walls at least this thick bound the footprint on their own; partitions never do. */
        val thickWallM: Double = 0.20,
        /** No wall in a house is thicker than this; a wider run is a perpendicular wall seen sideways. */
        val maxWallThicknessM: Double = 0.70,
        val minOpeningM: Double = 0.55,
        val maxOpeningM: Double = 5.5,
        val doorLikeMaxM: Double = 1.30,
        /** Interior wall-line gaps up to this width are sealed for segmentation (a doorway, a double door); wider ones are rooms. */
        val interiorSealMaxM: Double = 2.50,
        /** An exterior opening at least this wide is a vehicle gate, not a door or a window. */
        val gateMinM: Double = 2.20,
        val minRegionM2: Double = 0.7,
        val minTreadM: Double = 0.7,
        val treadSpacingM: ClosedFloatingPointRange<Double> = 0.20..0.36,
        /** A roof band frames the whole building; a component must span at least this to count as one. */
        val minRoofBandExtentM: Double = 3.0,
        /** Sealed structure within this distance of the roof band is its outline stroke, not a wall: the outside flood passes through it. */
        val roofBandHaloM: Double = 0.20,
        /** How many metres the structure's longer extent is assumed to span before calibration exists. */
        val provisionalStructureSpanM: Double = 14.0,
    )

    fun analyse(
        assetUrl: String,
        image: RasterImage,
        floor: PublishedFloor?,
        footprintAreaM2: Measured?,
        sharedScale: Measured?,
        attic: Boolean,
        thresholds: Thresholds = Thresholds(),
        debugPrefix: String = "plan",
    ): FloorPlanAnalysis {
        val raster = PlanRaster(image)
        val inkBox = raster.ink.boundingBox()
            ?: return empty(assetUrl, image, listOf(AnalysisIssue(IssueSeverity.BLOCKING, "plan", null, "raster has no ink")))

        // Pass 1: a provisional scale from the structure's own extent, which only sizes kernels.
        val roughStructure = raster.structure(minThicknessPx = 4, minStructurePixels = 40)
        val structureBox = roughStructure.boundingBox() ?: inkBox
        val provisionalPpm = sharedScale?.value
            ?: (max(structureBox.width, structureBox.height) / thresholds.provisionalStructureSpanM)
        val first = pass(assetUrl, raster, floor, footprintAreaM2, sharedScale, attic, thresholds, provisionalPpm, PlanDebugSink.NONE, "$debugPrefix-pass1")
        val calibrated = first.calibration?.pixelsPerMeter?.value ?: return first

        // Pass 2: every kernel sized from the calibrated scale.
        return pass(assetUrl, raster, floor, footprintAreaM2, sharedScale, attic, thresholds, calibrated, debug, debugPrefix)
    }

    private fun pass(
        assetUrl: String,
        raster: PlanRaster,
        floor: PublishedFloor?,
        footprintAreaM2: Measured?,
        sharedScale: Measured?,
        attic: Boolean,
        t: Thresholds,
        ppm: Double,
        debug: PlanDebugSink,
        debugPrefix: String,
    ): FloorPlanAnalysis {
        val image = raster.image
        val issues = mutableListOf<AnalysisIssue>()
        fun px(m: Double) = max(1, (m * ppm).roundToInt())

        val structure = raster.structure(minThicknessPx = px(t.minWallThicknessM), minStructurePixels = px(0.4) * px(0.1))
        val thick = raster.thick(thicknessPx = px(t.thickWallM), minPixels = px(0.4) * px(0.2))
        debug.emit("$debugPrefix-0-ink", raster.ink)
        debug.emit("$debugPrefix-1-structure", structure)
        // Thick structure is wall from a short length on (the masonry between a window and a
        // corner); thin structure needs the full minimum, or symbols and hatches become walls.
        val thinPieces = WallPieces.extract(structure, px(t.minWallLengthM), px(t.minWallThicknessM), px(t.maxWallThicknessM))
        val thickStubs = WallPieces.extract(thick, px(t.minThickWallLengthM), px(t.thickWallM), px(t.maxWallThicknessM))
            .filter { s -> thinPieces.none { p -> overlapFraction(s, p) > 0.5 } }
        val rawPieces = thinPieces + thickStubs
        val rawGaps = WallPieces.endGaps(rawPieces, structure, px(t.minOpeningM), px(t.maxOpeningM))

        // Step 3: seal everything, flood the outside, take the footprint. Thin lines never
        // bound the footprint: only pieces, their sealed gaps and thick structure do, so a
        // dimension chain or a hatch cannot enclose a strip of page and call it building.
        // The roof band's own outline stroke is structure by thickness but not a wall: the
        // flood passes through anything sealed that hugs the band, so the outdoors reaches the
        // real walls even when the plan draws an eaves strip between them and the roof edge.
        val roofBand = raster.roofBand(minThicknessPx = px(0.10), minExtentPx = px(t.minRoofBandExtentM))
        if (roofBand.count() > 0) debug.emit("$debugPrefix-0-roofband", roofBand)
        val bandHalo = if (roofBand.count() > 0) roofBand.dilate(px(t.roofBandHaloM), px(t.roofBandHaloM)) else null
        val sealedRaw = WallPieces.rasterise(image.width, image.height, rawPieces, rawGaps).or(thick)
        val outsideAll = (bandHalo?.let { sealedRaw.andNot(it) } ?: sealedRaw).outsideRegion(seed = roofBand)
        debug.emit("$debugPrefix-3a-outside", outsideAll)

        // A piece with the outdoors on both faces bounds nothing: a roof-edge stroke, a terrace
        // border, a fence. It is not a wall of this storey and would otherwise be counted as one.
        val outsideLines = rawPieces.withIndex().filter { (_, p) -> touchesOutside(p, outsideAll, px(0.06), bothFaces = true) }.map { it.index }.toSet()
        val pieces = rawPieces.filterIndexed { i, _ -> i !in outsideLines }
        if (outsideLines.isNotEmpty()) issues += AnalysisIssue(IssueSeverity.INFO, "plan", assetUrl, "${outsideLines.size} line pieces with the outdoors on both faces dropped (roof edge, terrace border or fence, not walls)")
        val allGaps = if (outsideLines.isEmpty()) rawGaps else WallPieces.endGaps(pieces, structure, px(t.minOpeningM), px(t.maxOpeningM))
        val pieceMask = WallPieces.rasterise(image.width, image.height, pieces)
        debug.emit("$debugPrefix-2-pieces", pieceMask)
        val unexplained = structure.andNot(pieceMask.dilate(2, 2)).count()
        val sealedAll = WallPieces.rasterise(image.width, image.height, pieces, allGaps).or(thick)
        debug.emit("$debugPrefix-3b-sealedAll", sealedAll)
        val footprintComponents = outsideAll.not().components()
        val mainLabel = (1..footprintComponents.count).maxByOrNull { footprintComponents.sizes[it] } ?: 0
        val footprintMask = if (mainLabel == 0) BinaryMask(image.width, image.height) else footprintComponents.maskOf(mainLabel)
        val footprintBox = footprintMask.boundingBox()
        val footprintPixelArea = footprintMask.count()
        val footprintOutline = footprintBox?.let { Regions.smoothJogs(Regions.traceOutline(footprintMask, it), px(0.15).toDouble()) } ?: emptyList()
        debug.emit("$debugPrefix-3-footprint", footprintMask)

        // Pieces beside the outside are exterior. Probe a band just beyond each face.
        val exterior = pieces.withIndex().filter { (_, p) -> touchesOutside(p, outsideAll, px(0.06)) }.map { it.index }.toSet()

        // Step 4: seal the gaps on the wall lines, so the plan is over-segmented along the lines
        // the walls define and the matcher joins regions back across those virtual lines. Every
        // exterior gap is sealed (a window or a door is not the outdoors); an interior gap only up
        // to the width a doorway or a double door can have — wider, the "gap" is a room the wall
        // line merely points across.
        // A wide interior gap is still sealed when a collinear wall piece stands on both sides
        // of it: two partitions on one line with an open passage between them are one wall
        // alignment, and the rooms either side of that line are different rooms.
        fun collinearPair(g: PieceGap): Boolean =
            g.beforeIndex >= 0 && g.afterIndex >= 0 && pieces[g.beforeIndex].axis == pieces[g.afterIndex].axis
        val keptGaps = allGaps.filter { g ->
            g.beforeIndex in exterior || g.afterIndex in exterior || g.width <= px(t.interiorSealMaxM) || collinearPair(g)
        }
        val sealed = WallPieces.rasterise(image.width, image.height, pieces, keptGaps).or(thick)
        debug.emit("$debugPrefix-4b-sealed", sealed)
        val outside = sealed.outsideRegion(seed = roofBand)
        debug.emit("$debugPrefix-4a-outside2", outside)
        val (components, regions) = Regions.extract(sealed, outside, minPixels = max(1, (t.minRegionM2 * ppm * ppm).roundToInt()))
        val regionsInFootprint = regions.filter { r ->
            val cx = (r.box.minX + r.box.maxX) / 2
            val cy = (r.box.minY + r.box.maxY) / 2
            footprintMask[cx, cy] || footprintMask[r.box.minX + 1, r.box.minY + 1] || footprintMask[r.box.maxX - 1, r.box.maxY - 1]
        }
        debug.emit("$debugPrefix-4-regions", sealed.or(outside).not())

        // Roof outline (attic plans): the outer edge of the band round the walls.
        val roofBandOutline = if (roofBand.count() > 0) {
            val filled = roofBand.or(footprintMask).close(px(0.3), px(0.3)).outsideRegion().not()
            val comps = filled.components()
            val big = (1..comps.count).maxByOrNull { comps.sizes[it] }
            big?.let { label -> comps.boundingBox(label)?.let { box -> Regions.smoothJogs(Regions.traceOutline(comps.maskOf(label), box), px(0.15).toDouble()) } }
        } else null

        // Step 5: calibration.
        val regionsPixelArea = regionsInFootprint.sumOf { it.pixelArea }
        val publishedTotal = floor?.let { f -> f.floorAreaTotal.takeIf { it.value != null } ?: f.usableAreaTotal.takeIf { it.value != null } }
        val origin = footprintBox?.let { Pt(it.minX.toDouble(), it.minY.toDouble()) } ?: Pt(0.0, 0.0)
        val calibration = PlanCalibrator.calibrate(
            footprintPixelArea = footprintPixelArea.takeIf { it > 0 },
            footprintAreaM2 = footprintAreaM2,
            regionsPixelArea = regionsPixelArea.takeIf { it > 0 },
            publishedFloorTotalM2 = publishedTotal,
            originPx = origin,
            sharedScale = sharedScale,
        )
        if (calibration == null) {
            issues += AnalysisIssue(IssueSeverity.BLOCKING, "calibration", assetUrl, "no source anchor for the plan scale: neither a published footprint area nor room totals")
        } else if (calibration.residual != null && calibration.residual > 0.08) {
            issues += AnalysisIssue(IssueSeverity.WARNING, "calibration", assetUrl, "scale anchors disagree by ${"%.1f".format(java.util.Locale.ROOT, calibration.residual * 100)} %")
        }
        if (unexplained > px(0.4) * px(0.4)) {
            issues += AnalysisIssue(IssueSeverity.INFO, "walls", assetUrl, "$unexplained structure pixels are not on any axis-aligned wall piece (oblique walls or thick symbols); they bound rooms but have no wall candidate")
        }

        // Step 6: rooms. Regions touch across sealed gaps, never across walls.
        val labelIndex = regionsInFootprint.withIndex().associate { (i, r) -> r.label to i }
        val adjacency = keptGaps.mapNotNull { g ->
            val (a, b) = WallPieces.sidesOf(g, { x, y -> components.label(x, y) }, px(0.08))
            val ia = labelIndex[a]
            val ib = labelIndex[b]
            if (ia != null && ib != null && ia != ib) ia to ib else null
        }.distinct()
        // Structural cues: a region beside a gate-width exterior opening, a region with a stair flight.
        val cues = HashMap<Int, MutableSet<RoomMatcher.RegionCue>>()
        keptGaps.filter { g -> (g.beforeIndex in exterior || g.afterIndex in exterior) && g.width >= px(t.gateMinM) }.forEach { g ->
            val (a, b) = WallPieces.sidesOf(g, { x, y -> components.label(x, y) }, px(0.08))
            listOfNotNull(labelIndex[a], labelIndex[b]).forEach { cues.getOrPut(it) { mutableSetOf() } += RoomMatcher.RegionCue.GATE }
        }
        val stairsEarly = StairDetector.detect(
            raster.thinInk(structure),
            minTreadLengthPx = px(t.minTreadM),
            spacingPx = px(t.treadSpacingM.start)..px(t.treadSpacingM.endInclusive),
        )
        stairsEarly.forEach { z ->
            val label = components.label((z.box.minX + z.box.maxX) / 2, (z.box.minY + z.box.maxY) / 2)
            labelIndex[label]?.let { cues.getOrPut(it) { mutableSetOf() } += RoomMatcher.RegionCue.STAIR }
        }
        val matching = if (calibration != null && floor != null) {
            val ppm2 = calibration.pixelsPerMeter.requireValue().let { it * it }
            RoomMatcher.match(regionsInFootprint.map { it.pixelArea / ppm2 }, adjacency, floor.rooms, attic, cues)
        } else null
        matching?.ambiguities?.forEach { issues += AnalysisIssue(IssueSeverity.WARNING, "rooms", assetUrl, it) }
        matching?.unmatchedRooms?.forEach { issues += AnalysisIssue(IssueSeverity.WARNING, "rooms", floor?.rooms?.get(it)?.name, "published room has no region of matching area") }

        // Step 7: stairs.
        val thin = raster.thinInk(structure)
        val stairs = StairDetector.detect(
            thin,
            minTreadLengthPx = px(t.minTreadM),
            spacingPx = px(t.treadSpacingM.start)..px(t.treadSpacingM.endInclusive),
        ).filter { z -> footprintMask[(z.box.minX + z.box.maxX) / 2, (z.box.minY + z.box.maxY) / 2] }
        if (stairs.isNotEmpty()) {
            val m = BinaryMask(image.width, image.height)
            stairs.forEach { z -> for (y in z.box.minY..z.box.maxY) for (x in z.box.minX..z.box.maxX) m[x, y] = true }
            debug.emit("$debugPrefix-6-stairs", m)
        }

        return FloorPlanAnalysis(
            assetUrl = assetUrl,
            image = image,
            pieces = pieces,
            gaps = allGaps,
            sealedGaps = keptGaps,
            exteriorPieceIndices = exterior,
            footprintPixelArea = footprintPixelArea,
            footprintOutlinePx = footprintOutline,
            roofBandOutlinePx = roofBandOutline,
            regions = regionsInFootprint,
            regionLabelAt = { x, y -> components.label(x, y) },
            regionIndexByLabel = labelIndex,
            stairs = stairs,
            thinInk = thin,
            calibration = calibration,
            matching = matching,
            unexplainedStructurePixels = unexplained,
            issues = issues,
        )
    }

    /** Whether a probe band just beyond either face of the piece lands mostly on outside pixels. */
    /** Share of [a]'s rectangle covered by [b]. */
    private fun overlapFraction(a: WallPiece, b: WallPiece): Double {
        val w = min(a.maxX(), b.maxX()) - max(a.minX(), b.minX())
        val h = min(a.maxY(), b.maxY()) - max(a.minY(), b.minY())
        if (w <= 0 || h <= 0) return 0.0
        val area = (a.maxX() - a.minX()).toDouble() * (a.maxY() - a.minY())
        return if (area <= 0) 0.0 else w.toDouble() * h / area
    }

    private fun touchesOutside(piece: WallPiece, outside: BinaryMask, band: Int, bothFaces: Boolean = false): Boolean {
        fun probe(lowSide: Boolean): Boolean {
            var hit = 0
            var total = 0
            val across = if (lowSide) (piece.low - band - 1) until (piece.low - 1) else (piece.high + 1) until (piece.high + band + 1)
            val step = max(1, piece.length / 40)
            var a = piece.from + piece.length / 10
            while (a < piece.to - piece.length / 10) {
                for (c in across) {
                    val x = if (piece.axis == Axis.HORIZONTAL) a else c
                    val y = if (piece.axis == Axis.HORIZONTAL) c else a
                    total++
                    if (outside[x, y]) hit++
                }
                a += step
            }
            return total > 0 && hit * 2 > total
        }
        return if (bothFaces) probe(true) && probe(false) else probe(true) || probe(false)
    }

    private fun empty(assetUrl: String, image: RasterImage, issues: List<AnalysisIssue>) = FloorPlanAnalysis(
        assetUrl, image, emptyList(), emptyList(), emptyList(), emptySet(), 0, emptyList(), null, emptyList(), { _, _ -> 0 }, emptyMap(), emptyList(), BinaryMask(image.width, image.height), null, null, 0, issues,
    )
}

/** A pixel box as a metre box under a calibration. */
fun PixelBox.toMeters(calibration: PlanCalibration): Box {
    val a = calibration.toMeters(Pt(minX.toDouble(), minY.toDouble()))
    val b = calibration.toMeters(Pt(maxX + 1.0, maxY + 1.0))
    return Box(min(a.x, b.x), min(a.z, b.z), max(a.x, b.x), max(a.z, b.z))
}

/** A wall piece's centreline in metres. */
fun WallPiece.centrelineMeters(calibration: PlanCalibration): Segment {
    val a = if (axis == Axis.HORIZONTAL) Pt(from.toDouble(), centre) else Pt(centre, from.toDouble())
    val b = if (axis == Axis.HORIZONTAL) Pt(to.toDouble(), centre) else Pt(centre, to.toDouble())
    return Segment(calibration.toMeters(a), calibration.toMeters(b))
}

/** A polygon in metres from a pixel outline, or null when degenerate. */
fun List<Pt>.toMeterPolygon(calibration: PlanCalibration): Polygon? =
    Regions.toPolygon(map { calibration.toMeters(it) })

/** A measured length in metres from a pixel count under a calibration; uncertainty is one pixel. */
fun PlanCalibration.tracedLength(px: Double, what: String, assetUrl: String): Measured = Measured.traced(
    value = px / pixelsPerMeter.requireValue(),
    unit = MeasureUnit.METER,
    provenance = Provenance(assetUrl, what, "traced on the plan raster at ${"%.2f".format(java.util.Locale.ROOT, pixelsPerMeter.requireValue())} px/m"),
    uncertainty = 1.0 / pixelsPerMeter.requireValue(),
)

/** A measured area in square metres from a pixel count. */
fun PlanCalibration.tracedArea(pixels: Int, what: String, assetUrl: String): Measured {
    val ppm = pixelsPerMeter.requireValue()
    return Measured.traced(
        value = pixels / (ppm * ppm),
        unit = MeasureUnit.SQUARE_METER,
        provenance = Provenance(assetUrl, what, "pixel count of the enclosed region at ${"%.2f".format(java.util.Locale.ROOT, ppm)} px/m"),
        uncertainty = 2 * Math.sqrt(pixels.toDouble()) / (ppm * ppm),
    )
}

/** Fidelity of anything derived from a calibration: never better than the calibration itself. */
fun PlanCalibration.cap(fidelity: FactFidelity): FactFidelity = FactFidelity.weakest(listOf(fidelity, confidence))
