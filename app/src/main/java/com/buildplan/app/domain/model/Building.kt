package com.buildplan.app.domain.model

/**
 * The building being constructed, owning its floors and every building element.
 *
 * [floors] is kept in a single canonical arrangement: strictly increasing
 * [Floor.order]. That rejects both duplicate positions and a list whose visual
 * order contradicts the stored one, so callers never have to guess which of the
 * two is authoritative.
 *
 * [elements] is the one place an element exists. Holding elements per floor
 * would leave the roof and the foundation homeless — they belong to no storey —
 * and would give a wall shared by two rooms two possible homes. One list plus an
 * explicit [BuildingElement.scope] keeps element identity canonical: an element
 * appears exactly once no matter how many rooms link it.
 */
data class Building(
    val id: BuildingId,
    val floors: List<Floor> = emptyList(),
    val elements: List<BuildingElement> = emptyList(),
) {
    init {
        requireUniqueIds(floors.map { it.id }, "Floor")

        floors.zipWithNext { lower, upper ->
            require(lower.order < upper.order) {
                "Floors must be listed in strictly increasing order, but " +
                    "${lower.name} (${lower.order}) precedes ${upper.name} (${upper.order})"
            }
        }

        // Room ids are unique across the building, not merely within a floor:
        // elements link rooms by id, and a repeated id would make the link
        // ambiguous about which storey it reached.
        requireUniqueIds(floors.flatMap { floor -> floor.rooms.map { it.id } }, "Room")
        requireUniqueIds(elements.map { it.id }, "BuildingElement")

        val roomsByFloor = floors.associate { floor ->
            floor.id to floor.rooms.mapTo(mutableSetOf()) { it.id }
        }
        val roomsInBuilding = roomsByFloor.values.flatMapTo(mutableSetOf()) { it }

        elements.forEach { element ->
            val scope = element.scope
            val reachableRooms = when (scope) {
                BuildingElementScope.WholeBuilding -> roomsInBuilding
                is BuildingElementScope.OnFloor -> requireNotNull(roomsByFloor[scope.floorId]) {
                    "BuildingElement ${element.id.value} is scoped to floor " +
                        "${scope.floorId.value}, which is not part of building ${id.value}"
                }
            }

            val unreachable = element.roomIds - reachableRooms
            require(unreachable.isEmpty()) {
                "BuildingElement ${element.id.value} links rooms outside its scope " +
                    "(${scopeLabel(scope)}): ${unreachable.joinToString { it.value }}"
            }
        }
    }

    private fun scopeLabel(scope: BuildingElementScope): String = when (scope) {
        BuildingElementScope.WholeBuilding -> "building ${id.value}"
        is BuildingElementScope.OnFloor -> "floor ${scope.floorId.value}"
    }
}
