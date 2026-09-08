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

    /**
     * The third reading, for STAGE-013D: both plans, all four elevations and
     * the section re-fetched from the URLs above and re-measured for the
     * cheeks, the south balcony, the fascia and the stacks, beside the owner's
     * own reference pack of the same page's renders and elevations — six
     * screenshots kept outside the tracked tree, with the frame and the band
     * marked in red as the traits the model has to be recognised by.
     */
    const val FIDELITY_AUDIT_RETRIEVED_AT: String = "2026-09-08T09:05+02:00"

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
            value = "5.35-7.45 x 5.18-8.80 m, rdzeń 5.35-6.47 x 6.16-7.77 m, zabiegi w narożach",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "Three straight runs turning twice around a rectangular core, traced from " +
                "both plans: the flight the ground plan draws across the south of the " +
                "stairwell lands within 5 cm of the one the upper plan draws there. The " +
                "direction is fixed by the upper plan's arrow, which points west off the " +
                "top flight into the attic corridor. Both plans cut each corner square of " +
                "the well on its diagonal, so the turns are winders rather than landings; " +
                "the bottom flight leaves the hall through a gap in the hall's east " +
                "partition between the core and the boiler room, and the top flight " +
                "arrives in the corridor through a gap in its east partition between the " +
                "walk-in and the core — both drawn as open on the plans, and STAGE-013B " +
                "had walled both. The well's east edge and the core's east and north " +
                "faces were traced at 7.47, 6.46 and 6.18, one to two centimetres off " +
                "the east wall's inner face and the pantry's two wall faces on the same " +
                "lines; STAGE-013G derives them from those faces instead.",
        ),
        FidelityRecord(
            name = "Drzwi wewnętrzne",
            value = "12 drzwi: 5 na parterze, 7 na poddaszu",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "Neither plan schedules an internal door, but both draw a swing arc " +
                "against every partition a room is entered through. Each arc was read " +
                "at the calibrated scale for the wall it stands on and where along it " +
                "the leaf hangs: hall to vestibule, bathroom, bedroom and pantry and " +
                "vestibule to boiler room on the ground floor; corridor to both north " +
                "bedrooms, the laundry, the bathroom and the south-east bedroom, and " +
                "each bedroom to its walk-in on the attic. The north-east bedroom's " +
                "wall to the corridor, which STAGE-013B left out, is drawn on the plan " +
                "and carries the first of those doors. Positions are traced to about " +
                "±0.10 m; leaf sizes and heights are assumptions, listed below.",
        ),
        FidelityRecord(
            name = "Policzki podcieni",
            value = "0.64 m (rzut poddasza 24-25 px; rzut parteru 18 px)",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "On the upper-floor plan the wall hatch in all four portal cheeks " +
                "spans 24 to 25 px at 37.77 px/m while the eaves wall beside it spans " +
                "17, so the cheek is 0.64 m against the wall's 0.44. The front and " +
                "garden elevations read the frame leg at 15 to 16 px at 25.4 px/m, 0.59 " +
                "to 0.63 m, in agreement. The ground plan hatches its cheeks at 18 px " +
                "(0.48 m); the model uses the upper-plan figure on both storeys because " +
                "both elevations draw each leg as one straight band from the ground to " +
                "the mitre, and the disagreement is recorded here rather than drawn.",
        ),
        FidelityRecord(
            name = "Rama podcienia szczytowego",
            value = "0.64 m, ze skosem w narożu",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "The front and garden elevations frame each gable with one band of " +
                "constant width: up both cheeks and along both slopes, with the facade a " +
                "metre behind it, and the leg meets the slope in a sharp mitre. The frame " +
                "is the cheek carried up over the roof, so it takes the cheek's traced " +
                "0.64 m; STAGE-013C carried the 0.44 m eaves wall here and read the " +
                "elevation's wider band as render, which the upper plan's cheek hatch " +
                "now shows it was not. The bar is drawn a metre deep — front, soffit and " +
                "top — because the elevations show a frame and the renders show its depth.",
        ),
        FidelityRecord(
            name = "Balkon południowy",
            value = "od x = 3.39 m do policzka wschodniego",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "The upper-floor plan stipples the south portal floor only east of a " +
                "line at 173.5 px — 3.35 m from the west face — that lies on the " +
                "wardrobe-to-bedroom partition, and draws that west edge as a single " +
                "line. The front elevation starts the dark band at 3.2 m and shows the " +
                "gable open through both storeys west of it; the ground plan puts a " +
                "planting bed in that open half. The balcony, its band and its guarding " +
                "therefore start at the partition centreline, which is what the owner " +
                "saw: the upper exterior zone on the garage side stops mid-house.",
        ),
        FidelityRecord(
            name = "Pas okapowy",
            value = "0.24 m",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "Both side elevations draw a 6 px dark band at 25.4 px/m between the " +
                "tiles and the render, the full 14.6 m from portal face to portal face: " +
                "the roof's own edge, seen end-on, on a roof the page calls eaveless. " +
                "Drawn as a trim standing on the wall face rather than as a thickness " +
                "given to the roof, so the facets that reconcile with the published " +
                "150.57 m2 are untouched.",
        ),
        FidelityRecord(
            name = "Kominy ponad dachem",
            value = "2 x 0.60 x 0.60 m, x 5.45-6.05, z 4.40-5.00 i 8.90-9.50, góra +8.19",
            fidelity = SourceFidelity.SOURCE_TRACED,
            note = "Three views agree. The garage-side elevation shows two stacks 15 px " +
                "wide centred 5.7 m and 10.2 m south of the north portal face; the front " +
                "and garden elevations each show exactly one — which is what two stacks " +
                "on one x do — at 1.5 to 2.1 m east of the ridge; and the section draws " +
                "the same stack 1.5 to 2.1 m east of its apex. The front elevation puts " +
                "the top 6 px above the ridge. The fireplace the ground plan marks sits " +
                "under the southern one; the boiler room under the northern one. " +
                "STAGE-013 left the chimney out because no plan draws its shaft; the " +
                "stacks are placed from the elevations, which do draw them.",
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
            value = "1.10 x 0.02 m, szkło",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The plan draws the balcony edge as a single line with no height " +
                "against it. 1.10 m is the usual guarding height, and without something " +
                "there the portal reads as a hole in the gable rather than a balcony. " +
                "Two centimetres is a sheet, not a wall; that the sheet is drawn as " +
                "glass is a presentation role kept beside the model, not a fact in it.",
        ),
        FidelityRecord(
            name = "Lico ramy przed policzkiem",
            value = "0.005 m, góra ramy 0.01 m pod połacią",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The frame's mitre overlaps the top of the cheek's end face and its " +
                "top bar lies in the roof plane; two surfaces in one plane flicker, so " +
                "the front face stands five millimetres proud and the top a centimetre " +
                "under the roof. Neither is visible at any viewing distance.",
        ),
        FidelityRecord(
            name = "Wysunięcie pasa okapowego",
            value = "0.04 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The elevations give the fascia's depth and nothing about how far it " +
                "stands off the wall. A trim in the wall's own plane cannot be drawn " +
                "beside the wall, so it stands off by four centimetres.",
        ),
        FidelityRecord(
            name = "Zagłębienie komina w połaci",
            value = "0.30 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The source shows each stack above the roof and nothing of its shaft. " +
                "Each box is started a little under the roof plane at its lowest corner " +
                "so it emerges from the slope rather than balancing on it.",
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
            name = "Liczba stopni",
            value = "17 stopni po 0.18 m: 4 + 2 zabiegowe + 5 + 2 zabiegowe + 4",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The plans draw the treads but the watermark crosses the east run, so " +
                "the count is settled two ways that agree: the south and north runs read " +
                "four treads each at about 0.28 m going and the east run's length holds " +
                "five, which with two winders per turn is seventeen; and 3.06 m of rise " +
                "divided into seventeen puts each riser at a climbable 0.18 m. The runs, " +
                "the turns and the direction are traced; the subdivision is chosen.",
        ),
        FidelityRecord(
            name = "Wymiary drzwi wewnętrznych",
            value = "0.80 lub 0.90 x 2.00 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The plans schedule no internal door. Each leaf width is the chord of " +
                "its swing arc rounded to the nearer of the two sizes the arcs fall into, " +
                "and every head is 2.00 m because a door needs some head and the plan " +
                "prints none; where the roof comes lower than that over an attic " +
                "partition the hole stops at the roof. Its own line is drawn for the " +
                "leaf: a pane, like every other opening fill in the study.",
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
        FidelityRecord(
            name = "Moduł pokrycia dachu",
            value = "dachówka 0.25 x 0.40 m, krycie 0.30 m, wyniesienie 0.01–0.055 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The page names a tiled roof and gives its area and pitch; it gives no " +
                "tile. The covering is a presentation layer laid over the two facets " +
                "in aligned courses of cambered, round-tailed patches at a gauge " +
                "a mobile study can afford. The facets themselves are unchanged and keep " +
                "the 150.4 m2 that reconciles with the published area; the tiles stop " +
                "0.03 m short of the stacks and the rooflights and go away with the roof.",
        ),
        FidelityRecord(
            name = "Ścianki pod biegiem schodów",
            value = "górna krawędź 0.02 m pod spodem stopnia",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The ground plan draws the pantry directly under the top flight: its " +
                "three partitions enclose the band the upper plan draws the north run " +
                "over, and the plan shows no treads there because the flight is above " +
                "its cut plane. The walls are therefore kept — the source draws them " +
                "and the door into the pantry — and stopped under the flight: a " +
                "partition piece under a tread rises to that tread's underside less " +
                "two centimetres, never through it. The pantry's east wall stands " +
                "under the first tread of the north run and is the piece cut; the " +
                "clearance is the only chosen number.",
        ),
        FidelityRecord(
            name = "Ramy i słupki okien",
            value = "listwa 0.07 m, głębokość 0.08 m, skrzydło do 1.20 m",
            fidelity = SourceFidelity.DISPLAY_ASSUMPTION,
            note = "The schedule gives every facade opening a width and a height and " +
                "no joinery, and a bare pane in a reveal read as a hole. Each facade " +
                "pane and each rooflight is drawn with a bar round its edge and a " +
                "mullion wherever a leaf would exceed 1.20 m — four leaves across " +
                "the terrace glazing, two in each north gable glazing, none in a " +
                "door or the garage gate. A presentation mesh laid through the pane " +
                "by the renderer's adapter: the hole, the pane and the wall are " +
                "untouched, internal doors stay bare, and a tap on a bar is a tap on " +
                "its window.",
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
        "Trzony kominowe. The two stacks are drawn where the elevations show them, " +
            "above the roof; neither plan draws a shaft that can be told apart from " +
            "the wardrobes hatched the same way, so below the roof there is nothing.",
        "Elewacje materiałowe. The renders show vertical timber in the gables and " +
            "dark render on the garage; this is a technical model with one surface " +
            "colour and no materials, so none of that is represented. Glass is the " +
            "one exception, and only its transparency: a railing that cannot be seen " +
            "through is a parapet.",
        "Skrzydła drzwi i kierunek otwierania. The twelve internal doors are holes " +
            "with a pane in each; which way a leaf swings is read only to place the " +
            "hole and is not drawn.",
        "Grubość połaci. The roof stays a plane and its edge is drawn beside it as " +
            "the eaves fascia; the facets themselves keep the 150.4 m2 that " +
            "reconciles with the published roof area.",
        "Pochwyt balustrady. The renders show a thin top rail on the glass guarding; " +
            "the study draws the sheet alone. Window frames are drawn since STAGE-013G, " +
            "as a presentation assumption listed above, not as a traced section.",
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
