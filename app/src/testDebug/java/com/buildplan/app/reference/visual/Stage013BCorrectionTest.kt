package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.primitivesOf
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.SpikeVisibility
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * STAGE-013B — the corrections the owner asked for, stated as things that can
 * fail.
 *
 * The owner's complaint about the first model was not that a number was wrong.
 * It was that hiding a layer produced something that did not obviously belong to
 * the same house, that the windows were missing, and that the stair was not
 * where the plan puts it. Those are all checkable, and they are checked here
 * rather than left to a screenshot — a screenshot proves the state it was taken
 * in and nothing about the next one.
 */
class Stage013BCorrectionTest {

    private val model = MarcowkiVisualModelV1
    private val building = model.building
    private val geometry = model.geometry

    /** The four states the owner toggles between, plus the restore. */
    private val states = SpikeVisibility.entries

    @Test
    fun `C013B-01 every visibility state is a subset of one canonical model`() {
        // Same object, not merely equal contents: two states reading two
        // separately built models is exactly the drift the owner saw, and equal
        // contents today would not stop it appearing tomorrow.
        assertTrue(DebugModel.MARCOWKI.geometry === geometry)
        assertTrue(DebugModel.MARCOWKI.building === building)

        val everything = geometry.elementIds
        states.forEach { state ->
            val visible = visibleIds(state)
            assertTrue(
                "$state draws ${visible - everything}, which the canonical model does not have",
                everything.containsAll(visible),
            )
        }

        // Between them the states account for the whole model: nothing is
        // permanently invisible, so no shape is being carried that the owner
        // never gets to review.
        assertEquals(everything, states.flatMapTo(LinkedHashSet()) { visibleIds(it) })

        // The parts that never go away are the same parts in every state, and
        // they are the ones that carry the building's identity: its footprint,
        // its garage, its ground-floor walls.
        val alwaysVisible = states
            .map { visibleIds(it) }
            .reduce { common, next -> common.filterTo(LinkedHashSet()) { it in next } }
        listOf("fundament", "parter-sciana-poludniowa", "garaz-sciana-wschodnia", "parter-schody")
            .forEach { slug ->
                assertTrue(
                    "$slug must survive every toggle, or the house changes shape between states",
                    elementId(slug) in alwaysVisible,
                )
            }

        // And the footprint they describe is identical in every state, to the
        // millimetre. This is the owner's "same house" made into a number.
        val footprints = states.map { state ->
            val visible = visibleIds(state)
            geometry.primitives
                .filter { it.elementId in visible }
                .map { it.bounds }
                .reduce { total, next -> total.encompass(next) }
                .let { Triple(it.min.x, it.min.z, it.max.x) }
        }
        assertEquals(
            "Hiding a layer must not move the building's plan extent",
            1,
            footprints.toSet().size,
        )
    }

    @Test
    fun `C013B-02 hiding the roof removes roof-linked geometry and nothing else`() {
        val everything = visibleIds(SpikeVisibility.EVERYTHING)
        val roofOff = visibleIds(SpikeVisibility.ROOF_HIDDEN)

        val removed = everything - roofOff
        val roofIds = building.elements
            .filter { it.kind == BuildingElementKind.ROOF }
            .mapTo(LinkedHashSet()) { it.id }

        assertEquals("Hiding the roof must remove exactly the roof elements", roofIds, removed)
        assertTrue("There must be a roof to remove", roofIds.isNotEmpty())

        // The rooflights are shapes of the roof, so they go with it. If they
        // ever became their own elements this would fail, which is the point:
        // three panes left hovering over an open attic is the bug.
        val rooflights = geometry.primitivesFor(model.roofId).filterIsInstance<OpeningPanelGeometry>()
        assertEquals(3, rooflights.size)

        // Everything else is untouched, including every attic wall: the roof
        // coming off must not take the storey under it.
        assertTrue(
            "Attic walls must survive with the roof off",
            building.elements
                .filter { it.floorId == model.atticId && it.kind == BuildingElementKind.WALL }
                .all { it.id in roofOff },
        )
    }

