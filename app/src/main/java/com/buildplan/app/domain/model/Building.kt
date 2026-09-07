package com.buildplan.app.domain.model

/**
 * The building being constructed, owning its floors.
 *
 * [floors] is kept in a single canonical arrangement: strictly increasing
 * [Floor.order]. That rejects both duplicate positions and a list whose visual
 * order contradicts the stored one, so callers never have to guess which of the
 * two is authoritative.
 */
data class Building(
    val id: BuildingId,
    val floors: List<Floor> = emptyList(),
) {
    init {
        requireUniqueIds(floors.map { it.id }, "Floor")

        floors.zipWithNext { lower, upper ->
            require(lower.order < upper.order) {
                "Floors must be listed in strictly increasing order, but " +
                    "${lower.name} (${lower.order}) precedes ${upper.name} (${upper.order})"
            }
        }
    }
}
