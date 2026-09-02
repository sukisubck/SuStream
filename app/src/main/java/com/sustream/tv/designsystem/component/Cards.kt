package com.sustream.tv.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.util.Formatters
import com.sustream.tv.designsystem.icon.SuStreamIcons
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.designsystem.theme.TextLimits
import com.sustream.tv.domain.model.Channel
import com.sustream.tv.domain.model.ContinueWatchingItem
import com.sustream.tv.domain.model.EpgProgramme
import com.sustream.tv.domain.model.Episode
import com.sustream.tv.domain.model.MediaItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star

/**
 * Cards, following the prototype's treatment: a 2:3 poster with a rating pill in the top-right
 * corner, the title and a `year · genre` metadata line beneath, a 12 dp radius, a 1 dp slate border
 * that becomes the brand violet on focus, and a scale-up on focus.
 *
 * Each card is a single focus target with one content description covering the whole thing, rather
 * than several separately-focusable children. On a D-pad, a card whose title and rating are
 * independently focusable takes three presses to move past and is unusable.
 */

/** Poster card for rails and grids. */
@Composable
fun PosterCard(
    item: MediaItem,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inWatchlist: Boolean = false,
    /** 0f..1f when the title is part-watched; draws the progress strip. */
    progressFraction: Float? = null,
) {
    val colours = SuStreamTheme.colours
    val metadata = Formatters.metadataLine(
        item.releaseYear?.toString(),
        item.primaryGenre,
    )
    val description = buildCardDescription(
        title = item.title,
        metadata = metadata,
        voteAverage = item.voteAverage,
        inWatchlist = inWatchlist,
        progressFraction = progressFraction,
    )

    FocusableCard(
        onClick = onClick,
        modifier = modifier
            .width(Dimens.posterCardWidth)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.posterCardHeight)
                    .clip(SuStreamTheme.shapes.card),
            ) {
                RemoteImage(
                    url = posterUrl,
                    contentDescription = null,
                    fallbackText = item.title,
                    modifier = Modifier.fillMaxSize(),
                )

                RatingPill(
                    voteAverage = item.voteAverage,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(CARD_OVERLAY_PADDING),
                )

                if (inWatchlist) {
                    Icon(
                        imageVector = SuStreamIcons.BookmarkFilled,
                        contentDescription = null,
                        tint = colours.accent,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(CARD_OVERLAY_PADDING)
                            .size(WATCHLIST_MARK_SIZE),
                    )
                }

                if (progressFraction != null) {
                    ProgressTrack(
                        fraction = progressFraction,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            Column(modifier = Modifier.padding(top = Dimens.space2)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colours.textPrimary,
                    maxLines = TextLimits.CARD_TITLE_LINES,
                    overflow = TextLimits.OVERFLOW,
                )
                if (metadata.isNotEmpty()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.textTertiary,
                        maxLines = TextLimits.CARD_SUBTITLE_LINES,
                        overflow = TextLimits.OVERFLOW,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 16:9 card with a play overlay and a progress strip: the prototype's Continue Watching tile.
 */
@Composable
fun ContinueWatchingCard(
    entry: ContinueWatchingItem,
    backdropUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = SuStreamTheme.colours
    val remaining = Formatters.remaining(
        entry.progress.positionMillis,
        entry.progress.durationMillis,
    )
    val subtitle = Formatters.metadataLine(
        entry.episodeLabel,
        remaining?.let { stringResource(R.string.library_progress_remaining, it) },
    )
    val description = buildCardDescription(
        title = entry.item.title,
        metadata = subtitle,
        voteAverage = null,
        inWatchlist = false,
        progressFraction = entry.progress.fraction,
    )

    FocusableCard(
        onClick = onClick,
        modifier = modifier
            .width(Dimens.wideCardWidth)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.wideCardHeight)
                    .clip(SuStreamTheme.shapes.card),
            ) {
                RemoteImage(
                    url = backdropUrl,
                    contentDescription = null,
                    fallbackText = entry.item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                // Dim the artwork so the play affordance reads, as the prototype does.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colours.backgroundDeep.copy(alpha = PLAY_SCRIM_ALPHA)),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(PLAY_BUBBLE_SIZE)
                        .clip(SuStreamTheme.shapes.pill)
                        .background(colours.primary.copy(alpha = PLAY_BUBBLE_ALPHA)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = colours.onPrimary,
                        modifier = Modifier.size(PLAY_ICON_SIZE),
                    )
                }
                ProgressTrack(
                    fraction = entry.progress.fraction,
                    height = Dimens.progressBarHeightThick,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            Column(modifier = Modifier.padding(top = Dimens.space2)) {
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colours.textPrimary,
                    maxLines = TextLimits.CARD_TITLE_LINES,
                    overflow = TextLimits.OVERFLOW,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.textTertiary,
                        maxLines = 1,
                        overflow = TextLimits.OVERFLOW,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** Episode card: 16:9 still, `S01E02 · Title`, runtime and a two-line synopsis. */
@Composable
fun EpisodeCard(
    episode: Episode,
    stillUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressFraction: Float? = null,
    watched: Boolean = false,
) {
    val colours = SuStreamTheme.colours
    val label = stringResource(
        R.string.details_episode_label,
        episode.seasonNumber,
        episode.episodeNumber,
    )
    val heading = label + "  " + episode.name
    val meta = Formatters.metadataLine(Formatters.runtime(episode.runtimeMinutes))

    FocusableCard(
        onClick = onClick,
        modifier = modifier
            .width(EPISODE_CARD_WIDTH)
            .clearAndSetSemantics {
                contentDescription = listOf(heading, meta, episode.overview)
                    .filter { it.isNotBlank() }
                    .joinToString(". ")
            },
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.wideCardHeight)
                    .clip(SuStreamTheme.shapes.card),
            ) {
                RemoteImage(
                    url = stillUrl,
                    contentDescription = null,
                    fallbackText = episode.name,
                    modifier = Modifier.fillMaxSize(),
                )
                if (watched) {
                    Badge(
                        text = stringResource(R.string.action_mark_watched),
                        containerColour = colours.healthy,
                        contentColour = colours.onAccent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(CARD_OVERLAY_PADDING),
                    )
                }
                if (progressFraction != null) {
                    ProgressTrack(
                        fraction = progressFraction,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            Column(modifier = Modifier.padding(top = Dimens.space2)) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall,
                    color = colours.textPrimary,
                    maxLines = 1,
                    overflow = TextLimits.OVERFLOW,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = colours.textTertiary,
                        maxLines = 1,
                    )
                }
                if (episode.overview.isNotBlank()) {
                    Text(
                        text = episode.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = colours.textSecondary,
                        maxLines = TextLimits.EPISODE_OVERVIEW_LINES,
                        overflow = TextLimits.OVERFLOW,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Channel row for the IPTV list: logo, name, LIVE tag and the current programme — the prototype's
 * amber-accented channel item.
 */
@Composable
fun ChannelRow(
    channel: Channel,
    nowProgramme: EpgProgramme?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showNumber: Boolean = true,
) {
    val colours = SuStreamTheme.colours
    val favouriteLabel = stringResource(R.string.cd_favourite)
    val description = listOfNotNull(
        channel.number?.takeIf { showNumber },
        channel.name,
        nowProgramme?.title,
        favouriteLabel.takeIf { channel.isFavourite },
    ).joinToString(". ")

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.card),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colours.surface,
            contentColor = colours.textPrimary,
            focusedContainerColor = colours.surfaceRaised,
            focusedContentColor = colours.textPrimary,
            pressedContainerColor = colours.surfaceRaised,
            pressedContentColor = colours.textPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dimens.FOCUS_SCALE_LARGE),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, colours.border),
                shape = SuStreamTheme.shapes.card,
            ),
            // Amber ring here rather than white: the IPTV domain is amber-accented throughout, and
            // the focused row should read as part of that domain.
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidth, colours.iptvAccentSoft),
                shape = SuStreamTheme.shapes.card,
            ),
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.channelRowHeight)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.space4),
        ) {
            if (showNumber && !channel.number.isNullOrBlank()) {
                Text(
                    text = channel.number,
                    style = MaterialTheme.typography.labelMedium,
                    color = colours.textTertiary,
                    modifier = Modifier.width(CHANNEL_NUMBER_WIDTH),
                )
            }

            RemoteImage(
                url = channel.logoUrl,
                contentDescription = null,
                fallbackText = channel.name,
                modifier = Modifier
                    .size(Dimens.channelLogoSize)
                    .clip(SuStreamTheme.shapes.chip),
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                ) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = colours.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (nowProgramme != null) LiveBadge()
                }
                Text(
                    text = nowProgramme?.title ?: stringResource(R.string.iptv_no_epg),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nowProgramme != null) colours.textSecondary else colours.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (channel.isFavourite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = colours.iptvAccentSoft,
                    modifier = Modifier.size(FAVOURITE_MARK_SIZE),
                )
            }
        }
    }
}

