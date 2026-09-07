package com.buildplan.app.domain.model

import com.buildplan.app.domain.units.MeasurementDimension
import com.buildplan.app.domain.units.Quantity

/**
 * A room inside a [Floor].
 *
 * A room does not carry the id of its floor. Containment is expressed once, by
 * the floor holding the room, so the two cannot disagree about where the room
 * belongs.
 *
 * @property area optional, because a room is often named long before it is
 *   measured. When present it must be a positive area.
 */
data class Room(
    val id: RoomId,
    val name: String,
    val area: Quantity? = null,
) {
    init {
        requireDomainName(name, "Room name")
        area
            ?.requireDimension(MeasurementDimension.AREA, "Room area")
            ?.requirePositive("Room area")
    }
}
