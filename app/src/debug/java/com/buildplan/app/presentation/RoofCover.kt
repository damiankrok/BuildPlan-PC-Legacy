package com.buildplan.app.presentation

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.geometry.GeometryTolerance
import com.buildplan.app.geometry.LocalBounds
import com.buildplan.app.geometry.PlanPoint

/**
 * How a roof's covering is *shown*, when it is shown as anything more than the
 * facet's own plane.
 *
 * One style has a real implementation. The type exists so that a second one —
 * flat tile, shingle, standing-seam sheet — has somewhere to go without the
 * generator being rewritten, not because any of those exist. A roof with no
 * visible cover needs no style: it simply has no entry in a [RoofCoverProfile].
 *
 * This is presentation vocabulary and nothing else. It is not a roofing
 * taxonomy, not a material, not a cost line and not a
 * [com.buildplan.app.domain.model.BuildingElementKind]: the canonical roof is
 * still one `ROOF` element with planar facets, and the covering is a way of
 * drawing those facets.
 */
enum class RoofCoverStyle {
    /**
     * Overlapping cambered patches with a rounded tail, laid in staggered
     * courses from the eave to the ridge: the scale-like read of a tiled roof,
     * reduced to the few vertices a study model can afford.
     */
    CURVED_TILE,
}

/**
 * The module one covering repeats: how big a tile is, how much of it shows,
 * and how far it stands off the roof plane.
 *
 * ## Every number is a display assumption
 *
 * None of these is a fact about the building. A published roof gives an area
 * and a pitch; it does not give the gauge its tiles are laid to, and a model
 * that pretended to know would be inventing a construction detail. So a spec
 * is kept beside the reference model in the same way its glass roles and its
 * decomposition groups are — a map by element id — and the values are recorded
 * as `DISPLAY_ASSUMPTION` where the model keeps its ledger.
 *
 * ## Units
 *
 * Metres, like everything in `geometry/`. The offsets are measured along the
 * facet's own normal, so a tile on a 40 degree slope stands off it by exactly
 * what a tile on a flat roof would.
 *
 * @property moduleWidth across the slope: the tile's full width, and the
 *   spacing of tiles within one course.
 * @property moduleLength up the slope: the tile's full length, of which only
 *   [exposure] is seen because the course above laps the rest.
 * @property exposure the visible length of each course, and therefore the row
 *   pitch from eave to ridge. Must be less than [moduleLength], or nothing laps.
 * @property tailRound how far the tail's corners are drawn back to round it.
 *   Must stay under the lap, or the course below shows through the corner.
 * @property camber how high the tile's middle rises above its two side edges:
 *   the curve that catches the light.
 * @property lift how far the tile's head — its up-slope end — stands off the
 *   roof plane. Small but never zero: a tile in the roof's own plane fights it
 *   for the depth buffer.
 * @property step how much higher than the head the tail stands, because the
 *   tail rests on the course below. It is what keeps a tile's tail clear of the
 *   head it laps, and what makes the courses read as steps rather than a print.
 * @property minimumWidthFraction the fraction of [moduleWidth] a tile trimmed
 *   at a facet edge must keep to be laid at all; a narrower sliver is dropped.
 */
data class RoofCoverSpec(
    val style: RoofCoverStyle,
    val moduleWidth: Double,
    val moduleLength: Double,
    val exposure: Double,
    val tailRound: Double,
    val camber: Double,
    val lift: Double,
    val step: Double,
    val minimumWidthFraction: Double = DEFAULT_MINIMUM_WIDTH_FRACTION,
) {
    init {
        requirePositive(moduleWidth, "moduleWidth")
        requirePositive(moduleLength, "moduleLength")
        requirePositive(exposure, "exposure")
        requirePositive(lift, "lift")
        requireNonNegative(tailRound, "tailRound")
        requireNonNegative(camber, "camber")
        requireNonNegative(step, "step")
        require(exposure < moduleLength) {
            "RoofCoverSpec exposure $exposure must be less than moduleLength $moduleLength, or no course laps the next"
        }
        require(tailRound < moduleLength - exposure + GeometryTolerance.LENGTH_METERS) {
            "RoofCoverSpec tailRound $tailRound reaches past the lap of ${moduleLength - exposure}"
        }
        require(minimumWidthFraction > 0.0 && minimumWidthFraction <= 1.0) {
            "RoofCoverSpec minimumWidthFraction must be in (0, 1], was $minimumWidthFraction"
        }
    }

    /** How much of each course the next one laps, up the slope. */
    val lap: Double get() = moduleLength - exposure

    /** The furthest any point of a tile stands off the roof plane. */
    val maximumOffset: Double get() = lift + step + camber

    private fun requirePositive(value: Double, field: String) {
        require(value.isFinite() && value > GeometryTolerance.LENGTH_METERS) {
            "RoofCoverSpec $field must be a positive length in metres, was $value"
        }
    }

    private fun requireNonNegative(value: Double, field: String) {
        require(value.isFinite() && value >= 0.0) {
            "RoofCoverSpec $field must be a finite non-negative length in metres, was $value"
        }
    }

    companion object {
        const val DEFAULT_MINIMUM_WIDTH_FRACTION: Double = 0.25
    }
}

