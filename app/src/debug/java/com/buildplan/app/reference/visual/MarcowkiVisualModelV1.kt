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
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.reference.MarcowkiReferenceProject
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid

/**
 * The first visual model of *Dom w marcówkach (GE)*: the canonical reference
 * project given a shape traced from the drawings ARCHON publishes for it.
 *
 * # What this is for
 *
 * One thing only — so the owner can put this model beside the product page and
 * say whether it is the same house. That is a question about massing, storeys,
 * roof direction and where the partitions run, and this model answers exactly
 * that and stops. It is not a construction document, it has no openings, and
 * nothing in it should be measured off for building.
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
    const val TRACED_GRID_LINE_COUNT: Int = Grid.TRACED_LINE_COUNT

    /** The roof, which belongs to the building rather than to either storey. */
    val roofId: BuildingElementId = elementId("dach")

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
            // MarcowkiPlanGrid.houseFootprint for why.
            listOf(Grid.houseFootprint, Grid.garageFootprint).forEach { outline ->
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

            // Corners belong to the walls that run east-west: those reach the
            // outer faces, and the north-south walls stop at their inner faces.
            // Letting both reach the corner would put two coplanar faces in the
            // same place and make the corner flicker; letting neither reach it
            // leaves a notch of daylight, which is what the first render showed.
            val northInnerFace = Grid.exteriorFace(Grid.Z_NORTH_WALL, towardsPositive = true)
            val southInnerFace = Grid.exteriorFace(Grid.Z_SOUTH_WALL, towardsPositive = false)
            val garageNorthInnerFace =
                Grid.exteriorFace(Grid.Z_GARAGE_NORTH_WALL, towardsPositive = true)

            exteriorWall(
                slug = "parter-sciana-polnocna",
                name = "Ściana zewnętrzna parteru — północ",
                scope = scope,
                rooms = setOf(ground("salon-jadalnia")),
                from = PlanPoint(0.0, Grid.Z_NORTH_WALL),
                to = PlanPoint(Grid.HOUSE_WIDTH, Grid.Z_NORTH_WALL),
                base = base,
                height = height,
            )
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
                from = PlanPoint(Grid.X_WEST_WALL, northInnerFace),
                to = PlanPoint(Grid.X_WEST_WALL, southInnerFace),
                base = base,
                height = height,
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
                from = PlanPoint(0.0, Grid.Z_SOUTH_WALL),
                to = PlanPoint(Grid.BUILDING_WIDTH, Grid.Z_SOUTH_WALL),
                base = base,
                height = height,
            )
            exteriorWall(
                slug = "parter-sciana-wschodnia",
                name = "Ściana zewnętrzna parteru — wschód",
                scope = scope,
                rooms = setOf(ground("salon-jadalnia")),
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, northInnerFace),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_GARAGE_NORTH_WALL),
                base = base,
                height = height,
            )

            // One wall, two rooms, one identity: the party wall is not
            // duplicated so that the garage and the house can each have a copy.
            exteriorWall(
                slug = "sciana-miedzy-domem-a-garazem",
                name = "Ściana między domem a garażem",
                scope = scope,
                rooms = setOf(ground("garaz"), ground("kotlownia")),
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_GARAGE_NORTH_WALL),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, southInnerFace),
                base = base,
                height = height,
            )
            exteriorWall(
                slug = "garaz-sciana-polnocna",
                name = "Ściana zewnętrzna garażu — północ",
                scope = scope,
                rooms = setOf(ground("garaz")),
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, Grid.Z_GARAGE_NORTH_WALL),
                to = PlanPoint(Grid.BUILDING_WIDTH, Grid.Z_GARAGE_NORTH_WALL),
                base = base,
                height = height,
            )
            exteriorWall(
                slug = "garaz-sciana-wschodnia",
                name = "Ściana zewnętrzna garażu — wschód",
                scope = scope,
                rooms = setOf(ground("garaz")),
                from = PlanPoint(Grid.X_GARAGE_EAST_WALL, garageNorthInnerFace),
                to = PlanPoint(Grid.X_GARAGE_EAST_WALL, southInnerFace),
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
            primitives += SlabGeometry(
                elementId = garageRoof,
                outline = Grid.rectangle(
                    minX = Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
                    minZ = Grid.GARAGE_NORTH_FACE,
                    maxX = Grid.BUILDING_WIDTH,
                    maxZ = Grid.BUILDING_DEPTH,
                ),
                elevation = Grid.GARAGE_CLEAR_HEIGHT,
                thickness = Grid.GROUND_CEILING_Y - Grid.GARAGE_CLEAR_HEIGHT,
            )
        }

        fun addAtticFloor() {
            val scope = BuildingElementScope.OnFloor(atticId)
            val base = Grid.UPPER_FLOOR_Y
            val perimeterHeight = Grid.ATTIC_PERIMETER_WALL_HEIGHT
            val northInnerFace = Grid.exteriorFace(Grid.Z_NORTH_WALL, towardsPositive = true)
            val southInnerFace = Grid.exteriorFace(Grid.Z_SOUTH_WALL, towardsPositive = false)

            // The floor the attic stands on belongs to the attic, so hiding the
            // storey opens the ground floor to the sky instead of leaving its
            // ceiling in the way.
            val slab = element(
                slug = "strop-nad-parterem",
                kind = BuildingElementKind.SLAB,
                name = "Strop nad parterem",
                scope = scope,
            )
            primitives += SlabGeometry(
                elementId = slab,
                outline = Grid.houseFootprint,
                elevation = Grid.GROUND_CEILING_Y,
                thickness = Grid.UPPER_SLAB_THICKNESS,
            )

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
                from = PlanPoint(Grid.X_WEST_WALL, northInnerFace),
                to = PlanPoint(Grid.X_WEST_WALL, southInnerFace),
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
                from = PlanPoint(Grid.X_HOUSE_EAST_WALL, northInnerFace),
                to = PlanPoint(Grid.X_HOUSE_EAST_WALL, southInnerFace),
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
        }

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
        ): BuildingElementId = wall(
            slug, name, scope, rooms, from, to, base, height,
            Grid.EXTERIOR_WALL_THICKNESS,
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
         * A gable wall: the perimeter wall of the storey plus the panel that
         * closes the triangle above it, both on the same element.
         *
         * The panel sits on the wall's **outer** face rather than its centreline
         * so that the gable and the wall below it read as one plane from
         * outside, which is the only angle the owner will compare from.
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
        ) {
            val id = exteriorWall(
                slug = slug,
                name = name,
                scope = scope,
                rooms = rooms,
                from = PlanPoint(0.0, centreZ),
                to = PlanPoint(Grid.HOUSE_WIDTH, centreZ),
                base = base,
                height = height,
            )
            primitives += GablePanelGeometry(
                elementId = id,
                vertices = listOf(
                    ModelPoint(0.0, Grid.EAVES_Y, outerFaceZ),
                    ModelPoint(Grid.HOUSE_WIDTH, Grid.EAVES_Y, outerFaceZ),
                    ModelPoint(Grid.RIDGE_X, Grid.RIDGE_Y, outerFaceZ),
                ),
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
        ): BuildingElementId {
            val id = element(slug, BuildingElementKind.WALL, name, scope, rooms)
            primitives += WallGeometry(
                elementId = id,
                start = from,
                end = to,
                baseElevation = base,
                height = height,
                thickness = thickness,
            )
            return id
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
