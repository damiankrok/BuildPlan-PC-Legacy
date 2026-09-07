package com.buildplan.app.domain.model

import com.buildplan.app.domain.units.MeasurementDimension
import com.buildplan.app.domain.units.Quantity

/**
 * One storey of a [Building], owning its rooms and building elements.
 *
 * @property order explicit position within the building, counted from the
 *   lowest storey. Ordering is stored, never inferred from list position, so it
 *   survives storage and reordering.
 * @property elevation optional height of the floor level relative to ground.
 *   May be negative: a basement sits below zero.
 * @property height optional storey height, which must be positive when given.
 */
data class Floor(
    val id: FloorId,
    val name: String,
    val order: Int,
    val elevation: Quantity? = null,
    val height: Quantity? = null,
    val rooms: List<Room> = emptyList(),
    val elements: List<BuildingElement> = emptyList(),
) {
    init {
        requireDomainName(name, "Floor name")
        requireOrder(order, "Floor order")

        elevation?.requireDimension(MeasurementDimension.LENGTH, "Floor elevation")
        height
            ?.requireDimension(MeasurementDimension.LENGTH, "Floor height")
            ?.requirePositive("Floor height")

        requireUniqueIds(rooms.map { it.id }, "Room")
        requireUniqueIds(elements.map { it.id }, "BuildingElement")

        // An element may point at a room, but only at one on this same floor —
        // otherwise the floor would own an element that belongs elsewhere.
        val roomIds = rooms.mapTo(mutableSetOf()) { it.id }
        elements.forEach { element ->
            val roomId = element.roomId
            require(roomId == null || roomId in roomIds) {
                "BuildingElement ${element.id.value} points at room ${roomId?.value} " +
                    "which is not on floor ${id.value}"
            }
        }
    }
}
