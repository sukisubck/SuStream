package com.sustream.tv.presentation.catalogue

import androidx.lifecycle.ViewModel
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.repository.TmdbRepository
import com.sustream.tv.domain.repository.WatchlistRepository

/**
 * ViewModel for the catalogue grid (movies or TV shows).
 *
 * This is a minimal implementation to unblock the build. The actual logic for loading
 * media items from the TMDB repository should be added here.
 */
class CatalogueViewModel(
    private val mediaType: MediaType,
    private val tmdbRepository: TmdbRepository,
    private val watchlistRepository: WatchlistRepository
) : ViewModel() {
    // TODO: Implement loading of media items based on mediaType (e.g., popular, trending)
}