package com.buildplan.app.ui.components

import kotlin.math.*

/** Screen-space planar polygons. Larger depth is closer to the orthographic camera. */
internal data class RasterVertex(val x: Double, val y: Double, val depth: Double)
internal data class RasterFace(val vertices: List<RasterVertex>, val color: Int)

/** A bounded local depth buffer; unlike centroid sorting, handles overlapping sloped roofs. */
internal object DepthRasterizer {
    fun render(width: Int, height: Int, faces: List<RasterFace>, background: Int): IntArray {
        require(width in 1..1024 && height in 1..1024)
        val pixels = IntArray(width * height) { background }
        val depths = DoubleArray(pixels.size) { Double.NEGATIVE_INFINITY }
        for (face in faces) {
            val p = face.vertices
            if (p.size < 3 || p.any { !it.x.isFinite() || !it.y.isFinite() || !it.depth.isFinite() }) continue
            val a = p[0]
            val pair = (1 until p.lastIndex).firstOrNull { i ->
                abs((p[i].x-a.x)*(p[i+1].y-a.y)-(p[i+1].x-a.x)*(p[i].y-a.y)) > 1e-8
            } ?: continue
            val b = p[pair]; val c = p[pair+1]
            val det = (b.x-a.x)*(c.y-a.y)-(c.x-a.x)*(b.y-a.y)
            val dx = ((b.depth-a.depth)*(c.y-a.y)-(c.depth-a.depth)*(b.y-a.y))/det
            val dy = ((b.x-a.x)*(c.depth-a.depth)-(c.x-a.x)*(b.depth-a.depth))/det
            val minX = floor(p.minOf { it.x }).toInt().coerceAtLeast(0)
            val maxX = ceil(p.maxOf { it.x }).toInt().coerceAtMost(width-1)
            val minY = floor(p.minOf { it.y }).toInt().coerceAtLeast(0)
            val maxY = ceil(p.maxOf { it.y }).toInt().coerceAtMost(height-1)
            for (y in minY..maxY) for (x in minX..maxX) {
                val px=x+0.5; val py=y+0.5
                var inside=false
                var j=p.lastIndex
                for (i in p.indices) {
                    val u=p[i]; val v=p[j]
                    if ((u.y>py)!=(v.y>py) && px < (v.x-u.x)*(py-u.y)/(v.y-u.y)+u.x) inside=!inside
                    j=i
                }
                if (!inside) continue
                val z=a.depth+dx*(px-a.x)+dy*(py-a.y)
                val index=y*width+x
                if (z>depths[index]) { depths[index]=z; pixels[index]=face.color }
            }
        }
        return pixels
    }
}
