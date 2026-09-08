package com.buildplan.app.render.filament

import com.buildplan.app.R
import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.LocalBounds
import com.buildplan.app.geometry.primitivesOf
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.presentation.DecompositionProfile
import com.buildplan.app.presentation.RoofCoverProfile
import com.buildplan.app.reference.visual.MarcowkiVisualModelV1
import com.buildplan.app.reference.visual.MarcowkiVisualPresentation
import com.buildplan.app.reference.visual.VisualSurfaceRole

/**
 * Which building the debug viewport is drawing.
 *
 * Two, and for different reasons. [MARCOWKI] is the point of this stage: the
 * traced model the owner will hold against the ARCHON page. [SYNTHETIC] stays
 * because it is the renderer's regression fixture — invented numbers, no source,
 * and therefore a shape that never changes when a trace is corrected. Losing it
 * would mean every future renderer bug had to be reproduced on a model that is
 * itself under revision.
 *
 * Marcówki is first, so it is what the screen opens on.
 */
internal enum class DebugModel(val labelRes: Int) {

    MARCOWKI(R.string.model_debug_source_marcowki) {
        override val building: Building get() = MarcowkiVisualModelV1.building
        override val geometry: BuildingGeometry get() = MarcowkiVisualModelV1.geometry
        override val atticId: FloorId get() = MarcowkiVisualModelV1.atticId
        override val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole>
            get() = MarcowkiVisualPresentation.surfaceRoles
        override val decomposition: DecompositionProfile
            get() = MarcowkiVisualPresentation.decomposition
        override val roofCover: RoofCoverProfile
            get() = MarcowkiVisualPresentation.roofCover

        override fun focusElementId(focus: PresetFocus): BuildingElementId? = when (focus) {
            PresetFocus.WHOLE_MODEL -> null
            PresetFocus.STAIR -> MarcowkiVisualModelV1.stairId
            PresetFocus.NORTH_BALUSTRADE -> MarcowkiVisualModelV1.balustradeIds.first()
            PresetFocus.NORTH_FRAME -> MarcowkiVisualModelV1.gableFrameIds.first()
            PresetFocus.ROOF -> MarcowkiVisualModelV1.roofId
        }
    },

    SYNTHETIC(R.string.model_debug_source_synthetic) {
        override val building: Building get() = SyntheticDemoHouse.building
        override val geometry: BuildingGeometry get() = SyntheticDemoHouse.geometry
        override val atticId: FloorId get() = SyntheticDemoHouse.atticId
        override val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole> get() = emptyMap()
        override val decomposition: DecompositionProfile get() = DecompositionProfile.NONE
        override val roofCover: RoofCoverProfile get() = RoofCoverProfile.NONE
        override fun focusElementId(focus: PresetFocus): BuildingElementId? = null
    },
    ;

    abstract val building: Building
    abstract val geometry: BuildingGeometry

    /** The upper storey, which "hide the upper floor" names. */
    abstract val atticId: FloorId

    /**
     * The presentation roles of this model's elements, for the renderer. The
     * synthetic fixture has none: it is invented, and has no glass to read as
     * glass.
     */
    abstract val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole>

    /**
     * What this model's presentation takes away beyond the domain's answer
     * when a layer is removed. The synthetic fixture has nothing to add: its
     * roof is its roof and nothing else is seen as part of it.
     */
    abstract val decomposition: DecompositionProfile

    /**
     * Which of this model's roofs are drawn with a covering, and what the
     * covering avoids. Presentation beside the model, like the roles and the
     * decomposition: the synthetic fixture declares none and stays the plain
     * planes the renderer's regression tests know.
     */
    abstract val roofCover: RoofCoverProfile

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

    /**
     * The element a focused preset frames, or null when this model has nothing
     * to frame for it. The synthetic fixture has no stair, no balcony and no
     * frame, so every focus on it is the whole model.
     */
    abstract fun focusElementId(focus: PresetFocus): BuildingElementId?

    /** The stair, when this model has one. The synthetic fixture does not. */
    val stairId: BuildingElementId? get() = focusElementId(PresetFocus.STAIR)

    /**
     * The box a focused preset frames, or the whole model when this model has
     * nothing for that focus.
     *
     * Falling back rather than refusing, because the preset list is one list for
     * both models: a stair view on a model without a stair should be the general
     * view, not a camera pointed at the origin.
     */
    fun focusBounds(focus: PresetFocus, modelBounds: LocalBounds): LocalBounds =
        focusElementId(focus)
            ?.let { id -> geometry.primitivesFor(id) }
            ?.map { it.bounds }
            ?.reduceOrNull { total, next -> total.encompass(next) }
            ?: modelBounds
}

/** What a preset is a view *of*. */
internal enum class PresetFocus { WHOLE_MODEL, STAIR, NORTH_BALUSTRADE, NORTH_FRAME, ROOF }

