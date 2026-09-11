package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.preview.CandidateGeometry
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.render.filament.toRenderMeshes
import com.buildplan.app.render.filament.toRenderMesh
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Debug evidence gate: exercises the actual surface-to-GPU-mesh adapter before device upload. */
class EvidencePreviewMeshTest {
    @Test fun `evaluated source candidates produce nonempty meshes`() {
        val root=System.getenv("BUILDPLAN_ANALYZER_EVIDENCE_DIR")
        assumeTrue("external source evidence not configured",root!=null)
        for(label in listOf("a","b")) {
            val file=File(root!!,"iter/e2e-$label/snapshot.json")
            assumeTrue("source snapshot missing",file.isFile)
            val c=SnapshotCodec.read(file.readText()).candidate!!
            val geometry=CandidateGeometry.of(c)!!.geometry
            geometry.primitives.forEach { primitive -> val mesh=primitive.toRenderMesh(); assertTrue("${mesh.elementId}: ${mesh.vertexCount} vertices; $primitive",mesh.vertexCount>=3 && mesh.triangleCount>=1) }
        }
    }
}
