package com.buildplan.app.analyzer.candidate

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.Measured

/**
 * The analyzer's proposal for a building: floors, rooms, walls, openings,
 * stairs, roof and levels, every one of them a *candidate* with fidelity and
 * provenance.
 *
 * This is never the canonical `Building`. Nothing here is written into the
 * domain; promotion is a later, user-verified stage. Identifiers are stable
 * strings minted from the analysis (floor index, region order, wall order)
 * so that two runs over the same source name the same things.
 */
data class ProjectAnalysisCandidate(
    val floors: List<FloorCandidate>,
    val walls: List<WallCandidate>,
    val openings: List<OpeningCandidate>,
    val stairs: List<StairCandidate>,
    val roof: RoofCandidate?,
    val levels: LevelsCandidate,
    val dimensions: List<NamedDimension>,
    /** Everything the analysis could not settle, in the order it was found. */
    val issues: List<AnalysisIssue>,
) {
    fun floor(id: String): FloorCandidate? = floors.firstOrNull { it.id == id }
    fun room(id: String): RoomCandidate? = floors.asSequence().flatMap { it.rooms.asSequence() }.firstOrNull { it.id == id }
    fun wall(id: String): WallCandidate? = walls.firstOrNull { it.id == id }
    val rooms: List<RoomCandidate> get() = floors.flatMap { it.rooms }
}

/** One storey, with the frame its plan was analysed in. */
data class FloorCandidate(
    val id: String,
    val name: String,
    /** Semantic order from the lowest storey. */
    val order: Int,
    val calibration: PlanCalibration?,
    /** The outer footprint at this storey, as traced from its plan, in metres. */
    val footprint: Polygon?,
    /** The outer roof outline at this storey when the plan draws one (an attic plan with eaves), else null. */
    val roofOutline: Polygon?,
    val floorElevation: Measured,
    val clearHeight: Measured,
    val rooms: List<RoomCandidate>,
    /** Regions the plan produced that matched no published room, kept as evidence. */
    val unmatchedRegions: List<RegionCandidate>,
    val planAssetUrl: String?,
)

/**
 * How a plan raster was turned into metres: pixels per metre, the raster
 * origin of the model frame, the evidence and the residual.
 */
data class PlanCalibration(
    val pixelsPerMeter: Measured,
    /** Raster pixel that maps to model (0, 0). */
    val originPx: Pt,
    val method: String,
    val anchors: List<CalibrationAnchor>,
    /** Relative residual between independent checks (0.01 = 1 %), or null when only one anchor exists. */
    val residual: Double?,
    val confidence: FactFidelity,
) {
    fun toMeters(px: Pt): Pt = Pt((px.x - originPx.x) / pixelsPerMeter.requireValue(), (px.z - originPx.z) / pixelsPerMeter.requireValue())
    fun toPixels(m: Pt): Pt = Pt(originPx.x + m.x * pixelsPerMeter.requireValue(), originPx.z + m.z * pixelsPerMeter.requireValue())
}

data class CalibrationAnchor(
    val kind: String,
    val sourceValue: Measured,
    val measuredPixels: Double,
    val impliedPixelsPerMeter: Double,
)

/** A closed region the plan produced, before or without a room label. */
data class RegionCandidate(
    val id: String,
    val polygon: Polygon,
    val areaM2: Double,
    /** Ids of the wall candidates bounding the region. */
    val boundaryWallIds: List<String>,
)

data class RoomCandidate(
    val id: String,
    val floorId: String,
    val name: String,
    /** The printed ordinal in the source table, when it had one. */
    val sourceOrdinal: Int?,
    val polygon: Polygon,
    val perimeter: Measured,
    val plannedArea: Measured,
    /** The published usable area, as the site defines it (attic rooms exclude low strips). */
    val sourceUsableArea: Measured,
    /** The published floor area when the site prints one, else MISSING. */
    val sourceFloorArea: Measured,
    val boundary: List<RoomBoundarySegment>,
    val matchConfidence: FactFidelity,
    val matchNote: String,
)

/** One straight run of a room's boundary: which wall it lies on and what is on the other side. */
data class RoomBoundarySegment(
    val segment: Segment,
    val wallId: String?,
    val neighbourRoomId: String?,
    val faceOutside: Boolean,
)

