package com.sustream.tv.iptv.epg

import android.util.Xml
import com.sustream.tv.domain.model.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * XMLTV guide parser.
 *
 * ## Security
 *
 * XMLTV is XML from a URL the user supplied, which makes XXE and billion-laughs the first thing to
 * deal with, not the last:
 *
 *  * **DTD processing is disabled** (`FEATURE_PROCESS_DOCDECL = false`), which is what closes both
 *    XXE (`<!ENTITY xxe SYSTEM "file:///data/data/…">`) and entity-expansion bombs. `XmlPullParser`
 *    defaults to DTDs off, but it is set explicitly here so the guarantee is visible and cannot be
 *    lost by swapping the parser factory.
 *  * **Pull parsing, not DOM.** A DOM parse of a 25 MB guide would allocate the whole tree; a
 *    50 MB one would kill a Fire TV Stick. The pull parser holds one element at a time.
 *  * **Hard caps** on programmes, channels and per-field length, so a hostile feed cannot exhaust
 *    memory even within the download size limit.
 *  * **A time window filter**, applied while parsing, so a feed carrying two weeks of schedule for
 *    1,400 channels only materialises the few hours the guide will actually show.
 *
 * ## Format
 *
 * ```xml
 * <tv>
 *   <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
 *   <programme start="20260901183000 +0100" stop="20260901190000 +0100" channel="bbc1.uk">
 *     <title lang="en">BBC News at Six</title>
 *     <desc lang="en">The latest national and international news.</desc>
 *     <category lang="en">News</category>
 *     <episode-num system="onscreen">S2 E4</episode-num>
 *   </programme>
 * </tv>
 * ```
 */
object XmltvParser {

