package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.ArchonFixture
import com.buildplan.app.analyzer.FakeFetcher
import com.buildplan.app.analyzer.cache.AnalysisCache
import com.buildplan.app.analyzer.cache.AnalysisCachePolicy
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AN024-CACHE — the app-private cache is replayable, versioned, bounded and
 * never half-written.
 *
 * Each of these is a specific way a cache produces a confident wrong answer
 * rather than a miss, which is why they are tested rather than trusted:
 * replaying an old parser's output, replaying a half-finished download, or
 * growing without limit until the device runs out of room.
 */
class AnalysisCacheTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("analyzer-cache", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private val tag get() = AnalyzerServices.cacheTag(ArchonSiteAdapter())

    private fun service(fetcher: FakeFetcher, policy: AnalysisCachePolicy = AnalysisCachePolicy()) =
        AnalyzerServices.create(
            platform = ServiceFixture.platform(fetcher),
            cacheRoot = root,
            cachePolicy = policy,
            dispatcher = Dispatchers.Default,
        )

    private fun run(service: ProjectAnalyzerService, policy: CachePolicy = CachePolicy.PREFER_CACHE) = runBlocking {
        withTimeout(120_000) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL, policy)) }
    }

    @Test
    fun `a second run of the same project answers from the cache without a fetch`() {
        val fetcher = ServiceFixture.fetcher()
        val service = service(fetcher)

        val first = run(service)
        assertNotNull(first.reportOrNull)
        val fetchedFirst = fetcher.requested.size
        assertTrue("the first run has to go to the network", fetchedFirst > 0)

        fetcher.requested.clear()
        val second = run(service)
        assertTrue("the second run served the cache", second.reportOrNull!!.generation.servedFromCache)
        assertEquals("and opened nothing", emptyList<String>(), fetcher.requested)

        // The replayed report is the same analysis, not a summary of one.
        assertEquals(
            first.reportOrNull!!.deterministicJson(),
            second.reportOrNull!!.deterministicJson(),
        )
    }

    @Test
    fun `a run whose drawings never arrived is not stored for the next one to replay`() {
        val fetcher = ServiceFixture.fetcher(plans = false)
        val service = service(fetcher)
        val first = run(service)
        assertTrue("expected AssetFailure, got $first", first is AnalysisOutcome.AssetFailure)
        assertEquals("nothing may be cached from it", emptyList<String>(), AnalysisCache(root, tag).projects())

        // Which means tapping again actually tries again, rather than replaying the failure.
        fetcher.requested.clear()
        val second = run(service)
        assertTrue("the retry went back to the source", fetcher.requested.isNotEmpty())
        assertTrue(second is AnalysisOutcome.AssetFailure)
    }

    @Test
    fun `a page the adapter can no longer read is not stored either`() {
        val gutted = ArchonFixture.page.replace("table table-striped table-hover", "spec-grid")
        val outcome = run(service(ServiceFixture.fetcher(page = gutted)))
        assertTrue("expected SourceChanged, got $outcome", outcome is AnalysisOutcome.SourceChanged)
        assertEquals(emptyList<String>(), AnalysisCache(root, tag).projects())
    }

    @Test
    fun `a refresh run ignores the cache and goes back to the source`() {
        val fetcher = ServiceFixture.fetcher()
        val service = service(fetcher)
        run(service)
        fetcher.requested.clear()

        val refreshed = run(service, CachePolicy.REFRESH)
        assertTrue("a refresh must not be served from the cache", !refreshed.reportOrNull!!.generation.servedFromCache)
        assertTrue("and must fetch again", fetcher.requested.isNotEmpty())
    }

    @Test
    fun `a cache-only run never opens a socket and fails honestly on a miss`() {
        val fetcher = ServiceFixture.fetcher()
        val outcome = run(service(fetcher), CachePolicy.CACHE_ONLY)
        assertTrue("expected FetchFailed, got $outcome", outcome is AnalysisOutcome.FetchFailed)
        assertEquals(emptyList<String>(), fetcher.requested)
    }

    @Test
    fun `an entry written by another version of the analyzer is deleted rather than replayed`() {
        val fetcher = ServiceFixture.fetcher()
        run(service(fetcher))
        val current = File(root, tag)
        assertTrue(current.isDirectory)

        // A build whose analyzer version differs cannot see the old directory at all. This is
        // the whole defence against a changed algorithm quietly reporting last week's numbers.
        val other = AnalysisCache(root, "s1-a0.0.1-old-adapter0")
        assertEquals(emptyList<String>(), other.projects())
        assertTrue("the stale version's directory is gone", !current.isDirectory)
        assertNull(other.lookupByUrl(ArchonFixture.PAGE_URL))
    }

    @Test
    fun `a snapshot the current schema cannot read is dropped, not half-understood`() {
        val fetcher = ServiceFixture.fetcher()
        run(service(fetcher))
        val projectDir = File(File(root, tag), "projects/${ArchonFixture.KEY}")
        assertTrue(projectDir.isDirectory)
        File(projectDir, "snapshot.json").writeText("""{"schemaVersion": 1, "analyzerVersion": "0.1.0-old"}""")

        val cache = AnalysisCache(root, tag)
        assertNull(cache.lookupByUrl(ArchonFixture.PAGE_URL))
        assertTrue("the unreadable entry is removed", !projectDir.isDirectory)
    }

    @Test
    fun `a cancelled run leaves no working area for the next run to trust`() {
        val fetcher = ServiceFixture.fetcher()
        val cache = AnalysisCache(root, tag)
        val session = cache.beginSession(ArchonFixture.PAGE_URL)
        session.storage().store("plan_ground-abc.gif", ByteArray(4096))
        assertTrue("the session is on disk while it runs", cache.sizeBytes() > 0)

        session.discard()
        assertEquals(0L, cache.sizeBytes())

        // And even a session that was abandoned without discard is swept when the cache reopens.
        val abandoned = cache.beginSession(ArchonFixture.PAGE_URL)
        abandoned.storage().store("plan_ground-def.gif", ByteArray(4096))
        assertTrue(cache.sizeBytes() > 0)
        assertEquals(0L, AnalysisCache(root, tag).sizeBytes())
        assertEquals(emptyList<String>(), AnalysisCache(root, tag).projects())
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test
    fun `a partly written file is never visible under its final name`() {
        val cache = AnalysisCache(root, tag)
        val session = cache.beginSession(ArchonFixture.PAGE_URL)
        val path = session.storage().store("nested/plan.gif", byteArrayOf(1, 2, 3))
        assertEquals("assets/nested/plan.gif", path)
        assertTrue(
            "no .part sibling survives a completed write",
            File(root, tag).walkTopDown().none { it.name.endsWith(".part") },
        )
        session.discard()
    }

    @Test
    fun `storage paths handed back are relative, so no device path can travel in a report`() {
        val cache = AnalysisCache(root, tag)
        val session = cache.beginSession(ArchonFixture.PAGE_URL)
        val path = session.storage().store("plan_ground-abc.gif", ByteArray(8))
        assertTrue("got $path", !path.contains(root.absolutePath))
        assertTrue("got $path", !File(path).isAbsolute)
        session.discard()
    }

    @Test
    fun `the cache drops whole projects, oldest first, to stay inside its budget`() {
        // One project per key, each larger than the budget allows two of.
        val cache = AnalysisCache(root, tag, AnalysisCachePolicy(maxBytes = 20_000, maxProjects = 8))
        listOf("aaa", "bbb", "ccc").forEach { key ->
            val session = cache.beginSession("https://www.archon.pl/projekty-domow/x-$key")
            session.storage().store("plan.gif", ByteArray(12_000))
            session.commit(key, snapshotFor(key))
            // Distinct mtimes, so "least recently used" is a fact and not a tie.
            Thread.sleep(15)
        }
        val kept = cache.projects()
        assertTrue("the budget must actually bind, kept $kept", kept.size < 3)
        assertTrue("the newest survives, kept $kept", kept.contains("ccc"))
        assertTrue("the oldest is gone, kept $kept", !kept.contains("aaa"))
        // Whole projects only: a project missing half its drawings would replay as a project
        // whose drawings failed, which is worse than a miss.
        kept.forEach { key ->
            val dir = File(File(root, tag), "projects/$key")
            assertTrue("$key kept its snapshot", File(dir, "snapshot.json").isFile)
            assertTrue("$key kept its drawings", File(dir, "assets/plan.gif").isFile)
        }
    }

    @Test
    fun `the project count limit binds independently of size`() {
        val cache = AnalysisCache(root, tag, AnalysisCachePolicy(maxBytes = Long.MAX_VALUE, maxProjects = 2))
        listOf("aaa", "bbb", "ccc").forEach { key ->
            val session = cache.beginSession("https://www.archon.pl/projekty-domow/x-$key")
            session.storage().store("plan.gif", ByteArray(16))
            session.commit(key, snapshotFor(key))
            Thread.sleep(15)
        }
        assertEquals(2, cache.projects().size)
        assertTrue(cache.projects().contains("ccc"))
    }

    @Test
    fun `the typed URL and the canonical URL find the same entry`() {
        val fetcher = ServiceFixture.fetcher()
        fetcher.serve("${ArchonFixture.PAGE_URL}to", ArchonFixture.catalogue)
        val service = service(fetcher)
        runBlocking {
            withTimeout(120_000) { service.analyzeOnce(AnalyzeProjectRequest("${ArchonFixture.PAGE_URL}to")) }
        }
        val cache = AnalysisCache(root, tag)
        assertNotNull("the typed URL finds it", cache.lookupByUrl("${ArchonFixture.PAGE_URL}to"))
        assertNotNull("and so does the project key", cache.lookupByKey(ArchonFixture.KEY))
    }

    @Test
    fun `a trailing slash is the same URL and a different query is not`() {
        assertEquals(
            AnalysisCache.normalizeUrl("https://WWW.Archon.pl/projekty-domow/x-m1/"),
            AnalysisCache.normalizeUrl("https://www.archon.pl/projekty-domow/x-m1"),
        )
        assertTrue(
            AnalysisCache.normalizeUrl("https://www.archon.pl/p?a=1") !=
                AnalysisCache.normalizeUrl("https://www.archon.pl/p?a=2"),
        )
    }

    private fun snapshotFor(key: String) = com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot(
        schemaVersion = com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot.SCHEMA_VERSION,
        analyzerVersion = com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot.ANALYZER_VERSION,
        adapterId = ArchonSiteAdapter.ADAPTER_ID,
        adapterVersion = ArchonSiteAdapter.ADAPTER_VERSION,
        createdAtEpochMillis = 0L,
        inputUrl = "https://www.archon.pl/projekty-domow/x-$key",
        resolutionSteps = emptyList(),
        source = null,
        candidate = null,
        quantities = null,
        validations = emptyList(),
        gaps = null,
        questions = emptyList(),
        log = emptyList(),
        timingsMillis = emptyMap(),
    )
}
