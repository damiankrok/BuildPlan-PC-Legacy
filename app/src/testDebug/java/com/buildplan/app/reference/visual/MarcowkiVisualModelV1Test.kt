package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.model.elementsOnFloor
import com.buildplan.app.domain.model.roofElements
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.GablePanelGeometry
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.primitivesOf
import com.buildplan.app.reference.MarcowkiReferenceProject
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE-013 — the traced Marcówki model says what the current source says, and
 * says clearly where it does not.
 *
 * Two different kinds of promise are checked here and they are worth keeping
 * apart. Some are about *shape*: the walls close, the ids are unique, the roof
 * belongs to the building. Those would matter for any model. The rest are about
 * *provenance*: the height agrees with the published height, the pitch is the
 * published pitch, and every number that is neither published nor traced is
 * named as an assumption. Those are what stop a validation model from drifting
 * into a fabricated one between one stage and the next.
 */
class MarcowkiVisualModelV1Test {

    private val model = MarcowkiVisualModelV1
    private val building = model.building
    private val geometry = model.geometry

    /** How far a traced dimension may sit from its printed source anchor. */
    private val traceToleranceMeters = 0.05

    @Test
    fun `M013-01 source metadata names the exact owner page and its drawings`() {
        assertEquals(
            "https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca",
            MarcowkiSourceEvidence.PAGE_URL,
        )
        assertEquals("Dom w marcówkach (GE)", MarcowkiSourceEvidence.PAGE_TITLE)

        // The canonical dataset and the visual model must point at one page; two
        // URLs would mean two houses.
        assertEquals(MarcowkiReferenceProject.source.url, MarcowkiSourceEvidence.PAGE_URL)

        listOf(
            MarcowkiSourceEvidence.GROUND_FLOOR_PLAN_URL,
            MarcowkiSourceEvidence.UPPER_FLOOR_PLAN_URL,
            MarcowkiSourceEvidence.CROSS_SECTION_URL,
        ).forEach { url ->
            assertTrue(
                "Drawing reference must be a resolved ARCHON asset URL, was $url",
                url.startsWith("https://assets.archon.pl/images/products/m2fa281446a8ca/"),
            )
        }

        assertTrue(MarcowkiSourceEvidence.RETRIEVED_AT.startsWith("2026-"))
        assertEquals(
            "Visual trace for product validation — not construction documentation.",
            MarcowkiSourceEvidence.DISCLAIMER,
        )
    }

    @Test
    fun `M013-02 the two modelled storeys are the canonical reference floors`() {
        assertEquals(MarcowkiReferenceProject.projectId, model.project.id)
        assertEquals(MarcowkiReferenceProject.buildingId, building.id)
        assertEquals(
            listOf(MarcowkiReferenceProject.groundFloorId, MarcowkiReferenceProject.atticId),
            building.floors.map { it.id },
        )

        // The canonical dataset is decorated, never rewritten: its rooms come
        // through untouched, and only elements were added.
        val canonical = MarcowkiReferenceProject.build().building
        assertEquals(canonical.floors, building.floors)
        assertTrue("The canonical dataset must still carry no elements", canonical.elements.isEmpty())
        assertTrue("The visual model must carry elements", building.elements.isNotEmpty())

        val storeys = building.elements.mapNotNull { it.floorId }.toSet()
        assertEquals(setOf(model.groundFloorId, model.atticId), storeys)
    }

    @Test
    fun `M013-03 every ground-floor room has one zone, with uncertainty declared`() {
        assertFloorIsFullyTraced(model.groundFloorId, MarcowkiRoomTrace.groundFloor)

        // The open-plan pair is the only thing the source does not draw a
        // boundary for, and it must be marked rather than quietly guessed.
        val uncertain = MarcowkiRoomTrace.groundFloor
            .filter { it.certainty == TraceCertainty.TRACE_UNCERTAIN }
            .map { it.roomId.value }
            .toSet()
        assertEquals(
            setOf(
                "${model.groundFloorId.value}-kuchnia",
                "${model.groundFloorId.value}-hol",
            ),
            uncertain,
        )

        MarcowkiRoomTrace.groundFloor.forEach { trace ->
            trace.outline.forEach { point ->
                assertTrue(
                    "Zone ${trace.roomId.value} leaves the traced footprint at $point",
                    Grid.isWithinBuildingFootprint(point),
                )
            }
        }
    }

