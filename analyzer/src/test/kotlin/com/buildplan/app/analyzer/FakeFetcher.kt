package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.source.FetchException
import com.buildplan.app.analyzer.source.FetchedResource
import com.buildplan.app.analyzer.source.ResourceFetcher

/**
 * A fetcher that serves canned responses and records what was asked, so the
 * layers above the network can be tested without it.
 */
class FakeFetcher : ResourceFetcher {

    private val responses = LinkedHashMap<String, () -> FetchedResource>()
    val requested = mutableListOf<String>()

    fun serve(url: String, body: String, status: Int = 200, contentType: String = "text/html; charset=utf-8", finalUrl: String = url, redirects: List<String> = emptyList()) {
        responses[url] = {
            FetchedResource(
                requestedUrl = url,
                finalUrl = finalUrl,
                redirects = redirects,
                statusCode = status,
                contentType = contentType,
                body = body.toByteArray(Charsets.UTF_8),
                retrievedAtEpochMillis = 1_757_000_000_000L,
            )
        }
    }

    fun serveBytes(url: String, body: ByteArray, contentType: String) {
        responses[url] = {
            FetchedResource(url, url, emptyList(), 200, contentType, body, 1_757_000_000_000L)
        }
    }

    fun fail(url: String, message: String) {
        responses[url] = { throw FetchException(message) }
    }

    override fun fetch(url: String): FetchedResource {
        requested += url
        val response = responses[url] ?: throw FetchException("FakeFetcher has nothing for $url")
        return response()
    }
}