/**
 * Structural class of a traced wall piece. [LIGHT_EXTERIOR] is a thin line on the outer
 * boundary (parapet, terrace edge, glazing frame) and [PIER] a thick stub shorter than twice
 * its thickness (chimney, pier, duct block); neither is summed as a wall of the envelope or
 * of the load-bearing set.
 */
enum class WallClass { EXTERIOR, INTERNAL_LOAD_BEARING, PARTITION, LIGHT_EXTERIOR, PIER, UNKNOWN }

data class WallCandidate(
    val id: String,
    val floorId: String,
    val centreline: Segment,
    val length: Measured,
    val thickness: Measured,
    val wallClass: WallClass,
    val baseElevation: Measured,
    val height: Measured,
    /** Room ids on each side; empty when the side is outside or unresolved. */
    val roomIdsLeft: List<String>,
    val roomIdsRight: List<String>,
    val touchesOutside: Boolean,
    val openingIds: List<String>,
    val fidelity: FactFidelity,
)

enum class OpeningType { DOOR, WINDOW, GARAGE_GATE, PASSAGE, ROOFLIGHT, UNKNOWN }

data class OpeningCandidate(
    val id: String,
    val wallId: String,
    val floorId: String,
    val type: OpeningType,
    /** Distance along the wall centreline from its start to the opening's near edge. */
    val distanceAlongWall: Measured,
    val width: Measured,
    val height: Measured,
    val sillHeight: Measured,
    val linkedRoomIds: List<String>,
    val exterior: Boolean,
    val typeFidelity: FactFidelity,
)

data class StairCandidate(
    val id: String,
    val floorId: String,
    val zone: Box,
    val treadCount: Measured,
    val direction: String,
    val fidelity: FactFidelity,
    val note: String,
)

enum class RoofFamily { GABLE, HIP, FLAT, MIXED, UNKNOWN }

/** One planar roof facet with its 3D outline (X east, Y up, Z south). */
data class RoofFacetCandidate(
    val id: String,
    val vertices: List<Pt3>,
    val areaM2: Double,
    /** The eave edge the facet rises from. */
    val eaveEdge: Segment,
)

data class Pt3(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Pt3 must be finite" }
    }
}

data class RoofCandidate(
    val family: RoofFamily,
    val pitchDegrees: Measured,
    val outline: Polygon,
    val eaveElevation: Measured,
    val ridgeElevation: Measured,
    val facets: List<RoofFacetCandidate>,
    val totalArea: Measured,
    val ridgeLines: List<Segment3>,
    val hipLines: List<Segment3>,
    val eaveLength: Measured,
    /** Which outline edges were treated as gable ends (vertical), by edge index. */
    val gableEdgeIndices: List<Int>,
    val fidelity: FactFidelity,
    val note: String,
    /** Roof masses beside the main roof (a flat garage roof), each its own facet list. */
    val secondaryMasses: List<SecondaryRoofMass>,
)

data class SecondaryRoofMass(
    val id: String,
    val outline: Polygon,
    val family: RoofFamily,
    val facets: List<RoofFacetCandidate>,
    val topElevation: Measured,
    val fidelity: FactFidelity,
    val note: String,
)

data class Segment3(val a: Pt3, val b: Pt3) {
    val length: Double get() = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
}

/** The vertical skeleton: every level the analysis settled or assumed. */
data class LevelsCandidate(
    val terrain: Measured,
    val groundFloor: Measured,
    val upperFloor: Measured,
    val groundClearHeight: Measured,
    val upperClearHeight: Measured,
    val upperSlabThickness: Measured,
    val kneeWall: Measured,
    val eave: Measured,
    val ridge: Measured,
    val buildingHeight: Measured,
    /** The flat ceiling level of the attic above its floor, when the roof allows one. */
    val atticFlatCeilingHeight: Measured,
    val notes: List<String>,
)

/** A named dimension at project or floor scope, for the "save all dimensions" ledger. */
data class NamedDimension(
    val scope: String,
    val name: String,
    val measured: Measured,
)

enum class IssueSeverity { INFO, WARNING, BLOCKING }

data class AnalysisIssue(
    val severity: IssueSeverity,
    val stage: String,
    val subject: String?,
    val message: String,
)
