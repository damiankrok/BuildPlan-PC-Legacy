package com.buildplan.app.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * The axis-aligned box, in the building's local metres, that contains something.
 *
 * This is geometry-local data and nothing else. It is *not* a camera, a viewport
 * or a frustum: a later renderer will use it to decide where to point a camera,
 * but which camera, at what distance and with what projection are its decisions,
 * not the model's.
 */
data class LocalBounds(val min: ModelPoint, val max: ModelPoint) {
    init {
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) {
            "LocalBounds min must not exceed max, was $min .. $max"
        }
    }

    val sizeX: Double get() = max.x - min.x
    val sizeY: Double get() = max.y - min.y
    val sizeZ: Double get() = max.z - min.z

    /** The centre of the box, the natural point for a renderer to frame on. */
    val center: ModelPoint
        get() = ModelPoint(
            x = (min.x + max.x) / 2.0,
            y = (min.y + max.y) / 2.0,
            z = (min.z + max.z) / 2.0,
        )

    /** The smallest box containing both this one and [other]. */
    fun encompass(other: LocalBounds): LocalBounds = LocalBounds(
        min = ModelPoint(
            x = min(min.x, other.min.x),
            y = min(min.y, other.min.y),
            z = min(min.z, other.min.z),
        ),
        max = ModelPoint(
            x = max(max.x, other.max.x),
            y = max(max.y, other.max.y),
            z = max(max.z, other.max.z),
        ),
    )

    companion object {

        /**
         * The smallest box containing every point of [points].
         *
         * @throws IllegalArgumentException if [points] is empty: there is no
         *   honest box around nothing, and returning a zero box at the origin
         *   would put a phantom point into every union it took part in.
         */
        fun around(points: Iterable<ModelPoint>): LocalBounds {
            val iterator = points.iterator()
            require(iterator.hasNext()) { "LocalBounds needs at least one point" }

            val first = iterator.next()
            var minX = first.x
            var minY = first.y
            var minZ = first.z
            var maxX = first.x
            var maxY = first.y
            var maxZ = first.z

            iterator.forEach { point ->
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                minZ = min(minZ, point.z)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
                maxZ = max(maxZ, point.z)
            }

            return LocalBounds(
                min = ModelPoint(minX, minY, minZ),
                max = ModelPoint(maxX, maxY, maxZ),
            )
        }

        /** The smallest box containing [plan] extruded between [bottomY] and [topY]. */
        fun aroundExtrusion(
            plan: Iterable<PlanPoint>,
            bottomY: Double,
            topY: Double,
        ): LocalBounds = around(
            plan.flatMap { point -> listOf(point.at(min(bottomY, topY)), point.at(max(bottomY, topY))) },
        )
    }
}
