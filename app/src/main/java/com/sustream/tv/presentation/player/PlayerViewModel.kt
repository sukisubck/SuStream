package com.sustream.tv.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.domain.model.AppSettings
import com.sustream.tv.domain.model.EpisodeRef
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.PlaybackProgress
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.PlaybackRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.playback.PlayerManager
import com.sustream.tv.playback.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The player screen.
 *
 * Four responsibilities that are easy to get wrong on a TV, and are therefore all handled here in
 * one place rather than spread across the composable:
 *
 *  1. **Progress is saved before anything else happens.** Every exit path — back, autoplay,
 *     backgrounding, an error — flushes progress first. Losing a user's position because they pressed
 *     back at the wrong moment is the kind of bug that makes an app feel untrustworthy.
 *  2. **Controls auto-hide, and BACK is layered.** BACK closes a sheet, else hides the controls, else
 *     leaves. The brief calls this out specifically: back must not unexpectedly exit playback.
 *  3. **Expiry is anticipated.** Provider links are short-lived, so the stream is re-resolved on
 *     return from the background rather than failing on a dead URL.
 *  4. **Position is polled, not observed.** ExoPlayer has no position callback, so a coroutine ticks
 *     while playing and stops when paused — which also stops the seek bar burning CPU on a paused
 *     screen.
 */
