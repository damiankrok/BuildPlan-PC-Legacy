package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.candidate.AnalysisIssue
import com.buildplan.app.analyzer.candidate.FloorCandidate
import com.buildplan.app.analyzer.candidate.IssueSeverity
import com.buildplan.app.analyzer.candidate.OpeningCandidate
import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RegionCandidate
import com.buildplan.app.analyzer.candidate.RoomBoundarySegment
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.candidate.Segment
import com.buildplan.app.analyzer.candidate.StairCandidate
import com.buildplan.app.analyzer.candidate.WallCandidate
import com.buildplan.app.analyzer.candidate.WallClass
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.site.PublishedFloor
import com.buildplan.app.analyzer.site.RoomKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns one storey's plan analysis into candidates: rooms with polygons,
 * walls, openings and stairs, all in metres with fidelity and provenance.
 *
 * Nothing new is measured here; the builder converts, joins and labels. The
 * one construction it adds is the proportional split of an open-plan region
 * shared by several published rooms, and that split is a
 * [FactFidelity.DISPLAY_ASSUMPTION] with the question it raises.
 */
object FloorCandidateBuilder {

    class Built(
        val floor: FloorCandidate,
        val walls: List<WallCandidate>,
        val openings: List<OpeningCandidate>,
        val stairs: List<StairCandidate>,
        val issues: List<AnalysisIssue>,
        /** Room id -> pixel mask, for the quantity engine's raster work. */
        val roomMasks: Map<String, BinaryMask>,
    )

