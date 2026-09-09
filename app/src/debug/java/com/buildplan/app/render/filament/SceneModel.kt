package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.LocalBounds
import com.buildplan.app.geometry.primitivesOf
import com.buildplan.app.presentation.DecompositionProfile
import com.buildplan.app.presentation.OpeningFrameProfile
import com.buildplan.app.presentation.RoofCoverProfile
import com.buildplan.app.reference.visual.VisualSurfaceRole

/**
 * What the debug viewport needs from a model to draw it: the canonical
 * building and its geometry, which storey counts as "upper", and the
 * presentation profiles kept beside the model.
 *
 * The seam exists so that a model can come from somewhere other than a
 * compiled fixture — the analyzer's candidate preview — without the renderer,
 * the scene or the canvas learning where it came from. The decision path for
 * what is on screen is unchanged and lives here once: the domain says which
 * elements survive a visibility, the presentation profile narrows that, and
 * the geometry's single bridge says which shapes draw them.
 */
internal interface SceneModel {
    val building: Building
    val geometry: BuildingGeometry

    /** The upper storey, which "hide the upper floor" names. */
    val atticId: FloorId

    /** Presentation roles by element id; empty when the model has no glass to read as glass. */
    val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole>

    /** What the presentation takes away beyond the domain's answer when a layer is removed. */
    val decomposition: DecompositionProfile

    /** Which roofs are drawn with a covering. */
    val roofCover: RoofCoverProfile

    /** Which panes are drawn with a frame. */
    val openingFrames: OpeningFrameProfile

    /** The element a focused preset frames, or null when this model has nothing for it. */
    fun focusElementId(focus: PresetFocus): BuildingElementId?

    /**
     * The one decision path for what is on screen: the domain says which
     * elements survive [visibility], the presentation profile narrows that to
     * what the view is meant to expose, and the geometry's single bridge says
     * which shapes draw them. The renderer receives the ids and nothing else.
     */
    fun visibleElementIds(visibility: BuildingVisibility): Set<BuildingElementId> =
        geometry
            .primitivesOf(decomposition.visibleElements(building, visibility))
            .mapTo(LinkedHashSet()) { it.elementId }

    /** The stair, when this model has one. */
    val stairId: BuildingElementId? get() = focusElementId(PresetFocus.STAIR)

    /**
     * The box a focused preset frames, or the whole model when this model has
     * nothing for that focus — a stair view on a model without a stair is the
     * general view, not a camera pointed at the origin.
     */
    fun focusBounds(focus: PresetFocus, modelBounds: LocalBounds): LocalBounds =
        focusElementId(focus)
            ?.let { id -> geometry.primitivesFor(id) }
            ?.map { it.bounds }
            ?.reduceOrNull { total, next -> total.encompass(next) }
            ?: modelBounds
}
