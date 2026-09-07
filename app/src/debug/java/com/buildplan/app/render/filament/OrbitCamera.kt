package com.buildplan.app.render.filament

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.buildplan.app.geometry.LocalBounds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where the camera is and what it looks at, in the building's local metres. */
internal data class OrbitPose(
    val eyeX: Double,
    val eyeY: Double,
    val eyeZ: Double,
    val targetX: Double,
    val targetY: Double,
    val targetZ: Double,
    /** Near plane, scaled to the current distance so depth precision stays usable. */
    val near: Double,
    val far: Double,
)

/**
 * An orbit camera around the model: a target, two angles and a distance.
 *
 * It holds no Filament type. The renderer is handed an [OrbitPose] each frame
 * and never decides where to look, which is what lets the gesture handling be
 * reasoned about — and the framing be re-derived after an Activity recreation —
 * without a GPU anywhere in sight.
 *
 * The distance is clamped to a band derived from the model's own size, so the
 * camera can neither end up inside a wall nor drift so far out that the building
 * is a dot. [FIELD_OF_VIEW_DEGREES] is shared with the renderer's projection:
 * framing computed against one field of view and drawn with another is how a
 * model ends up cropped on first frame.
 */
internal class OrbitCameraState(
    private val bounds: LocalBounds,
    initialYaw: Float,
    initialPitch: Float,
    initialDistance: Float,
    initialPanRight: Float,
    initialPanUp: Float,
) {

    private var yaw by mutableFloatStateOf(initialYaw)
    private var pitch by mutableFloatStateOf(initialPitch)
    private var distance by mutableFloatStateOf(initialDistance)
    private var panRight by mutableFloatStateOf(initialPanRight)
    private var panUp by mutableFloatStateOf(initialPanUp)

    /** Half the diagonal of the model's box: the radius everything is scaled against. */
    private val radius: Double = 0.5 * sqrt(
        bounds.sizeX * bounds.sizeX + bounds.sizeY * bounds.sizeY + bounds.sizeZ * bounds.sizeZ,
    )

    private val minDistance: Float = (radius * MIN_DISTANCE_FACTOR).toFloat()
    private val maxDistance: Float = (radius * MAX_DISTANCE_FACTOR).toFloat()

    /** Drag with one finger: yaw around the model, pitch clamped short of the poles. */
    fun orbit(dragX: Float, dragY: Float) {
        yaw -= dragX * ORBIT_RADIANS_PER_PIXEL
        pitch = (pitch + dragY * ORBIT_RADIANS_PER_PIXEL).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /** Pinch: multiply the distance, then clamp it back into the allowed band. */
    fun zoomBy(factor: Float) {
        if (factor <= 0f) return
        distance = (distance / factor).coerceIn(minDistance, maxDistance)
    }

    /**
     * Two-finger drag: slide the target across the screen plane. The pixel-to-
     * metre scale follows the distance, so panning feels the same whether the
     * camera is close in or far out.
     */
    fun pan(dragX: Float, dragY: Float, viewportHeight: Int) {
        if (viewportHeight <= 0) return
        val metersPerPixel =
            (2.0 * distance * tan(Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0)) / viewportHeight)
        panRight -= (dragX * metersPerPixel).toFloat()
        panUp += (dragY * metersPerPixel).toFloat()
    }

    /** Puts the camera back where [frame] first placed it. */
    fun reset() {
        apply(frame(bounds))
    }

    /**
     * Moves the camera to a named framing, dropping any pan the user had
     * applied.
     *
     * This is what makes a debug view preset reproducible: the framing is a pure
     * function of the model bounds, so the same preset on the same model puts
     * the camera in the same place on every run and every device, whatever the
     * user had dragged it to first.
     */
    fun apply(framing: Framing) {
        yaw = framing.yaw
        pitch = framing.pitch
        distance = framing.distance.coerceIn(minDistance, maxDistance)
        panRight = 0f
        panUp = 0f
    }

    fun pose(): OrbitPose {
        val sinYaw = sin(yaw.toDouble())
        val cosYaw = cos(yaw.toDouble())
        val sinPitch = sin(pitch.toDouble())
        val cosPitch = cos(pitch.toDouble())

        // Unit vector from the target towards the eye.
        val directionX = cosPitch * sinYaw
        val directionY = sinPitch
        val directionZ = cosPitch * cosYaw

        // Screen right and screen up, for the pan offset. Right is horizontal by
        // construction, so panning never rolls the model. Both fall out of
        // right = normalize(forward x worldUp) and up = right x forward, with
        // the trigonometry already simplified because direction is a unit vector.
        val rightX = cosYaw
        val rightZ = -sinYaw

        val upX = -sinYaw * sinPitch
        val upY = cosPitch
        val upZ = -cosYaw * sinPitch

        val center = bounds.center
        val targetX = center.x + rightX * panRight + upX * panUp
        val targetY = center.y + upY * panUp
        val targetZ = center.z + rightZ * panRight + upZ * panUp

        val currentDistance = distance.toDouble()
        return OrbitPose(
            eyeX = targetX + directionX * currentDistance,
            eyeY = targetY + directionY * currentDistance,
            eyeZ = targetZ + directionZ * currentDistance,
            targetX = targetX,
            targetY = targetY,
            targetZ = targetZ,
            near = (currentDistance * NEAR_PLANE_FACTOR).coerceAtLeast(MIN_NEAR_PLANE),
            far = currentDistance + radius * FAR_PLANE_MARGIN,
        )
    }

    internal fun saveableState(): List<Float> = listOf(yaw, pitch, distance, panRight, panUp)

    internal data class Framing(val yaw: Float, val pitch: Float, val distance: Float)

    companion object {

        /** Vertical field of view, shared with the renderer's projection. */
        const val FIELD_OF_VIEW_DEGREES: Double = 45.0

        private const val ORBIT_RADIANS_PER_PIXEL = 0.008f
        private val MIN_PITCH = (-70.0 * PI / 180.0).toFloat()
        private val MAX_PITCH = (80.0 * PI / 180.0).toFloat()

        private const val MIN_DISTANCE_FACTOR = 0.55
        private const val MAX_DISTANCE_FACTOR = 10.0
        private const val FRAMING_MARGIN = 1.15
        private const val DEFAULT_YAW_DEGREES = 35.0
        private const val DEFAULT_PITCH_DEGREES = 22.0
        private const val NEAR_PLANE_FACTOR = 0.01
        private const val MIN_NEAR_PLANE = 0.05
        private const val FAR_PLANE_MARGIN = 12.0

        /**
         * The opening view of [bounds]: a three-quarter view from above, far
         * enough out that the whole box fits the vertical field of view with a
         * margin, which puts the camera outside the building rather than in it.
         */
        fun frame(bounds: LocalBounds): Framing =
            framing(bounds, DEFAULT_YAW_DEGREES, DEFAULT_PITCH_DEGREES, FRAMING_MARGIN)

        /**
         * An arbitrary view of [bounds], as two angles and how much room to
         * leave around the model.
         *
         * A pure function of its arguments and nothing else — no screen size, no
         * device, no previous camera. That is what a reproducible debug view
         * preset needs, and what lets one be asserted on a plain JVM.
         *
         * [margin] multiplies the distance at which the model box exactly fills
         * the vertical field of view, so 1.0 is a tight fit and anything below
         * it deliberately crops the box - which a near top-down plan view wants,
         * because the height of the building is not what is being looked at.
         */
        fun framing(
            bounds: LocalBounds,
            yawDegrees: Double,
            pitchDegrees: Double,
            margin: Double,
        ): Framing {
            val radius = 0.5 * sqrt(
                bounds.sizeX * bounds.sizeX +
                    bounds.sizeY * bounds.sizeY +
                    bounds.sizeZ * bounds.sizeZ,
            )
            val halfFov = Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0)
            return Framing(
                yaw = (yawDegrees * PI / 180.0).toFloat(),
                pitch = (pitchDegrees * PI / 180.0).toFloat(),
                distance = (radius / tan(halfFov) * margin).toFloat(),
            )
        }
    }
}

/**
 * Remembers an [OrbitCameraState] across configuration changes.
 *
 * The camera survives an Activity recreation because losing it is jarring in a
 * way that losing, say, the current selection is not: the user has aimed it by
 * hand. What is *not* saved is deliberate — visibility and selection are rebuilt
 * from the domain on every composition.
 */
@Composable
internal fun rememberOrbitCameraState(bounds: LocalBounds): OrbitCameraState =
    rememberSaveable(
        bounds,
        saver = listSaver(
            save = { it.saveableState() },
            restore = { saved ->
                OrbitCameraState(
                    bounds = bounds,
                    initialYaw = saved[0],
                    initialPitch = saved[1],
                    initialDistance = saved[2],
                    initialPanRight = saved[3],
                    initialPanUp = saved[4],
                )
            },
        ),
    ) {
        val framing = OrbitCameraState.frame(bounds)
        OrbitCameraState(
            bounds = bounds,
            initialYaw = framing.yaw,
            initialPitch = framing.pitch,
            initialDistance = framing.distance,
            initialPanRight = 0f,
            initialPanUp = 0f,
        )
    }
