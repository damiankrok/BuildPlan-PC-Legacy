package com.buildplan.app.reference.visual

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.presentation.OpeningFrameProfile
import com.buildplan.app.presentation.OpeningFrameSpec
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.MeshStyle
import com.buildplan.app.render.filament.OpeningFrameGenerator
import com.buildplan.app.render.filament.SpikeVisibility
import com.buildplan.app.render.filament.openingFrameMeshes
import com.buildplan.app.render.filament.surfaceRoleOf
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE-013G — the owner's two model corrections, stated as things that can
 * fail: the stair no longer passes through a wall, and a glazed opening reads
 * as a window rather than as a hole.
 *
 * Both are held against the source reading that fixed them and against the
 * contracts that must survive — the canonical geometry untouched by the
 * frames, the id as the only join, and the renderer owning no rule.
 */
class Stage013GCorrectionTest {

    private val model = MarcowkiVisualModelV1
    private val building = model.building
    private val geometry = model.geometry

    @Test
    fun `G013-01 no wall prism intersects any stair tread`() {
        val treads = geometry.primitivesFor(model.stairId).filterIsInstance<SlabGeometry>()
        val walls = geometry.primitives.filterIsInstance<WallGeometry>()
        assertEquals(Grid.STAIR_RISER_COUNT, treads.size)

        walls.forEach { wall ->
            treads.forEach { tread ->
                val wallBox = wall.bounds
                val treadBox = tread.bounds
                val overlapX = minOf(wallBox.max.x, treadBox.max.x) - maxOf(wallBox.min.x, treadBox.min.x)
                val overlapY = minOf(wallBox.max.y, treadBox.max.y) - maxOf(wallBox.min.y, treadBox.min.y)
                val overlapZ = minOf(wallBox.max.z, treadBox.max.z) - maxOf(wallBox.min.z, treadBox.min.z)
                assertTrue(
                    "${wall.elementId.value} passes through a tread at ${tread.elevation}",
                    overlapX <= TOUCH || overlapY <= TOUCH || overlapZ <= TOUCH,
                )
            }
        }
    }

    @Test
    fun `G013-02 the pantry keeps its walls and door, stopped under the flight`() {
        // The source draws the pantry and a door into it; the correction is
        // not to delete the wall the stair hit but to stop it under the stair.
        val pantry = building.elements.filter { element ->
            element.roomIds.any { it.value.endsWith("-spizarnia") }
        }
        assertTrue(pantry.any { it.kind == BuildingElementKind.DOOR })
        val east = geometry.primitivesFor(BuildingElementId("marcowki-v1-parter-scianka-spizarni-wschod"))
            .filterIsInstance<WallGeometry>()
        val south = geometry.primitivesFor(BuildingElementId("marcowki-v1-parter-scianka-spizarni-poludnie"))
            .filterIsInstance<WallGeometry>()
        assertEquals("The east wall is split where the flight passes over it", 2, east.size)
        assertEquals(1, south.size)

        // Under the first tread of the north run, and exactly the clearance
        // below its underside; full height north of the well.
        val underFlight = east.single { it.footprint().maxOf { p -> p.z } > Grid.STAIR_NORTH_Z + 0.01 }
        val clear = east.single { it !== underFlight }
        val treads = geometry.primitivesFor(model.stairId).filterIsInstance<SlabGeometry>()
        val over = treads.filter { tread ->
            tread.outline.minOf { it.x } <= Grid.X_GF_PANTRY_EAST && tread.outline.maxOf { it.x } >= Grid.X_GF_PANTRY_EAST &&
                tread.outline.minOf { it.z } < underFlight.footprint().maxOf { p -> p.z } - 0.01
        }
        assertTrue(over.isNotEmpty())
        assertEquals(over.minOf { it.elevation } - Grid.STAIR_SOFFIT_CLEARANCE, underFlight.topElevation, 1e-9)
        assertEquals(Grid.GROUND_CLEAR_HEIGHT, clear.height, 1e-9)
        assertEquals(Grid.STAIR_NORTH_Z, clear.footprint().maxOf { it.z }, 1e-9)
        // Nothing full-height is left standing beside the flight as a post.
        assertTrue(underFlight.footprint().maxOf { it.z } >= Grid.Z_GF_PANTRY_SOUTH - 1e-9)

        // The well's edges are the wall faces they coincide with.
        assertEquals(Grid.exteriorFace(Grid.X_HOUSE_EAST_WALL, false), Grid.STAIR_EAST_X, 1e-9)
        assertEquals(Grid.partitionFace(Grid.X_GF_PANTRY_EAST, true), Grid.STAIR_CORE_EAST_X, 1e-9)
        assertEquals(Grid.partitionFace(Grid.Z_GF_PANTRY_SOUTH, false), Grid.STAIR_CORE_NORTH_Z, 1e-9)
        // And each within trace tolerance of the number first read off the plan.
        assertEquals(7.47, Grid.STAIR_EAST_X, 0.03)
        assertEquals(6.46, Grid.STAIR_CORE_EAST_X, 0.03)
        assertEquals(6.18, Grid.STAIR_CORE_NORTH_Z, 0.03)

        // The circulation of STAGE-013E is intact: the flight still leaves the
        // hall and arrives in the corridor through gaps, not walls.
        val hallEast = geometry.primitivesFor(BuildingElementId("marcowki-v1-parter-scianka-holu-wschod"))
            .filterIsInstance<WallGeometry>()
        assertEquals(2, hallEast.size)
        assertTrue(hallEast.none { wall -> wall.footprint().any { it.z > Grid.STAIR_CORE_SOUTH_Z + 0.01 && it.z < Grid.STAIR_SOUTH_Z - 0.01 } })
    }

