package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.presentation.RoofCoverBlocker
import com.buildplan.app.presentation.RoofCoverProfile
import com.buildplan.app.presentation.RoofCoverSpec
import com.buildplan.app.presentation.RoofCoverStyle
import com.buildplan.app.reference.visual.MarcowkiPlanGrid as Grid
import com.buildplan.app.reference.visual.MarcowkiSourceEvidence
import com.buildplan.app.reference.visual.MarcowkiVisualModelV1
import com.buildplan.app.reference.visual.MarcowkiVisualPresentation
import com.buildplan.app.reference.visual.SourceFidelity
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE-013F — the roof covering, stated as things that can fail.
 *
 * A covering is presentation: it is laid over the canonical facets, carries
 * their id and nothing of its own, and goes away with them. Everything here
 * holds that to account on a plain JVM — determinism, the untouched roof,
 * facet-genericity, finite and well-wound triangles, the shallow offset, the
 * stable animation seam, the cutouts, the batching budget, the decomposition
 * contract and the purity of the layers beneath it.
 */
class RoofCoverMeshTest {

    private val model = MarcowkiVisualModelV1
    private val geometry = model.geometry
    private val profile = MarcowkiVisualPresentation.roofCover
    private val spec = MarcowkiVisualPresentation.roofCoverSpec
    private val covers = geometry.roofCoverMeshes(profile)
    private val roofFacets = geometry.primitivesFor(model.roofId).filterIsInstance<RoofFacetGeometry>()

    @Test
    fun `TILE013F-01 the same facet and spec lay the same tiles in the same order`() {
        val again = geometry.roofCoverMeshes(profile)
        assertEquals(covers.size, again.size)
        covers.zip(again).forEach { (first, second) ->
            assertEquals(first.elementId, second.elementId)
            assertEquals(first.facetOrdinal, second.facetOrdinal)
            assertTrue(first.positions.contentEquals(second.positions))
            assertTrue(first.normals.contentEquals(second.normals))
            assertTrue(first.signals.contentEquals(second.signals))
            assertTrue(first.indices.contentEquals(second.indices))
            assertEquals(first.tiles, second.tiles)
        }

        // A lone facet, twice, through the generator directly.
        val facet = roofFacets.first()
        val once = requireNotNull(RoofCoverGenerator.cover(facet, 0, spec, profile.blockers))
        val twice = requireNotNull(RoofCoverGenerator.cover(facet, 0, spec, profile.blockers))
        assertEquals(once.tiles, twice.tiles)
        assertTrue(once.positions.contentEquals(twice.positions))
    }

    @Test
    fun `TILE013F-02 laying the covering changes nothing in the canonical roof`() {
        val elementsBefore = model.building.elements
        val primitivesBefore = geometry.primitives
        val facetsBefore = roofFacets.map { it.vertices }
        val areaBefore = roofFacets.sumOf { it.area }

        geometry.roofCoverMeshes(profile)

        assertEquals(elementsBefore, model.building.elements)
        assertEquals(primitivesBefore, geometry.primitives)
        assertEquals(facetsBefore, geometry.primitivesFor(model.roofId).filterIsInstance<RoofFacetGeometry>().map { it.vertices })
        assertEquals(areaBefore, roofFacets.sumOf { it.area }, 1e-12)
        // Still the two facets that reconcile with the published roof area,
        // and still no element for any tile.
        assertEquals(2, roofFacets.size)
        assertEquals(150.4, areaBefore, 0.3)
        assertTrue(model.building.elements.none { it.id.value.contains("dachowk") || it.name.contains("Dachówk") })
        assertTrue(geometry.primitives.none { it is RoofFacetGeometry && it.elementId != model.roofId && it.elementId !in model.gableFrameIds })
    }

