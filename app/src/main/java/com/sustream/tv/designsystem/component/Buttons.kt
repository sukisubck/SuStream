package com.sustream.tv.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme

/**
 * Buttons for a remote control.
 *
 * Built on `tv-material`'s [Surface] rather than its [androidx.tv.material3.Button] for one
 * reason: the prototype's primary action is a **gradient** fill
 * (`from-[#6C5CE7] to-[#8070F7]`), and `ButtonColors` only accepts flat colours. A Surface with a
 * transparent container plus an inner gradient Box keeps the gradient while retaining the TV focus
 * and D-pad-centre click semantics that Surface provides.
 *
 * Focus treatment throughout: a white ring plus a 1.06x scale, which reads clearly from three
 * metres in a way a colour change alone does not.
 */

/** The screen's main action. One per screen, e.g. "Play". */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    val colours = SuStreamTheme.colours
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.button),
        colors = ClickableSurfaceDefaults.colors(
            // Transparent so the gradient below shows through; the content colour still drives
            // the text and icon tint.
            containerColor = Color.Transparent,
            contentColor = colours.onPrimary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = colours.onPrimary,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = colours.onPrimary,
            disabledContainerColor = colours.surfaceRaised,
            disabledContentColor = colours.textDisabled,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dimens.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidth, colours.focusRing),
                shape = SuStreamTheme.shapes.button,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = colours.primary, elevation = GLOW_ELEVATION),
        ),
        modifier = modifier.height(Dimens.buttonHeight),
    ) {
        // Gradient fill, clipped to the button shape.
        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(SuStreamTheme.shapes.button)
                    .background(
                        Brush.horizontalGradient(
                            listOf(colours.primary, colours.primaryGradientEnd),
                        ),
                    ),
            )
        }
        ButtonContent(
            text = text,
            icon = icon,
            fillWidth = fillWidth,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Secondary action alongside a [PrimaryButton]: outlined, dark fill. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** Set for destructive actions such as "Delete playlist". */
    destructive: Boolean = false,
) {
    val colours = SuStreamTheme.colours
    val accent = if (destructive) colours.danger else colours.textPrimary

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.button),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colours.surface,
            contentColor = accent,
            focusedContainerColor = if (destructive) colours.danger else colours.focusFill,
            focusedContentColor = colours.onFocusFill,
            pressedContainerColor = if (destructive) colours.danger else colours.focusFill,
            pressedContentColor = colours.onFocusFill,
            disabledContainerColor = colours.surface,
            disabledContentColor = colours.textDisabled,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dimens.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, colours.borderStrong),
                shape = SuStreamTheme.shapes.button,
            ),
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidth, colours.focusRing),
                shape = SuStreamTheme.shapes.button,
            ),
        ),
        modifier = modifier.height(Dimens.buttonHeight),
    ) {
        ButtonContent(
            text = text,
            icon = icon,
            fillWidth = false,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Icon-only button, e.g. the prototype's bookmark and info buttons beside "Play".
 *
 * [contentDescription] is required rather than optional: an icon-only control with no description
 * is invisible to TalkBack, and on a TV that is the only way some users navigate at all.
 */
@Composable
fun IconOnlyButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Set when the control represents an active state, e.g. already in the watchlist. */
    active: Boolean = false,
) {
    val colours = SuStreamTheme.colours

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.button),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) colours.primaryMuted else colours.surface,
            contentColor = if (active) colours.accent else colours.textPrimary,
            focusedContainerColor = colours.focusFill,
            focusedContentColor = colours.onFocusFill,
            pressedContainerColor = colours.focusFill,
            pressedContentColor = colours.onFocusFill,
            disabledContainerColor = colours.surface,
            disabledContentColor = colours.textDisabled,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dimens.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, if (active) colours.primary else colours.borderStrong),
                shape = SuStreamTheme.shapes.button,
            ),
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidth, colours.focusRing),
                shape = SuStreamTheme.shapes.button,
            ),
        ),
        modifier = modifier
            .size(Dimens.iconButtonSize)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(ICON_SIZE),
        )
    }
}

/**
 * Text-only action, for low-emphasis links such as "View all".
 *
 * Still a full focus target with a visible focused state: a bare `Text` with a click handler is
 * unreachable by D-pad, which is the single most common TV accessibility mistake.
 */
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colours = SuStreamTheme.colours

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.chip),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colours.accent,
            focusedContainerColor = colours.focusFill,
            focusedContentColor = colours.onFocusFill,
            pressedContainerColor = colours.focusFill,
            pressedContentColor = colours.onFocusFill,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colours.textDisabled,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidthSubtle, colours.focusRing),
                shape = SuStreamTheme.shapes.chip,
            ),
        ),
        modifier = modifier.defaultMinSize(minHeight = Dimens.buttonHeightCompact),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = TEXT_ACTION_PADDING_H),
        )
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
    fillWidth: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2, Alignment.CenterHorizontally),
        modifier = modifier.padding(horizontal = BUTTON_PADDING_H),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

private val BUTTON_PADDING_H = 20.dp
private val TEXT_ACTION_PADDING_H = 12.dp
private val ICON_SIZE = 20.dp
private val GLOW_ELEVATION = 10.dp
