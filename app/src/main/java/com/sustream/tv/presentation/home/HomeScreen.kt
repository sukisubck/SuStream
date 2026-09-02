package com.sustream.tv.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.di.LocalAppContainer
import com.sustream.tv.core.di.suStreamViewModel
import com.sustream.tv.core.util.Formatters
import com.sustream.tv.designsystem.component.ContinueWatchingCard
import com.sustream.tv.designsystem.component.EmptyState
import com.sustream.tv.designsystem.component.ErrorState
import com.sustream.tv.designsystem.component.InlineError
import com.sustream.tv.designsystem.component.PosterCard
import com.sustream.tv.designsystem.component.Rail
import com.sustream.tv.designsystem.component.SectionHeader
import com.sustream.tv.designsystem.component.SkeletonRail
import com.sustream.tv.designsystem.icon.SuStreamIcons
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.domain.model.ContinueWatchingItem
import com.sustream.tv.domain.model.HomeRail
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaItem
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.RailId
import com.sustream.tv.presentation.components.FeaturedHero

/**
 * Home: a featured hero over a vertical stack of horizontal rails, following the prototype's layout.
 *
 * The whole screen is one [LazyColumn], which matters on a TV: rails below the fold are not composed
 * until focus approaches them, so a home screen with eight rails of twenty cards does not compose a
 * hundred and sixty cards at once on a 1 GB device.
 */
@Composable
fun HomeScreen(
    onOpenDetails: (MediaId) -> Unit,
    onPlayMovie: (MediaId) -> Unit,
    onPlayEpisode: (MediaId, Int, Int) -> Unit,
    onBrowse: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = suStreamViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val colours = SuStreamTheme.colours

    // Poster width in pixels, so the image loader can request the right size bucket rather than
    // downloading an `original` for a 132 dp slot.
    val density = LocalDensity.current
    val posterWidthPx = with(density) { Dimens.posterCardWidth.roundToPx() }
    val backdropWidthPx = with(density) { Dimens.wideCardWidth.roundToPx() }
    val heroWidthPx = with(density) { HERO_IMAGE_WIDTH.roundToPx() }

    when {
        state.allRailsFailed && state.continueWatching.isEmpty() && state.watchlist.isEmpty() -> {
            ErrorState(
                error = state.rails.first().error!!,
                onRetry = viewModel::refresh,
                modifier = modifier,
            )
        }

        state.isEmpty -> {
            EmptyState(
                body = stringResource(R.string.home_empty_body),
                actionLabel = stringResource(R.string.action_refresh),
                onAction = viewModel::refresh,
                modifier = modifier,
            )
        }

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(colours.background),
            verticalArrangement = Arrangement.spacedBy(Dimens.railBottomSpacing),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = Dimens.overscanVertical,
            ),
        ) {
            item(key = "hero") {
                FeaturedHero(
                    item = state.featured,
                    backdropUrl = container.imageUrlBuilder.backdrop(
                        state.featured?.backdropPath ?: state.featured?.posterPath,
                        heroWidthPx,
                    ),
                    inWatchlist = state.featured?.let { state.watchlistIds.contains(it.id) } == true,
                    onPlay = { item ->
                        when (item.type) {
                            MediaType.MOVIE -> onPlayMovie(item.id)
                            // A show has no single thing to play, so the hero's primary action opens
                            // Details, where a season and episode can be chosen.
                            MediaType.TV -> onOpenDetails(item.id)
                        }
                    },
                    onToggleWatchlist = viewModel::toggleWatchlist,
                    onMoreInfo = { onOpenDetails(it.id) },
                    isLoading = state.isInitialLoad,
                )
            }

            continueWatchingRail(
                items = state.continueWatching,
                backdropUrlFor = { item ->
                    container.imageUrlBuilder.backdrop(
                        item.item.backdropPath ?: item.item.posterPath,
                        backdropWidthPx,
                    )
                },
                onResume = { entry ->
                    val season = entry.progress.seasonNumber
                    val episode = entry.progress.episodeNumber
                    if (season != null && episode != null) {
                        onPlayEpisode(entry.item.id, season, episode)
                    } else {
                        onPlayMovie(entry.item.id)
                    }
                },
            )

            if (state.watchlist.isNotEmpty()) {
                posterRail(
                    railKey = "rail-watchlist",
                    titleRes = R.string.home_rail_watchlist,
                    icon = SuStreamIcons.Bookmark,
                    items = state.watchlist,
                    watchlistIds = state.watchlistIds,
                    posterUrlFor = { container.imageUrlBuilder.poster(it.posterPath, posterWidthPx) },
                    onItemClick = onOpenDetails,
                    onViewAll = null,
                )
            }

            state.rails.forEach { rail ->
                catalogueRail(
                    rail = rail,
                    watchlistIds = state.watchlistIds,
                    posterUrlFor = { container.imageUrlBuilder.poster(it.posterPath, posterWidthPx) },
                    onItemClick = onOpenDetails,
                    onItemFocused = { item ->
                        // Only the first rail drives the hero; letting every rail do so would make
                        // the backdrop change as the user scrolled far down the page.
                        if (rail.id == state.rails.firstOrNull()?.id) {
                            viewModel.onFeaturedFocused(item)
                        }
                    },
                    onViewAll = onBrowse,
                    onRetry = viewModel::refresh,
                )
            }
        }
    }
}

