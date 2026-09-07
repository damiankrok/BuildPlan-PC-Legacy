package com.buildplan.app.render.filament

import com.buildplan.app.R
import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.LocalBounds
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.reference.visual.MarcowkiVisualModelV1

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
        override val stairId: BuildingElementId? get() = MarcowkiVisualModelV1.stairId
    },

    SYNTHETIC(R.string.model_debug_source_synthetic) {
        override val building: Building get() = SyntheticDemoHouse.building
        override val geometry: BuildingGeometry get() = SyntheticDemoHouse.geometry
        override val atticId: FloorId get() = SyntheticDemoHouse.atticId
        override val stairId: BuildingElementId? get() = null
    },
    ;

    abstract val building: Building
    abstract val geometry: BuildingGeometry

    /** The upper storey, which "hide the upper floor" names. */
    abstract val atticId: FloorId

    /** The stair, when this model has one. The synthetic fixture does not. */
    abstract val stairId: BuildingElementId?

    /**
     * The box a preset with [PresetFocus.STAIR] frames, or the whole model when
     * this model has no stair to frame.
     *
     * Falling back rather than refusing, because the preset list is one list for
     * both models: a stair view on a model without a stair should be the general
     * view, not a camera pointed at the origin.
     */
    fun focusBounds(focus: PresetFocus, modelBounds: LocalBounds): LocalBounds = when (focus) {
        PresetFocus.WHOLE_MODEL -> modelBounds
        PresetFocus.STAIR -> stairId
            ?.let { id -> geometry.primitivesFor(id) }
            ?.map { it.bounds }
            ?.reduceOrNull { total, next -> total.encompass(next) }
            ?: modelBounds
    }
}

/** What a preset is a view *of*. */
internal enum class PresetFocus { WHOLE_MODEL, STAIR }

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
