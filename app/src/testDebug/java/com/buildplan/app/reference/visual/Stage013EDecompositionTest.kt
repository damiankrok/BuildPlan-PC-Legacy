package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.floorOfRoom
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.WallOpening
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.presentation.DecompositionGroup
import com.buildplan.app.presentation.DecompositionProfile
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.GlassPresentation
import com.buildplan.app.render.filament.RenderStyle
import com.buildplan.app.render.filament.SpikeVisibility
import com.buildplan.app.render.filament.toRenderMesh
import com.buildplan.app.render.filament.toRenderMeshes
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE-013E — decomposition, structural fidelity and presentation, stated as
 * things that can fail.
 *
 * The owner's fourth verdict named four defects: the stair did not turn the
 * way the plan turns, the full model was crossed by lines that were not on the
 * building, "without the roof" still showed a roof, and every room was sealed
 * because no internal door existed. Each is held here against the source and
 * against the contracts that must survive the fix — one canonical model, a
 * subset per state, the id as the only join, and the renderer owning no rule.
 */
class Stage013EDecompositionTest {

    private val model = MarcowkiVisualModelV1
    private val building = model.building
    private val geometry = model.geometry
    private val profile = MarcowkiVisualPresentation.decomposition
    private val states = SpikeVisibility.entries

    @Test
    fun `C013E-01 one canonical model underlies every decomposition state`() {
        assertTrue(DebugModel.MARCOWKI.geometry === geometry)
        assertTrue(DebugModel.MARCOWKI.building === building)

        val everything = geometry.elementIds
        val shown = states.map { state -> DebugModel.MARCOWKI.visibleElementIds(state.visibility()) }
        shown.forEach { assertTrue(everything.containsAll(it)) }
        assertEquals(everything, shown.flatMapTo(LinkedHashSet()) { it })

        // The presentation only narrows the domain's answer, never widens it,
        // and the full state is the domain's answer exactly.
        states.forEach { state ->
            val domain = building.visibleElements(state.visibility()).mapTo(LinkedHashSet()) { it.id }
            val presented = profile.visibleElements(building, state.visibility()).mapTo(LinkedHashSet()) { it.id }
            assertTrue(domain.containsAll(presented))
        }
        assertEquals(
            building.elements.map { it.id },
            profile.visibleElements(building, BuildingVisibility.EVERYTHING).map { it.id },
        )

        // Same object, same order, every time.
        val once = DebugModel.MARCOWKI.visibleElementIds(SpikeVisibility.ROOF_HIDDEN.visibility()).toList()
        val again = DebugModel.MARCOWKI.visibleElementIds(SpikeVisibility.ROOF_HIDDEN.visibility()).toList()
        assertEquals(once, again)
    }

    @Test
    fun `C013E-02 roof off removes every element assigned to the roof envelope`() {
        val envelope = profile.elementsIn(DecompositionGroup.ROOF_ENVELOPE)
        assertEquals((model.gableFrameIds + model.fasciaId).toSet(), envelope)

        val roofOff = DebugModel.MARCOWKI.visibleElementIds(SpikeVisibility.ROOF_HIDDEN.visibility())
        val bothOff = DebugModel.MARCOWKI.visibleElementIds(SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN.visibility())
        (envelope + building.elements.filter { it.kind == BuildingElementKind.ROOF }.map { it.id }).forEach { id ->
            assertTrue("$id must go with the roof", id !in roofOff && id !in bothOff)
        }

        // Ownership is untouched: the frames and the fascia are still
        // whole-building elements the domain shows without its roof.
        envelope.forEach { id ->
            val element = building.elements.single { it.id == id }
            assertEquals(BuildingElementScope.WholeBuilding, element.scope)
            assertTrue(element.kind != BuildingElementKind.ROOF)
            assertTrue(id in building.visibleElements(BuildingVisibility(roofHidden = true)).map { it.id })
        }

        // Attic off with the roof on keeps the envelope: it is the roof's.
        val atticOff = DebugModel.MARCOWKI.visibleElementIds(SpikeVisibility.UPPER_FLOOR_HIDDEN.visibility())
        envelope.forEach { assertTrue(it in atticOff) }

        // The synthetic fixture declares no groups and is the domain's answer.
        assertTrue(DebugModel.SYNTHETIC.decomposition === DecompositionProfile.NONE)
        assertEquals(
            SyntheticDemoHouse.building.visibleElements(BuildingVisibility(roofHidden = true)).map { it.id },
            DecompositionProfile.NONE.visibleElements(SyntheticDemoHouse.building, BuildingVisibility(roofHidden = true)).map { it.id },
        )
    }

