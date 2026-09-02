package com.sustream.tv.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme

/**
 * Selectable pills, used for the IPTV sub-tabs, category filters, genre and year filters, and
 * season selection. Mirrors the prototype's amber sub-tab row and violet filter chips.
 *
 * There are three visual states, not two, and keeping them distinct matters on a TV: **selected**
 * (this filter is active), **focused** (the D-pad is here), and **selected and focused**. An app
 * that collapses selected and focused into one style leaves the user unable to tell where they are.
 */

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Amber for the Live TV domain, violet elsewhere. */
    accent: Color = SuStreamTheme.colours.primary,
    onAccent: Color = SuStreamTheme.colours.onPrimary,
    enabled: Boolean = true,
) {
    val colours = SuStreamTheme.colours

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.chip),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent else colours.surface,
            contentColor = if (selected) onAccent else colours.textSecondary,
            // Focused-and-selected keeps the accent fill and gains the white ring below, so the
            // two states remain separable.
            focusedContainerColor = if (selected) accent else colours.focusFill,
            focusedContentColor = if (selected) onAccent else colours.onFocusFill,
            pressedContainerColor = if (selected) accent else colours.focusFill,
            pressedContentColor = if (selected) onAccent else colours.onFocusFill,
            disabledContainerColor = colours.surface,
            disabledContentColor = colours.textDisabled,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dimens.FOCUS_SCALE_LARGE),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) Color.Transparent else colours.border,
                ),
                shape = SuStreamTheme.shapes.chip,
            ),
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidthSubtle, colours.focusRing),
                shape = SuStreamTheme.shapes.chip,
            ),
        ),
        modifier = modifier.defaultMinSize(minHeight = Dimens.chipHeight),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = CHIP_PADDING_H),
        )
    }
}

/** A single-select row of chips, e.g. the IPTV sub-tabs or a genre filter. */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    // Composable so a caller can build the label from a string resource, which is the normal case.
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    accent: Color = SuStreamTheme.colours.primary,
    onAccent: Color = SuStreamTheme.colours.onPrimary,
    key: ((T) -> Any)? = null,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        // Vertical padding so the focused chip's scale is not clipped.
        modifier = modifier.padding(vertical = Dimens.space1),
    ) {
        items(items = options, key = key) { option ->
            FilterChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                accent = accent,
                onAccent = onAccent,
            )
        }
    }
}

/** Non-scrolling variant, for a small fixed set such as the four Live TV sub-tabs. */
@Composable
fun <T> ChipGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    accent: Color = SuStreamTheme.colours.primary,
    onAccent: Color = SuStreamTheme.colours.onPrimary,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        modifier = modifier,
    ) {
        options.forEach { option ->
            FilterChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                accent = accent,
                onAccent = onAccent,
            )
        }
    }
}

private val CHIP_PADDING_H = 14.dp
