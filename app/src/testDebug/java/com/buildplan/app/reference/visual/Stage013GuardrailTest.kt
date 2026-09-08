package com.buildplan.app.reference.visual

import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.ModelViewPreset
import com.buildplan.app.render.filament.SpikeVisibility
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * STAGE-013 — the promises that are about the repository and the stage, not
 * about the shape of the house.
 *
 * Three of these guard boundaries that are cheap to cross by accident and
 * expensive to discover later: a copyrighted drawing committed "just to check
 * something", a renderer quietly upgraded mid-review, or an emulator serial that
 * belongs to somebody else's work. The fourth guards the synthetic fixture,
 * which is the only model that stays still while the traced one is being
 * corrected.
 */
class Stage013GuardrailTest {

    @Test
    fun `M013-13 no third-party drawing or page is committed to the repository`() {
        val forbiddenExtensions = setOf("gif", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "html", "htm", "pdf")

        val offenders = repositoryFiles()
            .filter { it.extension.lowercase() in forbiddenExtensions }
            .map { it.path }

        if (offenders.isNotEmpty()) {
            fail(
                "Source drawings and pages are fetched to a temporary directory and never " +
                    "committed. Found:\n" + offenders.joinToString("\n"),
            )
        }

        // ARCHON may be named — a URL is a public fact — but only as text.
        val binaryish = repositoryFiles().filter { file ->
            file.extension.lowercase() == "png" && !file.path.replace('\\', '/').contains("/res/")
        }
        assertTrue("Unexpected raster committed: $binaryish", binaryish.isEmpty())
    }

    @Test
    fun `M013-14 the synthetic demo house survives as the renderer regression fixture`() {
        assertNotNull(SyntheticDemoHouse.geometry.bounds)
        assertTrue(SyntheticDemoHouse.building.elements.isNotEmpty())
        assertTrue(SyntheticDemoHouse.geometry.primitives.isNotEmpty())
        SyntheticDemoHouse.geometry.requireElementsIn(SyntheticDemoHouse.building)

        // Both models are reachable, and the traced one is what the screen opens
        // on, because that is what this stage is for.
        assertEquals(
            listOf(DebugModel.MARCOWKI, DebugModel.SYNTHETIC),
            DebugModel.entries.toList(),
        )
        assertEquals(DebugModel.MARCOWKI, DebugModel.entries.first())

        // The two must stay separate houses: a shared id would let a trace
        // correction silently move the fixture.
        val demoIds = SyntheticDemoHouse.building.elements.map { it.id }.toSet()
        val tracedIds = MarcowkiVisualModelV1.building.elements.map { it.id }.toSet()
        assertTrue((demoIds intersect tracedIds).isEmpty())
    }