    @Test
    fun `C013E-03 roof off leaves no roof-envelope geometry, solid or ghost, and exposes the attic`() {
        val roofOff = DebugModel.MARCOWKI.visibleElementIds(SpikeVisibility.ROOF_HIDDEN.visibility())
        val drawn = geometry.primitives.filter { it.elementId in roofOff }

        // The only whole-building element left standing is the foundation:
        // everything else on screen belongs to a storey. The gable triangles
        // stay, and rightly — they are the attic's walls, not the roof.
        val wholeBuilding = building.elements.filter { it.scope == BuildingElementScope.WholeBuilding }.map { it.id }
        assertEquals(
            setOf(BuildingElementId("marcowki-v1-fundament")),
            drawn.map { it.elementId }.filter { it in wholeBuilding }.toSet(),
        )
        drawn.forEach { primitive ->
            assertTrue(
                "${primitive.elementId.value} still stands in the roof",
                primitive.bounds.max.y <= Grid.RIDGE_Y + 1e-6,
            )
        }
        assertTrue(drawn.none { it.elementId == model.roofId })
        assertTrue(drawn.none { it.elementId in model.gableFrameIds })
        assertTrue(drawn.none { it.elementId == model.fasciaId })
        assertTrue(drawn.none { it.elementId in model.stackIds })

        // What is exposed: the attic's walls, floor, doors and the stair.
        val atticIds = building.elements.filter { it.floorId == model.atticId }.map { it.id }
        atticIds.forEach { assertTrue(it in roofOff) }
        assertTrue(model.stairId in roofOff)

        // And there is no second line material to draw a removed layer with:
        // one depth-tested line material, and the renderer takes one id set.
        val material = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/TechnicalMaterial.kt").readText()
        assertTrue(!material.contains("GHOST") && !material.contains("depthCulling(false)"))
        val renderer = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/FilamentModelRenderer.kt").readText()
        assertTrue(renderer.contains("fun setVisibleElements(visibleElementIds: Set<BuildingElementId>)"))
        assertTrue(!renderer.contains("removedElementIds"))
    }

