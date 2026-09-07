package com.buildplan.app.reference.visual

/**
 * The source-fidelity ledger for [MarcowkiVisualModelV1]: where the model's
 * numbers came from, when they were read, and which of them the source never
 * stated at all.
 *
 * # Why this is text and not an image
 *
 * The plans this model was traced from are ARCHON's copyrighted drawings. They
 * were fetched into a temporary directory outside the worktree, measured, and
 * left there. Nothing in this repository is a copy of them: what is recorded
 * here is the URL they live at, the moment they were read, and the numbers taken
 * from them — public facts about a public product page, not the drawings.
 *
 * # Why it lives next to the model rather than in a document
 *
 * A markdown file describing which numbers are traced drifts from the code the
 * first time a coordinate is corrected. This is compiled and tested: the
 * [displayAssumptions] list is asserted against the model, so an unclassified
 * assumption fails the build rather than quietly becoming folklore.
 */
object MarcowkiSourceEvidence {

    /** The exact page the owner will compare the rendered model against. */
    const val PAGE_URL: String =
        "https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca"

    /** The title that page carried when it was read. */
    const val PAGE_TITLE: String = "Dom w marcówkach (GE)"

    /** When the page and its drawings were read, in the machine's local time. */
    const val RETRIEVED_AT: String = "2026-09-07T17:20+02:00"

    /**
     * The plan and section images resolved from the page at [RETRIEVED_AT] and
     * traced for this model. Referenced, never copied.
     */
    const val GROUND_FLOOR_PLAN_URL: String =
        "https://assets.archon.pl/images/products/m2fa281446a8ca/" +
            "projekt-dom-w-marcowkach-ge-7af59bb86c46a8a68634e3630da6cd81__11815.gif"

    const val UPPER_FLOOR_PLAN_URL: String =
        "https://assets.archon.pl/images/products/m2fa281446a8ca/" +
            "projekt-dom-w-marcowkach-ge-9d1547b712997f93462fc19e9eefb963__11817.gif"

    const val CROSS_SECTION_URL: String =
        "https://assets.archon.pl/images/products/m2fa281446a8ca/" +
            "przekroj-budynku-projekt-dom-w-marcowkach-ge-" +
            "2bdfa17711fbe9d7bc173014765d5d51__256.jpg"

    /** What this model is, stated so that no reader has to infer it. */
    const val DISCLAIMER: String =
        "Visual trace for product validation — not construction documentation."

    /**
     * How the two plan rasters were turned into metres.
     *
     * Both plans are 853 x 853 and both resolve to the same scale, which is the
     * first thing that makes the trace trustworthy: on the ground plan the west
     * and east outer wall faces span the printed 1205 anchor in 455 px, and the
     * north and south faces span the printed 1260 anchor in 476 px — 37.76 and
     * 37.78 px/m, agreeing to within a quarter of a percent. On the upper plan
     * the outer faces span the same 7.89 m in the same 298 px, so both storeys
     * are traced in one coordinate frame and their walls line up by construction
     * rather than by adjustment.
     */
    const val CALIBRATION: String =
        "Both ARCHON plan rasters are 853x853 and calibrate to 37.77 px/m: 455 px " +
            "across the printed 1205 anchor, 476 px across the printed 1260 anchor. " +
            "Origin: the north-west outer wall corner of the main house."

