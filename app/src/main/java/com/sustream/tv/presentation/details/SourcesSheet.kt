package com.sustream.tv.presentation.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.designsystem.component.Badge
import com.sustream.tv.designsystem.component.OutlineBadge
import com.sustream.tv.designsystem.component.TvSelectionSheet
import com.sustream.tv.designsystem.component.TvChoiceRow
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.domain.model.Authorisation
import com.sustream.tv.domain.model.PlayableSource

/**
 * Source selection.
 *
 * This is the screen that most directly replaces the prototype's resolver modal, and the difference
 * is deliberate. The prototype listed `4K HDR10+ REMUX · 22.4 GB · 342 Seeders · Real-Debrid
 * (Cached)` — a torrent workflow's vocabulary, describing infringing copies located by scraping.
 *
 * What this shows instead, for every source:
 *  * the label the provider itself gave it;
 *  * **where the authorisation comes from** — which of the user's playlists, or which of their
 *    provider accounts. That line is the point: provenance is stated, never implied.
 *  * quality only when the provider reported it, never inferred from a filename.
 *
 * There are no seeders, no file sizes presented as quality signals, and no cache claims, because
 * none of those concepts exist in a lawful source.
 */
@Composable
fun SourcesSheet(
    sources: List<PlayableSource>,
    onSelect: (PlayableSource) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = SuStreamTheme.colours
    val hasDemoSource = sources.any { it.isDemo }

    TvSelectionSheet(
        title = stringResource(R.string.sources_sheet_title),
        subtitle = stringResource(R.string.sources_sheet_subtitle),
        footnote = if (hasDemoSource) stringResource(R.string.sources_demo_notice) else null,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        sources.forEachIndexed { index, source ->
            SourceRow(
                source = source,
                onClick = { onSelect(source) },
                // The first row takes focus so the user can press select immediately.
                modifier = if (index == 0) Modifier.initialFocus() else Modifier,
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: PlayableSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = SuStreamTheme.colours

    Column(modifier = modifier.fillMaxWidth()) {
        TvChoiceRow(
            title = source.label,
            subtitle = provenance(source),
            selected = false,
            onClick = onClick,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            modifier = Modifier.padding(
                start = Dimens.space4,
                top = Dimens.space1,
                bottom = Dimens.space2,
            ),
        ) {
            if (source.isLive) {
                Badge(
                    text = stringResource(R.string.iptv_live_badge),
                    containerColour = colours.live,
                    contentColour = colours.onLive,
                )
            }
            // Only shown when the provider reported it. Absent is absent, not "unknown quality".
            source.qualityLabel?.let { OutlineBadge(text = it) }
            source.audioLanguage?.let { OutlineBadge(text = it) }
            if (source.isDemo) {
                Badge(
                    text = "DEMO",
                    containerColour = colours.warning,
                    contentColour = colours.onAccent,
                )
            }
        }
    }
}

/**
 * The provenance line: exactly why this source is allowed to play.
 *
 * Exhaustive over [Authorisation], so a new authorisation basis cannot be added without deciding how
 * to explain it to the user.
 */
@Composable
private fun provenance(source: PlayableSource): String = when (val basis = source.authorisation) {
    is Authorisation.UserPlaylist ->
        stringResource(R.string.sources_authorised_by_playlist, basis.playlistName)

    is Authorisation.UserProviderLibrary ->
        stringResource(R.string.sources_authorised_by_provider, basis.provider)

    is Authorisation.UserAddon ->
        stringResource(R.string.sources_authorised_by_addon, basis.addonName)

    Authorisation.Demo -> stringResource(R.string.sources_demo_notice)
}