    @Test
    fun `C013E-04 the edge set excludes coplanar seams and keeps creases and reveals`() {
        // A wall with a window bakes as four boxes in one plane. Its drawn
        // edges are the box it would be without the seams: the outer twelve,
        // plus the reveal round the hole — never a jamb carried to the top.
        val wall = WallGeometry(
            elementId = BuildingElementId("e-test-wall"),
            start = PlanPoint(0.0, 0.0),
            end = PlanPoint(6.0, 0.0),
            baseElevation = 0.0,
            height = 2.72,
            thickness = 0.44,
            openings = listOf(
                WallOpening(BuildingElementId("e-test-window"), 2.0, 1.4, 0.9, 1.4),
            ),
        ).toRenderMesh()
        val edges = wall.segments()

        fun hasEdge(a: DoubleArray, b: DoubleArray) = edges.any { (p, q) ->
            (p.near(a) && q.near(b)) || (p.near(b) && q.near(a))
        }
        val face = -0.22
        // The jamb above the head and below the sill is a seam: absent.
        assertTrue(!hasEdge(doubleArrayOf(2.0, 2.3, face), doubleArrayOf(2.0, 2.72, face)))
        assertTrue(!hasEdge(doubleArrayOf(2.0, 0.0, face), doubleArrayOf(2.0, 0.9, face)))
        assertTrue(!hasEdge(doubleArrayOf(2.0, 0.0, face), doubleArrayOf(2.0, 2.72, face)))
        // The reveal is a crease: present, exactly the hole's outline.
        assertTrue(hasEdge(doubleArrayOf(2.0, 0.9, face), doubleArrayOf(2.0, 2.3, face)))
        assertTrue(hasEdge(doubleArrayOf(3.4, 0.9, face), doubleArrayOf(3.4, 2.3, face)))
        assertTrue(hasEdge(doubleArrayOf(2.0, 2.3, face), doubleArrayOf(3.4, 2.3, face)))
        assertTrue(hasEdge(doubleArrayOf(2.0, 0.9, face), doubleArrayOf(3.4, 0.9, face)))
        // The wall's own corners survive as whole lines.
        assertTrue(hasEdge(doubleArrayOf(0.0, 0.0, face), doubleArrayOf(0.0, 2.72, face)))
        assertTrue(hasEdge(doubleArrayOf(6.0, 0.0, face), doubleArrayOf(6.0, 2.72, face)))

        // Two coplanar boxes of one element — an eaves wall and its thicker
        // cheek — share no line on their common outer face.
        val portal = geometry.primitives.filterIsInstance<WallGeometry>()
            .filter { it.elementId == BuildingElementId("marcowki-v1-poddasze-sciana-zachodnia") }
        assertEquals(3, portal.size)
        // Baked as one element's meshes they are separate primitives, so the
        // contract is checked on the accumulator through one slab in two
        // pieces: no line where the pieces meet.
        val pieces = listOf(
            SlabGeometry(BuildingElementId("e-test-slab"), Grid.rectangle(0.0, 0.0, 2.0, 1.0), 0.0, 0.3),
        ).toRenderMeshes().single()
        assertEquals(12, pieces.edgeCount)

        // No edge is a triangulation diagonal: every edge lies along an axis
        // of its box, because every shape here is axis-aligned.
        wall.segments().forEach { (p, q) ->
            val axes = listOf(abs(p[0] - q[0]), abs(p[1] - q[1]), abs(p[2] - q[2])).count { it > 1e-6 }
            assertEquals("Edge $p-$q is a diagonal", 1, axes)
        }

        // Across the whole model the edge count fell against the seam-drawing
        // bake, and no mesh lost its outline entirely.
        geometry.primitives.toRenderMeshes().forEach { mesh ->
            assertTrue("${mesh.elementId.value} bakes no outline", mesh.edgeCount > 0)
        }
    }

