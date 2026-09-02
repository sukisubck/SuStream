package com.sustream.tv.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.util.Formatters
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

/**
 * Small status and metadata chips, taken from the prototype: the violet "FEATURED 4K" pill, the
 * amber star rating, the rose "LIVE" tag, and the emerald connected dot.
 */

/** Filled pill, e.g. "FEATURED". Uppercase with wide tracking, as in the prototype. */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    containerColour: Color = SuStreamTheme.colours.primary,
    contentColour: Color = SuStreamTheme.colours.onPrimary,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = contentColour,
        maxLines = 1,
        modifier = modifier
            .clip(SuStreamTheme.shapes.badge)
            .background(containerColour)
            .padding(horizontal = BADGE_PADDING_H, vertical = BADGE_PADDING_V),
    )
}

/** Outlined pill for genres and secondary metadata: the prototype's translucent genre tags. */
@Composable
fun OutlineBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colours = SuStreamTheme.colours
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = colours.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(SuStreamTheme.shapes.badge)
            .background(Color.White.copy(alpha = GLASS_ALPHA))
            .border(1.dp, Color.White.copy(alpha = GLASS_BORDER_ALPHA), SuStreamTheme.shapes.badge)
            .padding(horizontal = BADGE_PADDING_H, vertical = BADGE_PADDING_V),
    )
}

/**
 * Star plus score, as in the prototype's `★ 8.9` pill over the poster's top-right corner.
 *
 * Renders nothing when the rating is null, which is how a title with too few votes shows no score
 * rather than a misleading one. See [com.sustream.tv.domain.model.MediaItem.voteAverage].
 */
@Composable
fun RatingPill(
    voteAverage: Double?,
    modifier: Modifier = Modifier,
) {
    val formatted = Formatters.rating(voteAverage) ?: return
    val colours = SuStreamTheme.colours

    val description = stringResource(R.string.cd_rating, formatted)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RATING_ICON_GAP),
        modifier = modifier
            .clip(SuStreamTheme.shapes.badge)
            .background(colours.backgroundDeep.copy(alpha = SCRIM_PILL_ALPHA))
            .padding(horizontal = BADGE_PADDING_H, vertical = BADGE_PADDING_V)
            // One description for the pill as a whole; the star and the number read as one fact.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = colours.rating,
            modifier = Modifier.size(RATING_ICON_SIZE),
        )
        Text(
            text = formatted,
            style = MaterialTheme.typography.labelMedium,
            color = colours.rating,
            maxLines = 1,
        )
    }
}

/** The prototype's rose "LIVE" tag. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val colours = SuStreamTheme.colours
    Badge(
        text = stringResource(R.string.iptv_live_badge),
        containerColour = colours.live,
        contentColour = colours.onLive,
        modifier = modifier,
    )
}

/**
 * Pulsing status dot, matching the prototype's `animate-pulse` connection indicator.
 *
 * Only pulses when [pulsing] is true, so a healthy static state does not run an infinite animation
 * for no reason — on an always-on TV app that is a real, if small, battery and CPU cost.
 */
@Composable
fun StatusDot(
    colour: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    size: Dp = STATUS_DOT_SIZE,
) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_MIN_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(PULSE_DURATION_MILLIS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusPulseAlpha",
        )
        animated
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .clip(SuStreamTheme.shapes.pill)
            .background(colour),
    )
}

/**
 * Watch-progress bar drawn across the bottom of a card, as in the prototype's Continue Watching
 * strip.
 */
@Composable
fun ProgressTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = Dimens.progressBarHeight,
    trackColour: Color = SuStreamTheme.colours.borderStrong,
    indicatorColour: Color = SuStreamTheme.colours.primary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColour),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(indicatorColour),
        )
    }
}

/**
 * Gradient-filled brand tile, used for the nav rail logo. Mirrors the prototype's
 * `bg-gradient-to-tr from-[#6C5CE7] to-[#A29BFE]` header mark.
 */
@Composable
fun BrandTile(
    modifier: Modifier = Modifier,
    size: Dp = BRAND_TILE_SIZE,
    content: @Composable () -> Unit,
) {
    val colours = SuStreamTheme.colours
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(BRAND_TILE_RADIUS))
            .background(
                Brush.linearGradient(listOf(colours.primary, colours.accent)),
            ),
    ) {
        content()
    }
}

private val BADGE_PADDING_H = 8.dp
private val BADGE_PADDING_V = 3.dp
private val RATING_ICON_SIZE = 13.dp
private val RATING_ICON_GAP = 3.dp
private val STATUS_DOT_SIZE = 8.dp
private val BRAND_TILE_SIZE = 40.dp
private val BRAND_TILE_RADIUS = 10.dp
private const val GLASS_ALPHA = 0.10f
private const val GLASS_BORDER_ALPHA = 0.14f
private const val SCRIM_PILL_ALPHA = 0.78f
private const val PULSE_MIN_ALPHA = 0.35f
private const val PULSE_DURATION_MILLIS = 900
