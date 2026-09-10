package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.pipeline.ProjectAnalyzer
import com.buildplan.app.analyzer.service.ProjectAnalysisReport
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.verification.PriorityTier
import com.buildplan.app.analyzer.verification.RootQuestionKind
import com.buildplan.app.analyzer.verification.RootQuestions
import com.buildplan.app.analyzer.verification.VerificationSession
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVAL-025-BURDEN — how many decisions a real house actually costs a person.
 *
 * The claim STAGE-025 is built on is arithmetic, not taste: a person should
 * answer orders of magnitude fewer questions than there are quantity rows. A
 * claim like that is worth nothing measured on a fixture, because a fixture is
 * shaped by whoever needed the number. So it is measured here, on both
 * evaluation projects, from the owner's URL through the real pipeline, and
 * written out in words beside the rest of the evidence.
 *
 * The assertions are deliberately weak — a floor, not a target. Tightening
 * them to the number this pair of houses happens to produce would be tuning
 * the priority engine to the benchmark, and the reduction is a property of the
 * dependency graph, not a figure to protect.
 *
 * Opt-in like every evaluation test: skipped without an evidence directory.
 */
class VerificationBurdenProbeTest {

    @Test
    fun `project A verification burden`() = run(EvaluationProjects.A)

    @Test
    fun `project B verification burden`() = run(EvaluationProjects.B)

    private fun run(project: EvaluationProjects.Project) {
        val dir = EvidenceHarness.directoryOrSkip()
        val label = project.label.lowercase()
        val analyzer = ProjectAnalyzer(EvidenceHarness.fetcher(dir), EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-$label"))
        val run = analyzer.analyze(ProjectInput(project.ownerUrl))
        val report = ProjectAnalysisReport.of(
            snapshot = run.snapshot(now = 0L),
            requestedUrl = project.ownerUrl,
            servedFromCache = false,
            generatedAtEpochMillis = 0L,
        )
        requireNotNull(report) { "the pipeline must reach a report for ${project.label}" }

        val questions = RootQuestions.of(report)
        val burden = RootQuestions.burden(report, questions)
        val session = VerificationSession.start(report)

        EvidenceHarness.write(dir, "iter/burden-$label/burden.txt", render(project, report, questions, burden, session))

        // A floor, not a target: the point is that roots are fewer than rows, and that
        // the engine did not reach that by declaring things verified.
        assertTrue(
            "roots must be fewer than rows: ${burden.rootDecisions} vs ${burden.rawQuantitiesNeedingConfirmation}",
            burden.rootDecisions < burden.rawQuantitiesNeedingConfirmation / 2,
        )
        assertEquals(burden.rootDecisions, burden.required + burden.highImpact + burden.recommended + burden.optional)
        // Nothing is verified before a person decides anything.
        val verified = session.verified
        assertEquals(0, verified.summary.quantitiesUserConfirmed)
        assertEquals(0, verified.summary.quantitiesDerivedFromUser)
        assertEquals(burden.rawQuantitiesNeedingConfirmation, verified.summary.quantitiesStillUnsafe)
        // A root that resolves no takeoff row is not automatically a useless question — but it
        // must be one of the kinds that deliberately sit outside the takeoff, and the list is
        // closed so a new kind cannot join them by accident:
        //
        //  - appearance and roof cover are presentation candidates and create no quantity at all;
        //  - a stair creates no quantity either. The takeoff has no stair line, and
        //    `project:floorsAndStairs` is a sum of room polygons that does not care whether the
        //    evenly spaced lines are treads. Answering "those are shelves" changes what the model
        //    *looks* like and nothing that would be priced — which is exactly why the workspace
        //    must not print a fan-out for it;
        //  - an unplaced published row has no geometry yet to carry a quantity.
        //
        // Anything else with a fan-out of zero is a question whose answer changes nothing.
        val allowedWithoutRows = setOf(
            RootQuestionKind.APPEARANCE_FEATURE,
            RootQuestionKind.ROOF_COVER,
            RootQuestionKind.STAIR_DETAIL,
            RootQuestionKind.STAIR_INTERPRETATION,
            RootQuestionKind.ROOM_UNPLACED,
        )
        val idle = questions.filter { it.affectedCount == 0 && it.kind !in allowedWithoutRows }
        assertTrue("a root decision that changes nothing: ${idle.map { "${it.id} ${it.kind}" }}", idle.isEmpty())
        // And the ones that legitimately resolve nothing must not be claiming a count.
        assertTrue(
            "a question with no fan-out must not be tiered above RECOMMENDED",
            questions.none { it.affectedCount == 0 && (it.tier == PriorityTier.REQUIRED || it.tier == PriorityTier.HIGH_IMPACT) },
        )
    }

    private fun render(
        project: EvaluationProjects.Project,
        report: ProjectAnalysisReport,
        questions: List<com.buildplan.app.analyzer.verification.RootQuestion>,
        burden: com.buildplan.app.analyzer.verification.VerificationBurden,
        session: VerificationSession,
    ): String = buildString {
        appendLine("== verification burden — ${project.label}")
        appendLine(burden.render())
        appendLine("reductionFactor = ${fmt(burden.reductionFactor)}x")
        appendLine()
        appendLine("== summary before any decision")
        val s = session.verified.summary
        appendLine("  userConfirmed = ${s.quantitiesUserConfirmed}")
        appendLine("  derivedFromUserConfirmed = ${s.quantitiesDerivedFromUser}")
        appendLine("  stillUnconfirmed = ${s.quantitiesStillUnsafe} of ${s.quantitiesTotal}")
        appendLine("  readiness = ${session.verified.readiness}")
        appendLine()
        appendLine("== root decisions by tier")
        PriorityTier.entries.forEach { tier ->
            val inTier = questions.filter { it.tier == tier }
            appendLine("  $tier — ${inTier.size}")
            inTier.forEach { q ->
                appendLine("    ${q.id} score=${fmt(q.priorityScore)} resolves=${q.affectedCount} kind=${q.kind} input=${q.input}")
                appendLine("      ${q.subjectLabel}: ${q.text}")
            }
        }
        appendLine()
        appendLine("== largest fan-outs")
        burden.resolvedPerRoot.entries.sortedByDescending { it.value }.take(10).forEach { (id, n) ->
            val q = questions.firstOrNull { it.id == id }
            appendLine("  $n rows ← $id (${q?.subjectLabel})")
        }
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}
