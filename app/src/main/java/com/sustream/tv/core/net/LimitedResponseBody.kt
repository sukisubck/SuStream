package com.sustream.tv.core.net

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * A [ResponseBody] that fails once more than [maxBytes] have been read.
 *
 * Needed because `Content-Length` is optional: a chunked response can stream indefinitely, and a
 * playlist URL is attacker-controlled input. Counting as we read is the only reliable ceiling.
 */
internal class LimitedResponseBody(
    private val delegate: ResponseBody,
    private val maxBytes: Long,
) : ResponseBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = CountingSource(delegate.source()).buffer()

    private inner class CountingSource(source: Source) : ForwardingSource(source) {
        private var bytesRead = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) {
                bytesRead += read
                if (bytesRead > maxBytes) {
                    throw ResponseTooLargeException(maxBytes, declaredBytes = null)
                }
            }
            return read
        }
    }
}
