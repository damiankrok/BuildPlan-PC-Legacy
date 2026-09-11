package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import org.junit.Assert.*
import org.junit.Test

class RoofEvidenceTest {
    @Test fun `rooflight elevation coordinate intersects the physical sloping plane`() {
        val facet=RoofFacetCandidate("slope",listOf(Pt3(0.0,3.0,0.0),Pt3(8.0,3.0,0.0),Pt3(8.0,6.0,4.0),Pt3(0.0,6.0,4.0)),40.0,Segment(Pt(0.0,0.0),Pt(8.0,0.0)))
        assertEquals(Pt3(2.0,4.5,2.0),RoofEvidenceSolver.backProject(Pt(2.0,4.5),FacadeSide.NORTH,facet))
        assertNull(RoofEvidenceSolver.backProject(Pt(2.0,4.5),FacadeSide.WEST,facet))
    }
}
