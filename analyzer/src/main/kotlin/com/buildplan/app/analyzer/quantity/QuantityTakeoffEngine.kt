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
import kotlin.math.abs
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
    /** The envelope broken into the scopes a published facade figure might or might not include. */
    val facadeScope: FacadeScope,
    val notes: List<String>,
)

/**
 * The envelope, itemised by the choices a published facade figure silently
 * makes.
 *
 * A cost page prints one number under "facade" and never says whether it is
 * measured over the openings or net of them, whether an unheated garage is
 * inside the insulated envelope, or whether the plinth below the ground-floor
 * level is counted. Those are not small differences — on these houses they
 * span eighty square metres — so the analyzer states each of them separately
 * and lets the comparison say which combination, if any, the published number
 * could be. Naming the pieces is what turns "we are 38 % out" into "we do not
 * know what they measured", which is the truth.
 */
data class FacadeScope(
    /** Traced exterior masonry, openings absent by construction. */
    val exteriorStructuralWall: Measured,
    /** Storey footprint perimeter x storey height, measured over the openings. */
    val finishGross: Measured,
    /** [finishGross] less the exterior openings at their read or assumed heights. */
    val finishNet: Measured,
    val openingDeduction: Measured,
    val gableFace: Measured,
    /** The part of [finishGross] that belongs to rooms the site calls a garage. */
    val garageExterior: Measured,
    /** Walls of masses beside the main body (a garage or wing under its own roof). */
    val secondaryMassExterior: Measured,
    /** The band between terrain and the ground-floor level; unresolved while terrain is assumed. */
    val plinth: Measured,
) {
    /**
     * Every value the envelope takes under the scope choices the source leaves
     * open, smallest first.
     *
     * The subtractable parts are the ones a facade figure plausibly excludes
     * because they are not insulated: an unheated garage, and a mass under its
     * own lower roof. Both are measured, so removing them is a scope the
     * analyzer can name rather than a fudge to close a gap.
     */
    val scopes: List<Pair<String, Double>>
        get() {
            val gross = finishGross.value ?: return emptyList()
            val net = finishNet.value ?: gross
            val garage = garageExterior.value ?: 0.0
            val secondary = secondaryMassExterior.value ?: 0.0
            return buildList {
                add("over the openings" to gross)
                add("net of the openings" to net)
                if (garage > 0.1) {
                    add("over the openings, less the garage" to gross - garage)
                    add("net of the openings, less the garage" to net - garage)
                }
                if (secondary > 0.1) {
                    add("net of the openings, less a secondary mass" to net - secondary)
                    if (garage > 0.1) add("net of the openings, less the garage and a secondary mass" to net - garage - secondary)
                }
            }.sortedBy { it.second }
        }

    val scopeValues: List<Double> get() = scopes.map { it.second }

    /**
     * Which scope a published figure was measured under — and whether that can
     * be told at all.
     *
     * The scopes sit close together by construction: a garage is a fraction of
     * an envelope, not a multiple of it. So a tolerance wide enough to absorb
     * the trace's own error is often wide enough for two neighbouring scopes to
     * fit the same published number, and picking the nearer one would report a
     * definition the source never stated. [alternative] is that second scope
     * when it also fits, and its presence is the signal that the attribution is
     * undecided; the *numeric* agreement in [relative] is unaffected, because
     * that part was actually measured.
     */
    data class ScopeAttribution(
        val label: String,
        val value: Double,
        val relative: Double,
        val alternative: String?,
        val alternativeRelative: Double?,
    ) {
        val isDecided: Boolean get() = alternative == null
    }

    fun attribute(sourceValue: Double, tolerance: Double): ScopeAttribution? {
        if (sourceValue <= 0.0) return null
        val ranked = scopes.sortedBy { abs(it.second - sourceValue) }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        val runnerUpRel = runnerUp?.let { abs(it.second - sourceValue) / sourceValue }
        val ambiguous = runnerUpRel != null && runnerUpRel <= tolerance
        return ScopeAttribution(
            label = best.first,
            value = best.second,
            relative = abs(best.second - sourceValue) / sourceValue,
            alternative = if (ambiguous) runnerUp.first else null,
            alternativeRelative = if (ambiguous) runnerUpRel else null,
        )
    }

    /** Lowest and highest the envelope could be, over every scope the source leaves open. */
    val plausibleRange: ClosedFloatingPointRange<Double>
        get() = scopeValues.let { (it.firstOrNull() ?: 0.0)..(it.lastOrNull() ?: 0.0) }
}

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
    private val assumedOpeningHeights: Map<OpeningType, Double> = DEFAULT_ASSUMED_OPENING_HEIGHTS,
) {

    companion object {
        /**
         * The heights used for a deduction when the source prints none. Stated
         * once, here, so the verification stage can show a person exactly the
         * number it is asking them to confirm or replace.
         */
        val DEFAULT_ASSUMED_OPENING_HEIGHTS: Map<OpeningType, Double> = mapOf(
            OpeningType.DOOR to 2.05,
            OpeningType.WINDOW to 1.50,
            OpeningType.GARAGE_GATE to 2.20,
            OpeningType.PASSAGE to 2.05,
            OpeningType.ROOFLIGHT to 1.18,
            OpeningType.UNKNOWN to 1.50,
        )
    }

    private val levels = candidate.levels
    private val topFloorId = candidate.floors.maxByOrNull { it.order }?.id
    private val pitch = candidate.roof?.pitchDegrees?.value ?: 0.0
    private val cosPitch = cos(Math.toRadians(pitch))

    fun compute(): ProjectQuantities {
        candidate.resolvedGeometry?.takeIf { it.lineage.startsWith("final-resolution:") }?.let {
            val geometry=if(it.lineage==com.buildplan.app.analyzer.reconstruction.GeometryResolver.lineage(candidate)) it else com.buildplan.app.analyzer.reconstruction.GeometryResolver.resolve(candidate)
            return ResolvedQuantityTakeoff.compute(candidate,geometry)
        }
        val surfaces = mutableListOf<MeasuredSurfaceCandidate>()
        val roomQuantities = mutableListOf<RoomQuantities>()
        val notes = mutableListOf<String>()
        val assumption = Provenance.assumption("opening heights assumed per type (door ${assumedOpeningHeights[OpeningType.DOOR]} m, window ${assumedOpeningHeights[OpeningType.WINDOW]} m, gate ${assumedOpeningHeights[OpeningType.GARAGE_GATE]} m) because the plan prints them as text this stage does not read")
        notes += assumption.method
        // Whether the exterior openings' heights are known decides how far every figure that
        // deducts them may be trusted. Once each height is read or confirmed the deduction is a
        // derivation like any other; while one is absent the figure rests on the assumption
        // above and says so. Recomputed rather than fixed, because verification may supply them.
        val exteriorOpenings = candidate.openings.filter { it.exterior }
        val exteriorHeightFidelity = heightFidelity(exteriorOpenings)
        val exteriorHeightProvenance = if (exteriorOpenings.all { it.height.value != null }) {
            Provenance.derived("exterior opening widths × heights", exteriorOpenings.map { it.height.provenance.method }.distinct().take(3))
        } else assumption

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
                // A room with an outline and no boundary segments — a region a person assigned to
                // a published row — has a floor and a ceiling but no wall faces the analyzer can
                // name, and a zero here would look like a measurement of nothing.
                if (room.boundary.isEmpty()) {
                    val why = "room has no wall-face segments: ${room.geometryNote}"
                    val grid = integrate(room, ::heightAt)
                    roomQuantities += unresolvedRoom(room, floorArea, why).copy(
                        ceilingFlat = Measured(grid.flatArea, MeasureUnit.SQUARE_METER, fidelityOf(room, isTop, floor), Provenance.derived("grid integration")),
                        volume = Measured(grid.volume, MeasureUnit.CUBIC_METER, fidelityOf(room, isTop, floor), Provenance.derived("grid integration of height")),
                    )
                    surfaces += MeasuredSurfaceCandidate("${room.id}-floor", SurfaceType.FLOOR, room.id, room.id, null, null, false, "room polygon", floorArea, zero(), floorArea, "floor finish area of the room polygon")
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
                        deductionFidelity = if (o.height.value == null) {
                            FactFidelity.DISPLAY_ASSUMPTION
                        } else {
                            FactFidelity.weakest(listOf(deductionFidelity, o.height.fidelity, FactFidelity.SOURCE_DERIVED))
                        }
                    }
                    val faceHeightsKnown = openings.all { it.height.value != null }
                    val deductionProvenance = when {
                        openings.isEmpty() -> Provenance.derived("no openings on this face")
                        faceHeightsKnown -> Provenance.derived("opening widths × heights", openings.map { it.height.provenance.method }.distinct().take(3))
                        else -> assumption
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
                        deductions = Measured(faceDeduction, MeasureUnit.SQUARE_METER, if (openings.isEmpty()) fidelityH else deductionFidelity, deductionProvenance),
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
                    wallOpenings = Measured(deductions, MeasureUnit.SQUARE_METER, deductionFidelity, if (deductionFidelity == FactFidelity.DISPLAY_ASSUMPTION) assumption else Provenance.derived("sum of face deductions")),
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
            val floorExterior = openings.filter { it.exterior }
            val exteriorOpenings = floorExterior.sumOf { (it.width.value ?: 0.0) * (it.height.value ?: assumedOpeningHeights[it.type] ?: 0.0) }
            val envelopeGross = floor.footprint?.edges?.sumOf { e -> lineIntegral(e.a, e.b, ::wallHeightAt) } ?: 0.0
            val f = FactFidelity.weakest(listOf(floor.clearHeight.fidelity, FactFidelity.SOURCE_TRACED))
            val floorHeightFidelity = heightFidelity(floorExterior)
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
                exteriorEnvelopeNet = Measured(
                    max(0.0, envelopeGross - exteriorOpenings), MeasureUnit.SQUARE_METER,
                    FactFidelity.weakest(listOf(f, floorHeightFidelity)),
                    if (floorHeightFidelity == FactFidelity.DISPLAY_ASSUMPTION) assumption else Provenance.derived("envelope gross − exterior openings at their heights"),
                ),
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
        val joinery = exteriorOpenings.sumOf { (it.width.value ?: 0.0) * (it.height.value ?: assumedOpeningHeights[it.type] ?: 0.0) }
        val heightsKnown = exteriorOpenings.count { it.height.value != null }
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
            exteriorJoinery = Measured(
                joinery, MeasureUnit.SQUARE_METER, exteriorHeightFidelity, exteriorHeightProvenance,
                note = "${exteriorOpenings.size} exterior openings, widths traced, $heightsKnown heights read or confirmed, ${exteriorOpenings.size - heightsKnown} assumed",
            ),
            facadeGross = Measured(facadeGross, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("storey footprint perimeters × storey heights + gables")),
            facadeNet = Measured(facadeGross - joinery, MeasureUnit.SQUARE_METER, exteriorHeightFidelity, if (exteriorHeightFidelity == FactFidelity.DISPLAY_ASSUMPTION) assumption else Provenance.derived("facade gross − exterior joinery")),
            facadeWallMaterial = Measured(wallMaterial, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_TRACED, Provenance.derived("traced exterior wall pieces, openings and unresolved envelope excluded")),
            facadeScope = FacadeScope(
                exteriorStructuralWall = Measured(wallMaterial, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_TRACED, Provenance.derived("traced exterior wall pieces")),
                finishGross = Measured(facadeGross, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("storey footprint perimeters × storey heights + gables")),
                finishNet = Measured(facadeGross - joinery, MeasureUnit.SQUARE_METER, exteriorHeightFidelity, if (exteriorHeightFidelity == FactFidelity.DISPLAY_ASSUMPTION) assumption else Provenance.derived("facade gross − exterior joinery")),
                openingDeduction = Measured(joinery, MeasureUnit.SQUARE_METER, exteriorHeightFidelity, exteriorHeightProvenance),
                gableFace = Measured(gableArea(), MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("roof height field above the eave along the gable edges")),
                garageExterior = Measured(
                    garageExteriorArea(surfaces), MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED,
                    Provenance.derived("outward-facing wall faces of rooms the site names a garage"),
                ),
                secondaryMassExterior = Measured(
                    secondaryMassExteriorArea(), MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED,
                    Provenance.derived("perimeter of each secondary roof mass × its height above the storey floor"),
                ),
                plinth = Measured.missing(
                    MeasureUnit.SQUARE_METER,
                    "the plinth runs from terrain to the ground-floor level, and terrain is an assumption while the section's levels stay unread",
                ),
            ),
            floorsAndStairsArea = Measured(floorsAndStairs, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_TRACED, Provenance.derived("sum of all room polygons on all floors")),
            notes = notes,
        )
    }

    /**
     * How far a deduction of these openings may be trusted: the weakest of
     * their heights, with an absent height counting as the assumption it is
     * replaced by. Openings with no height at all — none in the list — deduct
     * nothing and rest on nothing.
     */
    private fun heightFidelity(openings: List<com.buildplan.app.analyzer.candidate.OpeningCandidate>): FactFidelity {
        if (openings.isEmpty()) return FactFidelity.SOURCE_DERIVED
        return FactFidelity.weakest(
            openings.map { if (it.height.value == null) FactFidelity.DISPLAY_ASSUMPTION else it.height.fidelity } + FactFidelity.SOURCE_DERIVED,
        )
    }

    private fun fidelityOf(room: RoomCandidate, isTop: Boolean, floor: com.buildplan.app.analyzer.candidate.FloorCandidate): FactFidelity =
        if (isTop) FactFidelity.weakest(listOf(room.matchConfidence, levels.atticFlatCeilingHeight.fidelity)) else FactFidelity.weakest(listOf(room.matchConfidence, floor.clearHeight.fidelity))

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

    /**
     * The envelope belonging to rooms the site names a garage.
     *
     * A garage is usually outside the insulated envelope and usually inside the
     * built area, so whether a published facade figure counts it is exactly the
     * kind of scope decision a cost page does not print. Measured from the
     * outward-facing wall faces already computed per room, so it is the same
     * geometry the rest of the takeoff uses rather than a second estimate.
     */
    private fun garageExteriorArea(surfaces: List<MeasuredSurfaceCandidate>): Double {
        val garageRooms = candidate.rooms.filter { it.kind == com.buildplan.app.analyzer.site.RoomKind.GARAGE }.map { it.id }.toSet()
        if (garageRooms.isEmpty()) return 0.0
        return surfaces
            .filter { it.type == SurfaceType.WALL_FACE && it.facesOutside && it.roomId in garageRooms }
            .sumOf { it.grossArea.value ?: 0.0 }
    }

    /** Walls of masses beside the main body: each secondary roof's perimeter up to its own top. */
    private fun secondaryMassExteriorArea(): Double {
        val roof = candidate.roof ?: return 0.0
        val groundLevel = candidate.floors.minByOrNull { it.order }?.floorElevation?.value ?: 0.0
        return roof.secondaryMasses.sumOf { mass ->
            val top = mass.topElevation.value ?: return@sumOf 0.0
            mass.outline.perimeter * max(0.0, top - groundLevel)
        }
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
