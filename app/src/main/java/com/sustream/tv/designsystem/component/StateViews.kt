package com.sustream.tv.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.isRetryable
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning

/**
 * Loading, empty and error states.
 *
 * The brief calls for explicit network-loss, slow-network, empty, loading, expired-link and error
 * states, and these are what satisfy that. Every one is a real, focusable screen rather than a
 * spinner or a toast, because a toast on a TV is unreadable from a sofa and a bare spinner leaves
 * the user with nothing to press.
 */

/** Full-screen loading state, used while a whole screen's data is in flight. */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.state_loading),
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxSize(),
    ) {
        Spinner()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = SuStreamTheme.colours.textSecondary,
            modifier = Modifier.padding(top = Dimens.space4),
        )
    }
}

/**
 * Simple rotating arc.
 *
 * A hand-rolled rotation rather than a Material progress indicator: `tv-material` ships no
 * indeterminate indicator, and pulling in `compose.material3` just for one spinner would mean two
 * Material design systems in the same app.
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = SPINNER_SIZE,
    colour: Color = SuStreamTheme.colours.primary,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(
            animation = tween(SPINNER_PERIOD_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinnerAngle",
    )

    Box(
        modifier = modifier
            .size(size)
            .rotate(angle)
            .clip(SuStreamTheme.shapes.pill)
            .background(
                Brush.sweepGradient(
                    listOf(Color.Transparent, colour.copy(alpha = 0.15f), colour),
                ),
            ),
    ) {
        // Inner disc, so the sweep reads as a ring rather than a filled circle.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size - SPINNER_STROKE * 2)
                .clip(SuStreamTheme.shapes.pill)
                .background(SuStreamTheme.colours.background),
        )
    }
}

/** Skeleton block, for rails that are still loading while the rest of the screen is usable. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = SKELETON_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(SKELETON_PERIOD_MILLIS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .clip(SuStreamTheme.shapes.card)
            .background(SuStreamTheme.colours.surfaceRaised.copy(alpha = alpha)),
    )
}

/** A rail's worth of skeleton posters. */
@Composable
fun SkeletonRail(
    modifier: Modifier = Modifier,
    count: Int = SKELETON_CARD_COUNT,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.railSpacing),
        modifier = modifier.padding(horizontal = Dimens.overscanHorizontal),
    ) {
        repeat(count) {
            SkeletonBlock(
                modifier = Modifier
                    .width(Dimens.posterCardWidth)
                    .height(Dimens.posterCardHeight),
            )
        }
    }
}

/**
 * Message state with an optional action.
 *
 * One composable behind [EmptyState] and [ErrorState] so that "nothing here" and "it broke" are
 * visually consistent and neither is a dead end: if there is an action, it takes focus.
 */
@Composable
fun MessageState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = SuStreamTheme.colours.textTertiary,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colours = SuStreamTheme.colours

    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.overscanHorizontal),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(STATE_ICON_SIZE)
                    .padding(bottom = Dimens.space4),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = colours.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = colours.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Dimens.space3)
                .width(STATE_BODY_MAX_WIDTH),
        )
        if (primaryActionLabel != null && onPrimaryAction != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
                modifier = Modifier.padding(top = Dimens.space6),
            ) {
                // The action takes focus so an error screen is never a dead end for a D-pad user.
                PrimaryButton(
                    text = primaryActionLabel,
                    onClick = onPrimaryAction,
                    modifier = Modifier.initialFocus(),
                )
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    SecondaryButton(text = secondaryActionLabel, onClick = onSecondaryAction)
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    body: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.state_empty_title),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    MessageState(
        title = title,
        body = body,
        icon = Icons.Filled.Info,
        primaryActionLabel = actionLabel,
        onPrimaryAction = onAction,
        modifier = modifier,
    )
}

/**
 * Error state driven by the typed [AppError].
 *
 * The retry button appears only when retrying could actually work, decided by
 * [com.sustream.tv.core.result.isRetryable] rather than by the caller remembering to pass a flag.
 * Offering "Try again" for a rejected URL scheme would be dishonest.
 */
@Composable
fun ErrorState(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val isOffline = error is AppError.Network
    val title = if (isOffline) {
        stringResource(R.string.state_offline_title)
    } else {
        stringResource(R.string.state_error_title)
    }
    val body = if (isOffline) {
        stringResource(R.string.state_offline_body)
    } else {
        errorMessage(error)
    }

    MessageState(
        title = title,
        body = body,
        icon = Icons.Filled.Warning,
        iconTint = SuStreamTheme.colours.warning,
        primaryActionLabel = if (error.isRetryable && onRetry != null) {
            stringResource(R.string.action_retry)
        } else {
            null
        },
        onPrimaryAction = onRetry.takeIf { error.isRetryable },
        secondaryActionLabel = secondaryActionLabel,
        onSecondaryAction = onSecondaryAction,
        modifier = modifier,
    )
}

/** Inline error strip, for a single failed rail inside an otherwise working screen. */
@Composable
fun InlineError(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val colours = SuStreamTheme.colours

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.overscanHorizontal)
            .clip(SuStreamTheme.shapes.card)
            .background(colours.surface)
            .padding(Dimens.space4),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = colours.warning,
            modifier = Modifier.size(INLINE_ICON_SIZE),
        )
        Text(
            text = errorMessage(error),
            style = MaterialTheme.typography.bodyMedium,
            color = colours.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null && error.isRetryable) {
            TextActionButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
            )
        }
    }
}

/**
 * Maps the typed error taxonomy onto user-facing copy.
 *
 * Exhaustive `when`, so adding an [AppError] case is a compile error here until someone writes the
 * message. That is the point: it is how "an unexpected error occurred" is kept from becoming the
 * app's default answer to everything.
 */
@Composable
fun errorMessage(error: AppError): String = when (error) {
    is AppError.Network -> stringResource(R.string.error_network)
    is AppError.Timeout -> stringResource(R.string.error_timeout)
    is AppError.Unauthorised -> stringResource(R.string.error_unauthorised)
    is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
    is AppError.NotFound -> stringResource(R.string.error_not_found)
    is AppError.ParseFailed -> error.detail ?: stringResource(R.string.error_parse)
    is AppError.SchemeRejected -> error.detail ?: stringResource(R.string.error_scheme_rejected)
    is AppError.TooLarge -> stringResource(R.string.error_too_large)
    is AppError.Expired -> stringResource(R.string.error_expired)
    is AppError.QuotaExceeded -> stringResource(R.string.error_quota)
    is AppError.UnsupportedFormat -> stringResource(R.string.error_unsupported_format)
    is AppError.ServerError -> stringResource(R.string.error_unknown)
    is AppError.Storage -> error.detail ?: stringResource(R.string.error_unknown)
    is AppError.Unknown -> error.detail ?: stringResource(R.string.error_unknown)
}

private val SPINNER_SIZE = 44.dp
private val SPINNER_STROKE = 4.dp
private val STATE_ICON_SIZE = 48.dp
private val STATE_BODY_MAX_WIDTH = 520.dp
private val INLINE_ICON_SIZE = 20.dp
private const val FULL_TURN = 360f
private const val SPINNER_PERIOD_MILLIS = 900
private const val SKELETON_PERIOD_MILLIS = 700
private const val SKELETON_MIN_ALPHA = 0.35f
private const val SKELETON_MAX_ALPHA = 0.75f
private const val SKELETON_CARD_COUNT = 6
