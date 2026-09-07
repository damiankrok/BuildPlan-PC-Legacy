package com.buildplan.app.reference.visual

/**
 * How one number in the Marcówki visual model relates to the published source.
 *
 * Every non-trivial datum in [MarcowkiVisualModelV1] carries one of these, and
 * the point of the distinction is that they are *not* interchangeable. A traced
 * partition position and a published roof pitch look identical once they are
 * both `Double`s in the same file, and the model would then quietly present a
 * measurement taken off a 853-pixel product image as though the architect had
 * stated it. Recording which is which is what keeps a validation model from
 * becoming a fake construction document.
 */
enum class SourceFidelity {

    /**
     * Printed as a number in the current source: a stated parameter on the
     * product page, or an annotated dimension on the current plan or section.
     *
     * The strongest claim the model makes. "40 degrees" and "1205" are of this
     * kind; so is a level marked on the cross-section.
     */
    SOURCE_EXACT,

    /**
     * Measured off the current source image after calibrating that image
     * against [SOURCE_EXACT] dimension anchors.
     *
     * Honest but approximate: it inherits the drawing's own line weights and the
     * raster's pixel size. Partition positions, room-zone boundaries and wall
     * thicknesses are of this kind.
     */
    SOURCE_TRACED,

    /**
     * Not published at all, and present only so that a surface can be drawn.
     *
     * A slab has to have *some* thickness before a renderer can extrude it, and
     * a massing model has to close its gable before it reads as a house. Such a
     * number is a rendering decision wearing a metre unit, and must never be
     * read as engineering truth — which is exactly why it is enumerated one by
     * one in [MarcowkiSourceEvidence.displayAssumptions] rather than hidden in
     * the model.
     */
    DISPLAY_ASSUMPTION,
}

/**
 * One classified datum used by the visual model, with the reason it carries the
 * classification it does.
 *
 * [value] is text rather than a number because these records are evidence for a
 * human reviewer, and a level, an angle, an area and a pixel anchor do not share
 * a unit. The model itself reads its constants from
 * [MarcowkiVisualModelV1]; this is the ledger beside them.
 */
data class FidelityRecord(
    val name: String,
    val value: String,
    val fidelity: SourceFidelity,
    val note: String,
) {
    init {
        require(name.isNotBlank()) { "FidelityRecord name must not be blank" }
        require(value.isNotBlank()) { "FidelityRecord value must not be blank" }
        require(note.isNotBlank()) {
            "FidelityRecord $name must say why it carries fidelity $fidelity"
        }
    }
}
