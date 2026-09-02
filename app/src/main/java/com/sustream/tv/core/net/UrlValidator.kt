package com.sustream.tv.core.net

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import java.net.IDN
import java.net.URI

/**
 * Gatekeeper for every URL the app is asked to open, whether it came from a user, a playlist, an
 * EPG document, a provider response or the backend.
 *
 * The brief requires URL validation, rejection of unsafe schemes and SSRF protection. The client
 * cannot be the only line of defence — a determined attacker controls the device — but it stops the
 * realistic attacks: a malicious M3U pointing at `file:///data/data/.../secure_prefs.xml`, a
 * provider response redirecting to a router admin page on the local network, or a `javascript:`
 * URL smuggled through a playlist field.
 *
 * Server-side equivalents are specified in docs/SECURITY.md section 4.
 */
class UrlValidator(
    /** True in release builds, where non-TLS traffic to our own services is never acceptable. */
    private val requireHttpsForAppServices: Boolean,
) {

    /**
     * What the URL will be used for. Policy differs by class, because IPTV is the one place where
     * plain HTTP is still the norm among real providers and blocking it outright would remove the
     * feature rather than secure it.
     */
    enum class Usage {
        /** TMDB, the SuStream backend, the provider API. Always HTTPS. */
        APP_SERVICE,

        /** A playlist, EPG or stream URL the user supplied. HTTPS preferred, HTTP on consent. */
        USER_MEDIA,

        /** Artwork and channel logos. HTTPS preferred; HTTP tolerated, never carries credentials. */
        IMAGE,
    }

    data class Options(
        /**
         * Set when the user has explicitly acknowledged that a specific playlist is unencrypted.
         * Recorded against the playlist so the warning is shown once, not on every refresh.
         */
        val cleartextAcknowledged: Boolean = false,
        /**
         * Set when the user has opted into playlists hosted on their own LAN (a home media server,
         * a local Xtream proxy). Off by default, because allowing private ranges is exactly what
         * turns the app into an SSRF tool against the user's own network.
         */
        val allowPrivateHosts: Boolean = false,
    )

    fun validate(
        raw: String?,
        usage: Usage,
        options: Options = Options(),
    ): AppResult<ValidatedUrl> {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) {
            return reject(null, "No address was supplied.")
        }
        if (trimmed.length > MAX_URL_LENGTH) {
            return reject(null, "That address is unreasonably long.")
        }
        // Control characters are how header and newline injection get smuggled through a text
        // format such as M3U.
        if (trimmed.any { it.isISOControl() }) {
            return reject(null, "That address contains control characters.")
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return reject(null, "That address is not valid.")
        }

        val scheme = uri.scheme?.lowercase() ?: return reject(null, "That address has no scheme.")

        if (scheme !in ALLOWED_SCHEMES) {
            return reject(scheme, "This app will not open " + scheme + ":// addresses.")
        }

        val httpsRequired = when (usage) {
            Usage.APP_SERVICE -> requireHttpsForAppServices
            Usage.USER_MEDIA -> !options.cleartextAcknowledged
            Usage.IMAGE -> false
        }
        if (scheme == HTTP && httpsRequired) {
            val why = if (usage == Usage.APP_SERVICE) {
                "Encrypted connections are required for this service."
            } else {
                "This address is not encrypted."
            }
            return reject(scheme, why)
        }

        val host = uri.host ?: return reject(scheme, "That address has no host name.")

        // Userinfo in a URL is both a credential leak into logs and a classic phishing and
        // parser-confusion vector. Credentials belong in the encrypted store.
        if (uri.userInfo != null) {
            return reject(
                scheme,
                "Put the username and password in their own fields, not in the address.",
            )
        }

        val asciiHost = try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: Exception) {
            return reject(scheme, "That host name is not valid.")
        }

        if (!options.allowPrivateHosts && isNonPublicHost(asciiHost)) {
            return reject(
                scheme,
                "That address points at a private or local network. Turn on local playlists in " +
                    "Settings if this is your own server.",
            )
        }

        if (uri.port != -1 && uri.port !in MIN_PORT..MAX_PORT) {
            return reject(scheme, "That port is not valid.")
        }

        return AppResult.Success(
            ValidatedUrl(
                value = trimmed,
                scheme = scheme,
                host = asciiHost,
                isCleartext = scheme == HTTP,
            ),
        )
    }

    private fun reject(scheme: String?, detail: String): AppResult<ValidatedUrl> =
        AppResult.Failure(AppError.SchemeRejected(scheme, detail))

    /**
     * Loopback, link-local, RFC1918, CGNAT, multicast, IPv6 unique-local and `.local`-style names.
     *
     * This is a textual check, not DNS resolution. Resolving here would itself be a request to an
     * attacker-chosen host, and the answer could change between check and use (DNS rebinding).
     * Authoritative enforcement belongs where the connection is actually made — see
     * docs/SECURITY.md section 4 for the backend rules.
     */
    private fun isNonPublicHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true

        // A bare hostname with no dot is only resolvable inside a local network.
        if (!host.contains('.') && !host.contains(':')) return true

        if (host.contains(':')) return isNonPublicIpv6(host)

        val octets = host.split('.')
        if (octets.size == IPV4_OCTETS && octets.all { it.toIntOrNull() in 0..MAX_OCTET }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return when {
                a == 0 -> true // 0.0.0.0/8
                a == 10 -> true // RFC1918
                a == 127 -> true // loopback
                a == 169 && b == 254 -> true // link-local, includes cloud metadata endpoints
                a == 172 && b in 16..31 -> true // RFC1918
                a == 192 && b == 168 -> true // RFC1918
                a == 100 && b in 64..127 -> true // CGNAT
                a == 192 && b == 0 -> true // 192.0.0.0/24 protocol assignments
                a >= 224 -> true // multicast and reserved
                else -> false
            }
        }
        return false
    }

    private fun isNonPublicIpv6(host: String): Boolean {
        val v6 = host.trim('[', ']').lowercase()
        if (v6 == "::1" || v6 == "::") return true
        val head = v6.substringBefore(':')
        if (head.length >= 2) {
            // fc00::/7 unique-local.
            if (head.take(2) == "fc" || head.take(2) == "fd") return true
            // fe80::/10 link-local.
            if (head.take(3) in IPV6_LINK_LOCAL_PREFIXES) return true
        }
        return false
    }

    companion object {
        private const val HTTP = "http"
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val IPV4_OCTETS = 4
        private const val MAX_OCTET = 255

        /**
         * `file`, `content`, `data`, `javascript`, `intent` and everything else are rejected.
         *
         * `content://` is excluded even though the app *does* read a user-picked M3U file: that
         * path goes through the Android document picker and a Uri the system granted us, never
         * through a URL string parsed out of a playlist. Keeping it off this list means a hostile
         * playlist cannot reach another app's content provider.
         */
        val ALLOWED_SCHEMES = setOf("https", HTTP)

        const val MAX_URL_LENGTH = 2048

        private val LOCAL_SUFFIXES = listOf(".local", ".internal", ".home.arpa", ".lan")
        private val IPV6_LINK_LOCAL_PREFIXES = setOf("fe8", "fe9", "fea", "feb")
    }
}

/**
 * A URL that has passed [UrlValidator]. Having a distinct type means "did anyone check this?" is
 * answered by the type system rather than by reading the whole call chain.
 */
data class ValidatedUrl(
    val value: String,
    val scheme: String,
    val host: String,
    val isCleartext: Boolean,
)
