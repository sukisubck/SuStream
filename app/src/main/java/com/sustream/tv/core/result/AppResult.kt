package com.sustream.tv.core.result

/**
 * Explicit success/failure for every operation that crosses a repository boundary.
 *
 * Repositories do not throw. Kotlin has no checked exceptions, so a thrown error is invisible at
 * the call site and easy to forget; returning [AppResult] makes the failure path part of the
 * signature and lets view models map errors to UI states exhaustively.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error

fun <T> AppResult<T>.getOrElse(fallback: T): T = getOrNull() ?: fallback

val AppResult<*>.isSuccess: Boolean get() = this is AppResult.Success

fun <T> T.asSuccess(): AppResult<T> = AppResult.Success(this)

fun AppError.asFailure(): AppResult<Nothing> = AppResult.Failure(this)

/**
 * Collapses a list of results, keeping every success and discarding failures.
 *
 * Used for home-screen rails: one rail failing to load must not blank the whole screen. Callers
 * that need to know *what* failed use [partitionResults] instead.
 */
fun <T> List<AppResult<T>>.successes(): List<T> = mapNotNull { it.getOrNull() }

fun <T> List<AppResult<T>>.partitionResults(): Pair<List<T>, List<AppError>> {
    val values = mutableListOf<T>()
    val errors = mutableListOf<AppError>()
    forEach {
        when (it) {
            is AppResult.Success -> values += it.value
            is AppResult.Failure -> errors += it.error
        }
    }
    return values to errors
}