private fun LazyListScope.continueWatchingRail(
    items: List<ContinueWatchingItem>,
    backdropUrlFor: (ContinueWatchingItem) -> String?,
    onResume: (ContinueWatchingItem) -> Unit,
) {
    if (items.isEmpty()) return

    item(key = "rail-continue") {
        Column {
            SectionHeader(
                title = stringResource(R.string.home_rail_continue_watching),
                icon = SuStreamIcons.Clock,
                iconTint = SuStreamTheme.colours.warning,
                trailing = {
                    Text(
                        text = Formatters.count(items.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = SuStreamTheme.colours.textTertiary,
                    )
                },
                modifier = Modifier.padding(
                    horizontal = Dimens.overscanHorizontal,
                    vertical = Dimens.railHeaderSpacing,
                ),
            )
            Rail(
                railKey = "rail-continue",
                items = items,
                key = { _, entry -> entry.progress.key },
            ) { _, entry, itemModifier ->
                ContinueWatchingCard(
                    entry = entry,
                    backdropUrl = backdropUrlFor(entry),
                    onClick = { onResume(entry) },
                    modifier = itemModifier,
                )
            }
        }
    }
}

private fun LazyListScope.posterRail(
    railKey: String,
    titleRes: Int,
    icon: ImageVector,
    items: List<MediaItem>,
    watchlistIds: Set<MediaId>,
    posterUrlFor: (MediaItem) -> String?,
    onItemClick: (MediaId) -> Unit,
    onViewAll: (() -> Unit)?,
    onItemFocused: (MediaItem) -> Unit = {},
) {
    item(key = railKey) {
        Column {
            SectionHeader(
                title = stringResource(titleRes),
                icon = icon,
                modifier = Modifier.padding(
                    horizontal = Dimens.overscanHorizontal,
                    vertical = Dimens.railHeaderSpacing,
                ),
                trailing = onViewAll?.let {
                    {
                        com.sustream.tv.designsystem.component.TextActionButton(
                            text = stringResource(R.string.action_view_all),
                            onClick = it,
                        )
                    }
                },
            )
            Rail(
                railKey = railKey,
                items = items,
                key = { _, item -> item.id.value },
                onItemFocused = { _, item -> onItemFocused(item) },
            ) { _, item, itemModifier ->
                PosterCard(
                    item = item,
                    posterUrl = posterUrlFor(item),
                    inWatchlist = watchlistIds.contains(item.id),
                    onClick = { onItemClick(item.id) },
                    modifier = itemModifier,
                )
            }
        }
    }
}

private fun LazyListScope.catalogueRail(
    rail: HomeRail,
    watchlistIds: Set<MediaId>,
    posterUrlFor: (MediaItem) -> String?,
    onItemClick: (MediaId) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    onViewAll: (MediaType) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "rail-" + rail.id.name) {
        Column {
            SectionHeader(
                title = stringResource(rail.id.titleRes()),
                icon = rail.id.icon(),
                iconTint = SuStreamTheme.colours.primary,
                modifier = Modifier.padding(
                    horizontal = Dimens.overscanHorizontal,
                    vertical = Dimens.railHeaderSpacing,
                ),
                trailing = {
                    rail.id.mediaTypeOrNull()?.let { type ->
                        com.sustream.tv.designsystem.component.TextActionButton(
                            text = stringResource(R.string.action_view_all),
                            onClick = { onViewAll(type) },
                        )
                    }
                },
            )

            when {
                rail.isLoading -> SkeletonRail()

                rail.error != null -> InlineError(error = rail.error, onRetry = onRetry)

                rail.isEmpty -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EMPTY_RAIL_HEIGHT)
                        .padding(horizontal = Dimens.overscanHorizontal),
                ) {
                    Text(
                        text = stringResource(R.string.state_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SuStreamTheme.colours.textTertiary,
                    )
                }

                else -> Rail(
                    railKey = "rail-" + rail.id.name,
                    items = rail.items,
                    key = { _, item -> item.id.value },
                    onItemFocused = { _, item -> onItemFocused(item) },
                ) { _, item, itemModifier ->
                    PosterCard(
                        item = item,
                        posterUrl = posterUrlFor(item),
                        inWatchlist = watchlistIds.contains(item.id),
                        onClick = { onItemClick(item.id) },
                        modifier = itemModifier,
                    )
                }
            }
        }
    }
}

private fun RailId.titleRes(): Int = when (this) {
    RailId.CONTINUE_WATCHING -> R.string.home_rail_continue_watching
    RailId.WATCHLIST -> R.string.home_rail_watchlist
    RailId.TRENDING_FILMS -> R.string.home_rail_trending_films
    RailId.TRENDING_TV -> R.string.home_rail_trending_tv
    RailId.POPULAR_FILMS -> R.string.home_rail_popular_films
    RailId.POPULAR_TV -> R.string.home_rail_popular_tv
    RailId.NEW_FILMS -> R.string.home_rail_new_films
    RailId.ON_THE_AIR_TV -> R.string.home_rail_new_tv
}

private fun RailId.icon(): ImageVector = when (this) {
    RailId.CONTINUE_WATCHING -> SuStreamIcons.Clock
    RailId.WATCHLIST -> SuStreamIcons.Bookmark
    RailId.TRENDING_FILMS, RailId.TRENDING_TV -> SuStreamIcons.TrendingUp
    RailId.POPULAR_FILMS, RailId.NEW_FILMS -> SuStreamIcons.Film
    RailId.POPULAR_TV, RailId.ON_THE_AIR_TV -> SuStreamIcons.Tv
}

private fun RailId.mediaTypeOrNull(): MediaType? = when (this) {
    RailId.TRENDING_FILMS, RailId.POPULAR_FILMS, RailId.NEW_FILMS -> MediaType.MOVIE
    RailId.TRENDING_TV, RailId.POPULAR_TV, RailId.ON_THE_AIR_TV -> MediaType.TV
    RailId.CONTINUE_WATCHING, RailId.WATCHLIST -> null
}

private val HERO_IMAGE_WIDTH = 960.dp
private val EMPTY_RAIL_HEIGHT = 48.dp
