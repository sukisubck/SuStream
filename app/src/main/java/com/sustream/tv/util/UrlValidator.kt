package com.sustream.tv.util

import android.net.Uri

/**
 * Guards every URL the app fetches or stores against scheme-injection and other
 * trivial attacks. The allowed-scheme list is intentionally narrow: only http/https
 * for remote resources. No file://, content://, javascript://, etc.
 *
 * See docs/SECURITY.md for the policy rationale.
 */
class UrlValidator {

    /** Returns true when [url] is a syntactically valid http or https URL. */
    fun isValid(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val parsed = Uri.parse(url.trim())
            parsed.scheme in ALLOWED_SCHEMES && !parsed.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Throws [IllegalArgumentException] when [url] is not a valid http/https URL.
     * Use at repository boundaries before storing or fetching.
     */
    fun requireValid(url: String) {
        require(isValid(url)) {
            "URL rejected — must be http or https with a non-empty host. Got: $url"
        }
    }

    /** True when [url] uses plain HTTP (not HTTPS). Used to surface cleartext warnings. */
    fun isCleartext(url: String): Boolean =
        url.trim().startsWith("http://", ignoreCase = true)

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https")
    }
}