    @Test
    fun `C013E-05 the stair envelope, runs, turns, arrival and slab opening match the traced contract`() {
        val treads = geometry.primitivesFor(model.stairId).filterIsInstance<SlabGeometry>()
        assertEquals(17, Grid.STAIR_RISER_COUNT)
        assertEquals(Grid.STAIR_RISER_COUNT, treads.size)

        // Envelope: every tread inside the traced well; none inside the core.
        treads.flatMap { it.outline }.forEach { corner ->
            assertTrue(corner.x >= Grid.STAIR_WEST_X - 1e-9 && corner.x <= Grid.STAIR_EAST_X + 1e-9)
            assertTrue(corner.z >= Grid.STAIR_NORTH_Z - 1e-9 && corner.z <= Grid.STAIR_SOUTH_Z + 1e-9)
        }
        treads.forEach { tread ->
            val centreX = tread.outline.sumOf { it.x } / tread.outline.size
            val centreZ = tread.outline.sumOf { it.z } / tread.outline.size
            val inCore = centreX > Grid.STAIR_WEST_X && centreX < Grid.STAIR_CORE_EAST_X &&
                centreZ > Grid.STAIR_CORE_NORTH_Z && centreZ < Grid.STAIR_CORE_SOUTH_Z
            assertTrue("A tread sits in the core", !inCore)
        }

        // Runs: four rectangles east along the south band, five north along
        // the east band, four west along the north band; two winders each turn.
        val (southRisers, eastRisers, northRisers) = Grid.STAIR_RUN_RISERS
        val south = treads.subList(0, southRisers)
        val southEastTurn = treads.subList(southRisers, southRisers + 2)
        val east = treads.subList(southRisers + 2, southRisers + 2 + eastRisers)
        val northEastTurn = treads.subList(southRisers + 2 + eastRisers, southRisers + 4 + eastRisers)
        val north = treads.subList(southRisers + 4 + eastRisers, treads.size)
        assertEquals(northRisers, north.size)

        south.forEach { assertEquals(4, it.outline.size) }
        south.zipWithNext { a, b -> assertTrue(b.outline.minOf { it.x } > a.outline.minOf { it.x }) }
        south.forEach { assertEquals(Grid.STAIR_SOUTH_Z, it.outline.maxOf { p -> p.z }, 1e-9) }
        east.zipWithNext { a, b -> assertTrue(b.outline.minOf { it.z } < a.outline.minOf { it.z }) }
        east.forEach { assertEquals(Grid.STAIR_EAST_X, it.outline.maxOf { p -> p.x }, 1e-9) }
        north.zipWithNext { a, b -> assertTrue(b.outline.minOf { it.x } < a.outline.minOf { it.x }) }
        north.forEach { assertEquals(Grid.STAIR_NORTH_Z, it.outline.minOf { p -> p.z }, 1e-9) }
        (southEastTurn + northEastTurn).forEach { winder ->
            assertEquals("A winder is a triangle", 3, winder.outline.size)
            assertTrue(winder.outline.any { abs(it.x - Grid.STAIR_CORE_EAST_X) < 1e-9 })
        }
        // No tread overlaps another in plan: the turns are not a heap.
        assertEquals(
            (Grid.STAIR_CORE_EAST_X - Grid.STAIR_WEST_X) * (Grid.STAIR_SOUTH_Z - Grid.STAIR_CORE_SOUTH_Z) +
                (Grid.STAIR_EAST_X - Grid.STAIR_CORE_EAST_X) * (Grid.STAIR_SOUTH_Z - Grid.STAIR_NORTH_Z) +
                (Grid.STAIR_CORE_EAST_X - Grid.STAIR_WEST_X) * (Grid.STAIR_CORE_NORTH_Z - Grid.STAIR_NORTH_Z),
            treads.sumOf { it.planArea },
            1e-6,
        )

        // Rise: one riser each, from 0.00 to +3.06.
        treads.zipWithNext { lower, upper -> assertEquals(Grid.STAIR_RISER_HEIGHT, upper.elevation - lower.elevation, 1e-9) }
        assertEquals(Grid.UPPER_FLOOR_Y, treads.last().topElevation, 1e-9)

        // Arrival: the top tread ends at the well's west edge, and the
        // corridor's east partition is open there — the arrow's line.
        assertEquals(Grid.STAIR_WEST_X, treads.last().outline.minOf { it.x }, 1e-9)
        val corridorEast = geometry.primitives.filterIsInstance<WallGeometry>()
            .filter { it.elementId == BuildingElementId("marcowki-v1-poddasze-scianka-korytarza-wschod") }
        assertEquals(2, corridorEast.size)
        assertTrue(corridorEast.none { wall -> wall.coversZ(Grid.Z_UF_BEDROOM_LAUNDRY + 0.01, Grid.STAIR_CORE_NORTH_Z - 0.01) })
        // Departure: the hall's east partition is open where the bottom
        // flight leaves the hall.
        val hallEast = geometry.primitives.filterIsInstance<WallGeometry>()
            .filter { it.elementId == BuildingElementId("marcowki-v1-parter-scianka-holu-wschod") }
        assertEquals(2, hallEast.size)
        assertTrue(hallEast.none { wall -> wall.coversZ(Grid.STAIR_CORE_SOUTH_Z + 0.01, Grid.STAIR_SOUTH_Z - 0.01) })

        // Slab opening: the attic floor is cut around the whole well.
        val slabPieces = geometry.primitivesFor(BuildingElementId("marcowki-v1-strop-nad-parterem")).filterIsInstance<SlabGeometry>()
        val wellCentre = PlanPoint((Grid.STAIR_WEST_X + Grid.STAIR_EAST_X) / 2, (Grid.STAIR_NORTH_Z + Grid.STAIR_SOUTH_Z) / 2)
        assertTrue(slabPieces.none { piece -> piece.contains(wellCentre) })
        treads.forEach { tread ->
            val c = PlanPoint(tread.outline.sumOf { it.x } / tread.outline.size, tread.outline.sumOf { it.z } / tread.outline.size)
            assertTrue("The slab covers a tread", slabPieces.none { it.contains(c) })
        }
    }

