package com.sustream.tv.presentation.navigation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.designsystem.component.BrandTile
import com.sustream.tv.designsystem.component.StatusDot
import com.sustream.tv.designsystem.icon.SuStreamIcons
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings

/**
 * Left navigation rail: the TV replacement for the prototype's bottom tab bar.
 *
 * The prototype's four-tab bottom bar cannot work with a remote — a bottom bar means the D-pad has
 * to traverse the whole page to reach navigation, then traverse back. A left rail is one press away
 * from anywhere in the content, which is why every Android TV app uses one.
 *
 * It collapses to icons and expands on focus, matching the prototype's sidebar treatment while
 * giving the content the full width when navigation is not in use. `animateContentSize` on the rail
 * rather than the items means the content shifts once, smoothly, instead of each label popping in.
 */
@Composable
fun NavRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Number of playlists that are configured and working; shown as a status line. */
    activePlaylistCount: Int = 0,
    providerConnected: Boolean = false,
    unreadNotifications: Int = 0,
) {
    val colours = SuStreamTheme.colours
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (expanded) Dimens.navRailExpandedWidth else Dimens.navRailCollapsedWidth)
            .animateContentSize(animationSpec = tween(Dimens.FOCUS_ANIMATION_MILLIS))
            .background(colours.surfaceNav)
            .focusGroup()
            // One listener for the whole rail: expanding per item would make the rail jitter as
            // focus moved between items.
            .onFocusChanged { expanded = it.hasFocus }
            .padding(
                horizontal = Dimens.space2,
                vertical = Dimens.overscanVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(Dimens.space1),
    ) {
        RailBrand(expanded = expanded)

        Spacer(modifier = Modifier.height(Dimens.space6))

        RAIL_ITEMS.forEach { item ->
            RailItem(
                item = item,
                selected = currentRoute == item.route,
                expanded = expanded,
                badgeCount = if (item.route == Routes.LIBRARY) unreadNotifications else 0,
                onClick = { onNavigate(item.route) },
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (expanded) {
            RailStatus(
                activePlaylistCount = activePlaylistCount,
                providerConnected = providerConnected,
            )
        }
    }
}

@Composable
private fun RailBrand(expanded: Boolean) {
    val colours = SuStreamTheme.colours

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        modifier = Modifier.padding(start = Dimens.space2),
    ) {
        BrandTile {
            Icon(
                imageVector = SuStreamIcons.Zap,
                contentDescription = null,
                tint = colours.onPrimary,
                modifier = Modifier.size(BRAND_ICON_SIZE),
            )
        }
        if (expanded) {
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = colours.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.app_tagline).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colours.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    item: RailDestination,
    selected: Boolean,
    expanded: Boolean,
    badgeCount: Int,
    onClick: () -> Unit,
) {
    val colours = SuStreamTheme.colours
    val label = stringResource(item.labelRes)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = SuStreamTheme.shapes.button),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colours.primaryMuted else colours.surfaceNav,
            contentColor = if (selected) colours.accent else colours.textTertiary,
            focusedContainerColor = colours.focusFill,
            focusedContentColor = colours.onFocusFill,
            pressedContainerColor = colours.focusFill,
            pressedContentColor = colours.onFocusFill,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) {
                Border(
                    border = BorderStroke(1.dp, colours.primary),
                    shape = SuStreamTheme.shapes.button,
                )
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = BorderStroke(Dimens.focusBorderWidthSubtle, colours.focusRing),
                shape = SuStreamTheme.shapes.button,
            ),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.navRailItemHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.space3),
        ) {
            Icon(
                imageVector = item.icon,
                // Collapsed, the icon is the only label there is, so it must carry the description.
                contentDescription = if (expanded) null else label,
                modifier = Modifier.size(Dimens.navRailIconSize),
            )
            if (expanded) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (badgeCount > 0) {
                    StatusDot(colour = colours.unread, size = UNREAD_DOT_SIZE)
                }
            }
        }
    }
}

/**
 * Status footer, echoing the prototype's header strip ("Debrid Resolver: Connected", "IPTV: 2
 * Playlists Active").
 *
 * Only reports what is actually true of this device. The prototype's version also claimed uptime
 * percentages and cache-hit rates, which the client cannot measure.
 */
@Composable
private fun RailStatus(
    activePlaylistCount: Int,
    providerConnected: Boolean,
) {
    val colours = SuStreamTheme.colours

    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        modifier = Modifier.padding(horizontal = Dimens.space3, vertical = Dimens.space2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            StatusDot(
                colour = if (providerConnected) colours.healthy else colours.textDisabled,
                pulsing = providerConnected,
            )
            Text(
                text = if (providerConnected) {
                    stringResource(R.string.settings_provider_connected)
                } else {
                    stringResource(R.string.settings_provider_not_connected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colours.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Icon(
                imageVector = SuStreamIcons.Radio,
                contentDescription = null,
                tint = colours.iptvAccentSoft,
                modifier = Modifier.size(STATUS_ICON_SIZE),
            )
            Text(
                text = if (activePlaylistCount > 0) {
                    activePlaylistCount.toString() + " active"
                } else {
                    stringResource(R.string.iptv_no_playlists_title)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colours.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class RailDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val RAIL_ITEMS = listOf(
    RailDestination(Routes.HOME, R.string.nav_home, SuStreamIcons.Zap),
    RailDestination(Routes.FILMS, R.string.nav_films, SuStreamIcons.Film),
    RailDestination(Routes.TV, R.string.nav_tv, SuStreamIcons.Tv),
    RailDestination(Routes.LIVE, R.string.nav_live, SuStreamIcons.Radio),
    RailDestination(Routes.SEARCH, R.string.nav_search, Icons.Filled.Search),
    RailDestination(Routes.LIBRARY, R.string.nav_library, SuStreamIcons.Bookmark),
    RailDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

private val BRAND_ICON_SIZE = 22.dp
private val STATUS_ICON_SIZE = 14.dp
private val UNREAD_DOT_SIZE = 8.dp