    fun build(
        index: Int,
        name: String,
        order: Int,
        published: PublishedFloor?,
        analysis: FloorPlanAnalysis,
        floorElevation: Measured,
        clearHeight: Measured,
        thresholds: PlanAnalyzer.Thresholds = PlanAnalyzer.Thresholds(),
    ): Built {
        val floorId = "f$index"
        val issues = analysis.issues.toMutableList()
        val calibration = analysis.calibration
        if (calibration == null) {
            return Built(
                FloorCandidate(floorId, name, order, null, null, null, floorElevation, clearHeight, emptyList(), emptyList(), analysis.assetUrl),
                emptyList(), emptyList(), emptyList(), issues, emptyMap(),
            )
        }
        val ppm = calibration.pixelsPerMeter.requireValue()
        fun px(m: Double) = max(1, (m * ppm).roundToInt())
        val url = analysis.assetUrl
        val image = analysis.image

        // ---- rooms
        val rooms = mutableListOf<RoomCandidate>()
        val roomMasks = LinkedHashMap<String, BinaryMask>()
        val regionToRooms = HashMap<Int, MutableList<String>>()
        val roomSeq = HashMap<Int, Int>()
        fun roomId(roomIndex: Int): String {
            val room = published?.rooms?.get(roomIndex)
            val ordinal = room?.ordinal ?: (roomIndex + 1)
            return "$floorId-r$ordinal"
        }
        analysis.matching?.matches?.forEach { match ->
            val mask = maskOf(analysis, match.regionIndices)
            val box = mask.boundingBox() ?: return@forEach
            if (match.roomIndices.size == 1) {
                val ri = match.roomIndices.first()
                val room = published!!.rooms[ri]
                val id = roomId(ri)
                val polygon = polygonOf(mask, box, calibration, px(0.15)) ?: return@forEach
                match.regionIndices.forEach { regionToRooms.getOrPut(it) { mutableListOf() } += id }
                roomMasks[id] = mask
                rooms += roomCandidate(id, floorId, room.name, room.ordinal, polygon, mask.count(), calibration, url, room.usableArea, room.floorArea, calibration.cap(match.fidelity), match.note)
            } else {
                // Open plan: one region, several rooms. Split proportionally to published areas
                // along the region's longer axis, in table order.
                val ordered = match.roomIndices.sortedBy { published!!.rooms[it].ordinal ?: it }
                val areas = ordered.map { published!!.rooms[it].let { r -> r.floorArea.value ?: r.usableArea.value ?: 1.0 } }
                val parts = splitProportionally(mask, box, areas)
                ordered.forEachIndexed { k, ri ->
                    val room = published!!.rooms[ri]
                    val id = roomId(ri)
                    val partMask = parts[k]
                    val partBox = partMask.boundingBox() ?: return@forEachIndexed
                    val polygon = polygonOf(partMask, partBox, calibration, px(0.15)) ?: return@forEachIndexed
                    match.regionIndices.forEach { regionToRooms.getOrPut(it) { mutableListOf() } += id }
                    roomMasks[id] = partMask
                    rooms += roomCandidate(
                        id, floorId, room.name, room.ordinal, polygon, partMask.count(), calibration, url, room.usableArea, room.floorArea,
                        FactFidelity.DISPLAY_ASSUMPTION,
                        "open-plan region shared with ${ordered.filter { it != ri }.joinToString { published.rooms[it].name }}; boundary is a proportional split by published area, not a drawn wall",
                    )
                }
                issues += AnalysisIssue(IssueSeverity.WARNING, "rooms", ordered.joinToString { roomId(it) }, "Źródło nie rysuje ścian między tymi pomieszczeniami; podział regionu jest proporcjonalny do podanych powierzchni.")
            }
        }
        val unmatched = analysis.matching?.unmatchedRegions.orEmpty().mapNotNull { ri ->
            val region = analysis.regions[ri]
            val mask = maskOf(analysis, listOf(ri))
            val poly = polygonOf(mask, region.box, calibration, px(0.15)) ?: return@mapNotNull null
            RegionCandidate("$floorId-region${ri + 1}", poly, region.pixelArea / (ppm * ppm), emptyList())
        }

        // ---- walls
        val walls = analysis.pieces.mapIndexed { i, piece ->
            val id = "$floorId-w${i + 1}"
            val centre = piece.centrelineMeters(calibration)
            val thicknessM = piece.thickness / ppm
            val exterior = i in analysis.exteriorPieceIndices
            // A thin piece on the outer boundary is a light element (parapet, terrace edge, glazing
            // frame), not the masonry envelope; a thick piece shorter than twice its thickness is a
            // pier, chimney or duct block. Neither is summed as a wall of its class.
            val stub = piece.length < 2 * piece.thickness
            val wallClass = when {
                exterior && thicknessM < thresholds.thickWallM -> WallClass.LIGHT_EXTERIOR
                exterior -> WallClass.EXTERIOR
                thicknessM >= thresholds.thickWallM && stub -> WallClass.PIER
                thicknessM >= thresholds.thickWallM -> WallClass.INTERNAL_LOAD_BEARING
                else -> WallClass.PARTITION
            }
            val (left, right) = sideRooms(piece, analysis, regionToRooms, px(0.10))
            WallCandidate(
                id = id,
                floorId = floorId,
                centreline = centre,
                length = calibration.tracedLength(piece.length.toDouble(), "piece ${i + 1} length", url),
                thickness = calibration.tracedLength(piece.thickness.toDouble(), "piece ${i + 1} thickness", url),
                wallClass = wallClass,
                baseElevation = floorElevation,
                height = clearHeight,
                roomIdsLeft = left,
                roomIdsRight = right,
                touchesOutside = exterior,
                openingIds = emptyList(),
                fidelity = calibration.cap(FactFidelity.SOURCE_TRACED),
            )
        }.toMutableList()

        // ---- openings
        val openings = analysis.gaps.mapIndexed { i, gap ->
            val id = "$floorId-o${i + 1}"
            val before = gap.beforeIndex.takeIf { it >= 0 }
            val after = gap.afterIndex.takeIf { it >= 0 }
            val wallIndex = before ?: after ?: -1
            val wallId = if (wallIndex >= 0) "$floorId-w${wallIndex + 1}" else "$floorId-w?"
            val besideExteriorPiece = (before != null && before in analysis.exteriorPieceIndices) || (after != null && after in analysis.exteriorPieceIndices)
            val widthM = gap.width / ppm
            val (sideA, sideB) = WallPieces.sidesOf(gap, analysis.regionLabelAt, px(0.10))
            // An opening is exterior only when one of its sides is not a room: a gap on an interior
            // wall line that happens to continue an exterior wall has rooms on both sides.
            val opensToOutside = analysis.regionIndexByLabel[sideA] == null || analysis.regionIndexByLabel[sideB] == null
            val exterior = besideExteriorPiece && opensToOutside
            val linked = listOfNotNull(analysis.regionIndexByLabel[sideA], analysis.regionIndexByLabel[sideB]).flatMap { regionToRooms[it].orEmpty() }.distinct()
            val glazed = glazingAcross(gap, analysis.thinInk)
            val kinds = linked.mapNotNull { rid -> published?.rooms?.firstOrNull { roomId(published.rooms.indexOf(it)) == rid }?.kind }
            val type = when {
                exterior && widthM >= thresholds.gateMinM && kinds.contains(RoomKind.GARAGE) -> OpeningType.GARAGE_GATE
                exterior && glazed -> OpeningType.WINDOW
                exterior -> OpeningType.DOOR
                widthM <= thresholds.doorLikeMaxM -> OpeningType.DOOR
                else -> OpeningType.PASSAGE
            }
            val typeFidelity = when (type) {
                OpeningType.GARAGE_GATE -> FactFidelity.SOURCE_TRACED
                OpeningType.WINDOW, OpeningType.DOOR -> FactFidelity.TRACE_UNCERTAIN
                else -> FactFidelity.TRACE_UNCERTAIN
            }
            val distance = if (before != null) analysis.pieces[before].length / ppm else -widthM
            OpeningCandidate(
                id = id,
                wallId = wallId,
                floorId = floorId,
                type = type,
                distanceAlongWall = Measured.traced(distance, MeasureUnit.METER, Provenance(url, "gap ${i + 1} position on the wall line", "measured from the wall piece start; equals the piece length when the opening follows it"), 1.0 / ppm),
                width = calibration.tracedLength(gap.width.toDouble(), "gap ${i + 1} width", url),
                height = Measured.missing(MeasureUnit.METER, "opening heights are printed in the joinery schedule as text; not read by this stage"),
                sillHeight = Measured.missing(MeasureUnit.METER, "sill heights are not printed on the plan"),
                linkedRoomIds = linked,
                exterior = exterior,
                typeFidelity = typeFidelity,
            )
        }
        // Attach opening ids to walls.
        openings.forEach { o ->
            val wi = walls.indexOfFirst { it.id == o.wallId }
            if (wi >= 0) walls[wi] = walls[wi].copy(openingIds = walls[wi].openingIds + o.id)
        }

        // ---- stairs
        val stairs = analysis.stairs.mapIndexed { i, zone ->
            StairCandidate(
                id = "$floorId-s${i + 1}",
                floorId = floorId,
                zone = zone.box.toMeters(calibration),
                treadCount = Measured(zone.treadLines.toDouble(), MeasureUnit.COUNT, FactFidelity.TRACE_UNCERTAIN, Provenance(url, "stair zone ${i + 1}", "count of parallel thin lines; nosing lines and landings are not told apart")),
                direction = "unknown",
                fidelity = FactFidelity.SOURCE_TRACED,
                note = "zone of ${zone.treadLines} parallel lines spaced like treads; direction arrows are not read",
            )
        }

        // ---- room boundary segments, now that walls exist
        val roomsWithBoundary = rooms.map { room ->
            room.copy(boundary = boundaryOf(room, walls, rooms, analysis, regionToRooms, calibration))
        }

        val footprint = analysis.footprintOutlinePx.toMeterPolygon(calibration)
        val roofOutline = analysis.roofBandOutlinePx?.toMeterPolygon(calibration)
        val floor = FloorCandidate(
            id = floorId,
            name = name,
            order = order,
            calibration = calibration,
            footprint = footprint,
            roofOutline = roofOutline,
            floorElevation = floorElevation,
            clearHeight = clearHeight,
            rooms = roomsWithBoundary,
            unmatchedRegions = unmatched,
            planAssetUrl = url,
        )
        return Built(floor, walls, openings, stairs, issues, roomMasks)
    }

