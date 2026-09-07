package com.buildplan.app.geometry

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The coordinate contract every geometry primitive in this package obeys.
 *
 * ## Units
 *
 * **Metres.** Every coordinate, elevation, height and thickness in this package
 * is a length in metres. There are no pixels, no density-independent pixels and
 * no screen coordinates here: those depend on a display, and geometry that
 * depends on a display cannot be tested, compared or reused by a second
 * renderer. Converting metres into whatever a renderer draws with is the
 * renderer's job, and the renderer does not exist yet.
 *
 * ## Axes
 *
 * A right-handed frame with **Y up**:
 *
 * - `X` — first local horizontal axis;
 * - `Y` — vertical, increasing upwards;
 * - `Z` — second local horizontal axis;
 * - the floor plan therefore lies in the **XZ plane**, and a plan drawing is
 *   what you see looking down the Y axis.
 *
 * Right-handed means `X × Y = Z`. Winding order is deliberately *not* given a
 * meaning: outlines are validated on absolute area, so no primitive silently
 * depends on whether a polygon was authored clockwise or counter-clockwise.
 * A renderer that needs face orientation will decide it from its own normals.
 *
 * ## Origin
 *
 * Coordinates are **local to one building model**. There is no site, no terrain,
 * no georeference and no camera: an origin shared with the outside world is a
 * decision for whatever imports real plans, and a camera is renderer state.
 *
 * ## Finiteness
 *
 * `NaN` and the infinities are rejected at construction, everywhere. They
 * propagate silently through every later transform, bound and intersection, and
 * turn one bad number into a model that cannot be drawn at all.
 */
object GeometryTolerance {

    /**
     * The distance below which two coordinates count as the same point, in
     * metres. One micrometre: far below anything a building plan expresses,
     * far above the rounding noise of accumulating `Double` arithmetic.
     */
    const val LENGTH_METERS: Double = 1e-6

    /**
     * The area below which a polygon counts as degenerate, in square metres.
     * A polygon this thin has no interior to render, whatever its vertex count
     * says.
     */
    const val AREA_SQUARE_METERS: Double = 1e-9
}

/** Requires a coordinate to be a real number rather than `NaN` or an infinity. */
internal fun requireFiniteCoordinate(value: Double, field: String): Double {
    require(value.isFinite()) { "$field must be a finite number of metres, was $value" }
    return value
}

/** Requires a strictly positive length, for a height, a thickness or a span. */
internal fun requirePositiveLength(value: Double, field: String): Double {
    requireFiniteCoordinate(value, field)
    require(value > GeometryTolerance.LENGTH_METERS) {
        "$field must be a positive length in metres, was $value"
    }
    return value
}

/**
 * A point on the floor plan: a position in the XZ plane, in metres, with no
 * height of its own.
 *
 * Separate from [ModelPoint] on purpose. A wall centreline and a slab outline
 * are plan facts — their height comes from the primitive that owns them, not
 * from each vertex — and letting a plan outline carry per-vertex Y values would
 * invite outlines that are quietly not horizontal.
 */
data class PlanPoint(val x: Double, val z: Double) {
    init {
        requireFiniteCoordinate(x, "PlanPoint.x")
        requireFiniteCoordinate(z, "PlanPoint.z")
    }

    /** Horizontal distance to [other], in metres. */
    fun distanceTo(other: PlanPoint): Double = hypot(x - other.x, z - other.z)

    /** Whether [other] is the same plan position within [GeometryTolerance.LENGTH_METERS]. */
    fun coincidesWith(other: PlanPoint): Boolean =
        distanceTo(other) <= GeometryTolerance.LENGTH_METERS

    /** Lifts this plan position to [y] metres above the local origin. */
    fun at(y: Double): ModelPoint = ModelPoint(x = x, y = y, z = z)
}

/**
 * A point in the building's local 3D space, in metres, with Y up.
 *
 * Not an Android graphics type and not a renderer vector: this is model truth
 * that must stay usable on a plain JVM, without a display and without a
 * graphics library deciding its precision.
 */
data class ModelPoint(val x: Double, val y: Double, val z: Double) {
    init {
        requireFiniteCoordinate(x, "ModelPoint.x")
        requireFiniteCoordinate(y, "ModelPoint.y")
        requireFiniteCoordinate(z, "ModelPoint.z")
    }

    /** This point projected onto the floor plan, dropping its height. */
    fun onPlan(): PlanPoint = PlanPoint(x = x, z = z)

    /** Whether [other] is the same position within [GeometryTolerance.LENGTH_METERS]. */
    fun coincidesWith(other: ModelPoint): Boolean =
        abs(x - other.x) <= GeometryTolerance.LENGTH_METERS &&
            abs(y - other.y) <= GeometryTolerance.LENGTH_METERS &&
            abs(z - other.z) <= GeometryTolerance.LENGTH_METERS
}
