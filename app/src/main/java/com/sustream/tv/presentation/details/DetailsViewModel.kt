package com.sustream.tv.presentation.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.Episode
import com.sustream.tv.domain.model.EpisodeRef
import com.sustream.tv.domain.model.MediaDetails
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.PlaybackProgress
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.Season
import com.sustream.tv.domain.model.SourceAvailability
import com.sustream.tv.domain.repository.AuthorisedSourceRepository
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.domain.repository.WatchlistRepository
import com.sustream.tv.presentation.common.Loadable
import com.sustream.tv.presentation.common.toLoadableValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Details screen.
 *
 * ## Metadata and availability are separate
 *
 * They load independently and are held in separate state. That separation is the point: TMDB
 * supplies catalogue information, and whether anything can actually *play* is a different question
 * answered by the user's own configured sources. The screen therefore shows the synopsis, cast and
 * seasons immediately, with the Play button appearing only once an authorised source is confirmed.
 *
 * The distinction the brief asks for — between "nothing is configured" and "your services do not
 * carry this" — is [SourceAvailability.NONE_CONFIGURED] versus
 * [SourceAvailability.NO_SOURCE_FOUND]. They need different copy because they need different action
 * from the user.
 */
class DetailsViewModel(
    private val mediaId: MediaId,
    private val tmdbRepository: TmdbRepository,
    private val sourceRepository: AuthorisedSourceRepository,
    private val watchlistRepository: WatchlistRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailsUiState(mediaId = mediaId))
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    /** Held so a rapid season change cancels the previous episode fetch instead of racing it. */
    private var episodeJob: Job? = null
    private var availabilityJob: Job? = null

    init {
        load()
        observeWatchlist()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(details = Loadable.Loading) }

            val result = tmdbRepository.details(mediaId)
            _state.update { it.copy(details = result.toLoadableValue()) }

            val details = (result as? AppResult.Success)?.value ?: return@launch

            // Pick a starting season: the first real one, not the specials bucket, which is what a
            // user opening a show expects to see.
            val firstSeason = details.seasons.firstOrNull { !it.isSpecials }
                ?: details.seasons.firstOrNull()
            if (firstSeason != null) {
                selectSeason(firstSeason.seasonNumber)
            }

            loadProgress()
            checkAvailability()
        }
    }

    private fun observeWatchlist() {
        viewModelScope.launch {
            watchlistRepository.observeContains(mediaId).collect { inWatchlist ->
                _state.update { it.copy(inWatchlist = inWatchlist) }
            }
        }
    }

    private fun loadProgress() {
        viewModelScope.launch {
            val progress = historyRepository.progressFor(mediaId).let {
                (it as? AppResult.Success)?.value
            }
            _state.update { it.copy(progress = progress) }
        }
    }

    /**
     * Checks whether anything can play this title.
     *
     * Distinguishes "nothing configured" up front, because asking the adapters is pointless when
     * none of them has anything to look in, and because the answer the user needs is different.
     */
    fun checkAvailability() {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            _state.update { it.copy(availability = SourceAvailability.CHECKING, sources = emptyList()) }

            if (!sourceRepository.isConfigured()) {
                _state.update { it.copy(availability = SourceAvailability.NONE_CONFIGURED) }
                return@launch
            }

            val details = _state.value.details.valueOrNull
            val request = buildRequest(details) ?: run {
                _state.update { it.copy(availability = SourceAvailability.ERROR) }
                return@launch
            }

            when (val result = sourceRepository.findSources(request)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        sources = result.value,
                        availability = if (result.value.isEmpty()) {
                            SourceAvailability.NO_SOURCE_FOUND
                        } else {
                            SourceAvailability.AVAILABLE
                        },
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(
                        availability = SourceAvailability.ERROR,
                        availabilityError = result.error,
                    )
                }
            }
        }
    }

    /**
     * Builds the playback request the availability check is about.
     *
     * For a show that means the *selected* episode, not the show as a whole: whether season 2
     * episode 4 is available is a different question from whether the series is.
     */
    private fun buildRequest(details: MediaDetails?): PlaybackRequest? {
        val item = details?.item ?: return null
        return when (item.type) {
            MediaType.MOVIE -> PlaybackRequest.Movie(id = item.id, title = item.title)

            MediaType.TV -> {
                val episode = _state.value.selectedEpisode ?: return null
                PlaybackRequest.TvEpisode(
                    ref = EpisodeRef(item.id, episode.seasonNumber, episode.episodeNumber),
                    showTitle = item.title,
                    episodeTitle = episode.name,
                )
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        episodeJob?.cancel()
        _state.update {
            it.copy(
                selectedSeasonNumber = seasonNumber,
                episodes = Loadable.Loading,
                selectedEpisode = null,
            )
        }

        episodeJob = viewModelScope.launch {
            val result = tmdbRepository.episodes(mediaId, seasonNumber)
            _state.update { current ->
                val episodes = when (result) {
                    is AppResult.Success ->
                        if (result.value.isEmpty()) {
                            Loadable.Empty
                        } else {
                            Loadable.Loaded(result.value)
                        }

                    is AppResult.Failure -> Loadable.Failed(result.error)
                }
                current.copy(
                    episodes = episodes,
                    selectedEpisode = (result as? AppResult.Success)?.value?.firstOrNull(),
                )
            }
            // Availability is per-episode for a show, so it is re-checked when the season changes.
            if (_state.value.details.valueOrNull?.isSeries == true) checkAvailability()
        }
    }

    fun selectEpisode(episode: Episode) {
        _state.update { it.copy(selectedEpisode = episode) }
        checkAvailability()
    }

    fun toggleWatchlist() {
        val item = _state.value.details.valueOrNull?.item ?: return
        viewModelScope.launch { watchlistRepository.toggle(item) }
    }

    fun toggleWatched() {
        val item = _state.value.details.valueOrNull?.item ?: return
        val episode = _state.value.selectedEpisode
        viewModelScope.launch {
            val alreadyWatched = _state.value.progress?.isWatched(
                PlaybackProgress.DEFAULT_COMPLETION_THRESHOLD,
            ) == true

            if (alreadyWatched) {
                historyRepository.markUnwatched(item.id, episode?.seasonNumber, episode?.episodeNumber)
            } else {
                historyRepository.markWatched(item, episode?.seasonNumber, episode?.episodeNumber)
            }
            loadProgress()
        }
    }

    fun openSourcePicker() {
        _state.update { it.copy(sourcePickerOpen = true) }
    }

    fun dismissSourcePicker() {
        _state.update { it.copy(sourcePickerOpen = false) }
    }
}

data class DetailsUiState(
    val mediaId: MediaId,
    val details: Loadable<MediaDetails> = Loadable.Idle,
    val episodes: Loadable<List<Episode>> = Loadable.Idle,
    val selectedSeasonNumber: Int? = null,
    val selectedEpisode: Episode? = null,
    val availability: SourceAvailability = SourceAvailability.CHECKING,
    val availabilityError: AppError? = null,
    val sources: List<PlayableSource> = emptyList(),
    val sourcePickerOpen: Boolean = false,
    val inWatchlist: Boolean = false,
    val progress: PlaybackProgress? = null,
) {
    val seasons: List<Season> get() = details.valueOrNull?.seasons.orEmpty()

    val isSeries: Boolean get() = details.valueOrNull?.isSeries == true

    /** The Play button appears only when something authorised can actually serve this. */
    val canPlay: Boolean get() = availability == SourceAvailability.AVAILABLE && sources.isNotEmpty()

    /** Show a source picker rather than playing straight away when there is a real choice. */
    val hasSourceChoice: Boolean get() = sources.size > 1

    val isWatched: Boolean
        get() = progress?.isWatched(PlaybackProgress.DEFAULT_COMPLETION_THRESHOLD) == true

    val resumePositionMillis: Long
        get() = progress?.takeIf { it.isResumable() }?.positionMillis ?: 0L
}
