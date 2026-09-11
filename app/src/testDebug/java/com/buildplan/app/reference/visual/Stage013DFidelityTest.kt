package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.GablePanelGeometry
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.GlassPresentation
import com.buildplan.app.render.filament.MeshStyle
import com.buildplan.app.render.filament.ModelViewPreset
import com.buildplan.app.render.filament.PresentationGrid
import com.buildplan.app.render.filament.PresetFocus
import com.buildplan.app.render.filament.SpikeVisibility
import com.buildplan.app.render.filament.surfaceRoleOf
import com.buildplan.app.render.filament.toRenderMesh
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * STAGE-013D — the facade fidelity corrections, stated as things that can fail.
 *
 * The owner's third verdict was that the model had the right features and still
 * did not read as *this* house: the frame was a sheet with no depth, the south
 * balcony ran the full width the source does not give it, the railings and
 * glazing were opaque, and the roof had no edge. Each of those is a fact about
 * geometry or about presentation, and each is held here against the source
 * reading that fixed it — not against a screenshot, which proves only the frame
 * it was taken in.
 *
 * The presentation checks are deliberately split from the geometry checks: what
 * reads as glass is decided beside the model by element id, and the model's
 * building and geometry are asserted to be exactly what they would be without
 * that mapping.
 */
class Stage013DFidelityTest {

    private val model = MarcowkiVisualModelV1
    private val building = model.building
    private val geometry = model.geometry
    private val states = SpikeVisibility.entries

    private val toleranceMeters = 1e-6

    @Test
    fun `C013D-01 one canonical Marcowki geometry source underlies every visibility state`() {
        assertTrue(DebugModel.MARCOWKI.geometry === geometry)
        assertTrue(DebugModel.MARCOWKI.building === building)

        val everything = geometry.elementIds
        states.forEach { state ->
            assertTrue(everything.containsAll(visibleIds(state)))
        }
        assertEquals(everything, states.flatMapTo(LinkedHashSet()) { visibleIds(it) })

        // The plan extent is identical in every state, fascia and cheeks
        // included — a trim that stood outside the walls and vanished with a
        // layer would move the building.
        val extents = states.map { state ->
            val visible = visibleIds(state)
            geometry.primitives
                .filter { it.elementId in visible }
                .map { it.bounds }
                .reduce { total, next -> total.encompass(next) }
                .let { listOf(it.min.x, it.min.z, it.max.x, it.max.z) }
        }
        assertEquals("Hiding a layer must not move the building", 1, extents.toSet().size)

        // No state draws from a second model: every primitive any state shows
        // is the same object the canonical geometry holds.
        val canonical = geometry.primitives.toSet()
        states.forEach { state ->
            val visible = visibleIds(state)
            geometry.primitives.filter { it.elementId in visible }.forEach { primitive ->
                assertTrue(primitive in canonical)
            }
        }
    }

    @Test
    fun `C013D-02 roof and floor hiding remain subset operations, never model switching`() {
        val everything = visibleIds(SpikeVisibility.EVERYTHING)
        val roofOff = visibleIds(SpikeVisibility.ROOF_HIDDEN)
        val atticOff = visibleIds(SpikeVisibility.UPPER_FLOOR_HIDDEN)
        val both = visibleIds(SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN)

        assertEquals(
            building.elements.filter { it.kind == BuildingElementKind.ROOF }.mapTo(LinkedHashSet()) { it.id },
            everything - roofOff,
        )
        assertEquals(
            building.elements.filter { it.floorId == model.atticId }.mapTo(LinkedHashSet()) { it.id },
            everything - atticOff,
        )
        assertEquals(both, roofOff intersect atticOff)
        listOf(roofOff, atticOff, both).forEach { assertTrue(everything.containsAll(it)) }

        // The stacks go with the roof they rise out of; the fascia and the
        // frames stay in the *domain's* answer, as whole-building elements —
        // the presentation profile (C013E-02) takes them off the screen with
        // the roof, without touching this ownership.
        model.stackIds.forEach { assertTrue(it !in roofOff) }
        assertTrue(model.fasciaId in roofOff && model.fasciaId in atticOff && model.fasciaId in both)
        model.gableFrameIds.forEach { assertTrue(it in both) }
    }