    @Test
    fun `M013-04 every attic room has one zone, inside the house footprint`() {
        assertFloorIsFullyTraced(model.atticId, MarcowkiRoomTrace.attic)

        MarcowkiRoomTrace.attic.forEach { trace ->
            assertEquals(
                "Attic zones are bounded by drawn walls, so none should be uncertain",
                TraceCertainty.TRACED,
                trace.certainty,
            )
            trace.outline.forEach { point ->
                assertTrue(
                    "Zone ${trace.roomId.value} leaves the house footprint at $point",
                    Grid.isWithinHouseFootprint(point),
                )
            }
        }

        // The attic sits over the house, never over the garage.
        val garageWestFace = Grid.HOUSE_WIDTH
        MarcowkiRoomTrace.attic.forEach { trace ->
            assertTrue(
                "Attic zone ${trace.roomId.value} reaches over the garage",
                trace.outline.all { it.x <= garageWestFace + 0.01 },
            )
        }
    }

    @Test
    fun `M013-05 every traced coordinate is a finite number of metres`() {
        val coordinates = geometry.primitives.flatMap { primitive ->
            when (primitive) {
                is WallGeometry -> listOf(
                    primitive.start.x, primitive.start.z, primitive.end.x, primitive.end.z,
                    primitive.baseElevation, primitive.height, primitive.thickness,
                ) + primitive.openings.flatMap { opening ->
                    listOf(
                        opening.distanceFromStart,
                        opening.width,
                        opening.sillElevation,
                        opening.height,
                    )
                }
                is SlabGeometry ->
                    primitive.outline.flatMap { listOf(it.x, it.z) } +
                        listOf(primitive.elevation, primitive.thickness)
                is RoofFacetGeometry -> primitive.vertices.flatMap { listOf(it.x, it.y, it.z) }
                is GablePanelGeometry -> primitive.vertices.flatMap { listOf(it.x, it.y, it.z) }
                is OpeningPanelGeometry ->
                    primitive.vertices.flatMap { listOf(it.x, it.y, it.z) }
            }
        } + MarcowkiRoomTrace.all.flatMap { trace ->
            trace.outline.flatMap { listOf(it.x, it.z) }
        }

        assertTrue("Expected a substantial trace, got ${coordinates.size} numbers", coordinates.size > 200)
        coordinates.forEach { value ->
            assertTrue("Traced coordinate $value is not finite", value.isFinite())
        }
    }

    @Test
    fun `M013-06 traced outer dimensions match the printed source anchors`() {
        val bounds = requireNotNull(geometry.bounds)

        // The whole building, across the printed 1205 anchor.
        assertEquals(Grid.BUILDING_WIDTH, bounds.sizeX, traceToleranceMeters)

        // In depth the model now reaches one portal depth past each gable, so
        // the printed 1260 anchor is what is left after taking those two off.
        // Asserted this way round on purpose: writing the outer number would
        // stop saying anything about the printed one.
        val portals = 2 * Grid.PORTAL_DEPTH
        assertEquals(Grid.BUILDING_DEPTH, bounds.sizeZ - portals, traceToleranceMeters)

        val groundBounds = boundsOfFloor(model.groundFloorId)
        assertEquals(Grid.BUILDING_WIDTH, groundBounds.sizeX, traceToleranceMeters)
        assertEquals(Grid.BUILDING_DEPTH, groundBounds.sizeZ - portals, traceToleranceMeters)

        // The attic covers the house alone, across the printed 790 anchor.
        val atticBounds = boundsOfFloor(model.atticId)
        assertEquals(Grid.HOUSE_WIDTH, atticBounds.sizeX, traceToleranceMeters)
        assertEquals(Grid.BUILDING_DEPTH, atticBounds.sizeZ - portals, traceToleranceMeters)

        // The walled envelope, portals excluded, is still exactly the anchors.
        val walls = geometry.primitives.filterIsInstance<WallGeometry>()
        assertEquals(
            "The house's own outer faces must still span the printed 790",
            Grid.HOUSE_WIDTH,
            walls.filter { it.baseElevation == Grid.GROUND_FLOOR_Y }
                .flatMap { it.footprint() }
                .filter { it.x <= Grid.HOUSE_WIDTH + 0.01 }
                .let { faces -> faces.maxOf { it.x } - faces.minOf { it.x } },
            traceToleranceMeters,
        )

        // The two printed splits of the 1205 anchor.
        assertEquals(12.05, Grid.HOUSE_WIDTH + 4.16, traceToleranceMeters)
        assertEquals(Grid.GARAGE_NORTH_FACE + 7.50, Grid.BUILDING_DEPTH, traceToleranceMeters)
    }

