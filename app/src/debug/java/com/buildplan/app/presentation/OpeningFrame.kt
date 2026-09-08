package com.buildplan.app.presentation

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.geometry.GeometryTolerance

/**
 * How the pane filling an opening is *framed* when it is drawn: a bar around
 * its edge and, for a wide glazing, the mullions that split it into leaves.
 *
 * ## Every number is a display assumption
 *
 * The plans schedule an opening's width and height and nothing else. A frame
 * section, a leaf width and a mullion are joinery the source never states, so
 * a spec lives beside the reference model as its glass roles and roof cover
 * do — a map by element id — and its values are recorded as
 * `DISPLAY_ASSUMPTION` in the model's ledger. The canonical pane, hole and
 * wall are byte-for-byte what they would be without it.
 *
 * ## Why it exists
 *
 * A bare transparent sheet in a reveal reads as a hole from any distance a
 * phone is held at: the owner's verdict on STAGE-013F was that the glazed
 * openings looked like openings with nothing in them. What makes a hole read
 * as a window is its frame — the one line every elevation draws round every
 * pane — and the leaves a wide glazing is divided into. That is the whole of
 * what is added: bars and mullions, monochrome, opaque, the study's own
 * surface colour.
 *
 * ## Units
 *
 * Metres, like everything in `geometry/`. [barDepth] is measured along the
 * pane's normal and centred on the pane, so the frame sits inside the reveal
 * on both faces of the wall.
 *
 * @property barWidth the width of the frame bar in the pane's own plane, and
 *   of each mullion.
 * @property barDepth how thick the bar is through the pane's plane.
 * @property maxLeafWidth the widest a leaf may be before a mullion splits it;
 *   null for an opening that is framed but never divided — a gate, a door.
 */
data class OpeningFrameSpec(
    val barWidth: Double,
    val barDepth: Double,
    val maxLeafWidth: Double?,
) {
    init {
        requirePositive(barWidth, "barWidth")
        requirePositive(barDepth, "barDepth")
        if (maxLeafWidth != null) {
            requirePositive(maxLeafWidth, "maxLeafWidth")
            require(maxLeafWidth > barWidth) {
                "OpeningFrameSpec maxLeafWidth $maxLeafWidth must exceed barWidth $barWidth, or every leaf is a bar"
            }
        }
    }

    private fun requirePositive(value: Double, field: String) {
        require(value.isFinite() && value > GeometryTolerance.LENGTH_METERS) {
            "OpeningFrameSpec $field must be a positive length in metres, was $value"
        }
    }
}

/**
 * Which elements have their panes framed, and how.
 *
 * ## Where it sits
 *
 * Beside a reference model, keyed by [BuildingElementId], exactly as
 * [DecompositionProfile], the glass roles and [RoofCoverProfile] are. Applied
 * by the renderer's adapter *after* the geometry layer has produced the panes,
 * reading nothing but those panes and this map.
 *
 * ## What it does not do
 *
 * It does not decide visibility: a frame carries its opening's own id and
 * goes in and out of the scene with it through the one id set the renderer is
 * handed. It does not make a frame an element: a tap on a bar answers with
 * the window. It does not reach into the wall — the hole, the reveal and the
 * pane are the geometry's, untouched.
 *
 * An element with no entry is drawn as it always was: a pane and nothing
 * round it. That is deliberately what an internal door stays — a hole with a
 * leaf, no frame, no handle — and what the synthetic fixture stays throughout.
 */
class OpeningFrameProfile(frames: Map<BuildingElementId, OpeningFrameSpec>) {

    /** The frame of each element that has one, in declaration order. */
    val frames: Map<BuildingElementId, OpeningFrameSpec> = LinkedHashMap(frames)

    /** The elements whose panes are framed, in declaration order. */
    val framedElementIds: Set<BuildingElementId> get() = frames.keys

    /** The frame [elementId]'s panes are drawn with, or null for a bare pane. */
    fun specFor(elementId: BuildingElementId): OpeningFrameSpec? = frames[elementId]

    companion object {
        /** No frame anywhere: every pane is a bare sheet. */
        val NONE: OpeningFrameProfile = OpeningFrameProfile(emptyMap())
    }
}