    private fun roomCandidate(
        id: String, floorId: String, name: String, ordinal: Int?, polygon: Polygon, pixels: Int, calibration: PlanCalibration, url: String,
        usable: Measured, floorArea: Measured, fidelity: FactFidelity, note: String,
    ) = RoomCandidate(
        id = id,
        floorId = floorId,
        name = name,
        sourceOrdinal = ordinal,
        polygon = polygon,
        perimeter = Measured.derived(polygon.perimeter, MeasureUnit.METER, "polygon perimeter", listOf(calibration.pixelsPerMeter)),
        plannedArea = calibration.tracedArea(pixels, "room $id region", url),
        sourceUsableArea = usable,
        sourceFloorArea = floorArea,
        boundary = emptyList(),
        matchConfidence = fidelity,
        matchNote = note,
    )

    private fun maskOf(analysis: FloorPlanAnalysis, regionIndices: List<Int>): BinaryMask {
        val mask = BinaryMask(analysis.image.width, analysis.image.height)
        regionIndices.forEach { ri ->
            val region = analysis.regions[ri]
            for (y in region.box.minY..region.box.maxY) for (x in region.box.minX..region.box.maxX) {
                if (analysis.regionLabelAt(x, y) == region.label) mask[x, y] = true
            }
        }
        return mask
    }

    private fun polygonOf(mask: BinaryMask, box: PixelBox, calibration: PlanCalibration, jogPx: Int): Polygon? {
        val outline = Regions.smoothJogs(Regions.traceOutline(mask, box), jogPx.toDouble())
        return outline.toMeterPolygon(calibration)
    }