    @Test
    fun `M013-15 the renderer is still direct Filament 1_75_1 with no SceneView`() {
        val versions = repositoryFile("gradle/libs.versions.toml").readText()
        assertTrue(
            "Filament must remain pinned at 1.75.1",
            versions.contains(Regex("""filament\s*=\s*"1\.75\.1"""")),
        )

        val buildFiles = repositoryFiles().filter {
            it.name.endsWith(".gradle.kts") || it.name == "libs.versions.toml"
        }
        assertTrue("Expected to find the Gradle build files", buildFiles.size >= 3)

        buildFiles.forEach { file ->
            val text = file.readText().lowercase()
            assertTrue(
                "SceneView must not appear in ${file.name}",
                !text.contains("sceneview"),
            )
        }

        // Debug only: the renderer must not reach a release build.
        val appBuild = repositoryFile("app/build.gradle.kts").readText()
        assertTrue(appBuild.contains("debugImplementation(libs.filament.android)"))
        assertTrue(appBuild.contains("debugImplementation(libs.filamat.android)"))
        assertTrue(!appBuild.contains("implementation(libs.filament.android)"))
    }

    @Test
    fun `M013-17 the evidence presets are deterministic and distinct`() {
        val bounds = requireNotNull(MarcowkiVisualModelV1.geometry.bounds)

        assertEquals(
            listOf(
                ModelViewPreset.FULL_AXON,
                ModelViewPreset.ROOF_OFF_AXON,
                ModelViewPreset.GROUND_CUTAWAY,
                ModelViewPreset.GROUND_TOP,
                ModelViewPreset.UPPER_TOP,
                ModelViewPreset.FACADE_OPENINGS,
                ModelViewPreset.FRONT_SIGNATURE,
                ModelViewPreset.SIGNATURE_FACADE,
                ModelViewPreset.GARAGE_RELATION,
                ModelViewPreset.FULL_REAR_AXON,
                ModelViewPreset.GLASS_RAILING_CLOSEUP,
                ModelViewPreset.ROOF_FASCIA_CLOSEUP,
                ModelViewPreset.ROOF_COVER_CLOSEUP,
                ModelViewPreset.SITE_CONTEXT,
                ModelViewPreset.STAIRS_VIEW,
            ),
            ModelViewPreset.entries.toList(),
        )

        // Same model, same preset, same camera — that is what makes a captured
        // screenshot evidence rather than an anecdote.
        ModelViewPreset.entries.forEach { preset ->
            assertEquals(preset.framing(bounds), preset.framing(bounds))
        }

        // Different presets must actually be different views, or the five
        // screenshots would be one screenshot five times. A view is a camera and
        // a visibility together: the two plan views share a camera on purpose,
        // so that the ground floor and the attic can be flipped between and
        // compared without anything moving.
        val views = ModelViewPreset.entries.map { it.framing(bounds) to it.visibility }
        assertEquals("Two presets are the same view", views.size, views.toSet().size)
        assertEquals(
            "The two plan views must look from exactly the same camera",
            ModelViewPreset.GROUND_TOP.framing(bounds),
            ModelViewPreset.UPPER_TOP.framing(bounds),
        )

        // The stair view is the only one that frames something other than the
        // whole model, and it has to actually be closer for that to mean
        // anything.
        val stairFocus = DebugModel.MARCOWKI.focusBounds(
            ModelViewPreset.STAIRS_VIEW.focus,
            bounds,
        )
        val stairFraming = ModelViewPreset.STAIRS_VIEW.framing(bounds, stairFocus)
        assertTrue(
            "The stair view must frame a part of the model, not the whole of it",
            stairFocus.sizeX < bounds.sizeX / 3.0 && stairFocus.sizeZ < bounds.sizeZ / 3.0,
        )
        assertTrue(
            "The stair view must move in, not sit at the general view's distance",
            stairFraming.distance < ModelViewPreset.FULL_AXON.framing(bounds).distance,
        )
        assertTrue(
            "The stair view must look at the stair rather than the building centre",
            abs(stairFraming.focusX) + abs(stairFraming.focusZ) > 1.0f,
        )
        // Still a pure function of its two boxes, like every other preset.
        assertEquals(stairFraming, ModelViewPreset.STAIRS_VIEW.framing(bounds, stairFocus))

        assertEquals(SpikeVisibility.EVERYTHING, ModelViewPreset.FULL_AXON.visibility)
        assertEquals(SpikeVisibility.ROOF_HIDDEN, ModelViewPreset.ROOF_OFF_AXON.visibility)
        assertEquals(
            SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN,
            ModelViewPreset.GROUND_CUTAWAY.visibility,
        )
        assertEquals(
            SpikeVisibility.ROOF_AND_UPPER_FLOOR_HIDDEN,
            ModelViewPreset.GROUND_TOP.visibility,
        )
        assertEquals(SpikeVisibility.ROOF_HIDDEN, ModelViewPreset.UPPER_TOP.visibility)

        // The plan views must look down far enough to read as plans.
        listOf(ModelViewPreset.GROUND_TOP, ModelViewPreset.UPPER_TOP).forEach { preset ->
            val pitchDegrees = Math.toDegrees(preset.framing(bounds).pitch.toDouble())
            assertTrue("$preset is not a near plan view at $pitchDegrees degrees", pitchDegrees >= 70.0)
        }
    }

    @Test
    fun `M013-20 no forbidden emulator serial is named anywhere in the repository`() {
        // Assembled rather than written out, so that this guard does not itself
        // become the thing it forbids finding.
        val forbidden = listOf(5554, 5560, 5580).map { "emulator-$it" }

        val offenders = repositoryFiles()
            .filter { it.extension.lowercase() in setOf("kt", "kts", "xml", "toml", "md", "pro", "properties") }
            .flatMap { file ->
                val text = file.readText()
                forbidden.filter { serial ->
                    // CLAUDE.md names the forbidden serials in order to forbid
                    // them; everywhere else, naming one is using one.
                    text.contains(serial) && file.name != "CLAUDE.md"
                }.map { "${file.path} names $it" }
            }

        if (offenders.isNotEmpty()) {
            fail("Only emulator-5570 may be used:\n" + offenders.joinToString("\n"))
        }
    }

    // --- helpers -----------------------------------------------------

    private fun repositoryFile(relativePath: String): File =
        File(repositoryRoot(), relativePath).also {
            assertTrue("Expected $relativePath to exist at ${it.absolutePath}", it.isFile)
        }

    /** Every file the repository owns, skipping tooling and build output. */
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

    /** Walks up from wherever Gradle started the test until the settings file appears. */
    private fun repositoryRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root from ${File("").absolutePath}")
    }
}
