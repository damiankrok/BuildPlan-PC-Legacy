package com.buildplan.app.analyzer.quantity

import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.candidate.WallCandidate
import com.buildplan.app.analyzer.candidate.WallClass
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.roof.RoofHeightField
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

enum class SurfaceType { FLOOR, WALL_FACE, CEILING_FLAT, CEILING_SLOPED, ROOF_FACET, FACADE, OPENING }

/**
 * One individually addressable surface with its measurement semantics.
 *
 * A wall between two rooms is one [WallCandidate] and *two* surfaces — one
 * face per room — because plaster and paint are priced per side while
 * masonry is priced once. The two must never be summed as if they were the
 * same quantity; [semantics] says which one this is.
 */
data class MeasuredSurfaceCandidate(
    val id: String,
    val type: SurfaceType,
    val ownerId: String,
    val roomId: String?,
    val wallId: String?,
    /** The room on the other side of a wall face, or null when it faces outside or is unresolved. */
    val neighbourRoomId: String?,
    val facesOutside: Boolean,
    val basis: String,
    val grossArea: Measured,
    val deductions: Measured,
    val netArea: Measured,
    val semantics: String,
)

data class RoomQuantities(
    val roomId: String,
    val floorArea: Measured,
    val perimeter: Measured,
    val wallFaceIds: List<String>,
    val wallGross: Measured,
    val wallOpenings: Measured,
    val wallNet: Measured,
    val ceilingFlat: Measured,
    val ceilingSloped: Measured,
    val ceilingTotal: Measured,
    val volume: Measured,
    /** Usable area by the published rule: full above 2.2 m, half between 1.4 and 2.2 m, none below. */
    val usableAreaByHeightRule: Measured,
    val meanHeight: Measured,
    val boundaryLengths: List<Double>,
)

data class FloorQuantities(
    val floorId: String,
    val roomFloorAreaSum: Measured,
    /**
     * Masonry: the traced exterior wall pieces of this storey.
     *
     * Already net of every opening, because an opening is a gap *between*
     * pieces and never a piece — so nothing may subtract openings from this
     * again. It is also short of whatever envelope the raster left oblique or
     * unresolved, which makes it a lower bracket rather than a measurement of
     * the envelope.
     */
    val exteriorWallsStructural: Measured,
    val loadBearingWallsStructural: Measured,
    val partitionsStructural: Measured,
    /** The envelope over the openings: this storey's footprint perimeter × its height. */
    val exteriorEnvelopeGross: Measured,
    /** [exteriorEnvelopeGross] minus the exterior openings on this storey. */
    val exteriorEnvelopeNet: Measured,
    val openingAreasByType: Map<OpeningType, Double>,
)

data class ProjectQuantities(
    val surfaces: List<MeasuredSurfaceCandidate>,
    val rooms: List<RoomQuantities>,
    val floors: List<FloorQuantities>,
    val roofFacetAreas: List<Pair<String, Double>>,
    val roofTotal: Measured,
    val ridgeLength: Measured,
    val hipLength: Measured,
    val eaveLength: Measured,
    val exteriorJoinery: Measured,
    /**
     * The envelope as a *surface*: the storey footprints' perimeters run up
     * their storey heights, plus the gable panels. Openings are part of it,
     * because a facade is measured over the wall and the openings are taken
     * off afterwards.
     */
    val facadeGross: Measured,
    val facadeNet: Measured,
    /**
     * The envelope as *masonry*: the traced exterior wall pieces only.
     *
     * A different quantity from [facadeGross] and never comparable with it —
     * this one already excludes every opening, because an opening is not a
     * wall piece, and it also drops whatever stretch of envelope the raster
     * left as oblique or unresolved structure. It is the lower bracket on
     * what the envelope can be.
     */
    val facadeWallMaterial: Measured,
    val floorsAndStairsArea: Measured,
    val notes: List<String>,
)

/**
 * The first quantity takeoff: per-room floors, individual wall faces with
 * deductions, flat and sloped ceilings, volumes and the usable-area rule,
 * structural wall quantities per floor, roof facets and lengths, joinery
 * and facade.
 *
 * Heights come from the vertical candidate and the roof height field; where
 * an opening's height is MISSING a stated assumption is used for the
 * deduction and the net figure inherits DISPLAY_ASSUMPTION — the gross
 * figure stays traced. Surfaces under a roof are integrated on a grid.
 */