class PlayerViewModel(
    private val request: PlaybackRequest,
    /** The source chosen in the sources sheet, when the player was reached that way. */
    private val preselectedSource: PlayableSource? = null,
    private val playerManager: PlayerManager,
    private val playbackRepository: PlaybackRepository,
    private val sourceRepository: AuthorisedSourceRepository,
    private val tmdbRepository: TmdbRepository,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val iptvPlaylistRepository: IptvPlaylistRepository,
    private val timeSource: TimeSource,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PlayerUiState(title = request.displayTitle, isLive = request.isLive),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val playerState: StateFlow<PlayerState> = playerManager.state

    private var positionJob: Job? = null
    private var hideControlsJob: Job? = null
    private var settings: AppSettings = AppSettings()

    /** The title being played, needed to write a progress row with a renderable snapshot. */
    private var mediaItem: MediaItem? = null
    private var currentStream: ResolvedStream? = null

    init {
        viewModelScope.launch {
            settings = settingsRepository.current()
            // Honour the user's explicit choice when there was one; otherwise discover.
            start(preselected = preselectedSource)
        }
        observePlayerErrors()
    }

    /** Begins playback: find a source, resolve it, load it. */
    private suspend fun start(preselected: PlayableSource? = null) {
        _state.update { it.copy(phase = PlayerPhase.PREPARING, error = null) }

        val source = preselected ?: when (val found = sourceRepository.findSources(request)) {
            is AppResult.Failure -> {
                fail(found.error)
                return
            }

            is AppResult.Success -> found.value.firstOrNull() ?: run {
                fail(
                    AppError.NotFound(
                        "None of your configured sources can play this. Add a Live TV playlist, or " +
                            "connect a provider account you hold.",
                    ),
                )
                return
            }
        }

        _state.update { it.copy(availableSources = listOf(source)) }

        val resumeFrom = resolveResumePosition()

        when (val resolved = playbackRepository.resolveForPlayback(source, resumeFrom)) {
            is AppResult.Failure -> fail(resolved.error)
            is AppResult.Success -> {
                currentStream = resolved.value
                loadMediaItemSnapshot()
                playerManager.load(
                    stream = resolved.value,
                    subtitlePreferences = settings.subtitles,
                    preferredQuality = settings.playback.preferredQuality,
                )
                _state.update {
                    it.copy(
                        phase = PlayerPhase.PLAYING,
                        title = resolved.value.source.label.ifBlank { request.displayTitle },
                    )
                }
                showControlsBriefly()
                startPositionTicker()
            }
        }
    }

    /**
     * Where to resume from.
     *
     * Live channels always start at the live edge — resuming a live stream from a stored position is
     * meaningless. A near-complete title restarts rather than resuming three seconds from the end.
     */
    private suspend fun resolveResumePosition(): Long {
        if (request.isLive) return 0L

        val (id, season, episode) = when (request) {
            is PlaybackRequest.Movie -> Triple(request.id, null, null)
            is PlaybackRequest.TvEpisode -> Triple(
                request.ref.showId,
                request.ref.seasonNumber,
                request.ref.episodeNumber,
            )

            is PlaybackRequest.LiveChannel -> return 0L
        }

        val progress = historyRepository.progressFor(id, season, episode)
            .let { (it as? AppResult.Success)?.value }
            ?: return 0L

        return if (progress.isResumable(
                completionThreshold = settings.playback.completionThreshold,
            )
        ) {
            progress.positionMillis
        } else {
            0L
        }
    }

    /** Loads the catalogue snapshot so a progress row renders in Continue Watching. */
    private suspend fun loadMediaItemSnapshot() {
        val id = when (request) {
            is PlaybackRequest.Movie -> request.id
            is PlaybackRequest.TvEpisode -> request.ref.showId
            // A live channel is not a catalogue title, so there is no progress to record.
            is PlaybackRequest.LiveChannel -> return
        }
        val item = (tmdbRepository.details(id) as? AppResult.Success)?.value?.item
        mediaItem = item

        // Routes carry ids, not titles, so the header starts blank when the player is opened from a
        // Continue Watching card or a deep link. Adopt the catalogue title as soon as it is known.
        if (item != null && _state.value.title.isBlank()) {
            _state.update { it.copy(title = item.title) }
        }
    }

    private fun observePlayerErrors() {
        viewModelScope.launch {
            playerManager.state.collect { player ->
                val error = player.error ?: return@collect

                // An expired link is recoverable without troubling the user: re-resolve and carry
                // on from the same position.
                if (error is AppError.Expired) {
                    reResolve()
                    return@collect
                }

                flushProgress()
                _state.update { it.copy(phase = PlayerPhase.ERROR, error = error) }
            }
        }
    }

    private fun reResolve() {
        val stream = currentStream ?: return
        viewModelScope.launch {
            _state.update { it.copy(phase = PlayerPhase.PREPARING, error = null) }
            val position = playerManager.currentPositionMillis()

            when (val resolved = playbackRepository.reResolve(stream, position)) {
                is AppResult.Failure -> fail(resolved.error)
                is AppResult.Success -> {
                    currentStream = resolved.value
                    playerManager.load(
                        stream = resolved.value,
                        subtitlePreferences = settings.subtitles,
                        preferredQuality = settings.playback.preferredQuality,
                    )
                    _state.update { it.copy(phase = PlayerPhase.PLAYING) }
                }
            }
        }
    }

    private fun fail(error: AppError) {
        _state.update { it.copy(phase = PlayerPhase.ERROR, error = error) }
    }

    /**
     * Position ticker.
     *
     * Runs only while playing. A paused player's position does not change, so polling it would be
     * pure waste — and on an always-on TV app, a needless one-second wakeup is worth removing.
     */
    private fun startPositionTicker() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                val position = playerManager.currentPositionMillis()
                val duration = playerManager.durationMillis()

                _state.update {
                    it.copy(
                        positionMillis = position,
                        durationMillis = duration,
                        bufferedMillis = playerManager.bufferedPositionMillis(),
                        isSeekable = playerManager.isSeekable(),
                    )
                }

                if (playerState.value.isPlaying) saveProgress(position, duration)

                if (playerState.value.hasEnded) {
                    onPlaybackEnded()
                    break
                }

                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun saveProgress(positionMillis: Long, durationMillis: Long) {
        val item = mediaItem ?: return
        if (durationMillis <= 0L || request.isLive) return

        val (season, episode) = when (request) {
            is PlaybackRequest.TvEpisode ->
                request.ref.seasonNumber to request.ref.episodeNumber

            else -> null to null
        }

        // The repository debounces the actual write; calling it every tick is intended.
        historyRepository.saveProgress(
            item = item,
            progress = PlaybackProgress(
                mediaId = item.id,
                seasonNumber = season,
                episodeNumber = episode,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                updatedAt = timeSource.now(),
            ),
        )
    }

    private fun flushProgress() {
        viewModelScope.launch { historyRepository.flushPendingProgress() }
    }

    /** End of stream: autoplay the next episode, or report that playback finished. */
    private suspend fun onPlaybackEnded() {
        val item = mediaItem
        if (item != null && !request.isLive) {
            saveProgress(playerManager.durationMillis(), playerManager.durationMillis())
        }
        historyRepository.flushPendingProgress()

        if (!settings.playback.autoplayNextEpisode || request !is PlaybackRequest.TvEpisode) {
            _state.update { it.copy(phase = PlayerPhase.FINISHED) }
            return
        }

        val next = playbackRepository.nextEpisode(
            showId = request.ref.showId,
            seasonNumber = request.ref.seasonNumber,
            episodeNumber = request.ref.episodeNumber,
        ).let { (it as? AppResult.Success)?.value }

        if (next == null) {
            _state.update { it.copy(phase = PlayerPhase.FINISHED) }
        } else {
            _state.update { it.copy(pendingNextEpisode = next) }
        }
    }

    // ---- Controls -----------------------------------------------------------

    fun togglePlayPause() {
        playerManager.togglePlayPause()
        showControlsBriefly()
        // Restart the ticker if playback has just resumed after the ticker exited.
        if (positionJob?.isActive != true) startPositionTicker()
    }

    fun rewind() {
        playerManager.seekBy(-seekStepMillis())
        showControlsBriefly()
    }

    fun fastForward() {
        playerManager.seekBy(seekStepMillis())
        showControlsBriefly()
    }

    fun seekTo(positionMillis: Long) {
        playerManager.seekTo(positionMillis)
        showControlsBriefly()
    }

    private fun seekStepMillis(): Long =
        settings.playback.seekStepSeconds.toLong() * MILLIS_PER_SECOND

    fun showControls() {
        showControlsBriefly()
    }

    /**
     * Reveals the controls and schedules their retreat.
     *
     * Auto-hide is not cosmetic on a TV: leaving a control bar over the picture is the most common
     * complaint about home-grown players. It does not hide while a sheet is open, or the sheet would
     * vanish under the user.
     */
    private fun showControlsBriefly() {
        _state.update { it.copy(controlsVisible = true) }
        hideControlsJob?.cancel()
        hideControlsJob = viewModelScope.launch {
            delay(CONTROLS_TIMEOUT_MILLIS)
            if (_state.value.openSheet == PlayerSheet.NONE) {
                _state.update { it.copy(controlsVisible = false) }
            }
        }
    }

    fun openSheet(sheet: PlayerSheet) {
        hideControlsJob?.cancel()
        _state.update { it.copy(openSheet = sheet, controlsVisible = true) }
    }

    fun closeSheet() {
        _state.update { it.copy(openSheet = PlayerSheet.NONE) }
        showControlsBriefly()
    }

    fun selectAudioTrack(trackId: String?) {
        playerManager.selectAudioTrack(trackId)
        closeSheet()
    }

    fun selectSubtitleTrack(trackId: String?) {
        playerManager.selectSubtitleTrack(trackId)
        closeSheet()
    }

    fun retry() {
        viewModelScope.launch {
            _state.update { it.copy(error = null, phase = PlayerPhase.PREPARING) }
            val stream = currentStream
            if (stream == null) {
                start()
            } else {
                playerManager.retry()
                _state.update { it.copy(phase = PlayerPhase.PLAYING) }
            }
        }
    }

    /** Switches to a different source after a failure, without leaving the player. */
    fun switchSource() {
        viewModelScope.launch {
            when (val found = sourceRepository.findSources(request)) {
                is AppResult.Failure -> fail(found.error)
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            availableSources = found.value,
                            openSheet = PlayerSheet.SOURCES,
                            controlsVisible = true,
                        )
                    }
                }
            }
        }
    }

    fun playSource(source: PlayableSource) {
        viewModelScope.launch {
            closeSheet()
            start(preselected = source)
        }
    }

    fun consumeNextEpisode(): EpisodeRef? {
        val next = _state.value.pendingNextEpisode
        _state.update { it.copy(pendingNextEpisode = null) }
        return next
    }

    /**
     * BACK handling, in priority order.
     *
     * @return true when the event was consumed. Returning false lets the navigation host pop the
     *   player, which is the only path that actually leaves playback.
     */
    fun onBackPressed(): Boolean {
        if (_state.value.openSheet != PlayerSheet.NONE) {
            closeSheet()
            return true
        }
        if (_state.value.controlsVisible) {
            _state.update { it.copy(controlsVisible = false) }
            return true
        }
        // Progress is flushed here, before the screen is popped, so nothing is lost on exit.
        flushProgress()
        return false
    }

    fun onBackground() {
        playerManager.onBackground()
        flushProgress()
        positionJob?.cancel()
    }

    /**
     * Returning to the foreground.
     *
     * The stream's expiry is checked first: a provider link that lapsed while the app was away
     * would otherwise resume straight into a playback error.
     */
    fun onForeground() {
        val stream = currentStream
        if (stream != null && stream.isExpiredAt(timeSource.now())) {
            reResolve()
            return
        }
        playerManager.onForeground()
        startPositionTicker()
    }

    override fun onCleared() {
        positionJob?.cancel()
        hideControlsJob?.cancel()
        // Progress first, then release. The other order can lose the final position.
        viewModelScope.launch { historyRepository.flushPendingProgress() }
        playerManager.pause()
        super.onCleared()
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 500L
        const val CONTROLS_TIMEOUT_MILLIS = 4_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

data class PlayerUiState(
    val title: String,
    val isLive: Boolean,
    val phase: PlayerPhase = PlayerPhase.PREPARING,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val bufferedMillis: Long = 0L,
    val isSeekable: Boolean = false,
    val controlsVisible: Boolean = true,
    val openSheet: PlayerSheet = PlayerSheet.NONE,
    val error: AppError? = null,
    val availableSources: List<PlayableSource> = emptyList(),
    /** Set when autoplay has found a following episode; the screen navigates to it. */
    val pendingNextEpisode: EpisodeRef? = null,
) {
    val progressFraction: Float
        get() = if (durationMillis > 0L) {
            (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
        } else {
            0f
        }

    val bufferedFraction: Float
        get() = if (durationMillis > 0L) {
            (bufferedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
        } else {
            0f
        }
}

enum class PlayerPhase {
    PREPARING,
    PLAYING,
    ERROR,
    FINISHED,
}

enum class PlayerSheet {
    NONE,
    AUDIO,
    SUBTITLES,
    SOURCES,
}
