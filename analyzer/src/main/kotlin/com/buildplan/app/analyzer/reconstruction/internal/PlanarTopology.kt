package com.buildplan.app.analyzer.reconstruction.internal

import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.triangulate.polygon.PolygonTriangulator

/** JTS never leaves this boundary. Holes are preserved as nonoverlapping simple triangles. */
internal object PlanarTopology {
    private val precision = PrecisionModel(10000.0) // 0.1 mm computational grid, not measurement fidelity
    private val factory = GeometryFactory(precision)
    private fun geometry(p: Polygon): Geometry = factory.createPolygon((p.vertices + p.vertices.first()).map { Coordinate(it.x, it.z) }.toTypedArray())
    private fun polygons(g: Geometry): List<Polygon> = when {
        g.isEmpty -> emptyList()
        g is org.locationtech.jts.geom.Polygon && g.numInteriorRing == 0 -> listOf(Polygon(g.exteriorRing.coordinates.dropLast(1).map { Pt(it.x, it.y) }).normalisedWinding())
        g is org.locationtech.jts.geom.Polygon -> polygons(PolygonTriangulator.triangulate(g))
        g.numGeometries > 1 -> (0 until g.numGeometries).flatMap { polygons(g.getGeometryN(it)) }
        else -> emptyList()
    }
    fun valid(p: Polygon): Boolean = p.area > 1e-8 && geometry(p).isValid
    fun intersection(a: Polygon, b: Polygon): List<Polygon> = polygons(OverlayNG.overlay(geometry(a), geometry(b), OverlayNG.INTERSECTION, precision))
    fun difference(a: Polygon, b: Polygon): List<Polygon> = polygons(OverlayNG.overlay(geometry(a), geometry(b), OverlayNG.DIFFERENCE, precision))
    fun union(a: Polygon, b: Polygon): List<Polygon> = polygons(OverlayNG.overlay(geometry(a), geometry(b), OverlayNG.UNION, precision))
    fun distance(a: Polygon, b: Polygon): Double = geometry(a).distance(geometry(b))
    fun hull(points: List<Pt>): Polygon? = polygons(factory.createMultiPointFromCoords(points.map { Coordinate(it.x,it.z) }.toTypedArray()).convexHull()).firstOrNull()
    fun inset(p:Polygon,distance:Double):List<Polygon> { require(distance>=0); return polygons(geometry(p).buffer(-distance)) }
    fun convexParts(p:Polygon):List<Polygon> {
        val shape=geometry(p)
        return if(shape.convexHull().area-shape.area<1e-8) listOf(p) else polygons(PolygonTriangulator.triangulate(shape))
    }
}