    /** Splits a mask into parts of the given area proportions along the longer axis of its box. */
    private fun splitProportionally(mask: BinaryMask, box: PixelBox, areas: List<Double>): List<BinaryMask> {
        val total = mask.count().toDouble()
        val targets = areas.map { it / areas.sum() * total }
        val horizontalSplit = box.width >= box.height
        val parts = areas.map { BinaryMask(mask.width, mask.height) }
        var part = 0
        var accumulated = 0.0
        val range = if (horizontalSplit) box.minX..box.maxX else box.minY..box.maxY
        for (a in range) {
            var lineCount = 0
            val across = if (horizontalSplit) box.minY..box.maxY else box.minX..box.maxX
            for (c in across) {
                val x = if (horizontalSplit) a else c
                val y = if (horizontalSplit) c else a
                if (mask[x, y]) { parts[part][x, y] = true; lineCount++ }
            }
            accumulated += lineCount
            while (part < parts.size - 1 && accumulated >= targets.take(part + 1).sum()) part++
        }
        return parts
    }

    /** Room ids beside each face of a wall piece, sampled just beyond its faces. */
    private fun sideRooms(piece: WallPiece, analysis: FloorPlanAnalysis, regionToRooms: Map<Int, List<String>>, probe: Int): Pair<List<String>, List<String>> {
        fun sample(lowSide: Boolean): List<String> {
            val found = LinkedHashSet<String>()
            val c = if (lowSide) piece.low - probe else piece.high - 1 + probe
            val step = max(1, piece.length / 8)
            var a = piece.from + step / 2
            while (a < piece.to) {
                val x = if (piece.axis == Axis.HORIZONTAL) a else c
                val y = if (piece.axis == Axis.HORIZONTAL) c else a
                val label = analysis.regionLabelAt(x, y)
                analysis.regionIndexByLabel[label]?.let { found += regionToRooms[it].orEmpty() }
                a += step
            }
            return found.toList()
        }
        return sample(true) to sample(false)
    }

    /** Whether thin ink runs along the wall line inside the gap: the glazing lines of a window. */
    private fun glazingAcross(gap: PieceGap, thin: BinaryMask): Boolean {
        var hits = 0
        for (a in gap.from until gap.to) {
            var any = false
            for (c in gap.low until gap.high) {
                val x = if (gap.axis == Axis.HORIZONTAL) a else c
                val y = if (gap.axis == Axis.HORIZONTAL) c else a
                if (thin[x, y]) { any = true; break }
            }
            if (any) hits++
        }
        return gap.width > 0 && hits.toDouble() / gap.width >= 0.5
    }

    private fun boundaryOf(
        room: RoomCandidate,
        walls: List<WallCandidate>,
        rooms: List<RoomCandidate>,
        analysis: FloorPlanAnalysis,
        regionToRooms: Map<Int, List<String>>,
        calibration: PlanCalibration,
    ): List<RoomBoundarySegment> = room.polygon.edges.map { edge ->
        val horizontal = abs(edge.a.z - edge.b.z) < 1e-9
        val wall = walls.filter { w ->
            val wc = w.centreline
            val wHorizontal = abs(wc.a.z - wc.b.z) < 1e-9
            if (wHorizontal != horizontal) return@filter false
            val half = (w.thickness.value ?: 0.0) / 2 + 0.12
            val offset = if (horizontal) abs(wc.a.z - edge.a.z) else abs(wc.a.x - edge.a.x)
            if (offset > half) return@filter false
            val (e0, e1) = if (horizontal) min(edge.a.x, edge.b.x) to max(edge.a.x, edge.b.x) else min(edge.a.z, edge.b.z) to max(edge.a.z, edge.b.z)
            val (w0, w1) = if (horizontal) min(wc.a.x, wc.b.x) to max(wc.a.x, wc.b.x) else min(wc.a.z, wc.b.z) to max(wc.a.z, wc.b.z)
            min(e1, w1) - max(e0, w0) > 0.05
        }.minByOrNull { w -> if (horizontal) abs(w.centreline.a.z - edge.a.z) else abs(w.centreline.a.x - edge.a.x) }
        // The room on the other side: probe a point beyond the wall from the edge midpoint.
        val mid = edge.midpoint
        val inside = room.polygon.centroid
        val outwardX = if (horizontal) 0.0 else if (mid.x > inside.x) 1.0 else -1.0
        val outwardZ = if (horizontal) (if (mid.z > inside.z) 1.0 else -1.0) else 0.0
        val depth = (wall?.thickness?.value ?: 0.12) + 0.15
        val probe = calibration.toPixels(Pt(mid.x + outwardX * depth, mid.z + outwardZ * depth))
        val label = analysis.regionLabelAt(probe.x.roundToInt(), probe.z.roundToInt())
        val neighbour = analysis.regionIndexByLabel[label]?.let { regionToRooms[it].orEmpty().firstOrNull { it != room.id } }
        RoomBoundarySegment(
            segment = Segment(edge.a, edge.b),
            wallId = wall?.id,
            neighbourRoomId = neighbour,
            faceOutside = neighbour == null && (wall?.touchesOutside ?: false),
        )
    }
}
