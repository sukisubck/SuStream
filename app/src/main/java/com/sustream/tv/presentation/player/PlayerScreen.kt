package com.sustream.tv.presentation.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sustream.tv.R
import com.sustream.tv.core.di.LocalAppContainer
import com.sustream.tv.core.util.Formatters
import com.sustream.tv.designsystem.component.Badge
import com.sustream.tv.designsystem.component.ErrorState
import com.sustream.tv.designsystem.component.IconOnlyButton
import com.sustream.tv.designsystem.component.Spinner
import com.sustream.tv.designsystem.component.TvChoiceRow
import com.sustream.tv.designsystem.component.TvSelectionSheet
import com.sustream.tv.designsystem.focus.initialFocus
import com.sustream.tv.designsystem.icon.SuStreamIcons
import com.sustream.tv.designsystem.theme.Dimens
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.domain.model.EpisodeRef
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.presentation.details.SourcesSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow

/**
 * Full-screen player.
 *
 * ## Focus and key handling
 *
 * The whole screen is one focus target holding a key handler, rather than a set of individually
 * focusable buttons. That is the correct model for a TV player: the D-pad's left and right mean
 * *scrub*, not *move between buttons*, and centre means play/pause. Making the buttons focusable
 * would force the user to navigate to a rewind button before rewinding, which no television player
 * does. The buttons are drawn as affordances showing what the keys do.
 *
 * The exception is the sheets, which are lists and are focusable in the ordinary way.
 *
 * ## Lifecycle
 *
 * `ON_PAUSE` and `ON_RESUME` are observed rather than `ON_STOP`/`ON_START`, because a Fire TV
 * overlay (the launcher's quick menu, a system dialog) pauses without stopping, and video playing
 * behind an overlay the user cannot see is both confusing and a waste of bandwidth.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    request: PlaybackRequest,
    onExit: () -> Unit,
    onPlayEpisode: (EpisodeRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val viewModel: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "player-" + request.hashCode(),
        factory = container.playerViewModelFactory(request),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val colours = SuStreamTheme.colours

    // Pause when the app leaves the foreground; re-resolve and resume when it returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onBackground()
                Lifecycle.Event.ON_RESUME -> viewModel.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Autoplay: when the previous episode finished and a next one exists, navigate to it.
    LaunchedEffect(state.pendingNextEpisode) {
        val next = state.pendingNextEpisode ?: return@LaunchedEffect
        viewModel.consumeNextEpisode()
        onPlayEpisode(next)
    }

    LaunchedEffect(state.phase) {
        if (state.phase == PlayerPhase.FINISHED) onExit()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .initialFocus()
            .onKeyEvent { event -> handleKey(event.key, event.type, state, viewModel, onExit) },
    ) {
        // The video surface. SurfaceView rather than TextureView: it is composited by the display
        // hardware, which on a Fire TV Stick is the difference between smooth 1080p and dropped
        // frames. The cost is that it cannot be animated or transformed, which a full-screen player
        // never needs to do.
        PlayerSurface(
            player = container.playerManager.ensurePlayer(
                preferredQuality = com.sustream.tv.domain.model.PreferredQuality.AUTO,
            ),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.fillMaxSize(),
        )

        if (playerState.isBuffering || state.phase == PlayerPhase.PREPARING) {
            BufferingOverlay()
        }

        if (state.phase == PlayerPhase.ERROR && state.error != null) {
            PlaybackErrorOverlay(
                error = state.error!!,
                onRetry = viewModel::retry,
                onSwitchSource = viewModel::switchSource,
                onExit = onExit,
            )
        }

        AnimatedVisibility(
            visible = state.controlsVisible && state.phase != PlayerPhase.ERROR,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControls(
                state = state,
                hasAlternativeAudio = playerState.hasAlternativeAudio,
                hasSubtitles = playerState.hasSubtitles,
                isPlaying = playerState.isPlaying,
                onPlayPause = viewModel::togglePlayPause,
                onRewind = viewModel::rewind,
                onFastForward = viewModel::fastForward,
                onOpenSheet = viewModel::openSheet,
            )
        }

        when (state.openSheet) {
            PlayerSheet.AUDIO -> TrackSheet(
                title = stringResource(R.string.player_audio_track),
                options = playerState.audioTracks.map { it.id to it.label },
                selectedId = playerState.selectedAudioTrackId,
                allowOff = false,
                onSelect = viewModel::selectAudioTrack,
                onDismiss = viewModel::closeSheet,
            )

            PlayerSheet.SUBTITLES -> TrackSheet(
                title = stringResource(R.string.player_subtitles),
                options = playerState.subtitleTracks.map { it.id to it.label },
                selectedId = playerState.selectedSubtitleTrackId,
                allowOff = true,
                onSelect = viewModel::selectSubtitleTrack,
                onDismiss = viewModel::closeSheet,
            )

            PlayerSheet.SOURCES -> SourcesSheet(
                sources = state.availableSources,
                onSelect = viewModel::playSource,
                onDismiss = viewModel::closeSheet,
            )

            PlayerSheet.NONE -> Unit
        }
    }
}

/**
 * Remote key handling.
 *
 * Returns true when the key was consumed. Only `KeyUp` is acted on: Fire TV remotes send both down
 * and up, and acting on down makes a long press fire repeatedly.
 */