    @Test
    fun `G013-03 every facade pane and rooflight is framed, and nothing else is`() {
        val profile = MarcowkiVisualPresentation.openingFrames
        assertEquals(Grid.facadeOpenings.size, model.facadeOpeningIds.size)
        assertEquals(11, model.facadeOpeningIds.size)
        assertEquals((model.facadeOpeningIds + model.roofId).toSet(), profile.framedElementIds)

        // Internal doors, the boiler-room door and the balustrades stay bare.
        val internalDoors = building.elements.filter { it.kind == BuildingElementKind.DOOR && it.id !in model.facadeOpeningIds }
        assertEquals(Grid.allInternalDoors.size + 1, internalDoors.size)
        internalDoors.forEach { assertNull(profile.specFor(it.id)) }
        model.balustradeIds.forEach { assertNull(profile.specFor(it)) }

        // Leaves only where the width asks for them; the gate never.
        assertNull(profile.specFor(model.garageDoorId)?.maxLeafWidth)
        assertNotNull(profile.specFor(model.roofId)?.maxLeafWidth)

        val frames = geometry.openingFrameMeshes(profile)
        assertEquals("One frame per facade pane and per rooflight", 11 + Grid.allRooflights.size, frames.size)
        frames.forEach { frame ->
            assertTrue(frame.elementId in profile.framedElementIds)
            assertEquals(MeshStyle.SOLID, frame.style)
            assertEquals(VisualSurfaceRole.OPAQUE_STUDY, surfaceRoleOf(frame, MarcowkiVisualPresentation.surfaceRoles))
            assertTrue(frame.triangleCount > 0)
            assertTrue("A frame is outlined like any solid", frame.edgeCount > 0)
        }

        // Each frame lies within its pane's outline, half a bar depth either
        // side of the pane's plane, and never reaches into the wall beside it.
        val spec = MarcowkiVisualPresentation.glazingFrameSpec
        profile.framedElementIds.forEach { id ->
            val panes = geometry.primitivesFor(id).filterIsInstance<OpeningPanelGeometry>()
            val own = frames.filter { it.elementId == id }
            assertEquals(panes.size, own.size)
            val paneBox = panes.map { it.bounds }.reduce { a, b -> a.encompass(b) }
            own.forEach { frame ->
                val slack = spec.barDepth / 2.0 + 1e-6
                assertTrue(frame.boundsCenter[0] - frame.boundsHalfExtent[0] >= paneBox.min.x - slack)
                assertTrue(frame.boundsCenter[0] + frame.boundsHalfExtent[0] <= paneBox.max.x + slack)
                assertTrue(frame.boundsCenter[1] - frame.boundsHalfExtent[1] >= paneBox.min.y - slack)
                assertTrue(frame.boundsCenter[1] + frame.boundsHalfExtent[1] <= paneBox.max.y + slack)
                assertTrue(frame.boundsCenter[2] - frame.boundsHalfExtent[2] >= paneBox.min.z - slack)
                assertTrue(frame.boundsCenter[2] + frame.boundsHalfExtent[2] <= paneBox.max.z + slack)
            }
        }
    }