    @Test
    fun `M013-07 every building element id is unique`() {
        val ids = building.elements.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("Expected a full house of elements, got ${ids.size}", ids.size >= 25)

        ids.forEach { id ->
            assertTrue(
                "Traced element ids must be namespaced away from the demo house, was ${id.value}",
                id.value.startsWith("marcowki-v1-"),
            )
        }
    }

    @Test
    fun `M013-08 wall scopes and heights match the storey they are on`() {
        val walls = building.elements.filter { it.kind == BuildingElementKind.WALL }
        assertTrue("Expected walls on both storeys", walls.size >= 20)

        walls.forEach { wall ->
            val floorId = wall.floorId
            assertNotNull("Wall ${wall.id.value} must belong to a storey", floorId)

            val expectedBase = when (floorId) {
                model.groundFloorId -> Grid.GROUND_FLOOR_Y
                model.atticId -> Grid.UPPER_FLOOR_Y
                else -> error("Wall ${wall.id.value} is on unknown floor $floorId")
            }
            geometry.primitivesFor(wall.id).filterIsInstance<WallGeometry>().forEach { shape ->
                assertEquals(
                    "Wall ${wall.id.value} starts at the wrong storey level",
                    expectedBase,
                    shape.baseElevation,
                    1e-9,
                )
            }
        }

        // Nothing in the attic comes out through the roof. The attic is a room
        // inside a roof rather than under one, so a partition standing its full
        // clear height near an eaves would break the surface — which is both
        // wrong and the first thing an owner would see.
        building.elementsOnFloor(model.atticId)
            .flatMap { geometry.primitivesFor(it.id) }
            .forEach { shape ->
                val topPoints = when (shape) {
                    is WallGeometry -> shape.footprint().map { it.x to shape.topElevation }
                    is GablePanelGeometry -> shape.vertices.map { it.x to it.y }
                    else -> emptyList()
                }
                topPoints.forEach { (x, y) ->
                    assertTrue(
                        "${shape.elementId.value} reaches $y at x=$x, through a roof at " +
                            "${Grid.roofUndersideAt(x)}",
                        y <= Grid.roofUndersideAt(x) + 1e-6,
                    )
                }
            }

        assertTrue(
            "Attic geometry must reach the ridge, or the gables are not closed",
            boundsOfFloor(model.atticId).max.y >= Grid.RIDGE_Y - 1e-9,
        )
    }

    @Test
    fun `M013-09 shared walls link real rooms on their own storey`() {
        val roomsByFloor: Map<FloorId, Set<RoomId>> = building.floors.associate { floor ->
            floor.id to floor.rooms.mapTo(mutableSetOf()) { it.id }
        }

        building.elements.forEach { element ->
            val scope = element.scope
            if (scope is BuildingElementScope.OnFloor) {
                val reachable = requireNotNull(roomsByFloor[scope.floorId])
                val stray = element.roomIds - reachable
                assertTrue(
                    "Element ${element.id.value} links rooms off its storey: " +
                        stray.joinToString { it.value },
                    stray.isEmpty(),
                )
            }
        }

        // A party wall is one element linked to both sides, not two copies.
        val shared = building.elements.filter { it.roomIds.size >= 2 }
        assertTrue("Expected shared walls linking two or more rooms", shared.size >= 8)

        val partyWall = building.elements.single {
            it.id.value == "marcowki-v1-sciana-miedzy-domem-a-garazem"
        }
        assertEquals(
            setOf(
                RoomId("${model.groundFloorId.value}-garaz"),
                RoomId("${model.groundFloorId.value}-kotlownia"),
            ),
            partyWall.roomIds,
        )
        assertEquals(
            "The party wall must stay one shape as well as one element",
            1,
            geometry.primitivesFor(partyWall.id).size,
        )
    }

