package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.presentation.DecompositionGroup
import com.buildplan.app.presentation.DecompositionProfile

/**
 * How a reference element's surfaces should read in the technical study, when
 * the shape alone does not say.
 *
 * ## Why this exists at all
 *
 * The renderer decides most of its shading from the *primitive type*: a thin
 * planar fill of a hole is glazing, everything else is fabric. That rule is
 * right and stays. But a glass balustrade is a sheet with thickness — a
 * [com.buildplan.app.geometry.WallGeometry] two centimetres thick — and no
 * geometric fact distinguishes it from a very thin wall. Drawn as fabric it
 * reads as a parapet, and the owner's verdict on STAGE-013C said exactly that:
 * the railings looked like solid wall.
 *
 * ## Why it is not on the element
 *
 * The domain does not know about transparency, and must not: "this reads as
 * glass in the debug study" is a fact about one presentation of one reference
 * model, not about what the element is or costs. So it is a map keyed by
 * [BuildingElementId], kept beside the reference model in `src/debug`, and the
 * canonical `Building` and `BuildingGeometry` are byte-for-byte what they
 * would be without it. The join is the id and nothing else — the same rule the
 * geometry and the renderer already follow.
 *
 * ## What it is not
 *
 * Not a material library. Two roles, both neutral and monochrome: the one
 * exception to "one surface colour" is transparency, and it is made because
 * glass that is not see-through is not glass. There is deliberately no role
 * for "signature trim": a frame is shown by its depth and its outline, never by
 * a third colour — see `CLAUDE.md`.
 */
enum class VisualSurfaceRole {
    /** Light neutral solid: walls, slabs, roofs, frames, trim. The default. */
    OPAQUE_STUDY,

    /** Neutral, semi-transparent sheet: balustrades, and every glazing pane. */
    GLASS_STUDY,
}

/**
 * The presentation roles of the Marcówki reference model, by element id.
 *
 * Only the balustrades are listed. Glazing panes need no entry — their
 * primitive type already says what they are — and listing them here would be
 * a second answer to a question the geometry answers.
 */
object MarcowkiVisualPresentation {

    val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole> =
        MarcowkiVisualModelV1.balustradeIds.associateWith { VisualSurfaceRole.GLASS_STUDY }

    /**
     * Which building-scoped trim is *seen* as the roof and goes away with it.
     *
     * The gable frames and the eaves fascia are whole-building elements, and
     * remain so: they span both storeys, and nothing about their ownership
     * changes here. But with the roof off they are the roof's outline standing
     * in the air over the attic — the raking bars trace the two slopes, the
     * fascia the two eaves — and the owner's verdict on STAGE-013D was that
     * "without the roof" still showed a roof. So the roof-off view removes
     * them too, through this map and nowhere else. The cheeks are walls and
     * stay; the stacks are `ROOF` and already go with the domain's answer.
     */
    val decomposition: DecompositionProfile = DecompositionProfile(
        (MarcowkiVisualModelV1.gableFrameIds + MarcowkiVisualModelV1.fasciaId)
            .associateWith { DecompositionGroup.ROOF_ENVELOPE },
    )
}
