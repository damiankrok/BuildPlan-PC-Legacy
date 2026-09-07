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
 * A physical part of the building, owned by the [Floor] it belongs to.
 *
 * Deliberately carries no geometry, transform, mesh, material or any other
 * renderer state. When the building model arrives it will read this data and
 * keep its own representation; letting render state leak in here would make the
 * domain unusable without a renderer and untestable on the JVM.
 *
 * @property roomId optional room within the same floor. Null means the element
 *   belongs to the floor as a whole, such as a load-bearing wall or a slab.
 */
data class BuildingElement(
    val id: BuildingElementId,
    val kind: BuildingElementKind,
    val name: String,
    val roomId: RoomId? = null,
) {
    init {
        requireDomainName(name, "BuildingElement name")
    }
}
