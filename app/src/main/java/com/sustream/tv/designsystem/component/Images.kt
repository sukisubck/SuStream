package com.sustream.tv.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.sustream.tv.designsystem.theme.SuStreamTheme

/**
 * Remote artwork with a branded placeholder.
 *
 * Missing artwork is routine, not exceptional: TMDB has no poster for plenty of titles, and IPTV
 * playlists frequently omit `tvg-logo` or point it at a host that no longer answers. A blank grey
 * rectangle in a rail of posters reads as a bug, so the fallback draws the title's initials over
 * the brand gradient — the same treatment the original scaffold used, and legible at viewing
 * distance.
 *
 * The image is faded in rather than snapped: twenty posters in a rail resolve at different times,
 * and without the fade the rail visibly pops as each one lands.
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    /** Text the initials fallback is built from. Usually the title or channel name. */
    fallbackText: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    /** Painted while loading and behind a transparent image. */
    placeholderBrush: Brush? = null,
) {
    var loadState by remember(url) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val loaded = loadState is AsyncImagePainter.State.Success
    val failed = url.isNullOrBlank() || loadState is AsyncImagePainter.State.Error

    val imageAlpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        label = "imageFade",
    )

    val placeholder = placeholderBrush ?: defaultPlaceholderBrush()

    Box(modifier = modifier.background(placeholder)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = { loadState = it },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(imageAlpha),
            )
        }

        if (failed) {
            InitialsFallback(
                text = fallbackText,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun defaultPlaceholderBrush(): Brush {
    val colours = SuStreamTheme.colours
    return Brush.verticalGradient(listOf(colours.surfaceRaised, colours.surface))
}

@Composable
private fun InitialsFallback(
    text: String?,
    modifier: Modifier = Modifier,
) {
    val colours = SuStreamTheme.colours
    val initials = remember(text) { initialsOf(text) }
    Text(
        text = initials,
        style = MaterialTheme.typography.displayMedium,
        color = colours.textTertiary.copy(alpha = FALLBACK_TEXT_ALPHA),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.padding(FALLBACK_PADDING),
    )
}

/** `"Cyberpunk: Neon Syndicate"` -> `"CN"`. Falls back to a neutral glyph for empty input. */
internal fun initialsOf(text: String?): String {
    if (text.isNullOrBlank()) return NEUTRAL_GLYPH
    return text
        .split(' ', ':', '-', '·')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
        .take(MAX_INITIALS)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifEmpty { NEUTRAL_GLYPH }
}

/**
 * Scrims for the featured hero, matching the prototype's stacked `bg-gradient-to-t` plus
 * `bg-gradient-to-r`: one gradient lifts the bottom of the frame into the page background, the
 * other darkens the left edge so the title block stays legible over any backdrop.
 */
@Composable
fun heroScrimBrushes(): Pair<Brush, Brush> {
    val background = SuStreamTheme.colours.background
    val vertical = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.55f to background.copy(alpha = HERO_MID_ALPHA),
            1.0f to background,
        ),
    )
    val horizontal = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.0f to background.copy(alpha = HERO_EDGE_ALPHA),
            0.6f to Color.Transparent,
        ),
    )
    return vertical to horizontal
}

/** Bottom-up scrim for card artwork that carries an overlaid title. */
@Composable
fun cardScrimBrush(): Brush {
    val background = SuStreamTheme.colours.backgroundDeep
    return Brush.verticalGradient(
        colorStops = arrayOf(
            0.45f to Color.Transparent,
            1.0f to background.copy(alpha = CARD_SCRIM_ALPHA),
        ),
    )
}

private const val NEUTRAL_GLYPH = "•"
private const val MAX_INITIALS = 2
private const val FALLBACK_TEXT_ALPHA = 0.55f
private const val HERO_MID_ALPHA = 0.45f
private const val HERO_EDGE_ALPHA = 0.88f
private const val CARD_SCRIM_ALPHA = 0.85f
private val FALLBACK_PADDING = 4.dp
