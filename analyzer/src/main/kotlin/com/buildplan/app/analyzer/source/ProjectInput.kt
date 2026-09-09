package com.buildplan.app.analyzer.source

/**
 * What the user handed the analyzer: a URL, as typed.
 *
 * Nothing is normalised here. The resolver keeps the original beside every
 * redirect and the final canonical page, because a URL that arrives with a
 * stale suffix and lands on a catalogue page is a fact the report must show,
 * not a detail to tidy away.
 */
data class ProjectInput(val rawUrl: String) {
    init {
        require(rawUrl.isNotBlank()) { "ProjectInput URL must not be blank" }
    }
}

/** The sites the analyzer knows how to read. One today; the type exists so the second is a value, not a rewrite. */
enum class SupportedSite(val displayName: String, val hosts: Set<String>, val assetHosts: Set<String>) {
    ARCHON(
        displayName = "ARCHON+",
        hosts = setOf("www.archon.pl", "archon.pl"),
        assetHosts = setOf("assets.archon.pl"),
    ),
    ;

    /** Every host this site may serve pages or assets from. */
    val allHosts: Set<String> get() = hosts + assetHosts

    companion object {
        fun forHost(host: String): SupportedSite? = entries.firstOrNull { host.lowercase() in it.allHosts }
    }
}

/**
 * The identity the resolver settles on: which site, which project key, and
 * the URL that identifies the project page canonically.
 *
 * @property projectKey the site's own stable key for the project (for ARCHON,
 *   the trailing hex token of the slug). Used to name analysis storage and to
 *   recognise the same project behind two URLs.
 */
data class SourceIdentity(
    val site: SupportedSite,
    val projectKey: String,
    val canonicalUrl: String,
) {
    init {
        require(projectKey.isNotBlank()) { "SourceIdentity projectKey must not be blank" }
    }
}
