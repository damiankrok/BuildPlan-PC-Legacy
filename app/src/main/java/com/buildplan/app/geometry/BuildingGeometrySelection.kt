package com.buildplan.app.geometry

import com.buildplan.app.domain.model.BuildingElement

/**
 * The one bridge from a semantic selection to the shapes that draw it.
 *
 * Deliberately one function. Which elements survive hiding the roof, hiding a
 * storey, hiding both, or isolating a room is already decided — once, in pure
 * Kotlin — by `BuildingElementSelection.kt` in the domain. Re-deciding any of it
 * here would give the product two answers to the same question, and the one the
 * user sees would be whichever the renderer happened to call.
 *
 * So the composition is always the same two steps, and the second one is this:
 *
 * ```
 * val visible = building.visibleElements(BuildingVisibility(roofHidden = true))
 * val toDraw = geometry.primitivesOf(visible)
 * ```
 */
fun BuildingGeometry.primitivesOf(elements: List<BuildingElement>): List<BuildingGeometryPrimitive> =
    primitivesFor(elements.mapTo(mutableSetOf()) { it.id })
