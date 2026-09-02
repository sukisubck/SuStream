package com.sustream.tv.presentation.common

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult

/**
 * The state of one piece of loaded data.
 *
 * A sealed type rather than the common `isLoading` / `data` / `error` triple, because that triple
 * permits states that make no sense — loading *and* errored, or loaded with both data and an error —
 * and every screen then has to decide which field wins. Here the compiler enforces that exactly one
 * state holds, and every `when` over it is exhaustive.
 *
 * [Empty] is distinct from `Loaded(emptyList())` on purpose: "we looked and there is nothing" needs
 * different copy from "loading finished", and the brief requires an explicit empty state.
 */
sealed interface Loadable<out T> {

    data object Idle : Loadable<Nothing>

    data object Loading : Loadable<Nothing>

    data class Loaded<out T>(val value: T) : Loadable<T>

    /** Loaded successfully, but there is nothing to show. */
    data object Empty : Loadable<Nothing>

    data class Failed(val error: AppError) : Loadable<Nothing>

    val isLoading: Boolean get() = this is Loading
    val valueOrNull: T? get() = (this as? Loaded)?.value
    val errorOrNull: AppError? get() = (this as? Failed)?.error
}

/** Maps an [AppResult] into a [Loadable], treating an empty collection as [Loadable.Empty]. */
fun <T : Collection<*>> AppResult<T>.toLoadable(): Loadable<T> = when (this) {
    is AppResult.Success -> if (value.isEmpty()) Loadable.Empty else Loadable.Loaded(value)
    is AppResult.Failure -> Loadable.Failed(error)
}

/** Maps an [AppResult] into a [Loadable] for a single value, which is never "empty". */
fun <T> AppResult<T>.toLoadableValue(): Loadable<T> = when (this) {
    is AppResult.Success -> Loadable.Loaded(value)
    is AppResult.Failure -> Loadable.Failed(error)
}

inline fun <T, R> Loadable<T>.map(transform: (T) -> R): Loadable<R> = when (this) {
    is Loadable.Loaded -> Loadable.Loaded(transform(value))
    is Loadable.Idle -> Loadable.Idle
    is Loadable.Loading -> Loadable.Loading
    is Loadable.Empty -> Loadable.Empty
    is Loadable.Failed -> this
}

/**
 * A one-shot event for the UI: a message to show, or a navigation instruction.
 *
 * Deliberately not part of the state. An event held in state replays on every recomposition and on
 * every process restart, which is how an app ends up showing the same error twice, or navigating
 * again after the user has already pressed back.
 */
sealed interface UiEvent {
    data class ShowMessage(val text: String) : UiEvent
    data class ShowError(val error: AppError) : UiEvent
    data object Dismiss : UiEvent
}
