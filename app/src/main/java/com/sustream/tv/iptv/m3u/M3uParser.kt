package com.sustream.tv.iptv.m3u

/**
 * M3U / M3U8 playlist parser.
 *
 * Treats its input as hostile. A playlist is a text file fetched from a URL the user typed, so it
 * may be truncated, be a 200-status HTML error page, contain a million lines, or carry a single
 * 4 MB line designed to exhaust memory. Every one of those is bounded here rather than by hoping
 * the file is well-formed.
 *
 * ## The format, as it actually occurs
 *
 * ```
 * #EXTM3U x-tvg-url="https://example.org/epg.xml.gz"
 * #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="https://…/bbc1.png" group-title="UK",BBC One HD
 * #EXTGRP:UK Entertainment
 * #EXTVLCOPT:http-user-agent=SomeAgent
 * https://example.org/live/user/pass/12345.m3u8
 * ```
 *
 * Real-world deviations this handles:
 *  * attribute values containing commas (`group-title="Sport, UK"`), so the display name is taken
 *    from the last comma **outside** quotes, not the first comma;
 *  * `#EXTGRP:` as an alternative to `group-title`;
 *  * `#EXTVLCOPT:` and `#KODIPROP:` metadata lines between the `#EXTINF` and the URL;
 *  * a UTF-8 byte-order mark before `#EXTM3U`;
 *  * CRLF and lone-CR line endings;
 *  * `tvg-chno` / `channel-number` for channel numbers;
 *  * a missing `#EXTM3U` header, which many providers omit;
 *  * duplicate `tvg-id` values, deduplicated by appending the ordinal.
 *
 * Pure Kotlin with no Android or network dependency, so it is fully unit-testable. See
 * `M3uParserTest`.
 */
object M3uParser {

    /**
     * @param content the whole playlist. Callers must apply the download size cap before this point
     *   (see `ResponseSizeLimitInterceptor`); the limits here bound *parsing*, not transfer.
     */
    fun parse(content: String): M3uPlaylist {
        val problems = mutableListOf<M3uProblem>()
        val entries = mutableListOf<M3uEntry>()
        var skipped = 0
        var truncated = false
        var epgUrl: String? = null

        // Pending state for the entry currently being assembled.
        var pendingAttributes: Map<String, String>? = null
        var pendingName: String? = null
        var pendingGroupOverride: String? = null
        var pendingLineNumber = 0

        val lines = content.lineSequence().iterator()
        var lineNumber = 0

        while (lines.hasNext()) {
            if (entries.size >= MAX_ENTRIES) {
                truncated = true
                break
            }

            lineNumber++
            var raw = lines.next()

            if (lineNumber == 1) raw = raw.removePrefix(BYTE_ORDER_MARK)

            // Lone-CR files arrive as a single enormous "line"; splitting is pointless because the
            // length guard below would reject it anyway, so it is reported plainly.
            if (raw.length > MAX_LINE_LENGTH) {
                skipped++
                addProblem(problems, lineNumber, "Line is too long to be a playlist entry.")
                continue
            }

            val line = raw.trim().trimEnd('\r')
            if (line.isEmpty()) continue

            when {
                line.startsWith(TAG_HEADER, ignoreCase = true) -> {
                    // The header often carries the provider's own EPG endpoint. Using it is lawful:
                    // it is the provider telling us where their guide lives.
                    epgUrl = parseAttributes(line).let { attrs ->
                        attrs[ATTR_TVG_URL] ?: attrs[ATTR_TVG_URL_ALT]
                    }
                }

                line.startsWith(TAG_EXTINF, ignoreCase = true) -> {
                    val info = parseExtInf(line)
                    if (info == null) {
                        skipped++
                        addProblem(problems, lineNumber, "Malformed #EXTINF line.")
                        pendingAttributes = null
                        pendingName = null
                    } else {
                        pendingAttributes = info.attributes
                        pendingName = info.displayName
                        pendingGroupOverride = null
                        pendingLineNumber = lineNumber
                    }
                }

                line.startsWith(TAG_EXTGRP, ignoreCase = true) -> {
                    pendingGroupOverride = line.substringAfter(':', "").trim().takeIf {
                        it.isNotEmpty()
                    }
                }

                // Metadata lines between #EXTINF and the URL. Recognised so they are not mistaken
                // for a stream URL, and otherwise ignored: honouring arbitrary VLC/Kodi options
                // would mean letting a playlist set request headers, which is a security decision
                // the app makes, not the playlist.
                line.startsWith(TAG_VLCOPT, ignoreCase = true) ||
                    line.startsWith(TAG_KODIPROP, ignoreCase = true) ||
                    line.startsWith(TAG_EXTVLCOPT_ALT, ignoreCase = true) -> Unit

                line.startsWith('#') -> Unit // any other directive or comment

                else -> {
                    // A non-comment line is the stream URL for the pending #EXTINF.
                    val attributes = pendingAttributes
                    val name = pendingName
                    if (attributes == null || name == null) {
                        skipped++
                        addProblem(problems, lineNumber, "Stream URL with no preceding #EXTINF.")
                    } else {
                        entries += M3uEntry(
                            displayName = name,
                            streamUrl = line,
                            tvgId = attributes[ATTR_TVG_ID]?.takeIf { it.isNotBlank() },
                            tvgName = attributes[ATTR_TVG_NAME]?.takeIf { it.isNotBlank() },
                            logoUrl = attributes[ATTR_TVG_LOGO]?.takeIf { it.isNotBlank() },
                            group = (pendingGroupOverride ?: attributes[ATTR_GROUP_TITLE])
                                ?.takeIf { it.isNotBlank() },
                            channelNumber = (
                                attributes[ATTR_TVG_CHNO]
                                    ?: attributes[ATTR_CHANNEL_NUMBER]
                                )?.takeIf { it.isNotBlank() },
                            sourceLineNumber = pendingLineNumber,
                        )
                    }
                    pendingAttributes = null
                    pendingName = null
                    pendingGroupOverride = null
                }
            }
        }

        // An #EXTINF with no following URL at end of file: the playlist was truncated mid-write.
        if (pendingAttributes != null) {
            skipped++
            addProblem(problems, pendingLineNumber, "Entry has no stream URL; playlist may be truncated.")
        }

        return M3uPlaylist(
            entries = entries,
            epgUrl = epgUrl,
            skippedLineCount = skipped,
            problems = problems,
            truncated = truncated,
        )
    }