    @Test
    fun `M013-10 the roof belongs to the building and keeps the published pitch`() {
        val roofs = building.roofElements()
        // The gable roof, the garage roof, and the two stacks that rise out of
        // the gable roof and go with it when it is taken off (STAGE-013D).
        assertEquals(
            "Expected the gable roof, the garage roof and the two roof stacks",
            setOf(model.roofId, BuildingElementId("marcowki-v1-stropodach-garazu")) + model.stackIds,
            roofs.mapTo(mutableSetOf()) { it.id },
        )
        roofs.forEach { roof ->
            assertEquals(
                "A roof belongs to no storey",
                BuildingElementScope.WholeBuilding,
                roof.scope,
            )
        }

        val gable = building.elements.single { it.id == model.roofId }
        val facets = geometry.primitivesFor(gable.id).filterIsInstance<RoofFacetGeometry>()
        assertEquals("A gable roof is two facets on one element", 2, facets.size)

        facets.forEach { facet ->
            val lowest = facet.vertices.minOf { it.y }
            val highest = facet.vertices.maxOf { it.y }
            val run = abs(
                facet.vertices.first { it.y == highest }.x -
                    facet.vertices.first { it.y == lowest }.x,
            )
            val pitch = Math.toDegrees(atan2(highest - lowest, run))
            assertEquals(
                "Roof facet pitch must be the published 40 degrees",
                Grid.ROOF_PITCH_DEGREES,
                pitch,
                0.01,
            )
        }

        assertEquals(Grid.RIDGE_Y, facets.flatMap { it.vertices }.maxOf { it.y }, 1e-9)

        // The stated roof area is what settled the ridge direction, so it is
        // also what proves the direction did not later drift.
        val modelledRoofArea = facets.sumOf { it.area }
        assertEquals(150.57, modelledRoofArea, 1.0)
    }

    @Test
    fun `M013-11 the modelled height matches the published building height`() {
        val bounds = requireNotNull(geometry.bounds)
        assertEquals(Grid.TERRAIN_Y, bounds.min.y, 1e-9)
        // The published height is terrain to ridge; stacks do not count towards
        // it, so it is read off the roof element rather than off everything.
        val ridge = geometry.primitivesFor(model.roofId).maxOf { it.bounds.max.y }
        assertEquals(Grid.RIDGE_Y, ridge, 1e-9)
        assertEquals(
            "Ridge above terrain must be the published 8.27 m",
            Grid.BUILDING_HEIGHT,
            ridge - bounds.min.y,
            0.01,
        )
        assertEquals(
            "Only the two stacks may rise above the ridge, and only by their traced 0.24 m",
            Grid.STACK_TOP_Y,
            bounds.max.y,
            1e-9,
        )

        // The published knee wall is kept as a fact even though the drawn eaves
        // wall is taller; both must stay between the attic floor and the eaves.
        assertTrue(Grid.KNEE_WALL_TOP_Y > Grid.UPPER_FLOOR_Y)
        assertTrue(Grid.KNEE_WALL_TOP_Y < Grid.EAVES_Y)
        assertEquals(4.67, Grid.EAVES_Y, 0.05)
    }

