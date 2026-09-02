package com.sustream.tv.presentation.live

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sustream.tv.R
import com.sustream.tv.core.di.LocalAppContainer
import com.sustream.tv.core.di.suStreamViewModel
import com.sustream.tv.core.log.Secret
import com.sustream.tv.designsystem.component.ChannelRow
import com.sustream.tv.designsystem.component.ChipGroup
import com.sustream.tv.designsystem.component.ChipRow
import com.sustream.tv.designsystem.component.ConfirmDialog
import com.sustream.tv.designsystem.component.EmptyState
import com.sustream.tv.designsystem.component.ErrorState
import com.sustream.tv.designsystem.component.InlineError
import com.sustream.tv.designsystem.component.PrimaryButton
import com.sustream.tv.designsystem.component.SecondaryButton
import com.sustream.tv.designsystem.component.SectionHeader
import com.sustream.tv.designsystem.component.TvActionRow
import com.sustream.tv.designsystem.component.TvDialog
import com.sustream.tv.designsystem.component.TvTextField
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.domain.model.Channel
import com.sustream.tv.domain.model.ChannelCategory
import com.sustream.tv.domain.model.Playlist
import com.sustream.tv.domain.model.PlaylistOrigin

/** The Live TV hub for playlists that the user has added to this device. */
@Composable
fun LiveTvScreen(
    onPlayChannel: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LiveTvViewModel = suStreamViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LiveTab.CHANNELS) }
    var showAddPlaylist by rememberSaveable { mutableStateOf(false) }
    var playlistIdToDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        modifier = modifier
            .fillMaxSize()
            .background(SuStreamTheme.colours.background)
            .padding(horizontal = Dimens.overscanHorizontal, vertical = Dimens.overscanVertical),
    ) {
        SectionHeader(
            title = stringResource(R.string.iptv_title),
            subtitle = stringResource(R.string.iptv_subtitle),
            iconTint = SuStreamTheme.colours.iptvAccent,
            trailing = {
                PrimaryButton(
                    text = stringResource(R.string.iptv_add_playlist),
                    onClick = { showAddPlaylist = true },
                )
            },
        )

        ChipGroup(
            options = LiveTab.entries,
            selected = tab,
            onSelect = { tab = it },
            label = { stringResource(it.labelRes) },
            accent = SuStreamTheme.colours.iptvAccent,
            onAccent = SuStreamTheme.colours.onAccent,
        )

        state.error?.let { error -> InlineError(error = error, onRetry = viewModel::clearError) }

        when (tab) {
            LiveTab.CHANNELS -> ChannelList(
                state = state,
                onSelectPlaylist = viewModel::selectPlaylist,
                onSelectCategory = viewModel::selectCategory,
                onPlayChannel = onPlayChannel,
                onAddPlaylist = { showAddPlaylist = true },
            )
            LiveTab.FAVOURITES -> FavouriteList(
                state = state,
                onPlayChannel = onPlayChannel,
                onAddPlaylist = { showAddPlaylist = true },
            )
            LiveTab.PLAYLISTS -> PlaylistList(
                state = state,
                onOpenChannels = { playlist ->
                    viewModel.selectPlaylist(playlist.id)
                    tab = LiveTab.CHANNELS
                },
                onRefresh = viewModel::refreshPlaylist,
                onDelete = { playlistIdToDelete = it.id },
                onAddPlaylist = { showAddPlaylist = true },
            )
            LiveTab.GUIDE -> GuideUnavailable(onOpenPlaylists = { tab = LiveTab.PLAYLISTS })
        }
    }

    if (showAddPlaylist) {
        AddPlaylistDialog(
            onDismiss = { showAddPlaylist = false },
            onAdd = viewModel::addPlaylist,
        )
    }
    state.playlists.firstOrNull { it.id == playlistIdToDelete }?.let { playlist ->
        ConfirmDialog(
            title = stringResource(R.string.iptv_delete_confirm_title, playlist.name),
            body = stringResource(R.string.iptv_delete_confirm_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.deletePlaylist(playlist.id)
                playlistIdToDelete = null
            },
            onDismiss = { playlistIdToDelete = null },
        )
    }
}

