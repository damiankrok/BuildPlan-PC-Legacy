package com.buildplan.app.analyzer.source

import java.net.URI

/**
 * Everything the resolver learned on the way from the typed URL to the
 * project page, kept because the report must show it: an owner-supplied URL
 * with a stale suffix that lands on a catalogue is a finding, not noise.
 */
data class SourceResolution(
    val input: ProjectInput,
    val steps: List<ResolutionStep>,
    val identity: SourceIdentity?,
    /** The fetched project page when resolution succeeded, so it is not fetched twice. */
    val page: FetchedResource?,
    val failure: String?,
) {
    val isResolved: Boolean get() = identity != null && page != null
}

/** One thing the resolver did, in order. */
data class ResolutionStep(val kind: Kind, val detail: String) {
    enum class Kind { SAFETY_CHECK, FETCH, REDIRECT, CANONICAL_LINK, DERIVED_CANDIDATE, ACCEPTED, REJECTED }
}

/**
 * Turns a typed URL into a [SourceIdentity] and the fetched project page.
 *
 * The steps, in order:
 *
 * 1. [UrlSafety] on the typed URL.
 * 2. Fetch it (redirects are followed and recorded by the fetcher).
 * 3. If the page carries a `<link rel="canonical">` pointing at a project
 *    page of the same site, that is the identity — even when the typed URL
 *    was an alias.
 * 4. If the page is *not* a project page (a redirect to the catalogue, a
 *    404), try the site's own derivation of a candidate slug from the typed
 *    URL — for ARCHON, the slug up to and including its project key (`m` plus 13 hex digits, as the site mints them),
 *    which strips a trailing typo — and fetch that candidate once. It is
 *    accepted only if *its* canonical link confirms it. Nothing is assumed
 *    silently: the derived candidate and the confirmation are both steps in
 *    the resolution the report prints.
 *
 * No search, no crawling: at most two page fetches.
 */
class SourceResolver(
    private val fetcher: ResourceFetcher,
    private val recogniser: ProjectPageRecogniser = ArchonProjectPageRecogniser,
) {

    fun resolve(input: ProjectInput): SourceResolution {
        val steps = mutableListOf<ResolutionStep>()

        val verdict = UrlSafety.check(input.rawUrl)
        if (verdict is UrlSafety.Verdict.Rejected) {
            steps += ResolutionStep(ResolutionStep.Kind.REJECTED, "${verdict.reason}: ${verdict.detail}")
            return SourceResolution(input, steps, null, null, "URL rejected: ${verdict.reason}")
        }
        val allowed = verdict as UrlSafety.Verdict.Allowed
        steps += ResolutionStep(ResolutionStep.Kind.SAFETY_CHECK, "allowed on ${allowed.site.displayName}: ${allowed.uri}")

        val first = fetchAndRecord(allowed.uri.toString(), steps) ?: return SourceResolution(input, steps, null, null, "fetch failed")
        recogniser.identify(first)?.let { identity ->
            steps += ResolutionStep(ResolutionStep.Kind.ACCEPTED, "canonical project page ${identity.canonicalUrl} (key ${identity.projectKey})")
            return SourceResolution(input, steps, identity, first, null)
        }
        steps += ResolutionStep(
            ResolutionStep.Kind.REJECTED,
            "${first.finalUrl} is not a project page (status ${first.statusCode}${if (first.redirects.isNotEmpty()) ", after ${first.redirects.size} redirect(s)" else ""})",
        )

        val candidate = recogniser.deriveCandidateUrl(allowed.uri)
        if (candidate == null || candidate == allowed.uri.toString()) {
            return SourceResolution(input, steps, null, null, "not a project page and no candidate could be derived from the typed URL")
        }
        steps += ResolutionStep(ResolutionStep.Kind.DERIVED_CANDIDATE, "typed URL does not resolve; trying the site's slug stem $candidate")
        val second = fetchAndRecord(candidate, steps) ?: return SourceResolution(input, steps, null, null, "candidate fetch failed")
        val identity = recogniser.identify(second)
        if (identity == null) {
            steps += ResolutionStep(ResolutionStep.Kind.REJECTED, "candidate ${second.finalUrl} is not a project page either")
            return SourceResolution(input, steps, null, null, "neither the typed URL nor its derived stem is a project page")
        }
        steps += ResolutionStep(
            ResolutionStep.Kind.ACCEPTED,
            "derived candidate confirmed by its own canonical link: ${identity.canonicalUrl} (key ${identity.projectKey})",
        )
        return SourceResolution(input, steps, identity, second, null)
    }

    private fun fetchAndRecord(url: String, steps: MutableList<ResolutionStep>): FetchedResource? {
        return try {
            val resource = fetcher.fetch(url)
            resource.redirects.zipWithNext().forEach { (from, to) ->
                steps += ResolutionStep(ResolutionStep.Kind.REDIRECT, "$from -> $to")
            }
            if (resource.redirects.isNotEmpty()) {
                steps += ResolutionStep(ResolutionStep.Kind.REDIRECT, "${resource.redirects.last()} -> ${resource.finalUrl}")
            }
            steps += ResolutionStep(ResolutionStep.Kind.FETCH, "${resource.finalUrl} -> HTTP ${resource.statusCode}, ${resource.body.size} bytes, ${resource.contentType}")
            resource
        } catch (e: FetchException) {
            steps += ResolutionStep(ResolutionStep.Kind.REJECTED, "fetch of $url failed: ${e.message}")
            null
        }
    }
}

