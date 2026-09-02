package com.sustream.tv.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.di.LocalAppContainer
import com.sustream.tv.core.util.Formatters
import com.sustream.tv.designsystem.component.Badge
import com.sustream.tv.designsystem.component.ChipRow
import com.sustream.tv.designsystem.component.EpisodeCard
import com.sustream.tv.designsystem.component.ErrorState
import com.sustream.tv.designsystem.component.IconOnlyButton
import com.sustream.tv.designsystem.component.LoadingState
import com.sustream.tv.designsystem.component.OutlineBadge
import com.sustream.tv.designsystem.component.PrimaryButton
import com.sustream.tv.designsystem.component.Rail
import com.sustream.tv.designsystem.component.RatingPill
import com.sustream.tv.designsystem.component.RemoteImage
import com.sustream.tv.designsystem.component.SecondaryButton
import com.sustream.tv.designsystem.component.SectionHeader
import com.sustream.tv.designsystem.component.SkeletonRail
import com.sustream.tv.designsystem.component.heroScrimBrushes
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.icon.SuStreamIcons
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.designsystem.theme.TextLimits
import com.sustream.tv.domain.model.CastMember
import com.sustream.tv.domain.model.Episode
import com.sustream.tv.domain.model.MediaDetails
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.Season
import com.sustream.tv.domain.model.SourceAvailability
import com.sustream.tv.presentation.common.Loadable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow

/**
 * Details: backdrop hero, metadata, availability, actions, seasons and episodes, cast.
 *
 * The important behaviour here is that the **Play button only exists when something can play**.
 * Everything else on the screen comes from TMDB and is always available; playback comes from the
 * user's own configured sources and frequently is not. Showing a Play button that then fails is the
 * dishonesty this screen is built to avoid — see [AvailabilityPanel].
 */
@Composable
fun DetailsScreen(
    mediaId: MediaId,
    onPlayMovie: (MediaId, PlayableSource) -> Unit,
    onPlayEpisode: (MediaId, Int, Int, PlayableSource) -> Unit,
    onAddPlaylist: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val viewModel: DetailsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        // Keyed by media id so navigating from one title to another creates a fresh view model
        // rather than reusing the previous title's state.
        key = "details-" + mediaId.value,
        factory = container.detailsViewModelFactory(mediaId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colours = SuStreamTheme.colours

    val density = LocalDensity.current
    val backdropWidthPx = with(density) { BACKDROP_WIDTH.roundToPx() }
    val posterWidthPx = with(density) { DETAIL_POSTER_WIDTH.roundToPx() }
    val stillWidthPx = with(density) { Dimens.wideCardWidth.roundToPx() }
    val castWidthPx = with(density) { Dimens.castPortraitSize.roundToPx() }

    when (val details = state.details) {
        Loadable.Idle, Loadable.Loading -> LoadingState(modifier = modifier)

        is Loadable.Failed -> ErrorState(
            error = details.error,
            onRetry = viewModel::load,
            modifier = modifier,
        )

        Loadable.Empty -> ErrorState(
            error = com.sustream.tv.core.result.AppError.NotFound(),
            modifier = modifier,
        )

        is Loadable.Loaded -> {
            val value = details.value
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .background(colours.background),
                verticalArrangement = Arrangement.spacedBy(Dimens.space6),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = Dimens.overscanVertical,
                ),
            ) {
                item(key = "hero") {
                    DetailsHero(
                        details = value,
                        backdropUrl = container.imageUrlBuilder.backdrop(
                            value.item.backdropPath ?: value.item.posterPath,
                            backdropWidthPx,
                        ),
                        posterUrl = container.imageUrlBuilder.poster(
                            value.item.posterPath,
                            posterWidthPx,
                        ),
                        state = state,
                        onPlay = {
                            val source = state.sources.firstOrNull() ?: return@DetailsHero
                            if (state.hasSourceChoice) {
                                viewModel.openSourcePicker()
                            } else {
                                launchPlayback(state, source, onPlayMovie, onPlayEpisode)
                            }
                        },
                        onChooseSource = viewModel::openSourcePicker,
                        onToggleWatchlist = viewModel::toggleWatchlist,
                        onToggleWatched = viewModel::toggleWatched,
                    )
                }

                item(key = "availability") {
                    AvailabilityPanel(
                        state = state,
                        onRetry = viewModel::checkAvailability,
                        onAddPlaylist = onAddPlaylist,
                        onOpenProviderSettings = onOpenProviderSettings,
                    )
                }

                if (state.isSeries && state.seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        SeasonSelector(
                            seasons = state.seasons,
                            selected = state.selectedSeasonNumber,
                            onSelect = viewModel::selectSeason,
                        )
                    }

                    item(key = "episodes") {
                        EpisodeRail(
                            episodes = state.episodes,
                            stillUrlFor = { episode ->
                                container.imageUrlBuilder.still(episode.stillPath, stillWidthPx)
                            },
                            onEpisodeClick = { episode ->
                                viewModel.selectEpisode(episode)
                                val source = state.sources.firstOrNull()
                                if (source != null && !state.hasSourceChoice) {
                                    onPlayEpisode(
                                        value.item.id,
                                        episode.seasonNumber,
                                        episode.episodeNumber,
                                        source,
                                    )
                                } else {
                                    viewModel.openSourcePicker()
                                }
                            },
                        )
                    }
                }

                if (value.cast.isNotEmpty()) {
                    item(key = "cast") {
                        CastRail(
                            cast = value.cast,
                            profileUrlFor = { member ->
                                container.imageUrlBuilder.profile(member.profilePath, castWidthPx)
                            },
                        )
                    }
                }

                item(key = "attribution") {
                    Text(
                        text = stringResource(R.string.details_metadata_credit),
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.textDisabled,
                        modifier = Modifier.padding(horizontal = Dimens.overscanHorizontal),
                    )
                }
            }

            if (state.sourcePickerOpen) {
                SourcesSheet(
                    sources = state.sources,
                    onSelect = { source ->
                        viewModel.dismissSourcePicker()
                        launchPlayback(state, source, onPlayMovie, onPlayEpisode)
                    },
                    onDismiss = viewModel::dismissSourcePicker,
                )
            }
        }
    }
}

