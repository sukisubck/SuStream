package com.sustream.tv.presentation.components

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.util.Formatters
import com.sustream.tv.designsystem.component.Badge
import com.sustream.tv.designsystem.component.IconOnlyButton
import com.sustream.tv.designsystem.component.OutlineBadge
import com.sustream.tv.designsystem.component.PrimaryButton
import com.sustream.tv.designsystem.component.RatingPill
import com.sustream.tv.designsystem.component.RemoteImage
import com.sustream.tv.designsystem.component.SkeletonBlock
import com.sustream.tv.designsystem.component.heroScrimBrushes
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.icon.SuStreamIcons
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.designsystem.theme.TextLimits
import com.sustream.tv.domain.model.MediaItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow

/**
 * The featured hero, following the prototype's treatment: a full-width backdrop under two stacked
 * gradient scrims, with the badge row, title, genre chips and action buttons in the lower-left.
 *
 * The Play button takes initial focus, which is the single most important focus decision on the
 * whole app: it is where a user landing on Home expects the D-pad to already be, and it means the
 * primary action is one press away from a cold start.
 */
@Composable
fun FeaturedHero(
    item: MediaItem?,
    backdropUrl: String?,
    inWatchlist: Boolean,
    onPlay: (MediaItem) -> Unit,
    onToggleWatchlist: (MediaItem) -> Unit,
    onMoreInfo: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    val colours = SuStreamTheme.colours
    val (verticalScrim, horizontalScrim) = heroScrimBrushes()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.heroHeight),
    ) {
        if (item == null) {
            if (isLoading) {
                SkeletonBlock(modifier = Modifier.fillMaxSize())
            }
            return@Box
        }

        RemoteImage(
            url = backdropUrl,
            contentDescription = stringResource(R.string.cd_backdrop, item.title),
            fallbackText = item.title,
            modifier = Modifier.fillMaxSize(),
        )

        // Two scrims, as the prototype stacks them: one lifts the bottom edge into the page
        // background, the other darkens the left so the text block stays legible over any artwork.
        Box(modifier = Modifier.fillMaxSize().background(verticalScrim))
        Box(modifier = Modifier.fillMaxSize().background(horizontalScrim))

        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = Dimens.overscanHorizontal,
                    end = Dimens.overscanHorizontal,
                    bottom = Dimens.space6,
                )
                .width(Dimens.heroContentMaxWidth),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                Badge(text = stringResource(R.string.home_featured_badge))
                RatingPill(voteAverage = item.voteAverage)
                Text(
                    text = Formatters.metadataLine(
                        item.releaseYear?.toString(),
                        item.primaryGenre,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = colours.textSecondary,
                    maxLines = 1,
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.displayMedium,
                color = colours.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.overview.isNotBlank()) {
                Text(
                    text = item.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colours.textSecondary,
                    maxLines = TextLimits.HERO_OVERVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.primaryGenre != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    OutlineBadge(text = item.primaryGenre)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
                modifier = Modifier.padding(top = Dimens.space2),
            ) {
                PrimaryButton(
                    text = stringResource(R.string.action_play),
                    icon = Icons.Filled.PlayArrow,
                    onClick = { onPlay(item) },
                    // Where the D-pad lands on a cold start.
                    modifier = Modifier.initialFocus(),
                )
                IconOnlyButton(
                    icon = if (inWatchlist) {
                        SuStreamIcons.BookmarkFilled
                    } else {
                        SuStreamIcons.Bookmark
                    },
                    contentDescription = stringResource(
                        if (inWatchlist) {
                            R.string.action_remove_from_watchlist
                        } else {
                            R.string.action_add_to_watchlist
                        },
                    ),
                    active = inWatchlist,
                    onClick = { onToggleWatchlist(item) },
                )
                IconOnlyButton(
                    icon = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.action_more_info),
                    onClick = { onMoreInfo(item) },
                )
            }
        }
    }
}