/**
 * Shared focus treatment for every card: violet border and white ring on focus, brand glow, and a
 * scale-up. Extracted so a change to focus styling is one edit rather than one per card type.
 */
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val colours = SuStreamTheme.colours

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.card),
        colors = ClickableSurfaceDefaults.colors(
            // Transparent: a card is artwork plus a caption, and a container colour behind the
            // caption would box it in a way the prototype does not.
            containerColor = Color.Transparent,
            contentColor = colours.textPrimary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = colours.textPrimary,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = colours.textPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dimens.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidth, colours.focusRing),
                shape = SuStreamTheme.shapes.card,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = colours.primary, elevation = CARD_GLOW_ELEVATION),
        ),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * Builds one description for a whole card.
 *
 * Assembled here rather than left to per-element semantics so a screen reader announces
 * "Dune, 2024, Sci-Fi, rated 9.2 out of 10, 68 percent watched" as a single item, which is how the
 * card actually behaves under a D-pad.
 */
@Composable
private fun buildCardDescription(
    title: String,
    metadata: String,
    voteAverage: Double?,
    inWatchlist: Boolean,
    progressFraction: Float?,
): String {
    val parts = mutableListOf(title)
    if (metadata.isNotBlank()) parts += metadata
    Formatters.rating(voteAverage)?.let { parts += stringResource(R.string.cd_rating, it) }
    if (inWatchlist) parts += stringResource(R.string.cd_in_watchlist)
    if (progressFraction != null) {
        parts += stringResource(
            R.string.cd_progress,
            (progressFraction * PERCENT).toInt(),
        )
    }
    return parts.joinToString(". ")
}

private const val PERCENT = 100
private const val PLAY_SCRIM_ALPHA = 0.35f
private const val PLAY_BUBBLE_ALPHA = 0.92f
private val CARD_OVERLAY_PADDING = 6.dp
private val WATCHLIST_MARK_SIZE = 16.dp
private val PLAY_BUBBLE_SIZE = 40.dp
private val PLAY_ICON_SIZE = 22.dp
private val CARD_GLOW_ELEVATION = 12.dp
private val FAVOURITE_MARK_SIZE = 18.dp
private val CHANNEL_NUMBER_WIDTH = 36.dp
private val EPISODE_CARD_WIDTH = 260.dp
