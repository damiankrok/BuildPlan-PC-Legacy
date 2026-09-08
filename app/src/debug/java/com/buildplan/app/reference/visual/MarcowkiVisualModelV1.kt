package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.Project
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.BuildingGeometryPrimitive
import com.buildplan.app.geometry.GablePanelGeometry
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.WallOpening
import com.buildplan.app.reference.MarcowkiReferenceProject
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import kotlin.math.abs

/**
 * The first visual model of *Dom w marcówkach (GE)*: the canonical reference
 * project given a shape traced from the drawings ARCHON publishes for it.
 *
 * # What this is for
 *
 * One thing only — so the owner can put this model beside the product page and
 * say whether it is the same house. That is a question about massing, storeys,
 * roof direction, where the partitions run, where the windows and the stair are,
 * and this model answers exactly that and stops. It is not a construction
 * document — its openings are the schedule the plans print and nothing more, it
 * has no structure, no services and no finishes — and nothing in it should be
 * measured off for building.
 *
 * # Its relationship to the canonical dataset
 *
 * The semantics stay where they were.
 * [com.buildplan.app.reference.MarcowkiReferenceProject] still owns the project,
 * building, floor and room identity, and is not modified: it states room names
 * and areas because its source states them, and it still has no geometry because
 * its source states none. What this object adds is a second layer keyed to the
 * same ids — building elements and the shapes that draw them — obtained by
 * measuring the published drawings, which is a different and weaker kind of
 * knowledge and is labelled as such throughout
 * [MarcowkiSourceEvidence].
 *
 * # Where its numbers come from
 *
 * Every coordinate is a line in [MarcowkiPlanGrid], and every wall below is
 * generated from those lines rather than typed out, so that a corrected trace
 * moves the wall and the rooms on both sides of it at once. The classification
 * of each number — stated, traced, or assumed for display — is in
 * [MarcowkiSourceEvidence].
 *
 * # Debug only
 *
 * It sits in `src/debug` beside the dataset it decorates. A traced product
 * drawing is development scaffolding for one owner review; it is not user
 * content, and the release build neither compiles nor ships it.
 */
object MarcowkiVisualModelV1 {

    val groundFloorId: FloorId = MarcowkiReferenceProject.groundFloorId
    val atticId: FloorId = MarcowkiReferenceProject.atticId

    /** The plan zones of all eighteen rooms. See [MarcowkiRoomTrace]. */
    val roomTraces: List<RoomTrace> get() = MarcowkiRoomTrace.all

    /** How many traced grid lines the model rests on, for the fidelity counts. */
    val TRACED_GRID_LINE_COUNT: Int = Grid.TRACED_LINE_COUNT

    /** The roof, which belongs to the building rather than to either storey. */
    val roofId: BuildingElementId = elementId("dach")

    /** The stair, so a view can be framed on it without searching by name. */
    val stairId: BuildingElementId = elementId("parter-schody")

    /** The horizontal band at +3.06 that ties the house to the garage. */
    val storeyBandId: BuildingElementId = elementId("opaska-miedzykondygnacyjna")

    /** The frame around each gable portal, north first. */
    val gableFrameIds: List<BuildingElementId> = listOf(
        elementId("rama-podcienia-polnocnego"),
        elementId("rama-podcienia-poludniowego"),
    )

    /** The glass guarding across each balcony, north first. */
    val balustradeIds: List<BuildingElementId> = listOf(
        elementId("poddasze-balustrada-polnocna"),
        elementId("poddasze-balustrada-poludniowa"),
    )

    /** The eaves fascia: the roof's own edge, drawn beside the roof rather than as its thickness. */
    val fasciaId: BuildingElementId = elementId("pas-okapowy")

    /** The two stacks above the roof, living room first. */
    val stackIds: List<BuildingElementId> = listOf(
        elementId("komin-salonu"),
        elementId("komin-kotlowni"),
    )

    /** The garage door, which the presentation frames without leaves: a gate, not a glazing. */
    val garageDoorId: BuildingElementId = elementId("parter-brama-garazowa")

    private val assembly: Assembly = assemble()

    /**
     * The elements filling the facade openings the plans schedule — see
     * [MarcowkiPlanGrid.facadeOpenings] — in assembly order.
     *
     * Exposed for the presentation beside the model, which frames these and
     * only these: an internal door is a hole with a leaf and no frame, by the
     * same rule that gives it no handle. The ids are collected as the openings
     * are placed rather than listed a second time here.
     */
    val facadeOpeningIds: List<BuildingElementId> get() = assembly.facadeOpeningIds

    /**
     * The reference building, carrying the traced elements.
     *
     * Built by copying the canonical project rather than restating it, so the
     * ids, names and areas can only ever be the canonical ones. The [Building]
     * constructor is also the model's own guard: it rejects a duplicate element
     * id, and it rejects an element that links a room outside its storey, so a
     * mistyped room link fails to construct instead of quietly disappearing.
     */
    val project: Project = MarcowkiReferenceProject.build().let { canonical ->
        canonical.copy(building = canonical.building.copy(elements = assembly.elements))
    }

    val building: Building get() = project.building

    /** The shapes that draw [building], joined to it by element id alone. */
    val geometry: BuildingGeometry = BuildingGeometry(assembly.primitives)

    // -----------------------------------------------------------------
    // Assembly
    // -----------------------------------------------------------------

    private fun assemble(): Assembly {
        val assembly = Assembly()
        assembly.addFoundation()
        assembly.addGroundFloor()
        assembly.addStair()
        assembly.addAtticFloor()
        assembly.addFacadeBands()
        assembly.addRoof()
        assembly.addFascia()
        assembly.addStacks()
        return assembly
    }

    /**
     * Collects elements and their shapes together, so that no element can be
     * declared without a shape and no shape without an element.
     *
     * Two parallel lists filled in two places is how geometry ends up pointing
     * at an element that no longer exists; here the only way to add either is to
     * add both.
     */
    private class Assembly {

        val elements = mutableListOf<BuildingElement>()
        val primitives = mutableListOf<BuildingGeometryPrimitive>()
        val facadeOpeningIds = mutableListOf<BuildingElementId>()

        /**
         * The stair's treads, laid out before any wall is built.
         *
         * Computed first because two things read them: the stair element
         * draws them, and the ground-floor partitions ask whether they stand
         * under one — the pantry does, and its walls stop at the flight's
         * soffit instead of passing through the treads. One list, read twice,
         * so the wall can only ever duck under the tread that is drawn.
         */
        private val treads: List<StairTread> = stairTreads()

        fun addFoundation() {
            val id = element(
                slug = "fundament",
                kind = BuildingElementKind.FOUNDATION,
                name = "Płyta fundamentowa",
                scope = BuildingElementScope.WholeBuilding,
            )
            // Two rectangles rather than one L-shaped outline — see
            // MarcowkiPlanGrid.houseFootprint for why. The two after them are
            // the paved platforms inside the gable portals, which are not built
            // area and are drawn only so the portal cheeks stand on something
            // instead of ending in mid-air one storey above the terrain.
            val northPortalPlatform = Grid.rectangle(
                minX = 0.0,
                minZ = Grid.Z_PORTAL_NORTH_FACE,
                maxX = Grid.HOUSE_WIDTH,
                maxZ = 0.0,
            )
            val southPortalPlatform = Grid.rectangle(
                minX = 0.0,
                minZ = Grid.BUILDING_DEPTH,
                maxX = Grid.BUILDING_WIDTH,
                maxZ = Grid.Z_PORTAL_SOUTH_FACE,
            )
            listOf(
                Grid.houseFootprint,
                Grid.garageFootprint,
                northPortalPlatform,
                southPortalPlatform,
            ).forEach { outline ->
                primitives += SlabGeometry(
                    elementId = id,
                    outline = outline,
                    elevation = Grid.TERRAIN_Y,
                    thickness = -Grid.TERRAIN_Y,
                )
            }
        }

        fun addGroundFloor() {
            val scope = BuildingElementScope.OnFloor(groundFloorId)
            val base = Grid.GROUND_FLOOR_Y
            val height = Grid.GROUND_CLEAR_HEIGHT

            // Corners belong to the walls that run north-south: those reach the
            // outer faces, and the east-west walls stop at their inner faces.
            // Letting both reach the corner would put two coplanar faces in the
            // same place and make the corner flicker; letting neither reach it
            // leaves a notch of daylight, which is what the first render showed.
            //
            // It is this way round rather than the other because the north-south
            // walls are the ones that now run past both gables to form the
            // portals: a corner owned by an east-west wall would be a face
            // buried inside the cheek that passes through it.
            val westInnerFace = Grid.exteriorFace(Grid.X_WEST_WALL, towardsPositive = true)
            val houseEastInnerFace =
                Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false)
            val garageEastInnerFace =
                Grid.exteriorFace(Grid.X_GARAGE_EAST_WALL, towardsPositive = false)