@Composable
private fun ChannelList(
    state: LiveTvUiState,
    onSelectPlaylist: (String?) -> Unit,
    onSelectCategory: (String) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    if (state.playlists.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.iptv_no_playlists_title),
            body = stringResource(R.string.iptv_no_playlists_body),
            actionLabel = stringResource(R.string.iptv_add_playlist),
            onAction = onAddPlaylist,
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        contentPadding = PaddingValues(bottom = Dimens.overscanVertical),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ChipRow(
                options = state.playlists,
                selected = state.playlists.firstOrNull { it.id == state.selectedPlaylistId },
                onSelect = { onSelectPlaylist(it.id) },
                label = { it.name },
                key = { it.id },
                accent = SuStreamTheme.colours.iptvAccent,
                onAccent = SuStreamTheme.colours.onAccent,
            )
        }
        item {
            ChipRow(
                options = listOf(ChannelCategory(ChannelCategory.ALL, state.channels.size)) + state.categories,
                selected = state.categories.firstOrNull { it.name == state.selectedCategory }
                    ?: ChannelCategory(ChannelCategory.ALL, state.channels.size),
                onSelect = { onSelectCategory(it.name) },
                label = {
                    if (it.name == ChannelCategory.ALL) stringResource(R.string.iptv_category_all)
                    else it.name
                },
                key = { it.name },
                accent = SuStreamTheme.colours.iptvAccent,
                onAccent = SuStreamTheme.colours.onAccent,
            )
        }
        if (state.channels.isEmpty()) {
            item {
                EmptyState(body = stringResource(R.string.iptv_empty_channels))
            }
        } else {
            items(state.channels, key = { it.id }) { channel ->
                ChannelRow(
                    channel = channel,
                    nowProgramme = channel.tvgId?.let(state.schedules::get)?.now,
                    onClick = { onPlayChannel(channel) },
                )
            }
        }
    }
}

@Composable
private fun FavouriteList(
    state: LiveTvUiState,
    onPlayChannel: (Channel) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    if (state.playlists.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.iptv_no_playlists_title),
            body = stringResource(R.string.iptv_no_playlists_body),
            actionLabel = stringResource(R.string.iptv_add_playlist),
            onAction = onAddPlaylist,
        )
    } else if (state.favouriteChannels.isEmpty()) {
        EmptyState(body = stringResource(R.string.iptv_empty_favourites))
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            items(state.favouriteChannels, key = { it.id }) { channel ->
                ChannelRow(channel = channel, nowProgramme = null, onClick = { onPlayChannel(channel) })
            }
        }
    }
}

