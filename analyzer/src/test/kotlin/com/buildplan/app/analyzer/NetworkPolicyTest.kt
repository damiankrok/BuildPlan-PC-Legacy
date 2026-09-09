package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.source.FetchException
import com.buildplan.app.analyzer.source.FetchPolicy
import com.buildplan.app.analyzer.source.HttpResourceFetcher
import com.buildplan.app.analyzer.source.HttpTransport
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AN024-NET — what the fetcher will and will not do on a network, tested
 * without one.
 *
 * These are the rules that stop an analyzer from becoming a request forwarder
 * for whoever controls the page it was pointed at. Each one is written as the
 * attack it prevents, because "the code calls UrlSafety" is not evidence that
 * it calls it on the *second* hop.
 */
class NetworkPolicyTest {

    /** A transport that answers from a script and records what it was asked to open. */
    private class ScriptedTransport(private val script: Map<String, HttpTransport.Response>) : HttpTransport {
        val opened = mutableListOf<String>()
        override fun get(uri: URI, policy: FetchPolicy): HttpTransport.Response {
            opened += uri.toString()
            return script[uri.toString()] ?: throw FetchException("nothing scripted for $uri")
        }
    }

    private fun redirect(to: String, status: Int = 301) =
        HttpTransport.Response(status, to, null, -1, null) { }

    private fun ok(body: ByteArray = ByteArray(0), declared: Long = -1, stream: InputStream? = null) =
        HttpTransport.Response(200, null, "text/html", declared, stream ?: ByteArrayInputStream(body)) { }

    /** A resolver that puts every host on a public address, so only the allowlist is under test. */
    private val publicHosts: (String) -> List<InetAddress> =
        { host -> listOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))) }

    private val page = "https://www.archon.pl/projekty-domow/x-m1234567890abc"

    @Test
    fun `a redirect off the allowlist is refused and never opened`() {
        val transport = ScriptedTransport(mapOf(page to redirect("https://attacker.example/steal")))
        val fetcher = HttpResourceFetcher(transport = transport, resolveAddress = publicHosts)
        try {
            fetcher.fetch(page)
            fail("a redirect must not inherit the trust of the host that issued it")
        } catch (e: FetchException) {
            assertTrue(e.message, e.message!!.contains("UNSUPPORTED_HOST"))
        }
        assertEquals("only the first hop may be opened", listOf(page), transport.opened)
    }

    @Test
    fun `a redirect to a private address is refused even on an allowlisted host`() {
        val internal = "https://www.archon.pl/internal"
        val transport = ScriptedTransport(mapOf(page to redirect(internal)))
        val fetcher = HttpResourceFetcher(
            transport = transport,
            // The second hop resolves to the loopback: a classic rebind, and the second check is
            // the only thing standing in front of it.
            resolveAddress = { host ->
                if (transport.opened.isEmpty()) publicHosts(host)
                else listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
            },
        )
        try {
            fetcher.fetch(page)
            fail("a hop that resolves to a private address must be refused")
        } catch (e: FetchException) {
            assertTrue(e.message, e.message!!.contains("PRIVATE_ADDRESS"))
        }
        assertEquals(listOf(page), transport.opened)
    }

    @Test
    fun `a redirect to plain http is refused`() {
        val transport = ScriptedTransport(mapOf(page to redirect("http://www.archon.pl/downgraded")))
        try {
            HttpResourceFetcher(transport = transport, resolveAddress = publicHosts).fetch(page)
            fail("https-only means every hop")
        } catch (e: FetchException) {
            assertTrue(e.message, e.message!!.contains("NOT_HTTPS"))
        }
    }

    @Test
    fun `a redirect that adds credentials or a port is refused`() {
        listOf(
            "https://user:pass@www.archon.pl/x" to "HAS_CREDENTIALS",
            "https://www.archon.pl:8443/x" to "NON_DEFAULT_PORT",
        ).forEach { (target, reason) ->
            val transport = ScriptedTransport(mapOf(page to redirect(target)))
            try {
                HttpResourceFetcher(transport = transport, resolveAddress = publicHosts).fetch(page)
                fail("expected $reason for $target")
            } catch (e: FetchException) {
                assertTrue("$target: ${e.message}", e.message!!.contains(reason))
            }
        }
    }

    @Test
    fun `a redirect loop ends in an error rather than a hang`() {
        val a = "https://www.archon.pl/a"
        val b = "https://www.archon.pl/b"
        val transport = ScriptedTransport(mapOf(a to redirect(b), b to redirect(a)))
        val fetcher = HttpResourceFetcher(FetchPolicy(maxRedirects = 3), transport = transport, resolveAddress = publicHosts)
        try {
            fetcher.fetch(a)
            fail("a loop must be bounded")
        } catch (e: FetchException) {
            assertTrue(e.message, e.message!!.contains("More than 3 redirects"))
        }
        assertEquals("the budget is hops, and it binds", 4, transport.opened.size)
    }

    @Test
    fun `redirects within the allowlist are followed and recorded`() {
        val target = "https://www.archon.pl/projekty-domow/x-m1234567890abc-final"
        val transport = ScriptedTransport(mapOf(page to redirect(target), target to ok("ok".toByteArray())))
        val resource = HttpResourceFetcher(transport = transport, resolveAddress = publicHosts).fetch(page)
        assertEquals(target, resource.finalUrl)
        assertEquals(listOf(page), resource.redirects)
        assertEquals("ok", resource.bodyAsText())
    }

    @Test
    fun `a body that declares more than the limit is refused before it is read`() {
        val transport = ScriptedTransport(mapOf(page to ok(declared = 50_000_000L)))
        try {
            HttpResourceFetcher(FetchPolicy(maxBodyBytes = 1024), transport = transport, resolveAddress = publicHosts).fetch(page)
            fail("an oversized declared length must be refused")
        } catch (e: FetchException) {
            assertTrue(e.message, e.message!!.contains("declares"))
        }
    }

    @Test
    fun `a body that lies about its length is cut off at the limit`() {
        // Declares nothing and then sends without end: the header is a claim, the socket is the
        // fact, and only the second check stops this one.
        val endless = object : InputStream() {
            override fun read(): Int = 0x41
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 0x41.toByte())
                return len
            }
        }
        val transport = ScriptedTransport(mapOf(page to ok(declared = -1, stream = endless)))
        try {
            HttpResourceFetcher(FetchPolicy(maxBodyBytes = 64 * 1024), transport = transport, resolveAddress = publicHosts).fetch(page)
            fail("a body that never ends must be cut off")
        } catch (e: FetchException) {
            assertTrue(e.message, e.message!!.contains("exceeds"))
        }
    }

    @Test
    fun `a redirect without a location is an error, not an empty page`() {
        val transport = ScriptedTransport(mapOf(page to HttpTransport.Response(302, null, null, -1, null) { }))
        try {
            HttpResourceFetcher(transport = transport, resolveAddress = publicHosts).fetch(page)
            fail("a redirect with nowhere to go is an error")
        } catch (e: FetchException) {
            assertTrue(e.message, e.message!!.contains("without Location"))
        }
    }

    @Test
    fun `the fetcher follows nothing but redirects`() {
        // One request in, one request out. There is no crawl in this class to bound, and this is
        // the test that says so: a page full of links produces exactly one open.
        val transport = ScriptedTransport(mapOf(page to ok("""<a href="/other">x</a><img src="/y.gif">""".toByteArray())))
        HttpResourceFetcher(transport = transport, resolveAddress = publicHosts).fetch(page)
        assertEquals(listOf(page), transport.opened)
    }
}
