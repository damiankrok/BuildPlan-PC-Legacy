package com.buildplan.app.analyzer.source

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * One fetched HTTP resource: bytes plus the metadata the layers above need
 * and nothing they do not. No product interpretation happens here.
 */
data class FetchedResource(
    val requestedUrl: String,
    val finalUrl: String,
    /** Every hop from the requested URL to the final one, in order, excluding the final URL itself. */
    val redirects: List<String>,
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
    val retrievedAtEpochMillis: Long,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    /** The body decoded as text, by the charset the content type names or UTF-8. */
    fun bodyAsText(): String = String(body, charsetOf(contentType))

    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        val name = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Why a fetch did not produce a resource. */
class FetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Limits every fetch obeys. Named rather than inlined so the report can state them.
 */
data class FetchPolicy(
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 20_000,
    val maxRedirects: Int = 5,
    val maxBodyBytes: Long = 8L * 1024 * 1024,
    val userAgent: String = "BuildPlanAnalyzer/0.1 (+prototype; deterministic; no crawling)",
)

/**
 * Network only. Takes a URL that [UrlSafety] has already allowed, follows a
 * bounded number of redirects — re-checking every hop against the same
 * safety rule — and returns bytes with metadata.
 *
 * An interface so that every layer above can be tested with a fake that
 * serves fixtures, and so the Lab can swap in a recording fetcher.
 */
interface ResourceFetcher {
    @Throws(FetchException::class)
    fun fetch(url: String): FetchedResource
}

/**
 * The production fetcher on `java.net.HttpURLConnection` — in the platform on
 * Android and on the JVM alike, so no HTTP client dependency is added for a
 * prototype that makes a handful of GETs.
 *
 * Redirects are followed by hand rather than by the platform, because the
 * platform's automatic redirect would happily follow a `Location` header to a
 * host the allowlist has never heard of.
 */
class HttpResourceFetcher(
    private val policy: FetchPolicy = FetchPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ResourceFetcher {

    override fun fetch(url: String): FetchedResource {
        val redirects = mutableListOf<String>()
        var current = url
        repeat(policy.maxRedirects + 1) {
            val verdict = UrlSafety.check(current)
            if (verdict !is UrlSafety.Verdict.Allowed) {
                throw FetchException("Refusing to fetch $current: ${(verdict as UrlSafety.Verdict.Rejected).reason} ${verdict.detail}")
            }
            val connection = open(verdict.uri)
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw FetchException("Redirect $status from $current without Location")
                    val next = verdict.uri.resolve(location).toString()
                    redirects += current
                    current = next
                    return@repeat
                }
                val body = readBounded(connection, status)
                return FetchedResource(
                    requestedUrl = url,
                    finalUrl = current,
                    redirects = redirects.toList(),
                    statusCode = status,
                    contentType = connection.contentType,
                    body = body,
                    retrievedAtEpochMillis = clock(),
                )
            } finally {
                connection.disconnect()
            }
        }
        throw FetchException("More than ${policy.maxRedirects} redirects starting at $url")
    }

    private fun open(uri: URI): HttpURLConnection {
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = policy.connectTimeoutMillis
        connection.readTimeout = policy.readTimeoutMillis
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", policy.userAgent)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*;q=0.9,*/*;q=0.5")
        connection.setRequestProperty("Accept-Language", "pl,en;q=0.5")
        connection.useCaches = false
        return connection
    }

    private fun readBounded(connection: HttpURLConnection, status: Int): ByteArray {
        val declared = connection.contentLengthLong
        if (declared > policy.maxBodyBytes) {
            throw FetchException("Body of ${connection.url} declares $declared bytes, over the ${policy.maxBodyBytes} limit")
        }
        val stream = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?: return ByteArray(0)
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > policy.maxBodyBytes) {
                    throw FetchException("Body of ${connection.url} exceeds the ${policy.maxBodyBytes} byte limit")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