private fun launchPlayback(
    state: DetailsUiState,
    source: PlayableSource,
    onPlayMovie: (MediaId, PlayableSource) -> Unit,
    onPlayEpisode: (MediaId, Int, Int, PlayableSource) -> Unit,
) {
    val episode = state.selectedEpisode
    if (state.isSeries && episode != null) {
        onPlayEpisode(state.mediaId, episode.seasonNumber, episode.episodeNumber, source)
    } else {
        onPlayMovie(state.mediaId, source)
    }
}

@Composable
private fun DetailsHero(
    details: MediaDetails,
    backdropUrl: String?,
    posterUrl: String?,
    state: DetailsUiState,
    onPlay: () -> Unit,
    onChooseSource: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val colours = SuStreamTheme.colours
    val (verticalScrim, horizontalScrim) = heroScrimBrushes()
    val item = details.item

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DETAILS_HERO_HEIGHT),
    ) {
        RemoteImage(
            url = backdropUrl,
            contentDescription = stringResource(R.string.cd_backdrop, item.title),
            fallbackText = item.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(verticalScrim))
        Box(modifier = Modifier.fillMaxSize().background(horizontalScrim))

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = Dimens.overscanHorizontal,
                    end = Dimens.overscanHorizontal,
                    bottom = Dimens.space6,
                ),
        ) {
            RemoteImage(
                url = posterUrl,
                contentDescription = null,
                fallbackText = item.title,
                modifier = Modifier
                    .width(DETAIL_POSTER_WIDTH)
                    .height(DETAIL_POSTER_HEIGHT)
                    .clip(SuStreamTheme.shapes.card),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.space3),
                modifier = Modifier.width(DETAILS_TEXT_WIDTH),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                ) {
                    RatingPill(voteAverage = item.voteAverage)
                    details.certification?.let {
                        OutlineBadge(text = stringResource(R.string.details_certification, it))
                    }
                    Text(
                        text = Formatters.metadataLine(
                            item.releaseYear?.toString(),
                            Formatters.runtime(details.runtimeMinutes),
                            details.seriesStatus,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = colours.textSecondary,
                        maxLines = 1,
                    )
                }

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = colours.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (details.genres.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        details.genres.take(MAX_GENRE_CHIPS).forEach { genre ->
                            OutlineBadge(text = genre.name)
                        }
                    }
                }

                Text(
                    text = item.overview.ifBlank {
                        stringResource(R.string.details_no_overview)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.textSecondary,
                    maxLines = TextLimits.DETAILS_OVERVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
                    modifier = Modifier.padding(top = Dimens.space2),
                ) {
                    // Only rendered when something authorised can serve this title.
                    if (state.canPlay) {
                        val resumeMillis = state.resumePositionMillis
                        PrimaryButton(
                            text = if (resumeMillis > 0L) {
                                stringResource(
                                    R.string.action_resume_from,
                                    Formatters.clockPosition(resumeMillis),
                                )
                            } else {
                                stringResource(R.string.action_play)
                            },
                            icon = Icons.Filled.PlayArrow,
                            onClick = onPlay,
                            modifier = Modifier.initialFocus(),
                        )
                        if (state.hasSourceChoice) {
                            SecondaryButton(
                                text = stringResource(R.string.action_select_source),
                                onClick = onChooseSource,
                            )
                        }
                    }

                    IconOnlyButton(
                        icon = if (state.inWatchlist) {
                            SuStreamIcons.BookmarkFilled
                        } else {
                            SuStreamIcons.Bookmark
                        },
                        contentDescription = stringResource(
                            if (state.inWatchlist) {
                                R.string.action_remove_from_watchlist
                            } else {
                                R.string.action_add_to_watchlist
                            },
                        ),
                        active = state.inWatchlist,
                        onClick = onToggleWatchlist,
                        // When nothing can play, this becomes the screen's primary action, so it
                        // needs to hold focus.
                        modifier = if (state.canPlay) Modifier else Modifier.initialFocus(),
                    )

                    IconOnlyButton(
                        icon = Icons.Filled.Check,
                        contentDescription = stringResource(
                            if (state.isWatched) {
                                R.string.action_mark_unwatched
                            } else {
                                R.string.action_mark_watched
                            },
                        ),
                        active = state.isWatched,
                        onClick = onToggleWatched,
                    )
                }
            }
        }
    }
}

