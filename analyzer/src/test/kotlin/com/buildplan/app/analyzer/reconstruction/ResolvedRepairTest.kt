package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import com.buildplan.app.analyzer.quantity.QuantityTakeoffEngine
import com.buildplan.app.analyzer.service.CandidateGeometryQueries
import org.junit.Assert.*
import org.junit.Test

class ResolvedRepairTest {
    private fun m(v:Double)=Measured.assumed(v,MeasureUnit.METER,"synthetic source")
    private fun ring(w:Double=8.0)=Polygon(listOf(Pt(0.0,0.0),Pt(w,0.0),Pt(w,10.0),Pt(0.0,10.0)))
    private fun candidate():ProjectAnalysisCandidate {
        val f=FloorCandidate("floor","Floor",0,null,ring(),null,m(0.0),m(3.0),emptyList(),emptyList(),"source-plan")
        val l=LevelsCandidate(m(0.0),m(0.0),m(3.0),m(3.0),m(3.0),m(0.2),m(1.0),m(3.0),m(6.0),m(6.0),m(3.0),emptyList())
        val wall=FacadeEnvelopeCandidate("front",f.id,Segment(Pt(0.0,10.0),Pt(8.0,10.0)),m(0.0),m(0.3),listOf(Pt3(0.0,3.0,10.0),Pt3(8.0,3.0,10.0)),emptyList())
        return ProjectAnalysisCandidate(listOf(f),emptyList(),emptyList(),emptyList(),null,l,emptyList(),emptyList(),facadeEnvelopes=listOf(wall))
    }
    private fun group()=OpeningGroupCandidate("glass","front","floor",Polygon(listOf(Pt(1.0,0.0),Pt(5.0,0.0),Pt(5.0,2.2),Pt(1.0,2.2))),emptyList(),emptyList(),null,emptyList(),listOf("drawing"),0.8,FactFidelity.SOURCE_DERIVED)
    @Test fun `quantity deductions are the very polygons consumed by preview and stale geometry is invalidated`() {
        val initial=candidate()
        val before=initial.copy(resolvedGeometry=GeometryResolver.resolve(initial))
        val edited=before.copy(openingGroups=listOf(group()))
        val geometry=CandidateGeometryQueries.resolvedGeometry(edited)!!
        val q=QuantityTakeoffEngine(edited,null).compute()
        assertNotEquals(before.resolvedGeometry!!.lineage,geometry.lineage)
        assertEquals(8.8,geometry.surfaces.filter { it.kind==ResolvedSurfaceKind.OPENING && it.roomId==null }.sumOf { it.area },1e-7)
        assertEquals(24.0,q.facadeGross.requireValue(),1e-7)
        assertEquals(15.2,q.facadeNet.requireValue(),1e-7)
        geometry.surfaces.filter { it.kind==ResolvedSurfaceKind.WALL }.forEach { s ->
            assertEquals(s.area,q.surfaces.single { it.id==s.id }.netArea.requireValue(),1e-8)
            assertEquals(geometry.lineage,q.surfaces.single { it.id==s.id }.basis)
        }
        assertEquals(geometry.lineage,GeometryResolver.lineage(edited.copy(resolvedGeometry=geometry)))
    }
    @Test fun `source supported improvement converges deterministically and a pretty conflicting plan is rejected`() {
        val c=candidate(); val policy=ReconstructionPolicy(repairCycles=4)
        val constraints=ReconstructionConstraints(mapOf("floor" to ring()),1,null,emptyMap())
        fun evaluate(id:String,trial:ProjectAnalysisCandidate):ScoredHypothesis {
            val hard=constraints.violations(trial)
            val score=if(trial.floors.first().footprint!!.area>80.1) 1.0 else if(trial.openingGroups.isNotEmpty()) 0.8 else 0.5
            return ScoredHypothesis(id,trial,SourceScore(mapOf("planTopologyScore" to 1.0,"elevationOpeningScore" to score),emptyMap(),hard,score,emptyList()))
        }
        fun run()=BoundedRepair.solve(evaluate("initial",c),policy,{ trial -> listOf(
            RepairProposal("expand source opening",trial.copy(openingGroups=listOf(group()))),
            RepairProposal("pretty render contradicting plan",trial.copy(floors=listOf(trial.floors.first().copy(footprint=ring(10.0))))))
        },::evaluate)
        val result=run()
        assertEquals("expand source opening",result.selected.id)
        assertEquals(1,result.trace.count { it.accepted })
        assertTrue(result.trace.any { !it.accepted && it.reason.contains("fixed plan geometry") })
        assertTrue(result.evaluated<=4)
        assertEquals(result,run())
    }
    @Test fun `no better valid proposal makes zero repairs without hiding diagnostics`() {
        val c=candidate(); val score=SourceScore(mapOf("planTopologyScore" to 1.0),emptyMap(),emptyList(),1.0,listOf("unresolved source detail"))
        val result=BoundedRepair.solve(ScoredHypothesis("initial",c,score),ReconstructionPolicy(),{ emptyList() },{ _,_->error("must not evaluate") })
        assertEquals(0,result.evaluated)
        assertEquals(0,result.trace.count { it.accepted })
        assertEquals(score.diagnostics,result.selected.score.diagnostics)
    }
}
