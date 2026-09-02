package com.sustream.tv.core.log

import android.util.Log
import com.sustream.tv.BuildConfig

/**
 * The only logging entry point in the app.
 *
 * Two rules it enforces that scattered `Log.d` calls cannot:
 *  1. Every message passes through [Redact.message], so a token pasted into a message is masked
 *     even when the caller forgets.
 *  2. Verbose and debug output is compiled to a no-op condition in release builds, so release
 *     logcat carries warnings and errors only.
 *
 * There is deliberately no crash-reporting or analytics sink wired in: v1 collects nothing. See
 * docs/SECURITY.md section 8.
 */
object AppLog {

    private const val MAX_TAG_LENGTH = 23

    fun v(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag.safe(), Redact.message(message()))
    }

    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag.safe(), Redact.message(message()))
    }

    fun i(tag: String, message: String) {
        Log.i(tag.safe(), Redact.message(message))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag.safe(), Redact.message(message), throwable)
        } else {
            Log.w(tag.safe(), Redact.message(message))
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag.safe(), Redact.message(message), throwable)
        } else {
            Log.e(tag.safe(), Redact.message(message))
        }
    }

    /** Logcat truncates tags beyond 23 characters on older platforms. */
    private fun String.safe(): String =
        if (length <= MAX_TAG_LENGTH) this else substring(0, MAX_TAG_LENGTH)
}