    @Test
    fun `M013-12 geometry draws only elements the building has`() {
        assertTrue(geometry.unknownElementIds(building).isEmpty())
        geometry.requireElementsIn(building)

        val withoutShape = building.elements.filter { geometry.primitivesFor(it.id).isEmpty() }
        assertTrue(
            "Every element must be drawable: ${withoutShape.joinToString { it.id.value }}",
            withoutShape.isEmpty(),
        )

        // Pinned so that a trace correction that quietly drops a wall, or adds a
        // second shape for one, shows up as a failure rather than as a model the
        // owner has to re-review from scratch.
        assertEquals("Element count", 65, building.elements.size)
        assertEquals("Primitive count", 139, geometry.primitives.size)
        assertEquals(
            "The 28 walls plus the eight portal cheeks split off five of them, the " +
                "two partitions split around the stair, the six attic partitions across " +
                "the slope cut where the roof meets the ceiling, the three sheets of " +
                "the two balustrades, the two runs of storey band along the portal " +
                "fronts, and the two runs of eaves fascia",
            51,
            geometry.primitives.count { it is WallGeometry },
        )
        assertEquals(
            "Four foundation plates, nine pieces of storey slab, the garage roof, " +
                "seventeen stair treads and two roof stacks",
            33,
            geometry.primitives.count { it is SlabGeometry },
        )
        assertEquals(
            "A gable roof is two facets; the four frame bars add a soffit and a top each",
            2 + 8,
            geometry.primitives.count { it is RoofFacetGeometry },
        )
        assertEquals(
            "The roof element itself is still exactly two facets",
            2,
            geometry.primitivesFor(model.roofId).count { it is RoofFacetGeometry },
        )
        assertEquals(
            "Six attic partitions across the slope, the five bands the north gable " +
                "is cut into by its two glazings and the three the south gable is cut " +
                "into by its one, and the two raking pieces of each portal frame",
            18,
            geometry.primitives.count { it is GablePanelGeometry },
        )
        assertEquals(
            "One pane per traced opening, one leaf per internal door, plus the three rooflights",
            27,
            geometry.primitives.count { it is OpeningPanelGeometry },
        )
    }

    @Test
    fun `M013-16 the evidence views select non-empty, correct element sets`() {
        val everything = visibleIds(BuildingVisibility.EVERYTHING)
        assertEquals(building.elements.size, everything.size)

        val roofOff = visibleIds(BuildingVisibility(roofHidden = true))
        assertTrue(roofOff.isNotEmpty())
        assertTrue(
            "Hiding the roof must drop both roof elements",
            building.roofElements().none { it.id in roofOff },
        )
        assertTrue(
            "Attic walls must survive with the roof off",
            building.elementsOnFloor(model.atticId).any { it.id in roofOff },
        )

        val cutaway = visibleIds(
            BuildingVisibility(hiddenFloorIds = setOf(model.atticId), roofHidden = true),
        )
        assertTrue(
            "The ground cutaway must keep the ground-floor walls",
            building.elementsOnFloor(model.groundFloorId).all { it.id in cutaway },
        )
        assertTrue(
            "The ground cutaway must drop the whole attic",
            building.elementsOnFloor(model.atticId).none { it.id in cutaway },
        )
        assertTrue(building.roofElements().none { it.id in cutaway })

        // Restoring shows everything again; visibility is an argument, not state.
        assertEquals(everything, visibleIds(BuildingVisibility.EVERYTHING))
    }