    @Test
    fun `TILE013F-03 the generator is facet-generic and not a gable algorithm`() {
        // The synthetic fixture's ridge runs along X where Marcówki's runs
        // along Z; both roofs get courses along their own eaves.
        val synthetic = SyntheticDemoHouse.geometry.roofCoverMeshes(
            RoofCoverProfile(mapOf(SyntheticDemoHouse.roofId to spec)),
        )
        assertEquals(2, synthetic.size)
        synthetic.forEach { cover ->
            assertTrue(cover.tileCount > 50)
            assertTrue("courses run across the slope", abs(abs(cover.across[0]) - 1f) < 1e-4f)
            assertTrue("up the slope rises", cover.upSlope[1] > 0.1f)
        }
        covers.forEach { cover ->
            assertTrue(abs(abs(cover.across[2]) - 1f) < 1e-4f)
            assertTrue(cover.upSlope[1] > 0.1f)
        }

        // A triangular hip facet, apex at the top: covered, trimmed to the
        // triangle, courses narrowing towards the apex.
        val hip = RoofFacetGeometry(
            elementId = BuildingElementId("e-test-hip"),
            vertices = listOf(ModelPoint(0.0, 3.0, 0.0), ModelPoint(6.0, 3.0, 0.0), ModelPoint(3.0, 5.5, 3.0)),
        )
        val hipCover = requireNotNull(RoofCoverGenerator.cover(hip, 0, spec))
        assertTrue(hipCover.tileCount > 20)
        assertEveryVertexOn(hip, hipCover)
        val widthByRow = hipCover.tiles.groupBy { it.row }.mapValues { (_, tiles) -> tiles.size }
        assertTrue(widthByRow.getValue(0) > widthByRow.getValue(widthByRow.keys.max()))

        // A shed roof turned 30 degrees in plan: courses still follow the
        // steepest ascent of its own plane, not an axis.
        val yaw = Math.toRadians(30.0)
        fun turned(x: Double, y: Double, z: Double) = ModelPoint(x * cos(yaw) - z * sin(yaw), y, x * sin(yaw) + z * cos(yaw))
        val shed = RoofFacetGeometry(
            elementId = BuildingElementId("e-test-shed"),
            vertices = listOf(turned(0.0, 3.0, 0.0), turned(5.0, 3.0, 0.0), turned(5.0, 4.5, 4.0), turned(0.0, 4.5, 4.0)),
        )
        val shedCover = requireNotNull(RoofCoverGenerator.cover(shed, 0, spec))
        assertTrue(abs(shedCover.across[1]) < 1e-4f)
        assertTrue(shedCover.upSlope[1] > 0.3f)
        assertTrue(abs(shedCover.across[0] * cos(yaw).toFloat() + shedCover.across[2] * sin(yaw).toFloat()) > 0.999f)
        assertEveryVertexOn(shed, shedCover)

        // A flat facet has no steepest ascent and still gets straight courses.
        val flat = RoofFacetGeometry(
            elementId = BuildingElementId("e-test-flat"),
            vertices = listOf(ModelPoint(0.0, 3.0, 0.0), ModelPoint(4.0, 3.0, 0.0), ModelPoint(4.0, 3.0, 2.0), ModelPoint(0.0, 3.0, 2.0)),
        )
        val flatCover = requireNotNull(RoofCoverGenerator.cover(flat, 0, spec))
        assertTrue(flatCover.tileCount > 20)
        assertTrue(abs(flatCover.normal[1] - 1f) < 1e-5f)

        // Nothing in the generator names the house, its grid or a ridge.
        val source = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/RoofCoverMesh.kt").readText()
        listOf("Marcowki", "MarcowkiPlanGrid", "RIDGE", "EAVES", "HOUSE_WIDTH", "SyntheticDemoHouse", "facets.size == 2")
            .forEach { assertTrue("Generator must not know $it", !source.contains(it)) }
    }