    @Test
    fun `C013D-03 front and rear gable frames exist with depth and remain separable`() {
        assertEquals(2, model.gableFrameIds.size)
        model.gableFrameIds.forEachIndexed { index, frameId ->
            val element = building.elements.single { it.id == frameId }
            assertEquals(BuildingElementScope.WholeBuilding, element.scope)
            assertTrue(element.kind != BuildingElementKind.ROOF)

            val faceZ = if (index == 0) Grid.Z_PORTAL_NORTH_FACE else Grid.Z_PORTAL_SOUTH_FACE
            val gableFaceZ = if (index == 0) 0.0 else Grid.BUILDING_DEPTH
            val fronts = geometry.primitivesFor(frameId).filterIsInstance<GablePanelGeometry>()
            val sloped = geometry.primitivesFor(frameId).filterIsInstance<RoofFacetGeometry>()
            assertEquals("Two raking front faces", 2, fronts.size)
            assertEquals("A soffit and a top per raking bar", 4, sloped.size)

            // The bar is the cheek's thickness, measured perpendicular to the
            // roof at the ridge, and the cheek is the traced 0.64 m.
            val innerRidgeY = fronts.flatMap { it.vertices }
                .filter { abs(it.x - Grid.RIDGE_X) < toleranceMeters }
                .minOf { it.y }
            assertEquals(
                Grid.PORTAL_CHEEK_THICKNESS,
                (Grid.RIDGE_Y - innerRidgeY) * cos(Math.toRadians(Grid.ROOF_PITCH_DEGREES)),
                1e-9,
            )

            // The mitre: the inner corner sits on the leg's inner face, below
            // the eaves, as both elevations draw it.
            fronts.forEach { front ->
                val innerCorner = front.vertices.minBy { it.y }
                val legInnerX = if (innerCorner.x < Grid.RIDGE_X) {
                    Grid.PORTAL_CHEEK_THICKNESS
                } else {
                    Grid.HOUSE_WIDTH - Grid.PORTAL_CHEEK_THICKNESS
                }
                assertEquals(legInnerX, innerCorner.x, toleranceMeters)
                assertTrue(innerCorner.y < Grid.EAVES_Y)
                assertEquals(Grid.GABLE_FRAME_INNER_CORNER_Y, innerCorner.y, toleranceMeters)
            }

            // Depth: every sloped face spans the whole portal, from the front
            // face back to the gable wall, and lies under the roof plane.
            sloped.forEach { face ->
                val zs = face.vertices.map { it.z }
                assertTrue(zs.any { abs(it - gableFaceZ) < toleranceMeters })
                assertTrue(zs.any { abs(abs(it - faceZ) - Grid.GABLE_FRAME_PROUD) < toleranceMeters })
                face.vertices.forEach { vertex ->
                    assertTrue(vertex.y < Grid.roofUndersideAt(vertex.x) + toleranceMeters)
                }
            }

            // Separable: picking or hiding the frame is one id, and nothing else
            // draws from that id.
            assertTrue(geometry.primitives.filter { it.elementId == frameId }.size == 6)
        }
    }

    @Test
    fun `C013D-04 the horizontal facade band remains separable and source scoped`() {
        val band = building.elements.single { it.id == model.storeyBandId }
        assertEquals(BuildingElementScope.OnFloor(model.atticId), band.scope)
        val runs = geometry.primitivesFor(model.storeyBandId).filterIsInstance<WallGeometry>()
        assertEquals(2, runs.size)

        val north = runs.single { it.start.z < 0.0 }
        val south = runs.single { it.start.z > 0.0 }

        // North: cheek to cheek. South: from the traced balcony edge to the
        // party wall's outer face, where the garage roof continues the line.
        assertEquals(Grid.PORTAL_CHEEK_THICKNESS, north.start.x, 1e-9)
        assertEquals(Grid.HOUSE_WIDTH - Grid.PORTAL_CHEEK_THICKNESS, north.end.x, 1e-9)
        assertEquals(Grid.X_SOUTH_BALCONY_WEST, south.start.x, 1e-9)
        val garageRoof = geometry
            .primitivesFor(BuildingElementId("marcowki-v1-stropodach-garazu"))
            .filterIsInstance<SlabGeometry>()
            .single()
        assertEquals(garageRoof.outline.minOf { it.x }, south.end.x, 1e-9)
        assertEquals(garageRoof.topElevation, south.topElevation, 1e-9)
        assertEquals(garageRoof.elevation, south.baseElevation, 1e-9)
        assertEquals(Grid.Z_PORTAL_SOUTH_FACE, garageRoof.outline.maxOf { it.z }, 1e-9)
        assertEquals(Grid.Z_PORTAL_SOUTH_FACE, south.footprint().maxOf { it.z }, 1e-9)

        // Both runs are one traced depth, from two stated levels.
        runs.forEach { run ->
            assertEquals(Grid.UPPER_FLOOR_Y, run.topElevation, 1e-9)
            assertEquals(Grid.GARAGE_CLEAR_HEIGHT, run.baseElevation, 1e-9)
        }
    }

