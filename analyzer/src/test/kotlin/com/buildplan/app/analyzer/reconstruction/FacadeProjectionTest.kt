package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import org.junit.Assert.*
import org.junit.Test

class FacadeProjectionTest {
    private fun m(v:Double)=Measured.assumed(v,MeasureUnit.METER,"synthetic source")
    private fun polygon(vararg p:Pair<Double,Double>)=Polygon(p.map { Pt(it.first,it.second) })
    private fun candidate():ProjectAnalysisCandidate {
        val floor=FloorCandidate("f","Floor",0,null,polygon(0.0 to 0.0,8.0 to 0.0,8.0 to 10.0,0.0 to 10.0),null,m(0.0),m(3.0),emptyList(),emptyList(),"source-plan")
        val levels=LevelsCandidate(m(0.0),m(0.0),m(3.0),m(3.0),m(3.0),m(0.2),m(1.0),m(3.0),m(6.0),m(6.0),m(3.0),emptyList())
        val facade=FacadeEnvelopeCandidate("front","f",Segment(Pt(0.0,10.0),Pt(8.0,10.0)),m(0.0),m(0.3),listOf(Pt3(0.0,3.0,10.0),Pt3(4.0,6.0,10.0),Pt3(8.0,3.0,10.0)),emptyList())
        return ProjectAnalysisCandidate(listOf(floor),emptyList(),emptyList(),emptyList(),null,levels,emptyList(),emptyList(),facadeEnvelopes=listOf(facade))
    }
    @Test fun `rectangular and sloped group structural areas conserve facade area`() {
        val c=candidate()
        val sloped=polygon(3.0 to 1.0,6.0 to 1.0,6.0 to 4.5,4.0 to 6.0,3.0 to 5.25)
        val g=OpeningGroupCandidate("group","front","f",sloped,emptyList(),listOf(sloped),null,emptyList(),listOf("elevation"),0.8,FactFidelity.SOURCE_DERIVED)
        val geometry=GeometryResolver.facades(c.copy(openingGroups=listOf(g)))
        assertEquals(36.0,geometry.surfaces.sumOf { it.area },1e-7)
        assertEquals(sloped.area,geometry.surfaces.filter { it.kind==ResolvedSurfaceKind.OPENING }.sumOf { it.area },1e-7)
        assertTrue(geometry.surfaces.all { it.vertices.all { p->p.z==10.0 } })
    }
    @Test fun `window door group retains single structural extent`() {
        val p=polygon(1.0 to 0.0,5.0 to 0.0,5.0 to 2.2,1.0 to 2.2)
        val door=polygon(1.0 to 0.0,2.0 to 0.0,2.0 to 2.2,1.0 to 2.2)
        val g=OpeningGroupCandidate("group","front","f",p,emptyList(),listOf(door,polygon(2.0 to 0.0,5.0 to 0.0,5.0 to 2.2,2.0 to 2.2)),door,listOf(Segment(Pt(2.0,0.0),Pt(2.0,2.2))),listOf("source"),0.8,FactFidelity.SOURCE_DERIVED)
        assertEquals(8.8,GeometryResolver.facades(candidate().copy(openingGroups=listOf(g))).surfaces.filter { it.kind==ResolvedSurfaceKind.OPENING }.sumOf { it.area },1e-8)
    }
    @Test fun `four facade coordinate directions are reversible`() {
        val b=Box(0.0,0.0,8.0,10.0)
        assertEquals(0.25,FacadeReconstruction.fraction(Pt(2.0,0.0),FacadeSide.SOUTH,b),1e-9)
        assertEquals(0.75,FacadeReconstruction.fraction(Pt(2.0,0.0),FacadeSide.NORTH,b),1e-9)
        assertEquals(0.8,FacadeReconstruction.fraction(Pt(0.0,2.0),FacadeSide.EAST,b),1e-9)
        assertEquals(0.2,FacadeReconstruction.fraction(Pt(0.0,2.0),FacadeSide.WEST,b),1e-9)
    }
    @Test fun `correct gable silhouette beats a wrong flat silhouette`() {
        val c=candidate(); val correct=ProjectionScorer.polygons(c)
        val flat=listOf(listOf(Pt3(0.0,0.0,0.0),Pt3(8.0,0.0,0.0),Pt3(8.0,3.0,0.0),Pt3(0.0,3.0,0.0)))
        val source=ProjectionScorer.raster(correct,64,0.0,0.0,0.0)
        val wrong=ProjectionScorer.raster(flat,64,0.0,0.0,0.0)
        assertEquals(1.0,ProjectionScorer.iou(source,source),0.0)
        assertTrue(ProjectionScorer.iou(source,wrong)<0.85)
        assertTrue(ProjectionScorer.residuals(source,wrong,64).second>0.1)
    }
    @Test fun `projection search has a global finite budget`() {
        val budget=ProjectionBudget(2)
        assertTrue(budget.take()); assertTrue(budget.take()); assertFalse(budget.take()); assertEquals(2,budget.used)
    }
}