    @Test
    fun `C013B-03 hiding the upper floor removes upper-floor geometry and nothing else`() {
        val everything = visibleIds(SpikeVisibility.EVERYTHING)
        val atticOff = visibleIds(SpikeVisibility.UPPER_FLOOR_HIDDEN)

        val removed = everything - atticOff
        val atticIds = building.elements
            .filter { it.floorId == model.atticId }
            .mapTo(LinkedHashSet()) { it.id }

        assertEquals("Hiding the attic must remove exactly the attic elements", atticIds, removed)

        // The roof belongs to the building, not to the storey under it, so it
        // stays: taking a floor out is not the same operation as taking the lid
        // off, and the two chips must not quietly mean the same thing.
        assertTrue(
            "The roof must survive hiding the storey below it",
            building.elements.filter { it.kind == BuildingElementKind.ROOF }.all { it.id in atticOff },
        )
        assertTrue(
            "The ground floor must survive hiding the storey above it",
            building.elements.filter { it.floorId == model.groundFloorId }.all { it.id in atticOff },
        )
    }

    @Test
    fun `C013B-04 restoring returns the whole model, in the same order, every time`() {
        val full = visibleIds(SpikeVisibility.EVERYTHING)
        assertEquals(building.elements.mapTo(LinkedHashSet()) { it.id }, full)

        // Walked away from and back again: visibility is an argument, so the
        // route taken to a state cannot change what the state contains.
        states.forEach { visibleIds(it) }
        assertEquals(full, visibleIds(SpikeVisibility.EVERYTHING))

        // Order too, not just membership. The renderer draws in this order, and
        // a set that came back shuffled would be a different picture of the same
        // elements.
        val drawOrder = orderedPrimitiveIds(SpikeVisibility.EVERYTHING)
        assertEquals(drawOrder, orderedPrimitiveIds(SpikeVisibility.EVERYTHING))
        assertEquals(geometry.primitives.map { it.elementId }, drawOrder)
    }

    @Test
    fun `C013B-05 every traced opening is a hole in a wall and an element that fills it`() {
        val openings = geometry.primitives
            .filterIsInstance<WallGeometry>()
            .flatMap { wall -> wall.openings.map { wall to it } }

        assertEquals(
            "Every opening in the traced schedule must be cut",
            MarcowkiPlanGrid.allOpenings.size,
            openings.size,
        )

        val byId = building.elements.associateBy { it.id }
        openings.forEach { (wall, opening) ->
            val element = byId[opening.elementId]
            assertNotNull("Opening in ${wall.elementId.value} fills no known element", element)
            assertTrue(
                "${element?.id?.value} fills an opening but is a ${element?.kind}",
                element?.kind in setOf(BuildingElementKind.WINDOW, BuildingElementKind.DOOR),
            )
            assertTrue(
                "A hole and the wall it is in must not be the same element",
                opening.elementId != wall.elementId,
            )
            assertTrue(
                "${opening.elementId.value} has no pane",
                geometry.primitivesFor(opening.elementId).any { it is OpeningPanelGeometry },
            )
            // The hole must be on the storey the wall is: a window scoped to the
            // attic in a ground-floor wall would hide with the wrong toggle.
            assertEquals(
                "Opening ${opening.elementId.value} is not on its wall's storey",
                byId.getValue(wall.elementId).scope,
                byId.getValue(opening.elementId).scope,
            )
        }

        // The facades the owner will actually look at, each with the openings
        // the plan schedules on it. Named rather than counted, so a window that
        // moved to the wrong wall fails instead of balancing out.
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
            // Summed over the element's prisms: an eaves wall is now three
            // pieces where it runs past the gables to form the portal cheeks.
            val openings = geometry.primitives
                .filterIsInstance<WallGeometry>()
                .filter { it.elementId == elementId(slug) }
                .sumOf { it.openings.size }
            assertEquals("$slug carries the wrong number of openings", expected, openings)
        }