    @Test
    fun `G013-04 the frame generator mitres bars and divides leaves on a plain rectangle`() {
        val pane = OpeningPanelGeometry(
            elementId = BuildingElementId("g-test-window"),
            vertices = listOf(
                com.buildplan.app.geometry.ModelPoint(0.0, 0.0, 0.0),
                com.buildplan.app.geometry.ModelPoint(2.4, 0.0, 0.0),
                com.buildplan.app.geometry.ModelPoint(2.4, 1.5, 0.0),
                com.buildplan.app.geometry.ModelPoint(0.0, 1.5, 0.0),
            ),
        )
        val spec = OpeningFrameSpec(barWidth = 0.1, barDepth = 0.08, maxLeafWidth = 1.2)
        val framed = requireNotNull(OpeningFrameGenerator.frame(pane, spec))

        // Four bars and one mullion: five prisms, six faces each, mitred so
        // that the bars tile the border without overlapping — the total
        // front-face area is exactly the border plus the mullion.
        val frontArea = (0 until framed.triangleCount).sumOf { triangle ->
            val a = framed.indices[triangle * 3]
            val b = framed.indices[triangle * 3 + 1]
            val c = framed.indices[triangle * 3 + 2]
            if (abs(framed.positions[a * 3 + 2] - 0.04f) > 1e-5f) return@sumOf 0.0
            val ax = framed.positions[a * 3].toDouble()
            val ay = framed.positions[a * 3 + 1].toDouble()
            val bx = framed.positions[b * 3].toDouble()
            val by = framed.positions[b * 3 + 1].toDouble()
            val cx = framed.positions[c * 3].toDouble()
            val cy = framed.positions[c * 3 + 1].toDouble()
            abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0
        }
        val border = 2.4 * 1.5 - 2.2 * 1.3
        val mullion = 0.1 * 1.3
        assertEquals(border + mullion, frontArea, 1e-6)

        // Thickness: every vertex lies on one of the two faces.
        for (vertex in 0 until framed.vertexCount) {
            val z = framed.positions[vertex * 3 + 2]
            assertTrue(abs(abs(z) - 0.04f) < 1e-5f)
        }
        // A bare profile frames nothing; a leafless spec adds no mullion.
        assertTrue(SyntheticDemoHouse.geometry.openingFrameMeshes(OpeningFrameProfile.NONE).isEmpty())
        val gate = requireNotNull(OpeningFrameGenerator.frame(pane, spec.copy(maxLeafWidth = null)))
        assertTrue(gate.triangleCount < framed.triangleCount)
    }

    @Test
    fun `G013-05 frames are presentation - the canonical model and the visibility path are unchanged by them`() {
        // The pane count is what it was: no frame became a primitive.
        assertEquals(27, geometry.primitives.count { it is OpeningPanelGeometry })
        assertTrue(geometry.primitives.none { it.elementId.value.contains("rama-okna") })
        assertTrue(building.elements.none { it.id.value.contains("rama-okna") })

        // Hiding decides by id, and a frame has its opening's id: the set the
        // renderer receives is the same set with or without frames.
        SpikeVisibility.entries.forEach { state ->
            val visible = DebugModel.MARCOWKI.visibleElementIds(state.toBuildingVisibility(model.atticId))
            geometry.openingFrameMeshes(MarcowkiVisualPresentation.openingFrames).forEach { frame ->
                val paneShown = geometry.primitives.first { it is OpeningPanelGeometry && it.elementId == frame.elementId }
                assertEquals(paneShown.elementId in visible, frame.elementId in visible)
            }
        }

        // Neither the domain nor the geometry knows a frame exists.
        listOf(
            "app/src/main/java/com/buildplan/app/domain",
            "app/src/main/java/com/buildplan/app/geometry",
        ).forEach { path ->
            File(repositoryRoot(), path).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                listOf("OpeningFrame", "maxLeafWidth", "barWidth").forEach { assertTrue("${file.path} knows $it", !text.contains(it)) }
            }
        }
        // And the renderer takes frames as meshes, with no rule of its own.
        val renderer = File(repositoryRoot(), "app/src/debug/java/com/buildplan/app/render/filament/FilamentModelRenderer.kt").readText()
        assertTrue(!renderer.contains("OpeningFrame"))
        assertTrue(DebugModel.SYNTHETIC.openingFrames === OpeningFrameProfile.NONE)
    }

    private companion object {
        /** Boxes that meet along a face or an edge share no volume. */
        const val TOUCH = 1e-6
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
