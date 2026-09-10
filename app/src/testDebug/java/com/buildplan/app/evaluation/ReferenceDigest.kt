package com.buildplan.app.evaluation

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import com.buildplan.app.reference.visual.MarcowkiRoomTrace
import com.buildplan.app.reference.visual.MarcowkiVisualModelV1
import com.buildplan.app.reference.visual.TraceCertainty
import kotlin.math.abs

/**
 * The hand-built reference model reduced to the same digest, so a candidate
 * can be held against it measurement by measurement.
 *
 * **The fidelity column is the contract.** Every value here carries the
 * fidelity the trace's own ledger gives it: the page's stated width, height,
 * pitch, roof area and knee wall and the section's levels are
 * [FactFidelity.SOURCE_EXACT]; wall positions measured off the rasters are
 * [FactFidelity.SOURCE_TRACED]; anything the trace assumed to close a shape is
 * [FactFidelity.DISPLAY_ASSUMPTION] and the comparator refuses to score it.
 * That refusal is what stops this file from turning one careful person's guess
 * into a benchmark the analyzer would then be tuned against.
 *
 * Test-source only. Nothing here is reachable from `src/main`, from the
 * analyzer module or from a release build.
 */
object ReferenceDigest {

    const val LABEL = "hand-built reference"

    fun marcowki(): ModelDigest {
        val geometry = MarcowkiVisualModelV1.geometry
        val walls = geometry.primitives.filterIsInstance<WallGeometry>()
        val facets = geometry.primitives.filterIsInstance<RoofFacetGeometry>()
        val roofFacets = geometry.primitivesFor(MarcowkiVisualModelV1.roofId).filterIsInstance<RoofFacetGeometry>()
        val panes = MarcowkiVisualModelV1.facadeOpeningIds
            .flatMap { geometry.primitivesFor(it) }
            .filterIsInstance<OpeningPanelGeometry>()

        fun exact(v: Double, note: String = "") = DigestValue(v, FactFidelity.SOURCE_EXACT, note)
        fun traced(v: Double, note: String = "") = DigestValue(v, FactFidelity.SOURCE_TRACED, note)
        fun assumed(v: Double?, note: String = "") = DigestValue(v, FactFidelity.DISPLAY_ASSUMPTION, note)

        // The eaves wall of the main house runs the full depth on both sides; the garage adds
        // its own three faces. Measured off the traced walls rather than restated.
        val groundExterior = walls
            .filter { abs(it.baseElevation - Grid.GROUND_FLOOR_Y) < 0.4 && it.thickness >= Grid.EXTERIOR_WALL_THICKNESS - 0.02 }
            .sumOf { it.length }

        val values = mapOf(
            "plan.extentX" to exact(Grid.BUILDING_WIDTH, "printed 1205 anchor across both outer wall faces"),
            "plan.extentZ" to exact(Grid.BUILDING_DEPTH, "printed 1260 anchor"),
            "plan.footprintArea" to exact(131.16, "stated on the page"),
            "level.terrain" to exact(Grid.TERRAIN_Y, "levelled on the section"),
            "level.groundFloor" to exact(Grid.GROUND_FLOOR_Y, "the section's datum"),
            "level.upperFloor" to exact(Grid.UPPER_FLOOR_Y, "levelled +3.06 on the section"),
            "level.groundClearHeight" to exact(Grid.GROUND_CLEAR_HEIGHT, "dimensioned 272 on the section"),
            "level.kneeWall" to exact(Grid.KNEE_WALL_HEIGHT, "stated on the page, dimensioned 130 on the section"),
            "level.eave" to DigestValue(Grid.EAVES_Y, FactFidelity.SOURCE_DERIVED, "ridge − half the house width × tan(pitch); every input stated"),
            "level.ridge" to exact(Grid.RIDGE_Y, "levelled +7.95 on the section"),
            "height.total" to exact(Grid.BUILDING_HEIGHT, "stated on the page; equals +7.95 over −0.32"),
            "level.atticClearHeight" to exact(Grid.ATTIC_CLEAR_HEIGHT, "dimensioned 266 on the section"),
            "roof.pitchDeg" to exact(Grid.ROOF_PITCH_DEGREES, "stated on the page and annotated on the section"),
            "roof.area" to DigestValue(roofFacets.sumOf { it.area }, FactFidelity.SOURCE_DERIVED, "traced facets; reconciles with the stated 150.57 m2"),
            "roof.facetCount" to DigestValue(roofFacets.size.toDouble(), FactFidelity.SOURCE_DERIVED, "a gable: two slopes"),
            "roof.ridgeLength" to DigestValue(Grid.BUILDING_DEPTH + 2 * Grid.GABLE_OVERHANG, FactFidelity.SOURCE_TRACED, "depth plus the traced gable overhang at both ends"),
            "roof.outlineExtentX" to traced(Grid.HOUSE_WIDTH, "the roof covers the house, not the garage"),
            "roof.outlineExtentZ" to traced(Grid.BUILDING_DEPTH + 2 * Grid.GABLE_OVERHANG, "traced 1.00 m gable overhang at both ends"),
            "walls.exteriorLengthGround" to traced(groundExterior, "sum of traced 0.44 m wall pieces at ground level"),
            "openings.exteriorCount" to exact(MarcowkiVisualModelV1.facadeOpeningIds.size.toDouble(), "the joinery schedule printed on both plans"),
            "openings.garageGateCount" to exact(1.0, "one gate in the schedule"),
            "rooms.count" to exact(MarcowkiRoomTrace.all.size.toDouble(), "eighteen rows in the published tables"),
            "rooms.planAreaSum" to traced(MarcowkiRoomTrace.all.sumOf { it.planAreaM2 }, "traced zones; the source states usable area, which is smaller under the slopes"),
            "stairs.count" to exact(1.0, "one flight, drawn on both plans"),
            "mass.secondaryArea" to traced(Grid.BUILDING_WIDTH.minus(Grid.HOUSE_WIDTH) * (Grid.BUILDING_DEPTH - Grid.GARAGE_NORTH_FACE), "the garage rectangle"),
            "mass.secondaryTop" to exact(Grid.UPPER_FLOOR_Y, "the garage roof and the storey band meet at the stated +3.06"),
            "floors.count" to exact(2.0, "two published storey tables"),
        )

        val roomsById = MarcowkiVisualModelV1.building.floors.flatMap { it.rooms }.associateBy { it.id }
        val traces = MarcowkiRoomTrace.all.filter { it.certainty != TraceCertainty.TRACE_UNCERTAIN }
        return ModelDigest(
            label = LABEL,
            values = values,
            openingsByFacade = panes.mapNotNull { facadeOf(it) }.groupingBy { it }.eachCount(),
            roomAreas = traces.mapNotNull { t -> roomsById[t.roomId]?.name?.let { it to t.planAreaM2 } }.toMap(),
            roomCentroids = traces.mapNotNull { t -> roomsById[t.roomId]?.name?.let { it to centroid(t.outline) } }.toMap(),
            roofFamily = "GABLE",
        )
    }

