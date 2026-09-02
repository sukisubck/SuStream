package com.sustream.tv.core.util

import java.time.Instant
import java.time.ZoneId

/**
 * Injectable clock.
 *
 * Anything that records "when" — watch progress, playlist sync time, link expiry, the EPG's
 * "now" marker — takes this instead of calling [Instant.now]. Tests then assert on exact values
 * rather than sleeping or tolerating drift, and the EPG grid can be driven to a fixed instant.
 */
interface TimeSource {
    fun now(): Instant
    fun nowMillis(): Long = now().toEpochMilli()
    fun zone(): ZoneId
}

object SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Fixed clock for tests and for previews that must render a stable EPG. */
class FixedTimeSource(
    private var instant: Instant,
    private val zone: ZoneId = ZoneId.of("Europe/London"),
) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zone

    fun advanceBy(millis: Long) {
        instant = instant.plusMillis(millis)
    }

    fun set(newInstant: Instant) {
        instant = newInstant
    }
}
