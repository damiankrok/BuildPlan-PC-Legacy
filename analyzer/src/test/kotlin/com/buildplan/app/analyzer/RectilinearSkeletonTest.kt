package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.roof.RectilinearSkeleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AN023-ROOF — the skeleton solver produces gable and hip families from one algorithm. */
class RectilinearSkeletonTest {

    private fun rect(w: Double, d: Double) = listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, d), Pt(0.0, d))

    @Test
    fun `hip roof over a rectangle has four facets, two triangles and two trapezoids, one ridge`() {
        val result = RectilinearSkeleton.solve(rect(8.0, 12.0), listOf(1.0, 1.0, 1.0, 1.0))
        assertTrue(result.note ?: "", result.complete)
        assertEquals(4, result.facets.size)
        assertEquals(4.0, result.maxTime, 1e-6)
        val areas = result.facets.map { Polygon(it.ring).area }.sorted()
        // Two triangles of base 8 and height 4 (16 each), two trapezoids: (12 + 4) / 2 * 4 = 32 each.
        assertEquals(16.0, areas[0], 1e-6)
        assertEquals(16.0, areas[1], 1e-6)
        assertEquals(32.0, areas[2], 1e-6)
        assertEquals(32.0, areas[3], 1e-6)
        assertEquals(96.0, areas.sum(), 1e-6)
        val ridges = result.arcs.filter { it.isRidgeLike }
        assertEquals(1, ridges.size)
        assertEquals(4.0, kotlin.math.abs(ridges[0].a.z - ridges[0].b.z), 1e-6)
    }

    @Test
    fun `gable roof over a rectangle has two facets reaching the gable ends`() {
        // Short edges (index 0 and 2) are gables: speed 0.
        val result = RectilinearSkeleton.solve(rect(8.0, 12.0), listOf(0.0, 1.0, 0.0, 1.0))
        assertTrue(result.note ?: "", result.complete)
        assertEquals(2, result.facets.size)
        assertEquals(4.0, result.maxTime, 1e-6)
        result.facets.forEach { f ->
            assertEquals(48.0, Polygon(f.ring).area, 1e-6)
            assertEquals(4, f.ring.size)
        }
        val ridge = result.arcs.first { it.isRidgeLike }
        assertEquals(12.0, kotlin.math.abs(ridge.a.z - ridge.b.z), 1e-6)
    }

    @Test
    fun `hip roof over an L-shaped footprint is complete and covers the plan area`() {
        val l = listOf(Pt(0.0, 0.0), Pt(12.0, 0.0), Pt(12.0, 6.0), Pt(6.0, 6.0), Pt(6.0, 10.0), Pt(0.0, 10.0))
        val result = RectilinearSkeleton.solve(l, List(6) { 1.0 })
        assertTrue(result.note ?: "", result.complete)
        assertEquals(6, result.facets.size)
        val plan = Polygon(l).area
        assertEquals(plan, result.facets.sumOf { Polygon(it.ring).area }, 1e-6)
        assertEquals(3.0, result.maxTime, 1e-6)
    }

    @Test
    fun `winding does not matter`() {
        val cw = rect(8.0, 12.0).reversed()
        val result = RectilinearSkeleton.solve(cw, listOf(1.0, 1.0, 1.0, 1.0))
        assertTrue(result.complete)
        assertEquals(96.0, result.facets.sumOf { Polygon(it.ring).area }, 1e-6)
    }
}
