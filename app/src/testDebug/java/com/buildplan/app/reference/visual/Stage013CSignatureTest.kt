package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.GablePanelGeometry
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.PresentationGrid
import com.buildplan.app.render.filament.SpikeVisibility
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * STAGE-013C — the signature features, stated as things that can fail.
 *
 * The owner's second verdict was not that a dimension was wrong either. It was
 * that the model was *a* modern house rather than *this* one: the band that runs
 * round both gables and across the garage was missing, and the garage stopped a
 * third of a metre below the balcony beside it, so nothing tied the two masses
 * together. Those are facts about geometry, so they are checked here — a
 * screenshot proves the frame it was taken in and nothing about the next
 * correction.
 *
 * The tests are deliberately written against *relationships* rather than
 * against numbers copied out of the model. "The garage top and the band top are
 * the same level" fails when either moves; "the garage top is 3.06" would pass a
 * model where the band had drifted away from it.
 */
class Stage013CSignatureTest {

    private val model = MarcowkiVisualModelV1
    private val building = model.building
    private val geometry = model.geometry
    private val states = SpikeVisibility.entries

    /** Two traced coordinates may differ by this and still be the same line. */
    private val toleranceMeters = 1e-6

    @Test
    fun `C013C-01 one canonical model still underlies every visibility state`() {
        // Same object, not merely equal contents: two states reading two
        // separately built models is exactly the drift that started this.
        assertTrue(DebugModel.MARCOWKI.geometry === geometry)
        assertTrue(DebugModel.MARCOWKI.building === building)

        val everything = geometry.elementIds
        states.forEach { state ->
            assertTrue(
                "$state draws elements the canonical model does not have",
                everything.containsAll(visibleIds(state)),
            )
        }
        assertEquals(everything, states.flatMapTo(LinkedHashSet()) { visibleIds(it) })

        // The plan extent is identical in every state, to the millimetre. This
        // is the owner's "same house" made into a number, and it now has to
        // survive the bands as well.
        val extents = states.map { state ->
            val visible = visibleIds(state)
            geometry.primitives
                .filter { it.elementId in visible }
                .map { it.bounds }
                .reduce { total, next -> total.encompass(next) }
                .let { listOf(it.min.x, it.min.z, it.max.x, it.max.z) }
        }
        assertEquals("Hiding a layer must not move the building", 1, extents.toSet().size)

        // The frame belongs to the whole building rather than to a storey, so
        // the gable outline is on screen in every state. It is what lets a
        // reviewer take the roof off and still recognise the house.
        val alwaysVisible = states
            .map { visibleIds(it) }
            .reduce { common, next -> common.filterTo(LinkedHashSet()) { it in next } }
        model.gableFrameIds.forEach { frameId ->
            assertTrue(
                "${frameId.value} must survive every toggle",
                frameId in alwaysVisible,
            )
        }
    }