    @Test
    fun `M013-18 no geometry number escapes its fidelity classification`() {
        // Every wall is built from the grid; a hand-typed dimension anywhere in
        // the assembly would show up here as a value the grid does not contain.
        val allowedThicknesses = setOf(
            Grid.EXTERIOR_WALL_THICKNESS,
            Grid.PORTAL_CHEEK_THICKNESS,
            Grid.PARTITION_THICKNESS,
            Grid.BALUSTRADE_THICKNESS,
            Grid.FASCIA_PROUD,
        )
        // The storey band starts at neither storey level: it hangs off the
        // balcony edge from the garage's dimensioned ceiling up to the attic
        // floor, and both of those are levels the section states. The fascia
        // hangs from the eaves line by its traced depth.
        val allowedBases = setOf(
            Grid.GROUND_FLOOR_Y,
            Grid.UPPER_FLOOR_Y,
            Grid.STOREY_BAND_BASE_Y,
            Grid.FASCIA_BASE_Y,
        )
        // An attic wall may also stop early where the roof comes down to meet
        // it, which is a height the grid computes rather than one it lists.
        val allowedHeights = setOf(
            Grid.GROUND_CLEAR_HEIGHT,
            Grid.ATTIC_CLEAR_HEIGHT,
            Grid.ATTIC_PERIMETER_WALL_HEIGHT,
            Grid.BALUSTRADE_HEIGHT,
            Grid.STOREY_BAND_DEPTH,
            Grid.FASCIA_DEPTH,
        )

        geometry.primitives.filterIsInstance<WallGeometry>().forEach { wall ->
            assertTrue(
                "Wall ${wall.elementId.value} has unclassified thickness ${wall.thickness}",
                allowedThicknesses.any { abs(it - wall.thickness) < 1e-9 },
            )
            assertTrue(
                "Wall ${wall.elementId.value} has unclassified base ${wall.baseElevation}",
                allowedBases.any { abs(it - wall.baseElevation) < 1e-9 },
            )
            val clippedToRoof = wall.footprint().minOf { Grid.roofUndersideAt(it.x) } -
                wall.baseElevation
            assertTrue(
                "Wall ${wall.elementId.value} has unclassified height ${wall.height}",
                (allowedHeights + clippedToRoof).any { abs(it - wall.height) < 1e-6 },
            )
        }

        // Everything the source does not state is exactly what is declared as a
        // display assumption — no more, so the list cannot rot, and no fewer, so
        // a new invented number cannot arrive unlabelled.
        val assumptionNames = MarcowkiSourceEvidence.displayAssumptions.map { it.name }.toSet()
        assertEquals(
            setOf(
                "Grubość płyty fundamentowej",
                "Ściana okapowa poddasza rysowana do połaci",
                "Granica kuchni i holu",
                "Parapet okna kuchni",
                "Nadproże drzwi garaż–kotłownia",
                "Balustrada balkonów",
                "Lico ramy przed policzkiem",
                "Wysunięcie pasa okapowego",
                "Zagłębienie komina w połaci",
                "Podesty w podcieniach",
                "Zadaszenie przed garażem",
                "Liczba stopni",
                "Okna połaciowe rysowane na połaci",
                "Wymiary drzwi wewnętrznych",
            ),
            assumptionNames,
        )
        MarcowkiSourceEvidence.allRecords.forEach { record ->
            assertTrue("${record.name} carries no reason", record.note.length > 20)
        }

        // The one assumption that changes a published number must still differ
        // from it, or the assumption has silently become unnecessary.
        assertTrue(Grid.ATTIC_PERIMETER_WALL_HEIGHT > Grid.KNEE_WALL_HEIGHT)
        assertEquals(
            MarcowkiSourceEvidence.countOf(SourceFidelity.DISPLAY_ASSUMPTION),
            MarcowkiSourceEvidence.displayAssumptions.size,
        )
        assertTrue(MarcowkiSourceEvidence.countOf(SourceFidelity.SOURCE_EXACT) >= 15)
        assertTrue(MarcowkiSourceEvidence.countOf(SourceFidelity.SOURCE_TRACED) >= 20)
    }

    // --- helpers -----------------------------------------------------

    private fun visibleIds(visibility: BuildingVisibility) =
        geometry.primitivesOf(building.visibleElements(visibility))
            .mapTo(LinkedHashSet()) { it.elementId }

    private fun boundsOfFloor(floorId: FloorId) = requireNotNull(
        building.elementsOnFloor(floorId)
            .flatMap { geometry.primitivesFor(it.id) }
            .map { it.bounds }
            .reduceOrNull { total, next -> total.encompass(next) },
    ) { "Floor ${floorId.value} has no geometry" }

    private fun assertFloorIsFullyTraced(floorId: FloorId, traces: List<RoomTrace>) {
        val floor = building.floors.single { it.id == floorId }
        assertEquals("Each source storey lists nine rooms", 9, floor.rooms.size)

        val traced = traces.map { it.roomId }
        assertEquals("A room may have at most one zone", traced.size, traced.toSet().size)
        assertEquals(
            "Every room on ${floor.name} needs a zone",
            floor.rooms.map { it.id }.toSet(),
            traced.toSet(),
        )

        traces.forEach { trace ->
            assertEquals(floorId, trace.floorId)
            assertTrue(
                "Zone ${trace.roomId.value} is degenerate at ${trace.planAreaM2} m2",
                trace.planAreaM2 > 0.5,
            )
        }
    }
}
