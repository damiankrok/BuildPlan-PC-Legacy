package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.geometry.*
import org.junit.Assert.*
import org.junit.Test

class CoalescedMeshTest {
    @Test fun `two wall triangles keep perimeter but suppress their shared diagonal`() {
        val id=BuildingElementId("synthetic-wall")
        val a=ModelPoint(0.0,0.0,0.0); val b=ModelPoint(4.0,0.0,0.0)
        val c=ModelPoint(4.0,3.0,0.0); val d=ModelPoint(0.0,3.0,0.0)
        val parts=listOf(GablePanelGeometry(id,listOf(a,b,c)),GablePanelGeometry(id,listOf(a,c,d)))
        val result=parts.toCoalescedRenderMeshes().single()
        assertEquals(4,result.edgeCount)
        assertEquals(2,result.triangleCount)
    }
}