    @Test
    fun `C013C-02 the signature frame and band exist as deliberate geometry`() {
        // --- the frame around each gable portal --------------------------

        assertEquals(2, model.gableFrameIds.size)
        model.gableFrameIds.forEachIndexed { index, frameId ->
            val element = building.elements.single { it.id == frameId }
            assertEquals(
                "The frame spans both storeys, so it belongs to neither",
                BuildingElementScope.WholeBuilding,
                element.scope,
            )

            val faceZ = if (index == 0) Grid.Z_PORTAL_NORTH_FACE else Grid.Z_PORTAL_SOUTH_FACE
            // The front face stands a hair proud of the portal face, so the
            // mitre can overlap the cheek without sharing its plane (STAGE-013D).
            val frontZ = if (index == 0) faceZ - Grid.GABLE_FRAME_PROUD else faceZ + Grid.GABLE_FRAME_PROUD
            val panels = geometry.primitivesFor(frameId).filterIsInstance<GablePanelGeometry>()
            assertEquals("One raking front face per slope", 2, panels.size)

            panels.forEach { panel ->
                // In the portal's own face plane, a metre in front of the gable
                // wall. A frame drawn back on the wall is not a frame.
                panel.vertices.forEach { vertex ->
                    assertEquals(
                        "${frameId.value} leaves the portal face plane",
                        frontZ,
                        vertex.z,
                        toleranceMeters,
                    )
                }

                // Its upper edge is the roof, exactly. Every vertex is either on
                // the roof underside or on the frame's own inner line below it,
                // and none is above the roof.
                panel.vertices.forEach { vertex ->
                    assertTrue(
                        "${frameId.value} reaches ${vertex.y} at x=${vertex.x}, through a " +
                            "roof at ${Grid.roofUndersideAt(vertex.x)}",
                        vertex.y <= Grid.roofUndersideAt(vertex.x) + toleranceMeters,
                    )
                }
                assertTrue(
                    "A frame piece must touch the roof it follows",
                    panel.vertices.any {
                        abs(it.y - Grid.roofUndersideAt(it.x)) < toleranceMeters
                    },
                )
            }

            // The frame is one band of constant width, and that width is the
            // cheek's own thickness. Checked through the geometry the model
            // actually built rather than through the constant it was built
            // from: the inner corner at the ridge is one frame width from the
            // roof plane, measured perpendicular to it.
            val innerRidgeY = panels.flatMap { it.vertices }
                .filter { abs(it.x - Grid.RIDGE_X) < toleranceMeters }
                .minOf { it.y }
            assertEquals(
                "The frame is not one cheek thick where the two slopes meet",
                Grid.GABLE_FRAME_WIDTH,
                (Grid.RIDGE_Y - innerRidgeY) * cos(Math.toRadians(Grid.ROOF_PITCH_DEGREES)),
                1e-9,
            )

            // Deliberate, not a seam: the frame is a metre in front of the
            // gable wall it surrounds, and it is a surface rather than a line.
            val gableWallZ = geometry.primitives
                .filterIsInstance<WallGeometry>()
                .single { it.elementId.value.contains("sciana-szczytowa") && facing(it, faceZ) }
                .start.z
            assertTrue(
                "The frame must stand clear of the gable wall behind it",
                abs(gableWallZ - faceZ) >= Grid.PORTAL_DEPTH - Grid.EXTERIOR_WALL_THICKNESS,
            )
            assertTrue(
                "A frame this small would read as a seam",
                panels.sumOf { it.area } > 3.5,
            )
        }

        // --- the band that ties the house to the garage -------------------

        val bandRuns = geometry.primitivesFor(model.storeyBandId).filterIsInstance<WallGeometry>()
        assertEquals("One run along each portal front", 2, bandRuns.size)
        assertEquals(
            BuildingElementScope.OnFloor(model.atticId),
            building.elements.single { it.id == model.storeyBandId }.scope,
        )

        bandRuns.forEach { run ->
            assertEquals(Grid.STOREY_BAND_TOP_Y, run.topElevation, toleranceMeters)
            assertEquals(Grid.STOREY_BAND_BASE_Y, run.baseElevation, toleranceMeters)

            // Its outer face is the portal face, so band and frame sit in one
            // plane and read as one composition rather than as two details.
            val outerZ = run.footprint().map { it.z }.let { z ->
                if (run.start.z < 0.0) z.min() else z.max()
            }
            val expected =
                if (run.start.z < 0.0) Grid.Z_PORTAL_NORTH_FACE else Grid.Z_PORTAL_SOUTH_FACE
            assertEquals("The band must be flush with the portal face", expected, outerZ, 1e-9)

            // And it stops against the cheeks — the elevations draw the band
            // between the frame, not through it, and running it through would
            // put a second face in the plane the frame occupies. The north run
            // fills its portal; the south run starts where its balcony starts
            // (STAGE-013D) and ends where the garage roof takes the line over.
            if (run.start.z < 0.0) {
                assertEquals(Grid.PORTAL_CHEEK_THICKNESS, run.start.x, 1e-9)
                assertEquals(Grid.HOUSE_WIDTH - Grid.PORTAL_CHEEK_THICKNESS, run.end.x, 1e-9)
            } else {
                assertEquals(Grid.X_SOUTH_BALCONY_WEST, run.start.x, 1e-9)
                assertEquals(Grid.HOUSE_WIDTH - Grid.EXTERIOR_WALL_THICKNESS, run.end.x, 1e-9)
            }
        }

        // The balcony slab between the cheeks is taken back by exactly the
        // band's thickness, so the two never share a plane. The pieces inside
        // the cheeks do reach the portal face — that is their job — and they
        // lie outside the band's run, so nothing is coplanar with it.
        val betweenCheeks = Grid.PORTAL_CHEEK_THICKNESS..(Grid.HOUSE_WIDTH - Grid.PORTAL_CHEEK_THICKNESS)
        val balconyFronts = geometry.primitivesFor(BuildingElementId("marcowki-v1-strop-nad-parterem"))
            .filterIsInstance<SlabGeometry>()
            .filter { slab -> slab.outline.all { it.x in betweenCheeks } }
            .flatMap { it.outline }
        assertTrue(balconyFronts.isNotEmpty())
        assertTrue(
            "No piece of storey slab between the cheeks may reach the portal face the band occupies",
            balconyFronts.none { it.z < Grid.Z_PORTAL_NORTH_FACE + Grid.STOREY_BAND_THICKNESS - 1e-9 } &&
                balconyFronts.none {
                    it.z > Grid.Z_PORTAL_SOUTH_FACE - Grid.STOREY_BAND_THICKNESS + 1e-9
                },
        )
    }

