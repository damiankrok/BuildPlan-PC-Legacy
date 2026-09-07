package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.visibleElements
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import com.buildplan.app.geometry.demo.SyntheticDemoHouse
import com.buildplan.app.geometry.primitivesOf
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE-012 — the geometry-to-triangles adapter that feeds the Filament spike.
 *
 * These are the parts of the renderer that can be checked without a GPU: the
 * join back to the semantic model, the triangle counts, and the two things that
 * are invisible in a screenshot until they are wrong at a particular angle —
 * whether normals point out of the solid, and whether each triangle is wound to
 * agree with the normal it is shaded by.
 */
class BuildingRenderMeshTest {

    @Test
    fun `R012-06 every mesh answers with the element id of the primitive it came from`() {
        val primitives = SyntheticDemoHouse.geometry.primitives
        val meshes = primitives.toRenderMeshes()

        assertEquals(primitives.size, meshes.size)
        assertEquals(
            primitives.map { it.elementId },
            meshes.map { it.elementId },
        )
    }

    @Test
    fun `R012-06 the gable roof is one element id and two meshes`() {
        val roofMeshes = SyntheticDemoHouse.geometry
            .primitivesFor(SyntheticDemoHouse.roofId)
            .toRenderMeshes()

        assertEquals(2, roofMeshes.size)
        assertEquals(
            setOf(SyntheticDemoHouse.roofId),
            roofMeshes.mapTo(mutableSetOf()) { it.elementId },
        )
    }

    @Test
    fun `a wall bakes to a closed box of six flat faces`() {
        val wall = WallGeometry(
            elementId = BuildingElementId("e-test-wall"),
            start = PlanPoint(0.0, 0.0),
            end = PlanPoint(4.0, 0.0),
            baseElevation = 0.0,
            height = 2.5,
            thickness = 0.3,
        )

        val mesh = wall.toRenderMesh()

        // Two caps and four sides, none of them welded to another.
        assertEquals(24, mesh.vertexCount)
        assertEquals(12, mesh.triangleCount)
    }

    @Test
    fun `a rectangular slab bakes to a closed box and a roof facet to a single plane`() {
        val slab = SlabGeometry(
            elementId = BuildingElementId("e-test-slab"),
            outline = listOf(
                PlanPoint(0.0, 0.0),
                PlanPoint(3.0, 0.0),
                PlanPoint(3.0, 2.0),
                PlanPoint(0.0, 2.0),
            ),
            elevation = 1.0,
            thickness = 0.2,
        )
        val facet = RoofFacetGeometry(
            elementId = BuildingElementId("e-test-roof"),
            vertices = listOf(
                ModelPoint(0.0, 3.0, 0.0),
                ModelPoint(4.0, 3.0, 0.0),
                ModelPoint(4.0, 5.0, 2.0),
                ModelPoint(0.0, 5.0, 2.0),
            ),
        )

        assertEquals(24, slab.toRenderMesh().vertexCount)
        assertEquals(12, slab.toRenderMesh().triangleCount)
        assertEquals(4, facet.toRenderMesh().vertexCount)
        assertEquals(2, facet.toRenderMesh().triangleCount)
    }

    @Test
    fun `every normal is a unit vector`() {
        SyntheticDemoHouse.geometry.primitives.toRenderMeshes().forEach { mesh ->
            for (vertex in 0 until mesh.vertexCount) {
                val x = mesh.normals[vertex * 3]
                val y = mesh.normals[vertex * 3 + 1]
                val z = mesh.normals[vertex * 3 + 2]
                val length = sqrt(x * x + y * y + z * z)
                assertTrue(
                    "${mesh.elementId.value} vertex $vertex has normal length $length",
                    abs(length - 1.0f) < 1e-4f,
                )
            }
        }
    }

    @Test
    fun `solid normals point out of the solid`() {
        val solids = SyntheticDemoHouse.geometry.primitives
            .filter { it is WallGeometry || it is SlabGeometry }

        solids.toRenderMeshes().forEach { mesh ->
            val center = mesh.boundsCenter
            for (vertex in 0 until mesh.vertexCount) {
                val outward =
                    (mesh.positions[vertex * 3] - center[0]) * mesh.normals[vertex * 3] +
                        (mesh.positions[vertex * 3 + 1] - center[1]) * mesh.normals[vertex * 3 + 1] +
                        (mesh.positions[vertex * 3 + 2] - center[2]) * mesh.normals[vertex * 3 + 2]
                assertTrue(
                    "${mesh.elementId.value} vertex $vertex is shaded by an inward normal",
                    outward > 0f,
                )
            }
        }
    }

    @Test
    fun `every triangle is wound to agree with the normal it is shaded by`() {
        SyntheticDemoHouse.geometry.primitives.toRenderMeshes().forEach { mesh ->
            for (triangle in 0 until mesh.triangleCount) {
                val a = mesh.indices[triangle * 3]
                val b = mesh.indices[triangle * 3 + 1]
                val c = mesh.indices[triangle * 3 + 2]

                val edge1 = mesh.vertex(b) - mesh.vertex(a)
                val edge2 = mesh.vertex(c) - mesh.vertex(a)
                val geometric = edge1 cross edge2
                val shading = Vector(
                    mesh.normals[a * 3],
                    mesh.normals[a * 3 + 1],
                    mesh.normals[a * 3 + 2],
                )

                assertTrue(
                    "${mesh.elementId.value} triangle $triangle is wound against its normal",
                    (geometric dot shading) > 0f,
                )
            }
        }
    }

    @Test
    fun `a flat facet still gets a box with depth on every axis`() {
        val horizontal = RoofFacetGeometry(
            elementId = BuildingElementId("e-test-flat"),
            vertices = listOf(
                ModelPoint(0.0, 4.0, 0.0),
                ModelPoint(3.0, 4.0, 0.0),
                ModelPoint(3.0, 4.0, 2.0),
                ModelPoint(0.0, 4.0, 2.0),
            ),
        )

        val mesh = horizontal.toRenderMesh()

        assertTrue(
            "a zero half-extent lets the culler drop the facet",
            mesh.boundsHalfExtent.all { it > 0f },
        )
        // Flat and seen from above, whichever way its vertices were written.
        assertTrue(mesh.normals[1] > 0.99f)
    }

    @Test
    fun `hiding the roof and the upper floor leaves only the lower geometry to bake`() {
        val visible = SyntheticDemoHouse.geometry.primitivesOf(
            SyntheticDemoHouse.building.visibleElements(
                BuildingVisibility(
                    hiddenFloorIds = setOf(SyntheticDemoHouse.atticId),
                    roofHidden = true,
                ),
            ),
        )

        val elementIds = visible.toRenderMeshes().mapTo(mutableSetOf()) { it.elementId }

        assertTrue(SyntheticDemoHouse.roofId !in elementIds)
        assertTrue(SyntheticDemoHouse.atticSouthWallId !in elementIds)
        assertTrue(SyntheticDemoHouse.foundationId in elementIds)
        assertTrue(SyntheticDemoHouse.southWallId in elementIds)
        assertTrue(SyntheticDemoHouse.groundCeilingSlabId in elementIds)
    }

    // --- helpers -------------------------------------------------------------

    private data class Vector(val x: Float, val y: Float, val z: Float) {
        operator fun minus(other: Vector) = Vector(x - other.x, y - other.y, z - other.z)
        infix fun cross(other: Vector) = Vector(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

        infix fun dot(other: Vector) = x * other.x + y * other.y + z * other.z
    }

    private fun BuildingRenderMesh.vertex(index: Int) =
        Vector(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2])
}
