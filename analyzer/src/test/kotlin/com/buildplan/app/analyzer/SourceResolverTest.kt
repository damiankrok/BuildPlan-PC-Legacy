package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.source.ArchonProjectPageRecogniser
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.ResolutionStep
import com.buildplan.app.analyzer.source.SourceResolver
import com.buildplan.app.analyzer.source.SupportedSite
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023-02 — resolution is honest: the typed URL, every redirect, the
 * canonical link and any derived candidate are recorded, and a candidate is
 * accepted only when its own page confirms it.
 *
 * Network safety is not re-tested here (the fake fetcher does no DNS); the
 * resolver's safety step is checked by [UrlSafetyTest] through the same call.
 */
class SourceResolverTest {

    private val fetcher = FakeFetcher()
    private val resolver = SourceResolver(fetcher)

    @Test
    fun `a canonical project page resolves to its identity in one fetch`() {
        fetcher.serve(ArchonFixture.PAGE_URL, ArchonFixture.page)
        val resolution = resolver.resolve(ProjectInput(ArchonFixture.PAGE_URL))

        assertTrue(resolution.isResolved)
        assertEquals(SupportedSite.ARCHON, resolution.identity?.site)
        assertEquals(ArchonFixture.KEY, resolution.identity?.projectKey)
        assertEquals(ArchonFixture.PAGE_URL, resolution.identity?.canonicalUrl)
        assertEquals(listOf(ArchonFixture.PAGE_URL), fetcher.requested)
    }

    @Test
    fun `a tab URL of the project resolves to the bare project page identity`() {
        val tabUrl = ArchonFixture.PAGE_URL + "-koszt-budowy-7"
        fetcher.serve(tabUrl, ArchonFixture.costPage)
        val resolution = resolver.resolve(ProjectInput(tabUrl))
        assertTrue(resolution.isResolved)
        assertEquals(ArchonFixture.PAGE_URL, resolution.identity?.canonicalUrl)
    }

    @Test
    fun `a stale suffix that redirects to the catalogue is resolved through the slug stem and recorded`() {
        val stale = ArchonFixture.PAGE_URL + "to"
        fetcher.serve(
            stale,
            ArchonFixture.catalogue,
            finalUrl = "https://www.archon.pl/projekty-domow",
            redirects = listOf(stale),
        )
        fetcher.serve(ArchonFixture.PAGE_URL, ArchonFixture.page)

        val resolution = resolver.resolve(ProjectInput(stale))

        assertTrue(resolution.isResolved)
        assertEquals(ArchonFixture.PAGE_URL, resolution.identity?.canonicalUrl)
        assertEquals(listOf(stale, ArchonFixture.PAGE_URL), fetcher.requested)
        val kinds = resolution.steps.map { it.kind }
        assertTrue(ResolutionStep.Kind.REDIRECT in kinds)
        assertTrue(ResolutionStep.Kind.DERIVED_CANDIDATE in kinds)
        assertTrue(ResolutionStep.Kind.ACCEPTED in kinds)
        assertTrue(resolution.steps.any { it.kind == ResolutionStep.Kind.REJECTED && it.detail.contains("not a project page") })
        assertEquals(stale, resolution.input.rawUrl)
    }

    @Test
    fun `a derived candidate that is not a project page is not accepted`() {
        val stale = ArchonFixture.PAGE_URL + "to"
        fetcher.serve(stale, ArchonFixture.catalogue, finalUrl = "https://www.archon.pl/projekty-domow", redirects = listOf(stale))
        fetcher.serve(ArchonFixture.PAGE_URL, ArchonFixture.catalogue)
        val resolution = resolver.resolve(ProjectInput(stale))
        assertFalse(resolution.isResolved)
        assertNull(resolution.identity)
        assertTrue(resolution.failure!!.contains("neither"))
    }

    @Test
    fun `a URL without a project key derives no candidate and fails`() {
        fetcher.serve("https://www.archon.pl/projekty-domow", ArchonFixture.catalogue)
        val resolution = resolver.resolve(ProjectInput("https://www.archon.pl/projekty-domow"))
        assertFalse(resolution.isResolved)
        assertEquals(1, fetcher.requested.size)
    }

    @Test
    fun `an unsupported host is rejected before any fetch`() {
        val resolution = resolver.resolve(ProjectInput("https://example.com/projekty-domow/projekt-x-m0123456789abc"))
        assertFalse(resolution.isResolved)
        assertTrue(fetcher.requested.isEmpty())
        assertEquals(ResolutionStep.Kind.REJECTED, resolution.steps.single().kind)
    }

    @Test
    fun `the recogniser derives the stem only from a path carrying a project key`() {
        assertEquals(
            ArchonFixture.PAGE_URL,
            ArchonProjectPageRecogniser.deriveCandidateUrl(URI(ArchonFixture.PAGE_URL + "to")),
        )
        assertNull(ArchonProjectPageRecogniser.deriveCandidateUrl(URI("https://www.archon.pl/projekty-domow/projekt-bez-klucza")))
    }
}
