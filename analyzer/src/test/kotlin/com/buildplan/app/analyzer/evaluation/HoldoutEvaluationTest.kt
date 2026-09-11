package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.pipeline.ProjectAnalyzer
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.analyzer.source.ProjectInput
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in once the development code and all parameters have a recorded freeze. */
class HoldoutEvaluationTest {
    @Test fun `C after freeze only`() {
        assumeTrue(System.getenv("BUILDPLAN_ANALYZER_HOLDOUT_C")=="1")
        val dir=EvidenceHarness.directoryOrSkip()
        val freeze=File(dir,"freeze.json")
        assertTrue("holdout requires recorded freeze",freeze.isFile && freeze.readText().contains("productionDigest"))
        val url="https://www.archon.pl/projekty-domow/projekt-dom-w-plumeriach-e-ver-2-md0e26c471a7c5"
        val run=ProjectAnalyzer(EvidenceHarness.fetcher(dir),EvidenceHarness.codec,EvidenceHarness.storage(dir,"run-c")).analyze(ProjectInput(url))
        val json=SnapshotCodec.write(run.snapshot(now=0L))
        EvidenceHarness.write(dir,"holdout-c/snapshot.json",json)
        EvidenceHarness.write(dir,"holdout-c/report.txt",buildString {
            appendLine("freeze=${freeze.readText()}"); appendLine("resolution=${run.resolution}"); appendLine("timings=${run.timingsMillis}")
            appendLine("source categories=${run.source?.assets}"); appendLine("QA=${run.candidate?.selfVerification}")
            appendLine("rooms=${run.candidate?.rooms?.size}/${run.source?.floors?.sumOf { it.rooms.size }}")
            appendLine("stairs=${run.candidate?.resolvedGeometry?.stairTopology}")
            appendLine("quantities=${run.quantities}"); appendLine("log=${run.log}")
            appendLine("Architecture responses consumed=0. This execution success is not a reconstruction PASS.")
        })
        assertNotNull(run.candidate)
        assertEquals(json,SnapshotCodec.write(SnapshotCodec.read(json)))
    }
}
