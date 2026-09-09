package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.ArchonFixture
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AN024-DRIFT — a page that has moved on says so.
 *
 * This is the failure mode a scraper has that a person does not: a selector
 * that matches nothing returns nothing, and the layers above happily assemble
 * a tidy, nearly empty house out of the silence. Every case below is a
 * plausible edit to someone else's markup, and in each one the run must either
 * name what it lost or degrade honestly — never report a complete analysis of
 * a page it could no longer read.
 */
class AdapterDriftTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("analyzer-drift", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun outcome(
        page: String = ArchonFixture.page,
        costPage: String? = ArchonFixture.costPage,
        serveAssets: Boolean = true,
    ): AnalysisOutcome = runBlocking {
        val service = AnalyzerServices.create(
            platform = ServiceFixture.platform(ServiceFixture.fetcher(page = page, costPage = costPage, serveAssets = serveAssets)),
            cacheRoot = root,
            dispatcher = Dispatchers.Default,
        )
        withTimeout(120_000) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)) }
    }

    private fun health(outcome: AnalysisOutcome): AdapterHealth =
        (outcome as? AnalysisOutcome.SourceChanged)?.health
            ?: requireNotNull(outcome.reportOrNull) { "expected a report, got $outcome" }.adapterHealth

    @Test
    fun `a renamed value selector loses the parameters and is not a silent success`() {
        val page = ArchonFixture.page.replace("product-data__value", "product-data__figure")
        val result = outcome(page)
        val scalars = health(result).check(AdapterSignal.SCALARS)
        assertNotNull(scalars)
        assertTrue("expected the parameter blocks to be missed, got ${scalars?.state}", scalars?.state != AdapterCheckState.PRESENT)
        assertTrue("expected a degraded outcome, got $result", result !is AnalysisOutcome.Success)
    }

    @Test
    fun `a renamed table wrapper loses the storey tables and breaks the reading`() {
        val page = ArchonFixture.page.replace("table table-striped table-hover", "spec-grid")
        val result = outcome(page)
        assertTrue("expected SourceChanged, got $result", result is AnalysisOutcome.SourceChanged)
        assertEquals(AdapterCheckState.ABSENT, health(result).check(AdapterSignal.ROOM_TABLES)?.state)
        assertEquals(AdapterSeverity.BROKEN, health(result).severity)
    }

    @Test
    fun `a page with no plan drawing is a changed source, not a house without rooms`() {
        val page = ArchonFixture.page.lineSequence().filterNot { it.contains("rzut") }.joinToString("\n")
        val result = outcome(page)
        assertTrue("expected SourceChanged, got $result", result is AnalysisOutcome.SourceChanged)
        assertEquals(AdapterCheckState.ABSENT, health(result).check(AdapterSignal.PLAN_ASSETS)?.state)
        // What the page did still publish is kept: the parameters and the room tables are worth
        // showing beside the warning.
        val report = (result as AnalysisOutcome.SourceChanged).report
        assertNotNull(report)
        assertTrue(report!!.source!!.scalars.isNotEmpty())
        assertTrue(report.source!!.floors.isNotEmpty())
    }

    @Test
    fun `drawings the page names but the host will not serve are an asset failure`() {
        val result = outcome(serveAssets = false)
        assertTrue("expected AssetFailure, got $result", result is AnalysisOutcome.AssetFailure)
        val failures = (result as AnalysisOutcome.AssetFailure).failures
        assertTrue("every failure names the drawing it is about", failures.all { it.url.isNotBlank() && it.detail.isNotBlank() })
    }

    @Test
    fun `a missing cost page degrades the run and names what was not read`() {
        val page = ArchonFixture.page.replace("koszt-budowy-7#product-heading", "gallery#product-heading")
        val result = outcome(page)
        assertEquals(AdapterCheckState.ABSENT, health(result).check(AdapterSignal.COST_PAGE)?.state)
        assertTrue("expected a degraded outcome, got $result", result is AnalysisOutcome.Partial)
        val issues = (result as AnalysisOutcome.Partial).issues
        assertTrue(
            "the missing page is named in the issues: $issues",
            issues.any { it.subject == AdapterSignal.COST_PAGE.name },
        )
        // The house is still there; only the benchmark it would have been compared against is not.
        assertTrue(result.report.candidate != null)
    }

    @Test
    fun `a cost page that answers with an error is degraded rather than absent`() {
        val result = runBlocking {
            val fetcher = ServiceFixture.fetcher(costPage = null)
            fetcher.serve(ArchonFixture.COST_URL, "<html><body>gone</body></html>", status = 404)
            val service = AnalyzerServices.create(ServiceFixture.platform(fetcher), root, dispatcher = Dispatchers.Default)
            withTimeout(120_000) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)) }
        }
        assertEquals(AdapterCheckState.DEGRADED, health(result).check(AdapterSignal.COST_PAGE)?.state)
    }

    @Test
    fun `a malformed dataLayer costs the structured tags and nothing else`() {
        val page = ArchonFixture.page.replace(
            """<script>dataLayer.push({"pageType":"Produkt","projectName":"TEST_01_X","projectRoof":"dwuspadowy","projectGarage":"z-garazem-jednostanowiskowym"});</script>""",
            """<script>dataLayer.push({"pageType":"Produkt","projectRoof":</script>""",
        )
        val result = outcome(page)
        assertEquals(AdapterCheckState.ABSENT, health(result).check(AdapterSignal.SITE_TAGS)?.state)
        // The roof family also appears in the construction block, so losing the tags must not
        // lose the roof: a second reading of the same fact is why this is degraded, not broken.
        assertTrue("expected a report, got $result", result.reportOrNull != null)
        assertTrue(health(result).severity != AdapterSeverity.BROKEN)
        assertTrue(result.reportOrNull!!.candidate?.roof != null)
    }

    @Test
    fun `a healthy page reports every signal present`() {
        val result = outcome()
        val health = health(result)
        assertTrue(
            "expected every signal present, got ${health.checks.map { "${it.signal}=${it.state}" }}",
            health.checks.all { it.state == AdapterCheckState.PRESENT },
        )
        assertEquals(AdapterSeverity.HEALTHY, health.severity)
    }
}