/**
 * A region of the plan that no tile may cross: the footprint of a stack, a
 * rooflight, or anything else that comes through the roof.
 *
 * ## Why a plan polygon
 *
 * The things that interrupt a roof covering are known by their plan position —
 * a stack is traced as a rectangle on the plan, a rooflight is dashed onto the
 * upper plan — and a facet's tiles have a plan footprint of their own, so the
 * question "does this tile run into the stack" is a question on the plan. It
 * is also the one form that does not depend on which facet is being covered.
 *
 * ## Derived, not restated
 *
 * A blocker is built from the bounds of geometry the model already has, by
 * element id, so that the stack the tiles avoid is the stack that is drawn.
 * Writing the footprints out a second time here would be the second copy of a
 * coordinate that `MarcowkiPlanGrid` exists to prevent.
 *
 * The outline must be convex, walked in either direction. Every footprint the
 * model produces is a rectangle; a concave blocker would be tested against
 * its hull, which blocks too much rather than too little.
 */
class RoofCoverBlocker(outline: List<PlanPoint>) {

    val outline: List<PlanPoint> = outline.toList()

    init {
        require(this.outline.size >= 3) { "RoofCoverBlocker needs at least three corners, got ${outline.size}" }
    }

    companion object {

        /**
         * The plan footprint of [bounds], grown by [margin] on every side so
         * that a tile stops short of the object rather than touching it.
         */
        fun aroundPlan(bounds: LocalBounds, margin: Double): RoofCoverBlocker {
            require(margin.isFinite() && margin >= 0.0) { "RoofCoverBlocker margin must be non-negative, was $margin" }
            return RoofCoverBlocker(
                listOf(
                    PlanPoint(bounds.min.x - margin, bounds.min.z - margin),
                    PlanPoint(bounds.max.x + margin, bounds.min.z - margin),
                    PlanPoint(bounds.max.x + margin, bounds.max.z + margin),
                    PlanPoint(bounds.min.x - margin, bounds.max.z + margin),
                ),
            )
        }
    }
}

/**
 * Which elements are drawn with a covering, and what the covering must avoid.
 *
 * ## Where it sits
 *
 * Beside a reference model, keyed by [BuildingElementId], exactly as
 * [DecompositionProfile] and the glass roles are. Applied by the renderer's
 * adapter *after* the geometry layer has produced the facets, and reading
 * nothing but the facets and this map: the canonical [com.buildplan.app.domain.model.Building]
 * and [com.buildplan.app.geometry.BuildingGeometry] are byte-for-byte what they
 * would be without it.
 *
 * ## What it does not do
 *
 * It does not decide visibility. A covering is drawn for a facet and hides
 * with the facet's element, through the one id set the renderer is handed;
 * there is no second rule here about roofs. It does not make a tile an
 * element either: a tap on the covering answers with the roof, because the
 * covering carries the roof's id and nothing of its own.
 *
 * Source-neutral: nothing here names a house. The synthetic fixture declares
 * [NONE] and stays the plain planes it always was.
 */
class RoofCoverProfile(
    covers: Map<BuildingElementId, RoofCoverSpec>,
    /** Plan regions no tile of any covered facet may cross. */
    val blockers: List<RoofCoverBlocker> = emptyList(),
) {

    /** The covering of each element that has one, in declaration order. */
    val covers: Map<BuildingElementId, RoofCoverSpec> = LinkedHashMap(covers)

    /** The elements drawn with a covering, in declaration order. */
    val coveredElementIds: Set<BuildingElementId> get() = covers.keys

    /** The covering [elementId] is drawn with, or null for the plain plane. */
    fun specFor(elementId: BuildingElementId): RoofCoverSpec? = covers[elementId]

    companion object {
        /** No covering anywhere: every facet is its own plane. */
        val NONE: RoofCoverProfile = RoofCoverProfile(emptyMap())
    }
}