    @Test
    fun `TILE013F-04 every covering is finite, non-degenerate and wound to its normal`() {
        covers.forEach { cover ->
            cover.positions.forEach { assertTrue(it.isFinite()) }
            cover.normals.forEach { assertTrue(it.isFinite()) }
            cover.signals.forEach { assertTrue(it.isFinite()) }
            cover.boundsHalfExtent.forEach { assertTrue(it > 0f) }
            assertEquals(0, cover.indices.size % 3)
            cover.indices.forEach { assertTrue(it in 0 until cover.vertexCount) }
            for (vertex in 0 until cover.vertexCount) {
                val n = cover.normal(vertex)
                assertEquals(1f, sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]), 1e-4f)
            }
            for (triangle in 0 until cover.triangleCount) {
                val a = cover.indices[triangle * 3]
                val b = cover.indices[triangle * 3 + 1]
                val c = cover.indices[triangle * 3 + 2]
                val pa = cover.vertex(a)
                val pb = cover.vertex(b)
                val pc = cover.vertex(c)
                val ux = pb[0] - pa[0]; val uy = pb[1] - pa[1]; val uz = pb[2] - pa[2]
                val vx = pc[0] - pa[0]; val vy = pc[1] - pa[1]; val vz = pc[2] - pa[2]
                val cx = uy * vz - uz * vy
                val cy = uz * vx - ux * vz
                val cz = ux * vy - uy * vx
                val doubleArea = sqrt(cx * cx + cy * cy + cz * cz)
                assertTrue("triangle $triangle is degenerate", doubleArea > 1e-6)
                val n = cover.normal(a)
                assertTrue("triangle $triangle is wound against its normal", cx * n[0] + cy * n[1] + cz * n[2] > 0f)
            }
        }
    }

    @Test
    fun `TILE013F-05 every tile vertex stands a shallow bounded offset above its facet and inside it`() {
        assertEquals(roofFacets.size, covers.size)
        covers.forEach { cover ->
            val facet = roofFacets[cover.facetOrdinal]
            val plane = Plane.of(facet)
            var lowest = Double.POSITIVE_INFINITY
            var highest = Double.NEGATIVE_INFINITY
            for (vertex in 0 until cover.vertexCount) {
                val p = cover.vertex(vertex)
                val height = plane.heightOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
                lowest = minOf(lowest, height)
                highest = maxOf(highest, height)
            }
            assertTrue("a tile sits in or under the roof plane: $lowest", lowest >= spec.lift - 1e-4)
            assertTrue("a tile stands too far off the roof: $highest", highest <= spec.maximumOffset + 1e-4)
            // The relief is real: tails stand higher than heads, crests higher than edges.
            assertTrue(highest - lowest > spec.step + spec.camber * 0.9)
            assertEveryVertexOn(facet, cover)
        }
        // And the offset stays under the rooflights drawn proud of the same plane.
        assertTrue(spec.maximumOffset < Grid.ROOFLIGHT_PROUD_OF_ROOF)
    }

    @Test
    fun `TILE013F-06 tile row, column, ordinal and phase are deterministic and unique per facet`() {
        val again = geometry.roofCoverMeshes(profile)
        covers.zip(again).forEach { (cover, repeat) ->
            val keys = cover.tiles.map { Triple(it.facetOrdinal, it.row, it.column) }
            assertEquals("tile keys repeat", keys.size, keys.toSet().size)
            assertEquals((0 until cover.tileCount).toList(), cover.tiles.map { it.ordinal })
            cover.tiles.forEach { tile ->
                assertEquals(cover.facetOrdinal, tile.facetOrdinal)
                assertEquals(tile.ordinal * RoofCoverGenerator.VERTICES_PER_TILE, tile.firstVertex)
                assertTrue(tile.phase >= 0f && tile.phase < 1f)
                assertTrue(tile.row >= 0 && tile.column >= 0)
                // The vertex signal carries the same phase, and where along the tile the vertex is.
                for (corner in 0 until RoofCoverGenerator.VERTICES_PER_TILE) {
                    val vertex = tile.firstVertex + corner
                    assertEquals(tile.phase, cover.signals[vertex * 2], 0f)
                    val along = if (corner < RoofCoverGenerator.VERTICES_ACROSS) 0f else 1f
                    assertEquals(along, cover.signals[vertex * 2 + 1], 0f)
                }
            }
            assertEquals(cover.tiles, repeat.tiles)
            // Phases are spread, not one value.
            assertTrue(cover.tiles.map { it.phase }.toSet().size > cover.tileCount / 4)
            // Courses run from the eave upwards: row 0 is the lowest.
            val byRow = cover.tiles.groupBy { it.row }.mapValues { (_, tiles) -> tiles.map { it.center.y }.average() }
            byRow.keys.sorted().zipWithNext { lower, upper -> assertTrue(byRow.getValue(upper) > byRow.getValue(lower)) }
        }
    }

    @Test
    fun `TILE013F-07 no tile occupies a stack or a rooflight, and the tiles come close to them`() {
        val stacks = Grid.allStacks.map { doubleArrayOf(it.minX, it.minZ, it.maxX, it.maxZ) }
        val rooflights = Grid.allRooflights.map { doubleArrayOf(it.minX, it.minZ, it.maxX, it.maxZ) }
        val objects = stacks + rooflights
        assertEquals(objects.size, profile.blockers.size)

        val footprints = covers.flatMap { cover -> cover.tiles.map { cover.planBox(it) } }
        objects.forEach { box ->
            footprints.forEach { tile ->
                val apart = tile[2] <= box[0] + 1e-6 || tile[0] >= box[2] - 1e-6 ||
                    tile[3] <= box[1] + 1e-6 || tile[1] >= box[3] - 1e-6
                assertTrue("a tile ${tile.toList()} runs into ${box.toList()}", apart)
            }
            // The hole is a gap, not a bare slope: tiles are laid within a
            // tile length of every object.
            val near = footprints.count { tile ->
                tile[2] > box[0] - spec.moduleLength && tile[0] < box[2] + spec.moduleLength &&
                    tile[3] > box[1] - spec.moduleLength && tile[1] < box[3] + spec.moduleLength
            }
            assertTrue("no tile near ${box.toList()}", near >= 4)
        }

        // The blockers were read off the model's own geometry, not written again.
        val presentation = repositoryFile("app/src/debug/java/com/buildplan/app/reference/visual/MarcowkiVisualPresentation.kt").readText()
        listOf("5.45", "6.05", "0.45", "1.30", "6.59", "7.44").forEach {
            assertTrue("A blocker coordinate is restated: $it", !presentation.contains(it))
        }
        assertTrue(presentation.contains("primitivesFor(") && presentation.contains("RoofCoverBlocker.aroundPlan("))

        // A blocker in the way removes tiles; the same facet with none has more.
        val facet = roofFacets.first()
        val open = requireNotNull(RoofCoverGenerator.cover(facet, 0, spec))
        val blocked = requireNotNull(RoofCoverGenerator.cover(facet, 0, spec, profile.blockers))
        assertTrue(blocked.tileCount < open.tileCount)
        val middle = facet.bounds.center
        val blocker = RoofCoverBlocker(
            listOf(
                PlanPoint(middle.x - 0.5, middle.z - 0.5), PlanPoint(middle.x + 0.5, middle.z - 0.5),
                PlanPoint(middle.x + 0.5, middle.z + 0.5), PlanPoint(middle.x - 0.5, middle.z + 0.5),
            ),
        )
        val punched = requireNotNull(RoofCoverGenerator.cover(facet, 0, spec, listOf(blocker)))
        assertTrue(punched.tileCount < open.tileCount)
        assertTrue(punched.tiles.none { tile ->
            abs(tile.center.x - middle.x) < 0.5 && abs(tile.center.z - middle.z) < 0.5
        })
    }

    @Test
    fun `TILE013F-08 the covering is a handful of batches within budget, never an entity per tile`() {
        val tiles = covers.sumOf { it.tileCount }
        val triangles = covers.sumOf { it.triangleCount }
        val vertices = covers.sumOf { it.vertexCount }
        assertTrue("$tiles tiles is not a covering", tiles >= 1000)
        assertTrue("${covers.size} renderables", covers.size <= 4)
        assertTrue("$triangles triangles", triangles <= 25_000)
        assertEquals(tiles * RoofCoverGenerator.VERTICES_PER_TILE, vertices)
        assertTrue(triangles <= tiles * RoofCoverGenerator.TRIANGLES_PER_TILE)
        assertTrue(triangles >= tiles * RoofCoverGenerator.TRIANGLES_PER_TILE * 9 / 10)

        // The renderer uploads one entity per batch: one create, no loop over
        // the tiles, and no outline.
        val renderer = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/FilamentModelRenderer.kt").readText()
        val upload = renderer.substringAfter("private fun uploadRoofCover").substringBefore("private fun uploadGrid")
        assertEquals(1, Regex("entityManager\\.create\\(\\)").findAll(upload).count())
        assertEquals(1, Regex("RenderableManager\\.Builder\\(1\\)").findAll(upload).count())
        assertTrue(!upload.contains(".tiles") && !upload.contains("forEach") && !upload.contains("for ("))
        assertTrue(!upload.contains("uploadOutline("))
        assertTrue(renderer.contains("roofCovers.forEach(::uploadRoofCover)"))
    }

    @Test
    fun `TILE013F-09 the covering is present exactly when its roof is`() {
        covers.forEach { assertEquals(model.roofId, it.elementId) }
        val coverIds = covers.mapTo(mutableSetOf()) { it.elementId }
        SpikeVisibility.entries.forEach { state ->
            val visible = DebugModel.MARCOWKI.visibleElementIds(state.toBuildingVisibility(model.atticId))
            val roofShown = !state.toBuildingVisibility(model.atticId).roofHidden
            if (roofShown) {
                assertTrue("$state must show the covering", visible.containsAll(coverIds))
            } else {
                assertTrue("$state must not show the covering", coverIds.none { it in visible })
            }
        }
        assertTrue(model.roofId in DebugModel.MARCOWKI.visibleElementIds(BuildingVisibility(hiddenFloorIds = setOf(model.atticId))))
        assertTrue(model.roofId !in DebugModel.MARCOWKI.visibleElementIds(BuildingVisibility(roofHidden = true)))

        // The renderer keys the covering into the same id maps as the planes
        // — the one visibility path — and into nothing else.
        val renderer = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/FilamentModelRenderer.kt").readText()
        val upload = renderer.substringAfter("private fun uploadRoofCover").substringBefore("private fun uploadGrid")
        assertTrue(upload.contains("entitiesByElementId.getOrPut(cover.elementId)"))
        assertTrue(upload.contains("elementIdByEntity[entity] = cover.elementId"))
        assertTrue(!upload.contains("outlinesByElementId"))
        val setVisible = renderer.substringAfter("fun setVisibleElements").substringBefore("private fun setInScene")
        assertTrue(!setVisible.contains("roofCover") && !setVisible.contains("tile"))

        // The synthetic fixture declares no covering and gets none.
        assertTrue(DebugModel.SYNTHETIC.roofCover === RoofCoverProfile.NONE)
        assertTrue(SyntheticDemoHouse.geometry.roofCoverMeshes(RoofCoverProfile.NONE).isEmpty())
        assertEquals(DebugModel.MARCOWKI.roofCover.coveredElementIds, setOf(model.roofId))
    }

    @Test
    fun `TILE013F-10 the domain and the canonical geometry know nothing of tiles, styles or phases`() {
        val forbidden = listOf("RoofCover", "CURVED_TILE", "\\bTiles?\\b", "\\bphase\\b", "RenderStyle", "camber")
            .map { Regex(it) }
        listOf(
            "app/src/main/java/com/buildplan/app/domain",
            "app/src/main/java/com/buildplan/app/geometry",
        ).forEach { path ->
            File(repositoryRoot(), path).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                forbidden.forEach { pattern ->
                    assertTrue("${file.path} knows ${pattern.pattern}", !pattern.containsMatchIn(text))
                }
            }
        }
        // The presentation spec and the generator import no Filament and copy
        // no semantics; the decomposition profile knows no covering.
        listOf(
            "app/src/debug/java/com/buildplan/app/presentation/RoofCover.kt",
            "app/src/debug/java/com/buildplan/app/render/filament/RoofCoverMesh.kt",
        ).forEach { path ->
            val text = repositoryFile(path).readText()
            listOf(
                "import com.google.android.filament",
                "import com.buildplan.app.domain.model.BuildingElementKind",
                "import com.buildplan.app.domain.model.BuildingElementScope",
                "import com.buildplan.app.domain.model.BuildingVisibility",
                "import com.buildplan.app.domain.model.Building\n",
                ".kind", ".roomIds", ".scope", ".floorId",
            ).forEach { assertTrue("$path knows $it", !text.contains(it)) }
        }
        val decomposition = repositoryFile("app/src/debug/java/com/buildplan/app/presentation/DecompositionProfile.kt").readText()
        assertTrue(!decomposition.contains("RoofCover"))
        // The module is a recorded display assumption, not a fact.
        val record = MarcowkiSourceEvidence.displayAssumptions.single { it.name == "Moduł pokrycia dachu" }
        assertEquals(SourceFidelity.DISPLAY_ASSUMPTION, record.fidelity)
        assertTrue(record.value.contains("0.25") && record.value.contains("0.40"))
    }

    @Test
    fun `TILE013F-11 the covering uses no texture, no asset and no new dependency`() {
        val rasters = setOf("png", "jpg", "jpeg", "webp", "ktx", "ktx2", "basis", "hdr", "exr", "filamat", "glb", "gltf")
        val assets = repositoryFiles().filter { it.extension.lowercase() in rasters && !it.path.replace('\\', '/').contains("/res/") }
        assertTrue("Assets committed: $assets", assets.isEmpty())
        File(repositoryRoot(), "app/src/debug/java/com/buildplan/app/render/filament").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                listOf("Texture", "sampler2D", "SAMPLER", "normalMap", "KtxLoader", "loadAsset").forEach {
                    assertTrue("${file.name} uses $it", !text.contains(it))
                }
            }
        val build = repositoryFile("app/build.gradle.kts").readText()
        assertEquals(2, Regex("debugImplementation\\(libs\\.filam").findAll(build).count())
        assertTrue(!build.contains("gltfio") && !build.contains("filament-utils") && !build.contains("texture"))
        // The material shader is still the one lit, untextured surface.
        val material = repositoryFile("app/src/debug/java/com/buildplan/app/render/filament/TechnicalMaterial.kt").readText()
        assertTrue(!material.contains("texture(") && !material.contains("requires"))
    }

    @Test
    fun `TILE013F-12 the study, its presets and the facet bake are as STAGE-013E left them`() {
        assertEquals(2, RenderStyle.entries.size)
        RenderStyle.entries.forEach { style ->
            val rgb = style.roofCover.take(3)
            assertTrue("${style.name} tints the covering", rgb.max() - rgb.min() < 0.06f)
            val difference = style.surface[0] - style.roofCover[0]
            assertTrue("${style.name} covering must be a step darker, not a colour", difference > 0.05f && difference < 0.2f)
        }
        // The facet itself still bakes as before: one plane, two triangles,
        // its own four edges. The covering is beside it, not in it.
        roofFacets.forEach { facet ->
            val mesh = facet.toRenderMesh()
            assertEquals(2, mesh.triangleCount)
            assertEquals(4, mesh.edgeCount)
            assertEquals(MeshStyle.SOLID, mesh.style)
        }
        // The close-up preset frames the roof, deterministically, and closer.
        val bounds = requireNotNull(geometry.bounds)
        val focus = DebugModel.MARCOWKI.focusBounds(ModelViewPreset.ROOF_COVER_CLOSEUP.focus, bounds)
        assertNotNull(focus)
        assertTrue(focus.sizeY < bounds.sizeY)
        val framing = ModelViewPreset.ROOF_COVER_CLOSEUP.framing(bounds, focus)
        assertEquals(framing, ModelViewPreset.ROOF_COVER_CLOSEUP.framing(bounds, focus))
        assertTrue(framing.distance < ModelViewPreset.FULL_AXON.framing(bounds).distance)
        assertEquals(SpikeVisibility.EVERYTHING, ModelViewPreset.ROOF_COVER_CLOSEUP.visibility)
        // A spec that does not lap is refused; so is a tail rounder than the lap.
        assertRefused { spec.copy(exposure = spec.moduleLength) }
        assertRefused { spec.copy(tailRound = spec.lap + 0.01) }
        assertRefused { spec.copy(lift = 0.0) }
        assertEquals(RoofCoverStyle.CURVED_TILE, spec.style)
    }

    // --- helpers -----------------------------------------------------

    private fun assertRefused(build: () -> RoofCoverSpec) {
        try {
            build()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected the spec to be refused")
    }

    /** Every vertex of [cover] lies over [facet]'s polygon, within a hair. */
    private fun assertEveryVertexOn(facet: RoofFacetGeometry, cover: RoofCoverMesh) {
        val plane = Plane.of(facet)
        for (vertex in 0 until cover.vertexCount) {
            val p = cover.vertex(vertex)
            assertTrue(
                "vertex $vertex at ${p.toList()} lies outside facet ${facet.elementId.value}",
                plane.contains(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), 1e-4),
            )
        }
    }

    private fun RoofCoverMesh.vertex(index: Int) = floatArrayOf(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2])
    private fun RoofCoverMesh.normal(index: Int) = floatArrayOf(normals[index * 3], normals[index * 3 + 1], normals[index * 3 + 2])

    /** The plan box of one tile's vertices: minX, minZ, maxX, maxZ. */
    private fun RoofCoverMesh.planBox(tile: RoofCoverTile): DoubleArray {
        var minX = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        for (corner in 0 until RoofCoverGenerator.VERTICES_PER_TILE) {
            val v = vertex(tile.firstVertex + corner)
            minX = minOf(minX, v[0].toDouble()); maxX = maxOf(maxX, v[0].toDouble())
            minZ = minOf(minZ, v[2].toDouble()); maxZ = maxOf(maxZ, v[2].toDouble())
        }
        return doubleArrayOf(minX, minZ, maxX, maxZ)
    }

    /** A facet's plane with an in-plane basis, for height and containment. */
    private class Plane(
        private val origin: ModelPoint,
        private val normal: DoubleArray,
        private val axisA: DoubleArray,
        private val axisB: DoubleArray,
        private val outline: List<DoubleArray>,
    ) {
        fun heightOf(x: Double, y: Double, z: Double): Double =
            (x - origin.x) * normal[0] + (y - origin.y) * normal[1] + (z - origin.z) * normal[2]

        fun contains(x: Double, y: Double, z: Double, tolerance: Double): Boolean {
            val a = (x - origin.x) * axisA[0] + (y - origin.y) * axisA[1] + (z - origin.z) * axisA[2]
            val b = (x - origin.x) * axisB[0] + (y - origin.y) * axisB[1] + (z - origin.z) * axisB[2]
            var sign = 0.0
            outline.indices.forEach { index ->
                val p = outline[index]
                val q = outline[(index + 1) % outline.size]
                val cross = (q[0] - p[0]) * (b - p[1]) - (q[1] - p[1]) * (a - p[0])
                val length = sqrt((q[0] - p[0]) * (q[0] - p[0]) + (q[1] - p[1]) * (q[1] - p[1]))
                val distance = cross / length
                if (abs(distance) <= tolerance) return@forEach
                if (sign == 0.0) sign = distance else if (sign * distance < 0) return false
            }
            return true
        }

        companion object {
            fun of(facet: RoofFacetGeometry): Plane {
                val v = facet.vertices
                var nx = 0.0; var ny = 0.0; var nz = 0.0
                v.indices.forEach { i ->
                    val c = v[i]; val n = v[(i + 1) % v.size]
                    nx += (c.y - n.y) * (c.z + n.z); ny += (c.z - n.z) * (c.x + n.x); nz += (c.x - n.x) * (c.y + n.y)
                }
                val length = sqrt(nx * nx + ny * ny + nz * nz)
                val sign = if (ny < 0) -1.0 else 1.0
                val normal = doubleArrayOf(sign * nx / length, sign * ny / length, sign * nz / length)
                val e = doubleArrayOf(v[1].x - v[0].x, v[1].y - v[0].y, v[1].z - v[0].z)
                val el = sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2])
                val axisA = doubleArrayOf(e[0] / el, e[1] / el, e[2] / el)
                val axisB = doubleArrayOf(
                    normal[1] * axisA[2] - normal[2] * axisA[1],
                    normal[2] * axisA[0] - normal[0] * axisA[2],
                    normal[0] * axisA[1] - normal[1] * axisA[0],
                )
                val outline = v.map { p ->
                    doubleArrayOf(
                        (p.x - v[0].x) * axisA[0] + (p.y - v[0].y) * axisA[1] + (p.z - v[0].z) * axisA[2],
                        (p.x - v[0].x) * axisB[0] + (p.y - v[0].y) * axisB[1] + (p.z - v[0].z) * axisB[2],
                    )
                }
                return Plane(v[0], normal, axisA, axisB, outline)
            }
        }
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