    @Test
    fun `C013E-06 internal doors exist for the source-supported openings and do not seal their partitions`() {
        assertEquals(12, Grid.allInternalDoors.size)
        val doors = building.elements.filter { it.kind == BuildingElementKind.DOOR && it.id.value.contains("drzwi") }
        val internal = doors.filter { it.roomIds.size == 2 && it.id.value !in setOf("marcowki-v1-parter-drzwi-garaz-kotlownia") }
        assertEquals(12, internal.size)

        val walls = geometry.primitives.filterIsInstance<WallGeometry>()
        internal.forEach { door ->
            val hole = walls.flatMap { wall -> wall.openings.map { wall to it } }.single { (_, o) -> o.elementId == door.id }
            val (wall, opening) = hole
            // The hole is in a partition, the storey's floor is its sill, and
            // the wall is not baked across it.
            assertEquals(Grid.PARTITION_THICKNESS, wall.thickness, 1e-9)
            assertEquals(wall.baseElevation, opening.sillElevation, 1e-9)
            assertTrue(opening.height >= 1.9)
            val mesh = wall.toRenderMesh()
            val along = if (abs(wall.end.x - wall.start.x) >= abs(wall.end.z - wall.start.z)) 0 else 2
            val origin = if (along == 0) wall.start.x else wall.start.z
            val from = origin + opening.distanceFromStart + 0.01
            val to = origin + opening.distanceToEnd - 0.01
            for (v in 0 until mesh.vertexCount) {
                val a = mesh.positions[v * 3 + along].toDouble()
                val y = mesh.positions[v * 3 + 1].toDouble()
                assertTrue(
                    "${door.id.value}: wall ${wall.elementId.value} is solid inside its door",
                    !(a > from && a < to && y > opening.sillElevation + 0.01 && y < opening.headElevation - 0.01),
                )
            }
            // A leaf of its own, on its own element.
            assertTrue(geometry.primitivesFor(door.id).any { it is OpeningPanelGeometry })
        }

        // Every room a person walks into has a door or an open side.
        val entered = internal.flatMap { it.roomIds }.toSet()
        listOf("lazienka", "pokoj", "spizarnia", "wiatrolap", "kotlownia").forEach { slug ->
            assertTrue("${model.groundFloorId.value}-$slug", entered.any { it.value == "${model.groundFloorId.value}-$slug" })
        }
        listOf("pokoj-1", "pokoj-2", "pokoj-3", "garderoba-1", "garderoba-2", "pralnia", "lazienka").forEach { slug ->
            assertTrue("${model.atticId.value}-$slug", entered.any { it.value == "${model.atticId.value}-$slug" })
        }

        // The wall between the corridor and the north-east bedroom now exists
        // across the corridor's width.
        val bedroomWall = walls.filter { it.elementId == BuildingElementId("marcowki-v1-poddasze-scianka-pokoj-garderoba") }
        assertEquals(Grid.X_UF_CORRIDOR_WEST, bedroomWall.minOf { it.start.x }, 1e-9)
        assertEquals(Grid.X_HOUSE_EAST_WALL, bedroomWall.maxOf { it.end.x }, 1e-9)
        assertEquals(2, bedroomWall.sumOf { it.openings.size })
    }

