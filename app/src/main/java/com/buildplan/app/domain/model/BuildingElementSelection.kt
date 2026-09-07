package com.buildplan.app.domain.model

/**
 * Pure queries that answer which elements a view of the building should show.
 *
 * The later 3D model needs to hide the roof, hide a storey, hide both at once
 * and isolate a single room. What it needs to know for that — which elements
 * survive — is a fact about the building, not about the renderer, so it is
 * decided here in plain Kotlin: deterministic, testable on the JVM, and free of
 * Compose, cameras, meshes, alpha and materials.
 *
 * Nothing here is stored. [BuildingVisibility] is an argument, never a field on
 * [Building], [Floor] or [BuildingElement]: a domain entity that remembered what
 * is currently hidden would be holding renderer state, and two views of the same
 * building could not disagree. Every query preserves the declaration order of
 * [Building.elements], so the same building and the same question always give
 * the same answer in the same order.
 */

/** Elements owned by one storey. Building-scoped elements are not on any floor. */
fun Building.elementsOnFloor(floorId: FloorId): List<BuildingElement> =
    elements.filter { it.floorId == floorId }

/** Elements owned by the building as a whole, such as the roof and the foundation. */
fun Building.buildingScopedElements(): List<BuildingElement> =
    elements.filter { it.scope == BuildingElementScope.WholeBuilding }

/**
 * Roof elements, identified by their kind and independent of any storey. The
 * roof is found without walking floors precisely because it belongs to none.
 */
fun Building.roofElements(): List<BuildingElement> =
    elements.filter { it.kind == BuildingElementKind.ROOF }

/**
 * Every element linked to one room, each appearing once. A wall shared by two
 * rooms is returned for both, and is the same element in both.
 */
fun Building.elementsInRoom(roomId: RoomId): List<BuildingElement> =
    elements.filter { roomId in it.roomIds }

/** The storey a room sits on, or null when the room is not in this building. */
fun Building.floorOfRoom(roomId: RoomId): Floor? =
    floors.firstOrNull { floor -> floor.rooms.any { it.id == roomId } }

/**
 * What a view of the building currently hides.
 *
 * A query argument, not stored state — see the file note above. Hiding a storey
 * leaves building-scoped elements alone: dropping the foundation because the
 * ground floor is hidden would be a different, unasked-for operation.
 */
data class BuildingVisibility(
    val hiddenFloorIds: Set<FloorId> = emptySet(),
    val roofHidden: Boolean = false,
) {

    /** Whether one element survives this view. */
    fun shows(element: BuildingElement): Boolean {
        if (roofHidden && element.kind == BuildingElementKind.ROOF) return false
        val floorId = element.floorId
        return floorId == null || floorId !in hiddenFloorIds
    }

    companion object {
        /** Nothing hidden. */
        val EVERYTHING: BuildingVisibility = BuildingVisibility()
    }
}

/** The elements remaining once [visibility] has been applied. */
fun Building.visibleElements(visibility: BuildingVisibility): List<BuildingElement> =
    elements.filter { visibility.shows(it) }

/**
 * One room lifted out of the building together with the elements that make it
 * up and the storey it sits on.
 *
 * The camera work that follows is the renderer's; this is only the selection it
 * will be given.
 */
data class RoomIsolation(
    val floor: Floor,
    val room: Room,
    val elements: List<BuildingElement>,
)

/**
 * Selects one room and the elements linked to it. A shared wall appears once,
 * because it is one element rather than a copy per room.
 *
 * @throws IllegalArgumentException if the room is not part of this building.
 */
fun Building.isolateRoom(roomId: RoomId): RoomIsolation {
    val floor = requireNotNull(floorOfRoom(roomId)) {
        "Room ${roomId.value} is not part of building ${id.value}"
    }
    val room = floor.rooms.first { it.id == roomId }
    return RoomIsolation(floor = floor, room = room, elements = elementsInRoom(roomId))
}
