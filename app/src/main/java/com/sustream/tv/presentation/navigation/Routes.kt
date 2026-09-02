package com.sustream.tv.presentation.navigation

import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaType

object Routes {

    // ---- Top-level sections (the nav rail) ----------------------------------
    const val HOME      = "home"
    const val FILMS     = "films"
    const val TV        = "tv"
    const val LIVE      = "live"
    /** Addon list — top-level rail destination, sits between Live TV and Search. */
    const val ADDONS    = "addons"
    const val SEARCH    = "search"
    const val LIBRARY   = "library"
    const val SETTINGS  = "settings"

    /** Sections reachable from the rail, in rail order. */
    val SECTIONS = listOf(HOME, FILMS, TV, LIVE, ADDONS, SEARCH, LIBRARY, SETTINGS)

    // ---- Startup ------------------------------------------------------------
    const val SPLASH     = "splash"
    const val ONBOARDING = "onboarding"
    const val AUTH       = "auth"

    // ---- Details ------------------------------------------------------------
    private const val DETAILS_BASE = "details"
    const val ARG_MEDIA_ID = "mediaId"
    const val DETAILS = "$DETAILS_BASE/{$ARG_MEDIA_ID}"

    fun details(id: MediaId): String = DETAILS_BASE + "/" + encode(id.value)

    // ---- Player -------------------------------------------------------------
    private const val PLAYER_BASE = "player"
    const val ARG_PLAYBACK_KIND = "kind"
    const val ARG_TARGET_ID     = "targetId"
    const val ARG_SEASON        = "season"
    const val ARG_EPISODE       = "episode"
    const val PLAYER =
        "$PLAYER_BASE/{$ARG_PLAYBACK_KIND}/{$ARG_TARGET_ID}?$ARG_SEASON={$ARG_SEASON}&$ARG_EPISODE={$ARG_EPISODE}"

    const val KIND_MOVIE   = "movie"
    const val KIND_EPISODE = "episode"
    const val KIND_CHANNEL = "channel"

    fun playerForMovie(id: MediaId): String =
        PLAYER_BASE + "/" + KIND_MOVIE + "/" + encode(id.value) +
            "?" + ARG_SEASON + "=-1&" + ARG_EPISODE + "=-1"

    fun playerForEpisode(showId: MediaId, season: Int, episode: Int): String =
        PLAYER_BASE + "/" + KIND_EPISODE + "/" + encode(showId.value) +
            "?" + ARG_SEASON + "=" + season + "&" + ARG_EPISODE + "=" + episode

    fun playerForChannel(channelId: String): String =
        PLAYER_BASE + "/" + KIND_CHANNEL + "/" + encode(channelId) +
            "?" + ARG_SEASON + "=-1&" + ARG_EPISODE + "=-1"

    // ---- Catalogue browse ---------------------------------------------------
    fun catalogue(type: MediaType): String = if (type == MediaType.MOVIE) FILMS else TV

    // ---- Addons sub-screens -------------------------------------------------
    const val ADDONS_ADD    = "addons/add"
    const val ADDONS_DETAIL = "addons/detail/{$ARG_ADDON_ID}"
    const val ARG_ADDON_ID  = "addonId"

    fun addonDetail(addonId: String): String = "addons/detail/" + encode(addonId)

    // ---- Settings sub-screens -----------------------------------------------
    const val SETTINGS_ACCOUNT     = "settings/account"
    const val SETTINGS_PLAYBACK    = "settings/playback"
    const val SETTINGS_SUBTITLES   = "settings/subtitles"
    const val SETTINGS_IPTV        = "settings/iptv"
    const val SETTINGS_DIAGNOSTICS = "settings/diagnostics"
    const val SETTINGS_ABOUT       = "settings/about"
    // SETTINGS_PROVIDERS removed — no Providers screen in the updated plan.
    // SETTINGS_ADDONS removed — Addons is a top-level rail destination, not a settings sub-screen.

    // ---- Live TV sub-screens ------------------------------------------------
    private const val PLAYLIST_BASE = "live/playlist"
    const val ARG_PLAYLIST_ID = "playlistId"
    const val PLAYLIST_DETAIL = "$PLAYLIST_BASE/{$ARG_PLAYLIST_ID}"

    fun playlistDetail(playlistId: String): String = PLAYLIST_BASE + "/" + encode(playlistId)

    // ---- Encoding -----------------------------------------------------------
    fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }
            .getOrDefault(value)
}
