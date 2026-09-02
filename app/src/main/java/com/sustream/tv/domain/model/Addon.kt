package com.sustream.tv.domain.model

import java.time.Instant

/**
 * Domain models for user-configured addons.
 *
 * An addon is a server the user personally controls or has an account on, reachable at an HTTPS
 * address, that implements the manifest/stream API. Nothing here carries a torrent hash, magnet
 * link, or cache-probing concept — those are out of scope by design. See DEFERRED_AND_RESTRICTED.md.
 */

data class AddonConfiguration(
    val id: String,
    val displayName: String,
    val normalisedBaseUrl: String,
    /** Recorded when the user confirms they are authorised to use this service. Never defaults to true. */
    val authorisedByUser: Boolean,
    val addedAt: Instant,
    /** Null until a health check has run. */
    val lastCheckedAt: Instant?,
    val lastHealthState: AddonHealthState,
)

data class AddonTestResult(
    val addonId: String,
    val addonName: String,
    /** e.g. `movie`, `series`. Empty means the addon did not declare a restriction. */
    val types: List<String>,
    val supportsStreams: Boolean,
    /** The normalised form of whatever the user typed, stored verbatim. */
    val normalisedBaseUrl: String,
)

enum class AddonHealthState {
    /** Health check has never been run. */
    UNKNOWN,
    OK,
    DEGRADED,
    FAILING,
    /** Configured but explicitly disabled by the user. */
    DISABLED,
}