@Composable
private fun PlaylistList(
    state: LiveTvUiState,
    onOpenChannels: (Playlist) -> Unit,
    onRefresh: (String) -> Unit,
    onDelete: (Playlist) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    if (state.playlists.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.iptv_no_playlists_title),
            body = stringResource(R.string.iptv_no_playlists_body),
            actionLabel = stringResource(R.string.iptv_add_playlist),
            onAction = onAddPlaylist,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        items(state.playlists, key = { it.id }) { playlist ->
            val status = when (playlist.status) {
                com.sustream.tv.domain.model.PlaylistStatus.OK ->
                    stringResource(R.string.iptv_channel_count, playlist.channelCount)
                com.sustream.tv.domain.model.PlaylistStatus.NEVER_SYNCED ->
                    stringResource(R.string.iptv_status_never_synced)
                com.sustream.tv.domain.model.PlaylistStatus.PARSE_FAILED ->
                    stringResource(R.string.iptv_status_parse_failed)
                com.sustream.tv.domain.model.PlaylistStatus.UNREACHABLE ->
                    stringResource(R.string.iptv_status_unreachable)
                com.sustream.tv.domain.model.PlaylistStatus.AUTH_FAILED ->
                    stringResource(R.string.iptv_status_auth_failed)
            }
            TvActionRow(title = playlist.name, subtitle = status, onClick = { onOpenChannels(playlist) })
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                SecondaryButton(
                    text = stringResource(R.string.action_refresh),
                    enabled = playlist.id !in state.refreshingPlaylistIds,
                    onClick = { onRefresh(playlist.id) },
                )
                SecondaryButton(
                    text = stringResource(R.string.action_delete),
                    onClick = { onDelete(playlist) },
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun GuideUnavailable(onOpenPlaylists: () -> Unit) {
    EmptyState(
        body = stringResource(R.string.iptv_no_epg),
        actionLabel = stringResource(R.string.iptv_tab_playlists),
        onAction = onOpenPlaylists,
    )
}

@Composable
private fun AddPlaylistDialog(
    onDismiss: () -> Unit,
    onAdd: (
        name: String,
        origin: PlaylistOrigin,
        epgUrl: String?,
        password: Secret?,
        cleartextAcknowledged: Boolean,
        onAdded: () -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(PlaylistKind.M3U_URL) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var fileUri by rememberSaveable { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    val httpWarning = (if (kind == PlaylistKind.XTREAM) url else url)
        .trim().startsWith("http://", ignoreCase = true)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fileUri = uri.toString()
        fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }
                ?.let { index -> if (cursor.moveToFirst()) cursor.getString(index) else null }
        } ?: "Playlist file"
        if (name.isBlank()) name = fileName.orEmpty()
    }

    TvDialog(
        title = stringResource(R.string.iptv_add_playlist),
        subtitle = stringResource(R.string.iptv_no_playlists_body),
        onDismiss = onDismiss,
    ) {
        ChipGroup(
            options = PlaylistKind.entries,
            selected = kind,
            onSelect = { kind = it },
            label = { stringResource(it.labelRes) },
            accent = SuStreamTheme.colours.iptvAccent,
            onAccent = SuStreamTheme.colours.onAccent,
        )
        TvTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.iptv_field_name))
        when (kind) {
            PlaylistKind.M3U_URL -> {
                TvTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.iptv_field_url),
                    supportingText = if (httpWarning) stringResource(R.string.iptv_cleartext_warning) else null,
                    isError = httpWarning,
                )
                TvTextField(value = epgUrl, onValueChange = { epgUrl = it }, label = stringResource(R.string.iptv_field_epg_url))
            }
            PlaylistKind.FILE -> {
                SecondaryButton(text = stringResource(R.string.iptv_field_pick_file), onClick = {
                    filePicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "text/plain"))
                })
                fileName?.let { TvTextField(value = it, onValueChange = {}, label = stringResource(R.string.iptv_field_pick_file), enabled = false) }
            }
            PlaylistKind.XTREAM -> {
                TvTextField(value = url, onValueChange = { url = it }, label = stringResource(R.string.iptv_field_server), supportingText = stringResource(R.string.iptv_xtream_note))
                TvTextField(value = username, onValueChange = { username = it }, label = stringResource(R.string.iptv_field_username))
                TvTextField(value = password, onValueChange = { password = it }, label = stringResource(R.string.iptv_field_password), secret = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.initialFocus())
            PrimaryButton(
                text = if (httpWarning) stringResource(R.string.iptv_cleartext_acknowledge) else stringResource(R.string.iptv_add_playlist),
                enabled = name.isNotBlank() && when (kind) {
                    PlaylistKind.M3U_URL -> url.isNotBlank()
                    PlaylistKind.FILE -> fileUri != null
                    PlaylistKind.XTREAM -> url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                },
                onClick = {
                    val origin = when (kind) {
                        PlaylistKind.M3U_URL -> PlaylistOrigin.M3uUrl(url.trim())
                        PlaylistKind.FILE -> PlaylistOrigin.M3uFile(fileUri!!, fileName ?: "Playlist file")
                        PlaylistKind.XTREAM -> PlaylistOrigin.Xtream(url.trim(), username.trim())
                    }
                    onAdd(name, origin, epgUrl.takeIf { it.isNotBlank() }, password.takeIf { kind == PlaylistKind.XTREAM }?.let(::Secret), httpWarning, onDismiss)
                },
            )
        }
    }
}

private enum class LiveTab(val labelRes: Int) {
    CHANNELS(R.string.iptv_tab_channels),
    GUIDE(R.string.iptv_tab_guide),
    FAVOURITES(R.string.iptv_tab_favourites),
    PLAYLISTS(R.string.iptv_tab_playlists),
}

private enum class PlaylistKind(val labelRes: Int) {
    M3U_URL(R.string.iptv_add_playlist_m3u_url),
    FILE(R.string.iptv_add_playlist_m3u_file),
    XTREAM(R.string.iptv_add_playlist_xtream),
}
