package com.sustream.tv.domain.usecase

import com.sustream.tv.domain.model.PlaybackSource
import kotlinx.coroutines.channels.Channel

/**
 * One-shot channel between DetailsScreen and PlayerScreen.
 *
 * DetailsScreen resolves a [PlaybackSource] (validates the URL, fetches any redirect) and deposits
 * it here. PlayerScreen collects it on the way in, so it never has to re-resolve.
 *
 * Capacity 1: if the player hasn't collected yet and details deposits again (e.g. the user pressed
 * play twice very quickly), the second deposit is silently dropped — the player will use whichever
 * source it receives first.
 */
class PlaybackHandoff {

    private val channel = Channel<PlaybackSource>(capacity = Channel.CONFLATED)

    /**
     * Deposits [source] into the channel. Non-blocking; overwrites any unconsumed value
     * (CONFLATED semantics — the player always gets the most recent source).
     */
    fun offer(source: PlaybackSource) {
        channel.trySend(source)
    }

    /**
     * Suspends until a [PlaybackSource] is available, then returns it.
     * Called once by PlayerScreen during composition.
     */
    suspend fun collect(): PlaybackSource = channel.receive()

    /**
     * Returns the deposited source immediately if one is available, or null.
     * Useful for synchronous checks in tests.
     */
    fun tryCollect(): PlaybackSource? = channel.tryReceive().getOrNull()
}
