package com.sustream.tv.domain.model

/**
 * User-controlled settings. Device-local; never synced, because "play on Wi-Fi only" and subtitle
 * size are properties of a device, not of an account.
 */

data class AppSettings(
    val playback: PlaybackPreferences = PlaybackPreferences(),
    val subtitles: SubtitlePreferences = SubtitlePreferences(),
    val iptv: IptvPreferences = IptvPreferences(),
    val stremioAddon: StremioAddonPreferences = StremioAddonPreferences(),
    val notifications: NotificationPreferences = NotificationPreferences(),
    /** Ordered interests from onboarding. Only affects home rail ordering. */
    val interests: List<String> = emptyList(),
    val onboardingComplete: Boolean = false,
)

/** One user-configured, direct-stream Stremio-compatible addon. Device-local and never synced. */
data class StremioAddonPreferences(
    val baseUrl: String? = null,
    val displayName: String? = null,
    /** Recorded only after the user confirms that they are authorised to use this service. */
    val authorisedByUser: Boolean = false,
)

data class PlaybackPreferences(
    val autoplayNextEpisode: Boolean = true,
    val preferredQuality: PreferredQuality = PreferredQuality.AUTO,
    /**
     * Fraction at which a title counts as finished and leaves Continue Watching.
     * See [PlaybackProgress.isWatched].
     */
    val completionThreshold: Float = PlaybackProgress.DEFAULT_COMPLETION_THRESHOLD,
    /**
     * Fire TV devices are mains-powered and usually wired or on strong Wi-Fi, so this is off by
     * default; it exists for tethered or metered setups.
     */
    val playOnWifiOnly: Boolean = false,
    val seekStepSeconds: Int = DEFAULT_SEEK_STEP_SECONDS,
) {
    companion object {
        /** 10 s matches the prototype's rewind/forward affordances and remote expectations. */
        const val DEFAULT_SEEK_STEP_SECONDS = 10
    }
}

/**
 * Quality preference.
 *
 * Applies only where a source genuinely offers a choice. Live IPTV streams are a single rendition
 * chosen by the provider, so this has no effect there — the Settings copy says so rather than
 * pretending otherwise.
 */
enum class PreferredQuality {
    AUTO,
    /** Cap at 1080p, useful on a Stick with constrained bandwidth. */
    FULL_HD,
    HD,
    /** Lowest available, for very poor connections. */
    DATA_SAVER,
    ;

    /** Maximum vertical resolution to allow ExoPlayer to select, or null for no cap. */
    val maxHeight: Int?
        get() = when (this) {
            AUTO -> null
            FULL_HD -> 1080
            HD -> 720
            DATA_SAVER -> 480
        }
}

data class SubtitlePreferences(
    /** BCP-47, or null for "off unless the stream forces them". */
    val preferredLanguage: String? = null,
    val enabledByDefault: Boolean = false,
    val textSize: SubtitleTextSize = SubtitleTextSize.MEDIUM,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val preferSdh: Boolean = false,
) {
    companion object {
        /**
         * A partly opaque backing plate rather than none: subtitles over a bright scene are
         * unreadable without one, and a fully opaque bar covers too much picture.
         */
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
    /**
     * Off by default. Turning it on lets `UrlValidator` accept playlists on RFC1918 and other
     * non-public addresses, which is only correct when the user really is pointing at their own
     * server. See docs/SECURITY.md section 4.
     */
    val allowLocalNetworkPlaylists: Boolean = false,
    val refreshOnOpen: Boolean = true,
    val showChannelNumbers: Boolean = true,
    /** Remembered so the channel list opens where the user left it. */
    val lastCategory: String = ChannelCategory.ALL,
)

data class NotificationPreferences(
    /** Service alerts: a playlist or provider stopped working. */
    val serviceAlerts: Boolean = true,
    /** New content: a watchlist title became available. */
    val newContentAlerts: Boolean = false,
    /**
     * Whether the user has been asked for the POST_NOTIFICATIONS runtime permission. Tracked so
     * the prompt appears once, in context, rather than at cold start.
     */
    val permissionRequested: Boolean = false,
)