        // Both gables are glazed above their eaves walls, which is the whole
        // reason their triangles are cut into bands.
        listOf("poddasze-sciana-szczytowa-polnocna", "poddasze-sciana-szczytowa-poludniowa")
            .forEach { slug ->
                val wall = geometry.primitives
                    .filterIsInstance<WallGeometry>()
                    .single { it.elementId == elementId(slug) }
                wall.openings.forEach { opening ->
                    val pane = geometry.primitivesFor(opening.elementId)
                        .filterIsInstance<OpeningPanelGeometry>()
                        .single()
                    assertTrue(
                        "A gable glazing must reach above the eaves wall it starts in",
                        pane.bounds.max.y > wall.topElevation + 0.01,
                    )
                    assertTrue(
                        "A gable glazing must stop at the roof",
                        pane.bounds.max.y <= MarcowkiPlanGrid.RIDGE_Y + 0.01,
                    )
                }
            }
    }

    @Test
    fun `C013B-06 the stair is on the ground floor, climbs to the attic, and fits its well`() {
        val stair = building.elements.single { it.kind == BuildingElementKind.STAIRS }
        assertEquals(model.stairId, stair.id)
        assertEquals(BuildingElementScope.OnFloor(model.groundFloorId), stair.scope)
        assertTrue(
            "The source names no ground-floor room for the stair, so it must claim none",
            stair.roomIds.isEmpty(),
        )

        val treads = geometry.primitivesFor(stair.id).filterIsInstance<SlabGeometry>()
        assertEquals(MarcowkiPlanGrid.STAIR_RISER_COUNT, treads.size)

        // It starts on the ground floor and arrives at the attic floor. Both
        // ends are levels the section states, so this is the one thing about the
        // stair that is not a display choice.
        assertEquals(MarcowkiPlanGrid.GROUND_FLOOR_Y, treads.minOf { it.elevation }, 1e-9)
        assertEquals(MarcowkiPlanGrid.UPPER_FLOOR_Y, treads.maxOf { it.topElevation }, 1e-9)

        // Each tread is one riser above the one before it: a stair, not a heap.
        treads.map { it.topElevation }.zipWithNext { lower, upper ->
            assertEquals(MarcowkiPlanGrid.STAIR_RISER_HEIGHT, upper - lower, 1e-9)
        }

        // Every tread lies inside the stairwell both plans draw.
        treads.flatMap { it.outline }.forEach { corner ->
            assertTrue(
                "Tread corner $corner escapes the traced stairwell",
                corner.x >= MarcowkiPlanGrid.STAIR_WEST_X - 0.01 &&
                    corner.x <= MarcowkiPlanGrid.STAIR_EAST_X + 0.01 &&
                    corner.z >= MarcowkiPlanGrid.STAIR_NORTH_Z - 0.01 &&
                    corner.z <= MarcowkiPlanGrid.STAIR_SOUTH_Z + 0.01,
            )
        }

        // It wraps the core rather than running through it, which is what makes
        // the three flights read as three flights.
        val core = treads.filter { tread ->
            tread.outline.all { corner ->
                corner.x > MarcowkiPlanGrid.STAIR_WEST_X + 0.01 &&
                    corner.x < MarcowkiPlanGrid.STAIR_CORE_EAST_X - 0.01 &&
                    corner.z > MarcowkiPlanGrid.STAIR_CORE_NORTH_Z + 0.01 &&
                    corner.z < MarcowkiPlanGrid.STAIR_CORE_SOUTH_Z - 0.01
            }
        }
        assertTrue("No tread may sit inside the stairwell core", core.isEmpty())

        // The climb ends heading west, into the attic corridor — the direction
        // the upper plan's arrow gives, and the reason the flights are in the
        // order they are.
        val last = treads.last().outline
        val secondToLast = treads[treads.size - 2].outline
        assertTrue(
            "The top flight must travel west, towards the corridor",
            last.minOf { it.x } < secondToLast.minOf { it.x },
        )

        // And the floor above has a hole for it to arrive through.
        val slabPieces = geometry.primitivesFor(elementId("strop-nad-parterem"))
            .filterIsInstance<SlabGeometry>()
        assertTrue(
            "The attic floor must be split around the stairwell, not solid over it",
            slabPieces.size > 1 && slabPieces.none { piece ->
                piece.outline.any { corner ->
                    corner.x > MarcowkiPlanGrid.STAIR_WEST_X + 0.01 &&
                        corner.x < MarcowkiPlanGrid.STAIR_EAST_X - 0.01 &&
                        corner.z > MarcowkiPlanGrid.STAIR_NORTH_Z + 0.01 &&
                        corner.z < MarcowkiPlanGrid.STAIR_SOUTH_Z - 0.01
                }
            },
        )
    }

    @Test
    fun `C013B-07 this stage committed no drawing, render or captured evidence`() {
        // The stage traced four elevations and two visualisations that STAGE-013
        // never opened, and produced nine screenshots. None of them may be here.
        val evidenceNames = listOf(
            "01_FULL_AXON", "02_ROOF_OFF_AXON", "03_GROUND_CUTAWAY", "04_GROUND_TOP",
            "05_UPPER_TOP", "06_WINDOWS_EXTERIOR", "07_STAIRS_VIEW",
            "08_SAME_MODEL_CONTINUITY", "09_STYLE_CLOSEUP",
        )
        val offenders = repositoryFiles().filter { file ->
            evidenceNames.any { file.name.startsWith(it) } ||
                file.extension.lowercase() in setOf("gif", "jpg", "jpeg", "webp", "bmp", "psd")
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Source drawings and captured evidence live outside the worktree. Found:\n" +
                    offenders.joinToString("\n") { it.path },
            )
        }

        // The elevations behind the portal and glazing corrections are recorded
        // as URLs and numbers, which is what may be kept.
        assertTrue(MarcowkiSourceEvidence.PAGE_URL.startsWith("https://www.archon.pl/"))
        assertTrue(MarcowkiSourceEvidence.notModelled.isNotEmpty())
    }

    @Test
    fun `C013B-08 the renderer gained materials, not a dependency`() {
        val appBuild = repositoryFile("app/build.gradle.kts").readText()
        val threeDimensional = Regex("""(?i)(sceneview|rajawali|libgdx|gltf|three|arcore)""")

        assertTrue(
            "This stage adds no 3D library: the edge overlay is a second material",
            !threeDimensional.containsMatchIn(appBuild),
        )
        assertTrue(appBuild.contains("debugImplementation(libs.filament.android)"))
        assertTrue(appBuild.contains("debugImplementation(libs.filamat.android)"))
        assertTrue(
            "Filament must stay out of release",
            !appBuild.contains("implementation(libs.filament.android)"),
        )
        assertTrue(
            repositoryFile("gradle/libs.versions.toml").readText()
                .contains(Regex("""filament\s*=\s*"1\.75\.1"""")),
        )
    }

    @Test
    fun `C013B-09 the only emulator this project names is 5570`() {
        val serials = Regex("""emulator-(\d{4})""")
        val named = repositoryFiles()
            .filter { it.extension.lowercase() in setOf("kt", "kts", "md", "xml", "toml", "properties") }
            .filter { it.name != "CLAUDE.md" }
            .flatMap { file -> serials.findAll(file.readText()).map { file.path to it.groupValues[1] } }

        named.forEach { (path, serial) ->
            assertEquals("$path names a device this project may not use", "5570", serial)
        }
    }

    // --- helpers -----------------------------------------------------

    private fun visibleIds(state: SpikeVisibility): LinkedHashSet<BuildingElementId> =
        building.visibleElements(state.toBuildingVisibility(model.atticId))
            .mapTo(LinkedHashSet(), BuildingElement::id)

    private fun orderedPrimitiveIds(state: SpikeVisibility): List<BuildingElementId> =
        geometry
            .primitivesOf(building.visibleElements(state.toBuildingVisibility(model.atticId)))
            .map { it.elementId }

    private fun elementId(slug: String): BuildingElementId =
        BuildingElementId("marcowki-v1-$slug")

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

    private fun repositoryRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
