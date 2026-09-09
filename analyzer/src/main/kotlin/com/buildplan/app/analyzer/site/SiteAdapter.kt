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
    fun read(identity: SourceIdentity, page: FetchedResource, fetcher: ResourceFetcher): SourcePackage
}
