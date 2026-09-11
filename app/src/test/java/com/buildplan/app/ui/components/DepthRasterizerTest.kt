package com.buildplan.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class DepthRasterizerTest {
    @Test fun `sloped roof occludes near wall locally independent of submission order`() {
        // A single average-depth ordering cannot solve these intersecting projections.
        val roof=RasterFace(listOf(RasterVertex(0.0,0.0,0.0),RasterVertex(10.0,0.0,10.0),RasterVertex(10.0,10.0,10.0),RasterVertex(0.0,10.0,0.0)),1)
        val wall=RasterFace(listOf(RasterVertex(0.0,0.0,5.0),RasterVertex(10.0,0.0,5.0),RasterVertex(10.0,10.0,5.0),RasterVertex(0.0,10.0,5.0)),2)
        val first=DepthRasterizer.render(10,10,listOf(roof,wall),0)
        assertArrayEquals(first,DepthRasterizer.render(10,10,listOf(wall,roof),0))
        assertEquals(2,first[5*10+2]);assertEquals(1,first[5*10+8])
    }

    @Test fun `concave slab leaves the recess uncovered`() {
        val face=RasterFace(listOf(0 to 0,8 to 0,8 to 3,3 to 3,3 to 8,0 to 8).map { RasterVertex(it.first.toDouble(),it.second.toDouble(),1.0) },7)
        val pixels=DepthRasterizer.render(10,10,listOf(face),0)
        assertEquals(7,pixels[1*10+6]);assertEquals(7,pixels[6*10+1]);assertEquals(0,pixels[6*10+6])
    }
}
