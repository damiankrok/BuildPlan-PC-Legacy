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

    private val assembly: Assembly = assemble()

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
        assembly.addRoof()
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
            // depth past each gable: this single wall is both eaves walls and
            // both west portal cheeks, because the plans draw it as one line.
            exteriorWall(
                slug = "parter-sciana-zachodnia",
                name = "Ściana zewnętrzna parteru — zachód",
                scope = scope,
                rooms = setOf(
                    ground("salon-jadalnia"),
                    ground("kuchnia"),
                    ground("lazienka"),
                    ground("pokoj"),
                ),
                from = PlanPoint(Grid.X_WEST_WALL, Grid.Z_PORTAL_NORTH_FACE),
                to = PlanPoint(Grid.X_WEST_WALL, Grid.Z_PORTAL_SOUTH_FACE),
                base = base,
                height = height,
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
            exteriorWall(
                slug = "parter-sciana-wschodnia",
                name = "Ściana zewnętrzna parteru — wschód",
                scope = scope,
                rooms = setOf(ground("salon-jadalnia")),
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_PORTAL_NORTH_FACE),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_GARAGE_NORTH_WALL),
                base = base,
                height = height,
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
            exteriorWall(
                slug = "garaz-sciana-wschodnia",
                name = "Ściana zewnętrzna garażu — wschód",
                scope = scope,
                rooms = setOf(ground("garaz")),
                from = PlanPoint(Grid.X_GARAGE_EAST_WALL, Grid.GARAGE_NORTH_FACE),
                to = PlanPoint(Grid.X_GARAGE_EAST_WALL, Grid.Z_PORTAL_SOUTH_FACE),
                base = base,
                height = height,
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
            )
            partition(
                slug = "parter-scianka-holu-wschod",
                name = "Ścianka holu — wschód",
                scope = scope,
                rooms = setOf(
                    ground("hol"),
                    ground("spizarnia"),
                    ground("wiatrolap"),
                    ground("kotlownia"),
                ),
                from = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_GF_LIVING_SOUTH),
                to = PlanPoint(Grid.X_GF_HALL_EAST, Grid.Z_SOUTH_WALL),
                base = base,
                height = height,
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
            primitives += SlabGeometry(
                elementId = garageRoof,
                outline = Grid.rectangle(
                    minX = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
                    minZ = Grid.GARAGE_NORTH_FACE,
                    maxX = Grid.BUILDING_WIDTH,
                    maxZ = Grid.Z_PORTAL_SOUTH_FACE,
                ),
                elevation = Grid.GARAGE_CLEAR_HEIGHT,
                thickness = Grid.GROUND_CEILING_Y - Grid.GARAGE_CLEAR_HEIGHT,
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

            val halfWidth = Grid.STAIR_FLIGHT_WIDTH / 2.0
            val southRun = (Grid.STAIR_CORE_SOUTH_Z + Grid.STAIR_SOUTH_Z) / 2.0
            val eastRun = (Grid.STAIR_CORE_EAST_X + Grid.STAIR_EAST_X) / 2.0
            val northRun = (Grid.STAIR_NORTH_Z + Grid.STAIR_CORE_NORTH_Z) / 2.0

            val path = listOf(
                PlanPoint(Grid.STAIR_WEST_X, southRun),
                PlanPoint(eastRun, southRun),
                PlanPoint(eastRun, northRun),
                PlanPoint(Grid.STAIR_WEST_X, northRun),
            )
            val legLengths = path.zipWithNext { from, to -> from.distanceTo(to) }
            val going = legLengths.sum() / Grid.STAIR_RISER_COUNT

            repeat(Grid.STAIR_RISER_COUNT) { step ->
                val centre = path.walk(legLengths, (step + 0.5) * going)
                val runsEastWest = centre.alongX
                val outline = Grid.rectangle(
                    minX = centre.point.x - if (runsEastWest) going / 2.0 else halfWidth,
                    minZ = centre.point.z - if (runsEastWest) halfWidth else going / 2.0,
                    maxX = centre.point.x + if (runsEastWest) going / 2.0 else halfWidth,
                    maxZ = centre.point.z + if (runsEastWest) halfWidth else going / 2.0,
                )
                primitives += SlabGeometry(
                    elementId = id,
                    outline = outline,
                    elevation = Grid.GROUND_FLOOR_Y + step * Grid.STAIR_RISER_HEIGHT,
                    thickness = Grid.STAIR_RISER_HEIGHT,
                )
            }
        }

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
            // Six pieces of one slab, not six slabs: four of them are the house
            // floor with the stairwell left out of it — a stair that came up
            // through a solid ceiling would be the first thing to disbelieve —
            // and the last two are the balcony floors inside the gable portals,
            // which are the same slab carried past the gable wall.
            val slab = element(
                slug = "strop-nad-parterem",
                kind = BuildingElementKind.SLAB,
                name = "Strop nad parterem",
                scope = scope,
            )
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
                Grid.rectangle(0.0, Grid.Z_PORTAL_NORTH_FACE, Grid.HOUSE_WIDTH, 0.0),
                Grid.rectangle(
                    0.0,
                    Grid.BUILDING_DEPTH,
                    Grid.HOUSE_WIDTH,
                    Grid.Z_PORTAL_SOUTH_FACE,
                ),
            ).forEach { outline ->
                primitives += SlabGeometry(
                    elementId = slab,
                    outline = outline,
                    elevation = Grid.GROUND_CEILING_Y,
                    thickness = Grid.UPPER_SLAB_THICKNESS,
                )
            }

            exteriorWall(
                slug = "poddasze-sciana-zachodnia",
                name = "Ściana okapowa poddasza — zachód",
                scope = scope,
                rooms = setOf(
                    attic("pokoj-2"),
                    attic("pralnia"),
                    attic("lazienka"),
                    attic("garderoba-1"),
                ),
                from = PlanPoint(Grid.X_WEST_WALL, Grid.Z_PORTAL_NORTH_FACE),
                to = PlanPoint(Grid.X_WEST_WALL, Grid.Z_PORTAL_SOUTH_FACE),
                base = base,
                height = perimeterHeight,
            )
            exteriorWall(
                slug = "poddasze-sciana-wschodnia",
                name = "Ściana okapowa poddasza — wschód",
                scope = scope,
                rooms = setOf(
                    attic("pokoj-3"),
                    attic("garderoba-2"),
                    attic("schody"),
                    attic("pokoj-1"),
                ),
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_PORTAL_NORTH_FACE),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_PORTAL_SOUTH_FACE),
                base = base,
                height = perimeterHeight,
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
            // underneath.
            balustrade(
                slug = "poddasze-balustrada-polnocna",
                name = "Balustrada balkonu — północ",
                scope = scope,
                centreZ = Grid.Z_PORTAL_NORTH_FACE + Grid.BALUSTRADE_THICKNESS / 2.0,
                fromX = westInnerFace,
                toX = houseEastInnerFace,
            )
            balustrade(
                slug = "poddasze-balustrada-poludniowa",
                name = "Balustrada balkonu — południe",
                scope = scope,
                centreZ = Grid.Z_PORTAL_SOUTH_FACE - Grid.BALUSTRADE_THICKNESS / 2.0,
                fromX = westInnerFace,
                toX = houseEastInnerFace,
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
            )
            atticPartition(
                slug = "poddasze-scianka-korytarza-wschod",
                name = "Ścianka korytarza poddasza — wschód",
                scope = scope,
                rooms = setOf(attic("korytarz"), attic("garderoba-2"), attic("schody")),
                from = PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.Z_UF_BEDROOM_WARDROBE),
                to = PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.Z_UF_CORRIDOR_SOUTH),
            )
            atticPartition(
                slug = "poddasze-scianka-pokoj-garderoba",
                name = "Ścianka między pokojem a garderobą",
                scope = scope,
                rooms = setOf(attic("pokoj-3"), attic("garderoba-2")),
                from = PlanPoint(Grid.X_UF_CORRIDOR_EAST, Grid.Z_UF_BEDROOM_WARDROBE),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_UF_BEDROOM_WARDROBE),
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
            )
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
        ): PlannedOpening = PlannedOpening(
            trace = trace,
            elementId = element(slug, kind, name, scope, rooms),
        )

        private fun partition(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            rooms: Set<RoomId>,
            from: PlanPoint,
            to: PlanPoint,
            base: Double,
            height: Double,
        ): BuildingElementId = wall(
            slug, name, scope, rooms, from, to, base, height,
            Grid.PARTITION_THICKNESS,
        )

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

        /** A guarding across a portal: a thin, low wall on its own element. */
        private fun balustrade(
            slug: String,
            name: String,
            scope: BuildingElementScope,
            centreZ: Double,
            fromX: Double,
            toX: Double,
        ) {
            val id = element(slug, BuildingElementKind.OTHER, name, scope)
            primitives += WallGeometry(
                elementId = id,
                start = PlanPoint(fromX, centreZ),
                end = PlanPoint(toX, centreZ),
                baseElevation = Grid.UPPER_FLOOR_Y,
                height = Grid.BALUSTRADE_HEIGHT,
                thickness = Grid.BALUSTRADE_THICKNESS,
            )
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
        ) {
            val clearTop = Grid.UPPER_FLOOR_Y + Grid.ATTIC_CLEAR_HEIGHT
            val lowestRoof = minOf(Grid.roofUndersideAt(from.x), Grid.roofUndersideAt(to.x))
            val boxTop = minOf(clearTop, lowestRoof)

            val id = partition(
                slug = slug,
                name = name,
                scope = scope,
                rooms = rooms,
                from = from,
                to = to,
                base = Grid.UPPER_FLOOR_Y,
                height = boxTop - Grid.UPPER_FLOOR_Y,
            )

            // A wall that runs along the slope rather than across it sees the
            // same roof height at both ends, and needs nothing above its box.
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
            return id
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

    /** Where a walk along a polyline has got to, and which way that leg runs. */
    private class PathPosition(val point: PlanPoint, val alongX: Boolean)

    /**
     * The point [distance] along this polyline, with the direction of the leg it
     * landed on.
     *
     * The direction comes back with the point because a stair step is a box
     * across the flight, and which way "across" is depends entirely on which of
     * the three flights the step belongs to.
     */
    private fun List<PlanPoint>.walk(legLengths: List<Double>, distance: Double): PathPosition {
        var remaining = distance
        legLengths.forEachIndexed { leg, legLength ->
            if (remaining <= legLength || leg == legLengths.lastIndex) {
                val from = this[leg]
                val to = this[leg + 1]
                val fraction = (remaining / legLength).coerceIn(0.0, 1.0)
                return PathPosition(
                    point = PlanPoint(
                        x = from.x + (to.x - from.x) * fraction,
                        z = from.z + (to.z - from.z) * fraction,
                    ),
                    alongX = abs(to.x - from.x) >= abs(to.z - from.z),
                )
            }
            remaining -= legLength
        }
        error("Cannot walk $distance m along a path with no legs")
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