            exteriorWall(
                slug = "parter-sciana-polnocna",
                name = "Ściana zewnętrzna parteru — północ",
                scope = scope,
                rooms = setOf(ground("salon-jadalnia")),
                from = PlanPoint(westInnerFace, Grid.Z_NORTH_WALL),
                to = PlanPoint(houseEastInnerFace, Grid.Z_NORTH_WALL),
                base = base,
                height = height,
                openings = listOf(
                    opening(
                        trace = Grid.GF_LIVING_NORTH_DOOR,
                        slug = "parter-drzwi-taras-polnocny",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi tarasowe salonu — północ",
                        scope = scope,
                        rooms = setOf(ground("salon-jadalnia")),
                    ),
                ),
            )
            // The west wall runs the whole length of the building and one portal
            // depth past each gable: this single element is both eaves walls and
            // both west portal cheeks, because the plans draw it as one line.
            // It is three prisms rather than one because the cheeks are thicker
            // than the wall between them — see MarcowkiPlanGrid.PORTAL_CHEEK_THICKNESS.
            portalWall(
                slug = "parter-sciana-zachodnia",
                name = "Ściana zewnętrzna parteru — zachód",
                scope = scope,
                rooms = setOf(
                    ground("salon-jadalnia"),
                    ground("kuchnia"),
                    ground("lazienka"),
                    ground("pokoj"),
                ),
                outerFaceX = 0.0,
                fromZ = 0.0,
                toZ = Grid.BUILDING_DEPTH,
                base = base,
                height = height,
                northCheek = true,
                southCheek = true,
                openings = listOf(
                    opening(
                        trace = Grid.GF_LIVING_WEST_WINDOW,
                        slug = "parter-okno-salon-zachod",
                        kind = BuildingElementKind.WINDOW,
                        name = "Okno salonu — zachód",
                        scope = scope,
                        rooms = setOf(ground("salon-jadalnia")),
                    ),
                    opening(
                        trace = Grid.GF_KITCHEN_WINDOW,
                        slug = "parter-okno-kuchni",
                        kind = BuildingElementKind.WINDOW,
                        name = "Okno kuchni — zachód",
                        scope = scope,
                        rooms = setOf(ground("kuchnia")),
                    ),
                ),
            )
            exteriorWall(
                slug = "parter-sciana-poludniowa",
                name = "Ściana zewnętrzna parteru — południe",
                scope = scope,
                rooms = setOf(
                    ground("pokoj"),
                    ground("wiatrolap"),
                    ground("kotlownia"),
                    ground("garaz"),
                ),
                // One run across the house and the garage, as the plan draws
                // it. Two abutting walls here would meet in a pair of coincident
                // faces for no gain: the facade is continuous.
                from = PlanPoint(westInnerFace, Grid.Z_SOUTH_WALL),
                to = PlanPoint(garageEastInnerFace, Grid.Z_SOUTH_WALL),
                base = base,
                height = height,
                openings = listOf(
                    opening(
                        trace = Grid.GF_BEDROOM_WINDOW,
                        slug = "parter-okno-pokoju",
                        kind = BuildingElementKind.WINDOW,
                        name = "Okno pokoju — południe",
                        scope = scope,
                        rooms = setOf(ground("pokoj")),
                    ),
                    opening(
                        trace = Grid.GF_ENTRANCE_DOOR,
                        slug = "parter-drzwi-wejsciowe",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi wejściowe",
                        scope = scope,
                        rooms = setOf(ground("wiatrolap")),
                    ),
                    opening(
                        trace = Grid.GF_GARAGE_DOOR,
                        slug = "parter-brama-garazowa",
                        kind = BuildingElementKind.DOOR,
                        name = "Brama garażowa",
                        scope = scope,
                        rooms = setOf(ground("garaz")),
                    ),
                ),
            )
            // North cheek only: south of the garage this wall is the party wall,
            // and at the south portal the garage's own volume stands where a
            // cheek would — the front elevation stops the frame's east leg at
            // the band for exactly that reason.
            portalWall(
                slug = "parter-sciana-wschodnia",
                name = "Ściana zewnętrzna parteru — wschód",
                scope = scope,
                rooms = setOf(ground("salon-jadalnia")),
                outerFaceX = Grid.HOUSE_WIDTH,
                fromZ = 0.0,
                toZ = Grid.Z_GARAGE_NORTH_WALL,
                base = base,
                height = height,
                northCheek = true,
                southCheek = false,
                openings = listOf(
                    opening(
                        trace = Grid.GF_LIVING_EAST_DOOR,
                        slug = "parter-drzwi-taras-wschodni",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi tarasowe salonu — wschód",
                        scope = scope,
                        rooms = setOf(ground("salon-jadalnia")),
                    ),
                ),
            )