    /**
     * Which facade a pane sits in: the outer wall face it is nearest to, in
     * the plan's own frame (X east, Z south, so north is the top of the plan).
     * A pane more than a wall's thickness from every outer face — a rooflight,
     * an internal screen — belongs to none and is not counted.
     */
    private fun facadeOf(pane: OpeningPanelGeometry): String? {
        val b = pane.bounds
        val x = (b.min.x + b.max.x) / 2
        val z = (b.min.z + b.max.z) / 2
        val tolerance = Grid.EXTERIOR_WALL_THICKNESS
        val candidates = listOf(
            "WEST" to abs(x - 0.0),
            "EAST" to abs(x - Grid.BUILDING_WIDTH),
            "NORTH" to abs(z - 0.0),
            "SOUTH" to abs(z - Grid.BUILDING_DEPTH),
        )
        // The house's own east wall is an outer face too, where the garage does not reach.
        val houseEast = if (z < Grid.GARAGE_NORTH_FACE) abs(x - Grid.HOUSE_WIDTH) else Double.MAX_VALUE
        val best = (candidates + ("EAST" to houseEast)).minBy { it.second }
        return if (best.second <= tolerance) best.first else null
    }

    private fun centroid(outline: List<com.buildplan.app.geometry.PlanPoint>): Pair<Double, Double> {
        var area = 0.0
        var cx = 0.0
        var cz = 0.0
        for (i in outline.indices) {
            val p = outline[i]
            val q = outline[(i + 1) % outline.size]
            val cross = p.x * q.z - q.x * p.z
            area += cross
            cx += (p.x + q.x) * cross
            cz += (p.z + q.z) * cross
        }
        area /= 2.0
        if (abs(area) < 1e-9) return outline.map { it.x }.average() to outline.map { it.z }.average()
        return cx / (6 * area) to cz / (6 * area)
    }
}
