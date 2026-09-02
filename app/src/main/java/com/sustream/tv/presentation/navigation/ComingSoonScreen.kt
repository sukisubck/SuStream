package com.sustream.tv.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.designsystem.component.SecondaryButton
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme

/**
 * Placeholder for a section whose screen has not been written yet.
 *
 * It exists so the navigation rail has **no dead ends**: on a TV, a rail entry that leads to a blank
 * screen with nothing focusable traps the user, because there is no focusable target to press BACK
 * from and no indication anything happened.
 *
 * It is deliberately explicit that the feature is unbuilt rather than broken, and it names what the
 * screen will do — so anyone clicking through the build can tell the difference between "not written
 * yet" and "written and failing". Each instance is removed as its real screen lands; see
 * `docs/HANDOVER.md` for the outstanding list.
 */
@Composable
fun ComingSoonScreen(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val colours = SuStreamTheme.colours

    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.overscanHorizontal),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = colours.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = colours.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Dimens.space3)
                .width(BODY_WIDTH),
        )
        Text(
            text = "This screen has not been built yet. The data layer behind it is complete and " +
                "tested; only the presentation is outstanding.",
            style = MaterialTheme.typography.bodySmall,
            color = colours.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Dimens.space4)
                .width(BODY_WIDTH),
        )

        if (onBack != null) {
            SecondaryButton(
                text = "Back to Home",
                onClick = onBack,
                // Something focusable, so the section is never a trap for a D-pad user.
                modifier = Modifier
                    .padding(top = Dimens.space6)
                    .initialFocus(),
            )
        }
    }
}

private val BODY_WIDTH = 560.dp
