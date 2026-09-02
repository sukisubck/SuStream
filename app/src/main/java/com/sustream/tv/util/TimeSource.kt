package com.sustream.tv.util

/**
 * Abstraction over wall-clock time so repositories are testable without Thread.sleep.
 *
 * Production code uses [System]; tests inject a fake that advances time on demand.
 */
interface TimeSource {
    /** Returns the current epoch time in milliseconds. */
    fun nowMillis(): Long

    /** Returns the current epoch time in seconds (truncated). */
    fun nowSeconds(): Long = nowMillis() / 1_000L

    /** The real wall clock. */
    object System : TimeSource {
        override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
    }
}
