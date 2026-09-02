package com.sustream.tv.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.Channel
import com.sustream.tv.domain.model.ChannelCategory
import com.sustream.tv.domain.model.ChannelSchedule
import com.sustream.tv.domain.model.Playlist
import com.sustream.tv.domain.model.PlaylistOrigin
import com.sustream.tv.core.log.Secret
import com.sustream.tv.domain.repository.EpgRepository
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * State holder for the Live TV hub.
 *
 * Playlist and channel data stay as repository flows so a completed add, refresh or favourite
 * operation updates the visible list without the screen having to re-query Room itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvViewModel(
    private val playlistRepository: IptvPlaylistRepository,
    private val epgRepository: EpgRepository,
) : ViewModel() {

    private val selectedPlaylistId = MutableStateFlow<String?>(null)
    private val selectedCategory = MutableStateFlow(ChannelCategory.ALL)
    private val actionError = MutableStateFlow<AppError?>(null)
    private val refreshingPlaylistIds = MutableStateFlow<Set<String>>(emptySet())

    private val playlists = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    init {
        viewModelScope.launch {
            playlists.collect { available ->
                if (available.none { it.id == selectedPlaylistId.value }) {
                    selectedPlaylistId.value = available.firstOrNull()?.id
                    selectedCategory.value = ChannelCategory.ALL
                }
            }
        }
    }

    private val channels = combine(selectedPlaylistId, selectedCategory) { playlistId, category ->
        playlistId to category
    }.flatMapLatest { (playlistId, category) ->
        playlistRepository.observeChannels(playlistId, category)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val categories = selectedPlaylistId.flatMapLatest { playlistId ->
        playlistRepository.observeCategories(playlistId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val schedules = channels.flatMapLatest { list ->
        epgRepository.observeSchedules(list.mapNotNull(Channel::tvgId))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        emptyMap(),
    )

    private val favouriteChannels = playlistRepository.observeFavouriteChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val content = combine(
        playlists,
        selectedPlaylistId,
        selectedCategory,
        channels,
        categories,
    ) { availablePlaylists, requestedPlaylistId, category, channelList, categoryList ->
        val playlistId = requestedPlaylistId?.takeIf { requested ->
            availablePlaylists.any { it.id == requested }
        } ?: availablePlaylists.firstOrNull()?.id

        LiveTvContent(
            playlists = availablePlaylists,
            selectedPlaylistId = playlistId,
            selectedCategory = category,
            channels = channelList,
            categories = categoryList,
        )
    }

    private val feedback = combine(
        schedules,
        favouriteChannels,
        actionError,
        refreshingPlaylistIds,
    ) { schedules, favourites, error, refreshing ->
        LiveTvFeedback(
            schedules = schedules,
            favouriteChannels = favourites,
            error = error,
            refreshingPlaylistIds = refreshing,
        )
    }

    val state: StateFlow<LiveTvUiState> = combine(content, feedback) { content, feedback ->
        LiveTvUiState(
            playlists = content.playlists,
            selectedPlaylistId = content.selectedPlaylistId,
            selectedCategory = content.selectedCategory,
            channels = content.channels,
            categories = content.categories,
            schedules = feedback.schedules,
            favouriteChannels = feedback.favouriteChannels,
            error = feedback.error,
            refreshingPlaylistIds = feedback.refreshingPlaylistIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LiveTvUiState())

    fun selectPlaylist(playlistId: String?) {
        selectedPlaylistId.value = playlistId
        selectedCategory.value = ChannelCategory.ALL
    }

    fun selectCategory(category: String) {
        selectedCategory.value = category
    }

    fun toggleFavourite(channel: Channel) {
        viewModelScope.launch {
            record(
                playlistRepository.setChannelFavourite(channel.id, !channel.isFavourite),
            )
        }
    }

    fun refreshPlaylist(playlistId: String) {
        viewModelScope.launch {
            refreshingPlaylistIds.value += playlistId
            record(playlistRepository.refreshPlaylist(playlistId))
            refreshingPlaylistIds.value -= playlistId
        }
    }

    fun addPlaylist(
        name: String,
        origin: PlaylistOrigin,
        epgUrl: String?,
        password: Secret? = null,
        cleartextAcknowledged: Boolean,
        onAdded: () -> Unit,
    ) {
        viewModelScope.launch {
            when (
                val result = playlistRepository.addPlaylist(
                    name = name,
                    origin = origin,
                    epgUrl = epgUrl,
                    password = password,
                    cleartextAcknowledged = cleartextAcknowledged,
                )
            ) {
                is AppResult.Success -> {
                    selectedPlaylistId.value = result.value.id
                    selectedCategory.value = ChannelCategory.ALL
                    actionError.value = null
                    onAdded()
                }
                is AppResult.Failure -> actionError.value = result.error
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            record(playlistRepository.deletePlaylist(playlistId))
        }
    }

    fun clearError() {
        actionError.value = null
    }

    private fun record(result: AppResult<*>) {
        actionError.value = (result as? AppResult.Failure)?.error
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private data class LiveTvContent(
    val playlists: List<Playlist>,
    val selectedPlaylistId: String?,
    val selectedCategory: String,
    val channels: List<Channel>,
    val categories: List<ChannelCategory>,
)

private data class LiveTvFeedback(
    val schedules: Map<String, ChannelSchedule>,
    val favouriteChannels: List<Channel>,
    val error: AppError?,
    val refreshingPlaylistIds: Set<String>,
)

data class LiveTvUiState(
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val selectedCategory: String = ChannelCategory.ALL,
    val channels: List<Channel> = emptyList(),
    val categories: List<ChannelCategory> = emptyList(),
    val schedules: Map<String, ChannelSchedule> = emptyMap(),
    val favouriteChannels: List<Channel> = emptyList(),
    val error: AppError? = null,
    val refreshingPlaylistIds: Set<String> = emptySet(),
)
