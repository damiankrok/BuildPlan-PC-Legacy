package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EvidenceMassTest {
    private fun rect(x: Double,z: Double,w: Double,d: Double)=Polygon(listOf(Pt(x,z),Pt(x+w,z),Pt(x+w,z+d),Pt(x,z+d)))
    private fun node(id:String)=EvidenceNode(id,EvidenceKind.OPENING,"source",EvidenceClass.ELEVATION,FactFidelity.SOURCE_DERIVED,0.8,"fixture",0.03)
    private fun mass(id:String,p:Polygon,base:Double=0.0,top:Double=3.0,use:MassUse=MassUse.ENCLOSED)=MassRegion(id,p,base,top,use,RoofFamily.FLAT,"f",listOf("source"),0.8)
    @Test fun `support contradiction same feature and occlusion survive graph`() {
        val graph=EvidenceGraph(listOf(node("a"),node("b"),node("view")),listOf(
            EvidenceEdge("a","b",EvidenceRelation.SAME_PHYSICAL_FEATURE,0.9,"multi-view correspondence"),
            EvidenceEdge("a","b",EvidenceRelation.SUPPORTS,0.8,"same width"),
            EvidenceEdge("a","b",EvidenceRelation.CONTRADICTS,0.8,"different head level"),
            EvidenceEdge("b","view",EvidenceRelation.OCCLUDED_IN_VIEW,1.0,"foreground mass")))
        assertEquals(setOf("a","b"),graph.sameFeature("a"))
        assertFalse(graph.visibleIn("a","view"))
        assertEquals(1,graph.related("a",EvidenceRelation.CONTRADICTS).size)
        assertEquals(1,graph.related("a",EvidenceRelation.SUPPORTS).size)
    }
    @Test fun `dangling evidence is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EvidenceGraph(listOf(node("a")),listOf(EvidenceEdge("a","missing",EvidenceRelation.SUPPORTS,1.0,"fixture"))) }
    }
    @Test fun `gable body and flat annex have shared facade topology`() {
        val masses=MassSolver.topology(listOf(mass("body",rect(0.0,0.0,8.0,10.0)).copy(roof=RoofFamily.GABLE),mass("annex",rect(8.0,4.0,3.0,6.0),top=2.6)))
        assertEquals(listOf("annex"),masses.first().adjacentIds)
        assertTrue(MassSolver.violations(masses).isEmpty())
    }
    @Test fun `overlapping canopy is allowed but intersecting enclosed volumes are rejected`() {
        val body=mass("body",rect(0.0,0.0,8.0,10.0))
        val slab=mass("slab",rect(4.0,8.0,6.0,3.0),2.8,3.0,MassUse.SLAB)
        val allowed=MassSolver.topology(listOf(body,slab))
        assertEquals(listOf("slab"),allowed.first().overlappingIds)
        assertTrue(MassSolver.violations(allowed).isEmpty())
        assertTrue(MassSolver.violations(MassSolver.topology(listOf(body,slab.copy(use=MassUse.ENCLOSED)))).any { it.contains("overlap") })
        assertTrue(MassSolver.violations(MassSolver.topology(listOf(body,slab.copy(base=3.0,top=5.0,use=MassUse.ENCLOSED)))).isEmpty())
    }
    @Test fun `open terrace carries no enclosure semantics`() {
        assertNotEquals(MassUse.ENCLOSED,mass("terrace",rect(0.0,0.0,3.0,4.0),0.0,0.2,MassUse.TERRACE).use)
    }
    @Test fun `polygon holes survive boundary conversion without area filling`() {
        val parts=PlanarTopology.difference(rect(0.0,0.0,10.0,10.0),rect(2.0,2.0,6.0,6.0))
        assertEquals(64.0,parts.sumOf { it.area },1e-8)
        assertTrue(parts.all { PlanarTopology.valid(it) })
        for(i in parts.indices) for(j in i+1 until parts.size) assertEquals(0.0,PlanarTopology.intersection(parts[i],parts[j]).sumOf { it.area },1e-8)
    }
    @Test fun `JTS types never enter contracts snapshots or app code`() {
        val dirs=listOf(File("src/main/kotlin/com/buildplan/app/analyzer/candidate"),File("src/main/kotlin/com/buildplan/app/analyzer/service"),File("src/main/kotlin/com/buildplan/app/analyzer/snapshot"),File("../app/src"))
        dirs.forEach { dir -> assertTrue(dir.isDirectory); dir.walkTopDown().filter { it.isFile && it.extension=="kt" }.forEach { assertFalse(it.path,it.readText().contains("org.locationtech.jts")) } }
    }
}
