package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.ArchonFixture
import com.buildplan.app.analyzer.cache.AnalysisCache
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.source.UrlSafety
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AN024-SERVICE — the app-facing boundary answers with the right family for
 * every way a run can end.
 *
 * The families exist because a caller does different things with them. A test
 * that only asserted "it did not throw" would let an unsupported host and a
 * changed page collapse into the same shrug, which is exactly the failure the
 * boundary was built to prevent.
 */

class ProjectAnalyzerServiceTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("analyzer-service", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun service(
        fetcher: com.buildplan.app.analyzer.source.ResourceFetcher = ServiceFixture.fetcher(),
    ): ProjectAnalyzerService = AnalyzerServices.create(
        platform = ServiceFixture.platform(fetcher),
        cacheRoot = root,
        dispatcher = Dispatchers.Default,
    )

    private suspend fun outcomeOf(
        service: ProjectAnalyzerService,
        url: String = ArchonFixture.PAGE_URL,
        policy: CachePolicy = CachePolicy.PREFER_CACHE,
    ): AnalysisOutcome = withTimeout(120_000) { service.analyzeOnce(AnalyzeProjectRequest(url, policy)) }

    @Test
    fun `a supported project page produces a report with identity, quantities and questions`() = runBlocking {
        val outcome = outcomeOf(service())
        val report = outcome.reportOrNull
        assertNotNull("expected a report, got $outcome", report)
        requireNotNull(report)

        assertEquals(ArchonFixture.KEY, report.identity.projectKey)
        assertEquals(ArchonSiteAdapter.ADAPTER_ID, report.identity.adapterId)
        assertEquals(ArchonFixture.PAGE_URL, report.identity.canonicalUrl)
        assertTrue("the report should carry quantities", report.quantityVerification.isNotEmpty())
        assertTrue("a real source leaves questions", report.questions.isNotEmpty())
        assertTrue("nothing came from the cache on a first run", !report.generation.servedFromCache)
    }

    @Test
    fun `an unsupported host is a typed answer and never a fetch`() = runBlocking {
        val fetcher = ServiceFixture.fetcher()
        val outcome = outcomeOf(service(fetcher), url = "https://www.example.com/projekty-domow/dom")
        assertTrue("expected UnsupportedSource, got $outcome", outcome is AnalysisOutcome.UnsupportedSource)
        assertTrue("nothing may be fetched from an unsupported host", fetcher.requested.isEmpty())
    }

    @Test
    fun `a malformed URL is refused before any socket`() = runBlocking {
        val fetcher = ServiceFixture.fetcher()
        val outcome = outcomeOf(service(fetcher), url = "ht!tp://%%%")
        assertTrue("expected UnsafeUrl, got $outcome", outcome is AnalysisOutcome.UnsafeUrl)
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test
    fun `a stale slug resolves through the site's own canonical link`() = runBlocking {
        // The typed URL carries a trailing typo and lands on the catalogue; the resolver derives
        // the slug stem and accepts it only because that page's own canonical link confirms it.
        val fetcher = ServiceFixture.fetcher()
        fetcher.serve("${ArchonFixture.PAGE_URL}to", ArchonFixture.catalogue)
        val outcome = outcomeOf(service(fetcher), url = "${ArchonFixture.PAGE_URL}to")
        val report = outcome.reportOrNull
        assertNotNull("expected the derived candidate to be accepted, got $outcome", report)
        assertEquals(ArchonFixture.PAGE_URL, report?.identity?.canonicalUrl)
        assertEquals("${ArchonFixture.PAGE_URL}to", report?.identity?.requestedUrl)
    }

    @Test
    fun `drawings that will not decode are an asset failure, not a house with no rooms`() = runBlocking {
        val outcome = outcomeOf(service(ServiceFixture.fetcher(plans = false)))
        assertTrue("expected AssetFailure, got $outcome", outcome is AnalysisOutcome.AssetFailure)
        val failure = outcome as AnalysisOutcome.AssetFailure
        assertTrue(failure.failures.isNotEmpty())
        // The page's own facts survive the drawings failing, and are worth showing.
        assertEquals(ArchonFixture.KEY, failure.report.identity.projectKey)
        assertTrue(failure.report.source!!.scalars.isNotEmpty())
    }

    @Test
    fun `a page the adapter can no longer read is reported as changed rather than as an empty house`() = runBlocking {
        val gutted = ArchonFixture.page
            .replace("product-data__item", "parameter-tile")
            .replace("table table-striped table-hover", "spec-grid")
        val outcome = outcomeOf(service(ServiceFixture.fetcher(page = gutted)))
        assertTrue("expected SourceChanged, got $outcome", outcome is AnalysisOutcome.SourceChanged)
        val changed = outcome as AnalysisOutcome.SourceChanged
        assertEquals(AdapterSeverity.BROKEN, changed.health.severity)
        assertTrue(
            "the storey tables are the signal that vanished",
            changed.health.absent.any { it.signal == AdapterSignal.ROOM_TABLES },
        )
        // What the renaming did *not* take away is still reported as extracted: the construction
        // block kept its markup, so the roof pitch and the knee wall came through and the scalars
        // signal is degraded rather than gone. Keeping whatever survived is the point — a page
        // that lost its tables may still have published its parameters.
        assertEquals(
            AdapterCheckState.DEGRADED,
            changed.health.check(AdapterSignal.SCALARS)?.state,
        )
        assertTrue(changed.report!!.source!!.scalars.isNotEmpty())
    }

    @Test
    fun `progress reports discrete phases in order and never repeats a finished one`() = runBlocking {
        val events = withTimeout(120_000) {
            service().analyze(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)).toList()
        }
        val phases = events.filterIsInstance<AnalysisEvent.Progress>().map { it.progress.phase }
        assertEquals(AnalysisPhase.VALIDATING_URL, phases.first())
        assertTrue("the run should reach geometry", phases.contains(AnalysisPhase.RECONSTRUCTING_GEOMETRY))
        assertTrue("phases never go backwards", phases.map { it.ordinal }.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("exactly one terminal event", events.count { it is AnalysisEvent.Completed } == 1)
        assertTrue("the terminal event is last", events.last() is AnalysisEvent.Completed)
    }

    @Test
    fun `cancelling the collection stops the run and leaves no cache entry`() = runBlocking {
        val service = service()
        // Taking only the first progress event cancels the flow, and with it the analysis.
        val first = withTimeout(120_000) {
            service.analyze(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)).first()
        }
        assertTrue(first is AnalysisEvent.Progress)

        val cache = AnalysisCache(root, AnalyzerServices.cacheTag(ArchonSiteAdapter()))
        assertEquals("a cancelled run publishes nothing", emptyList<String>(), cache.projects())
        assertTrue("and leaves no working area behind", cache.sizeBytes() == 0L)
    }

    @Test
    fun `two concurrent runs of the same project produce one consistent cache entry`() = runBlocking {
        val fetcher = ServiceFixture.fetcher()
        val service = service(fetcher)
        val outcomes = withTimeout(180_000) {
            listOf(
                async(Dispatchers.Default) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)) },
                async(Dispatchers.Default) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)) },
            ).awaitAll()
        }
        outcomes.forEach { assertNotNull("both runs should produce a report, got $it", it.reportOrNull) }

        val cache = AnalysisCache(root, AnalyzerServices.cacheTag(ArchonSiteAdapter()))
        assertEquals(listOf(ArchonFixture.KEY), cache.projects())

        // Serialised rather than raced: the second run answers from what the first one stored,
        // which is why exactly one of them says it went to the network.
        assertEquals(1, outcomes.count { it.reportOrNull?.generation?.servedFromCache == true })
    }

    @Test
    fun `a private address is refused even on an allowlisted host`() = runBlocking {
        val fetcher = ServiceFixture.fetcher()
        val platform = object : AnalyzerPlatform {
            override val codec = ServiceFixture.platform(fetcher).codec
            override fun networkFetcher() = fetcher
            override fun resolve(host: String): List<InetAddress> =
                listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
        }
        val service = AnalyzerServices.create(platform, root, dispatcher = Dispatchers.Default)
        val outcome = withTimeout(60_000) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)) }
        assertTrue("expected UnsafeUrl, got $outcome", outcome is AnalysisOutcome.UnsafeUrl)
        assertEquals(UrlSafety.Rejection.PRIVATE_ADDRESS, (outcome as AnalysisOutcome.UnsafeUrl).rejection)
        assertTrue("a DNS rebind must not be fetched", fetcher.requested.isEmpty())
    }
}
