package com.sustream.tv.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Thin wrapper around [kotlinx.coroutines.Dispatchers] so call-sites are testable:
 * tests inject a [TestCoroutineDispatcher] rather than relying on the real thread pools.
 */
class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val main: CoroutineDispatcher = Dispatchers.Main,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val unconfined: CoroutineDispatcher = Dispatchers.Unconfined,
)
