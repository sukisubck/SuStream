package com.sustream.tv.core.log

/**
 * Removes credentials from anything that might reach a log, a crash report or the UI.
 *
 * This exists because the two integrations most likely to leak are exactly the two that carry
 * long-lived credentials: Xtream playlists put the username and password in the *query string* of
 * every request, and provider APIs put a bearer token in a header. A single stray
 * `Log.d(TAG, url)` would publish a user's IPTV subscription to logcat, which any app with
 * READ_LOGS or anyone with adb can read.
 *
 * Everything here is pure and unit-tested; see `RedactTest`.
 */
object Redact {

    private const val MASK = "***"

    /** Query parameters whose values are always credentials or tokens. */
    private val SENSITIVE_QUERY_KEYS = setOf(
        "username", "user", "usr", "password", "pass", "pwd",
        "api_key", "apikey", "key", "token", "access_token", "refresh_token",
        "auth", "session", "sid", "secret", "signature", "sig",
    )

    /** Headers whose values must never appear in a log. */
    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "x-api-key", "x-auth-token", "x-torbox-key",
    )

    /**
     * Reduces a URL to scheme, host, port and path with every sensitive query value masked.
     *
     * Xtream stream URLs also embed credentials as *path segments*
     * (`/live/<username>/<password>/1234.ts`), which no query-parameter filter would catch, so the
     * path is additionally collapsed for the known Xtream shapes.
     */
    fun url(raw: String?): String {
        if (raw.isNullOrBlank()) return "<empty>"
        return try {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme ?: return MASK
            val host = uri.host ?: return "$scheme://$MASK"
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = redactPath(uri.rawPath.orEmpty())
            val query = uri.rawQuery?.let { "?" + redactQuery(it) }.orEmpty()
            "$scheme://$host$port$path$query"
        } catch (_: Exception) {
            // A URL we cannot even parse is more likely to be malformed user input than a secret,
            // but we still refuse to echo it verbatim.
            "<unparseable-url>"
        }
    }

    /**
     * Xtream-style stream paths embed the credentials directly:
     *   /live/USERNAME/PASSWORD/12345.ts
     *   /movie/USERNAME/PASSWORD/12345.mkv
     *   /series/USERNAME/PASSWORD/12345.mp4
     * Collapse the two segments after the known prefixes.
     */
    private fun redactPath(path: String): String {
        val segments = path.split('/')
        if (segments.size < 4) return path
        val prefixes = setOf("live", "movie", "series", "timeshift")
        val out = segments.toMutableList()
        for (i in out.indices) {
            if (out[i].lowercase() in prefixes && i + 2 < out.size) {
                out[i + 1] = MASK
                out[i + 2] = MASK
            }
        }
        return out.joinToString("/")
    }

    private fun redactQuery(rawQuery: String): String =
        rawQuery.split('&').joinToString("&") { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@joinToString pair
            val key = pair.substring(0, idx)
            if (key.lowercase() in SENSITIVE_QUERY_KEYS) "$key=$MASK" else pair
        }

    /** Masks a header value if the header name is sensitive. */
    fun header(name: String, value: String): String =
        if (name.lowercase() in SENSITIVE_HEADERS) MASK else value

    fun isSensitiveHeader(name: String): Boolean = name.lowercase() in SENSITIVE_HEADERS

    /**
     * A tail-only hint so the UI can show *which* key is configured without revealing it.
     * `"18a8a896-...-78a068259e8a"` becomes `"...9e8a"`.
     */
    fun tail(secret: String?, visible: Int = 4): String {
        if (secret.isNullOrBlank()) return "<not set>"
        return if (secret.length <= visible) MASK else "..." + secret.takeLast(visible)
    }

    /** Scrubs a free-text message of anything that looks like a bearer token or a long key. */
    fun message(text: String): String =
        text
            .replace(BEARER_REGEX) { "Bearer $MASK" }
            .replace(LONG_TOKEN_REGEX, MASK)

    private val BEARER_REGEX = Regex("""(?i)bearer\s+[A-Za-z0-9\-._~+/]{8,}=*""")

    /** JWTs and long opaque keys: three dot-separated base64 chunks, or a 24+ char key-like run. */
    private val LONG_TOKEN_REGEX =
        Regex("""\b(?:[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|[A-Fa-f0-9]{32,})\b""")
}

/**
 * Wrapper for a value that must never be printed.
 *
 * Kotlin data classes generate a `toString()` that includes every property, so a password held as
 * a plain `String` inside a data class leaks the moment anything logs that class. Holding it as a
 * [Secret] makes the leak impossible by construction.
 */
@JvmInline
value class Secret(private val raw: String) {
    /** The only way to read the value. Named so that call sites are easy to audit by grepping. */
    fun reveal(): String = raw

    val isBlank: Boolean get() = raw.isBlank()
    val length: Int get() = raw.length

    override fun toString(): String = "Secret(***)"

    companion object {
        val EMPTY = Secret("")
    }
}