class QuantityTakeoffEngine(
    private val candidate: ProjectAnalysisCandidate,
    private val roofField: RoofHeightField?,
    private val gridM: Double = 0.05,
    private val assumedOpeningHeights: Map<OpeningType, Double> = mapOf(
        OpeningType.DOOR to 2.05,
        OpeningType.WINDOW to 1.50,
        OpeningType.GARAGE_GATE to 2.20,
        OpeningType.ROOFLIGHT to 1.18,
        OpeningType.UNKNOWN to 1.50,
    ),
) {

    private val levels = candidate.levels
    private val topFloorId = candidate.floors.maxByOrNull { it.order }?.id
    private val pitch = candidate.roof?.pitchDegrees?.value ?: 0.0
    private val cosPitch = cos(Math.toRadians(pitch))

    fun compute(): ProjectQuantities {
        val surfaces = mutableListOf<MeasuredSurfaceCandidate>()
        val roomQuantities = mutableListOf<RoomQuantities>()
        val notes = mutableListOf<String>()
        val assumption = Provenance.assumption("opening heights assumed per type (door ${assumedOpeningHeights[OpeningType.DOOR]} m, window ${assumedOpeningHeights[OpeningType.WINDOW]} m, gate ${assumedOpeningHeights[OpeningType.GARAGE_GATE]} m) because the plan prints them as text this stage does not read")
        notes += assumption.method

        for (floor in candidate.floors) {
            val floorY = floor.floorElevation.value ?: 0.0
            val clear = floor.clearHeight.value ?: levels.groundClearHeight.value ?: 2.70
            val isTop = floor.id == topFloorId && roofField != null
            val flatCeiling = if (isTop) (levels.atticFlatCeilingHeight.value ?: clear) else clear

            fun heightAt(p: Pt): Double {
                if (!isTop) return clear
                val roofY = roofField!!.heightAt(p) ?: return flatCeiling
                return max(0.0, min(flatCeiling, roofY - floorY))
            }

            for (room in floor.rooms) {
                val floorArea = room.plannedArea
                // No proved ring, no polygon quantities. The region's pixel area is a measurement
                // of the room and stands; ceilings, volumes and wall faces are measurements of its
                // *outline*, and computing them from a ring that was rejected would put an
                // apparently exact number on geometry the analyzer has said it cannot resolve.
                if (room.polygon == null) {
                    val why = "room geometry unresolved: ${room.geometryNote}"
                    roomQuantities += unresolvedRoom(room, floorArea, why)
                    surfaces += MeasuredSurfaceCandidate(
                        "${room.id}-floor", SurfaceType.FLOOR, room.id, room.id, null, null, false,
                        "enclosed region pixel area (no ring)", floorArea, zero(), floorArea,
                        "floor finish area from the segmented region; the room has no proved outline",
                    )
                    notes += "${room.id}: $why"
                    continue
                }
                val grid = integrate(room, ::heightAt)
                val fidelityH = if (isTop) FactFidelity.weakest(listOf(room.matchConfidence, levels.atticFlatCeilingHeight.fidelity)) else FactFidelity.weakest(listOf(room.matchConfidence, floor.clearHeight.fidelity))
                val faceIds = mutableListOf<String>()
                var gross = 0.0
                var deductions = 0.0
                var deductionFidelity = fidelityH
                room.boundary.forEachIndexed { i, seg ->
                    val length = seg.segment.length
                    val faceGross = lineIntegral(seg.segment.a, seg.segment.b, ::heightAt)
                    val openings = candidate.openings.filter { o -> o.wallId == seg.wallId && (room.id in o.linkedRoomIds || o.linkedRoomIds.isEmpty()) && overlapsSegment(o, seg.wallId, seg.segment) }
                    var faceDeduction = 0.0
                    openings.forEach { o ->
                        val h = o.height.value ?: assumedOpeningHeights[o.type] ?: 0.0
                        val w = o.width.value ?: 0.0
                        val full = if (o.type == OpeningType.PASSAGE) faceGross / max(length, 1e-6) * w else w * h
                        faceDeduction += min(full, faceGross)
                        if (o.height.value == null) deductionFidelity = FactFidelity.DISPLAY_ASSUMPTION
                    }
                    val id = "${room.id}-face${i + 1}"
                    faceIds += id
                    gross += faceGross
                    deductions += faceDeduction
                    surfaces += MeasuredSurfaceCandidate(
                        id = id,
                        type = SurfaceType.WALL_FACE,
                        ownerId = room.id,
                        roomId = room.id,
                        wallId = seg.wallId,
                        neighbourRoomId = seg.neighbourRoomId,
                        facesOutside = seg.faceOutside,
                        basis = "boundary segment ${"%.2f".format(java.util.Locale.ROOT, length)} m from (${"%.2f".format(java.util.Locale.ROOT, seg.segment.a.x)}, ${"%.2f".format(java.util.Locale.ROOT, seg.segment.a.z)}) to (${"%.2f".format(java.util.Locale.ROOT, seg.segment.b.x)}, ${"%.2f".format(java.util.Locale.ROOT, seg.segment.b.z)}), height integrated along it",
                        grossArea = Measured(faceGross, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("segment length × height profile", listOf("room polygon", if (isTop) "roof height field" else "storey clear height"))),
                        deductions = Measured(faceDeduction, MeasureUnit.SQUARE_METER, if (openings.isEmpty()) fidelityH else deductionFidelity, if (openings.isEmpty()) Provenance.derived("no openings on this face") else assumption),
                        netArea = Measured(faceGross - faceDeduction, MeasureUnit.SQUARE_METER, if (openings.isEmpty()) fidelityH else deductionFidelity, Provenance.derived("gross − opening deductions")),
                        semantics = "room-facing finish area of one side of the wall (plaster/paint); not a structural wall quantity",
                    )
                }
                val ceilingFlat = grid.flatArea
                val ceilingSloped = grid.slopedPlanArea / cosPitch
                surfaces += MeasuredSurfaceCandidate("${room.id}-floor", SurfaceType.FLOOR, room.id, room.id, null, null, false, "room polygon", floorArea, zero(), floorArea, "floor finish area of the room polygon")
                surfaces += MeasuredSurfaceCandidate("${room.id}-ceiling-flat", SurfaceType.CEILING_FLAT, room.id, room.id, null, null, false, "grid cells at the flat ceiling height", Measured(ceilingFlat, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("grid integration")), zero(), Measured(ceilingFlat, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("grid integration")), "horizontal ceiling finish area")
                if (ceilingSloped > 1e-6) {
                    surfaces += MeasuredSurfaceCandidate("${room.id}-ceiling-sloped", SurfaceType.CEILING_SLOPED, room.id, room.id, null, null, false, "grid cells under the roof slope, divided by cos(pitch)", Measured(ceilingSloped, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("grid integration / cos(pitch)")), zero(), Measured(ceilingSloped, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("grid integration / cos(pitch)")), "sloped ceiling (roof underside) finish area")
                }
                roomQuantities += RoomQuantities(
                    roomId = room.id,
                    floorArea = floorArea,
                    perimeter = room.perimeter,
                    wallFaceIds = faceIds,
                    wallGross = Measured(gross, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("sum of wall faces")),
                    wallOpenings = Measured(deductions, MeasureUnit.SQUARE_METER, deductionFidelity, assumption),
                    wallNet = Measured(gross - deductions, MeasureUnit.SQUARE_METER, deductionFidelity, Provenance.derived("gross − openings")),
                    ceilingFlat = Measured(ceilingFlat, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("grid integration")),
                    ceilingSloped = Measured(ceilingSloped, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("grid integration / cos(pitch)")),
                    ceilingTotal = Measured(ceilingFlat + ceilingSloped, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("flat + sloped")),
                    volume = Measured(grid.volume, MeasureUnit.CUBIC_METER, fidelityH, Provenance.derived("grid integration of height")),
                    usableAreaByHeightRule = Measured(grid.usable, MeasureUnit.SQUARE_METER, fidelityH, Provenance.derived("PN-ISO 9836 height rule on the grid: 100 % above 2.2 m, 50 % between 1.4 and 2.2 m")),
                    meanHeight = Measured(if (grid.area > 0) grid.volume / grid.area else 0.0, MeasureUnit.METER, fidelityH, Provenance.derived("volume / area")),
                    boundaryLengths = room.boundary.map { it.segment.length },
                )
            }
        }

        // Structural walls per floor, counted once each.
        val floorQuantities = candidate.floors.map { floor ->
            val floorY = floor.floorElevation.value ?: 0.0
            val clear = floor.clearHeight.value ?: 2.70
            val isTop = floor.id == topFloorId && roofField != null
            val slab = levels.upperSlabThickness.value ?: 0.0
            fun wallHeightAt(p: Pt): Double {
                if (!isTop) return clear + slab
                val roofY = roofField!!.heightAt(p) ?: return clear
                return max(0.0, roofY - floorY)
            }
            val walls = candidate.walls.filter { it.floorId == floor.id }
            fun structural(w: WallCandidate): Double = lineIntegral(w.centreline.a, w.centreline.b, ::wallHeightAt)
            val exteriorGross = walls.filter { it.wallClass == WallClass.EXTERIOR }.sumOf(::structural)
            val loadBearing = walls.filter { it.wallClass == WallClass.INTERNAL_LOAD_BEARING }.sumOf(::structural)
            val partitions = walls.filter { it.wallClass == WallClass.PARTITION }.sumOf(::structural)
            val openings = candidate.openings.filter { it.floorId == floor.id }
            val byType = openings.groupBy { it.type }.mapValues { (t, list) -> list.sumOf { (it.width.value ?: 0.0) * (it.height.value ?: assumedOpeningHeights[t] ?: 0.0) } }
            val exteriorOpenings = openings.filter { it.exterior }.sumOf { (it.width.value ?: 0.0) * (it.height.value ?: assumedOpeningHeights[it.type] ?: 0.0) }
            val envelopeGross = floor.footprint?.edges?.sumOf { e -> lineIntegral(e.a, e.b, ::wallHeightAt) } ?: 0.0
            val f = FactFidelity.weakest(listOf(floor.clearHeight.fidelity, FactFidelity.SOURCE_TRACED))
            FloorQuantities(
                floorId = floor.id,
                roomFloorAreaSum = Measured(floor.rooms.sumOf { it.plannedArea.value ?: 0.0 }, MeasureUnit.SQUARE_METER, f, Provenance.derived("sum of room polygons")),
                exteriorWallsStructural = Measured(exteriorGross, MeasureUnit.SQUARE_METER, f, Provenance.derived("exterior wall piece centreline length × storey height, once per piece; openings are gaps between pieces and are already absent")),
                loadBearingWallsStructural = Measured(loadBearing, MeasureUnit.SQUARE_METER, f, Provenance.derived("internal walls ≥ 0.20 m thick, once each")),
                partitionsStructural = Measured(partitions, MeasureUnit.SQUARE_METER, f, Provenance.derived("internal walls < 0.20 m thick, once each")),
                // Net comes off the *envelope*, never off the masonry: the masonry sum already
                // has every opening missing from it, and taking them off a second time reported
                // Project A's envelope at 73 m2 when the traced masonry alone was 123 m2.
                exteriorEnvelopeGross = Measured(envelopeGross, MeasureUnit.SQUARE_METER, f, Provenance.derived("storey footprint perimeter × storey height, measured over the openings")),
                exteriorEnvelopeNet = Measured(max(0.0, envelopeGross - exteriorOpenings), MeasureUnit.SQUARE_METER, FactFidelity.DISPLAY_ASSUMPTION, assumption),
                openingAreasByType = byType,
            )
        }

        // Roof.
        val roof = candidate.roof
        val roofFacets = roof?.facets?.map { it.id to it.areaM2 }.orEmpty() + roof?.secondaryMasses?.flatMap { m -> m.facets.map { it.id to it.areaM2 } }.orEmpty()
        val roofTotal = roof?.totalArea ?: Measured.missing(MeasureUnit.SQUARE_METER, "no roof candidate")
        val ridge = roof?.let { Measured(it.ridgeLines.sumOf { l -> l.length }, MeasureUnit.METER, it.fidelity, Provenance.derived("sum of skeleton ridge arcs")) }
            ?: Measured.missing(MeasureUnit.METER, "no roof candidate")
        val hip = roof?.let { Measured(it.hipLines.sumOf { l -> l.length }, MeasureUnit.METER, it.fidelity, Provenance.derived("sum of skeleton hip/valley arcs")) }
            ?: Measured.missing(MeasureUnit.METER, "no roof candidate")
        val eave = roof?.eaveLength ?: Measured.missing(MeasureUnit.METER, "no roof")

        // Joinery and facade.
        val exteriorOpenings = candidate.openings.filter { it.exterior }
        val joinery = exteriorOpenings.sumOf { (it.width.value ?: 0.0) * (it.height.value ?: assumedOpeningHeights[it.type] ?: 0.0) }
        // Two readings of the envelope, and the difference between them is the point.
        //
        // The masonry reading sums the traced exterior wall pieces. Every opening is already
        // missing from it, because an opening is a gap between pieces and not a piece, and so is
        // any stretch of envelope the raster left oblique or unresolved. Calling that "gross" was
        // wrong: on Project A it counts 24 m of wall around a 57 m perimeter.
        //
        // The envelope reading runs each storey's own traced footprint perimeter up that storey's
        // height and adds the gables. That is a facade as a facade is measured — over the
        // openings, which are then deducted — and it does not depend on every piece of the
        // envelope having been resolved into an axis-aligned rectangle.
        val wallMaterial = floorQuantities.sumOf { it.exteriorWallsStructural.value ?: 0.0 }
        // One source of truth for the envelope: the per-storey figures computed just above.
        val facadeGross = floorQuantities.sumOf { it.exteriorEnvelopeGross.value ?: 0.0 } + gableArea()
        val floorsAndStairs = roomQuantities.sumOf { it.floorArea.value ?: 0.0 }
        notes += "Facade gross = storey footprint perimeter × storey height + gable panels, measured over openings."
        notes += "Facade wall material = traced exterior wall pieces only; openings and unresolved envelope are absent from it, so it is the lower bracket."
        notes += "Exterior wall area on the top storey runs to the roof underside (knee wall plus slope); lower storeys floor-to-floor."

        return ProjectQuantities(
            surfaces = surfaces,
            rooms = roomQuantities,
            floors = floorQuantities,
            roofFacetAreas = roofFacets,
            roofTotal = roofTotal,
            ridgeLength = ridge,
            hipLength = hip,
            eaveLength = eave,
            exteriorJoinery = Measured(joinery, MeasureUnit.SQUARE_METER, FactFidelity.DISPLAY_ASSUMPTION, assumption, note = "${exteriorOpenings.size} exterior openings, widths traced, heights assumed"),
            facadeGross = Measured(facadeGross, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("storey footprint perimeters × storey heights + gables")),
            facadeNet = Measured(facadeGross - joinery, MeasureUnit.SQUARE_METER, FactFidelity.DISPLAY_ASSUMPTION, assumption),
            facadeWallMaterial = Measured(wallMaterial, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_TRACED, Provenance.derived("traced exterior wall pieces, openings and unresolved envelope excluded")),
            floorsAndStairsArea = Measured(floorsAndStairs, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_TRACED, Provenance.derived("sum of all room polygons on all floors")),
            notes = notes,
        )
    }

    /**
     * A room whose outline could not be proved: every quantity that needs the
     * ring is MISSING with the reason, and none of them is silently zero.
     */
    private fun unresolvedRoom(room: RoomCandidate, floorArea: Measured, why: String): RoomQuantities {
        fun gone(unit: MeasureUnit) = Measured.missing(unit, why)
        return RoomQuantities(
            roomId = room.id,
            floorArea = floorArea,
            perimeter = room.perimeter,
            wallFaceIds = emptyList(),
            wallGross = gone(MeasureUnit.SQUARE_METER),
            wallOpenings = gone(MeasureUnit.SQUARE_METER),
            wallNet = gone(MeasureUnit.SQUARE_METER),
            ceilingFlat = gone(MeasureUnit.SQUARE_METER),
            ceilingSloped = gone(MeasureUnit.SQUARE_METER),
            ceilingTotal = gone(MeasureUnit.SQUARE_METER),
            volume = gone(MeasureUnit.CUBIC_METER),
            usableAreaByHeightRule = gone(MeasureUnit.SQUARE_METER),
            meanHeight = gone(MeasureUnit.METER),
            boundaryLengths = emptyList(),
        )
    }

    /** Vertical area of the gable panels: along each gable edge, the roof rises above the eave. */
    private fun gableArea(): Double {
        val roof = candidate.roof ?: return 0.0
        val field = roofField ?: return 0.0
        val eaveY = roof.eaveElevation.value ?: return 0.0
        val ring = roof.outline.vertices
        return roof.gableEdgeIndices.sumOf { i ->
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            // Sample slightly inside the outline so the facet containing the point is found.
            val inward = insideOffset(roof.outline, a, b)
            lineIntegral(a, b) { p -> max(0.0, (field.heightAt(Pt(p.x + inward.x, p.z + inward.z)) ?: eaveY) - eaveY) }
        }
    }

    private fun insideOffset(polygon: com.buildplan.app.analyzer.candidate.Polygon, a: Pt, b: Pt): Pt {
        val mid = Pt((a.x + b.x) / 2, (a.z + b.z) / 2)
        val candidates = listOf(Pt(0.01, 0.0), Pt(-0.01, 0.0), Pt(0.0, 0.01), Pt(0.0, -0.01))
        return candidates.firstOrNull { polygon.contains(Pt(mid.x + it.x, mid.z + it.z)) } ?: Pt(0.0, 0.0)
    }

    private fun overlapsSegment(o: com.buildplan.app.analyzer.candidate.OpeningCandidate, wallId: String?, segment: com.buildplan.app.analyzer.candidate.Segment): Boolean {
        if (wallId == null) return false
        val wall = candidate.wall(wallId) ?: return false
        val d = o.distanceAlongWall.value ?: return true
        val w = o.width.value ?: return true
        // Opening span along the wall axis, projected onto the segment's axis.
        val horizontal = kotlin.math.abs(wall.centreline.a.z - wall.centreline.b.z) < 1e-9
        val start = if (horizontal) min(wall.centreline.a.x, wall.centreline.b.x) else min(wall.centreline.a.z, wall.centreline.b.z)
        val o0 = start + d
        val o1 = o0 + w
        val (s0, s1) = if (horizontal) min(segment.a.x, segment.b.x) to max(segment.a.x, segment.b.x) else min(segment.a.z, segment.b.z) to max(segment.a.z, segment.b.z)
        return min(o1, s1) - max(o0, s0) > -0.30
    }

    private class Integral(val area: Double, val volume: Double, val flatArea: Double, val slopedPlanArea: Double, val usable: Double)

    private fun integrate(room: RoomCandidate, height: (Pt) -> Double): Integral {
        val polygon = room.polygon ?: return Integral(0.0, 0.0, 0.0, 0.0, 0.0)
        val bounds = polygon.bounds
        val cell = gridM * gridM
        var area = 0.0
        var volume = 0.0
        var flat = 0.0
        var sloped = 0.0
        var usable = 0.0
        var x = bounds.minX + gridM / 2
        val top = candidate.floors.firstOrNull { it.id == room.floorId }
        val topClear = top?.clearHeight?.value ?: 2.7
        val ceilingRef = if (top?.id == topFloorId && roofField != null) (levels.atticFlatCeilingHeight.value ?: topClear) else topClear
        while (x < bounds.maxX) {
            var z = bounds.minZ + gridM / 2
            while (z < bounds.maxZ) {
                val p = Pt(x, z)
                if (polygon.contains(p)) {
                    val h = height(p)
                    area += cell
                    volume += cell * h
                    if (h >= ceilingRef - 1e-6) flat += cell else if (h > 1e-6) sloped += cell
                    usable += when {
                        h >= 2.2 -> cell
                        h >= 1.4 -> cell / 2
                        else -> 0.0
                    }
                }
                z += gridM
            }
            x += gridM
        }
        return Integral(area, volume, flat, sloped, usable)
    }

    private fun lineIntegral(a: Pt, b: Pt, f: (Pt) -> Double): Double {
        val length = a.distanceTo(b)
        if (length < 1e-9) return 0.0
        val steps = max(1, (length / gridM).toInt())
        var sum = 0.0
        for (i in 0 until steps) {
            val t = (i + 0.5) / steps
            sum += f(Pt(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t))
        }
        return sum * length / steps
    }

    private fun zero() = Measured(0.0, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("none"))
}
