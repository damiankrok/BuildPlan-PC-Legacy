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

    /**
     * The four elevations, and when they were measured for the facade bands.
     *
     * A second reading rather than a correction to [RETRIEVED_AT]: the plans and
     * the section still say what they said, and moving that timestamp would
     * claim the whole trace had been redone. What was read this time is the
     * banding, which only the elevations show.
     */
    const val ELEVATIONS_RETRIEVED_AT: String = "2026-09-08T00:13+02:00"

    const val FRONT_ELEVATION_URL: String =
        "https://assets.archon.pl/images/products/m2fa281446a8ca/" +
            "elewacja-frontowa-projekt-dom-w-marcowkach-ge-" +
            "b165fc1dadc0b7ef46c3d1d74725aea3__264.jpg"

    const val GARDEN_ELEVATION_URL: String =
        "https://assets.archon.pl/images/products/m2fa281446a8ca/" +
            "elewacja-ogrodowa-projekt-dom-w-marcowkach-ge-" +
            "9b95a143134b5e1afdf79034a2391bd8__267.jpg"

    const val SIDE_ELEVATION_WEST_URL: String =
        "https://assets.archon.pl/images/products/m2fa281446a8ca/" +
            "elewacja-boczna-projekt-dom-w-marcowkach-ge-" +
            "b72318402d5f7c263303f0bf13597c8b__265.jpg"

    const val SIDE_ELEVATION_EAST_URL: String =
        "https://assets.archon.pl/images/products/m2fa281446a8ca/" +
            "elewacja-boczna-projekt-dom-w-marcowkach-ge-" +
            "24130d2bfa16ddf40cd1a16f8e154ce2__266.jpg"

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
     * How the elevations were turned into metres, for the facade bands.
     *
     * The elevations are a fifth the resolution of the plans, so they are used
     * for *what is there and at what level*, and levels are then read off the
     * section rather than off the raster. Both bands in the model take their top
     * from a stated level for exactly that reason.
     */
    const val ELEVATION_CALIBRATION: String =
        "The four ARCHON elevations are 550x256 and calibrate to 25.38 px/m: the " +
            "whole building's 12.05 m spans 305.8 px on the front elevation, which " +
            "puts the main house's east face within 0.6 px of its traced 7.89 m. At " +
            "that scale one pixel is 4 cm, so the elevations are read for what exists " +
            "and at which stated level, never for a dimension of their own."

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
        FidelityRecord(
            name = "Stolarka parteru",
            value = "470/230, 300/230, 110/230, 105/210, 100/210, 90/230, 275/225, 140/140",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "The circled width/height schedule on the ground-floor plan. Every one " +
                "of them was cross-checked against the gap it labels in the wall hatch, " +
                "and all eight agree to within 2 cm — which is what says the schedule and " +
                "the drawing are describing the same openings.",
        ),
        FidelityRecord(
            name = "Przeszklenia szczytów",
            value = "2 x 234/303 (szczyt północny), 270/320 (szczyt południowy)",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "The circled schedule on the upper-floor plan. Their heights are the " +
                "maximum each reaches, not a constant: a 303 opening standing where the " +
                "west one starts would need 2.38 m of roof and there is only 2.38 m at " +
                "that point, so the heads follow the slope and reach 303 nearer the ridge.",
        ),
        FidelityRecord(
            name = "Okna połaciowe",
            value = "3 x 78/118",
            fidelity = SourceFidelity.SOURCE_EXACT,
            note = "Labelled on the upper-floor plan and dashed in over rooms 4, 5 and the " +
                "stairwell. The 78 cm width was confirmed against the dashed outlines, " +
                "which trace 0.79 m across and 0.85 m up the slope — 118 cm foreshortened " +
                "by a 40 degree pitch is 0.90 m, so the two agree.",
        ),
    )

    /**
     * Numbers measured off the drawings that place something the schedule does
     * not dimension.
     *
     * These are [SourceFidelity.SOURCE_TRACED] and are listed rather than
     * counted, unlike the grid lines, because each is a claim about *where a
     * thing is* rather than a coordinate the model would obviously need.
     */
    val tracedFeatures: List<FidelityRecord> = listOf(
        FidelityRecord(
            name = "Podcień szczytowy",
            value = "1.00 m",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "On both plans and on both storeys the west and east wall hatch runs " +
                "38 px past the north outer face and 36 px past the south one — 1.00 m " +
                "and 0.95 m at the calibrated scale, and the same figure as the traced " +
                "gable overhang. So each gable is a portal with two solid cheeks rather " +
                "than a bare roof projection, which is the trait the first model missed.",
        ),
        FidelityRecord(
            name = "Balkony w podcieniach",
            value = "0.98 m głębokości",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "The upper-floor plan stipples the portal floor between the cheeks and " +
                "draws a single line along its outer edge. The two 234/303 doors open " +
                "onto the north one and the 270/320 onto the south one.",
        ),
        FidelityRecord(
            name = "Klatka schodowa",
            value = "5.35-7.47 x 5.18-8.80 m, rdzeń 5.35-6.46 x 6.18-7.77 m",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "Three flights turning twice around a rectangular core, traced from " +
                "both plans: the flight the ground plan draws across the south of the " +
                "stairwell lands within 5 cm of the one the upper plan draws there. The " +
                "direction is fixed by the upper plan's arrow, which points west off the " +
                "top flight into the attic corridor.",
        ),
        FidelityRecord(
            name = "Rama podcienia szczytowego",
            value = "0.44 m (elewacje mierzą 0.66 m)",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "The front and garden elevations frame each gable with one band of " +
                "constant width: up both cheeks and along both slopes, with the facade a " +
                "metre behind it. Measured at 16.9 px on the front elevation, which " +
                "calibrates at 25.38 px/m against the printed 1205 across the whole " +
                "building — 0.66 m, the wall with its render and insulation. The model " +
                "carries the 0.44 m the plan hatch gives, because the frame is the cheek " +
                "carried up over the roof and a frame drawn wider than the cheek it " +
                "continues would put a step in a line the source draws straight.",
        ),
        FidelityRecord(
            name = "Opaska międzykondygnacyjna",
            value = "+3.06 m, 0.54 m wysokości",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "One unbroken horizontal line across all four elevations: the front " +
                "edge of the balcony in each gable portal and the top of the garage, at " +
                "one level. Its top traces 3.06 to 3.08 m above the drawn ground line, " +
                "which is the attic floor the section levels. Its depth traces 0.47 m on " +
                "the garden elevation and 0.71 m on the front one; the model uses the " +
                "0.54 m between the garage's dimensioned 252 and that +3.06, so both ends " +
                "of the band are stated levels rather than pixels.",
        ),
        FidelityRecord(
            name = "Wysokość garażu w bryle",
            value = "góra na +3.06 m",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "On the front elevation the band over the garage and the band over the " +
                "house entrance are the same run of pixels — rows 169 to 186 at both " +
                "x = 300 and x = 430 — so the garage top and the house balcony are one " +
                "level. The first model stopped the garage at the ground-floor ceiling, " +
                "0.34 m lower, which broke the only line tying the two masses together.",
        ),
        FidelityRecord(
            name = "Drzwi z garażu do kotłowni",
            value = "0.90 m szerokości",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "The one opening the schedule does not label, because internal doors " +
                "are not scheduled on this plan. Its width is the gap in the wall hatch; " +
                "its 2.10 m head is taken from the labelled doors beside it and is " +
                "recorded as an assumption below.",
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
        FidelityRecord(
            name = "Parapet okna kuchni",
            value = "0.90 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The schedule gives width and height, never a sill. Eight of the nine " +
                "ground-floor openings reach the floor and their sill is therefore stated " +
                "by their own height; the 140/140 kitchen window does not, so its head is " +
                "aligned with the 2.30 line the five 230-high openings print and its sill " +
                "falls out of that. The side elevation shows a sill at about this height.",
        ),
        FidelityRecord(
            name = "Nadproże drzwi garaż–kotłownia",
            value = "2.10 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The only unlabelled opening in the model. Its width is traced; its " +
                "head is borrowed from the 105/210 and 100/210 doors either side of it, " +
                "because a door needs some head and no other number is available.",
        ),
        FidelityRecord(
            name = "Balustrada balkonów",
            value = "1.10 x 0.08 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The plan draws the balcony edge as a single line with no height " +
                "against it. 1.10 m is the usual guarding height, and without something " +
                "there the portal reads as a hole in the gable rather than a balcony.",
        ),
        FidelityRecord(
            name = "Podesty w podcieniach",
            value = "płyta do -0.32 m pod podcieniami",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The portal cheeks are traced but nothing under them is. The foundation " +
                "plate is carried beneath both portals so they stand on something instead " +
                "of ending in mid-air above the terrain; it is paving, not built area, and " +
                "the stated 131.16 m2 footprint is unaffected by it.",
        ),
        FidelityRecord(
            name = "Zadaszenie przed garażem",
            value = "1.00 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The ground plan draws the garage's east wall one portal depth past the " +
                "building line and dimensions nothing above it. The flat roof is carried " +
                "over that metre so the wall is a sheltered entrance rather than a " +
                "free-standing fin.",
        ),
        FidelityRecord(
            name = "Balkon południowy na pełnej szerokości",
            value = "7.89 m zamiast 3.81 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The upper plan stipples the south portal floor only east of x = 3.39, " +
                "and the front elevation agrees within 0.11 m: its band starts at " +
                "x = 3.28 and the entrance beside it is open two storeys. The model " +
                "floors the whole width anyway, because the alternative leaves a 0.34 m " +
                "slot in the portal cheeks where the storey slab would have closed them, " +
                "and closing that slot needs a piece the source does not draw either.",
        ),
        FidelityRecord(
            name = "Liczba stopni",
            value = "17 stopni po 0.18 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The plans draw the treads but the watermark crosses them, so the count " +
                "is derived from the two exact levels the section gives instead: 3.06 m of " +
                "rise divided into seventeen puts each riser at 0.18 m. The three flights " +
                "and their direction are traced; only the subdivision is chosen.",
        ),
        FidelityRecord(
            name = "Okna połaciowe rysowane na połaci",
            value = "0.08 m nad płaszczyzną dachu",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "Their positions are traced, but this stage does not cut holes in a roof " +
                "facet, so each is drawn as a panel lying proud of the slope. It reads as a " +
                "rooflight from outside and as a panel floating over the attic from inside; " +
                "the second is the honest cost of not having cut the hole.",
        ),
    )

    /**
     * What the source shows and this model does not draw.
     *
     * Recorded rather than left out silently. An owner comparing the model with
     * the published renders will notice these, and the useful answer is "known,
     * and here is why" rather than a second review round discovering them.
     */
    val notModelled: List<String> = listOf(
        "Komin. Both published renders show one on the ridge, but neither plan " +
            "draws a shaft that can be told apart from the wardrobes hatched the " +
            "same way, and the ground plan's fireplace does not fix where it comes " +
            "out. A chimney placed from a render rather than a plan would be an " +
            "invented coordinate wearing the same type as a measured one.",
        "Elewacje materiałowe. The renders show vertical timber in the gables and " +
            "dark render on the garage; this is a technical model with one surface " +
            "colour and no materials, so none of that is represented.",
        "Drzwi wewnętrzne poza jednymi. The plans schedule no internal doors, and " +
            "only the garage-to-boiler-room opening is drawn clearly enough in the " +
            "wall hatch to trace.",
        "Okapowy pas podrynnowy. Both side elevations show a 0.24 m dark band " +
            "between the tiles and the render along the whole length of each long " +
            "facade — the roof's own edge, seen end-on, on a roof the page calls " +
            "eaveless. The model's roof facets have no thickness, so there is no " +
            "edge for that band to be. Giving the roof a thickness is a change to " +
            "the roof itself rather than a band laid beside it, and it would move " +
            "the 150.4 m2 the facets currently reconcile with the published " +
            "150.57 m2 — so it is left out rather than approximated.",
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
    val allRecords: List<FidelityRecord> get() = exactValues + tracedFeatures + displayAssumptions

    /**
     * How many classified data of one kind the model rests on.
     *
     * [SourceFidelity.SOURCE_TRACED] is counted from the grid rather than only
     * from [tracedFeatures]: every traced coordinate is a grid line, and a
     * hand-written list of them beside the grid would be a second copy to keep
     * in step.
     */
    fun countOf(fidelity: SourceFidelity): Int = when (fidelity) {
        SourceFidelity.SOURCE_TRACED -> MarcowkiVisualModelV1.TRACED_GRID_LINE_COUNT
        else -> allRecords.count { it.fidelity == fidelity }
    }
}
