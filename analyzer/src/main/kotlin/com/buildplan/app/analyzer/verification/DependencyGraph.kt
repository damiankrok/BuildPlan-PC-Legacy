package com.buildplan.app.analyzer.verification

import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.service.ProjectAnalysisReport

/**
 * Which quantity rows hang off which root fact.
 *
 * The verification screen asks about roots — a room's identity, a family of
 * opening heights, a storey level — and this graph is what turns one answer
 * into the list of rows it settles. It is derived from the candidate's own
 * structure (which openings sit on which room's faces, which storey is the
 * top one) and from the ledger of quantity keys the report actually carries,
 * so the counts it reports are counts of real rows and never estimates.
 *
 * Root keys are stable strings:
 *
 * - `room:<id>:identity` — which published row a region is;
 * - `room:<id>:boundary` — an open-plan split assumed proportionally;
 * - `opening:<id>:height` — one opening's height;
 * - `level:terrain`, `level:upperFloor`, `level:slab`, `level:atticCeiling`;
 * - `roof:ridge` — the ridge direction;
 * - `roof:mass:<id>` — a secondary mass's roof;
 * - `facade:scope` — which envelope scope the published figure priced.
 */
class DependencyGraph private constructor(
    private val dependents: Map<String, List<String>>,
) {
    /** Quantity keys that change when [rootKey] is settled. Empty for an unknown root. */
    fun dependentsOf(rootKey: String): List<String> = dependents[rootKey].orEmpty()

    fun dependentsOf(rootKeys: Collection<String>): List<String> =
        rootKeys.flatMap { dependentsOf(it) }.distinct()

    /** Root keys [quantityKey] depends on. */
    fun rootsOf(quantityKey: String): List<String> =
        dependents.filterValues { quantityKey in it }.keys.sorted()

    val rootKeys: Set<String> get() = dependents.keys

    companion object {

        fun of(report: ProjectAnalysisReport): DependencyGraph {
            val candidate = report.candidate ?: return DependencyGraph(emptyMap())
            val keys = report.quantityVerification.map { it.key }.toSet()
            return DependencyGraph(build(candidate, keys))
        }

        /**
         * Builds the graph over [keys], the quantity keys that exist. Exposed
         * for the engine, which needs the same graph over a recomputed ledger.
         */
        fun build(candidate: ProjectAnalysisCandidate, keys: Set<String>): Map<String, List<String>> {
            val out = LinkedHashMap<String, MutableList<String>>()
            fun add(root: String, vararg quantityKeys: String) {
                val list = out.getOrPut(root) { mutableListOf() }
                quantityKeys.filter { it in keys && it !in list }.forEach { list += it }
            }
            fun addAll(root: String, quantityKeys: Collection<String>) = add(root, *quantityKeys.toTypedArray())

            val topFloorId = candidate.floors.maxByOrNull { it.order }?.id
            val groundFloorId = candidate.floors.minByOrNull { it.order }?.id
            fun roomKeys(roomId: String, vararg names: String): List<String> = names.map { "room:$roomId:$it" }
            val allRoomNames = listOf("floorArea", "perimeter", "wallFaceGross", "wallFaceOpenings", "wallFaceNet", "ceilingFlat", "ceilingSloped", "ceilingTotal", "volume", "usableAreaByHeightRule")
            val heightNames = listOf("wallFaceGross", "wallFaceOpenings", "wallFaceNet", "ceilingFlat", "ceilingSloped", "ceilingTotal", "volume", "usableAreaByHeightRule")
            val floorHeightNames = listOf("exteriorWallsStructural", "loadBearingWallsStructural", "partitionsStructural", "exteriorEnvelopeGross", "exteriorEnvelopeNet")
            val projectHeightKeys = listOf("project:facadeGross", "project:facadeNet", "project:exteriorWallMaterial", "project:facadeInsulation", "project:loadBearingWalls", "project:exteriorEnvelopeGross")

            // Room identity: every row of the room.
            candidate.rooms.forEach { room ->
                addAll("room:${room.id}:identity", roomKeys(room.id, *allRoomNames.toTypedArray()))
                addAll("room:${room.id}:boundary", roomKeys(room.id, *allRoomNames.toTypedArray()))
            }

            // Opening heights: the opening's own row, the faces it sits on, and every envelope figure.
            val roomsByFace: Map<String, List<String>> = candidate.rooms
                .flatMap { room -> room.boundary.mapNotNull { seg -> seg.wallId?.let { it to room.id } } }
                .groupBy({ it.first }, { it.second })
            candidate.openings.forEach { o ->
                val root = "opening:${o.id}:height"
                add(root, "opening:${o.id}:height")
                val rooms = (o.linkedRoomIds + roomsByFace[o.wallId].orEmpty()).distinct()
                rooms.forEach { r -> addAll(root, roomKeys(r, "wallFaceOpenings", "wallFaceNet")) }
                if (o.exterior) {
                    add(root, "floor:${o.floorId}:exteriorEnvelopeNet", "project:exteriorJoinery", "project:facadeNet", "project:facadeInsulation")
                }
            }

            // Levels.
            val groundRooms = candidate.rooms.filter { it.floorId == groundFloorId }
            val topRooms = candidate.rooms.filter { it.floorId == topFloorId }
            val groundFloorKeys = groundFloorId?.let { f -> floorHeightNames.map { "floor:$f:$it" } }.orEmpty()
            val topFloorKeys = topFloorId?.let { f -> floorHeightNames.map { "floor:$f:$it" } }.orEmpty()
            add("level:terrain", "project:terrainLevel", "project:ridgeLevel", "project:eaveLevel", "project:groundClearHeight")
            groundRooms.forEach { r -> addAll("level:terrain", roomKeys(r.id, *heightNames.toTypedArray())) }
            addAll("level:terrain", groundFloorKeys + projectHeightKeys)

            add("level:upperFloor", "project:groundClearHeight", "project:upperClearHeight")
            groundRooms.forEach { r -> addAll("level:upperFloor", roomKeys(r.id, *heightNames.toTypedArray())) }
            addAll("level:upperFloor", groundFloorKeys + projectHeightKeys)

            add("level:slab", "project:groundClearHeight")
            groundRooms.forEach { r -> addAll("level:slab", roomKeys(r.id, *heightNames.toTypedArray())) }
            addAll("level:slab", groundFloorKeys + projectHeightKeys)

            topRooms.forEach { r -> addAll("level:atticCeiling", roomKeys(r.id, "ceilingFlat", "ceilingSloped", "ceilingTotal", "volume", "usableAreaByHeightRule", "wallFaceGross", "wallFaceOpenings", "wallFaceNet")) }

            // Roof.
            add("roof:ridge", "project:roofArea", "project:ridgeLength", "project:hipLength", "project:eaveLength", "project:facadeGross", "project:facadeNet", "project:facadeInsulation", "project:eaveLevel", "project:ridgeLevel")
            topRooms.forEach { r -> addAll("roof:ridge", roomKeys(r.id, *heightNames.toTypedArray())) }
            addAll("roof:ridge", topFloorKeys)
            candidate.roof?.secondaryMasses?.forEach { m -> add("roof:mass:${m.id}", "project:facadeInsulation") }

            add("facade:scope", "project:facadeInsulation")

            // A stair does not enter the takeoff; it enters the picture. Recorded so the graph knows the root.
            candidate.stairs.forEach { s -> out.getOrPut("stair:${s.id}") { mutableListOf() } }

            // Garage-gate openings decide the facade scope's garage share as well.
            candidate.openings.filter { it.type == OpeningType.GARAGE_GATE }.forEach { o -> add("opening:${o.id}:height", "project:facadeInsulation") }

            return out
        }
    }
}
