package com.sustream.tv.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check

/**
 * Form controls for a remote control and an on-screen keyboard.
 *
 * `tv-material` provides no text field, so these are built on `BasicTextField` with the focus
 * treatment the rest of the design system uses. Two TV-specific details that a phone text field
 * would get wrong:
 *
 *  - The field itself is the focus target and the D-pad centre opens the on-screen keyboard, so
 *    the user never has to hunt for a caret.
 *  - `singleLine = true` throughout: multi-line entry on a TV keyboard is unusable, and a URL or a
 *    playlist name is never multi-line anyway.
 */

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    /** Shown beneath the field. Use for validation messages and for the HTTP warning. */
    supportingText: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    /** Masks the value and disables autofill-style suggestions. */
    secret: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colours = SuStreamTheme.colours
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val borderColour = when {
        isError -> colours.danger
        focused -> colours.focusRing
        else -> colours.border
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) colours.danger else colours.textSecondary,
            modifier = Modifier.padding(bottom = Dimens.space1),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.textFieldHeight)
                .clip(SuStreamTheme.shapes.button)
                .background(colours.backgroundDeep)
                .border(
                    width = if (focused) Dimens.focusBorderWidthSubtle else 1.dp,
                    color = borderColour,
                    shape = SuStreamTheme.shapes.button,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                modifier = Modifier.padding(horizontal = FIELD_PADDING_H),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = colours.textTertiary,
                        modifier = Modifier.size(FIELD_ICON_SIZE),
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = true,
                        interactionSource = interactionSource,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = colours.textPrimary,
                        ),
                        cursorBrush = SolidColor(colours.accent),
                        visualTransformation = if (secret) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = imeAction,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colours.textDisabled,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) colours.danger else colours.textTertiary,
                modifier = Modifier.padding(top = Dimens.space1),
            )
        }
    }
}

/**
 * A settings row that toggles.
 *
 * The whole row is the focus target and the click toggles it. A separate focusable switch thumb
 * would mean two D-pad stops per setting and no benefit.
 */
@Composable
fun TvSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        modifier = modifier,
        trailing = { contentColour ->
            SwitchTrack(checked = checked, contentColour = contentColour)
        },
    )
}

/** A settings row that shows its current value and opens a picker. */
@Composable
fun TvSelectRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        trailing = { contentColour ->
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = contentColour,
                maxLines = 1,
            )
        },
    )
}

/** A settings row that performs an action, e.g. "Clear cache". */
@Composable
fun TvActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = enabled,
        destructive = destructive,
        modifier = modifier,
        trailing = null,
    )
}

/** A single-choice option inside a picker dialog. */
@Composable
fun TvChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = true,
        modifier = modifier,
        trailing = { contentColour ->
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = contentColour,
                    modifier = Modifier.size(FIELD_ICON_SIZE),
                )
            }
        },
    )
}

/**
 * The shared row shell.
 *
 * `trailing` receives the current content colour so a trailing element inverts correctly when the
 * row is focused — without that, a trailing value stays dim against the focused fill and becomes
 * unreadable, which is a common oversight in TV settings screens.
 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    trailing: (@Composable (contentColour: Color) -> Unit)?,
) {
    val colours = SuStreamTheme.colours
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val baseColour = when {
        !enabled -> colours.textDisabled
        destructive -> colours.danger
        else -> colours.textPrimary
    }
    val contentColour = if (focused && enabled) colours.onFocusFill else baseColour
    val subtitleColour = if (focused && enabled) colours.onFocusFill else colours.textTertiary

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.button),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colours.surface,
            contentColor = baseColour,
            focusedContainerColor = if (destructive) colours.danger else colours.focusFill,
            focusedContentColor = colours.onFocusFill,
            pressedContainerColor = if (destructive) colours.danger else colours.focusFill,
            pressedContentColor = colours.onFocusFill,
            disabledContainerColor = colours.surface,
            disabledContentColor = colours.textDisabled,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, colours.border),
                shape = SuStreamTheme.shapes.button,
            ),
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidthSubtle, colours.focusRing),
                shape = SuStreamTheme.shapes.button,
            ),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.space4, vertical = Dimens.space3),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColour,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColour,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing?.invoke(contentColour)
        }
    }
}

/** Switch visual. Not focusable itself; the parent row owns the interaction. */
@Composable
private fun SwitchTrack(
    checked: Boolean,
    contentColour: Color,
    modifier: Modifier = Modifier,
) {
    val colours = SuStreamTheme.colours
    Box(
        modifier = modifier
            .size(width = SWITCH_WIDTH, height = SWITCH_HEIGHT)
            .clip(SuStreamTheme.shapes.pill)
            .background(if (checked) colours.primary else colours.borderStrong),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = SWITCH_THUMB_INSET)
                .size(SWITCH_THUMB_SIZE)
                .clip(SuStreamTheme.shapes.pill)
                .background(if (checked) colours.onPrimary else contentColour),
        )
    }
}

private val FIELD_PADDING_H = 14.dp
private val FIELD_ICON_SIZE = 20.dp
private val SWITCH_WIDTH = 44.dp
private val SWITCH_HEIGHT = 24.dp
private val SWITCH_THUMB_SIZE = 16.dp
private val SWITCH_THUMB_INSET = 4.dp
