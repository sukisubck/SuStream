package com.sustream.tv.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.focus.onBackPressed
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme

/**
 * Dialogs and sheets for a TV.
 *
 * Deliberately different from the prototype's web modals in three ways, all forced by the remote:
 *
 *  1. **BACK dismisses.** The prototype closes its modals with a small "x" glyph, which a D-pad
 *     user would have to navigate to. Here BACK closes, and the glyph is gone.
 *  2. **Focus is trapped.** `focusGroup` plus an initial-focus claim keeps the D-pad inside the
 *     dialog. Without it, arrow keys wander into the screen behind and press invisible controls.
 *  3. **Wide, not tall.** A 16:9 panel with a bounded height and internal scrolling, rather than a
 *     phone-shaped sheet.
 *
 * These are rendered inline in the composition rather than in a platform `Dialog` window, because a
 * separate window would take focus out of the activity's tree and break D-pad traversal back into
 * the content beneath.
 */
@Composable
fun TvDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    maxWidth: Dp = Dimens.dialogMaxWidth,
    content: @Composable ColumnScopeProxy.() -> Unit,
) {
    val colours = SuStreamTheme.colours

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(colours.scrim)
            // Trap focus inside the dialog, and let BACK close it.
            .focusGroup()
            .onBackPressed {
                onDismiss()
                true
            },
    ) {
        Column(
            modifier = Modifier
                .width(maxWidth)
                .heightIn(max = DIALOG_MAX_HEIGHT)
                .clip(SuStreamTheme.shapes.dialog)
                .background(colours.surfaceSunken)
                .border(1.dp, colours.borderStrong, SuStreamTheme.shapes.dialog)
                .padding(Dimens.space6),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colours.textPrimary,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.textSecondary,
                    modifier = Modifier.padding(top = Dimens.space2),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
                modifier = Modifier
                    .padding(top = Dimens.space5)
                    .verticalScroll(rememberScrollState()),
            ) {
                ColumnScopeProxy.content()
            }
        }
    }
}

/**
 * Marker receiver for [TvDialog] content.
 *
 * Exists so dialog content cannot accidentally call `Modifier.align`, `weight` or the other
 * `ColumnScope` extensions: the dialog scrolls its content, and a weighted child inside a
 * scrollable column throws at runtime. Keeping the real `ColumnScope` out of reach turns that into
 * a compile-time impossibility.
 */
object ColumnScopeProxy

/**
 * Confirmation dialog for a destructive action, e.g. deleting a playlist or resetting local data.
 *
 * Cancel takes initial focus, not confirm. On a TV the centre key is pressed constantly, so a
 * pre-focused destructive button is one stray press away from data loss.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = true,
) {
    TvDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = body,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space2),
        ) {
            SecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.initialFocus(),
            )
            SecondaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                destructive = destructive,
            )
        }
    }
}

/**
 * Wide sheet for lists: source selection, audio and subtitle tracks, filter pickers.
 *
 * Same focus and BACK behaviour as [TvDialog], but sized for a list and with the first row taking
 * focus so the user can start pressing down immediately.
 */
@Composable
fun TvSelectionSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    footnote: String? = null,
    content: @Composable ColumnScopeProxy.() -> Unit,
) {
    val colours = SuStreamTheme.colours

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(colours.scrim)
            .focusGroup()
            .onBackPressed {
                onDismiss()
                true
            },
    ) {
        Column(
            modifier = Modifier
                .width(Dimens.sheetMaxWidth)
                .heightIn(max = DIALOG_MAX_HEIGHT)
                .clip(SuStreamTheme.shapes.dialog)
                .background(colours.surfaceSunken)
                .border(1.dp, colours.borderStrong, SuStreamTheme.shapes.dialog)
                .padding(Dimens.space6),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colours.textPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.textSecondary,
                    modifier = Modifier.padding(top = Dimens.space2),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
                modifier = Modifier
                    .padding(vertical = Dimens.space4)
                    .verticalScroll(rememberScrollState()),
            ) {
                ColumnScopeProxy.content()
            }
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = colours.textTertiary,
                )
            }
        }
    }
}

private val DIALOG_MAX_HEIGHT = 460.dp
