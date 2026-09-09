package com.buildplan.app.analyzer.roof

import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RoofCandidate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

/**
 * The roof underside as a function of plan position, read straight off the
 * facet candidates: inside a facet the height rises from that facet's eave
 * edge at the pitch; under a flat secondary mass it is that mass's top;
 * elsewhere it is unknown (null).
 *
 * The quantity engine uses this for attic wall faces, sloped ceilings and
 * usable-area rules. No structural thickness is subtracted: the source
 * states none, and the assumption is recorded once, not per surface.
 */
class RoofHeightField(private val roof: RoofCandidate) {

    private val tanP = tan(Math.toRadians(roof.pitchDegrees.value ?: 0.0))
    private val eave = roof.eaveElevation.value ?: 0.0
    private val facetPolygons = roof.facets.map { f -> Polygon(f.vertices.map { Pt(it.x, it.z) }) to f }
    private val masses = roof.secondaryMasses.map { it.outline to (it.topElevation.value ?: eave) }

    /** Roof underside elevation above the point, or null when no roof covers it. */
    fun heightAt(p: Pt): Double? {
        for ((poly, facet) in facetPolygons) {
            if (poly.contains(p) || onBoundary(poly, p)) {
                if (roof.family == com.buildplan.app.analyzer.candidate.RoofFamily.FLAT) return eave
                val d = distanceToLine(p, facet.eaveEdge.a, facet.eaveEdge.b)
                return eave + d * tanP
            }
        }
        for ((poly, top) in masses) if (poly.contains(p)) return top
        return null
    }

    val ridgeElevation: Double get() = roof.ridgeElevation.value ?: eave

    private fun onBoundary(poly: Polygon, p: Pt): Boolean =
        poly.edges.any { e -> distanceToSegment(p, e.a, e.b) < 1e-6 }

    private fun distanceToLine(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = max(1e-12, kotlin.math.hypot(dx, dz))
        return abs((p.x - a.x) * dz - (p.z - a.z) * dx) / len
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len2 = dx * dx + dz * dz
        val t = if (len2 < 1e-12) 0.0 else (((p.x - a.x) * dx + (p.z - a.z) * dz) / len2).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(p.x - (a.x + t * dx), p.z - (a.z + t * dz))
    }
}