private fun handleKey(
    key: Key,
    type: KeyEventType,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onExit: () -> Unit,
): Boolean {
    if (type != KeyEventType.KeyUp) return false

    // A sheet is open: let its own focus handling deal with everything except back.
    if (state.openSheet != PlayerSheet.NONE) {
        return if (key == Key.Back || key == Key.Escape) {
            viewModel.closeSheet()
            true
        } else {
            false
        }
    }

    return when (key) {
        Key.Back, Key.Escape -> {
            // Layered: close a sheet, else hide the controls, else actually leave.
            if (viewModel.onBackPressed()) true else { onExit(); true }
        }

        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA,
        Key.MediaPlayPause, Key.Spacebar,
        -> { viewModel.togglePlayPause(); true }

        Key.MediaPlay -> { viewModel.togglePlayPause(); true }
        Key.MediaPause -> { viewModel.togglePlayPause(); true }

        // Left and right scrub. This is why the transport buttons are not focus targets: on a TV
        // player the D-pad's horizontal axis belongs to the timeline, not to a button row.
        Key.DirectionLeft, Key.MediaRewind -> { viewModel.rewind(); true }
        Key.DirectionRight, Key.MediaFastForward -> { viewModel.fastForward(); true }

        // Up reveals the controls without changing anything.
        Key.DirectionUp -> { viewModel.showControls(); true }

        Key.Captions -> { viewModel.openSheet(PlayerSheet.SUBTITLES); true }

        else -> false
    }
}

@Composable
private fun BufferingOverlay() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spinner()
            Text(
                text = stringResource(R.string.player_buffering),
                style = MaterialTheme.typography.bodyMedium,
                color = SuStreamTheme.colours.textSecondary,
                modifier = Modifier.padding(top = Dimens.space3),
            )
        }
    }
}

@Composable
private fun PlaybackErrorOverlay(
    error: com.sustream.tv.core.result.AppError,
    onRetry: () -> Unit,
    onSwitchSource: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SuStreamTheme.colours.scrim),
    ) {
        ErrorState(
            error = error,
            onRetry = onRetry,
            secondaryActionLabel = stringResource(R.string.player_error_switch_source),
            onSecondaryAction = onSwitchSource,
        )
    }
}

/**
 * The control bar.
 *
 * Drawn over a bottom-up scrim so it stays readable over a bright scene, and laid out as
 * title / seek bar / transport, which is the arrangement the prototype uses and what a TV viewer
 * expects to scan top-to-bottom.
 */