    @Test
    fun `C013C-03 the garage reads as part of the composition, not beside it`() {
        val garageRoof = geometry
            .primitivesFor(BuildingElementId("marcowki-v1-stropodach-garazu"))
            .filterIsInstance<SlabGeometry>()
            .single()

        // The one relationship this stage is about: the garage top and the
        // house band are one level and one depth, so the line across the
        // composition is continuous rather than two lines that nearly agree.
        val band = geometry.primitivesFor(model.storeyBandId).filterIsInstance<WallGeometry>()
        assertEquals(
            "The garage top and the storey band must be one line",
            band.first().topElevation,
            garageRoof.topElevation,
            toleranceMeters,
        )
        assertEquals(
            "The garage fascia and the storey band must be one depth",
            band.first().height,
            garageRoof.thickness,
            toleranceMeters,
        )
        assertEquals(Grid.UPPER_FLOOR_Y, garageRoof.topElevation, toleranceMeters)

        // And the two runs meet in plan: the band ends at the house's east
        // cheek, and the garage roof starts at the same wall, so nothing but
        // the cheek stands between them.
        assertEquals(
            Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, towardsPositive = false),
            garageRoof.outline.minOf { it.x },
            1e-9,
        )
        assertEquals(
            "The garage roof must still reach the south portal face the band runs along",
            Grid.Z_PORTAL_SOUTH_FACE,
            garageRoof.outline.maxOf { it.z },
            1e-9,
        )
        assertEquals(Grid.BUILDING_WIDTH, garageRoof.outline.maxOf { it.x }, 1e-9)

