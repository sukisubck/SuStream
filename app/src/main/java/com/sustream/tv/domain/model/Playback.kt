package com.sustream.tv.domain.model

import java.time.Instant

/**
 * Playback availability and resolution.
 *
 * This file is where the brief's legal boundary is enforced *by type*, not by convention. Read
 * [Authorisation] first: a [PlayableSource] cannot exist without one, so there is no way to
 * construct a source whose right to play is unrecorded, and therefore no way for an unauthorised
 * source to reach the player.
 *
 * See docs/IMPLEMENTATION_PLAN.md section 1.2 for why the prototype's "scrape trackers, resolve
 * cached hashes" flow is not implemented.
 */

/**
 * The basis on which SuStream is allowed to play a stream.
 *
 * Closed and non-nullable on purpose. Adding a new way to be authorised means adding a case here,
 * which forces every `when` over it — including the UI that explains provenance to the user — to
 * be updated deliberately rather than by accident.
 */
sealed interface Authorisation {

    /** Shown to the user so provenance is always visible, never implied. */
    val displayName: String

    /**
     * A channel or VOD entry from a playlist the user added themselves, using a service they
     * subscribe to. The lawful, primary path.
     */
    data class UserPlaylist(
        val playlistId: String,
        val playlistName: String,
    ) : Authorisation {
        override val displayName: String get() = playlistName
    }

    /**
     * A file already present in the user's own provider cloud account.
     *
     * Note what this is *not*: it is not "search a provider for this title". It is "this item is
     * already in the account you own, so play it". The distinction is the whole point.
     */
    data class UserProviderLibrary(
        val provider: String,
        /** Masked account reference for display, e.g. an email tail. Never a token. */
        val accountRef: String,
    ) : Authorisation {
        override val displayName: String get() = provider
    }

    /** A direct HTTPS stream from an addon endpoint the user configured on this device. */
    data class UserAddon(
        val addonId: String,
        val addonName: String,
    ) : Authorisation {
        override val displayName: String get() = addonName
    }

    /**
     * Fixture data used for development, previews and tests.
     *
     * Deliberately cannot resolve to a network stream: `ResolveSourceUseCase` refuses to produce a
     * [ResolvedStream] with a remote URI for a Demo source. The UI labels it as a demo so nobody
     * mistakes the flow for real availability.
     */
    data object Demo : Authorisation {
        override val displayName: String get() = "Demo source"
    }
}

enum class StreamContainer {
    /** HLS: `.m3u8`. What almost every IPTV service and live channel uses. */
    HLS,

    /** MPEG-DASH: `.mpd`. */
    DASH,

    /** A single progressive file over HTTP: mp4, mkv, ts. */
    PROGRESSIVE,

    /** Unknown; ExoPlayer infers from the response and the URI. */
    UNKNOWN,
}

/**
 * A source the user could choose to play.
 *
 * No `seeders`, no `hash`, no `magnet`, no "cached" flag. Those fields only make sense in a
 * torrent workflow, and their absence is deliberate — see the file header.
 */
data class PlayableSource(
    val id: String,
    /** Human label, e.g. `"Sky Sports Main Event · 1080p"`. Built from provider metadata only. */
    val label: String,
    val container: StreamContainer,
    val authorisation: Authorisation,
    /** Which service is serving this, for display. */
    val providerName: String,
    val isLive: Boolean,
    /**
     * Quality as *reported by the provider*. Never inferred from a filename, because guessing
     * "4K" from a title string is how misleading quality badges happen.
     */
    val qualityLabel: String? = null,
    val audioLanguage: String? = null,
    /** Opaque handle the resolving repository needs to turn this into a URI. Never displayed. */
    val resolutionKey: String,
) {
    val isDemo: Boolean get() = authorisation is Authorisation.Demo
}

/** A subtitle track offered by a stream or a sidecar file. */
data class SubtitleTrack(
    val id: String,
    /** BCP-47 where the provider supplies one, otherwise whatever it gave us. */
    val language: String?,
    val label: String,
    val isForced: Boolean = false,
    /** True for subtitles for the deaf and hard of hearing. */
    val isSdh: Boolean = false,
    /** Set when the track is a separate file rather than embedded in the manifest. */
    val sidecarUri: String? = null,
    val mimeType: String? = null,
)

data class AudioTrack(
    val id: String,
    val language: String?,
    val label: String,
    val channelCount: Int?,
    val codec: String?,
)

/**
 * A source that has been resolved and validated, and is ready for the player.
 *
 * Constructing one is an assertion that the URI was checked by `UrlValidator` and that the
 * response was reachable. Nothing in the app hands ExoPlayer a URI that did not come through here.
 */
data class ResolvedStream(
    val source: PlayableSource,
    val uri: String,
    /**
     * Request headers the provider requires, typically `Referer` or a signed token. Redacted in
     * every log; see `core.log.Redact`.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * When the link stops working. Provider links are usually short-lived, so the player checks
     * this before resuming from the background and re-resolves rather than showing a dead stream.
     */
    val expiresAt: Instant? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val startPositionMillis: Long = 0L,
) {
    fun isExpiredAt(now: Instant): Boolean = expiresAt?.isBefore(now) == true
}

/** Whether a title can currently be played, for the availability indicator on Details. */
enum class SourceAvailability {
    /** A check has not started, or is in flight. */
    CHECKING,

    /** At least one authorised source exists. */
    AVAILABLE,

    /**
     * The user has configured nothing that could play anything. Distinct from [NO_SOURCE_FOUND]
     * because the fix is completely different: add a playlist, versus this title simply is not in
     * the services you have.
     */
    NONE_CONFIGURED,

    /** Sources were checked and none offers this title. Expected and normal, not an error. */
    NO_SOURCE_FOUND,

    /** The check itself failed: network, provider outage, bad credentials. */
    ERROR,
}

/** What the user asked to play. Resolution turns this into a [ResolvedStream]. */
sealed interface PlaybackRequest {

    data class Movie(val id: MediaId, val title: String) : PlaybackRequest

    data class TvEpisode(
        val ref: EpisodeRef,
        val showTitle: String,
        val episodeTitle: String,
    ) : PlaybackRequest

    /** A live IPTV channel. Has no resume position and no seekable window by default. */
    data class LiveChannel(
        val channelId: String,
        val channelName: String,
    ) : PlaybackRequest

    val displayTitle: String
        get() = when (this) {
            is Movie -> title
            is TvEpisode -> showTitle + " · " + episodeTitle
            is LiveChannel -> channelName
        }

    val isLive: Boolean get() = this is LiveChannel
}
