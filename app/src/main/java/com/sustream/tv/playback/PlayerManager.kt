package com.sustream.tv.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.result.AppError
import com.sustream.tv.domain.model.AudioTrack
import com.sustream.tv.domain.model.PreferredQuality
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.StreamContainer
import com.sustream.tv.domain.model.SubtitlePreferences
import com.sustream.tv.domain.model.SubtitleTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

private const val TAG = "Player"

/**
 * Owns the single [ExoPlayer] instance and translates it into observable state.
 *
 * ## Why one instance, owned here
 *
 * A Fire TV Stick has around 1 GB of RAM, and an ExoPlayer with its buffers is one of the largest
 * allocations the app makes. Creating one per screen — or leaking one across a configuration change
 * — is the difference between an app that plays and one the system kills. The activity declares
 * `configChanges` for the transitions a TV actually produces (HDMI mode switches, HDR changes) so
 * that this instance survives them rather than being torn down mid-playback.
 *
 * ## Buffering, tuned for TV
 *
 * The defaults are tuned for mobile: small buffers to save data. On a mains-powered TV the opposite
 * trade-off is right — a larger buffer rides out the Wi-Fi dropouts that make a living-room stream
 * stutter. See [buildLoadControl].
 *
 * Everything Media3 exposes is `@UnstableApi`, hence the file-wide opt-in; the annotation is a
 * source-compatibility warning, not a stability statement about the player itself.
 */