            // One wall, two rooms, one identity: the party wall is not
            // duplicated so that the garage and the house can each have a copy.
            // The door through it is likewise one opening serving both.
            exteriorWall(
                slug = "sciana-miedzy-domem-a-garazem",
                name = "Ściana między domem a garażem",
                scope = scope,
                rooms = setOf(ground("garaz"), ground("kotlownia")),
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_GARAGE_NORTH_WALL),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_SOUTH_WALL),
                base = base,
                height = height,
                openings = listOf(
                    opening(
                        trace = Grid.GF_BOILER_GARAGE_DOOR,
                        slug = "parter-drzwi-garaz-kotlownia",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z garażu do kotłowni",
                        scope = scope,
                        rooms = setOf(ground("garaz"), ground("kotlownia")),
                    ),
                ),
            )
            exteriorWall(
                slug = "garaz-sciana-polnocna",
                name = "Ściana zewnętrzna garażu — północ",
                scope = scope,
                rooms = setOf(ground("garaz")),
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_GARAGE_NORTH_WALL),
                to = PlanPoint(garageEastInnerFace, Grid.Z_GARAGE_NORTH_WALL),
                base = base,
                height = height,
                openings = listOf(
                    opening(
                        trace = Grid.GF_GARAGE_SIDE_DOOR,
                        slug = "parter-drzwi-boczne-garazu",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi boczne garażu",
                        scope = scope,
                        rooms = setOf(ground("garaz")),
                    ),
                ),
            )
            // The garage's east wall carries the south portal's east cheek: the
            // front elevation draws its end face as the same light band the
            // house cheeks have, one cheek wide, from the ground to the band.
            portalWall(
                slug = "garaz-sciana-wschodnia",
                name = "Ściana zewnętrzna garażu — wschód",
                scope = scope,
                rooms = setOf(ground("garaz")),
                outerFaceX = Grid.BUILDING_WIDTH,
                fromZ = Grid.GARAGE_NORTH_FACE,
                toZ = Grid.BUILDING_DEPTH,
                base = base,
                height = height,
                northCheek = false,
                southCheek = true,
            )

            partition(
                slug = "parter-scianka-kuchnia-lazienka",
                name = "Ścianka między kuchnią a łazienką",
                scope = scope,
                rooms = setOf(ground("kuchnia"), ground("lazienka")),
                from = PlanPoint(Grid.X_WEST_WALL, Grid.Z_GF_KITCHEN_BATH),
                to = PlanPoint(Grid.X_GF_HALL_WEST, Grid.Z_GF_KITCHEN_BATH),
                base = base,
                height = height,
            )
            partition(
                slug = "parter-scianka-lazienka-pokoj",
                name = "Ścianka między łazienką a pokojem",
                scope = scope,
                rooms = setOf(ground("lazienka"), ground("pokoj")),
                from = PlanPoint(Grid.X_WEST_WALL, Grid.Z_GF_BATH_BEDROOM),
                to = PlanPoint(Grid.X_GF_HALL_WEST, Grid.Z_GF_BATH_BEDROOM),
                base = base,
                height = height,
            )
            partition(
                slug = "parter-scianka-holu-zachod",
                name = "Ścianka holu — zachód",
                scope = scope,
                rooms = setOf(
                    ground("hol"),
                    ground("wiatrolap"),
                    ground("lazienka"),
                    ground("pokoj"),
                ),
                from = PlanPoint(Grid.X_GF_HALL_WEST, Grid.Z_GF_KITCHEN_BATH),
                to = PlanPoint(Grid.X_GF_HALL_WEST, Grid.Z_SOUTH_WALL),
                base = base,
                height = height,
                openings = listOf(
                    opening(
                        trace = Grid.GF_DOOR_HALL_BATHROOM,
                        slug = "parter-drzwi-hol-lazienka",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z holu do łazienki",
                        scope = scope,
                        rooms = setOf(ground("hol"), ground("lazienka")),
                    ),
                    opening(
                        trace = Grid.GF_DOOR_HALL_BEDROOM,
                        slug = "parter-drzwi-hol-pokoj",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z holu do pokoju",
                        scope = scope,
                        rooms = setOf(ground("hol"), ground("pokoj")),
                    ),
                ),
            )
            // One element, two prisms: the wall stops where the stair leaves
            // the hall. Both plans draw the bottom flight starting from the
            // hall, and a partition drawn straight through it — as STAGE-013B
            // did — walls the stair off from the only room it can be entered
            // from. The gap runs from the core's south face to the boiler
            // room's north wall, which is the band the bottom flight occupies.
            splitPartition(
                slug = "parter-scianka-holu-wschod",
                name = "Ścianka holu — wschód",
                scope = scope,
                rooms = setOf(
                    ground("hol"),
                    ground("spizarnia"),
                    ground("wiatrolap"),
                    ground("kotlownia"),
                ),
                base = base,
                height = height,
                runs = listOf(
                    PartitionRun(
                        from = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_GF_LIVING_SOUTH),
                        to = PlanPoint(Grid.X_GF_HALL_EAST, Grid.STAIR_CORE_SOUTH_Z),
                        openings = listOf(
                            opening(
                                trace = Grid.GF_DOOR_HALL_PANTRY,
                                slug = "parter-drzwi-hol-spizarnia",
                                kind = BuildingElementKind.DOOR,
                                name = "Drzwi z holu do spiżarni",
                                scope = scope,
                                rooms = setOf(ground("hol"), ground("spizarnia")),
                            ),
                        ),
                    ),
                    PartitionRun(
                        from = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_GF_BOILER_NORTH),
                        to = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_SOUTH_WALL),
                        openings = listOf(
                            opening(
                                trace = Grid.GF_DOOR_VESTIBULE_BOILER,
                                slug = "parter-drzwi-wiatrolap-kotlownia",
                                kind = BuildingElementKind.DOOR,
                                name = "Drzwi z wiatrołapu do kotłowni",
                                scope = scope,
                                rooms = setOf(ground("wiatrolap"), ground("kotlownia")),
                            ),
                        ),
                    ),
                ),
            )
            partition(
                slug = "parter-scianka-hol-wiatrolap",
                name = "Ścianka między holem a wiatrołapem",
                scope = scope,
                rooms = setOf(ground("hol"), ground("wiatrolap")),
                from = PlanPoint(Grid.X_GF_HALL_WEST, Grid.Z_GF_HALL_VESTIBULE),
                to = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_GF_HALL_VESTIBULE),
                base = base,
                height = height,
                openings = listOf(
                    opening(
                        trace = Grid.GF_DOOR_HALL_VESTIBULE,
                        slug = "parter-drzwi-wiatrolap-hol",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z wiatrołapu do holu",
                        scope = scope,
                        rooms = setOf(ground("hol"), ground("wiatrolap")),
                    ),
                ),
            )
            partition(
                slug = "parter-scianka-kotlowni-polnoc",
                name = "Ścianka kotłowni — północ",
                scope = scope,
                rooms = setOf(ground("kotlownia")),
                from = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_GF_BOILER_NORTH),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_GF_BOILER_NORTH),
                base = base,
                height = height,
            )
            partition(
                slug = "parter-scianka-spizarni-wschod",
                name = "Ścianka spiżarni — wschód",
                scope = scope,
                rooms = setOf(ground("spizarnia")),
                from = PlanPoint(Grid.X_GF_PANTRY_EAST, Grid.Z_GF_LIVING_SOUTH),
                to = PlanPoint(Grid.X_GF_PANTRY_EAST, Grid.Z_GF_PANTRY_SOUTH),
                base = base,
                height = height,
            )
            partition(
                slug = "parter-scianka-spizarni-poludnie",
                name = "Ścianka spiżarni — południe",
                scope = scope,
                rooms = setOf(ground("spizarnia")),
                from = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_GF_PANTRY_SOUTH),
                to = PlanPoint(Grid.X_GF_PANTRY_EAST, Grid.Z_GF_PANTRY_SOUTH),
                base = base,
                height = height,
            )

            // Flat roof over the garage, hidden with the main roof because it is
            // one: the garage has no storey above it to reveal.
            val garageRoof = element(
                slug = "stropodach-garazu",
                kind = BuildingElementKind.ROOF,
                name = "Stropodach garażu",
                scope = BuildingElementScope.WholeBuilding,
                rooms = setOf(ground("garaz")),
            )
            // Carried one portal depth past the building line, because the plan
            // draws the garage's east wall that far south and a wall with
            // nothing over it is a free-standing fin rather than the sheltered
            // entrance the drawing describes.
            //
            // It is as deep as the storey band on the house, and reaches the
            // same +3.06, because all four elevations draw one unbroken line
            // across both masses at that level. Stopping the garage at the
            // ground-floor ceiling — as the first model did — put the two tops
            // 0.34 m apart, and a garage whose roof misses the house's balcony
            // by a third of a metre reads as a block parked beside the house
            // rather than as part of its composition.
            primitives += SlabGeometry(
                elementId = garageRoof,
                outline = Grid.rectangle(
                    minX = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
                    minZ = Grid.GARAGE_NORTH_FACE,
                    maxX = Grid.BUILDING_WIDTH,
                    maxZ = Grid.Z_PORTAL_SOUTH_FACE,
                ),
                elevation = Grid.STOREY_BAND_BASE_Y,
                thickness = Grid.STOREY_BAND_DEPTH,
            )
        }

        /**
         * The stair from the ground floor to the attic.
         *
         * Three flights turning twice around a rectangular core, which is what
         * both plans draw and what the upper plan's arrow settles the direction
         * of: it points west off the top flight into the attic corridor, so the
         * climb runs east along the south of the stairwell, north up its east
         * side, and west along its north side to arrive. Reading that arrow
         * backwards is the whole reason this stair is not simply a run of steps
         * pointing whichever way looked plausible.
         *
         * Each step is drawn as its own tread rather than as a solid mass from
         * the floor. A solid mass fills the stairwell and reads as a plinth from
         * above, which is the one view — the ground-floor plan — where the owner
         * most needs to see that this is a stair.
         *
         * The turns are winders, not landings. STAGE-013B walked equal steps
         * along a centreline and dropped an axis-aligned box at each, which
         * jammed the boxes into one another at every corner and taught a
         * turning pattern no plan draws; both plans cut each corner square on
         * its diagonal, and so does this — see
         * [MarcowkiPlanGrid.STAIR_WINDERS_PER_TURN]. The bottom flight leaves
         * the hall and the top flight arrives in the attic corridor through
         * gaps in the partitions, not through them.
         *
         * It belongs to the ground floor because that is where the flight
         * starts, and it links no room: the source names a "Schody" room on the
         * attic and none down here, and inventing a ground-floor room for the
         * stair to live in is exactly the fake-room move the model does not make.
         */
        fun addStair() {
            val id = element(
                slug = "parter-schody",
                kind = BuildingElementKind.STAIRS,
                name = "Schody na poddasze",
                scope = BuildingElementScope.OnFloor(groundFloorId),
            )
            treads.forEach { tread ->
                primitives += SlabGeometry(
                    elementId = id,
                    outline = tread.outline,
                    elevation = tread.underside,
                    thickness = Grid.STAIR_RISER_HEIGHT,
                )
            }
        }

        /**
         * The seventeen treads, in climbing order, each with the level of
         * its underside.
         *
         * The stairwell as both plans draw it: three straight runs, each the
         * full width of the band it occupies, and a corner square at each
         * turn cut on its diagonal into two winders. Nothing is walked along
         * a centreline: every tread is the piece of stairwell floor the plan
         * draws it on, so a step can neither jam into the one before it at a
         * corner nor float outside its band.
         */
        private fun stairTreads(): List<StairTread> {
            val (southRisers, eastRisers, northRisers) = Grid.STAIR_RUN_RISERS
            val treads = mutableListOf<List<PlanPoint>>()

            // South run: from the hall eastwards, between the core and the
            // south wall of the well.
            val southGoing = (Grid.STAIR_CORE_EAST_X - Grid.STAIR_WEST_X) / southRisers
            repeat(southRisers) { step ->
                treads += Grid.rectangle(
                    minX = Grid.STAIR_WEST_X + step * southGoing,
                    minZ = Grid.STAIR_CORE_SOUTH_Z,
                    maxX = Grid.STAIR_WEST_X + (step + 1) * southGoing,
                    maxZ = Grid.STAIR_SOUTH_Z,
                )
            }
            // South-east turn, on the corner square east of the core.
            treads += winders(
                inner = PlanPoint(Grid.STAIR_CORE_EAST_X, Grid.STAIR_CORE_SOUTH_Z),
                arriving = PlanPoint(Grid.STAIR_CORE_EAST_X, Grid.STAIR_SOUTH_Z),
                outer = PlanPoint(Grid.STAIR_EAST_X, Grid.STAIR_SOUTH_Z),
                leaving = PlanPoint(Grid.STAIR_EAST_X, Grid.STAIR_CORE_SOUTH_Z),
            )
            // East run: northwards along the east wall of the well.
            val eastGoing = (Grid.STAIR_CORE_SOUTH_Z - Grid.STAIR_CORE_NORTH_Z) / eastRisers
            repeat(eastRisers) { step ->
                treads += Grid.rectangle(
                    minX = Grid.STAIR_CORE_EAST_X,
                    minZ = Grid.STAIR_CORE_SOUTH_Z - (step + 1) * eastGoing,
                    maxX = Grid.STAIR_EAST_X,
                    maxZ = Grid.STAIR_CORE_SOUTH_Z - step * eastGoing,
                )
            }
            // North-east turn.
            treads += winders(
                inner = PlanPoint(Grid.STAIR_CORE_EAST_X, Grid.STAIR_CORE_NORTH_Z),
                arriving = PlanPoint(Grid.STAIR_EAST_X, Grid.STAIR_CORE_NORTH_Z),
                outer = PlanPoint(Grid.STAIR_EAST_X, Grid.STAIR_NORTH_Z),
                leaving = PlanPoint(Grid.STAIR_CORE_EAST_X, Grid.STAIR_NORTH_Z),
            )
            // North run: westwards over the pantry, arriving in the attic
            // corridor where the upper plan's arrow points.
            val northGoing = (Grid.STAIR_CORE_EAST_X - Grid.STAIR_WEST_X) / northRisers
            repeat(northRisers) { step ->
                treads += Grid.rectangle(
                    minX = Grid.STAIR_CORE_EAST_X - (step + 1) * northGoing,
                    minZ = Grid.STAIR_NORTH_Z,
                    maxX = Grid.STAIR_CORE_EAST_X - step * northGoing,
                    maxZ = Grid.STAIR_CORE_NORTH_Z,
                )
            }

            check(treads.size == Grid.STAIR_RISER_COUNT) {
                "The stair has ${treads.size} treads for ${Grid.STAIR_RISER_COUNT} risers"
            }
            return treads.mapIndexed { step, outline ->
                StairTread(outline, Grid.GROUND_FLOOR_Y + step * Grid.STAIR_RISER_HEIGHT)
            }
        }

        /**
         * The two winders of one turn: the corner square split on the
         * diagonal from its [inner] corner — the core's — to its [outer] one.
         *
         * The first winder is the triangle against the run that [arriving]s
         * into the corner, the second the one against the run that leaves it,
         * so the climb turns the corner in order. Each is a real tread of the
         * canonical model, one riser high like every other.
         */
        private fun winders(
            inner: PlanPoint,
            arriving: PlanPoint,
            outer: PlanPoint,
            leaving: PlanPoint,
        ): List<List<PlanPoint>> = listOf(
            listOf(inner, arriving, outer),
            listOf(inner, outer, leaving),
        )

        fun addAtticFloor() {
            val scope = BuildingElementScope.OnFloor(atticId)
            val base = Grid.UPPER_FLOOR_Y
            val perimeterHeight = Grid.ATTIC_PERIMETER_WALL_HEIGHT
            val westInnerFace = Grid.exteriorFace(Grid.X_WEST_WALL, towardsPositive = true)
            val houseEastInnerFace =
                Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false)

            // The floor the attic stands on belongs to the attic, so hiding the
            // storey opens the ground floor to the sky instead of leaving its
            // ceiling in the way.
            //
            // Nine pieces of one slab, not nine slabs: four of them are the house
            // floor with the stairwell left out of it — a stair that came up
            // through a solid ceiling would be the first thing to disbelieve —
            // two are the balcony floors inside the gable portals, which are the
            // same slab carried past the gable wall, and three are the slab
            // inside the portal cheeks, which is what closes the storey-height
            // slot a cheek would otherwise show where the floor structure runs
            // through it.
            val slab = element(
                slug = "strop-nad-parterem",
                kind = BuildingElementKind.SLAB,
                name = "Strop nad parterem",
                scope = scope,
            )
            val cheek = Grid.PORTAL_CHEEK_THICKNESS
            listOf(
                Grid.rectangle(0.0, 0.0, Grid.HOUSE_WIDTH, Grid.STAIR_NORTH_Z),
                Grid.rectangle(
                    0.0,
                    Grid.STAIR_SOUTH_Z,
                    Grid.HOUSE_WIDTH,
                    Grid.BUILDING_DEPTH,
                ),
                Grid.rectangle(
                    0.0,
                    Grid.STAIR_NORTH_Z,
                    Grid.STAIR_WEST_X,
                    Grid.STAIR_SOUTH_Z,
                ),
                Grid.rectangle(
                    Grid.STAIR_EAST_X,
                    Grid.STAIR_NORTH_Z,
                    Grid.HOUSE_WIDTH,
                    Grid.STAIR_SOUTH_Z,
                ),
                // Both balcony pieces stop one band thickness short of the
                // portal face: the storey band takes over from there, so the
                // slab's front face and the band's are never the same plane.
                // Both run between the cheeks, not through them. The north one
                // fills its portal; the south one starts where the upper plan
                // starts stippling it and runs to the party wall's outer face,
                // which is where the garage roof takes over at the same level.
                Grid.rectangle(
                    cheek,
                    Grid.Z_PORTAL_NORTH_FACE + Grid.STOREY_BAND_THICKNESS,
                    Grid.HOUSE_WIDTH - cheek,
                    0.0,
                ),
                Grid.rectangle(
                    Grid.X_SOUTH_BALCONY_WEST,
                    Grid.BUILDING_DEPTH,
                    Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
                    Grid.Z_PORTAL_SOUTH_FACE - Grid.STOREY_BAND_THICKNESS,
                ),
                // The slab inside the three house cheeks, out to the portal
                // face: the leg of the frame is one straight band on both
                // elevations, and this is the piece that keeps it one. The
                // south-east cheek needs none — the garage roof is under it.
                Grid.rectangle(0.0, Grid.Z_PORTAL_NORTH_FACE, cheek, 0.0),
                Grid.rectangle(
                    Grid.HOUSE_WIDTH - cheek,
                    Grid.Z_PORTAL_NORTH_FACE,
                    Grid.HOUSE_WIDTH,
                    0.0,
                ),
                Grid.rectangle(0.0, Grid.BUILDING_DEPTH, cheek, Grid.Z_PORTAL_SOUTH_FACE),
            ).forEach { outline ->
                primitives += SlabGeometry(
                    elementId = slab,
                    outline = outline,
                    elevation = Grid.GROUND_CEILING_Y,
                    thickness = Grid.UPPER_SLAB_THICKNESS,
                )
            }

            portalWall(
                slug = "poddasze-sciana-zachodnia",
                name = "Ściana okapowa poddasza — zachód",
                scope = scope,
                rooms = setOf(
                    attic("pokoj-2"),
                    attic("pralnia"),
                    attic("lazienka"),
                    attic("garderoba-1"),
                ),
                outerFaceX = 0.0,
                fromZ = 0.0,
                toZ = Grid.BUILDING_DEPTH,
                base = base,
                height = perimeterHeight,
                northCheek = true,
                southCheek = true,
            )
            portalWall(
                slug = "poddasze-sciana-wschodnia",
                name = "Ściana okapowa poddasza — wschód",
                scope = scope,
                rooms = setOf(
                    attic("pokoj-3"),
                    attic("garderoba-2"),
                    attic("schody"),
                    attic("pokoj-1"),
                ),
                outerFaceX = Grid.HOUSE_WIDTH,
                fromZ = 0.0,
                toZ = Grid.BUILDING_DEPTH,
                base = base,
                height = perimeterHeight,
                northCheek = true,
                southCheek = true,
            )

            gableWall(
                slug = "poddasze-sciana-szczytowa-polnocna",
                name = "Ściana szczytowa poddasza — północ",
                scope = scope,
                rooms = setOf(attic("pokoj-2"), attic("pokoj-3")),
                centreZ = Grid.Z_NORTH_WALL,
                outerFaceZ = 0.0,
                base = base,
                height = perimeterHeight,
                fromX = westInnerFace,
                toX = houseEastInnerFace,
                openings = listOf(
                    opening(
                        trace = Grid.UF_NORTH_GABLE_WEST_DOOR,
                        slug = "poddasze-przeszklenie-szczytu-polnocnego-zachod",
                        kind = BuildingElementKind.WINDOW,
                        name = "Przeszklenie szczytu północnego — zachód",
                        scope = scope,
                        rooms = setOf(attic("pokoj-2")),
                    ),
                    opening(
                        trace = Grid.UF_NORTH_GABLE_EAST_DOOR,
                        slug = "poddasze-przeszklenie-szczytu-polnocnego-wschod",
                        kind = BuildingElementKind.WINDOW,
                        name = "Przeszklenie szczytu północnego — wschód",
                        scope = scope,
                        rooms = setOf(attic("pokoj-3")),
                    ),
                ),
            )
            gableWall(
                slug = "poddasze-sciana-szczytowa-poludniowa",
                name = "Ściana szczytowa poddasza — południe",
                scope = scope,
                rooms = setOf(attic("garderoba-1"), attic("pokoj-1")),
                centreZ = Grid.Z_SOUTH_WALL,
                outerFaceZ = Grid.BUILDING_DEPTH,
                base = base,
                height = perimeterHeight,
                fromX = westInnerFace,
                toX = houseEastInnerFace,
                openings = listOf(
                    opening(
                        trace = Grid.UF_SOUTH_GABLE_DOOR,
                        slug = "poddasze-przeszklenie-szczytu-poludniowego",
                        kind = BuildingElementKind.WINDOW,
                        name = "Przeszklenie szczytu południowego",
                        scope = scope,
                        rooms = setOf(attic("pokoj-1")),
                    ),
                ),
            )

            // The guarding across each portal. Its own element rather than part
            // of the gable wall: it is the one thing in the portal the source
            // draws as a line with no thickness against it, so keeping it
            // separable is what lets a reviewer take it off and see the massing
            // underneath. Both run between the cheeks' inner faces; the south
            // one starts where its balcony starts and turns the corner there,
            // because the upper plan draws that west edge as a line too.
            val cheekInnerWest = Grid.PORTAL_CHEEK_THICKNESS
            val cheekInnerEast = Grid.HOUSE_WIDTH - Grid.PORTAL_CHEEK_THICKNESS
            val northGuardZ = Grid.Z_PORTAL_NORTH_FACE + Grid.BALUSTRADE_THICKNESS / 2.0
            val southGuardZ = Grid.Z_PORTAL_SOUTH_FACE - Grid.BALUSTRADE_THICKNESS / 2.0
            balustrade(
                slug = "poddasze-balustrada-polnocna",
                name = "Balustrada balkonu — północ",
                scope = scope,
                runs = listOf(
                    PlanPoint(cheekInnerWest, northGuardZ) to PlanPoint(cheekInnerEast, northGuardZ),
                ),
            )
            val returnX = Grid.X_SOUTH_BALCONY_WEST + Grid.BALUSTRADE_THICKNESS / 2.0
            balustrade(
                slug = "poddasze-balustrada-poludniowa",
                name = "Balustrada balkonu — południe",
                scope = scope,
                runs = listOf(
                    PlanPoint(Grid.X_SOUTH_BALCONY_WEST, southGuardZ) to
                        PlanPoint(cheekInnerEast, southGuardZ),
                    PlanPoint(returnX, Grid.BUILDING_DEPTH) to
                        PlanPoint(returnX, Grid.Z_PORTAL_SOUTH_FACE - Grid.BALUSTRADE_THICKNESS),
                ),
            )

            atticPartition(
                slug = "poddasze-scianka-korytarza-zachod",
                name = "Ścianka korytarza poddasza — zachód",
                scope = scope,
                rooms = setOf(
                    attic("korytarz"),
                    attic("pokoj-2"),
                    attic("pokoj-3"),
                    attic("pralnia"),
                    attic("lazienka"),
                ),
                from = PlanPoint(Grid.X_UF_CORRIDOR_WEST, Grid.Z_NORTH_WALL),
                to = PlanPoint(Grid.X_UF_CORRIDOR_WEST, Grid.Z_UF_CORRIDOR_SOUTH),
                openings = listOf(
                    opening(
                        trace = Grid.UF_DOOR_CORRIDOR_BEDROOM_NW,
                        slug = "poddasze-drzwi-korytarz-pokoj-2",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z korytarza do pokoju 2",
                        scope = scope,
                        rooms = setOf(attic("korytarz"), attic("pokoj-2")),
                    ),
                    opening(
                        trace = Grid.UF_DOOR_CORRIDOR_LAUNDRY,
                        slug = "poddasze-drzwi-korytarz-pralnia",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z korytarza do pralni",
                        scope = scope,
                        rooms = setOf(attic("korytarz"), attic("pralnia")),
                    ),
                    opening(
                        trace = Grid.UF_DOOR_CORRIDOR_BATHROOM,
                        slug = "poddasze-drzwi-korytarz-lazienka",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z korytarza do łazienki",
                        scope = scope,
                        rooms = setOf(attic("korytarz"), attic("lazienka")),
                    ),
                ),
            )
            // Two prisms of one wall, with the stair's arrival between them:
            // the upper plan's arrow crosses this line off the top flight, and
            // a partition drawn through it — as STAGE-013B did — had the stair
            // arriving in a wall. The gap is the north run's own band, from the
            // walk-in's south wall to the core's north face; south of the core
            // the wall stands again, guarding the corridor from the void.
            atticSplitPartition(
                slug = "poddasze-scianka-korytarza-wschod",
                name = "Ścianka korytarza poddasza — wschód",
                scope = scope,
                rooms = setOf(attic("korytarz"), attic("garderoba-2"), attic("schody")),
                runs = listOf(
                    PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.Z_UF_BEDROOM_WARDROBE) to
                        PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.Z_UF_BEDROOM_LAUNDRY),
                    PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.STAIR_CORE_NORTH_Z) to
                        PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.Z_UF_CORRIDOR_SOUTH),
                ),
            )
            // Runs the full width from the corridor's west wall to the eaves
            // wall, as the upper plan draws it — STAGE-013B started it at the
            // corridor's east wall, which left the north-east bedroom open to
            // the corridor with no door, and the corridor "walled on all four
            // sides" only in its room trace.
            atticPartition(
                slug = "poddasze-scianka-pokoj-garderoba",
                name = "Ścianka między pokojem a garderobą",
                scope = scope,
                rooms = setOf(attic("pokoj-3"), attic("garderoba-2"), attic("korytarz")),
                from = PlanPoint(Grid.X_UF_CORRIDOR_WEST, Grid.Z_UF_BEDROOM_WARDROBE),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_UF_BEDROOM_WARDROBE),
                openings = listOf(
                    opening(
                        trace = Grid.UF_DOOR_CORRIDOR_BEDROOM_NE,
                        slug = "poddasze-drzwi-korytarz-pokoj-3",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z korytarza do pokoju 3",
                        scope = scope,
                        rooms = setOf(attic("korytarz"), attic("pokoj-3")),
                    ),
                    opening(
                        trace = Grid.UF_DOOR_BEDROOM_NE_WARDROBE,
                        slug = "poddasze-drzwi-pokoj-3-garderoba-2",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z pokoju 3 do garderoby 2",
                        scope = scope,
                        rooms = setOf(attic("pokoj-3"), attic("garderoba-2")),
                    ),
                ),
            )
            atticPartition(
                slug = "poddasze-scianka-pokoj-pralnia",
                name = "Ścianka między pokojem a pralnią",
                scope = scope,
                rooms = setOf(attic("pokoj-2"), attic("pralnia")),
                from = PlanPoint(Grid.X_WEST_WALL, Grid.Z_UF_BEDROOM_LAUNDRY),
                to = PlanPoint(Grid.X_UF_CORRIDOR_WEST, Grid.Z_UF_BEDROOM_LAUNDRY),
            )
            atticPartition(
                slug = "poddasze-scianka-garderoba-schody",
                name = "Ścianka między garderobą a schodami",
                scope = scope,
                rooms = setOf(attic("garderoba-2"), attic("schody")),
                from = PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.Z_UF_BEDROOM_LAUNDRY),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_UF_BEDROOM_LAUNDRY),
            )
            atticPartition(
                slug = "poddasze-scianka-pralnia-lazienka",
                name = "Ścianka między pralnią a łazienką",
                scope = scope,
                rooms = setOf(attic("pralnia"), attic("lazienka")),
                from = PlanPoint(Grid.X_WEST_WALL, Grid.Z_UF_LAUNDRY_BATH),
                to = PlanPoint(Grid.X_UF_CORRIDOR_WEST, Grid.Z_UF_LAUNDRY_BATH),
            )
            atticPartition(
                slug = "poddasze-scianka-korytarza-poludnie",
                name = "Ścianka korytarza poddasza — południe",
                scope = scope,
                rooms = setOf(
                    attic("korytarz"),
                    attic("schody"),
                    attic("lazienka"),
                    attic("pokoj-1"),
                ),
                from = PlanPoint(Grid.X_UF_WARDROBE_EAST, Grid.Z_UF_CORRIDOR_SOUTH),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_UF_CORRIDOR_SOUTH),
                openings = listOf(
                    opening(
                        trace = Grid.UF_DOOR_CORRIDOR_BEDROOM_SE,
                        slug = "poddasze-drzwi-korytarz-pokoj-1",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z korytarza do pokoju 1",
                        scope = scope,
                        rooms = setOf(attic("korytarz"), attic("pokoj-1")),
                    ),
                ),
            )
            atticPartition(
                slug = "poddasze-scianka-lazienka-garderoba",
                name = "Ścianka między łazienką a garderobą",
                scope = scope,
                rooms = setOf(attic("lazienka"), attic("garderoba-1")),
                from = PlanPoint(Grid.X_WEST_WALL, Grid.Z_UF_BATH_WARDROBE),
                to = PlanPoint(Grid.X_UF_WARDROBE_EAST, Grid.Z_UF_BATH_WARDROBE),
            )
            atticPartition(
                slug = "poddasze-scianka-garderoba-pokoj",
                name = "Ścianka między garderobą a pokojem",
                scope = scope,
                rooms = setOf(attic("garderoba-1"), attic("pokoj-1")),
                from = PlanPoint(Grid.X_UF_WARDROBE_EAST, Grid.Z_UF_CORRIDOR_SOUTH),
                to = PlanPoint(Grid.X_UF_WARDROBE_EAST, Grid.Z_SOUTH_WALL),
                openings = listOf(
                    opening(
                        trace = Grid.UF_DOOR_BEDROOM_SE_WARDROBE,
                        slug = "poddasze-drzwi-pokoj-1-garderoba-1",
                        kind = BuildingElementKind.DOOR,
                        name = "Drzwi z pokoju 1 do garderoby 1",
                        scope = scope,
                        rooms = setOf(attic("pokoj-1"), attic("garderoba-1")),
                    ),
                ),
            )
        }

        /**
         * The two bands the owner recognises this house by.
         *
         * ## Why they are elements and not decoration
         *
         * Both are drawn on all four current elevations, and between them they
         * are what makes the massing this house rather than a modern house. The
         * first model had neither, and the reviewer's verdict — "still not
         * recognisably my house" — was about exactly this: the portal was a hole
         * with no frame around its top, and the garage was a block that ended
         * a third of a metre below the balcony beside it.
         *
         * ## The gable frame
         *
         * Each portal is framed by one continuous band in one plane: the two
         * cheek end faces, which the walls already give, and the two raking
         * pieces closing the triangle above them, which nothing gave. It is the
         * same 0.44 m the cheeks are thick, so the frame is the cheek carried up
         * and over rather than a trim laid on it — see
         * [MarcowkiPlanGrid.GABLE_FRAME_WIDTH] for why the elevation's wider
         * reading is not the number used.
         *
         * The frame is scoped to the whole building, not to a storey. It spans
         * both of them, and keeping it in every visibility state is what lets an
         * owner take the roof off and still be looking at the same house.
         *
         * ## The storey band
         *
         * One level, +3.06, running along the front of both balconies and around
         * the whole garage. That single line is the composition: it is what ties
         * the tall gabled mass to the flat garage instead of leaving them as two
         * buildings that happen to touch. On the house it belongs to the storey
         * whose floor edge it is; on the garage it is the roof's own fascia and
         * needs no element of its own.
         */
        fun addFacadeBands() {
            val bandId = element(
                slug = "opaska-miedzykondygnacyjna",
                kind = BuildingElementKind.OTHER,
                name = "Opaska międzykondygnacyjna",
                scope = BuildingElementScope.OnFloor(atticId),
            )
            // Between the cheeks rather than across them: the elevations stop
            // the band at the frame on both sides, and running it through would
            // put two faces in the plane the frame already occupies.
            //
            // The north run fills its portal. The south run starts where the
            // south balcony starts — the front elevation begins the dark band
            // 3.2 m from the west face, with the gable open two storeys to the
            // west of it — and runs to the party wall's outer face, where the
            // garage roof continues the same line at the same level.
            val northCentreZ = Grid.Z_PORTAL_NORTH_FACE + Grid.STOREY_BAND_THICKNESS / 2.0
            val southCentreZ = Grid.Z_PORTAL_SOUTH_FACE - Grid.STOREY_BAND_THICKNESS / 2.0
            listOf(
                PlanPoint(Grid.PORTAL_CHEEK_THICKNESS, northCentreZ) to
                    PlanPoint(Grid.HOUSE_WIDTH - Grid.PORTAL_CHEEK_THICKNESS, northCentreZ),
                PlanPoint(Grid.X_SOUTH_BALCONY_WEST, southCentreZ) to
                    PlanPoint(
                        Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
                        southCentreZ,
                    ),
            ).forEach { (start, end) ->
                primitives += WallGeometry(
                    elementId = bandId,
                    start = start,
                    end = end,
                    baseElevation = Grid.STOREY_BAND_BASE_Y,
                    height = Grid.STOREY_BAND_DEPTH,
                    thickness = Grid.STOREY_BAND_THICKNESS,
                )
            }

            gableFrame(
                slug = "rama-podcienia-polnocnego",
                name = "Rama podcienia szczytowego — północ",
                faceZ = Grid.Z_PORTAL_NORTH_FACE,
                gableFaceZ = 0.0,
            )
            gableFrame(
                slug = "rama-podcienia-poludniowego",
                name = "Rama podcienia szczytowego — południe",
                faceZ = Grid.Z_PORTAL_SOUTH_FACE,
                gableFaceZ = Grid.BUILDING_DEPTH,
            )
        }

        /**
         * The two raking bars of one gable frame: each a front face in the
         * portal's face plane, a soffit under it and a top just under the roof.
         *
         * ## Why a bar and not a sheet
         *
         * STAGE-013C drew each raking piece as a single vertical panel, and the
         * owner's verdict was that the frame read only from straight in front.
         * It would: a sheet has no side to see. The reference frame is the roof
         * edge carried over the portal — a metre deep, one cheek wide — and
         * from any three-quarter view it is the soffit and the depth that say
         * "frame" rather than "outline". So each bar is drawn as the three faces
         * a viewer can see: the front, the underside and, when the roof is off,
         * the top. Its back is the gable wall's own panels, and its sides are
         * the cheek and the other bar.
         *
         * ## The mitre
         *
         * The front face is a convex quadrilateral whose fourth corner is the
         * inner mitre corner, 0.30 m down the leg's inner face — see
         * [MarcowkiPlanGrid.GABLE_FRAME_INNER_CORNER_Y]. That corner is below the
         * eaves, so the quadrilateral overlaps the top of the cheek's end face
         * and is stood five millimetres in front of it rather than in its plane.
         * The overlap is not a shortcut: it is the mitre line the elevations
         * draw, and it needs the frame in front of the cheek to be drawn at all.
         *
         * ## The sloped faces
         *
         * The soffit and the top are planar and pitched, so they are
         * [RoofFacetGeometry] — the one sloped planar primitive the contract
         * has — but they belong to the frame element, not to the roof, and the
         * roof's own two facets are untouched by them. The top lies one
         * centimetre under the roof plane so the two never share a surface.
         */
        private fun gableFrame(slug: String, name: String, faceZ: Double, gableFaceZ: Double) {
            val id = element(
                slug = slug,
                kind = BuildingElementKind.OTHER,
                name = name,
                scope = BuildingElementScope.WholeBuilding,
            )
            val proud = if (faceZ < gableFaceZ) -Grid.GABLE_FRAME_PROUD else Grid.GABLE_FRAME_PROUD
            val frontZ = faceZ + proud
            val innerRidgeY = Grid.RIDGE_Y - Grid.GABLE_FRAME_VERTICAL_DROP
            val innerCornerY = Grid.GABLE_FRAME_INNER_CORNER_Y
            val width = Grid.GABLE_FRAME_WIDTH
            val topDrop = Grid.GABLE_FRAME_TOP_DROP

            // Each slope is written for the west one and mirrored across the
            // ridge for the east, so both bars are one shape and one mistake.
            listOf(0.0, Grid.HOUSE_WIDTH).forEach { eavesX ->
                val inward = if (eavesX == 0.0) 1.0 else -1.0
                val innerX = eavesX + inward * width
                primitives += GablePanelGeometry(
                    elementId = id,
                    vertices = listOf(
                        ModelPoint(eavesX, Grid.EAVES_Y, frontZ),
                        ModelPoint(Grid.RIDGE_X, Grid.RIDGE_Y, frontZ),
                        ModelPoint(Grid.RIDGE_X, innerRidgeY, frontZ),
                        ModelPoint(innerX, innerCornerY, frontZ),
                    ),
                )
                primitives += RoofFacetGeometry(
                    elementId = id,
                    vertices = listOf(
                        ModelPoint(innerX, innerCornerY, frontZ),
                        ModelPoint(Grid.RIDGE_X, innerRidgeY, frontZ),
                        ModelPoint(Grid.RIDGE_X, innerRidgeY, gableFaceZ),
                        ModelPoint(innerX, innerCornerY, gableFaceZ),
                    ),
                )
                primitives += RoofFacetGeometry(
                    elementId = id,
                    vertices = listOf(
                        ModelPoint(eavesX, Grid.EAVES_Y - topDrop, frontZ),
                        ModelPoint(Grid.RIDGE_X, Grid.RIDGE_Y - topDrop, frontZ),
                        ModelPoint(Grid.RIDGE_X, Grid.RIDGE_Y - topDrop, gableFaceZ),
                        ModelPoint(eavesX, Grid.EAVES_Y - topDrop, gableFaceZ),
                    ),
                )
            }
        }

        /**
         * The eaves fascia: the 0.24 m band both side elevations draw between
         * the tiles and the render, along the whole length of each long facade.
         *
         * A trim on the wall face, deliberately, rather than a thickness given
         * to the roof. The roof facets are the one piece of geometry that
         * reconciles with a published number — 150.4 m2 against the stated
         * 150.57 — and thickening them would move it. So the roof stays a
         * plane and its edge is drawn beside it: two prisms standing four
         * centimetres proud of the eaves walls and the cheeks, from the
         * north portal face to the south one, with their tops on the eaves
         * line.
         *
         * A signature trim of the whole building, like the gable frame, and
         * not a piece of the roof: the model's plan extent has to be the same
         * in every visibility state — that is the continuity invariant the
         * tests hold — and a trim that stood four centimetres outside the walls
         * and vanished with the roof would move it. So it stays through every
         * toggle, as the frame does, and with the roof off it reads as the cap
         * of the attic wall it sits on.
         */
        fun addFascia() {
            val id = element(
                slug = "pas-okapowy",
                kind = BuildingElementKind.OTHER,
                name = "Pas okapowy dachu",
                scope = BuildingElementScope.WholeBuilding,
            )
            listOf(
                -Grid.FASCIA_PROUD / 2.0,
                Grid.HOUSE_WIDTH + Grid.FASCIA_PROUD / 2.0,
            ).forEach { centreX ->
                primitives += WallGeometry(
                    elementId = id,
                    start = PlanPoint(centreX, Grid.Z_PORTAL_NORTH_FACE),
                    end = PlanPoint(centreX, Grid.Z_PORTAL_SOUTH_FACE),
                    baseElevation = Grid.FASCIA_BASE_Y,
                    height = Grid.FASCIA_DEPTH,
                    thickness = Grid.FASCIA_PROUD,
                )
            }
        }

        /**
         * The two stacks above the roof, each a box rising out of the east
         * slope to a little above the ridge — see [MarcowkiPlanGrid.allStacks]
         * for the three views that place them.
         *
         * Roof kind, like the fascia and for the same reason: the source shows
         * them only above the roof, so what is modelled is the part of each
         * chimney that belongs to the roofscape, and a stack left standing over
         * a ghosted roof would be a box in mid-air. Each starts a little under
         * the roof plane at its lowest corner so it emerges from the slope
         * rather than balancing on it.
         */
        fun addStacks() {
            listOf(
                Triple("komin-salonu", "Komin ponad dachem — salon", Grid.STACK_LIVING_ROOM),
                Triple("komin-kotlowni", "Komin ponad dachem — kotłownia", Grid.STACK_BOILER_ROOM),
            ).forEach { (slug, name, stack) ->
                val id = element(
                    slug = slug,
                    kind = BuildingElementKind.ROOF,
                    name = name,
                    scope = BuildingElementScope.WholeBuilding,
                )
                val lowestRoof = minOf(Grid.roofUndersideAt(stack.minX), Grid.roofUndersideAt(stack.maxX))
                val base = lowestRoof - Grid.STACK_BURIED_DEPTH
                primitives += SlabGeometry(
                    elementId = id,
                    outline = Grid.rectangle(stack.minX, stack.minZ, stack.maxX, stack.maxZ),
                    elevation = base,
                    thickness = Grid.STACK_TOP_Y - base,
                )
            }
        }

        /**
         * The gable roof: one element, two facets.
         *
         * Two shapes and one identity, because a gable roof is one thing to
         * name, hide, pick and cost. The ridge runs north-south over the middle
         * of the house — see [MarcowkiPlanGrid.RIDGE_X] for how that direction
         * was established rather than assumed.
         */
        fun addRoof() {
            val id = element(
                slug = "dach",
                kind = BuildingElementKind.ROOF,
                name = "Dach dwuspadowy",
                scope = BuildingElementScope.WholeBuilding,
            )
            val northEdge = -Grid.GABLE_OVERHANG
            val southEdge = Grid.BUILDING_DEPTH + Grid.GABLE_OVERHANG

            listOf(0.0, Grid.HOUSE_WIDTH).forEach { eavesX ->
                primitives += RoofFacetGeometry(
                    elementId = id,
                    vertices = listOf(
                        ModelPoint(eavesX, Grid.EAVES_Y, northEdge),
                        ModelPoint(Grid.RIDGE_X, Grid.RIDGE_Y, northEdge),
                        ModelPoint(Grid.RIDGE_X, Grid.RIDGE_Y, southEdge),
                        ModelPoint(eavesX, Grid.EAVES_Y, southEdge),
                    ),
                )
            }

            // The three rooflights, drawn as panels lying proud of the slope
            // rather than as holes through it. They are shapes *of the roof* —
            // the same element id — because a hole cut in a facet is what would
            // make them separately hideable, and this stage does not cut one:
            // taking the roof away has to take them with it, or a reviewer is
            // left with three panes floating over an open attic.
            Grid.allRooflights.forEach { light ->
                primitives += OpeningPanelGeometry(
                    elementId = id,
                    vertices = listOf(
                        rooflightCorner(light.minX, light.minZ),
                        rooflightCorner(light.maxX, light.minZ),
                        rooflightCorner(light.maxX, light.maxZ),
                        rooflightCorner(light.minX, light.maxZ),
                    ),
                )
            }
        }

        /** A rooflight corner, lifted off the roof plane it lies in. */
        private fun rooflightCorner(x: Double, z: Double): ModelPoint =
            ModelPoint(x, Grid.roofUndersideAt(x) + Grid.ROOFLIGHT_PROUD_OF_ROOF, z)

        // --- Builders -------------------------------------------------

        private fun exteriorWall(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            from: PlanPoint,
            to: PlanPoint,
            base: Double,
            height: Double,
            openings: List<PlannedOpening> = emptyList(),
            panes: Boolean = true,
        ): BuildingElementId = wall(
            slug, name, scope, rooms, from, to, base, height,
            Grid.EXTERIOR_WALL_THICKNESS, openings, panes,
        )

        /**
         * Declares the element that fills one opening, and pairs it with the
         * traced hole it fills.
         *
         * Two things are created here on purpose, and they are not the same
         * thing: the hole belongs to the wall and is subtracted from it, while
         * the pane belongs to this new element and is drawn in its own right. It
         * is what lets a reviewer tap a window and be told which window it is,
         * and what would let a later stage cost the joinery without costing the
         * wall twice.
         *
         * The pane itself cannot be built yet — it needs the plane and the
         * thickness of a wall that has not been declared — so this returns the
         * pairing and [wall] builds it.
         */
        fun opening(
            trace: Grid.OpeningTrace,
            slug: String,
            kind: BuildingElementKind,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
        ): PlannedOpening {
            val id = element(slug, kind, name, scope, rooms)
            if (trace in Grid.facadeOpenings) facadeOpeningIds += id
            return PlannedOpening(trace = trace, elementId = id)
        }

        /**
         * A ground-floor partition: one element, and as many prisms as the
         * stair over it makes of it — see [groundPartitionRun].
         */
        private fun partition(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            from: PlanPoint,
            to: PlanPoint,
            base: Double,
            height: Double,
            openings: List<PlannedOpening> = emptyList(),
        ): BuildingElementId {
            val id = element(slug, BuildingElementKind.WALL, name, scope, rooms)
            groundPartitionRun(id, from, to, base, height, openings)
            return id
        }

        /**
         * One straight run of a ground-floor partition, stopped under any
         * stair tread that stands over it.
         *
         * The ground plan draws the pantry directly beneath the top flight,
         * and its walls at full storey height stood straight through the
         * treads — the collision the owner saw. The plan is not wrong and the
         * walls are not removed: a wall under a flight is a wall that stops at
         * the flight's soffit. So the run is cut wherever a tread's edge
         * crosses it, each piece is built to the storey height or to the
         * lowest tread over it less [MarcowkiPlanGrid.STAIR_SOFFIT_CLEARANCE],
         * whichever is lower, and neighbouring pieces with one top are
         * merged back. Along the flight that leaves a stepped wall following
         * the treads; across it, one lower piece; away from the stair, the
         * one prism it always was.
         *
         * A piece shorter than the wall is thick — the few centimetres of the
         * pantry's east wall left past the last tread — is absorbed by its
         * neighbour at the lower of the two tops rather than left standing as
         * a full-height post beside the flight.
         */
        private fun groundPartitionRun(
            id: BuildingElementId,
            from: PlanPoint,
            to: PlanPoint,
            base: Double,
            height: Double,
            openings: List<PlannedOpening>,
        ) {
            val runsEastWest = abs(to.x - from.x) >= abs(to.z - from.z)
            val start = if (runsEastWest) minOf(from.x, to.x) else minOf(from.z, to.z)
            val end = if (runsEastWest) maxOf(from.x, to.x) else maxOf(from.z, to.z)
            val across = if (runsEastWest) from.z else from.x
            fun pointAt(along: Double) =
                if (runsEastWest) PlanPoint(along, across) else PlanPoint(across, along)

            val cuts = buildList {
                add(start)
                treads.flatMap { it.outline }
                    .map { if (runsEastWest) it.x else it.z }
                    .filter { it > start + GAP_TOLERANCE && it < end - GAP_TOLERANCE }
                    .forEach(::add)
                add(end)
            }.distinct().sorted()

            val half = Grid.PARTITION_THICKNESS / 2.0
            val pieces = cuts.zipWithNext { a, b ->
                val footprint = if (runsEastWest) {
                    Grid.rectangle(a, across - half, b, across + half)
                } else {
                    Grid.rectangle(across - half, a, across + half, b)
                }
                val soffit = treads
                    .filter { tread -> overlapsInPlan(footprint, tread.outline) }
                    .minOfOrNull { it.underside - Grid.STAIR_SOFFIT_CLEARANCE }
                PartitionPiece(a, b, minOf(base + height, soffit ?: (base + height)))
            }.toMutableList()

            // Absorb slivers into a neighbour, then merge equal tops.
            var index = 0
            while (index < pieces.size && pieces.size > 1) {
                val piece = pieces[index]
                if (piece.length >= Grid.PARTITION_THICKNESS) {
                    index++
                    continue
                }
                if (index > 0) {
                    val previous = pieces[index - 1]
                    pieces[index - 1] = PartitionPiece(previous.from, piece.to, minOf(previous.top, piece.top))
                    pieces.removeAt(index)
                } else {
                    val next = pieces[index + 1]
                    pieces[index] = PartitionPiece(piece.from, next.to, minOf(piece.top, next.top))
                    pieces.removeAt(index + 1)
                }
            }
            val merged = mutableListOf<PartitionPiece>()
            pieces.forEach { piece ->
                val last = merged.lastOrNull()
                if (last != null && abs(last.top - piece.top) < GAP_TOLERANCE) {
                    merged[merged.lastIndex] = PartitionPiece(last.from, piece.to, last.top)
                } else {
                    merged += piece
                }
            }

            var placed = 0
            merged.forEach { piece ->
                val here = openings.filter {
                    it.trace.nearEdge >= piece.from - GAP_TOLERANCE && it.trace.farEdge <= piece.to + GAP_TOLERANCE
                }
                placed += here.size
                wallPrism(
                    id, pointAt(piece.from), pointAt(piece.to), base, piece.top - base,
                    Grid.PARTITION_THICKNESS, here,
                )
            }
            check(placed == openings.size) {
                "A door on ${id.value} straddles a line where the stair cuts the partition"
            }
        }

        /** A stretch of one partition run, from one cut to the next, with its own top. */
        private class PartitionPiece(val from: Double, val to: Double, val top: Double) {
            val length: Double get() = to - from
        }

        /** One straight piece of a partition that stands in more than one piece. */
        class PartitionRun(
            val from: PlanPoint,
            val to: PlanPoint,
            val openings: List<PlannedOpening> = emptyList(),
        )

        /**
         * A partition the plan draws as one line with a gap in it — where a
         * stair leaves or arrives — as one element and several prisms.
         *
         * One element because it is one wall on the plan and one thing to
         * name, pick and cost; several prisms because the gap is not a door
         * with a head over it but an absence the full height of the storey,
         * and a hole cannot be cut through the top of a wall.
         */
        private fun splitPartition(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            base: Double,
            height: Double,
            runs: List<PartitionRun>,
        ): BuildingElementId {
            val id = element(slug, BuildingElementKind.WALL, name, scope, rooms)
            runs.forEach { run ->
                groundPartitionRun(id, run.from, run.to, base, height, run.openings)
            }
            return id
        }

        /** [splitPartition] on the attic, each run stopped under the roof like [atticPartition]. */
        private fun atticSplitPartition(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            runs: List<Pair<PlanPoint, PlanPoint>>,
        ) {
            val id = element(slug, BuildingElementKind.WALL, name, scope, rooms)
            runs.forEach { (from, to) -> atticRun(id, from, to) }
        }

        /** Where an attic wall's box stops: the storey's clear height, or the roof if that is lower. */
        private fun atticBoxTop(from: PlanPoint, to: PlanPoint): Double {
            val clearTop = Grid.UPPER_FLOOR_Y + Grid.ATTIC_CLEAR_HEIGHT
            val lowestRoof = minOf(Grid.roofUndersideAt(from.x), Grid.roofUndersideAt(to.x))
            return minOf(clearTop, lowestRoof)
        }

        /**
         * A gable wall: the perimeter wall of the storey plus the panels that
         * close the triangle above it, all on the same element.
         *
         * The panels sit on the wall's **outer** face rather than its centreline
         * so that the gable and the wall below it read as one plane from
         * outside, which is the only angle the owner will compare from.
         *
         * ## Why the triangle is not one panel any more
         *
         * Both gables are mostly glass, and their glazing is taller than the
         * 1.58 m of wall below the eaves: it carries on up into the triangle.
         * So the triangle is cut into vertical bands — solid where the wall is
         * solid, and starting at the glazing head where it is not — and each
         * band is closed against the roof above it. One panel with a hole in it
         * would need a polygon that is not simply connected, which nothing in
         * this model can bake.
         */
        private fun gableWall(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            centreZ: Double,
            outerFaceZ: Double,
            base: Double,
            height: Double,
            fromX: Double,
            toX: Double,
            openings: List<PlannedOpening> = emptyList(),
        ) {
            // The hole through the wall below the eaves is the full height of
            // that wall: these are glazed doors onto the balcony, so they start
            // at the floor, and the wall stops at the roof plane. The rest of
            // the opening is above the wall, and is a gap between panels rather
            // than a hole in one.
            val id = exteriorWall(
                slug = slug,
                name = name,
                scope = scope,
                rooms = rooms,
                from = PlanPoint(fromX, centreZ),
                to = PlanPoint(toX, centreZ),
                base = base,
                height = height,
                openings = openings.map { it.clippedTo(base, base + height) },
                panes = false,
            )
            gablePanels(id, outerFaceZ, openings)
            openings.forEach { planned -> gablePane(planned, centreZ) }
        }

        /**
         * Closes the gable triangle around [openings], band by band.
         *
         * Bands alternate: solid wall from the eaves up to the roof, then a
         * glazed band from the glazing head up to the roof. A glazed band
         * disappears entirely where the roof is already below the head, which is
         * what happens near either eaves and is exactly why the glazing has a
         * sloped top in the first place.
         */
        private fun gablePanels(
            id: BuildingElementId,
            outerFaceZ: Double,
            openings: List<PlannedOpening>,
        ) {
            val ordered = openings.sortedBy { it.trace.nearEdge }
            var solidFrom = 0.0
            ordered.forEach { planned ->
                roofSpandrel(id, solidFrom, planned.trace.nearEdge, Grid.EAVES_Y, outerFaceZ)
                roofSpandrel(
                    id,
                    planned.trace.nearEdge,
                    planned.trace.farEdge,
                    planned.trace.head,
                    outerFaceZ,
                )
                solidFrom = planned.trace.farEdge
            }
            roofSpandrel(id, solidFrom, Grid.HOUSE_WIDTH, Grid.EAVES_Y, outerFaceZ)
        }

        /**
         * The piece of gable between a horizontal line at [bottomY] and the roof
         * above it, over plan range [x0] to [x1].
         *
         * Clipped to where the roof actually is above that line, because outside
         * that range there is no gap to close and a polygon written anyway would
         * fold back through itself.
         */
        private fun roofSpandrel(
            id: BuildingElementId,
            x0: Double,
            x1: Double,
            bottomY: Double,
            z: Double,
        ) {
            if (x1 - x0 <= GAP_TOLERANCE) return
            val above = Grid.roofAbove(bottomY) ?: return
            val startX = maxOf(x0, above.start)
            val endX = minOf(x1, above.endInclusive)
            if (endX - startX <= GAP_TOLERANCE) return

            val alongRoof = buildList {
                add(endX)
                if (Grid.RIDGE_X in startX..endX) add(Grid.RIDGE_X)
                add(startX)
            }.map { x -> ModelPoint(x, Grid.roofUndersideAt(x), z) }

            if (alongRoof.all { it.y - bottomY <= GAP_TOLERANCE }) return
            primitives += GablePanelGeometry(
                elementId = id,
                vertices = listOf(
                    ModelPoint(startX, bottomY, z),
                    ModelPoint(endX, bottomY, z),
                ) + alongRoof,
            )
        }

        /**
         * The glazing that fills one gable opening: a sheet whose top follows
         * the roof until it reaches the height the plan prints, and is level
         * from there on.
         *
         * The breakpoints are inserted rather than approximated. Both places the
         * head line changes direction — where it meets its cap, and the ridge if
         * the opening spans it — are corners of the pane, and a pane drawn
         * without them would cross the roof it is supposed to stop at.
         */
        private fun gablePane(planned: PlannedOpening, centreZ: Double) {
            val trace = planned.trace
            val cap = trace.head
            val breakpoints = buildList {
                add(trace.nearEdge)
                add(trace.farEdge)
                if (Grid.RIDGE_X in trace.nearEdge..trace.farEdge) add(Grid.RIDGE_X)
                Grid.roofAbove(cap)?.let { above ->
                    if (above.start in trace.nearEdge..trace.farEdge) add(above.start)
                    if (above.endInclusive in trace.nearEdge..trace.farEdge) add(above.endInclusive)
                }
            }.distinct().sorted()

            val head = breakpoints.asReversed().map { x ->
                ModelPoint(x, minOf(Grid.roofUndersideAt(x), cap), centreZ)
            }
            primitives += OpeningPanelGeometry(
                elementId = planned.elementId,
                vertices = listOf(
                    ModelPoint(trace.nearEdge, trace.sill, centreZ),
                    ModelPoint(trace.farEdge, trace.sill, centreZ),
                ) + head,
            )
        }

        /**
         * A guarding across a portal: one or more thin, low sheets on one
         * element. The geometry says only that these are sheets; that they are
         * drawn as glass is a presentation fact and lives in
         * [MarcowkiVisualPresentation], not here.
         */
        private fun balustrade(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            runs: List<Pair<PlanPoint, PlanPoint>>,
        ) {
            val id = element(slug, BuildingElementKind.OTHER, name, scope)
            runs.forEach { (start, end) ->
                primitives += WallGeometry(
                    elementId = id,
                    start = start,
                    end = end,
                    baseElevation = Grid.UPPER_FLOOR_Y,
                    height = Grid.BALUSTRADE_HEIGHT,
                    thickness = Grid.BALUSTRADE_THICKNESS,
                )
            }
        }

        /**
         * An eaves wall that runs past one or both gables to form the portal
         * cheeks: one element, up to three prisms.
         *
         * The wall between the gables is the traced 0.44 m; each cheek is the
         * traced 0.64 m, flush with the wall on the outside and thicker inward
         * — see [MarcowkiPlanGrid.PORTAL_CHEEK_THICKNESS]. Splitting the prism
         * at the gable line is what lets one line on the plan be one element
         * and still change thickness where the plan changes it. The openings
         * are placed against absolute coordinates, so they land in the same
         * place whichever prism happens to start the element.
         */
        private fun portalWall(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            outerFaceX: Double,
            fromZ: Double,
            toZ: Double,
            base: Double,
            height: Double,
            northCheek: Boolean,
            southCheek: Boolean,
            openings: List<PlannedOpening> = emptyList(),
        ): BuildingElementId {
            val inward = if (outerFaceX == 0.0) 1.0 else -1.0
            val wallCentreX = outerFaceX + inward * Grid.EXTERIOR_WALL_THICKNESS / 2.0
            val cheekCentreX = outerFaceX + inward * Grid.PORTAL_CHEEK_THICKNESS / 2.0
            val id = wall(
                slug, name, scope, rooms,
                PlanPoint(wallCentreX, fromZ), PlanPoint(wallCentreX, toZ),
                base, height, Grid.EXTERIOR_WALL_THICKNESS, openings,
            )
            if (northCheek) {
                primitives += WallGeometry(
                    elementId = id,
                    start = PlanPoint(cheekCentreX, Grid.Z_PORTAL_NORTH_FACE),
                    end = PlanPoint(cheekCentreX, 0.0),
                    baseElevation = base,
                    height = height,
                    thickness = Grid.PORTAL_CHEEK_THICKNESS,
                )
            }
            if (southCheek) {
                primitives += WallGeometry(
                    elementId = id,
                    start = PlanPoint(cheekCentreX, Grid.BUILDING_DEPTH),
                    end = PlanPoint(cheekCentreX, Grid.Z_PORTAL_SOUTH_FACE),
                    baseElevation = base,
                    height = height,
                    thickness = Grid.PORTAL_CHEEK_THICKNESS,
                )
            }
            return id
        }


        /**
         * An attic partition, stopped where the roof comes down to meet it.
         *
         * The attic is a room *inside* a roof rather than under one, so its
         * partitions cannot all be the storey's full clear height: near an eaves
         * the roof underside is 1.76 m above the floor, and a 2.66 m wall there
         * would stand straight through the roof.
         *
         * So the wall is built to whichever is lower, and where the roof then
         * climbs away above it the gap is closed with a panel that follows the
         * slope. That is one element with two shapes — the box and its sloped
         * top — which is the same relation the gable roof already has, and it
         * keeps one traced wall segment answering to one id when it is picked.
         */
        private fun atticPartition(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            from: PlanPoint,
            to: PlanPoint,
            openings: List<PlannedOpening> = emptyList(),
        ) {
            val id = element(slug, BuildingElementKind.WALL, name, scope, rooms)
            atticRun(id, from, to, openings)
        }

        /**
         * One straight run of an attic partition, in as many pieces as the
         * roof makes of it.
         *
         * A run along the slope sees one roof height end to end and is one
         * box. A run across the slope is cut where the roof passes the clear
         * height: between those cuts it stands its full 2.66 m under the flat
         * ceiling the section dimensions, and outside them it stops at the
         * roof and is closed with a sloped panel. STAGE-013B built the whole
         * run to the *lowest* roof over it and panelled the rest up to the
         * ridge, which would have put a 1.76 m door in a wall whose middle has
         * 2.66 m of room, and stood a partition up into the roof space above
         * the ceiling.
         */
        private fun atticRun(
            id: BuildingElementId,
            from: PlanPoint,
            to: PlanPoint,
            openings: List<PlannedOpening> = emptyList(),
        ) {
            val clearTop = Grid.UPPER_FLOOR_Y + Grid.ATTIC_CLEAR_HEIGHT
            if (from.x == to.x) {
                atticPiece(id, from, to, openings)
                return
            }
            val startX = minOf(from.x, to.x)
            val endX = maxOf(from.x, to.x)
            val cuts = buildList {
                add(startX)
                Grid.roofAbove(clearTop)?.let { above ->
                    if (above.start > startX && above.start < endX) add(above.start)
                    if (above.endInclusive > startX && above.endInclusive < endX) add(above.endInclusive)
                }
                add(endX)
            }.sorted()
            var placed = 0
            cuts.zipWithNext { x0, x1 ->
                val here = openings.filter {
                    it.trace.nearEdge >= x0 - GAP_TOLERANCE && it.trace.farEdge <= x1 + GAP_TOLERANCE
                }
                placed += here.size
                atticPiece(id, PlanPoint(x0, from.z), PlanPoint(x1, from.z), here)
            }
            check(placed == openings.size) {
                "A door on ${id.value} straddles the line where the roof meets the ceiling"
            }
        }

        /**
         * One box of an attic wall, stopped at the roof or the ceiling, with
         * its doors cut in it and — only where the roof stopped it — the
         * sloped panel that closes it to the roof above.
         *
         * A door's head is brought down to the box top where the roof comes
         * lower than a door; the pane is clipped with the hole so the two
         * never overlap.
         */
        private fun atticPiece(
            id: BuildingElementId,
            from: PlanPoint,
            to: PlanPoint,
            openings: List<PlannedOpening>,
        ) {
            val clearTop = Grid.UPPER_FLOOR_Y + Grid.ATTIC_CLEAR_HEIGHT
            val boxTop = atticBoxTop(from, to)
            wallPrism(
                id, from, to, Grid.UPPER_FLOOR_Y, boxTop - Grid.UPPER_FLOOR_Y, Grid.PARTITION_THICKNESS,
                openings.map { it.clippedTo(Grid.UPPER_FLOOR_Y, boxTop) },
            )
            // Under the flat ceiling there is nothing to close; along the
            // slope the roof is one height end to end and there is no gap.
            if (boxTop >= clearTop - GAP_TOLERANCE) return
            if (Grid.roofUndersideAt(from.x) == Grid.roofUndersideAt(to.x)) return
            slopedTopOf(id, from, to, boxTop)
        }

        /**
         * The triangle or trapezoid between a wall top at [boxTop] and the roof
         * above it, in the wall's own vertical plane.
         *
         * The ridge is inserted as a vertex when the wall crosses it, because a
         * straight edge from one eaves to the other would cut the corner off the
         * roof and leave a wedge of daylight over the wall.
         */
        private fun slopedTopOf(
            id: BuildingElementId,
            from: PlanPoint,
            to: PlanPoint,
            boxTop: Double,
        ) {
            val z = from.z
            val startX = minOf(from.x, to.x)
            val endX = maxOf(from.x, to.x)

            val alongRoof = buildList {
                add(endX)
                if (Grid.RIDGE_X in startX..endX) add(Grid.RIDGE_X)
                add(startX)
            }.map { x -> ModelPoint(x, Grid.roofUndersideAt(x), z) }

            val outline = listOf(
                ModelPoint(startX, boxTop, z),
                ModelPoint(endX, boxTop, z),
            ) + alongRoof

            // Where the roof already sits on the wall top at both ends there is
            // no gap, and no panel: a zero-area polygon is not a shape.
            if (alongRoof.all { it.y - boxTop <= GAP_TOLERANCE }) return
            primitives += GablePanelGeometry(elementId = id, vertices = outline)
        }

        private fun wall(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            from: PlanPoint,
            to: PlanPoint,
            base: Double,
            height: Double,
            thickness: Double,
            openings: List<PlannedOpening> = emptyList(),
            panes: Boolean = true,
        ): BuildingElementId {
            val id = element(slug, BuildingElementKind.WALL, name, scope, rooms)
            wallPrism(id, from, to, base, height, thickness, openings, panes)
            return id
        }

        /** One prism of a wall element, with its holes cut and, if asked, their panes. */
        private fun wallPrism(
            id: BuildingElementId,
            from: PlanPoint,
            to: PlanPoint,
            base: Double,
            height: Double,
            thickness: Double,
            openings: List<PlannedOpening> = emptyList(),
            panes: Boolean = true,
        ) {
            val runsEastWest = abs(to.x - from.x) >= abs(to.z - from.z)
            val wallOrigin = if (runsEastWest) from.x else from.z

            primitives += WallGeometry(
                elementId = id,
                start = from,
                end = to,
                baseElevation = base,
                height = height,
                thickness = thickness,
                openings = openings.map { planned ->
                    planned.asWallOpening(distanceFromStart = planned.trace.nearEdge - wallOrigin)
                },
            )
            if (panes) {
                openings.forEach { planned -> rectangularPane(planned, from, to, runsEastWest) }
            }
        }

        /**
         * The rectangular sheet filling one opening, on the wall's own centre
         * plane.
         *
         * The centre plane rather than either face, so the pane sits back inside
         * the reveal by half a wall on both sides. It is the difference between
         * a window and a picture of a window: the reveal is what casts the
         * shadow that reads as depth from outside, and the bake produces those
         * reveal faces for free by splitting the wall around the hole.
         */
        private fun rectangularPane(
            planned: PlannedOpening,
            from: PlanPoint,
            to: PlanPoint,
            runsEastWest: Boolean,
        ) {
            val trace = planned.trace
            val near = trace.nearEdge
            val far = trace.farEdge
            val corners = if (runsEastWest) {
                listOf(
                    ModelPoint(near, trace.sill, from.z),
                    ModelPoint(far, trace.sill, from.z),
                    ModelPoint(far, trace.head, from.z),
                    ModelPoint(near, trace.head, from.z),
                )
            } else {
                listOf(
                    ModelPoint(from.x, trace.sill, near),
                    ModelPoint(from.x, trace.sill, far),
                    ModelPoint(from.x, trace.head, far),
                    ModelPoint(from.x, trace.head, near),
                )
            }
            check(to.x == from.x || to.z == from.z) {
                "Openings are only placed on axis-aligned walls, and $from to $to is not one"
            }
            primitives += OpeningPanelGeometry(elementId = planned.elementId, vertices = corners)
        }

        private fun element(
            slug: String,
            kind: BuildingElementKind,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId> = emptySet(),
        ): BuildingElementId {
            val id = elementId(slug)
            elements += BuildingElement(
                id = id,
                kind = kind,
                name = name,
                scope = scope,
                roomIds = rooms,
            )
            return id
        }
    }

    /** One tread of the stair: its plan outline and the level of its underside. */
    private class StairTread(val outline: List<PlanPoint>, val underside: Double)

    /**
     * Whether two convex plan polygons share any area — touching along an
     * edge does not count. The separating-axis test: they are apart exactly
     * when some edge normal of one of them separates their projections.
     */
    private fun overlapsInPlan(a: List<PlanPoint>, b: List<PlanPoint>): Boolean {
        listOf(a, b).forEach { polygon ->
            polygon.indices.forEach { index ->
                val from = polygon[index]
                val to = polygon[(index + 1) % polygon.size]
                val axisX = -(to.z - from.z)
                val axisZ = to.x - from.x
                if (abs(axisX) <= GAP_TOLERANCE && abs(axisZ) <= GAP_TOLERANCE) return@forEach
                val (minA, maxA) = a.projectedOnto(axisX, axisZ)
                val (minB, maxB) = b.projectedOnto(axisX, axisZ)
                if (maxA <= minB + GAP_TOLERANCE || maxB <= minA + GAP_TOLERANCE) return false
            }
        }
        return true
    }

    private fun List<PlanPoint>.projectedOnto(axisX: Double, axisZ: Double): Pair<Double, Double> {
        val projections = map { it.x * axisX + it.z * axisZ }
        return projections.min() to projections.max()
    }

    /**
     * A traced opening and the element that has been declared to fill it,
     * before either has been placed on a wall.
     *
     * It exists because those two facts arrive at different moments: the element
     * is declared where the wall's rooms and storey are known, and the hole can
     * only be measured once the wall's own start point is. Passing a pair of
     * loose values between the two would let a window end up in a wall it does
     * not belong to without anything noticing.
     */
    private class PlannedOpening(
        val trace: Grid.OpeningTrace,
        val elementId: BuildingElementId,
    ) {

        fun asWallOpening(distanceFromStart: Double): WallOpening = WallOpening(
            elementId = elementId,
            distanceFromStart = distanceFromStart,
            width = trace.width,
            sillElevation = trace.sill,
            height = trace.height,
        )

        /**
         * The same opening with its head brought down to [top].
         *
         * Only a gable opening needs this, and only because it is taller than
         * the wall it starts in: the hole through the wall stops at the roof
         * plane, and everything above that is a gap between gable panels rather
         * than a hole in one. Clipping here rather than storing a shorter height
         * keeps the printed 303 the model's answer to "how tall is it".
         */
        fun clippedTo(base: Double, top: Double): PlannedOpening = PlannedOpening(
            trace = trace.copy(
                sill = maxOf(trace.sill, base),
                height = minOf(trace.head, top) - maxOf(trace.sill, base),
            ),
            elementId = elementId,
        )
    }

    // -----------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------

    /**
     * Element ids are prefixed so they can never be confused with the synthetic
     * demo house's, and versioned so that a second, better trace can exist
     * beside this one without silently replacing the shapes an owner has already
     * reviewed.
     */
    private fun elementId(slug: String): BuildingElementId =
        BuildingElementId("$ELEMENT_PREFIX-$slug")

    private const val ELEMENT_PREFIX = "marcowki-v1"

    /** Below this, a sloped wall top and the roof above it are the same surface. */
    private const val GAP_TOLERANCE = 0.001

    private fun ground(slug: String): RoomId = RoomId("${groundFloorId.value}-$slug")

    private fun attic(slug: String): RoomId = RoomId("${atticId.value}-$slug")
}
