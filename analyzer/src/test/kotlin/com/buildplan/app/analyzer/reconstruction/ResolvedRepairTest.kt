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
    @Test fun `corroborated stair slab opening survives but a wall collision rejects it`() {
        val c=candidate(); val upper=c.floors.first().copy(id="upper",order=1,floorElevation=m(3.0))
        val stair=StairCandidate("stairs","floor",Box(1.0,1.0,3.0,5.0),Measured.assumed(12.0,MeasureUnit.COUNT,"fixture"),"unknown","floor","upper",listOf(Box(1.0,1.0,3.0,5.0)),null,setOf(StairEvidence.TREAD_LINES,StairEvidence.CROSS_FLOOR_ALIGNMENT),FactFidelity.SOURCE_TRACED,"fixture",emptyList())
        val clear=c.copy(floors=c.floors+upper,stairs=listOf(stair))
        val topology=StairTopologySolver.resolve(clear).single()
        assertTrue(topology.accepted)
        assertEquals(8.0,topology.slabOpenings.sumOf { it.area },1e-8)
        assertTrue(topology.diagnostics.any { it.contains("direction unresolved") })
        val wall=WallCandidate("collision","floor",Segment(Pt(0.0,3.0),Pt(4.0,3.0)),m(4.0),m(0.2),WallClass.PARTITION,m(0.0),m(3.0),emptyList(),emptyList(),false,emptyList(),FactFidelity.SOURCE_TRACED)
        val rejected=StairTopologySolver.resolve(clear.copy(walls=listOf(wall))).single()
        assertFalse(rejected.accepted)
        assertTrue(rejected.slabOpenings.isEmpty())
        assertTrue(rejected.diagnostics.any { it.contains("collision") })
    }
    @Test fun `source slab alternatives preserve overlap and reject invented enclosed plan area`() {
        val c=candidate().copy(reconstruction=ReconstructionState(EvidenceGraph(emptyList(),emptyList()),emptyList()))
        val feature=FacadeFeatureCandidate("balcony",FacadeFeatureKind.BALCONY,"front","floor",Polygon(listOf(Pt(1.0,2.8),Pt(5.0,2.8),Pt(5.0,3.0),Pt(1.0,3.0))),0.7,listOf("drawing"),0.7,FactFidelity.SOURCE_DERIVED)
        val alternatives=MassSolver.featureAlternatives(c,listOf(feature))
        assertEquals(setOf("SLAB","OPEN_COVERED","FACADE_PROJECTION","ENCLOSED"),alternatives.map { it.first }.toSet())
        val constraints=ReconstructionConstraints(mapOf("floor" to ring()),1,null,emptyMap())
        assertTrue(constraints.violations(alternatives.first { it.first=="ENCLOSED" }.second).any { it.contains("outside structural plan") })
        assertTrue(constraints.violations(alternatives.first { it.first=="SLAB" }.second).isEmpty())
        assertNotEquals(GeometryResolver.resolve(alternatives.first { it.first=="ENCLOSED" }.second).surfaces,
            GeometryResolver.resolve(alternatives.first { it.first=="SLAB" }.second).surfaces)
    }
    @Test fun `unclipped confirmed opening area is known while its sill position remains assumed`() {
        val wall=WallCandidate("wall","floor",Segment(Pt(0.0,10.0),Pt(8.0,10.0)),m(8.0),m(0.2),WallClass.EXTERIOR,m(0.0),m(3.0),emptyList(),emptyList(),true,listOf("opening"),FactFidelity.SOURCE_TRACED)
        val width=Measured(1.0,MeasureUnit.METER,FactFidelity.SOURCE_EXACT,Provenance("source","dimension","fixture"))
        val height=Measured(1.5,MeasureUnit.METER,FactFidelity.USER_CONFIRMED,Provenance(null,"decision","fixture"))
        val o=OpeningCandidate("opening","wall","floor",OpeningType.WINDOW,m(1.0),width,height,Measured.missing(MeasureUnit.METER,"no sill"),emptyList(),true,FactFidelity.SOURCE_TRACED)
        val c=candidate().let { it.copy(walls=listOf(wall),openings=listOf(o),facadeEnvelopes=listOf(it.facadeEnvelopes.first().copy(openingIds=listOf(o.id)))) }
        val pane=GeometryResolver.facades(c).surfaces.single { it.kind==ResolvedSurfaceKind.OPENING }
        assertEquals(1.5,pane.area,1e-8)
        assertEquals(FactFidelity.DISPLAY_ASSUMPTION,pane.fidelity)
        assertNotEquals(FactFidelity.DISPLAY_ASSUMPTION,pane.areaFidelity)
    }
}