@Composable
private fun PlayerControls(
    state: PlayerUiState,
    hasAlternativeAudio: Boolean,
    hasSubtitles: Boolean,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onFastForward: () -> Unit,
    onOpenSheet: (PlayerSheet) -> Unit,
) {
    val colours = SuStreamTheme.colours

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, colours.backgroundDeep.copy(alpha = 0.92f)),
                    ),
                )
                .padding(
                    horizontal = Dimens.overscanHorizontal,
                    vertical = Dimens.overscanVertical,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colours.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.isLive) {
                    Badge(
                        text = stringResource(R.string.player_live_badge),
                        containerColour = colours.live,
                        contentColour = colours.onLive,
                    )
                }
            }

            // Live streams get no seek bar: there is no meaningful position to show, and a bar the
            // user cannot scrub is worse than none.
            if (!state.isLive) {
                SeekBar(state = state)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
            ) {
                if (!state.isLive) {
                    ControlGlyph(
                        icon = SuStreamIcons.Rewind,
                        description = stringResource(R.string.player_rewind),
                    )
                }
                ControlGlyph(
                    icon = if (isPlaying) SuStreamIcons.Pause else Icons.Filled.PlayArrow,
                    description = stringResource(R.string.player_play_pause),
                    emphasised = true,
                )
                if (!state.isLive) {
                    ControlGlyph(
                        icon = SuStreamIcons.FastForward,
                        description = stringResource(R.string.player_fast_forward),
                    )
                }

                Box(modifier = Modifier.weight(1f))

                // These two *are* focusable, because they open a list and there is no remote key
                // that means "audio track".
                if (hasAlternativeAudio) {
                    IconOnlyButton(
                        icon = SuStreamIcons.AudioTrack,
                        contentDescription = stringResource(R.string.player_audio_track),
                        onClick = { onOpenSheet(PlayerSheet.AUDIO) },
                    )
                }
                if (hasSubtitles) {
                    IconOnlyButton(
                        icon = SuStreamIcons.Subtitles,
                        contentDescription = stringResource(R.string.player_subtitles),
                        onClick = { onOpenSheet(PlayerSheet.SUBTITLES) },
                    )
                }
            }
        }
    }
}

/**
 * A transport glyph.
 *
 * Deliberately not clickable and not focusable: it shows what the corresponding remote key does.
 * Making it a button would put it in the D-pad's path and break scrubbing.
 */
@Composable
private fun ControlGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    emphasised: Boolean = false,
) {
    val colours = SuStreamTheme.colours
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (emphasised) Dimens.playerPrimaryButtonSize else Dimens.playerButtonSize)
            .clip(SuStreamTheme.shapes.pill)
            .background(
                if (emphasised) colours.primary else colours.surface.copy(alpha = 0.8f),
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (emphasised) colours.onPrimary else colours.textPrimary,
            modifier = Modifier.size(if (emphasised) GLYPH_LARGE else GLYPH_NORMAL),
        )
    }
}

@Composable
private fun SeekBar(state: PlayerUiState) {
    val colours = SuStreamTheme.colours

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.playerSeekBarHeight)
                .clip(SuStreamTheme.shapes.pill)
                .background(colours.borderStrong),
        ) {
            // Buffered ahead of the playhead, drawn first so the position bar sits on top.
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.bufferedFraction)
                    .height(Dimens.playerSeekBarHeight)
                    .background(colours.textDisabled),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.progressFraction)
                    .height(Dimens.playerSeekBarHeight)
                    .clip(SuStreamTheme.shapes.pill)
                    .background(colours.primary),
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = Formatters.clockPosition(state.positionMillis),
                style = MaterialTheme.typography.labelMedium,
                color = colours.textSecondary,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = Formatters.clockPosition(state.durationMillis),
                style = MaterialTheme.typography.labelMedium,
                color = colours.textTertiary,
            )
        }
    }
}

/** Audio or subtitle track picker. */
@Composable
private fun TrackSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    allowOff: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    TvSelectionSheet(
        title = title,
        subtitle = if (options.isEmpty()) {
            stringResource(R.string.player_no_alternative_tracks)
        } else {
            null
        },
        onDismiss = onDismiss,
    ) {
        if (allowOff) {
            TvChoiceRow(
                title = stringResource(R.string.player_subtitles_off),
                selected = selectedId == null,
                onClick = { onSelect(null) },
                modifier = Modifier.initialFocus(),
            )
        }
        options.forEachIndexed { index, (id, label) ->
            TvChoiceRow(
                title = label,
                selected = id == selectedId,
                onClick = { onSelect(id) },
                modifier = if (!allowOff && index == 0) Modifier.initialFocus() else Modifier,
            )
        }
    }
}

private val GLYPH_NORMAL = 24.dp
private val GLYPH_LARGE = 32.dp
