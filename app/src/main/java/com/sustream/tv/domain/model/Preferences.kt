package com.sustream.tv.domain.model

/**
 * User-controlled settings. Device-local; never synced, because "play on Wi-Fi only" and subtitle
 * size are properties of a device, not of an account.
 */

data class AppSettings(
    val playback: PlaybackPreferences = PlaybackPreferences(),
    val subtitles: SubtitlePreferences = SubtitlePreferences(),
    val iptv: IptvPreferences = IptvPreferences(),
    val notifications: NotificationPreferences = NotificationPreferences(),
    /** Ordered interests from onboarding. Only affects home rail ordering. */
    val interests: List<String> = emptyList(),
    val onboardingComplete: Boolean = false,
)

data class PlaybackPreferences(
    val autoplayNextEpisode: Boolean = true,
    val preferredQuality: PreferredQuality = PreferredQuality.AUTO,
    val completionThreshold: Float = PlaybackProgress.DEFAULT_COMPLETION_THRESHOLD,
    val playOnWifiOnly: Boolean = false,
    val seekStepSeconds: Int = DEFAULT_SEEK_STEP_SECONDS,
) {
    companion object {
        const val DEFAULT_SEEK_STEP_SECONDS = 10
    }
}

enum class PreferredQuality {
    AUTO,
    FULL_HD,
    HD,
    DATA_SAVER,
    ;

    val maxHeight: Int?
        get() = when (this) {
            AUTO      -> null
            FULL_HD   -> 1080
            HD        -> 720
            DATA_SAVER -> 480
        }
}

data class SubtitlePreferences(
    val preferredLanguage: String? = null,
    val enabledByDefault: Boolean = false,
    val textSize: SubtitleTextSize = SubtitleTextSize.MEDIUM,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val preferSdh: Boolean = false,
) {
    companion object {
        const val DEFAULT_BACKGROUND_OPACITY = 0.6f
    }
}

enum class SubtitleTextSize(val scale: Float) {
    SMALL(0.85f),
    MEDIUM(1.0f),
    LARGE(1.25f),
    EXTRA_LARGE(1.5f),
}

data class IptvPreferences(
    val allowLocalNetworkPlaylists: Boolean = false,
    val refreshOnOpen: Boolean = true,
    val showChannelNumbers: Boolean = true,
    val lastCategory: String = ChannelCategory.ALL,
)

data class NotificationPreferences(
    val serviceAlerts: Boolean = true,
    val newContentAlerts: Boolean = false,
    val permissionRequested: Boolean = false,
)