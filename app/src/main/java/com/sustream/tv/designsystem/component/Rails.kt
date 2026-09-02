package com.sustream.tv.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.designsystem.focus.rememberRailFocusState
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme

/**
 * Horizontal rails, the primary browse pattern on a TV.
 *
 * Two things every rail must get right, and which are easy to get wrong:
 *
 *  1. **Focus is not clipped.** A card scales to 1.06x on focus, so a rail with tight bounds crops
 *     the focused card's ring. [Dimens.focusBleed] pads the row so the scaled card has room.
 *  2. **Focus is restored.** Coming back from Details must land on the card you left. The index is
 *     held in saveable state keyed by the rail, so it survives navigation *and* process death.
 */

/** Rail heading with an optional icon and trailing action, as in the prototype's rail headers. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = SuStreamTheme.colours.accent,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colours = SuStreamTheme.colours

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .padding(end = Dimens.space2)
                    .size(HEADER_ICON_SIZE),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colours.textPrimary,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colours.textTertiary,
                    maxLines = 1,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Generic rail.
 *
 * @param railKey stable identity for focus restoration. Use something derived from the rail's
 *   meaning (e.g. `"rail-trending-films"`), not its list index, or two rails will share state.
 * @param onItemFocused fires on focus changes only, so a caller can update a hero backdrop without
 *   doing work on every recomposition.
 */
@Composable
fun <T> Rail(
    railKey: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.overscanHorizontal),
    itemSpacing: androidx.compose.ui.unit.Dp = Dimens.railSpacing,
    onItemFocused: (index: Int, item: T) -> Unit = { _, _ -> },
    key: ((index: Int, item: T) -> Any)? = null,
    itemContent: @Composable (index: Int, item: T, itemModifier: Modifier) -> Unit,
) {
    if (items.isEmpty()) return

    val listState = rememberLazyListState()
    val focusState = rememberRailFocusState(railKey)

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        contentPadding = contentPadding,
        // Vertical padding so the focused card's scale and glow are not clipped by the row bounds.
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.focusBleed),
    ) {
        itemsIndexed(
            items = items,
            key = key,
        ) { index, item ->
            itemContent(
                index,
                item,
                Modifier
                    .then(focusState.restorerFor(index))
                    .then(
                        Modifier.onFocusIndexChanged(index) {
                            focusState.onItemFocused(index)
                            onItemFocused(index, item)
                        },
                    ),
            )
        }
    }
}

/**
 * Focus tracking for a rail item.
 *
 * Separate from the generic `trackFocus` helper because a rail only cares about *gaining* focus:
 * firing on loss as well would mean the hero backdrop briefly reverted between two cards.
 */
@Composable
private fun Modifier.onFocusIndexChanged(
    index: Int,
    onGained: () -> Unit,
): Modifier = onFocusChanged { state ->
    if (state.isFocused) onGained()
}

private val HEADER_ICON_SIZE = 20.dp