/**
 * The availability indicator.
 *
 * Four genuinely different messages, because the user's next action differs in each case: wait,
 * play, add a playlist, or accept that their services do not carry this. Collapsing them into one
 * "unavailable" state is what makes an app feel broken rather than honest.
 */
@Composable
private fun AvailabilityPanel(
    state: DetailsUiState,
    onRetry: () -> Unit,
    onAddPlaylist: () -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    val colours = SuStreamTheme.colours

    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.overscanHorizontal)
            .clip(SuStreamTheme.shapes.panel)
            .background(colours.surface)
            .padding(Dimens.space5),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            androidx.tv.material3.Icon(
                imageVector = SuStreamIcons.Shield,
                contentDescription = null,
                tint = when (state.availability) {
                    SourceAvailability.AVAILABLE -> colours.healthy
                    SourceAvailability.CHECKING -> colours.textTertiary
                    SourceAvailability.ERROR -> colours.danger
                    else -> colours.warning
                },
                modifier = Modifier.size(PANEL_ICON_SIZE),
            )
            Text(
                text = when (state.availability) {
                    SourceAvailability.CHECKING -> stringResource(R.string.availability_checking)
                    SourceAvailability.AVAILABLE -> if (state.sources.size == 1) {
                        stringResource(R.string.availability_available_count, 1)
                    } else {
                        stringResource(
                            R.string.availability_available_count_plural,
                            state.sources.size,
                        )
                    }

                    SourceAvailability.NONE_CONFIGURED ->
                        stringResource(R.string.availability_none_configured)

                    SourceAvailability.NO_SOURCE_FOUND ->
                        stringResource(R.string.availability_no_source_found)

                    SourceAvailability.ERROR -> stringResource(R.string.availability_error)
                },
                style = MaterialTheme.typography.titleMedium,
                color = colours.textPrimary,
            )
        }

        val body = when (state.availability) {
            SourceAvailability.NONE_CONFIGURED ->
                stringResource(R.string.availability_none_configured_body)

            SourceAvailability.NO_SOURCE_FOUND ->
                stringResource(R.string.availability_no_source_found_body)

            SourceAvailability.ERROR -> state.availabilityError?.detail
            else -> null
        }
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colours.textSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            when (state.availability) {
                SourceAvailability.NONE_CONFIGURED -> {
                    SecondaryButton(
                        text = stringResource(R.string.iptv_add_playlist),
                        onClick = onAddPlaylist,
                    )
                    SecondaryButton(
                        text = stringResource(R.string.settings_section_providers),
                        onClick = onOpenProviderSettings,
                    )
                }

                SourceAvailability.ERROR -> SecondaryButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRetry,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun SeasonSelector(
    seasons: List<Season>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.details_seasons),
            modifier = Modifier.padding(
                horizontal = Dimens.overscanHorizontal,
                vertical = Dimens.railHeaderSpacing,
            ),
        )
        ChipRow(
            options = seasons,
            selected = seasons.firstOrNull { it.seasonNumber == selected },
            onSelect = { onSelect(it.seasonNumber) },
            label = { season ->
                if (season.isSpecials) {
                    season.name
                } else {
                    stringResource(R.string.details_season_number, season.seasonNumber)
                }
            },
            key = { it.seasonNumber },
            modifier = Modifier.padding(horizontal = Dimens.overscanHorizontal),
        )
    }
}

