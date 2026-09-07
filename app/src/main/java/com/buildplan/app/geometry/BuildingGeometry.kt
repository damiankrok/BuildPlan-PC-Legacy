package com.buildplan.app.geometry

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElementId

/**
 * All the shapes of one building, in the building's local metres.
 *
 * ## A separate layer, joined by id
 *
 * This is not part of [Building] and [Building] knows nothing about it. The
 * semantic model answers what exists, who owns it and what it costs; this
 * answers what it looks like. Keeping them apart is what lets a building be
 * created, costed and tested before any of it has a shape — as
 * `MarcowkiReferenceProject` is — and lets one building later gain geometry from
 * a parser without any of its semantics being rewritten.
 *
 * The join is [BuildingGeometryPrimitive.elementId] and nothing else. Floor
 * ownership, room links, element kind and visibility are not repeated here; see
 * [BuildingGeometryPrimitive].
 *
 * ## Order
 *
 * [primitives] keeps its declaration order, and every query preserves it. The
 * same geometry and the same question therefore always give the same answer in
 * the same order — which a renderer needs for stable draw order, and a test
 * needs to be able to assert anything at all.
 */
data class BuildingGeometry(
    val primitives: List<BuildingGeometryPrimitive> = emptyList(),
) {

    /** The elements that have a shape, in the order they first appear. */
    val elementIds: Set<BuildingElementId>
        get() = primitives.mapTo(LinkedHashSet()) { it.elementId }

    /**
     * The axis-aligned local box containing every primitive, or `null` when
     * there is no geometry.
     *
     * Null rather than an empty box at the origin: a box around nothing is a
     * lie a camera would happily fly to.
     */
    val bounds: LocalBounds?
        get() = primitives.map { it.bounds }.reduceOrNull { total, next -> total.encompass(next) }

    /** Every shape of one element, in declaration order. Empty when it has none. */
    fun primitivesFor(elementId: BuildingElementId): List<BuildingGeometryPrimitive> =
        primitives.filter { it.elementId == elementId }

    /**
     * Every shape of any of [elementIds], in declaration order.
     *
     * A set is taken rather than a list because this answers "which of these",
     * not "in this order": the answer's order is the geometry's, so asking for
     * the same elements twice cannot produce two different draw orders, and an
     * element named twice cannot make its walls appear twice.
     */
    fun primitivesFor(elementIds: Set<BuildingElementId>): List<BuildingGeometryPrimitive> =
        primitives.filter { it.elementId in elementIds }

    /**
     * The elements this geometry draws that [building] does not have.
     *
     * Geometry pointing at an element that no longer exists is how a model goes
     * quietly wrong: the shape keeps being drawn, but nothing can name it, hide
     * it or cost it.
     */
    fun unknownElementIds(building: Building): Set<BuildingElementId> {
        val known = building.elements.mapTo(mutableSetOf()) { it.id }
        return elementIds.filterNotTo(LinkedHashSet()) { it in known }
    }

    /**
     * Requires every shape to belong to an element of [building].
     *
     * @throws IllegalArgumentException naming the unknown elements. This is
     *   checked on demand rather than in the constructor because geometry is
     *   assembled alongside its building rather than after it, and a type that
     *   could only be built last would force one of the two to be built twice.
     */
    fun requireElementsIn(building: Building) {
        val unknown = unknownElementIds(building)
        require(unknown.isEmpty()) {
            "Geometry references elements that are not in building ${building.id.value}: " +
                unknown.joinToString { it.value }
        }
    }
}
