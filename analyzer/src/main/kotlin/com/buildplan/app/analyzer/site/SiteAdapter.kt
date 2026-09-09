package com.buildplan.app.analyzer.site

import com.buildplan.app.analyzer.source.FetchedResource
import com.buildplan.app.analyzer.source.ResourceFetcher
import com.buildplan.app.analyzer.source.SourceIdentity

/**
 * Reads one site's project page into a [SourcePackage].
 *
 * An adapter extracts and labels; it never measures, never draws and never
 * decides geometry. It may fetch a small, fixed set of related public pages
 * it finds linked on the project page (the cost calculation page), through
 * the same bounded fetcher, and nothing else — no crawling.
 */
interface SiteAdapter {

    /** Stable identifier of this adapter, recorded in every snapshot it produces. */
    val adapterId: String

    /**
     * The revision of *this adapter's selectors*, bumped whenever the markup it
     * keys on changes.
     *
     * It is separate from the analyzer version because the two drift for
     * different reasons: geometry improves on our side, page structure changes
     * on theirs. A cached run is only replayable when both still match, which
     * is why both are part of the cache key.
     */
    val adapterVersion: String

    fun read(identity: SourceIdentity, page: FetchedResource, fetcher: ResourceFetcher): SourcePackage
}
