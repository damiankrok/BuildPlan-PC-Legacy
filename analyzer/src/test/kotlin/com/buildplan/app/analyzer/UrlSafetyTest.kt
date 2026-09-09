package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.source.UrlSafety
import com.buildplan.app.analyzer.source.UrlSafety.Rejection
import com.buildplan.app.analyzer.source.UrlSafety.Verdict
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AN023-01 — the network boundary refuses everything but public pages of the supported site. */
class UrlSafetyTest {

    private val publicAddress = { _: String -> listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) }

    @Test
    fun `accepts a supported https project page`() {
        val verdict = UrlSafety.check("https://www.archon.pl/projekty-domow/projekt-x-m0123456789abc", publicAddress)
        assertTrue(verdict is Verdict.Allowed)
    }

    @Test
    fun `accepts the site's asset host`() {
        assertTrue(UrlSafety.check("https://assets.archon.pl/images/products/x/y.gif", publicAddress) is Verdict.Allowed)
    }

    @Test
    fun `rejects plain http`() {
        assertEquals(Rejection.NOT_HTTPS, rejection("http://www.archon.pl/projekty-domow/x"))
    }

    @Test
    fun `rejects lookalike and unsupported hosts`() {
        assertEquals(Rejection.UNSUPPORTED_HOST, rejection("https://www.archon.pl.evil.example/projekty-domow/x"))
        assertEquals(Rejection.UNSUPPORTED_HOST, rejection("https://archon.pl.example/"))
        assertEquals(Rejection.UNSUPPORTED_HOST, rejection("https://example.com/projekty-domow/x"))
        assertEquals(Rejection.UNSUPPORTED_HOST, rejection("https://xn--archn-hra.pl/x"))
    }

    @Test
    fun `rejects credentials, ports and malformed input`() {
        assertEquals(Rejection.HAS_CREDENTIALS, rejection("https://user:pw@www.archon.pl/x"))
        assertEquals(Rejection.NON_DEFAULT_PORT, rejection("https://www.archon.pl:8443/x"))
        assertEquals(Rejection.MALFORMED, rejection("https://www.archon.pl/x y"))
        assertEquals(Rejection.MALFORMED, rejection("not a url"))
    }

    @Test
    fun `rejects hosts that resolve to private, loopback or link-local addresses`() {
        val privateResolvers = listOf(
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(10, 0, 0, 5),
            byteArrayOf(192.toByte(), 168.toByte(), 1, 1),
            byteArrayOf(0xAC.toByte(), 16, 0, 1),
            byteArrayOf(169.toByte(), 254.toByte(), 1, 1),
            byteArrayOf(100, 64, 0, 1),
            byteArrayOf(0, 0, 0, 0),
        )
        privateResolvers.forEach { address ->
            val verdict = UrlSafety.check("https://www.archon.pl/projekty-domow/x") { listOf(InetAddress.getByAddress(address)) }
            assertTrue("expected rejection for ${address.toList()}", verdict is Verdict.Rejected)
            assertEquals(Rejection.PRIVATE_ADDRESS, (verdict as Verdict.Rejected).reason)
        }
    }

    @Test
    fun `rejects an unresolvable host`() {
        val verdict = UrlSafety.check("https://www.archon.pl/x") { throw java.net.UnknownHostException("nope") }
        assertEquals(Rejection.PRIVATE_ADDRESS, (verdict as Verdict.Rejected).reason)
    }

    private fun rejection(url: String): Rejection = (UrlSafety.check(url, publicAddress) as Verdict.Rejected).reason
}