/**
 * A named, reproducible view of the model: a visibility state and a camera.
 *
 * These exist so that the evidence an owner reviews can be regenerated. "Roof
 * off, seen from the south-east" is not reproducible if it means whatever angle
 * a finger happened to leave the camera at; a preset is a pure function of the
 * model's own bounds, so the same preset on the same model gives the same
 * picture on every run.
 *
 * The visibility half decides nothing of its own — it is a [SpikeVisibility],
 * which the domain turns into a
 * [com.buildplan.app.domain.model.BuildingVisibility]. A preset chooses *which*
 * question to ask; the domain still answers it.
 */
internal enum class ModelViewPreset(
    val labelRes: Int,
    val visibility: SpikeVisibility,
    private val yawDegrees: Double,
    private val pitchDegrees: Double,
    private val distanceMargin: Double,
    val focus: PresetFocus = PresetFocus.WHOLE_MODEL,
) {

    /** The whole house with its roof on: massing, roof direction, relative height. */
    FULL_AXON(
        labelRes = R.string.model_view_full_axon,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 35.0,
        pitchDegrees = 22.0,
        distanceMargin = 1.15,
    ),

    /** Roof off, attic walls standing: the upper storey read from outside. */
    ROOF_OFF_AXON(
        labelRes = R.string.model_view_roof_off_axon,
        visibility = SpikeVisibility.ROOF_HIDDEN,
        yawDegrees = 35.0,
        pitchDegrees = 30.0,
        distanceMargin = 1.05,
    ),

    /** Roof and attic off: the ground floor seen into from above and the side. */
    GROUND_CUTAWAY(
        labelRes = R.string.model_view_ground_cutaway,
        visibility = SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN,
        yawDegrees = 35.0,
        pitchDegrees = 40.0,
        distanceMargin = 1.00,
    ),

    /** Near plan view of the ground floor, for comparing partitions to the plan. */
    GROUND_TOP(
        labelRes = R.string.model_view_ground_top,
        visibility = SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN,
        yawDegrees = 0.0,
        pitchDegrees = 78.0,
        distanceMargin = 0.90,
    ),

    /** The same for the attic, with only the roof taken off. */
    UPPER_TOP(
        labelRes = R.string.model_view_upper_top,
        visibility = SpikeVisibility.ROOF_HIDDEN,
        yawDegrees = 0.0,
        pitchDegrees = 78.0,
        distanceMargin = 0.90,
    ),

    /**
     * A low three-quarter view of the entrance side, for the windows.
     *
     * Nearly level with the building rather than looking down on it, because
     * this is the only view whose subject is the facade: from 22 degrees up, a
     * window reveal is a foreshortened sliver, and whether the openings are in
     * the right places is exactly what cannot be judged from it. The yaw puts
     * the garden gable and the long west facade on screen together, which
     * between them carry the terrace door, both west windows, the garage's side
     * door and both of the north gable's glazings.
     *
     * The margin is wider than any other preset's because the framing is
     * computed against the *vertical* field of view: a building seen almost
     * side-on is at its widest and at its shortest, so the distance that fits
     * its height leaves its ends off the screen.
     */
    FACADE_OPENINGS(
        labelRes = R.string.model_view_facade_openings,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 145.0,
        pitchDegrees = 10.0,
        distanceMargin = 1.75,
    ),

    /**
     * The front — the south gable with the garage beside it — nearly level and
     * a little off axis to the west.
     *
     * The view the owner's marked-up front elevation is of: the frame's left
     * leg running to the ground, the gable open through two storeys west of
     * the balcony, the band starting mid-gable and running out over the
     * garage, and the garage's own cheek closing the composition on the right.
     * Yawed to the west rather than the east so that the open half of the
     * portal and the balcony's free end are in front of the camera instead of
     * behind the garage.
     */
    FRONT_SIGNATURE(
        labelRes = R.string.model_view_front_signature,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 335.0,
        pitchDegrees = 6.0,
        distanceMargin = 1.20,
    ),

    /**
     * The north gable, close in and nearly level: the frame, the band and the
     * balcony in one frame.
     *
     * The view the owner's "is this my house" question is actually asked from.
     * The north gable is where the two signature bands meet — the portal frame
     * runs round the opening, the storey band crosses it at the balcony, and the
     * recessed glazing sits behind both — and none of that can be judged from
     * above, because from above a band 0.54 m deep on a vertical face is a line.
     *
     * Yawed off the axis rather than square to it, so the west facade comes with
     * it: a frame photographed straight on is a drawing, and what is being
     * checked is that the frame stands proud of what is behind it.
     */
    SIGNATURE_FACADE(
        labelRes = R.string.model_view_signature_facade,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 214.0,
        pitchDegrees = 8.0,
        distanceMargin = 1.30,
    ),

    /**
     * The south-east corner, where the house meets the garage.
     *
     * The one view that answers whether the two masses are one composition. The
     * storey band runs unbroken from the balcony over the entrance, across the
     * cheek, and round the whole garage at the same level; from this corner that
     * line is continuous across the screen, and from anywhere else it is two
     * lines that happen to agree.
     */
    GARAGE_RELATION(
        labelRes = R.string.model_view_garage_relation,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 48.0,
        pitchDegrees = 16.0,
        distanceMargin = 1.45,
    ),

    /**
     * The garden side from above and a little to the east: the twin of
     * [FULL_AXON] for the rear, with the garage's flat roof meeting the house
     * on the left as the garden render shows it.
     */
    FULL_REAR_AXON(
        labelRes = R.string.model_view_full_rear_axon,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 200.0,
        pitchDegrees = 20.0,
        distanceMargin = 1.15,
    ),

    /**
     * The north balustrade, close enough to see through it.
     *
     * The one question this view answers is whether the guarding reads as
     * glass — the balcony floor and the gable glazing behind it have to be
     * visible through the sheet, with its edges still drawn. Framed on the
     * balustrade's own box, from slightly above so the floor behind it is in
     * the picture.
     */
    GLASS_RAILING_CLOSEUP(
        labelRes = R.string.model_view_glass_railing,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 195.0,
        pitchDegrees = 14.0,
        distanceMargin = 1.25,
        focus = PresetFocus.NORTH_BALUSTRADE,
    ),

    /**
     * The north-west corner of the roof, where the frame's leg, its mitre and
     * the eaves fascia meet.
     *
     * Framed on the north frame and seen from the west-north-west, so the
     * frame's depth over the portal and the fascia running away along the
     * west eaves are both in one picture — the relationship the flat-sheet
     * roof edge of STAGE-013C could not show from anywhere.
     */
    ROOF_FASCIA_CLOSEUP(
        labelRes = R.string.model_view_roof_fascia,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 245.0,
        pitchDegrees = 12.0,
        distanceMargin = 1.10,
        focus = PresetFocus.NORTH_FRAME,
    ),

    /**
     * The east slope of the roof, close in and from the south-east, where the
     * sun is: the covering's course rhythm, the rolls running down the slope,
     * both stacks and the east rooflight standing in it, and the ridge and
     * the eaves closing it top and bottom.
     *
     * Framed on the roof's own box rather than the building's, and cropped
     * into it, because at the distance that fits the house a tile is a
     * texture and the question this view asks — does the roof read as tiles,
     * do they stop clean at the stacks — is a question about a few square
     * metres of it. Seen from the lit side on purpose: relief that is only
     * ambient-lit is relief a reviewer has to take on trust.
     */
    ROOF_COVER_CLOSEUP(
        labelRes = R.string.model_view_roof_cover,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 120.0,
        pitchDegrees = 34.0,
        distanceMargin = 0.62,
        focus = PresetFocus.ROOF,
    ),

    /**
     * The whole model on its reference plane, from far enough out to see it
     * standing on something.
     *
     * The grid is what this view is of. Every other preset frames the building
     * tightly, which is right for judging it and wrong for judging its size: a
     * house cropped to its own outline has no scale, and the metre grid only
     * says anything once several metres of it are on screen beside the building.
     */
    SITE_CONTEXT(
        labelRes = R.string.model_view_site_context,
        visibility = SpikeVisibility.EVERYTHING,
        yawDegrees = 35.0,
        pitchDegrees = 26.0,
        distanceMargin = 2.10,
    ),

    /**
     * The stair, close in, with the roof and the attic taken off.
     *
     * The only preset that frames something other than the whole building, and
     * it has to: the stair is two metres of a twelve-metre house, and at the
     * distance that fits the house it is a texture. The camera looks down from
     * the south-west, which is the corner the flight climbs away from — so the
     * three flights read in the order they are walked.
     *
     * Steeply, and from further out than the stair alone needs. Down at eye
     * level the camera stands between the partitions and the stair is behind a
     * wall; from above, the flights and the core they wrap are the shape being
     * looked at, and the rooms around them say which part of the house this is.
     */
    STAIRS_VIEW(
        labelRes = R.string.model_view_stairs,
        visibility = SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN,
        yawDegrees = 205.0,
        pitchDegrees = 62.0,
        distanceMargin = 3.40,
        focus = PresetFocus.STAIR,
    ),
    ;

    /**
     * Where this preset puts the camera around [bounds], looking at [focus].
     *
     * [bounds] is deliberately the *whole* model rather than the bounds of
     * whatever is currently visible: framing on the visible subset would move
     * the camera every time something was hidden, and two presets that hide
     * different things could not then be compared side by side. [focus] defaults
     * to the same box, so every whole-building preset is unaffected by the one
     * that is not.
     */
    fun framing(bounds: LocalBounds, focus: LocalBounds = bounds): OrbitCameraState.Framing =
        OrbitCameraState.framing(bounds, focus, yawDegrees, pitchDegrees, distanceMargin)
}
