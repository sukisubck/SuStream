package com.sustream.tv.domain.model

import java.time.Instant

/**
 * Notification models.
 *
 * The workbook asks for playback/service alerts (row 47) and new-content alerts (row 48). Both are
 * modelled as things the app can genuinely determine for itself:
 *
 *  - a service alert comes from a playlist or addon call that actually failed;
 *  - a new-content alert comes from a watchlist title that actually became available.
 *
 * There is no push transport. Fire TV has no Google Play Services, so FCM is unavailable, and
 * standing up a proprietary socket for a v1 TV app is not justified. [NotificationRepository]
 * therefore abstracts the source, and the shipped implementation is local. Swapping in a push
 * transport later means one new implementation and no UI change.
 */

sealed interface AppNotification {
    val id: String
    val createdAt: Instant
    val isRead: Boolean

    /** A configured playlist or addon stopped working. */
    data class ServiceAlert(
        override val id: String,
        override val createdAt: Instant,
        override val isRead: Boolean,
        val subject: ServiceSubject,
        val detail: String,
    ) : AppNotification

    /** Something on the watchlist became playable from an authorised source. */
    data class ContentAvailable(
        override val id: String,
        override val createdAt: Instant,
        override val isRead: Boolean,
        val mediaId: MediaId,
        val title: String,
        val sourceName: String,
    ) : AppNotification
}

/**
 * What generated a [AppNotification.ServiceAlert].
 *
 * TorBox / ProviderSubject removed — the app no longer has a cloud-provider integration.
 * Alerts now come only from playlists and addons.
 */
sealed interface ServiceSubject {
    val displayName: String

    data class PlaylistSubject(
        val playlistId: String,
        val playlistName: String,
    ) : ServiceSubject {
        override val displayName: String get() = playlistName
    }

    data class AddonSubject(
        val addonId: String,
        val addonName: String,
    ) : ServiceSubject {
        override val displayName: String get() = addonName
    }
}

/** Android notification channels, for the system tray on devices that show one. */
enum class NotificationChannelId(val id: String) {
    SERVICE_ALERTS("sustream_service_alerts"),
    NEW_CONTENT("sustream_new_content"),
}
