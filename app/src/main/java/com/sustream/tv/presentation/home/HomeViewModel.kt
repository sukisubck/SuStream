package com.sustream.tv.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.ContinueWatchingItem
import com.sustream.tv.domain.model.HomeRail
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.RailId
import com.sustream.tv.domain.repository.HistoryRepository
import com.sustream.tv.domain.repository.NotificationRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.domain.repository.WatchlistRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home screen.
 *
 * ## Rails load independently
 *
 * Each rail is fetched concurrently and rendered as it arrives, with its own loading and error
 * state. One rail failing does not blank the screen — a home screen that shows nothing because
 * `tv/popular` returned a 500 is a worse experience than one showing six of seven rails. This is why
 * [HomeRail] carries its own error rather than the screen having a single error state.
 *
 * ## Local rails first
 *
 * Continue Watching and Watchlist come from Room and are collected as flows, so they appear
 * immediately and stay live — finishing an episode updates the rail without a refresh. They also
 * work with no network at all, which is what makes the offline state useful rather than empty.
 */
class HomeViewModel(
    private val tmdbRepository: TmdbRepository,
    private val watchlistRepository: WatchlistRepository,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        observeLocalRails()
        observeWatchlistIds()
        loadCatalogueRails()
        viewModelScope.launch {
            // Non-fatal: artwork falls back to documented defaults if this fails.
            tmdbRepository.refreshImageConfiguration()
        }
        viewModelScope.launch { notificationRepository.refresh() }
    }

    /**
     * Continue Watching and Watchlist, combined so a single state update carries both.
     *
     * Combining rather than collecting separately avoids two recompositions per change, which on a
     * screen holding several hundred cards is worth avoiding.
     */
    private fun observeLocalRails() {
        viewModelScope.launch {
            combine(
                historyRepository.observeContinueWatching(),
                watchlistRepository.observeWatchlist(),
            ) { continueWatching, watchlist ->
                continueWatching to watchlist.map { it.item }
            }.collect { (continueWatching, watchlistItems) ->
                _state.update {
                    it.copy(
                        continueWatching = continueWatching,
                        watchlist = watchlistItems,
                    )
                }
            }
        }
    }

    private fun observeWatchlistIds() {
        viewModelScope.launch {
            watchlistRepository.observeWatchlistIds().collect { ids ->
                _state.update { it.copy(watchlistIds = ids) }
            }
        }
    }

    fun refresh() {
        loadCatalogueRails()
        viewModelScope.launch { notificationRepository.refresh() }
    }

    private fun loadCatalogueRails() {
        viewModelScope.launch {
            val order = railOrder()

            _state.update { current ->
                current.copy(
                    isInitialLoad = current.rails.isEmpty(),
                    rails = order.map { railId ->
                        current.rails.firstOrNull { it.id == railId }
                            ?: HomeRail(id = railId, items = emptyList(), isLoading = true)
                    },
                )
            }

            coroutineScope {
                order
                    .map { railId -> railId to async { tmdbRepository.feed(railId) } }
                    .forEach { (railId, deferred) ->
                        val result = deferred.await()
                        val rail = when (result) {
                            is AppResult.Success -> HomeRail(
                                id = railId,
                                items = result.value.items,
                                isLoading = false,
                            )

                            is AppResult.Failure -> HomeRail(
                                id = railId,
                                items = emptyList(),
                                error = result.error,
                                isLoading = false,
                            )
                        }

                        // Each rail is published as it lands, so the screen fills progressively
                        // rather than all at once after the slowest request.
                        _state.update { current ->
                            current.copy(
                                isInitialLoad = false,
                                rails = current.rails.map { if (it.id == railId) rail else it },
                                featured = current.featured ?: rail.items.firstOrNull(),
                            )
                        }
                    }
            }
        }
    }

    /**
     * Rail order, adjusted by the user's stated interests.
     *
     * Interests only reorder — they never remove a rail. Hiding content because of an onboarding
     * answer is a decision the user did not knowingly make.
     */
    private suspend fun railOrder(): List<RailId> {
        val interests = settingsRepository.current().interests.map { it.lowercase() }
        val base = listOf(
            RailId.TRENDING_FILMS,
            RailId.TRENDING_TV,
            RailId.POPULAR_FILMS,
            RailId.POPULAR_TV,
            RailId.NEW_FILMS,
            RailId.ON_THE_AIR_TV,
        )
        if (interests.isEmpty()) return base

        val prefersTv = interests.any { it.contains("tv") || it.contains("series") }
        return if (prefersTv) {
            base.sortedBy { railId ->
                if (railId.name.contains("TV") || railId.name.contains("AIR")) 0 else 1
            }
        } else {
            base
        }
    }

    /** Updates the hero as focus moves through the top rail, as the prototype's hero does. */
    fun onFeaturedFocused(item: MediaItem) {
        _state.update { it.copy(featured = item) }
    }

    fun toggleWatchlist(item: MediaItem) {
        viewModelScope.launch { watchlistRepository.toggle(item) }
    }

    fun isInWatchlist(id: MediaId): Boolean = _state.value.watchlistIds.contains(id)
}

data class HomeUiState(
    /** The hero title. Follows focus in the first rail once loading finishes. */
    val featured: MediaItem? = null,
    val rails: List<HomeRail> = emptyList(),
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val watchlist: List<MediaItem> = emptyList(),
    val watchlistIds: Set<MediaId> = emptySet(),
    val isInitialLoad: Boolean = true,
) {
    /**
     * True when there is nothing at all to show.
     *
     * Distinguished from "still loading" so the empty state explains what to do rather than showing
     * a spinner that will never resolve into content.
     */
    val isEmpty: Boolean
        get() = !isInitialLoad &&
            featured == null &&
            continueWatching.isEmpty() &&
            watchlist.isEmpty() &&
            rails.all { it.isEmpty && !it.isLoading }

    /** True when every catalogue rail failed, which usually means the network is down. */
    val allRailsFailed: Boolean
        get() = rails.isNotEmpty() && rails.all { it.error != null }
}
