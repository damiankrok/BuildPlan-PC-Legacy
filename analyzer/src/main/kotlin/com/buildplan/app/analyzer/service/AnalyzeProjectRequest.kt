package com.buildplan.app.analyzer.service

/**
 * How one run may use the app-private analysis cache.
 *
 * The cache is keyed by the analyzer's own versions, so "cached" always means
 * "produced by this build from these bytes" and never "an older parser's idea
 * of this page".
 */
enum class CachePolicy {

    /** Replay cached bytes when the cache holds them under the current versions; fetch what it does not. */
    PREFER_CACHE,

    /** Fetch everything from the network again and refresh the cache. */
    REFRESH,

    /** Never open a socket. A resource the cache does not hold fails the run. */
    CACHE_ONLY,
}

/**
 * What the caller asks the analyzer to do: read this URL under this cache
 * policy.
 *
 * Deliberately two fields. The stage's brief allowed optional locale and
 * source hints, and neither has a consumer: user-facing text is Polish by the
 * project's own rule, numbers are formatted in `Locale.ROOT` so a snapshot is
 * byte-identical on every device, and the site is decided by the URL's host
 * rather than by the caller's opinion of it. An unread field on a stable API
 * is a promise nothing keeps, so it is not here.
 */
data class AnalyzeProjectRequest(
    val url: String,
    val cachePolicy: CachePolicy = CachePolicy.PREFER_CACHE,
) {
    init {
        require(url.isNotBlank()) { "AnalyzeProjectRequest url must not be blank" }
    }
}
