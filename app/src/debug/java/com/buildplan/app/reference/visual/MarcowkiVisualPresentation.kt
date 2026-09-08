package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.presentation.DecompositionGroup
import com.buildplan.app.presentation.DecompositionProfile
import com.buildplan.app.presentation.OpeningFrameProfile
import com.buildplan.app.presentation.OpeningFrameSpec
import com.buildplan.app.presentation.RoofCoverBlocker
import com.buildplan.app.presentation.RoofCoverProfile
import com.buildplan.app.presentation.RoofCoverSpec
import com.buildplan.app.presentation.RoofCoverStyle

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

    /**
     * The tile module the two roof facets are drawn with: a display
     * assumption, recorded as one in [MarcowkiSourceEvidence.displayAssumptions].
     *
     * The page names a pitched, tiled roof and gives its area and pitch; it
     * does not give a tile, and the model does not pretend it does. These are
     * the proportions of a plain curved clay tile laid at a gauge a study can
     * afford — about two thousand tiles over 150 m² — chosen for the read at
     * the distances the owner reviews from, not for any manufacturer's
     * profile. Nothing in the canonical roof changes: the facets keep the
     * 150.4 m² that reconciles with the published area, and the covering is
     * drawn a centimetre above them.
     */
    val roofCoverSpec: RoofCoverSpec = RoofCoverSpec(
        style = RoofCoverStyle.CURVED_TILE,
        moduleWidth = 0.25,
        moduleLength = 0.40,
        exposure = 0.30,
        tailRound = 0.05,
        camber = 0.025,
        lift = 0.01,
        step = 0.02,
    )

    /**
     * How far a tile stops short of a stack or a rooflight. Display only: it
     * is the flashing a real roof has there, reduced to a gap.
     */
    const val ROOF_COVER_BLOCKER_MARGIN: Double = 0.03

    /**
     * Which of this model's elements are drawn with a covering, and what the
     * covering must avoid.
     *
     * Only the roof itself. The gable frames' soffits and tops are
     * `RoofFacetGeometry` too, and they are not tiled: they are trim, and a
     * tiled soffit would be a roof where the elevations draw a bar. The
     * blockers are the two stacks and the three rooflights — read off the
     * bounds of the geometry the model already draws for them, by id, so the
     * stack the tiles avoid is the stack that is on screen. No coordinate is
     * written a second time here.
     */
    val roofCover: RoofCoverProfile = RoofCoverProfile(
        covers = mapOf(MarcowkiVisualModelV1.roofId to roofCoverSpec),
        blockers = roofCoverBlockers(),
    )

    /**
     * The frame every facade pane is drawn with: a display assumption,
     * recorded as one in [MarcowkiSourceEvidence.displayAssumptions].
     *
     * The schedule gives each opening a width and a height and no joinery.
     * These are the proportions a frame needs to read as a frame at the
     * distance a phone is held — a 7 cm bar, 8 cm through the wall — and a
     * leaf no wider than 1.20 m, which divides the 4.70 m terrace glazing
     * into four and the two 2.34 m gable glazings into two, as the published
     * renders show them divided. Nothing in the canonical pane, hole or wall
     * changes; the frame is a mesh laid through the pane by the renderer's
     * adapter.
     */
    val glazingFrameSpec: OpeningFrameSpec = OpeningFrameSpec(
        barWidth = 0.07,
        barDepth = 0.08,
        maxLeafWidth = 1.20,
    )

    /** The garage door's frame: the same bar, no leaves — it is a gate, not a glazing. */
    val gateFrameSpec: OpeningFrameSpec = glazingFrameSpec.copy(maxLeafWidth = null)

    /**
     * Which of this model's panes are framed.
     *
     * The facade joinery the plans schedule, and the three rooflights under
     * the roof's own id. Internal doors are deliberately absent: a hole with
     * a leaf and nothing round it, by the same rule that gives them no
     * handle. The ids come from the model as its openings were placed, so an
     * opening the trace gains or loses is framed or not without a second
     * list to keep in step.
     */
    val openingFrames: OpeningFrameProfile = OpeningFrameProfile(
        MarcowkiVisualModelV1.facadeOpeningIds.associateWith { id ->
            if (id == MarcowkiVisualModelV1.garageDoorId) gateFrameSpec else glazingFrameSpec
        } + (MarcowkiVisualModelV1.roofId to glazingFrameSpec),
    )

    private fun roofCoverBlockers(): List<RoofCoverBlocker> {
        val geometry = MarcowkiVisualModelV1.geometry
        val stacks = MarcowkiVisualModelV1.stackIds.flatMap { geometry.primitivesFor(it) }
        val rooflights = geometry.primitivesFor(MarcowkiVisualModelV1.roofId).filterIsInstance<OpeningPanelGeometry>()
        return (stacks + rooflights).map { RoofCoverBlocker.aroundPlan(it.bounds, ROOF_COVER_BLOCKER_MARGIN) }
    }
}
