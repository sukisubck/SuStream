package com.sustream.tv.core.di

import com.sustream.tv.domain.model.PlayableSource
import java.util.concurrent.atomic.AtomicReference

/**
 * Carries the user's chosen source from Details to the player.
 *
 * ## Why this exists
 *
 * Navigation routes are strings, and a [PlayableSource] is an object with a provider-specific
 * resolution key that cannot be reconstructed from an id alone. The alternatives were both worse:
 *
 *  * serialise the source into the route — fragile, leaks provider internals into a URL, and would
 *    put a resolution key in the back stack where it could be logged;
 *  * have the player re-run source discovery and take the first result — which silently discards the
 *    choice the user just made in the sources sheet.
 *
 * So the selection is handed over out of band and **consumed exactly once**. If the player is
 * reached by any other route — a Continue Watching card, a deep link, autoplay — there is nothing
 * to consume and the player runs its own discovery, which is the correct behaviour for those paths.
 *
 * `AtomicReference.getAndSet(null)` makes the consume atomic, because the offer happens on the main
 * thread during navigation and the read happens when the player's view model is constructed, which
 * is not guaranteed to be the same dispatch.
 */
class PlaybackHandoff {

    private val pending = AtomicReference<PlayableSource?>(null)

    /** Called by Details immediately before navigating to the player. */
    fun offer(source: PlayableSource) {
        pending.set(source)
    }

    /** Returns the pending source and clears it. Null when the player was reached another way. */
    fun consume(): PlayableSource? = pending.getAndSet(null)

    /** Discards anything pending, so a cancelled navigation cannot leak into the next playback. */
    fun clear() {
        pending.set(null)
    }
}