    /** Cheap check used to distinguish a playlist from an HTML error page served with status 200. */
    fun looksLikePlaylist(content: String): Boolean {
        val head = content.take(SNIFF_LENGTH).removePrefix(BYTE_ORDER_MARK).trimStart()
        if (head.startsWith(TAG_HEADER, ignoreCase = true)) return true
        if (head.contains(TAG_EXTINF, ignoreCase = true)) return true
        // A provider that omits the header still cannot be serving HTML.
        val lowered = head.lowercase()
        return !lowered.startsWith("<!doctype") && !lowered.startsWith("<html") &&
            !lowered.startsWith("{") && !lowered.startsWith("[")
    }

    // ---- #EXTINF ------------------------------------------------------------

    internal data class ExtInf(
        val attributes: Map<String, String>,
        val displayName: String,
    )

    /**
     * Splits `#EXTINF:-1 key="value" …,Display Name`.
     *
     * The display name is everything after the **last comma outside quotes**. Splitting on the first
     * comma is the common bug: `group-title="Sport, UK"` would leave the name as `UK",BBC One`.
     */
    internal fun parseExtInf(line: String): ExtInf? {
        val afterTag = line.substringAfter(':', "")
        if (afterTag.isEmpty()) return null

        val separator = lastCommaOutsideQuotes(afterTag) ?: return null
        val attributePart = afterTag.substring(0, separator)
        val name = afterTag.substring(separator + 1).trim()

        // A blank name is recoverable: tvg-name is a reasonable substitute, and dropping the entry
        // would lose a working channel over a cosmetic omission.
        val attributes = parseAttributes(attributePart)
        val resolvedName = name.ifBlank { attributes[ATTR_TVG_NAME].orEmpty() }
        if (resolvedName.isBlank()) return null

        return ExtInf(attributes = attributes, displayName = resolvedName)
    }

