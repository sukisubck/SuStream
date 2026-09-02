package com.sustream.tv.iptv

import android.content.ContentResolver
import android.net.Uri
import com.sustream.tv.core.config.NetworkLimits
import com.sustream.tv.core.log.Redact
import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.net.safeApiCall
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.min

/**
 * Retrieves playlist and guide documents, from a URL or from a file the user picked.
 *
 * Every path through here is treated as untrusted input:
 *  * URLs go through [UrlValidator] before a socket is opened;
 *  * transfer size is capped by `ResponseSizeLimitInterceptor` on the client **and** re-checked
 *    while reading, because a chunked response has no declared length;
 *  * local documents are read through the [ContentResolver] using the persisted permission granted
 *    by the system document picker, never by parsing a path out of a playlist.
 */
class PlaylistFetcher(
    private val httpClient: OkHttpClient,
    private val contentResolver: ContentResolver,
    private val urlValidator: UrlValidator,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Downloads a playlist as text.
     *
     * Read as text rather than streamed because the M3U parser needs the whole document — it has no
     * meaningful incremental representation — and the 10 MB cap keeps that safe.
     */
    suspend fun fetchText(
        url: String,
        cleartextAcknowledged: Boolean,
        allowPrivateHosts: Boolean,
        maxBytes: Long = NetworkLimits.MAX_PLAYLIST_BYTES,
    ): AppResult<String> = withContext(dispatchers.io) {
        val validated = urlValidator.validate(
            raw = url,
            usage = UrlValidator.Usage.USER_MEDIA,
            options = UrlValidator.Options(cleartextAcknowledged, allowPrivateHosts),
        )
        if (validated is AppResult.Failure) return@withContext validated

        safeApiCall {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP " + response.code + " from " + Redact.url(url))
                }
                readTextBounded(
                    // Gzip is transparent to OkHttp when it set the request header itself, but a
                    // provider serving `.m3u8.gz` from a plain URL is not, so it is handled here.
                    stream = maybeGunzip(response.body.byteStream(), url),
                    maxBytes = maxBytes,
                )
            }
        }
    }

    /**
     * Reads a playlist the user picked with the document picker.
     *
     * Deliberately does **not** go through [UrlValidator]: a `content://` URI is not user-typed
     * text, it is a handle the system granted after the user chose a file in the picker.
     * `UrlValidator` rejects the `content` scheme precisely so that a URI appearing inside a
     * *playlist* can never reach this path.
     */
    suspend fun readDocument(
        documentUri: String,
        maxBytes: Long = NetworkLimits.MAX_PLAYLIST_BYTES,
    ): AppResult<String> = withContext(dispatchers.io) {
        try {
            val uri = Uri.parse(documentUri)
            if (uri.scheme != ContentResolver.SCHEME_CONTENT &&
                uri.scheme != ContentResolver.SCHEME_FILE
            ) {
                return@withContext AppResult.Failure(
                    AppError.SchemeRejected(uri.scheme, "That is not a document this app can read."),
                )
            }

            val stream = contentResolver.openInputStream(uri)
                ?: return@withContext AppResult.Failure(
                    AppError.NotFound("That file is no longer available."),
                )

            stream.use {
                AppResult.Success(
                    readTextBounded(maybeGunzip(it, documentUri), maxBytes),
                )
            }
        } catch (security: SecurityException) {
            // The persisted permission was revoked, which happens when the file is deleted or the
            // user clears the app's granted URIs.
            AppResult.Failure(
                AppError.Unauthorised(
                    "Permission to read that file has been withdrawn. Choose it again.",
                    refreshable = false,
                ),
            )
        } catch (io: IOException) {
            AppResult.Failure(AppError.Storage("That file could not be read."))
        }
    }

    /**
     * Opens a guide document as a stream.
     *
     * Streamed rather than read into a string because XMLTV feeds are an order of magnitude larger
     * than playlists and the pull parser consumes them incrementally. The caller must close it.
     */
    suspend fun openEpgStream(
        url: String,
        cleartextAcknowledged: Boolean,
        allowPrivateHosts: Boolean,
    ): AppResult<EpgStream> = withContext(dispatchers.io) {
        val validated = urlValidator.validate(
            raw = url,
            usage = UrlValidator.Usage.USER_MEDIA,
            options = UrlValidator.Options(cleartextAcknowledged, allowPrivateHosts),
        )
        if (validated is AppResult.Failure) return@withContext validated

        safeApiCall {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP " + response.code + " from " + Redact.url(url))
            }
            EpgStream(
                stream = maybeGunzip(response.body.byteStream(), url),
                close = { response.close() },
            )
        }
    }

    /**
     * Wraps the stream in a gzip decoder when the content looks compressed.
     *
     * Detected by sniffing the two-byte gzip magic rather than by trusting the file extension: a
     * `.xml` URL that actually serves gzip is common, and so is the reverse.
     */
    private fun maybeGunzip(stream: InputStream, sourceHint: String): InputStream {
        val buffered = BufferedInputStream(stream, GZIP_SNIFF_BUFFER)
        buffered.mark(GZIP_MAGIC_LENGTH)
        val header = ByteArray(GZIP_MAGIC_LENGTH)
        val read = buffered.read(header, 0, GZIP_MAGIC_LENGTH)
        buffered.reset()

        val isGzip = read == GZIP_MAGIC_LENGTH &&
            header[0] == GZIP_MAGIC_0 &&
            header[1] == GZIP_MAGIC_1

        return if (isGzip) GZIPInputStream(buffered) else buffered
    }

    /**
     * Reads text with a hard ceiling.
     *
     * The interceptor already caps the *transfer*, but a gzip stream expands after that check, so a
     * small download can still decompress into hundreds of megabytes. This is the only place that
     * bound is enforced for decompressed content.
     */
    private fun readTextBounded(stream: InputStream, maxBytes: Long): String {
        val builder = StringBuilder()
        val buffer = CharArray(READ_BUFFER_CHARS)
        var total = 0L

        stream.reader(Charsets.UTF_8).use { reader ->
            while (true) {
                val toRead = min(buffer.size.toLong(), maxBytes - total + 1).toInt()
                if (toRead <= 0) break
                val count = reader.read(buffer, 0, toRead)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw com.sustream.tv.core.net.ResponseTooLargeException(maxBytes, total)
                }
                builder.appendRange(buffer, 0, count)
            }
        }
        return builder.toString()
    }

    private companion object {
        const val READ_BUFFER_CHARS = 8 * 1024
        const val GZIP_SNIFF_BUFFER = 8 * 1024
        const val GZIP_MAGIC_LENGTH = 2
        const val GZIP_MAGIC_0 = 0x1f.toByte()
        const val GZIP_MAGIC_1 = 0x8b.toByte()
    }
}

/** An open guide stream and the means to release its connection. */
class EpgStream(
    val stream: InputStream,
    private val close: () -> Unit,
) : AutoCloseable {
    override fun close() {
        runCatching { stream.close() }
        runCatching { close.invoke() }
    }
}