@OptIn(UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val playbackHttpClient: OkHttpClient,
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var currentStream: ResolvedStream? = null

    /** Set when the app backgrounds while playing, so playback resumes on return. */
    private var wasPlayingBeforePause = false

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    hasEnded = playbackState == Player.STATE_ENDED,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "Playback failed: " + error.errorCodeName, error)
            _state.update { it.copy(error = mapError(error), isBuffering = false) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.update {
                it.copy(
                    audioTracks = readAudioTracks(tracks),
                    subtitleTracks = readSubtitleTracks(tracks),
                    selectedAudioTrackId = selectedTrackId(tracks, C.TRACK_TYPE_AUDIO),
                    selectedSubtitleTrackId = selectedTrackId(tracks, C.TRACK_TYPE_TEXT),
                )
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            // Some live streams only reveal a title once the manifest is read.
            mediaMetadata.title?.toString()?.let { title ->
                _state.update { it.copy(streamTitle = title) }
            }
        }
    }

    /** Creates the player if needed. Idempotent, so a screen can call it on every entry. */
    fun ensurePlayer(preferredQuality: PreferredQuality): ExoPlayer {
        player?.let { return it }

        val selector = DefaultTrackSelector(context)
        // `setParameters(TrackSelectionParameters)` rather than the `parameters` property: the
        // fluent builder methods return the base `TrackSelectionParameters.Builder`, so a chain
        // starting from `Parameters.Builder` no longer satisfies the narrower property type.
        selector.setParameters(
            selector.buildUponParameters()
                .setPreferredTextLanguage(null)
                .applyQualityCap(preferredQuality)
                .build(),
        )
        trackSelector = selector

        val created = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setTrackSelector(selector)
            .setLoadControl(buildLoadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // Honour audio focus: a notification or a voice assistant must be able to duck us.
                /* handleAudioFocus = */ true,
            )
            // Keeps the screen awake for the duration of playback, and only then.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply { addListener(listener) }

        player = created
        return created
    }

    /**
     * Data source stack.
     *
     * OkHttp rather than Media3's built-in HTTP stack, so playback shares the app's connection pool,
     * timeouts and TLS policy instead of maintaining a second, differently-configured one.
     * [DefaultDataSource] wraps it so `file://` and `content://` also work, which matters for a
     * locally-picked playlist.
     */
    private fun dataSourceFactory(): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(playbackHttpClient)
            .setUserAgent(USER_AGENT)
        return DefaultDataSource.Factory(context, httpFactory)
    }

    /**
     * Larger buffers than the mobile defaults.
     *
     * A TV is mains-powered and on a fixed connection, so buffering thirty to sixty seconds is free
     * and absorbs the brief Wi-Fi dropouts that otherwise show up as a stutter every few minutes.
     * The rebuffer threshold is kept low so recovery after a dropout is quick.
     */
    private fun buildLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ MIN_BUFFER_MILLIS,
            /* maxBufferMs = */ MAX_BUFFER_MILLIS,
            /* bufferForPlaybackMs = */ BUFFER_FOR_PLAYBACK_MILLIS,
            /* bufferForPlaybackAfterRebufferMs = */ BUFFER_AFTER_REBUFFER_MILLIS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /**
     * Loads a resolved stream.
     *
     * The container is declared explicitly where known rather than left to extension sniffing: an
     * Xtream HLS URL frequently has no `.m3u8` suffix, and without the MIME type ExoPlayer would try
     * to play the manifest as a progressive file and fail with a confusing decoder error.
     */
    fun load(
        stream: ResolvedStream,
        subtitlePreferences: SubtitlePreferences,
        preferredQuality: PreferredQuality,
        playWhenReady: Boolean = true,
    ) {
        val exo = ensurePlayer(preferredQuality)
        currentStream = stream
        _state.update {
            PlayerState(
                streamTitle = stream.source.label,
                isLive = stream.source.isLive,
                isBuffering = true,
            )
        }

        val builder = MediaItem.Builder()
            .setUri(stream.uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(stream.source.label).build(),
            )

        mimeTypeFor(stream.source.container)?.let { builder.setMimeType(it) }

        if (stream.subtitleTracks.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                stream.subtitleTracks.mapNotNull { track -> track.toConfiguration() },
            )
        }

        applyPreferences(subtitlePreferences, preferredQuality)

        exo.setMediaItem(builder.build(), stream.startPositionMillis)
        exo.prepare()
        exo.playWhenReady = playWhenReady
    }

    /** Applies subtitle and quality preferences to the live player. */
    fun applyPreferences(
        subtitlePreferences: SubtitlePreferences,
        preferredQuality: PreferredQuality,
    ) {
        val selector = trackSelector ?: return
        selector.setParameters(
            selector.buildUponParameters()
                .setPreferredTextLanguage(
                    subtitlePreferences.preferredLanguage.takeIf {
                        subtitlePreferences.enabledByDefault
                    },
                )
                .setSelectUndeterminedTextLanguage(false)
                // Off unless the user asked for them, or the stream forces them.
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlePreferences.enabledByDefault)
                .applyQualityCap(preferredQuality)
                .build(),
        )
    }

    private fun TrackSelectionParameters.Builder.applyQualityCap(
        quality: PreferredQuality,
    ): TrackSelectionParameters.Builder {
        val maxHeight = quality.maxHeight
        return if (maxHeight == null) {
            clearVideoSizeConstraints()
        } else {
            // Width is left effectively unbounded: capping by height alone is the correct way to
            // express "no more than 1080p" for any aspect ratio.
            setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    /**
     * Seeks by a delta.
     *
     * Clamped to the seekable window, so pressing rewind at the start of a stream does nothing
     * rather than restarting it, and fast-forward at the end does not overshoot into STATE_ENDED.
     */
    fun seekBy(deltaMillis: Long) {
        val exo = player ?: return
        if (!exo.isCurrentMediaItemSeekable) return
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMillis).coerceAtLeast(0L)
        val clamped = if (duration != C.TIME_UNSET) {
            target.coerceAtMost(duration - SEEK_END_MARGIN_MILLIS)
        } else {
            target
        }
        exo.seekTo(clamped.coerceAtLeast(0L))
    }

    fun seekTo(positionMillis: Long) {
        val exo = player ?: return
        if (!exo.isCurrentMediaItemSeekable) return
        exo.seekTo(positionMillis.coerceAtLeast(0L))
    }

    fun selectAudioTrack(trackId: String?) {
        selectTrack(C.TRACK_TYPE_AUDIO, trackId)
    }

    /** @param trackId null turns subtitles off. */
    fun selectSubtitleTrack(trackId: String?) {
        val selector = trackSelector ?: return
        if (trackId == null) {
            selector.setParameters(
                selector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build(),
            )
            _state.update { it.copy(selectedSubtitleTrackId = null) }
            return
        }
        selector.setParameters(
            selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build(),
        )
        selectTrack(C.TRACK_TYPE_TEXT, trackId)
    }

    private fun selectTrack(trackType: Int, trackId: String?) {
        val exo = player ?: return
        val selector = trackSelector ?: return
        if (trackId == null) return

        for (group in exo.currentTracks.groups) {
            if (group.type != trackType) continue
            for (index in 0 until group.length) {
                if (formatId(group.mediaTrackGroup.id, index) != trackId) continue
                selector.setParameters(
                    selector.buildUponParameters()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, listOf(index)),
                        )
                        .build(),
                )
                return
            }
        }
    }

    /** Current position, polled by the UI. Not a Flow: ExoPlayer has no position callback. */
    fun currentPositionMillis(): Long = player?.currentPosition ?: 0L

    fun durationMillis(): Long = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L

    fun bufferedPositionMillis(): Long = player?.bufferedPosition ?: 0L

    fun isSeekable(): Boolean = player?.isCurrentMediaItemSeekable == true

    /** Retries the current stream in place, for the "Try again" action after an error. */
    fun retry() {
        val exo = player ?: return
        _state.update { it.copy(error = null, isBuffering = true) }
        exo.prepare()
        exo.play()
    }

    /** Called when the app backgrounds. Remembers whether to resume. */
    fun onBackground() {
        val exo = player ?: return
        wasPlayingBeforePause = exo.isPlaying
        exo.pause()
    }

    /**
     * Called when the app returns to the foreground.
     *
     * Resumes only if playback was interrupted by backgrounding. A user who deliberately paused,
     * then went to the home screen and came back, expects to still be paused.
     */
    fun onForeground() {
        if (wasPlayingBeforePause) {
            player?.play()
            wasPlayingBeforePause = false
        }
    }

    /** Releases the player. Must be called, or the surface and buffers leak. */
    fun release() {
        player?.removeListener(listener)
        player?.release()
        player = null
        trackSelector = null
        currentStream = null
        _state.value = PlayerState()
    }

    // ---- Track reading ------------------------------------------------------

    private fun readAudioTracks(tracks: Tracks): List<AudioTrack> =
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    if (!group.isTrackSupported(index)) return@mapNotNull null
                    val format = group.getTrackFormat(index)
                    AudioTrack(
                        id = formatId(group.mediaTrackGroup.id, index),
                        language = format.language,
                        label = format.label
                            ?: languageLabel(format.language)
                            ?: ("Track " + (index + 1)),
                        channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
                        codec = format.codecs,
                    )
                }
            }

    private fun readSubtitleTracks(tracks: Tracks): List<SubtitleTrack> =
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    if (!group.isTrackSupported(index)) return@mapNotNull null
                    val format = group.getTrackFormat(index)
                    val forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
                    // ExoPlayer does not expose an SDH flag, so it is inferred from the label,
                    // which is where providers put it in practice.
                    val label = format.label ?: languageLabel(format.language) ?: "Subtitles"
                    SubtitleTrack(
                        id = formatId(group.mediaTrackGroup.id, index),
                        language = format.language,
                        label = label,
                        isForced = forced,
                        isSdh = label.contains("sdh", ignoreCase = true) ||
                            label.contains("cc", ignoreCase = true),
                        mimeType = format.sampleMimeType,
                    )
                }
            }

    private fun selectedTrackId(tracks: Tracks, trackType: Int): String? {
        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) {
                    return formatId(group.mediaTrackGroup.id, index)
                }
            }
        }
        return null
    }

    /** Group id plus index, which is stable across `onTracksChanged` for the same stream. */
    private fun formatId(groupId: String?, index: Int): String =
        (groupId ?: "group") + "#" + index

    private fun languageLabel(language: String?): String? {
        if (language.isNullOrBlank() || language == C.LANGUAGE_UNDETERMINED) return null
        return runCatching {
            java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.UK)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun SubtitleTrack.toConfiguration(): MediaItem.SubtitleConfiguration? {
        val uri = sidecarUri ?: return null
        return MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
            .setMimeType(mimeType ?: MimeTypes.TEXT_VTT)
            .setLanguage(language)
            .setLabel(label)
            .setSelectionFlags(if (isForced) C.SELECTION_FLAG_FORCED else 0)
            .build()
    }

    private fun mimeTypeFor(container: StreamContainer): String? = when (container) {
        StreamContainer.HLS -> MimeTypes.APPLICATION_M3U8
        StreamContainer.DASH -> MimeTypes.APPLICATION_MPD
        // Progressive and unknown are both left to ExoPlayer's own sniffing, which is reliable for
        // a real file and cannot be improved on by guessing here.
        StreamContainer.PROGRESSIVE, StreamContainer.UNKNOWN -> null
    }

    /**
     * Maps a playback failure onto the app's error taxonomy.
     *
     * The distinction that matters to a user is between "your connection dropped", "this stream is
     * gone", and "this device cannot decode it" — three problems with three different remedies, all
     * of which ExoPlayer reports as a `PlaybackException`.
     */
    internal fun mapError(error: PlaybackException): AppError = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> AppError.Network("The connection to the stream was lost.")

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            AppError.Expired("The stream link is no longer valid. Choose a source again.")

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            AppError.NotFound("That stream is no longer available.")

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        -> AppError.Unauthorised("Access to that stream was refused.", refreshable = false)

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> AppError.UnsupportedFormat("This stream's format could not be read.")

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> AppError.UnsupportedFormat("This device cannot decode that stream.")

        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        -> AppError.UnsupportedFormat(
            // Stated plainly: the app does not circumvent DRM, so a protected stream we hold no
            // licence for simply cannot play.
            "That stream is protected and SuStream holds no licence for it.",
        )

        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
            AppError.Expired("The live stream moved on. Rejoining from the current point.")

        else -> AppError.Unknown("Playback stopped unexpectedly.")
    }

    private companion object {
        const val USER_AGENT = "SuStream/1.0 (Android TV)"

        /** 30 s minimum buffer: generous for a mains-powered device on a fixed connection. */
        const val MIN_BUFFER_MILLIS = 30_000
        const val MAX_BUFFER_MILLIS = 60_000

        /** Start playing as soon as 2.5 s is buffered, so the first frame is not slow to appear. */
        const val BUFFER_FOR_PLAYBACK_MILLIS = 2_500

        /** Recover quickly after a dropout rather than waiting for the full minimum buffer. */
        const val BUFFER_AFTER_REBUFFER_MILLIS = 5_000

        /** Keeps a fast-forward at the end of a stream from tipping into STATE_ENDED. */
        const val SEEK_END_MARGIN_MILLIS = 1_000L
    }
}

/** Observable player state. */
data class PlayerState(
    val streamTitle: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false,
    val hasEnded: Boolean = false,
    val error: AppError? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null,
) {
    val hasAlternativeAudio: Boolean get() = audioTracks.size > 1
    val hasSubtitles: Boolean get() = subtitleTracks.isNotEmpty()
}
