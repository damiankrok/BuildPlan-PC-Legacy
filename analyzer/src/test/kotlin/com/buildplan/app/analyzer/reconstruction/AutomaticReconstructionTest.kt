package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.asset.*
import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.roof.RoofSolver
import com.buildplan.app.analyzer.site.*
import com.buildplan.app.analyzer.source.*
import com.buildplan.app.analyzer.visual.ElevationReader
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AutomaticReconstructionTest {
    private fun m(value: Double) = Measured.assumed(value, MeasureUnit.METER, "synthetic fixture")
    private fun rect(x0: Double, z0: Double, x1: Double, z1: Double) = Polygon(listOf(Pt(x0,z0),Pt(x1,z0),Pt(x1,z1),Pt(x0,z1)))
    private fun candidate(): ProjectAnalysisCandidate {
        val outline = rect(0.0,0.0,8.0,10.0)
        val roof = RoofSolver.solve(RoofSolver.Input(outline, PublishedRoofFamily.GABLE,
            Measured.assumed(40.0,MeasureUnit.DEGREE,"synthetic"),m(3.0),null,listOf(rect(8.0,4.0,11.0,10.0)),m(2.6)))!!
        val levels = LevelsCandidate(m(0.0),m(0.0),m(3.0),m(2.7),m(2.7),m(0.3),m(1.0),m(3.0),roof.ridgeElevation,roof.ridgeElevation,m(2.6),emptyList())
        val floor = FloorCandidate("f", "Floor",0,null,outline,null,m(0.0),m(2.7),emptyList(),emptyList(),"https://example.test/plan")
        return ProjectAnalysisCandidate(listOf(floor),emptyList(),emptyList(),emptyList(),roof,levels,emptyList(),emptyList())
    }
    private fun source() = SourcePackage(SourceIdentity(SupportedSite.ARCHON,"mfixture000000", "https://www.archon.pl/test-mfixture000000"),"Synthetic",0L,emptyList(),emptyList(),emptyList(),AssetManifest(emptyList()),emptyList(),emptyMap())
    private fun withOpening(c: ProjectAnalysisCandidate): ProjectAnalysisCandidate {
        val wall = WallCandidate("w","f",Segment(Pt(0.0,10.0),Pt(2.0,10.0)),m(2.0),m(0.3),WallClass.EXTERIOR,m(0.0),m(2.7),listOf("r"),emptyList(),true,listOf("o"),FactFidelity.SOURCE_TRACED)
        val opening = OpeningCandidate("o","w","f",OpeningType.WINDOW,m(2.0),m(1.6),Measured.missing(MeasureUnit.METER,"no label"),Measured.missing(MeasureUnit.METER,"no label"),listOf("r"),true,FactFidelity.TRACE_UNCERTAIN)
        return c.copy(walls=listOf(wall),openings=listOf(opening))
    }
    @Test fun `gable envelopes reach roof rather than room clear height`() {
        val c = AutomaticReconstruction.reconstruct(candidate(), source())
        assertEquals(2,c.masses.size)
        assertEquals(RoofFamily.FLAT,c.masses[1].roofKind)
        assertEquals(listOf("main"),c.masses[1].adjacentMassIds)
        assertTrue(c.facadeEnvelopes.flatMap { it.topProfile }.maxOf { it.y } > 6.0)
        assertTrue(c.facadeEnvelopes.all { f -> f.topProfile.all { it.y > f.baseLevel.requireValue() } })
        assertTrue(c.selfVerification!!.unresolvedDiagnostics.any { it.contains("Perspective") })
    }
    @Test fun `gap after fragment is placed on facade without clamping`() {
        val c = withOpening(candidate())
        val gap = AutomaticReconstruction.openingSegment(c,c.openings.single())!!
        assertEquals(Pt(2.0,10.0),gap.a)
        assertEquals(Pt(3.6,10.0),gap.b)
        assertEquals(1,AutomaticReconstruction.envelopes(c).count { "o" in it.openingIds })
        assertFalse(AutomaticReconstruction.onEdge(Segment(Pt(9.0,10.0),Pt(11.0,10.0)),Segment(Pt(0.0,10.0),Pt(8.0,10.0)),0.2))
    }
    @Test fun `terrace trace does not enlarge structural mass`() {
        val c = withOpening(candidate())
        val terrace = c.walls.single().copy(id="terrace",centreline=Segment(Pt(-3.0,13.0),Pt(12.0,13.0)),wallClass=WallClass.LIGHT_EXTERIOR)
        assertEquals(AutomaticReconstruction.reconstruct(c,source()).masses,AutomaticReconstruction.reconstruct(c.copy(walls=c.walls+terrace),source()).masses)
    }
    @Test fun `two dark facade boundaries cannot override a structural roof level`() {
        val c=candidate()
        val assets=listOf(AssetRole.ELEVATION_FRONT,AssetRole.ELEVATION_REAR).mapIndexed { index,role ->
            VisualAssetEvidence("https://example.test/facade-$index",role,VisualViewpoint.ORTHOGRAPHIC_ELEVATION,600,400,listOf(
                VisualObservation(VisualObservationKind.BUILDING_SILHOUETTE,NormalizedBox(0.0,0.0,1.0,1.0),0.9,"fixture",FactFidelity.SOURCE_DERIVED),
                VisualObservation(VisualObservationKind.DARK_MASS,NormalizedBox(0.6,0.55,0.95,1.0),0.85,"dark parapet",FactFidelity.SOURCE_DERIVED)
            ),0.8,FactFidelity.SOURCE_DERIVED)
        }
        val reconstructed=AutomaticReconstruction.reconstruct(c.copy(visual=c.visual.copy(assets=assets)),source())
        assertEquals(c.roof!!.secondaryMasses,reconstructed.roof!!.secondaryMasses)
        assertEquals("plan-levels",reconstructed.selfVerification!!.selectedHypothesis)
        assertTrue(reconstructed.selfVerification!!.hypotheses.any { it.hardViolations.any { message->message.contains("parapet") } })
    }
    @Test fun `elevation ratio resolves height only with plan interval agreement`() {
        val c = withOpening(candidate())
        val observations = listOf(
            VisualObservation(VisualObservationKind.BUILDING_SILHOUETTE,NormalizedBox(0.0,0.0,1.0,1.0),0.9,"fixture",FactFidelity.SOURCE_DERIVED),
            VisualObservation(VisualObservationKind.OPENING_RECTANGLE,NormalizedBox(0.25,0.58,0.45,0.85),0.9,"fixture",FactFidelity.SOURCE_DERIVED))
        val asset = VisualAssetEvidence("https://example.test/front",AssetRole.ELEVATION_FRONT,VisualViewpoint.ORTHOGRAPHIC_ELEVATION,600,400,observations,0.8,FactFidelity.SOURCE_DERIVED)
        val visual = c.visual.copy(assets=listOf(asset),facades=listOf(FacadeAssignment(asset.role,asset.assetUrl,listOf(FacadeSide.SOUTH),0.8,"fixture")))
        val fused = OpeningFusion.fuse(c.copy(visual=visual))
        assertNotNull(fused.openings.single().height.value)
        assertEquals(c.openings.single().width,fused.openings.single().width)
        assertEquals(FactFidelity.TRACE_UNCERTAIN,fused.openings.single().height.fidelity)
        val conflict = c.copy(visual=visual,openings=listOf(c.openings.single().copy(distanceAlongWall=m(5.5))))
        assertNull(OpeningFusion.fuse(conflict).openings.single().height.value)
        val labelled = c.copy(visual=visual,openings=listOf(c.openings.single().copy(height=m(1.2))))
        assertEquals(1.2,OpeningFusion.fuse(labelled).openings.single().height.requireValue(),1e-8)
        assertNull(OpeningFusion.fuse(c).openings.single().height.value)
    }
    @Test fun `bright glazing with mullions is grouped inside fabric not sky`() {
        val width=600; val height=400
        val pixels=IntArray(width*height) { 0xff7ab8df.toInt() }
        fun paint(x0:Int,y0:Int,x1:Int,y1:Int,color:Int) { for(y in y0 until y1) for(x in x0 until x1) pixels[y*width+x]=color }
        paint(0,350,600,400,0xff408030.toInt())
        paint(90,100,510,350,0xffeeeeee.toInt())
        for(x in listOf(160,330)) {
            paint(x-3,197,x+83,303,0xff303030.toInt());paint(x,200,x+80,300,0xff639dcc.toInt())
            paint(x+39,200,x+41,300,0xff303030.toInt())
        }
        val evidence=ElevationReader.read("https://example.test/elevation",AssetRole.ELEVATION_FRONT,RasterImage(width,height,pixels))
        val glass=evidence.of(VisualObservationKind.OPENING_RECTANGLE)
        assertEquals(2,glass.size)
        assertTrue(glass.all { it.bounds.width > 0.12 && it.bounds.height > 0.2 })
    }
    @Test fun `production reconstruction has no benchmark seed or oracle import`() {
        val root=File("src/main/kotlin")
        assertTrue(root.isDirectory)
        val forbidden=listOf("marcow", "marcó", "m2fa281446a8ca", "MarcowkiVisualModel", "com.buildplan.app.reference")
        root.walkTopDown().filter { it.isFile && it.extension=="kt" }.forEach { file ->
            val text=file.readText().lowercase()
            forbidden.forEach { token -> assertFalse("${file.path}: $token",text.contains(token.lowercase())) }
        }
    }
}