    @Test
    fun `C013D-05 balcony extent matches the source contract within trace tolerance`() {
        // The traced edge is the wardrobe-bedroom partition the upper plan
        // stipples from, within a trace pixel of the front elevation's band.
        val traceTolerance = 0.20
        assertEquals(Grid.X_UF_WARDROBE_EAST, Grid.X_SOUTH_BALCONY_WEST, 1e-9)
        assertTrue(abs(Grid.X_SOUTH_BALCONY_WEST - 3.35) <= traceTolerance)
        assertTrue(abs(Grid.X_SOUTH_BALCONY_WEST - 3.20) <= traceTolerance)

        val slabPieces = geometry
            .primitivesFor(BuildingElementId("marcowki-v1-strop-nad-parterem"))
            .filterIsInstance<SlabGeometry>()
        val southPortal = slabPieces.filter { slab -> slab.outline.all { it.z >= Grid.BUILDING_DEPTH - 1e-9 } }
        val northPortal = slabPieces.filter { slab -> slab.outline.all { it.z <= 1e-9 } }

        // South: one cheek piece and one balcony piece, nothing between the
        // west cheek and the traced edge.
        assertEquals(2, southPortal.size)
        val southBalcony = southPortal.single { it.outline.maxOf { p -> p.x } > Grid.PORTAL_CHEEK_THICKNESS + 1e-9 }
        assertEquals(Grid.X_SOUTH_BALCONY_WEST, southBalcony.outline.minOf { it.x }, 1e-9)
        southPortal.forEach { slab ->
            val minX = slab.outline.minOf { it.x }
            val maxX = slab.outline.maxOf { it.x }
            assertTrue(
                "Nothing may floor the open half of the south portal",
                maxX <= Grid.PORTAL_CHEEK_THICKNESS + 1e-9 || minX >= Grid.X_SOUTH_BALCONY_WEST - 1e-9,
            )
        }

        // North: the full width, as the plan stipples it.
        val northCovered = northPortal.map { slab -> slab.outline.minOf { it.x } to slab.outline.maxOf { it.x } }
            .sortedBy { it.first }
        assertEquals(0.0, northCovered.first().first, 1e-9)
        assertEquals(Grid.HOUSE_WIDTH, northCovered.last().second, 1e-9)
        northCovered.zipWithNext { a, b -> assertEquals(a.second, b.first, 1e-9) }

        // The guarding follows the same extent, and turns the corner at the
        // balcony's free end.
        val southGuard = geometry.primitivesFor(model.balustradeIds[1]).filterIsInstance<WallGeometry>()
        assertEquals(2, southGuard.size)
        assertEquals(Grid.X_SOUTH_BALCONY_WEST, southGuard.minOf { it.footprint().minOf { p -> p.x } }, 1e-9)
        val northGuard = geometry.primitivesFor(model.balustradeIds[0]).filterIsInstance<WallGeometry>()
        assertEquals(Grid.PORTAL_CHEEK_THICKNESS, northGuard.single().start.x, 1e-9)

        // The cheeks are closed where the slab runs through them: no state
        // leaves a storey-height slot in a leg the elevations draw straight.
        listOf(
            rectangleOf(0.0, Grid.Z_PORTAL_NORTH_FACE, Grid.PORTAL_CHEEK_THICKNESS, 0.0),
            rectangleOf(0.0, Grid.BUILDING_DEPTH, Grid.PORTAL_CHEEK_THICKNESS, Grid.Z_PORTAL_SOUTH_FACE),
        ).forEach { cheek ->
            assertTrue(
                "The slab must run through cheek $cheek",
                slabPieces.any { slab ->
                    slab.outline.minOf { it.x } <= cheek[0] + 1e-9 &&
                        slab.outline.maxOf { it.x } >= cheek[2] - 1e-9 &&
                        slab.outline.minOf { it.z } <= minOf(cheek[1], cheek[3]) + 1e-9 &&
                        slab.outline.maxOf { it.z } >= maxOf(cheek[1], cheek[3]) - 1e-9
                },
            )
        }

        // And the cheeks themselves are the traced 0.64 m, on both storeys, in
        // all four house portals and the garage's.
        val cheeks = geometry.primitives.filterIsInstance<WallGeometry>()
            .filter { abs(it.thickness - Grid.PORTAL_CHEEK_THICKNESS) < 1e-9 }
        assertEquals("Five ground-floor cheeks and four attic cheeks", 8, cheeks.size)
        cheeks.forEach { cheek ->
            val zs = cheek.footprint().map { it.z }
            assertTrue(zs.all { it <= 1e-9 } || zs.all { it >= Grid.BUILDING_DEPTH - 1e-9 })
        }
    }

