package com.buildplan.app.presentation

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.visibleElements

/**
 * A visual group an element is taken away with, when that group is not the
 * one the element semantically belongs to.
 *
 * Only what this stage needs. Storeys need no group here — an element's floor
 * is already its [com.buildplan.app.domain.model.BuildingElementScope], and the
 * domain hides it with the storey. What the domain cannot say is that a gable
 * frame, which spans both storeys and is honestly building-scoped, is *seen* as
 * part of the roof: taking the roof off and leaving the frame draws the roof's
 * outline in the air over an attic that was supposed to be exposed.
 */
enum class DecompositionGroup {
    /**
     * Goes away with the roof. The roof planes themselves need no entry — they
     * are `ROOF` elements and the domain already hides them — so this names the
     * building-scoped trim that visually encloses the attic: raking frames,
     * eaves fascia and the like.
     */
    ROOF_ENVELOPE,
}

/**
 * The one place a presentation may narrow what the domain shows.
 *
 * ## What it is
 *
 * A map from element id to [DecompositionGroup], kept beside a reference
 * model in the same way its glass roles are, and applied *after* the domain's
 * own selection. [visibleElements] can only ever remove from the domain's
 * answer, never add to it: every state is still a subset of one canonical
 * [Building], and an element the domain hides stays hidden whatever this says.
 *
 * ## What it is not
 *
 * Not ownership. It does not replace
 * [com.buildplan.app.domain.model.BuildingElementScope] or room links, and a
 * frame listed here is still a whole-building element for costing, naming and
 * picking. Not renderer state either: the renderer receives the final id set
 * and holds no rule about roofs.
 *
 * Source-neutral: nothing here names a house. A second reference model brings
 * its own map.
 */
class DecompositionProfile(
    private val groups: Map<BuildingElementId, DecompositionGroup>,
) {

    /** The ids assigned to [group], in declaration order. */
    fun elementsIn(group: DecompositionGroup): Set<BuildingElementId> =
        groups.filterValues { it == group }.keys

    /** Whether this profile takes [elementId] away under [visibility] beyond what the domain does. */
    fun removes(elementId: BuildingElementId, visibility: BuildingVisibility): Boolean =
        visibility.roofHidden && groups[elementId] == DecompositionGroup.ROOF_ENVELOPE

    /**
     * The domain's answer for [visibility], minus the elements this profile
     * assigns to a group that [visibility] takes away. Order is the building's.
     */
    fun visibleElements(building: Building, visibility: BuildingVisibility): List<BuildingElement> =
        building.visibleElements(visibility).filterNot { removes(it.id, visibility) }

    companion object {
        /** No presentation groups: the domain's answer is the final answer. */
        val NONE: DecompositionProfile = DecompositionProfile(emptyMap())
    }
}
