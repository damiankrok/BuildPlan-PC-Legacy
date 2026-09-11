package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.evaluation.EvidenceHarness
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Reprojects historical geometry against the same ORIGINAL source observations; no reference mesh. */
class SourceComparisonEvaluationTest {
    @Test fun `historical candidate comparison using frozen source objective`() {
        assumeTrue(System.getenv("BUILDPLAN_COMPARE_HISTORY")=="1")
        val dir=EvidenceHarness.directoryOrSkip()
        val rows=mutableListOf("project,iteration,metric,value")
        for(label in listOf("a","b")) {
            val current=SnapshotCodec.read(File(dir,"iter/e2e-$label/snapshot.json").readText())
            val final=requireNotNull(current.candidate)
            val paths=listOf("baseline" to "baseline-$label/snapshot.json","iteration1" to "iteration1/e2e-$label/snapshot.json",
                "iteration2" to "iteration2-verified/e2e-$label/snapshot.json","iteration3" to "iteration3/e2e-$label/snapshot.json",
                "iteration4" to "iteration4/e2e-$label/snapshot.json","iteration5" to "iteration5-final/e2e-$label/snapshot.json","final" to "iter/e2e-$label/snapshot.json")
            paths.forEach { (iteration,path) ->
                val file=File(dir,path); if(!file.isFile) return@forEach
                val historical=requireNotNull(SnapshotCodec.read(file.readText()).candidate)
                val c=historical.copy(visual=final.visual,reconstruction=historical.reconstruction?.copy(policy=final.reconstruction!!.policy))
                val resolved=c.copy(resolvedGeometry=GeometryResolver.resolve(c))
                val score=SourceScorer.score(resolved,requireNotNull(current.source),ProjectionBudget(final.reconstruction!!.policy.cameraProjections))
                (score.scores+score.residuals).forEach { (name,value) -> rows+="$label,$iteration,$name,$value" }
            }
        }
        EvidenceHarness.write(dir,"source-comparison.csv",rows.joinToString("\n"))
    }
}
