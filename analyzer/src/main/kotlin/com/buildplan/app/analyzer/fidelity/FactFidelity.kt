package com.buildplan.app.analyzer.fidelity

/**
 * How far a value produced by the analyzer can be trusted, and why.
 *
 * Every number the analyzer emits — a published area, a traced wall position,
 * a derived ridge height, an assumed slab thickness — carries one of these.
 * The point is that they are *not* interchangeable: once a traced partition
 * and a published pitch are both `Double`s in one snapshot, only the label
 * says which one an architect stated and which one a pixel count implied.
 *
 * The vocabulary extends the one the hand-traced reference model already uses
 * (`SourceFidelity` in the debug sources) with the states an automatic
 * pipeline needs: derivation, absence and disagreement.
 */
enum class FactFidelity {

    /** Printed verbatim by the current source: a page scalar, a table cell, a schedule label. */
    SOURCE_EXACT,

    /** Measured off a source raster after calibrating it against source-stated anchors. */
    SOURCE_TRACED,

    /**
     * Computed from [SOURCE_EXACT] and/or [SOURCE_TRACED] inputs by a stated
     * rule — a scale from a published footprint area, a ridge level from a
     * published height and pitch. Inherits the weakest input.
     */
    SOURCE_DERIVED,

    /** Confirmed or supplied by the user. Schema-ready; nothing in this stage produces it. */
    USER_CONFIRMED,

    /**
     * Not published and not measurable; present only so that a surface can be
     * drawn or a quantity closed. Always paired with a reason and, when it
     * matters, with a clarification question.
     */
    DISPLAY_ASSUMPTION,

    /** Measured, but the source does not resolve it: an open-plan boundary, a smeared hatch. */
    TRACE_UNCERTAIN,

    /** Needed and not available from any source the analyzer read. */
    MISSING,

    /** Two sources disagree beyond tolerance, and nothing says which one wins. */
    CONFLICTING,
    ;

    /** Whether a value of this fidelity may be used as a numeric input at all. */
    val hasValue: Boolean get() = this != MISSING

    companion object {
        /**
         * The fidelity a derived value inherits from its inputs: the weakest of
         * them, in the order a reader would rank them. [MISSING] anywhere makes
         * the result missing; [CONFLICTING] anywhere makes it conflicting.
         */
        fun weakest(inputs: Iterable<FactFidelity>): FactFidelity {
            val list = inputs.toList()
            if (list.isEmpty()) return MISSING
            return list.maxBy { rank(it) }
        }

        private fun rank(fidelity: FactFidelity): Int = when (fidelity) {
            USER_CONFIRMED -> 0
            SOURCE_EXACT -> 1
            SOURCE_TRACED -> 2
            SOURCE_DERIVED -> 3
            DISPLAY_ASSUMPTION -> 4
            TRACE_UNCERTAIN -> 5
            CONFLICTING -> 6
            MISSING -> 7
        }
    }
}
