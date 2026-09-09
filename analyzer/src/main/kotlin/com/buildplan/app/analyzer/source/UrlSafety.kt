package com.buildplan.app.analyzer.source

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * The network boundary's first line: which URLs the analyzer will touch at
 * all, decided before any socket is opened.
 *
 * The rules are deliberately narrow. This prototype fetches public product
 * pages of one supported site and their public assets, and nothing else:
 *
 * - `https` only — a plan fetched over plaintext is a plan someone else may
 *   have edited in transit;
 * - host must be on the supported site's allowlist, compared as an exact
 *   label after IDN normalisation, so `archon.pl.evil.example` and
 *   `xn--` lookalikes are rejected;
 * - no credentials in the URL, no non-default ports, no fragments;
 * - the host must not resolve to a loopback, link-local, private or
 *   multicast address — the classic SSRF hole — and every redirect target is
 *   re-checked by the same rule.
 */
object UrlSafety {

    /** Why a URL was refused. A reason rather than a boolean, so the Lab can say it. */
    enum class Rejection {
        MALFORMED,
        NOT_HTTPS,
        HAS_CREDENTIALS,
        NON_DEFAULT_PORT,
        UNSUPPORTED_HOST,
        PRIVATE_ADDRESS,
    }

    /** The outcome of checking one URL. */
    sealed interface Verdict {
        data class Allowed(val uri: URI, val site: SupportedSite) : Verdict
        data class Rejected(val reason: Rejection, val detail: String) : Verdict
    }

    /**
     * Checks [raw] against the rules above. [resolveAddress] is injectable so
     * that the private-address rule can be tested without DNS; production
     * passes [InetAddress.getAllByName].
     */
    fun check(
        raw: String,
        resolveAddress: (String) -> List<InetAddress> = ::systemResolve,
    ): Verdict {
        val uri = try {
            URI(raw.trim())
        } catch (e: URISyntaxException) {
            return Verdict.Rejected(Rejection.MALFORMED, e.message ?: raw)
        }
        if (uri.scheme?.lowercase() != "https") return Verdict.Rejected(Rejection.NOT_HTTPS, uri.scheme ?: "no scheme")
        if (uri.rawUserInfo != null) return Verdict.Rejected(Rejection.HAS_CREDENTIALS, "user info present")
        if (uri.port != -1 && uri.port != 443) return Verdict.Rejected(Rejection.NON_DEFAULT_PORT, uri.port.toString())

        val host = uri.host?.let { normaliseHost(it) }
            ?: return Verdict.Rejected(Rejection.MALFORMED, "no host")
        val site = SupportedSite.forHost(host)
            ?: return Verdict.Rejected(Rejection.UNSUPPORTED_HOST, host)

        val addresses = try {
            resolveAddress(host)
        } catch (e: java.net.UnknownHostException) {
            return Verdict.Rejected(Rejection.PRIVATE_ADDRESS, "unresolvable host $host")
        }
        addresses.firstOrNull { isForbiddenAddress(it) }?.let {
            return Verdict.Rejected(Rejection.PRIVATE_ADDRESS, "$host resolves to ${it.hostAddress}")
        }
        return Verdict.Allowed(uri, site)
    }

    /** Lower-cases and IDN-normalises a host label so allowlist comparison is exact. */
    fun normaliseHost(host: String): String = IDN.toASCII(host.trim().trimEnd('.')).lowercase()

    /** Whether an address is one the analyzer must never connect to. */
    fun isForbiddenAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            isCarrierGradeNat(address) ||
            isUniqueLocalIpv6(address)

    private fun isCarrierGradeNat(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xFF) in 64..127
    }

    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    private fun systemResolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}
