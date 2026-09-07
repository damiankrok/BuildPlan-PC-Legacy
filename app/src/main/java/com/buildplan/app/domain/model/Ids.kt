package com.buildplan.app.domain.model

/**
 * Application-owned stable identifiers.
 *
 * Every persistent domain object is identified by an explicit, typed id. Names,
 * list positions, indices and hash codes are never identity: names get edited,
 * positions get reordered, and hash codes collide. A typed id also makes it a
 * compile error to pass a [RoomId] where a [FloorId] is expected, which a bare
 * String would happily allow.
 *
 * Ids are supplied by the caller. Minting them is deliberately not decided here
 * — that belongs with persistence in STAGE-003, which owns when an entity first
 * becomes durable.
 */
private fun requireStableId(value: String, type: String): String {
    require(value.isNotBlank()) { "$type must not be blank" }
    return value
}

@JvmInline
value class ProjectId(val value: String) {
    init { requireStableId(value, "ProjectId") }
}

@JvmInline
value class BuildingId(val value: String) {
    init { requireStableId(value, "BuildingId") }
}

@JvmInline
value class FloorId(val value: String) {
    init { requireStableId(value, "FloorId") }
}

@JvmInline
value class RoomId(val value: String) {
    init { requireStableId(value, "RoomId") }
}

@JvmInline
value class BuildingElementId(val value: String) {
    init { requireStableId(value, "BuildingElementId") }
}

@JvmInline
value class StageId(val value: String) {
    init { requireStableId(value, "StageId") }
}

@JvmInline
value class CostId(val value: String) {
    init { requireStableId(value, "CostId") }
}

@JvmInline
value class BudgetId(val value: String) {
    init { requireStableId(value, "BudgetId") }
}
