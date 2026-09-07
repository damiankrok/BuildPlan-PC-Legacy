package com.buildplan.app.domain.model

/**
 * The kind of physical element being represented.
 *
 * An explicit set, not free text: costs, statistics and the future building
 * model all need to group elements, and they cannot group over typos.
 * [OTHER] keeps the model usable for anything not yet enumerated without
 * forcing a wrong category.
 */
enum class BuildingElementKind {
    WALL,
    SLAB,
    ROOF,
    DOOR,
    WINDOW,
    STAIRS,
    FOUNDATION,
    FACADE,
    OTHER,
}

/**
 * The single logical owner of a [BuildingElement].
 *
 * Every element belongs either to the building as a whole or to exactly one
 * storey — never to both, and never to none. The alternative, making everything
 * belong to a floor, forces a fake storey to hold the roof and the foundation,
 * and a fake storey then has to be hidden from every floor list, every hide
 * operation and every total.
 *
 * Scope is ownership, not room membership: rooms are a separate 0..N relation
 * on [BuildingElement.roomIds], because a party wall genuinely belongs to two
 * rooms while still being one element.
 */
sealed interface BuildingElementScope {

    /**
     * Belongs to the building as a whole: roof, foundation, a chimney crossing
     * several storeys. No fake [Floor] is invented to hold it.
     */
    data object WholeBuilding : BuildingElementScope

    /** Belongs to exactly one storey: its walls, slabs and openings. */
    data class OnFloor(val floorId: FloorId) : BuildingElementScope
}

/**
 * A physical part of the building, owned by the [Building] and scoped by
 * [scope] to either the whole building or one [Floor].
 *
 * Deliberately carries no geometry, transform, mesh, material or any other
 * renderer state, and no visibility flag either. When the building model
 * arrives it will read this data and keep its own representation; letting
 * render state leak in here would make the domain unusable without a renderer
 * and untestable on the JVM. Hiding and isolating are expressed as pure queries
 * over a [Building] instead — see `BuildingElementSelection.kt`.
 *
 * @property scope the one logical owner. Required, so that placing an element
 *   is always a decision rather than a default.
 * @property roomIds the rooms this element belongs to, none to many. Two rooms
 *   sharing a wall link the same element rather than each owning a copy, so the
 *   wall keeps one identity, one cost and one surface in the model. A
 *   [BuildingElementScope.OnFloor] element may only link rooms on that same
 *   floor; [Building] enforces it.
 */
data class BuildingElement(
    val id: BuildingElementId,
    val kind: BuildingElementKind,
    val name: String,
    val scope: BuildingElementScope,
    val roomIds: Set<RoomId> = emptySet(),
) {
    init {
        requireDomainName(name, "BuildingElement name")
    }

    /** The owning storey, or null when the element belongs to the whole building. */
    val floorId: FloorId?
        get() = (scope as? BuildingElementScope.OnFloor)?.floorId
}