    /**
     * @param windowStart programmes ending before this are discarded.
     * @param windowEnd programmes starting after this are discarded. Null means no upper bound.
     */
    fun parse(
        input: InputStream,
        windowStart: Instant,
        windowEnd: Instant?,
    ): XmltvResult {
        val programmes = mutableListOf<EpgProgramme>()
        val channelNames = mutableMapOf<String, String>()
        var skipped = 0
        var truncated = false

        val parser = Xml.newPullParser().apply {
            // The single most important line in this file. See the class comment.
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setFeature(FEATURE_PROCESS_DOCDECL, false)
            setInput(input, null)
        }

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        TAG_CHANNEL -> {
                            if (channelNames.size < MAX_CHANNELS) {
                                readChannel(parser)?.let { (id, name) -> channelNames[id] = name }
                            }
                        }

                        TAG_PROGRAMME -> {
                            if (programmes.size >= MAX_PROGRAMMES) {
                                truncated = true
                                break
                            }
                            when (val outcome = readProgramme(parser, windowStart, windowEnd)) {
                                is ProgrammeOutcome.Parsed -> programmes += outcome.programme
                                ProgrammeOutcome.OutOfWindow -> Unit // expected, not a problem
                                ProgrammeOutcome.Malformed -> skipped++
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            return XmltvResult(
                programmes = programmes,
                channelNames = channelNames,
                skippedCount = skipped,
                truncated = truncated,
                fatalError = "The guide file is not valid XML" +
                    (e.message?.let { ": " + it.take(MAX_ERROR_LENGTH) } ?: "."),
            )
        } catch (e: Exception) {
            return XmltvResult(
                programmes = programmes,
                channelNames = channelNames,
                skippedCount = skipped,
                truncated = truncated,
                fatalError = "The guide file could not be read.",
            )
        }

        return XmltvResult(
            programmes = programmes,
            channelNames = channelNames,
            skippedCount = skipped,
            truncated = truncated,
            fatalError = null,
        )
    }

    private sealed interface ProgrammeOutcome {
        data class Parsed(val programme: EpgProgramme) : ProgrammeOutcome
        data object OutOfWindow : ProgrammeOutcome
        data object Malformed : ProgrammeOutcome
    }

    private fun readChannel(parser: XmlPullParser): Pair<String, String>? {
        val id = parser.getAttributeValue(null, ATTR_ID)?.trim().orEmpty()
        if (id.isEmpty()) {
            skipElement(parser)
            return null
        }
        var name: String? = null
        val depth = parser.depth

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name.lowercase() == TAG_DISPLAY_NAME && name == null) {
                name = readText(parser)
            }
        }
        return id to (name ?: id)
    }

    private fun readProgramme(
        parser: XmlPullParser,
        windowStart: Instant,
        windowEnd: Instant?,
    ): ProgrammeOutcome {
        val channelId = parser.getAttributeValue(null, ATTR_CHANNEL)?.trim().orEmpty()
        val start = parseXmltvTime(parser.getAttributeValue(null, ATTR_START))
        val stop = parseXmltvTime(parser.getAttributeValue(null, ATTR_STOP))

        val depth = parser.depth
        var title: String? = null
        var description: String? = null
        var category: String? = null
        var episodeLabel: String? = null

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            if (parser.eventType != XmlPullParser.START_TAG) continue

            when (parser.name.lowercase()) {
                TAG_TITLE -> if (title == null) title = readText(parser)
                TAG_DESC -> if (description == null) description = readText(parser)
                TAG_CATEGORY -> if (category == null) category = readText(parser)
                TAG_EPISODE_NUM -> if (episodeLabel == null) episodeLabel = readText(parser)
            }
        }

        if (channelId.isEmpty() || start == null || title.isNullOrBlank()) {
            return ProgrammeOutcome.Malformed
        }

        // A missing `stop` is common. Assuming a nominal length is better than dropping the
        // programme: the guide then shows it with an approximate duration instead of a gap.
        val end = stop ?: start.plusSeconds(DEFAULT_DURATION_SECONDS)
        if (!end.isAfter(start)) return ProgrammeOutcome.Malformed

        if (end.isBefore(windowStart)) return ProgrammeOutcome.OutOfWindow
        if (windowEnd != null && start.isAfter(windowEnd)) return ProgrammeOutcome.OutOfWindow

        return ProgrammeOutcome.Parsed(
            EpgProgramme(
                channelTvgId = channelId.take(MAX_FIELD_LENGTH),
                title = title.take(MAX_FIELD_LENGTH),
                description = description?.take(MAX_DESCRIPTION_LENGTH),
                start = start,
                end = end,
                category = category?.take(MAX_FIELD_LENGTH),
                episodeLabel = episodeLabel?.take(MAX_SHORT_FIELD_LENGTH),
            ),
        )
    }

    private fun readText(parser: XmlPullParser): String? {
        val depth = parser.depth
        val builder = StringBuilder()
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            if (parser.eventType == XmlPullParser.TEXT) {
                if (builder.length < MAX_DESCRIPTION_LENGTH) builder.append(parser.text)
            }
        }
        return builder.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
        }
    }

    /**
     * XMLTV times are `yyyyMMddHHmmss` with an optional offset: `20260901183000 +0100`.
     *
     * Offset, seconds and even minutes are all optional in the wild, so the formatter treats the
     * offset as optional and defaults to UTC when absent — which is what the XMLTV specification
     * says to assume.
     */
    internal fun parseXmltvTime(raw: String?): Instant? {
        val text = raw?.trim()?.replace("  ", " ") ?: return null
        if (text.isEmpty()) return null

        return runCatching {
            OffsetDateTime.parse(text, WITH_OFFSET).toInstant()
        }.recoverCatching {
            // No offset supplied: interpret as UTC per the specification.
            OffsetDateTime.of(
                java.time.LocalDateTime.parse(text.substringBefore(' '), WITHOUT_OFFSET),
                ZoneOffset.UTC,
            ).toInstant()
        }.getOrNull()
    }

    private val WITH_OFFSET: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4)
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        // Seconds are omitted by some providers.
        .optionalStart().appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
        .optionalStart().appendLiteral(' ').optionalEnd()
        .appendOffset("+HHmm", "Z")
        .toFormatter()

    private val WITHOUT_OFFSET: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4)
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart().appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
        .toFormatter()

    /**
     * `XmlPullParser` exposes DTD processing under this feature name. Setting it false is what
     * blocks external entity resolution and entity expansion.
     */
    private const val FEATURE_PROCESS_DOCDECL =
        "http://xmlpull.org/v1/doc/features.html#process-docdecl"

    private const val TAG_CHANNEL = "channel"
    private const val TAG_PROGRAMME = "programme"
    private const val TAG_DISPLAY_NAME = "display-name"
    private const val TAG_TITLE = "title"
    private const val TAG_DESC = "desc"
    private const val TAG_CATEGORY = "category"
    private const val TAG_EPISODE_NUM = "episode-num"
    private const val ATTR_ID = "id"
    private const val ATTR_CHANNEL = "channel"
    private const val ATTR_START = "start"
    private const val ATTR_STOP = "stop"

    /** Enough for 1,400 channels over a two-day window at four programmes an hour. */
    const val MAX_PROGRAMMES = 300_000
    const val MAX_CHANNELS = 20_000
    const val MAX_FIELD_LENGTH = 512
    const val MAX_SHORT_FIELD_LENGTH = 64
    const val MAX_DESCRIPTION_LENGTH = 4_096
    private const val MAX_ERROR_LENGTH = 200
    private const val DEFAULT_DURATION_SECONDS = 1_800L
}

data class XmltvResult(
    val programmes: List<EpgProgramme>,
    /** `channel id` to display name, so a guide row can be labelled even without a playlist match. */
    val channelNames: Map<String, String>,
    val skippedCount: Int,
    val truncated: Boolean,
    /** Non-null when parsing could not continue at all. */
    val fatalError: String?,
)
