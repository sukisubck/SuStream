package com.sustream.tv.core.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Display formatting.
 *
 * Pure functions with no Android dependency so they are unit-testable, and locale-aware so a
 * UK user sees 24-hour clocks and day-first dates. Anything user-visible that needs pluralisation
 * or full translation goes through string resources instead; this handles the numeric shapes.
 */
object Formatters {

    private const val MINUTES_PER_HOUR = 60
    private const val MILLIS_PER_SECOND = 1_000L
    private const val PERCENT = 100

    /** `138` -> `"2h 18m"`, `54` -> `"54m"`. Matches the prototype's `2h 18m` / `54m`. */
    fun runtime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val hours = minutes / MINUTES_PER_HOUR
        val remainder = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0 -> remainder.toString() + "m"
            remainder == 0 -> hours.toString() + "h"
            else -> hours.toString() + "h " + remainder + "m"
        }
    }

    /** Remaining time for a Continue Watching card: `"1h 52m left"` in the prototype. */
    fun remaining(positionMillis: Long, durationMillis: Long): String? {
        if (durationMillis <= 0L) return null
        val leftMillis = (durationMillis - positionMillis).coerceAtLeast(0L)
        val minutes = Duration.ofMillis(leftMillis).toMinutes().toInt()
        return runtime(minutes.coerceAtLeast(1))
    }

    /** `8.867` -> `"8.9"`. One decimal place, as the prototype shows. */
    fun rating(voteAverage: Double?): String? {
        if (voteAverage == null || voteAverage <= 0.0) return null
        return String.format(Locale.UK, "%.1f", voteAverage)
    }

    fun progressPercent(positionMillis: Long, durationMillis: Long): Int {
        if (durationMillis <= 0L) return 0
        return ((positionMillis.toDouble() / durationMillis) * PERCENT)
            .roundToInt()
            .coerceIn(0, PERCENT)
    }

    /** `00:33:14` style, used by the player's position and duration readouts. */
    fun clockPosition(millis: Long): String {
        val safe = millis.coerceAtLeast(0L)
        val totalSeconds = safe / MILLIS_PER_SECOND
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.UK, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.UK, "%d:%02d", minutes, seconds)
        }
    }

    /** `18:45`, for the EPG. 24-hour, which is the UK convention. */
    fun timeOfDay(instant: Instant, zone: ZoneId): String =
        TIME_OF_DAY.format(instant.atZone(zone))

    /** `1 Sep 2026`. */
    fun mediumDate(date: LocalDate): String = MEDIUM_DATE.format(date)

    fun mediumDate(instant: Instant, zone: ZoneId): String =
        MEDIUM_DATE.format(instant.atZone(zone))

    /**
     * `"10m ago"`, `"1h ago"`, `"Just now"` — the shape the prototype uses for playlist sync times
     * and notifications.
     */
    fun relativePast(then: Instant, now: Instant): String {
        val seconds = Duration.between(then, now).seconds
        return when {
            seconds < 0 -> "Just now"
            seconds < SECONDS_IN_MINUTE -> "Just now"
            seconds < SECONDS_IN_HOUR -> (seconds / SECONDS_IN_MINUTE).toString() + "m ago"
            seconds < SECONDS_IN_DAY -> (seconds / SECONDS_IN_HOUR).toString() + "h ago"
            seconds < SECONDS_IN_WEEK -> (seconds / SECONDS_IN_DAY).toString() + "d ago"
            else -> mediumDate(then, ZoneId.systemDefault())
        }
    }

    /** `1420` -> `"1,420"`. Used for channel counts. */
    fun count(value: Int): String = String.format(Locale.UK, "%,d", value)

    /** `22.4 GB`, for provider library file sizes. */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= BYTES_PER_UNIT && unitIndex < units.lastIndex) {
            value /= BYTES_PER_UNIT
            unitIndex++
        }
        val pattern = if (unitIndex == 0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.UK, pattern, value, units[unitIndex])
    }

    /** Joins metadata parts with the mid-dot separator the prototype uses, skipping blanks. */
    fun metadataLine(vararg parts: String?): String =
        parts.filter { !it.isNullOrBlank() }.joinToString("  •  ")

    private const val SECONDS_IN_MINUTE = 60L
    private const val SECONDS_IN_HOUR = 3_600L
    private const val SECONDS_IN_DAY = 86_400L
    private const val SECONDS_IN_WEEK = 604_800L
    private const val BYTES_PER_UNIT = 1_024.0

    private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val MEDIUM_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
}