/**
 * How one site says "this is a project page, and this is its identity".
 * The ARCHON rule is below; the interface is what a second site would add.
 */
interface ProjectPageRecogniser {
    /** The identity when [page] is a project page of the site, else null. */
    fun identify(page: FetchedResource): SourceIdentity?

    /** A single derived candidate URL for a typed URL that did not resolve, or null. */
    fun deriveCandidateUrl(typed: URI): String?
}

/**
 * ARCHON project pages live at `/projekty-domow/<slug>-<`m` plus 13 hex key>[-<tab>-<n>]`
 * and declare themselves through `<link rel="canonical">` on the same path
 * shape. The key is the identity; the words before it are a human slug that
 * the site itself treats as decorative.
 */
object ArchonProjectPageRecogniser : ProjectPageRecogniser {

    private val projectPath = Regex("""^/projekty-domow/([a-z0-9-]+?)-(m[0-9a-f]{13})(?:-[a-z0-9-]+-\d+)?/?$""")
    private val canonicalLink = Regex("""<link\s+rel="canonical"\s+href="([^"]+)"""", RegexOption.IGNORE_CASE)

    override fun identify(page: FetchedResource): SourceIdentity? {
        if (!page.isSuccess) return null
        val html = page.bodyAsText()
        val canonical = canonicalLink.find(html)?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(canonical) }.getOrNull() ?: return null
        val host = uri.host?.let(UrlSafety::normaliseHost) ?: return null
        val site = SupportedSite.forHost(host)?.takeIf { host in it.hosts } ?: return null
        val match = projectPath.find(uri.path ?: return null) ?: return null
        val key = match.groupValues[2]
        // The canonical URL of the *project* is the page without any tab suffix.
        val canonicalProjectUrl = "https://${uri.host}/projekty-domow/${match.groupValues[1]}-$key"
        return SourceIdentity(site, key, canonicalProjectUrl)
    }

    private val stem = Regex("""^(/projekty-domow/[a-z0-9-]+?-m[0-9a-f]{13})""")

    override fun deriveCandidateUrl(typed: URI): String? {
        val path = typed.path ?: return null
        val match = stem.find(path) ?: return null
        return "https://${typed.host}${match.groupValues[1]}"
    }
}
