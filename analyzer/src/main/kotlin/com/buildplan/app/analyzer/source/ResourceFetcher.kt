package com.buildplan.app.analyzer.source

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

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
 * One HTTP exchange with no policy in it at all: open this URL, hand back the
 * response head and a stream.
 *
 * The split exists so the policy above it can be tested. Redirect
 * revalidation, the redirect budget and the body limits are the security
 * behaviour of this layer, and until they were separable from a socket the
 * only way to exercise them was to point the app at a real host and hope.
 */
interface HttpTransport {

    @Throws(FetchException::class)
    fun get(uri: URI, policy: FetchPolicy): Response

    /**
     * @property declaredLength what the response claims its body is, or -1.
     * @property body the body stream, or null when the response has none.
     * @property close releases the connection; always called by the fetcher.
     */
    class Response(
        val statusCode: Int,
        val location: String?,
        val contentType: String?,
        val declaredLength: Long,
        val body: InputStream?,
        val close: () -> Unit,
    )
}

/**
 * The transport on `java.net.HttpURLConnection` — in the platform on Android
 * and on the JVM alike, so no HTTP client dependency is added for a prototype
 * that makes a handful of GETs.
 *
 * Automatic redirects are switched off, because the platform's own would
 * happily follow a `Location` header to a host the allowlist has never heard
 * of. Following them is the layer above's job, and it re-checks each one.
 */
class UrlConnectionTransport : HttpTransport {

    override fun get(uri: URI, policy: FetchPolicy): HttpTransport.Response {
        val connection = URI(uri.toString()).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = policy.connectTimeoutMillis
        connection.readTimeout = policy.readTimeoutMillis
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", policy.userAgent)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*;q=0.9,*/*;q=0.5")
        connection.setRequestProperty("Accept-Language", "pl,en;q=0.5")
        connection.useCaches = false
        val status = connection.responseCode
        return HttpTransport.Response(
            statusCode = status,
            location = connection.getHeaderField("Location"),
            contentType = connection.contentType,
            declaredLength = connection.contentLengthLong,
            body = if (status >= 400) connection.errorStream else connection.inputStream,
            close = connection::disconnect,
        )
    }
}

/**
 * The production fetcher: everything the analyzer is allowed to do on a
 * network, and nothing else.
 *
 * Four rules, all enforced here rather than trusted to the platform:
 *
 * - **every hop is checked, not just the first.** `archon.pl` redirecting to
 *   somewhere else does not lend that somewhere else its trust, so each
 *   `Location` goes back through [UrlSafety] before it is opened;
 * - **the chain is bounded**, so a redirect loop is an error and not a hang;
 * - **the body is bounded twice** — once against what the response claims and
 *   once against what it actually sends, because a header is a claim and a
 *   socket is a fact;
 * - **nothing is followed except redirects.** No link on a page is fetched by
 *   this class; there is no crawl here to bound.
 *
 * [resolveAddress] is injectable for the same reason it is on [UrlSafety]: the
 * private-address rule has to be exercisable without DNS.
 */
class HttpResourceFetcher(
    private val policy: FetchPolicy = FetchPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val resolveAddress: ((String) -> List<java.net.InetAddress>)? = null,
) : ResourceFetcher {

    override fun fetch(url: String): FetchedResource {
        val redirects = mutableListOf<String>()
        var current = url
        repeat(policy.maxRedirects + 1) {
            val verdict = resolveAddress?.let { UrlSafety.check(current, it) } ?: UrlSafety.check(current)
            if (verdict !is UrlSafety.Verdict.Allowed) {
                throw FetchException("Refusing to fetch $current: ${(verdict as UrlSafety.Verdict.Rejected).reason} ${verdict.detail}")
            }
            val response = transport.get(verdict.uri, policy)
            try {
                if (response.statusCode in 300..399) {
                    val location = response.location
                        ?: throw FetchException("Redirect ${response.statusCode} from $current without Location")
                    redirects += current
                    current = verdict.uri.resolve(location).toString()
                    return@repeat
                }
                return FetchedResource(
                    requestedUrl = url,
                    finalUrl = current,
                    redirects = redirects.toList(),
                    statusCode = response.statusCode,
                    contentType = response.contentType,
                    body = readBounded(response, current),
                    retrievedAtEpochMillis = clock(),
                )
            } finally {
                response.close()
            }
        }
        throw FetchException("More than ${policy.maxRedirects} redirects starting at $url")
    }

    private fun readBounded(response: HttpTransport.Response, url: String): ByteArray {
        if (response.declaredLength > policy.maxBodyBytes) {
            throw FetchException("Body of $url declares ${response.declaredLength} bytes, over the ${policy.maxBodyBytes} limit")
        }
        val stream = response.body ?: return ByteArray(0)
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                // Checked against what arrives, not only against what was declared: a response
                // may promise a kilobyte and send until the device runs out of memory.
                if (total > policy.maxBodyBytes) {
                    throw FetchException("Body of $url exceeds the ${policy.maxBodyBytes} byte limit")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