@Composable
private fun EpisodeRail(
    episodes: Loadable<List<Episode>>,
    stillUrlFor: (Episode) -> String?,
    onEpisodeClick: (Episode) -> Unit,
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.details_episodes),
            modifier = Modifier.padding(
                horizontal = Dimens.overscanHorizontal,
                vertical = Dimens.railHeaderSpacing,
            ),
        )
        when (episodes) {
            Loadable.Idle, Loadable.Loading -> SkeletonRail(count = 4)

            is Loadable.Failed -> com.sustream.tv.designsystem.component.InlineError(
                error = episodes.error,
            )

            Loadable.Empty -> Text(
                text = stringResource(R.string.state_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = SuStreamTheme.colours.textTertiary,
                modifier = Modifier.padding(horizontal = Dimens.overscanHorizontal),
            )

            is Loadable.Loaded -> Rail(
                railKey = "episodes",
                items = episodes.value,
                key = { _, episode -> episode.key },
            ) { _, episode, itemModifier ->
                EpisodeCard(
                    episode = episode,
                    stillUrl = stillUrlFor(episode),
                    onClick = { onEpisodeClick(episode) },
                    modifier = itemModifier,
                )
            }
        }
    }
}

@Composable
private fun CastRail(
    cast: List<CastMember>,
    profileUrlFor: (CastMember) -> String?,
) {
    val colours = SuStreamTheme.colours

    Column {
        SectionHeader(
            title = stringResource(R.string.details_cast),
            modifier = Modifier.padding(
                horizontal = Dimens.overscanHorizontal,
                vertical = Dimens.railHeaderSpacing,
            ),
        )
        Rail(
            railKey = "cast",
            items = cast,
            key = { _, member -> member.id },
        ) { _, member, itemModifier ->
            // Cast entries are not focusable: there is nowhere to navigate to, and making them
            // focus targets would add a dozen D-pad stops with no destination.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = itemModifier.width(Dimens.castPortraitSize),
            ) {
                RemoteImage(
                    url = profileUrlFor(member),
                    contentDescription = stringResource(R.string.cd_person, member.name),
                    fallbackText = member.name,
                    modifier = Modifier
                        .size(Dimens.castPortraitSize)
                        .clip(SuStreamTheme.shapes.pill),
                )
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = colours.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Dimens.space2),
                )
                member.character?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val DETAILS_HERO_HEIGHT = 420.dp
private val BACKDROP_WIDTH = 960.dp
private val DETAIL_POSTER_WIDTH = 160.dp
private val DETAIL_POSTER_HEIGHT = 240.dp
private val DETAILS_TEXT_WIDTH = 560.dp
private val PANEL_ICON_SIZE = 22.dp
private const val MAX_GENRE_CHIPS = 3
