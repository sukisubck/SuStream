package com.sustream.tv.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per user-configured addon.
 *
 * Multiple addons are the target state; a single-addon DataStore field cannot represent a growing
 * list without increasingly awkward JSON encoding. This mirrors how [PlaylistEntity] models
 * playlists — one row, one addon — for the same reason.
 *
 * [authorisedByUser] is stored rather than derived. The two-gate requirement (probe + consent)
 * means a stored false is meaningful: the URL was saved at some earlier state but consent was
 * revoked, so the adapter must treat it as inactive.
 */
@Entity(
    tableName = "addon_configuration",
    indices = [Index("addedAt"), Index("authorisedByUser")],
)
data class AddonConfigurationEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val normalisedBaseUrl: String,
    /** Never defaults to true. Both probe and explicit consent are required to set this. */
    val authorisedByUser: Boolean,
    val addedAt: Long,
    val lastCheckedAt: Long?,
    /** One of AddonHealthState enum names. */
    val lastHealthState: String,
)