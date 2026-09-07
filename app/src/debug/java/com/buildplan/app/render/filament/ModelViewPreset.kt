package com.buildplan.app.render.filament

import com.buildplan.app.R
import com.buildplan.app.domain.model.Building
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
    },

    SYNTHETIC(R.string.model_debug_source_synthetic) {
        override val building: Building get() = SyntheticDemoHouse.building
        override val geometry: BuildingGeometry get() = SyntheticDemoHouse.geometry
        override val atticId: FloorId get() = SyntheticDemoHouse.atticId
    },
    ;

    abstract val building: Building
    abstract val geometry: BuildingGeometry

    /** The upper storey, which "hide the upper floor" names. */
    abstract val atticId: FloorId
}

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
    ;

    /**
     * Where this preset puts the camera around [bounds].
     *
     * Deliberately takes the *whole* model's bounds rather than the bounds of
     * whatever is currently visible: framing on the visible subset would move
     * the camera every time something was hidden, and two presets that hide
     * different things could not then be compared side by side.
     */
    fun framing(bounds: LocalBounds): OrbitCameraState.Framing =
        OrbitCameraState.framing(bounds, yawDegrees, pitchDegrees, distanceMargin)
}