    @Test
    fun `C013E-07 door and room links are same-floor and valid`() {
        val doors = building.elements.filter { it.kind == BuildingElementKind.DOOR }
        doors.forEach { door ->
            val floorId = requireNotNull(door.floorId) { "${door.id.value} must be on a storey" }
            door.roomIds.forEach { roomId ->
                val floor = requireNotNull(building.floorOfRoom(roomId)) { "${roomId.value} is not a room" }
                assertEquals("${door.id.value} links a room on another storey", floorId, floor.id)
            }
        }
        // And a door links the wall's own rooms, never a third.
        val walls = geometry.primitives.filterIsInstance<WallGeometry>()
        val byId = building.elements.associateBy(BuildingElement::id)
        walls.flatMap { wall -> wall.openings.map { wall to it } }.forEach { (wall, opening) ->
            val wallRooms = byId.getValue(wall.elementId).roomIds
            val doorRooms = byId.getValue(opening.elementId).roomIds
            assertTrue("${opening.elementId.value} links rooms its wall does not", wallRooms.containsAll(doorRooms))
        }
    }

    @Test
    fun `C013E-08 glass remains presentation metadata, not domain state`() {
        assertEquals(model.balustradeIds.toSet(), MarcowkiVisualPresentation.surfaceRoles.keys)
        assertTrue(GlassPresentation.COLOR.max() - GlassPresentation.COLOR.min() < 0.1f)
        assertTrue(GlassPresentation.ALPHA in 0.25f..0.5f)
        listOf(
            "app/src/main/java/com/buildplan/app/domain",
            "app/src/main/java/com/buildplan/app/geometry",
        ).forEach { path ->
            File(repositoryRoot(), path).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                listOf("VisualSurfaceRole", "GlassPresentation", "DecompositionGroup", "DecompositionProfile", "RenderStyle")
                    .forEach { assertTrue("${file.path} knows $it", !text.contains(it)) }
            }
        }
    }

    @Test
    fun `C013E-09 rendering style selection duplicates neither geometry nor semantic truth`() {
        assertEquals(2, RenderStyle.entries.size)
        RenderStyle.entries.forEach { style ->
            // Monochrome: no channel strays from the others.
            listOf(style.background, style.surface, style.edge).forEach { color ->
                val rgb = color.take(3)
                assertTrue("${style.name} carries a hue", rgb.max() - rgb.min() < 0.06f)
            }
        }
        val renderer = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/FilamentModelRenderer.kt").readText()
        val applyStyle = renderer.substringAfter("private fun applyStyle").substringBefore("private fun buildSkybox")
        listOf("uploadMesh", "toRenderMesh", "VertexBuffer", "IndexBuffer", "createInstance", "primitives").forEach {
            assertTrue("A style change must not touch geometry: $it", !applyStyle.contains(it))
        }
        val styleSource = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/RenderStyle.kt").readText()
        listOf("Building", "Geometry", "Visibility", "elementId", "roomIds").forEach {
            assertTrue("RenderStyle must not know the model: $it", !styleSource.contains(it))
        }
    }

    @Test
    fun `C013E-10 to 14 guardrails - grid, filament, rasters, analyzer, device`() {
        assertTrue(building.elements.none { it.id.value.contains("grid", ignoreCase = true) })
        listOf("app/src/main/java/com/buildplan/app/domain", "app/src/main/java/com/buildplan/app/geometry", "app/src/debug/java/com/buildplan/app/reference", "app/src/debug/java/com/buildplan/app/presentation")
            .forEach { path ->
                assertTrue(File(repositoryRoot(), path).walkTopDown().none { it.isFile && it.extension == "kt" && it.readText().contains("PresentationGrid") })
            }
        assertTrue(repositoryFile("gradle/libs.versions.toml").readText().contains(Regex("""filament\s*=\s*"1\.75\.1"""")))
        assertTrue(!repositoryFile("app/build.gradle.kts").readText().lowercase().contains("sceneview"))

        val rasters = setOf("gif", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "html", "htm", "pdf", "png")
        assertTrue(repositoryFiles().none { it.extension.lowercase() in rasters })

        // STAGE-023A authorised a research prototype with a bounded footprint
        // (core in `:analyzer`, in-app consumer only the debug Lab and the
        // `SceneModel` seam); OCR, vision and ML libraries stay forbidden.
        val forbidden = Regex("""(?i)\b(ocr|tesseract|opencv|mlkit|tflite)\b""")
        val analyzerish = Regex("""(?i)\b(analyzer|analizator)\b""")
        // Widened in STAGE-024 for the same reason as in Stage013CSignatureTest: the analyzer's
        // platform bindings and its one product screen are authorised; everything else is not.
        // Widened again in STAGE-025 for the verification workspace and its variant-specific
        // canvas; the reason is the same one that admitted the import screen in STAGE-024.
        val previewFiles = setOf("/analyzer/preview/CandidateGeometry.kt", "/ui/components/AutomaticHouseCanvas.kt") // STAGE-025A read-only candidate rendering in release.
        val authorised = Regex(
            """app[\\/]src[\\/]debug[\\/].*[\\/](analyzer[\\/]lab[\\/][^\\/]+|render[\\/]filament[\\/]SceneModel)\.kt$""" +
                """|app[\\/]src[\\/](main|debug|release)[\\/]java[\\/]com[\\/]buildplan[\\/]app[\\/](analyzer[\\/][^\\/]+|ui[\\/]screens[\\/](ProjectImport|Verification)[A-Za-z]*)\.kt$""",
        )
        val analyzerOffenders = listOf("app/src/main", "app/src/debug").flatMap { File(repositoryRoot(), it).walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                forbidden.containsMatchIn(text) || (analyzerish.containsMatchIn(text) && (!authorised.containsMatchIn(file.path) && previewFiles.none { file.path.replace('\\', '/').endsWith(it) }))
            }
        assertTrue("Analyzer code outside its authorised footprint: $analyzerOffenders", analyzerOffenders.isEmpty())

        val serials = Regex("""emulator-(\d{4})""")
        repositoryFiles()
            .filter { it.extension.lowercase() in setOf("kt", "kts", "md", "xml", "toml", "properties", "sh") && it.name != "CLAUDE.md" }
            .forEach { file -> serials.findAll(file.readText()).forEach { assertEquals(file.path, "5570", it.groupValues[1]) } }
    }

    // --- helpers -----------------------------------------------------

    private fun SpikeVisibility.visibility(): BuildingVisibility = toBuildingVisibility(model.atticId)

    private fun WallGeometry.coversZ(fromZ: Double, toZ: Double): Boolean {
        val zs = footprint().map { it.z }
        return zs.min() <= fromZ && zs.max() >= toZ
    }

    private fun SlabGeometry.contains(point: PlanPoint): Boolean {
        val xs = outline.map { it.x }
        val zs = outline.map { it.z }
        return point.x > xs.min() + 1e-9 && point.x < xs.max() - 1e-9 && point.z > zs.min() + 1e-9 && point.z < zs.max() - 1e-9
    }

    private fun DoubleArray.near(other: DoubleArray): Boolean =
        abs(this[0] - other[0]) < 1e-4 && abs(this[1] - other[1]) < 1e-4 && abs(this[2] - other[2]) < 1e-4

    private fun com.buildplan.app.render.filament.BuildingRenderMesh.segments(): List<Pair<DoubleArray, DoubleArray>> =
        (0 until edgeCount).map { edge ->
            val a = edgeIndices[edge * 2]
            val b = edgeIndices[edge * 2 + 1]
            doubleArrayOf(positions[a * 3].toDouble(), positions[a * 3 + 1].toDouble(), positions[a * 3 + 2].toDouble()) to
                doubleArrayOf(positions[b * 3].toDouble(), positions[b * 3 + 1].toDouble(), positions[b * 3 + 2].toDouble())
        }

    private fun repositoryFile(relativePath: String): File =
        File(repositoryRoot(), relativePath).also { assertTrue("Expected $relativePath", it.isFile) }

    private fun repositoryFiles(): List<File> {
        val skipped = setOf(".git", ".gradle", ".idea", "build", ".kotlin", "references")
        return repositoryRoot().walkTopDown().onEnter { it.name !in skipped }.filter { it.isFile }.toList()
    }

    private fun repositoryRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