    @Test
    fun `C013D-06 glass presentation is reference metadata, never domain truth`() {
        // The map is keyed by id and names only the balustrades.
        assertEquals(
            model.balustradeIds.toSet(),
            MarcowkiVisualPresentation.surfaceRoles.keys,
        )
        assertTrue(MarcowkiVisualPresentation.surfaceRoles.values.all { it == VisualSurfaceRole.GLASS_STUDY })

        // Nothing in the domain or geometry packages knows the role exists.
        listOf(
            "app/src/main/java/com/buildplan/app/domain",
            "app/src/main/java/com/buildplan/app/geometry",
        ).forEach { path ->
            val offenders = File(repositoryRoot(), path).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val text = file.readText()
                    listOf("VisualSurfaceRole", "GLASS_STUDY", "MeshStyle", "surfaceRole", "GlassPresentation")
                        .any { text.contains(it) }
                }
                .toList()
            assertTrue("$path must not know about presentation: $offenders", offenders.isEmpty())
        }

        // The building and its geometry are what they would be without the
        // mapping: no element carries a role, and the balustrades are ordinary
        // OTHER elements with ordinary wall prisms.
        model.balustradeIds.forEach { id ->
            val element = building.elements.single { it.id == id }
            assertEquals(BuildingElementKind.OTHER, element.kind)
            assertTrue(geometry.primitivesFor(id).all { it is WallGeometry })
        }
        val fieldNames = BuildingElement::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fieldNames.none { it.contains("glass") || it.contains("role") || it.contains("alpha") })

        // The synthetic fixture declares no roles at all.
        assertTrue(DebugModel.SYNTHETIC.surfaceRoles.isEmpty())
        assertEquals(MarcowkiVisualPresentation.surfaceRoles, DebugModel.MARCOWKI.surfaceRoles)
    }

    @Test
    fun `C013D-07 glass railings and panes use the transparent presentation`() {
        val roles = MarcowkiVisualPresentation.surfaceRoles

        // Every balustrade sheet and every pane resolves to glass; every wall,
        // slab, roof and frame resolves to the opaque study.
        geometry.primitives.forEach { primitive ->
            val mesh = primitive.toRenderMesh()
            val role = surfaceRoleOf(mesh, roles)
            val expectGlass = primitive.elementId in model.balustradeIds || primitive is OpeningPanelGeometry
            assertEquals(
                "${primitive.elementId.value} ${primitive::class.simpleName}",
                if (expectGlass) VisualSurfaceRole.GLASS_STUDY else VisualSurfaceRole.OPAQUE_STUDY,
                role,
            )
        }

        // The pane rule still comes from the primitive type, not from the map.
        val pane = geometry.primitives.first { it is OpeningPanelGeometry }.toRenderMesh()
        assertEquals(MeshStyle.GLAZING, pane.style)
        assertEquals(VisualSurfaceRole.GLASS_STUDY, surfaceRoleOf(pane, emptyMap()))

        // Transparent means transparent: well inside the usable range, never
        // opaque, and neutral to the eye.
        assertTrue(GlassPresentation.ALPHA in 0.2f..0.6f)
        assertTrue(GlassPresentation.SELECTED_ALPHA < 1.0f)
        assertEquals(3, GlassPresentation.COLOR.size)
        assertTrue(GlassPresentation.COLOR.max() - GlassPresentation.COLOR.min() < 0.25f)

        // The glass material is a blended, non-depth-writing, double-sided
        // variant of the technical material — the one place transparency may
        // be configured, and it is configured that way.
        val material = repositoryFile(
            "app/src/debug/java/com/buildplan/app/render/filament/TechnicalMaterial.kt",
        ).readText()
        val glass = material.substringAfter("fun buildGlass").substringBefore("fun buildLine")
        assertTrue(glass.contains("BlendingMode.TRANSPARENT"))
        assertTrue(glass.contains("depthWrite(false)"))
        assertTrue(glass.contains("doubleSided(true)"))
        assertTrue(material.contains("material.baseColor.rgb *= material.baseColor.a"))

        // And the synthetic fixture, with no roles, still gets glass panes.
        SyntheticDemoHouse.geometry.primitives.filterIsInstance<OpeningPanelGeometry>().forEach {
            assertEquals(VisualSurfaceRole.GLASS_STUDY, surfaceRoleOf(it.toRenderMesh(), emptyMap()))
        }
    }

    @Test
    fun `C013D-08 the fascia does not alter the canonical roof area or facets`() {
        val facets = geometry.primitivesFor(model.roofId).filterIsInstance<RoofFacetGeometry>()
        assertEquals(2, facets.size)

        // The facets are exactly the two planes the grid derives: eaves at the
        // outer faces, ridge over the centre, one metre past each gable, no
        // thickness — and their area is what reconciles with the page.
        val northEdge = -Grid.GABLE_OVERHANG
        val southEdge = Grid.BUILDING_DEPTH + Grid.GABLE_OVERHANG
        facets.forEach { facet ->
            assertEquals(4, facet.vertices.size)
            assertEquals(setOf(northEdge, southEdge), facet.vertices.map { it.z }.toSet())
            assertEquals(setOf(Grid.EAVES_Y, Grid.RIDGE_Y), facet.vertices.map { it.y }.toSet())
        }
        val expectedArea = 2.0 *
            (Grid.RIDGE_X / cos(Math.toRadians(Grid.ROOF_PITCH_DEGREES))) *
            (Grid.BUILDING_DEPTH + 2 * Grid.GABLE_OVERHANG)
        assertEquals(expectedArea, facets.sumOf { it.area }, 1e-6)
        assertEquals(150.57, facets.sumOf { it.area }, 1.0)
        assertTrue(geometry.primitivesFor(model.roofId).none { it is WallGeometry })

        // The fascia is its own element, made of prisms, standing on the wall
        // faces with its top on the eaves line and its depth the traced 0.24.
        val fascia = building.elements.single { it.id == model.fasciaId }
        assertEquals(BuildingElementScope.WholeBuilding, fascia.scope)
        val runs = geometry.primitivesFor(model.fasciaId)
        assertTrue(runs.all { it is WallGeometry })
        assertEquals(2, runs.size)
        runs.filterIsInstance<WallGeometry>().forEach { run ->
            assertEquals(Grid.EAVES_Y, run.topElevation, 1e-9)
            assertEquals(Grid.FASCIA_DEPTH, run.height, 1e-9)
            assertEquals(Grid.Z_PORTAL_NORTH_FACE, run.footprint().minOf { it.z }, 1e-9)
            assertEquals(Grid.Z_PORTAL_SOUTH_FACE, run.footprint().maxOf { it.z }, 1e-9)
            val xs = run.footprint().map { it.x }
            assertTrue(
                "The fascia stands on a wall face, outside it",
                abs(xs.max() - 0.0) < 1e-9 || abs(xs.min() - Grid.HOUSE_WIDTH) < 1e-9,
            )
        }

        // Nothing but the roof's own facets and the frame bars' faces is a
        // roof facet, and the frame bars all lie under the roof plane.
        geometry.primitives.filterIsInstance<RoofFacetGeometry>()
            .filter { it.elementId != model.roofId }
            .forEach { facet ->
                assertTrue(facet.elementId in model.gableFrameIds)
                facet.vertices.forEach { assertTrue(it.y <= Grid.roofUndersideAt(it.x) + 1e-9) }
            }
    }

    @Test
    fun `C013D-09 the presentation grid remains renderer only`() {
        val grid = PresentationGrid.under(requireNotNull(geometry.bounds))
        assertTrue(grid.lineCount > 20)
        assertTrue(building.elements.none { it.id.value.contains("grid", ignoreCase = true) })
        assertTrue(geometry.elementIds.none { it.value.contains("grid", ignoreCase = true) })
        listOf(
            "app/src/main/java/com/buildplan/app/domain",
            "app/src/main/java/com/buildplan/app/geometry",
            "app/src/debug/java/com/buildplan/app/reference",
        ).forEach { path ->
            val offenders = File(repositoryRoot(), path).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains("PresentationGrid") }
                .toList()
            assertTrue("$path must not know the grid: $offenders", offenders.isEmpty())
        }
    }

    @Test
    fun `C013D-10 the STAGE-013B stairs and openings are still present`() {
        val treads = geometry.primitivesFor(model.stairId).filterIsInstance<SlabGeometry>()
        assertEquals(Grid.STAIR_RISER_COUNT, treads.size)
        assertEquals(Grid.GROUND_FLOOR_Y, treads.minOf { it.elevation }, 1e-9)
        assertEquals(Grid.UPPER_FLOOR_Y, treads.maxOf { it.topElevation }, 1e-9)

        val openings = geometry.primitives.filterIsInstance<WallGeometry>().flatMap { it.openings }
        assertEquals(Grid.allOpenings.size, openings.size)
        openings.forEach { opening ->
            assertTrue(geometry.primitivesFor(opening.elementId).any { it is OpeningPanelGeometry })
        }
        // Splitting the eaves walls into cheeks moved no opening: each still
        // sits at its absolute traced coordinate.
        val westWall = geometry.primitives.filterIsInstance<WallGeometry>()
            .filter { it.elementId == BuildingElementId("marcowki-v1-parter-sciana-zachodnia") }
        val withOpenings = westWall.single { it.openings.isNotEmpty() }
        val kitchen = withOpenings.openings.single { it.elementId.value.endsWith("okno-kuchni") }
        assertEquals(Grid.GF_KITCHEN_WINDOW.nearEdge, withOpenings.start.z + kitchen.distanceFromStart, 1e-9)
    }

    @Test
    fun `C013D-11 no ARCHON or reference raster is tracked or shipped`() {
        val rasters = setOf("gif", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "html", "htm", "pdf")
        val offenders = repositoryFiles().filter { it.extension.lowercase() in rasters }.map { it.path }
        assertTrue("Reference rasters must not be tracked: $offenders", offenders.isEmpty())

        // The owner's local pack is inspected in place and kept out of git by
        // rule, not by habit.
        val ignore = repositoryFile(".gitignore").readLines().map { it.trim() }
        assertTrue("references/ must be git-ignored", "references/" in ignore)

        // No asset directory carries anything but the app's own resources.
        listOf("app/src/main/assets", "app/src/debug/assets").forEach { path ->
            val dir = File(repositoryRoot(), path)
            assertTrue("$path must not ship reference material", !dir.exists() || dir.walkTopDown().none { it.isFile })
        }

        // What is kept is text: URLs, readings and the moment they were made.
        assertTrue(MarcowkiSourceEvidence.FIDELITY_AUDIT_RETRIEVED_AT.startsWith("2026-09-08"))
        assertTrue(MarcowkiSourceEvidence.SIDE_ELEVATION_EAST_URL.startsWith("https://assets.archon.pl/"))
    }

    @Test
    fun `C013D-12 Filament remains 1_75_1 with no SceneView`() {
        val versions = repositoryFile("gradle/libs.versions.toml").readText()
        assertTrue(versions.contains(Regex("""filament\s*=\s*"1\.75\.1"""")))
        val appBuild = repositoryFile("app/build.gradle.kts").readText()
        assertTrue(appBuild.contains("debugImplementation(libs.filament.android)"))
        assertTrue(appBuild.contains("debugImplementation(libs.filamat.android)"))
        assertTrue(!appBuild.contains("implementation(libs.filament.android)"))
        repositoryFiles()
            .filter { it.name.endsWith(".gradle.kts") || it.name == "libs.versions.toml" }
            .forEach { assertTrue(!it.readText().lowercase().contains("sceneview")) }
    }

    @Test
    fun `C013D-13 no generic analyzer implementation was added`() {
        // STAGE-023A authorised a research prototype with a bounded footprint
        // (core in `:analyzer`, in-app consumer only the debug Lab and the
        // `SceneModel` seam); OCR, vision and ML libraries stay forbidden.
        val forbidden = Regex("""(?i)\b(ocr|tesseract|opencv|imageproc|mlkit|tflite)\b""")
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
        val offenders = listOf("app/src/main", "app/src/debug", "app/src/release")
            .flatMap { path -> File(repositoryRoot(), path).walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                forbidden.containsMatchIn(text) || forbidden.containsMatchIn(file.name) ||
                    ((analyzerish.containsMatchIn(text) || analyzerish.containsMatchIn(file.name)) && (!authorised.containsMatchIn(file.path) && previewFiles.none { file.path.replace('\\', '/').endsWith(it) }))
            }
        assertTrue("Analyzer code outside its authorised footprint: $offenders", offenders.isEmpty())

        // No network, image-decoding or ML dependency arrived either.
        val appBuild = repositoryFile("app/build.gradle.kts").readText()
        assertTrue(!Regex("""(?i)(okhttp|retrofit|ktor|mlkit|tensorflow|opencv|coil|glide)""").containsMatchIn(appBuild))
    }

    @Test
    fun `C013D-14 only emulator-5570 is addressed anywhere in the repository`() {
        val serials = Regex("""emulator-(\d{4})""")
        repositoryFiles()
            .filter { it.extension.lowercase() in setOf("kt", "kts", "md", "xml", "toml", "properties", "sh") }
            .filter { it.name != "CLAUDE.md" }
            .forEach { file ->
                serials.findAll(file.readText()).forEach { match ->
                    assertEquals("${file.path} names a device this project may not use", "5570", match.groupValues[1])
                }
            }
    }

    @Test
    fun `C013D-15 the evidence presets exist and the guardrails are untouched`() {
        val bounds = requireNotNull(geometry.bounds)
        listOf(
            ModelViewPreset.FRONT_SIGNATURE,
            ModelViewPreset.SIGNATURE_FACADE,
            ModelViewPreset.GARAGE_RELATION,
            ModelViewPreset.GLASS_RAILING_CLOSEUP,
            ModelViewPreset.ROOF_FASCIA_CLOSEUP,
            ModelViewPreset.FULL_REAR_AXON,
        ).forEach { preset -> assertEquals(preset.framing(bounds), preset.framing(bounds)) }

        // The two close-ups frame the element they are about, not the house.
        val railing = DebugModel.MARCOWKI.focusBounds(PresetFocus.NORTH_BALUSTRADE, bounds)
        assertTrue(railing.sizeZ < 0.1 && railing.sizeX < Grid.HOUSE_WIDTH)
        val frame = DebugModel.MARCOWKI.focusBounds(PresetFocus.NORTH_FRAME, bounds)
        assertTrue(frame.sizeZ <= Grid.PORTAL_DEPTH + 2 * Grid.GABLE_FRAME_PROUD + 1e-9)
        assertEquals(bounds, DebugModel.SYNTHETIC.focusBounds(PresetFocus.NORTH_FRAME, bounds))

        // Lint gates, no suppressions, no baseline.
        val appBuild = repositoryFile("app/build.gradle.kts").readText()
        assertTrue(appBuild.contains("abortOnError = true"))
        assertTrue(!appBuild.contains("baseline"))
        val suppressions = repositoryFiles()
            .filter { it.extension == "kt" && it.readText().contains("@" + "Suppress") }
        assertTrue("Nothing may be suppressed to pass: $suppressions", suppressions.isEmpty())
        if (repositoryFiles().any { it.name.startsWith("lint-baseline") }) fail("No lint baseline may exist")
    }

    // --- helpers -----------------------------------------------------

    private fun visibleIds(state: SpikeVisibility): LinkedHashSet<BuildingElementId> =
        building.visibleElements(state.toBuildingVisibility(model.atticId))
            .mapTo(LinkedHashSet(), BuildingElement::id)

    private fun rectangleOf(minX: Double, minZ: Double, maxX: Double, maxZ: Double): DoubleArray =
        doubleArrayOf(minX, minZ, maxX, maxZ)

    private fun repositoryFile(relativePath: String): File =
        File(repositoryRoot(), relativePath).also {
            assertTrue("Expected $relativePath to exist at ${it.absolutePath}", it.isFile)
        }

    private fun repositoryFiles(): List<File> {
        // `references/` is the owner's local pack of third-party drawings:
        // inspected, git-ignored, never committed — see C013D-11.
        val skipped = setOf(".git", ".gradle", ".idea", "build", ".kotlin", "references")
        return repositoryRoot()
            .walkTopDown()
            .onEnter { it.name !in skipped }
            .filter { it.isFile }
            .toList()
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
