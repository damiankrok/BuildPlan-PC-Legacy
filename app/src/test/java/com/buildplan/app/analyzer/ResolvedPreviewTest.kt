package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import com.buildplan.app.analyzer.preview.CandidateGeometry
import org.junit.Assert.*
import org.junit.Test

class ResolvedPreviewTest {
    private fun m(v:Double)=Measured.assumed(v,MeasureUnit.METER,"fixture")
    @Test fun `horizontal canopy and vertical facade surfaces map without a vertical-panel crash`() {
        val outline=Polygon(listOf(Pt(0.0,0.0),Pt(8.0,0.0),Pt(8.0,10.0),Pt(0.0,10.0)))
        val calibration=PlanCalibration(m(1.0),Pt(0.0,0.0),"fixture",emptyList(),null,FactFidelity.SOURCE_TRACED)
        val floor=FloorCandidate("f","Floor",0,calibration,outline,null,m(0.0),m(3.0),emptyList(),emptyList(),"fixture")
        val levels=LevelsCandidate(m(0.0),m(0.0),m(3.0),m(3.0),m(3.0),m(0.2),m(1.0),m(3.0),m(6.0),m(6.0),m(3.0),emptyList())
        val wall=ResolvedSurface("wall","wall","f",ResolvedSurfaceKind.WALL,listOf(Pt3(0.0,0.0,0.0),Pt3(8.0,0.0,0.0),Pt3(8.0,3.0,0.0),Pt3(0.0,3.0,0.0)),0.25,emptyList(),FactFidelity.SOURCE_DERIVED,exterior=true)
        val canopy=ResolvedSurface("canopy","canopy","f",ResolvedSurfaceKind.FEATURE,listOf(Pt3(0.0,3.0,0.0),Pt3(8.0,3.0,0.0),Pt3(8.0,3.0,1.0),Pt3(0.0,3.0,1.0)),0.0,emptyList(),FactFidelity.SOURCE_DERIVED,exterior=true)
        val c=ProjectAnalysisCandidate(listOf(floor),emptyList(),emptyList(),emptyList(),null,levels,emptyList(),emptyList(),resolvedGeometry=ResolvedBuildingGeometry(listOf(wall,canopy),"test",emptyList()))
        val preview=CandidateGeometry.of(c)
        assertNotNull(preview)
        preview!!.geometry.requireElementsIn(preview.building)
    }
}