    private fun lastCommaOutsideQuotes(text: String): Int? {
        var inQuotes = false
        var lastComma: Int? = null
        for (index in text.indices) {
            when (text[index]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) lastComma = index
            }
        }
        return lastComma
    }

    /**
     * Parses `key="value"` pairs, tolerating unquoted values and stray whitespace.
     *
     * Keys are lower-cased because providers are inconsistent about case (`Group-Title`,
     * `group-title`, `GROUP-TITLE` all occur).
     */
    internal fun parseAttributes(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        val length = text.length

        while (index < length && result.size < MAX_ATTRIBUTES) {
            while (index < length && (text[index].isWhitespace() || text[index] == ',')) index++
            if (index >= length) break

            val keyStart = index
            while (index < length && text[index] != '=' && !text[index].isWhitespace()) index++
            if (index >= length || text[index] != '=') {
                // A token with no '=' is the duration field or a stray word; skip to the next space.
                while (index < length && !text[index].isWhitespace()) index++
                continue
            }

            val key = text.substring(keyStart, index).lowercase()
            index++ // consume '='

            val value: String
            if (index < length && text[index] == '"') {
                index++
                val valueStart = index
                while (index < length && text[index] != '"') index++
                value = text.substring(valueStart, minOf(index, length))
                if (index < length) index++ // consume closing quote
            } else {
                val valueStart = index
                while (index < length && !text[index].isWhitespace()) index++
                value = text.substring(valueStart, index)
            }

            if (key.isNotEmpty()) result[key] = value.take(MAX_ATTRIBUTE_VALUE_LENGTH)
        }
        return result
    }

    private fun addProblem(
        problems: MutableList<M3uProblem>,
        lineNumber: Int,
        message: String,
    ) {
        if (problems.size < MAX_REPORTED_PROBLEMS) {
            problems += M3uProblem(lineNumber, message)
        }
    }

    // ---- Limits -------------------------------------------------------------

    /**
     * 100k channels is roughly seventy times the largest legitimate playlist seen in practice
     * (the supplied prototype's own example is 1,420). Beyond this the file is not a channel list.
     */
    const val MAX_ENTRIES = 100_000

    /** A single attribute line beyond 8 KB is not a channel definition. */
    const val MAX_LINE_LENGTH = 8_192

    const val MAX_ATTRIBUTES = 40
    const val MAX_ATTRIBUTE_VALUE_LENGTH = 2_048
    const val MAX_REPORTED_PROBLEMS = 10
    private const val SNIFF_LENGTH = 512

    private const val BYTE_ORDER_MARK = "﻿"
    private const val TAG_HEADER = "#EXTM3U"
    private const val TAG_EXTINF = "#EXTINF"
    private const val TAG_EXTGRP = "#EXTGRP"
    private const val TAG_VLCOPT = "#EXTVLCOPT"
    private const val TAG_EXTVLCOPT_ALT = "#EXTOPT"
    private const val TAG_KODIPROP = "#KODIPROP"

    private const val ATTR_TVG_ID = "tvg-id"
    private const val ATTR_TVG_NAME = "tvg-name"
    private const val ATTR_TVG_LOGO = "tvg-logo"
    private const val ATTR_GROUP_TITLE = "group-title"
    private const val ATTR_TVG_CHNO = "tvg-chno"
    private const val ATTR_CHANNEL_NUMBER = "channel-number"
    private const val ATTR_TVG_URL = "x-tvg-url"
    private const val ATTR_TVG_URL_ALT = "url-tvg"
}

/** One channel from a playlist, before validation and before becoming a domain `Channel`. */
data class M3uEntry(
    val displayName: String,
    val streamUrl: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val group: String?,
    val channelNumber: String?,
    val sourceLineNumber: Int,
)

data class M3uPlaylist(
    val entries: List<M3uEntry>,
    /** From `x-tvg-url` in the header, when the provider supplies one. */
    val epgUrl: String?,
    val skippedLineCount: Int,
    val problems: List<M3uProblem>,
    /** True when [M3uParser.MAX_ENTRIES] was reached and the rest of the file was not read. */
    val truncated: Boolean,
)

data class M3uProblem(
    val lineNumber: Int,
    val message: String,
)
