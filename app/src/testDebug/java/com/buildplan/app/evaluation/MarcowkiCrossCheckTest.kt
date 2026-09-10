package com.buildplan.app.evaluation

import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * EVAL-025-ORACLE — the analyzer's Marcówki candidate held against the
 * hand-built reference model, measurement by measurement.
 *
 * **What this is and is not.** The reference is a *development oracle*: what a
 * careful person got from the same public source. It is not truth above that
 * source. So the comparator refuses to score any measurement the reference
 * itself assumed, and a difference it does report is a finding to classify —
 * analyzer defect when the source backs the reference, reference debt when the
 * source backs the analyzer — never a target to tune towards.
 *
 * Opt-in and offline. It reads the candidate the analyzer's own evaluation run
 * wrote into the evidence directory (`iter/e2e-a/snapshot.json`), so no plan
 * raster is decoded here — this module's unit tests compile against
 * `android.jar` and have no ImageIO.
 */
class MarcowkiCrossCheckTest {

    private fun evidenceDir(): File {
        val path = System.getenv("BUILDPLAN_ANALYZER_EVIDENCE_DIR")
        Assume.assumeTrue("BUILDPLAN_ANALYZER_EVIDENCE_DIR not set: the reference cross-check is skipped", !path.isNullOrBlank())
        val dir = File(path!!)
        Assume.assumeTrue("$path is not a directory", dir.isDirectory)
        return dir
    }

    @Test
    fun `the analyzer candidate is compared against the hand-built reference`() {
        val dir = evidenceDir()
        val snapshotFile = File(dir, "iter/e2e-a/snapshot.json")
        Assume.assumeTrue("run the analyzer evaluation first: ${snapshotFile.absolutePath}", snapshotFile.isFile)

        val snapshot = SnapshotCodec.read(snapshotFile.readText())
        val candidate = requireNotNull(snapshot.candidate) { "the evaluation snapshot carries no candidate" }
        val candidateDigest = CandidateDigest.of(candidate, "analyzer candidate")
        val referenceDigest = ReferenceDigest.marcowki()

        val table = CandidateReferenceComparator.render(candidateDigest, referenceDigest)
        File(dir, "iter/oracle").mkdirs()
        File(dir, "iter/oracle/geometry-diff.txt").writeText(table)

        // The comparison must actually have compared something, and must have refused to score
        // the reference's own assumptions.
        val diffs = CandidateReferenceComparator.compare(candidateDigest, referenceDigest)
        assertTrue("the digest must carry measurements", diffs.size >= 20)
        assertTrue(
            "the source-stated anchors must be scored",
            diffs.filter { it.key in setOf("plan.extentX", "plan.extentZ", "height.total", "roof.pitchDeg") }
                .all { it.verdict != DiffVerdict.NOT_SCORED },
        )
    }

    @Test
    fun `the reference oracle is unreachable from production and from the analyzer module`() {
        // The guard the whole comparison rests on: an oracle the analyzer could read is a
        // benchmark the analyzer would be tuned to, and the tuning would be invisible.
        val appMain = File("src/main")
        val offenders = appMain.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("com.buildplan.app.evaluation") }
            .map { it.path }
            .toList()
        assertTrue("production code must not name the evaluation package: $offenders", offenders.isEmpty())

        val analyzerMain = File("../analyzer/src/main")
        if (analyzerMain.isDirectory) {
            val leaks = analyzerMain.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val text = file.readText().lowercase()
                    text.contains("marcowk") || text.contains("marcówk") || text.contains("com.buildplan.app.evaluation")
                }
                .map { it.path }
                .toList()
            assertTrue("the analyzer core must not name the reference model: $leaks", leaks.isEmpty())
        }

        // And the comparator itself lives only in test sources.
        assertTrue("the comparator is a test source", File("src/testDebug/java/com/buildplan/app/evaluation/ModelDigest.kt").isFile)
        assertTrue("and has no debug-source twin", !File("src/debug/java/com/buildplan/app/evaluation").exists())
    }
}
