package com.sustream.tv.designsystem.focus

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * D-pad focus utilities.
 *
 * Focus is the entire interaction model on a TV. Two problems have to be solved everywhere, and
 * getting them wrong is what makes a TV app feel broken:
 *
 *  1. **Initial focus.** A screen with nothing focused swallows every key press. Every screen must
 *     claim focus on first composition — see [rememberInitialFocus].
 *  2. **Focus restoration.** Returning from Details must land on the card you left from, not the
 *     first item in the rail. Compose's own `Modifier.focusRestorer()` handles the common case, but
 *     it does not survive the rail being recomposed with new data or the process being killed, so
 *     [RailFocusState] records the index in saveable state as well.
 */

/**
 * Remembers a [FocusRequester] and requests focus once, on first composition.
 *
 * @param enabled set false while a screen is still loading, so focus is claimed only once there is
 *   something focusable to claim it.
 */
@Composable
fun rememberInitialFocus(enabled: Boolean = true): FocusRequester {
    val requester = remember { FocusRequester() }
    var claimed by remember { mutableStateOf(false) }

    LaunchedEffect(enabled, claimed) {
        if (enabled && !claimed) {
            // requestFocus throws if the target is not yet attached, which happens when the
            // composable is created in the same frame. Failing softly is correct here: the effect
            // re-runs on the next state change and the user can always press a direction key.
            runCatching { requester.requestFocus() }
                .onSuccess { claimed = true }
        }
    }
    return requester
}

/** Applies [rememberInitialFocus] to a modifier chain. */
@Composable
fun Modifier.initialFocus(enabled: Boolean = true): Modifier =
    focusRequester(rememberInitialFocus(enabled))

/**
 * Remembers which item in a rail or grid last had focus, and restores it.
 *
 * Survives navigation away and back, and survives process death via [rememberSaveable], which
 * matters on a Fire TV Stick where the system reclaims memory from a backgrounded app quickly.
 */
class RailFocusState internal constructor(
    initialIndex: Int,
    private val onIndexChange: (Int) -> Unit,
) {
    var focusedIndex: Int = initialIndex
        private set

    internal val requester = FocusRequester()

    fun onItemFocused(index: Int) {
        if (index != focusedIndex) {
            focusedIndex = index
            onIndexChange(index)
        }
    }

    /** Whether [index] is the item that should receive focus when this rail is entered. */
    fun isRestoreTarget(index: Int): Boolean = index == focusedIndex

    /** Modifier for the item that should be re-focused. */
    fun restorerFor(index: Int): Modifier =
        if (isRestoreTarget(index)) Modifier.focusRequester(requester) else Modifier

    suspend fun restore() {
        runCatching { requester.requestFocus() }
    }
}

@Composable
fun rememberRailFocusState(key: String): RailFocusState {
    var savedIndex by rememberSaveable(key) { mutableStateOf(0) }
    return remember(key) {
        RailFocusState(initialIndex = savedIndex) { savedIndex = it }
    }
}

/**
 * Tracks focus for a single element without the caller writing the boilerplate.
 *
 * @param onFocusChanged invoked on transitions only, so a caller can scroll a parent or update a
 *   hero backdrop without doing so on every recomposition.
 */
@Composable
fun Modifier.trackFocus(onFocusChanged: (Boolean) -> Unit): Modifier {
    var wasFocused by remember { mutableStateOf(false) }
    return onFocusChanged { state ->
        if (state.isFocused != wasFocused) {
            wasFocused = state.isFocused
            onFocusChanged(state.isFocused)
        }
    }
}

/**
 * Handles the BACK key.
 *
 * Used instead of `BackHandler` where the key must be consumed *only* under a condition, which is
 * how the player's layered back behaviour is built: close the track sheet, else hide the controls,
 * else leave. Returning false lets the event fall through to the next handler.
 *
 * Only [KeyEventType.KeyUp] is handled. Fire TV remotes deliver both down and up, and acting on
 * down means a long press fires the action repeatedly.
 */
fun Modifier.onBackPressed(onBack: () -> Boolean): Modifier = onKeyEvent { event ->
    if (event.isBackKeyUp()) onBack() else false
}

fun KeyEvent.isBackKeyUp(): Boolean =
    type == KeyEventType.KeyUp && (key == Key.Back || key == Key.Escape)

/**
 * Handles the media and centre keys a TV remote sends.
 *
 * Fire TV remotes report play/pause as [Key.MediaPlayPause]; some report [Key.MediaPlay] and
 * [Key.MediaPause] separately; the Nvidia Shield controller sends [Key.ButtonA] for select. Missing
 * any of these makes the player feel unresponsive on that specific remote, so all are mapped.
 */
fun Modifier.onMediaKeys(
    onPlayPause: () -> Unit = {},
    onPlay: () -> Unit = onPlayPause,
    onPause: () -> Unit = onPlayPause,
    onRewind: () -> Unit = {},
    onFastForward: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onSelect: () -> Unit = {},
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
    when (event.key) {
        Key.MediaPlayPause -> { onPlayPause(); true }
        Key.MediaPlay -> { onPlay(); true }
        Key.MediaPause -> { onPause(); true }
        Key.MediaRewind -> { onRewind(); true }
        Key.MediaFastForward -> { onFastForward(); true }
        Key.MediaNext -> { onNext(); true }
        Key.MediaPrevious -> { onPrevious(); true }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { onSelect(); true }
        else -> false
    }
}

/**
 * Any key press at all, used by the player to reveal the controls.
 *
 * Deliberately does not consume the event: revealing the controls should not swallow the press that
 * revealed them, or the first D-pad nudge would appear to do nothing.
 */
fun Modifier.onAnyKey(onKey: () -> Unit): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && !event.isBackKeyUp()) onKey()
    false
}

/**
 * Keeps a [LazyListState] roughly centred on the focused index.
 *
 * A TV rail should scroll so the focused card is not flush against the screen edge, which is where
 * `scrollToItem` alone leaves it.
 */
suspend fun LazyListState.centreOnIndex(index: Int, itemsVisible: Int = DEFAULT_VISIBLE) {
    val target = (index - itemsVisible / 2).coerceAtLeast(0)
    runCatching { animateScrollToItem(target) }
}

private const val DEFAULT_VISIBLE = 5