    /**
     * Every number the source states outright and this model uses.
     *
     * The cross-checks these values pass are what raised the trace above
     * guesswork, and each is recorded in the relevant note: the stated building
     * height reconciles with the section levels, the stated roof area reconciles
     * with the pitch and the traced overhang, the stated footprint reconciles
     * with the two traced rectangles, and the printed plan anchors reconcile
     * with both rasters at one scale.
     */
    val exactValues: List<FidelityRecord> = listOf(
        FidelityRecord(
            name = "Szerokość budynku",
            value = "12.05 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Printed 1205 anchor on the ground-floor plan, spanning both outer " +
                "wall faces. Splits as the printed 790 (main house) and 415 (garage).",
        ),
        FidelityRecord(
            name = "Głębokość budynku",
            value = "12.60 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Printed 1260 anchor on the ground-floor plan, splitting as the " +
                "printed 510 (to the north wall of the garage) and 750 (the garage).",
        ),
        FidelityRecord(
            name = "Powierzchnia zabudowy",
            value = "131.16 m2",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Stated on the page. Cross-check: the two traced rectangles give " +
                "7.89 x 12.60 + 4.16 x 7.50 = 130.6 m2, 0.4 % under the stated figure, " +
                "the balance being the entrance step the model does not draw.",
        ),
        FidelityRecord(
            name = "Powierzchnia dachu",
            value = "150.57 m2",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Stated on the page, and the anchor that settled the ridge direction. " +
                "A ridge running north-south over the 7.89 m width, at 40 degrees, with " +
                "the traced 1.00 m gable overhang and no eaves overhang, gives 150.4 m2.",
        ),
        FidelityRecord(
            name = "Wysokość budynku",
            value = "8.27 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Stated on the page. Cross-check: a ridge at +7.95 above a terrain " +
                "level of -0.32, both taken from the section, is exactly 8.27 m — which " +
                "is what ties the page scalars to the section levels.",
        ),
        FidelityRecord(
            name = "Kąt nachylenia dachu",
            value = "40 stopni",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Stated on the page and annotated on the left slope of the section.",
        ),
        FidelityRecord(
            name = "Ścianka kolankowa",
            value = "1.30 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Stated on the page and dimensioned 130 on the section, above the " +
                "+3.06 attic floor level.",
        ),
        FidelityRecord(
            name = "Poziom parteru",
            value = "0.00 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Levelled on the section; the Y origin of the model.",
        ),
        FidelityRecord(
            name = "Wysokość pomieszczeń parteru",
            value = "2.72 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Dimensioned 272 on the section, between 0.00 and the ceiling.",
        ),
        FidelityRecord(
            name = "Poziom poddasza",
            value = "+3.06 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Levelled on the section. With the 272 clear height it fixes the floor " +
                "structure at 0.34 m, which the model uses rather than assumes.",
        ),
        FidelityRecord(
            name = "Wysokość poddasza",
            value = "2.66 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Dimensioned 266 on the section; the height of the attic partitions.",
        ),
        FidelityRecord(
            name = "Kalenica",
            value = "+7.95 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Levelled at the apex of the section.",
        ),
        FidelityRecord(
            name = "Okap",
            value = "+4.67 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Levelled on the section at the outer wall face. The model derives its " +
                "own eaves from the exact ridge and the exact 40 degrees and lands at " +
                "+4.64; the 0.03 m difference is the rounding in the published pitch, and " +
                "the model keeps the pitch rather than the level.",
        ),
        FidelityRecord(
            name = "Poziom terenu",
            value = "-0.32 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Levelled on the section outside the wall.",
        ),
        FidelityRecord(
            name = "Wysokość garażu",
            value = "2.52 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Dimensioned 252 in the single-storey volume on the right of the section.",
        ),
        FidelityRecord(
            name = "Wymiary garażu",
            value = "3.75 x 6.60 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Printed 375 and 660 clear dimensions inside room 9 on the ground plan.",
        ),
        FidelityRecord(
            name = "Salon z jadalnią",
            value = "7.00 x 4.14 m",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Printed 700 and 414 on the ground plan. The 7.00 m is what confirms " +
                "the traced 0.44 m exterior wall across the 7.89 m outer width.",
        ),
        FidelityRecord(
            name = "Wymiary pozostałych pomieszczeń parteru",
            value = "142, 190, 196, 203, 265, 290, 312, 325 cm",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Printed room dimensions on the ground plan, used to place the " +
                "partitions around the bathroom, bedroom, vestibule and boiler room.",
        ),
        FidelityRecord(
            name = "Wymiary pomieszczeń poddasza",
            value = "202, 235, 248, 290, 325, 344, 398, 449 cm",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Printed room dimensions on the upper-floor plan, used to place its " +
                "partitions.",
        ),
    )

    /**
     * Every number the source does not state, that exists only so the model can
     * be drawn.
     *
     * Kept short on purpose. Each entry is a place where a renderer needed a
     * value and the published material had none; anything that could be traced
     * instead was traced.
     */
    val displayAssumptions: List<FidelityRecord> = listOf(
        FidelityRecord(
            name = "Grubość płyty fundamentowej",
            value = "0.32 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The source gives no foundation. The model draws a plate between the " +
                "terrain level of the section and its 0.00, so the building stands on " +
                "something instead of floating; the thickness is therefore the gap " +
                "between two source levels, not a structural depth.",
        ),
        FidelityRecord(
            name = "Grubość stropodachu garażu",
            value = "0.20 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The source dimensions the clear height of the garage but not its roof " +
                "build-up. 0.20 m is chosen only so the garage roof reaches the top of " +
                "the ground-floor walls and closes the volume without a parapet.",
        ),
        FidelityRecord(
            name = "Ściana okapowa poddasza rysowana do połaci",
            value = "1.58 m zamiast 1.30 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The published knee wall is 1.30 m, putting its top at +4.36 while the " +
                "roof plane meets the outer face at +4.64. Drawing the wall to 1.30 m " +
                "would leave a 0.28 m slot running the length of both eaves. The model " +
                "builds the wall up to the roof plane instead; the published knee height " +
                "stays a fact and is asserted, not overwritten.",
        ),
        FidelityRecord(
            name = "Granica kuchni i holu",
            value = "x = 3.48 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The kitchen, the living room and the hall are one open-plan space with " +
                "no wall between them, so the source draws no boundary to trace. The zone " +
                "split is a reading of the furniture and room numbering on the plan, and " +
                "both zones are marked TRACE_UNCERTAIN because of it.",
        ),
    )

    /**
     * Why every traced attic room zone is larger than its published area.
     *
     * Not a defect and not uncertainty: ARCHON states attic rooms at their
     * usable area, which excludes the strip under the roof slope where the
     * ceiling is too low to count. A traced floor polygon is the whole floor, so
     * it is systematically larger — by 20 to 25 % here, which is about what a 40
     * degree roof over a 1.30 m knee wall takes away. Reconciling the two would
     * mean inventing a usable-area rule the source never published.
     */
    const val ATTIC_AREA_NOTE: String =
        "Attic rooms are published as usable area, which excludes the low strip under " +
            "the roof slope. Traced floor polygons are the full floor and are therefore " +
            "20-25 % larger; the difference is the slope, not a trace error."

    /** Everything classified, in one list, for counting and for the debug panel. */
    val allRecords: List<FidelityRecord> get() = exactValues + displayAssumptions

    /**
     * How many classified data of one kind the model rests on.
     *
     * [SourceFidelity.SOURCE_TRACED] is counted from the model itself rather
     * than from a hand-written list: every traced coordinate is a grid line, and
     * a list of them beside the grid would be a second copy to keep in step.
     */
    fun countOf(fidelity: SourceFidelity): Int = when (fidelity) {
        SourceFidelity.SOURCE_TRACED -> MarcowkiVisualModelV1.TRACED_GRID_LINE_COUNT
        else -> allRecords.count { it.fidelity == fidelity }
    }
}
