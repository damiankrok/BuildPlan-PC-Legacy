package com.buildplan.app.domain.model

import com.buildplan.app.domain.units.MeasurementDimension
import com.buildplan.app.domain.units.Quantity

/**
 * One storey of a [Building], owning its rooms.
 *
 * Building elements are not held here. They live on [Building] and name their
 * storey through [BuildingElementScope.OnFloor], so that the roof and the
 * foundation — which belong to no storey — need no fake floor to sit on, and so
 * that a floor list and an element's own scope can never disagree.
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
) {
    init {
        requireDomainName(name, "Floor name")
        requireOrder(order, "Floor order")

        elevation?.requireDimension(MeasurementDimension.LENGTH, "Floor elevation")
        height
            ?.requireDimension(MeasurementDimension.LENGTH, "Floor height")
            ?.requirePositive("Floor height")

        requireUniqueIds(rooms.map { it.id }, "Room")
    }
}