        // Nothing about the garage itself regressed while its roof was raised.
        listOf(
            "garaz-sciana-polnocna",
            "garaz-sciana-wschodnia",
            "sciana-miedzy-domem-a-garazem",
        ).forEach { slug ->
            assertNotNull(
                "$slug disappeared with the roof correction",
                geometry.primitives.firstOrNull { it.elementId == elementId(slug) },
            )
        }
        val garageDoors = geometry.primitives
            .filterIsInstance<WallGeometry>()
            .flatMap { it.openings }
            .filter { it.elementId.value.contains("garaz") }
        assertEquals("The garage keeps its door, side door and inner door", 3, garageDoors.size)
    }

    @Test
    fun `C013C-04 the STAGE-013B opening work is untouched`() {
        val openings = geometry.primitives
            .filterIsInstance<WallGeometry>()
            .flatMap { wall -> wall.openings.map { wall to it } }

        assertEquals(
            "Every opening in the traced schedule must still be cut",
            Grid.allOpenings.size,
            openings.size,
        )
        openings.forEach { (wall, opening) ->
            assertTrue(
                "A hole and the wall it is in must not be the same element",
                opening.elementId != wall.elementId,
            )
            assertTrue(
                "${opening.elementId.value} lost its pane",
                geometry.primitivesFor(opening.elementId).any { it is OpeningPanelGeometry },
            )
        }

        // Named per facade rather than counted in total, so a window that moved
        // to another wall fails instead of balancing out. The bands touch three
        // of these walls, which is exactly why the counts are re-asserted here.
        mapOf(
            "parter-sciana-polnocna" to 1,
            "parter-sciana-poludniowa" to 3,
            "parter-sciana-zachodnia" to 2,
            "parter-sciana-wschodnia" to 1,
            "garaz-sciana-polnocna" to 1,
            "sciana-miedzy-domem-a-garazem" to 1,
            "poddasze-sciana-szczytowa-polnocna" to 2,
            "poddasze-sciana-szczytowa-poludniowa" to 1,
        ).forEach { (slug, expected) ->
            val openings = geometry.primitives
                .filterIsInstance<WallGeometry>()
                .filter { it.elementId == elementId(slug) }
                .sumOf { it.openings.size }
            assertEquals("$slug carries the wrong number of openings", expected, openings)
        }

        // The panes still sit back inside their reveals rather than on a face:
        // that recess is the only depth the facade openings have, and it is what
        // this stage was asked not to lose.
        val northWall = geometry.primitives
            .filterIsInstance<WallGeometry>()
            .single { it.elementId == elementId("parter-sciana-polnocna") }
        val pane = geometry.primitivesFor(northWall.openings.single().elementId)
            .filterIsInstance<OpeningPanelGeometry>()
            .single()
        assertEquals(
            "The pane must stay on the wall's centre plane, half a wall inside each face",
            northWall.start.z,
            pane.bounds.min.z,
            1e-9,
        )
    }

    @Test
    fun `C013C-05 hiding the roof and hiding the attic are still subset operations`() {
        val everything = visibleIds(SpikeVisibility.EVERYTHING)

        val roofOff = visibleIds(SpikeVisibility.ROOF_HIDDEN)
        assertEquals(
            "Hiding the roof must remove exactly the roof elements",
            building.elements
                .filter { it.kind == BuildingElementKind.ROOF }
                .mapTo(LinkedHashSet()) { it.id },
            everything - roofOff,
        )

        val atticOff = visibleIds(SpikeVisibility.UPPER_FLOOR_HIDDEN)
        assertEquals(
            "Hiding the attic must remove exactly the attic elements",
            building.elements
                .filter { it.floorId == model.atticId }
                .mapTo(LinkedHashSet()) { it.id },
            everything - atticOff,
        )

        // The band goes with the storey whose floor edge it is — that is its one
        // honest owner — and the frame stays, because it is neither storey's.
        assertTrue(model.storeyBandId in everything)
        assertTrue("The band is the attic slab's edge", model.storeyBandId !in atticOff)
        assertTrue("The band is not part of the roof", model.storeyBandId in roofOff)
        model.gableFrameIds.forEach { frameId ->
            assertTrue(frameId in roofOff)
            assertTrue(frameId in atticOff)
        }

        // Both toggles are subsets, in both orders, every time.
        listOf(roofOff, atticOff, visibleIds(SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN))
            .forEach { assertTrue(everything.containsAll(it)) }
        assertEquals(everything, visibleIds(SpikeVisibility.EVERYTHING))
    }

    @Test
    fun `C013C-06 the reference grid is presentation only and never model geometry`() {
        val bounds = requireNotNull(geometry.bounds)
        val grid = PresentationGrid.under(bounds)

        assertTrue("The grid must actually draw something", grid.lineCount > 20)
        assertEquals("Two densities, both present", 0, grid.minorIndices.size % 2)
        assertTrue(grid.majorIndices.isNotEmpty())

        // One flat plane, below everything the model draws, so the building
        // stands on it rather than being cut by it.
        val gridY = (0 until grid.positions.size / 3).map { grid.positions[it * 3 + 1] }
        assertEquals("The grid is a plane, not a surface", 1, gridY.toSet().size)
        assertTrue(
            "The grid must sit below the model's lowest face",
            gridY.first() < bounds.min.y,
        )

        // It reaches past the building on all four sides — a grid cropped to the
        // footprint reads as a floor slab rather than as a reference plane.
        val gridX = (0 until grid.positions.size / 3).map { grid.positions[it * 3] }
        val gridZ = (0 until grid.positions.size / 3).map { grid.positions[it * 3 + 2] }
        assertTrue(gridX.min() < bounds.min.x && gridX.max() > bounds.max.x)
        assertTrue(gridZ.min() < bounds.min.z && gridZ.max() > bounds.max.z)

        // And none of it is in the model. No element owns it, no primitive draws
        // it, and neither the domain nor the geometry package has heard of it —
        // which is the difference between a presentation aid and terrain.
        assertTrue(building.elements.none { it.id.value.contains("grid", ignoreCase = true) })
        assertTrue(geometry.elementIds.none { it.value.contains("grid", ignoreCase = true) })

        listOf("app/src/main/java/com/buildplan/app/domain", "app/src/main/java/com/buildplan/app/geometry")
            .forEach { path ->
                val offenders = File(repositoryRoot(), path).walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { file ->
                        val text = file.readText()
                        text.contains("PresentationGrid") || text.contains("render.filament")
                    }
                    .toList()
                assertTrue("$path must not know about the renderer: $offenders", offenders.isEmpty())
            }
    }

    @Test
    fun `C013C-07 this stage committed no drawing, render or captured evidence`() {
        val evidenceNames = listOf(
            "01_FULL_AXON", "02_SIGNATURE_FACADE_CLOSEUP", "03_ROOF_OFF_AXON", "04_GROUND_TOP",
            "05_UPPER_TOP", "06_ROOF_AND_GARAGE_RELATION", "07_GRID_PRESENTATION",
            "08_CONTINUITY_2X2", "09_STAIRS_AND_CUTAWAY", "10_UI_CONTROLS_READABLE",
            "11_FEATURE_CALLOUT", "elew-", "elewacja-", "rzut-", "przekroj",
        )
        val offenders = repositoryFiles().filter { file ->
            evidenceNames.any { file.name.startsWith(it) } ||
                file.extension.lowercase() in setOf("gif", "jpg", "jpeg", "webp", "bmp", "psd", "tif")
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Source drawings and captured evidence live outside the worktree. Found:\n" +
                    offenders.joinToString("\n") { it.path },
            )
        }

        // The four elevations this stage measured are recorded as URLs and
        // numbers, which is what may be kept.
        listOf(
            MarcowkiSourceEvidence.FRONT_ELEVATION_URL,
            MarcowkiSourceEvidence.GARDEN_ELEVATION_URL,
            MarcowkiSourceEvidence.SIDE_ELEVATION_WEST_URL,
            MarcowkiSourceEvidence.SIDE_ELEVATION_EAST_URL,
        ).forEach { url ->
            assertTrue("$url is not an ARCHON asset URL", url.startsWith("https://assets.archon.pl/"))
        }
        assertTrue(MarcowkiSourceEvidence.ELEVATION_CALIBRATION.contains("px/m"))
        assertTrue(MarcowkiSourceEvidence.ELEVATIONS_RETRIEVED_AT.startsWith("2026-"))
    }

    @Test
    fun `C013C-08 the only emulator this project names is 5570`() {
        val serials = Regex("""emulator-(\d{4})""")
        repositoryFiles()
            .filter {
                it.extension.lowercase() in setOf("kt", "kts", "md", "xml", "toml", "properties")
            }
            // CLAUDE.md names the forbidden serials in order to forbid them;
            // everywhere else, naming one is using one.
            .filter { it.name != "CLAUDE.md" }
            .forEach { file ->
                serials.findAll(file.readText()).forEach { match ->
                    assertEquals(
                        "${file.path} names a device this project may not use",
                        "5570",
                        match.groupValues[1],
                    )
                }
            }
    }

    @Test
    fun `C013C-09 the signature work added no dependency and relaxed no gate`() {
        val appBuild = repositoryFile("app/build.gradle.kts").readText()

        // The bands are geometry and the grid is a line list; neither needs a
        // library, and reaching for one would be the wrong answer to "make it
        // look more like the house".
        assertTrue(
            "This stage adds no 3D or drawing library",
            !Regex("""(?i)(sceneview|rajawali|libgdx|gltf|three|arcore|opencv|tess)""")
                .containsMatchIn(appBuild),
        )
        assertTrue(appBuild.contains("debugImplementation(libs.filament.android)"))
        assertTrue(appBuild.contains("debugImplementation(libs.filamat.android)"))
        assertTrue(
            "Filament must stay out of release",
            !appBuild.contains("implementation(libs.filament.android)"),
        )

        // The gates stay where they were. A stage that made the model prettier
        // by turning lint down would have improved nothing.
        assertTrue("Lint must still fail the build", appBuild.contains("abortOnError = true"))
        assertTrue(
            "No lint baseline may be introduced",
            !appBuild.contains("baseline") &&
                repositoryFiles().none { it.name.startsWith("lint-baseline") },
        )
        val suppressions = repositoryFiles()
            .filter { it.extension == "kt" }
            .filter { it.readText().contains(suppressAnnotation) }
        assertTrue("Nothing may be suppressed to pass: $suppressions", suppressions.isEmpty())
    }

    @Test
    fun `C013C-10 the future analyzer is specified in prose and absent from the code`() {
        val architecture = repositoryFile("ARCHITECTURE.md").readText()

        // The contract has to say what it needs, what it must recover, and when
        // it has to stop and ask. A section that only said "the analyzer will
        // read plans" would be a plan, not a contract.
        listOf(
            "Kontrakt przyszłego analizatora",
            "Wejścia wymagane",
            "Cechy rozpoznawcze",
            "Kiedy analizator musi zapytać",
        ).forEach { heading ->
            assertTrue(
                "ARCHITECTURE.md is missing the analyzer contract heading: $heading",
                architecture.contains(heading),
            )
        }
        assertTrue(
            "The contract must name this model as the bar it has to clear",
            architecture.contains("STAGE-013C") && architecture.contains("Marcówk"),
        )

        // And none of it is implemented. The bar is set by one house first;
        // reading arbitrary drawings is a later stage and starting it early is
        // exactly what this test exists to catch.
        //
        // Shipped source only. This file argues about the analyzer in prose and
        // would otherwise report itself, which would make the guard useless in
        // the one direction that matters: it has to fail when somebody starts
        // writing one, not when somebody writes down that they must not.
        //
        // STAGE-023A later authorised a research prototype with a bounded
        // footprint: the core lives in the separate `:analyzer` module and its
        // only in-app consumer is the debug Lab. Inside the app the boundary
        // still holds — nothing analyzer-like in main or release sources, in
        // debug only under `analyzer/lab/` plus the `SceneModel` seam the Lab
        // renders through, and no OCR, vision or ML library anywhere.
        val forbidden = Regex("""(?i)\b(ocr|tesseract|opencv|imageproc)\b""")
        val analyzerish = Regex("""(?i)\b(analyzer|analizator)\b""")
        // STAGE-024 widened the footprint again, and deliberately: project import is a product
        // feature now, so the app carries the analyzer's platform bindings and one small screen
        // that calls its service. What is still forbidden is what always was — analyzer-like code
        // anywhere else in the shipped app, and any OCR, vision or ML library at all.
        // Widened in STAGE-025 for the verification workspace, which is the product's own screen
        // over the analyzer's questions: it names `RootQuestion` and `VerificationSession` the way
        // the import screen already names `Measured`. The canvas has a variant-specific half in
        // each of `debug` and `release`, so both are authorised by name and neither by wildcard.
        val previewFiles = setOf("/analyzer/preview/CandidateGeometry.kt", "/ui/components/AutomaticHouseCanvas.kt") // STAGE-025A read-only candidate rendering in release.
        val authorised = Regex(
            """app[\\/]src[\\/]debug[\\/].*[\\/](analyzer[\\/]lab[\\/][^\\/]+|render[\\/]filament[\\/]SceneModel)\.kt$""" +
                """|app[\\/]src[\\/](main|debug|release)[\\/]java[\\/]com[\\/]buildplan[\\/]app[\\/](analyzer[\\/][^\\/]+|ui[\\/]screens[\\/](ProjectImport|Verification)[A-Za-z]*)\.kt$""",
        )
        val sources = listOf("app/src/main", "app/src/debug", "app/src/release")
            .flatMap { path -> File(repositoryRoot(), path).walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
        val offenders = sources.filter { file ->
            val text = file.readText()
            forbidden.containsMatchIn(text) || forbidden.containsMatchIn(file.name) ||
                ((analyzerish.containsMatchIn(text) || analyzerish.containsMatchIn(file.name)) && (!authorised.containsMatchIn(file.path) && previewFiles.none { file.path.replace('\\', '/').endsWith(it) }))
        }
        assertTrue("Analyzer code outside its authorised footprint: $offenders", offenders.isEmpty())
    }

    // --- helpers -----------------------------------------------------

    private fun visibleIds(state: SpikeVisibility): LinkedHashSet<BuildingElementId> =
        building.visibleElements(state.toBuildingVisibility(model.atticId))
            .mapTo(LinkedHashSet(), BuildingElement::id)

    private fun elementId(slug: String): BuildingElementId =
        BuildingElementId("marcowki-v1-$slug")

    /** Whether [wall] is the gable wall on the same side of the house as [faceZ]. */
    private fun facing(wall: WallGeometry, faceZ: Double): Boolean =
        (wall.start.z < Grid.BUILDING_DEPTH / 2.0) == (faceZ < Grid.BUILDING_DEPTH / 2.0)

    private fun repositoryFile(relativePath: String): File =
        File(repositoryRoot(), relativePath).also {
            assertTrue("Expected $relativePath to exist at ${it.absolutePath}", it.isFile)
        }

    private fun repositoryFiles(): List<File> {
        // `references/` is the owner's local pack of third-party drawings: inspected,
        // git-ignored, never committed — see C013D-11.
        val skipped = setOf(".git", ".gradle", ".idea", "build", ".kotlin", "references")
        return repositoryRoot()
            .walkTopDown()
            .onEnter { it.name !in skipped }
            .filter { it.isFile }
            .toList()
    }

    /**
     * Assembled rather than written out, so that this guard does not itself
     * become the thing it forbids finding.
     */
    private val suppressAnnotation: String = "@" + "Suppress"

    private fun repositoryRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
