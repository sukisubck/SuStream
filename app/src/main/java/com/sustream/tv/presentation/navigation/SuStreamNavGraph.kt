package com.sustream.tv.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.sustream.tv.core.di.LocalAppContainer
import com.sustream.tv.domain.model.EpisodeRef
import com.sustream.tv.domain.model.MediaId
import com.sustream.tv.domain.model.MediaType
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.presentation.details.DetailsScreen
import com.sustream.tv.presentation.home.HomeScreen
import com.sustream.tv.presentation.live.LiveTvScreen
import com.sustream.tv.presentation.player.PlayerScreen

/**
 * The navigation graph.
 *
 * ## Rail visibility
 *
 * Details and the player are full-screen: the rail is hidden on both. On Details it would compete
 * with the backdrop for the left edge, and over video it would be an obstruction. Every other
 * destination is a section and keeps the rail.
 *
 * ## Section switching
 *
 * Moving between rail sections pops back to Home rather than stacking, with `launchSingleTop`. Without
 * that, pressing Home → Films → Live → Search → BACK four times would walk the user backwards through
 * their own navigation, which is not what BACK means on a television.
 */
@Composable
fun SuStreamNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showRail = currentRoute in Routes.SECTIONS

    Row(modifier = modifier.fillMaxSize()) {
        if (showRail) {
            NavRail(
                currentRoute = currentRoute,
                onNavigate = { route -> navController.navigateToSection(route) },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenDetails = { id -> navController.navigate(Routes.details(id)) },
                        onPlayMovie = { id -> navController.navigate(Routes.playerForMovie(id)) },
                        onPlayEpisode = { showId, season, episode ->
                            navController.navigate(
                                Routes.playerForEpisode(showId, season, episode),
                            )
                        },
                        onBrowse = { type -> navController.navigateToSection(Routes.catalogue(type)) },
                    )
                }

                composable(
                    route = Routes.DETAILS,
                    arguments = listOf(navArgument(Routes.ARG_MEDIA_ID) { type = NavType.StringType }),
                ) { entry ->
                    val raw = entry.arguments?.getString(Routes.ARG_MEDIA_ID).orEmpty()
                    val mediaId = MediaId.parseOrNull(Routes.decode(raw))

                    if (mediaId == null) {
                        // A malformed id is a bug or a bad deep link, not something to crash on.
                        ComingSoonScreen(
                            title = "Title unavailable",
                            description = "That link does not refer to a title this app can open.",
                            onBack = { navController.popBackStack() },
                        )
                        return@composable
                    }

                    DetailsScreen(
                        mediaId = mediaId,
                        onPlayMovie = { id, source ->
                            // Hand the chosen source over out of band; a route cannot carry it.
                            container.playbackHandoff.offer(source)
                            navController.navigate(Routes.playerForMovie(id))
                        },
                        onPlayEpisode = { showId, season, episode, source ->
                            container.playbackHandoff.offer(source)
                            navController.navigate(
                                Routes.playerForEpisode(showId, season, episode),
                            )
                        },
                        onAddPlaylist = { navController.navigateToSection(Routes.LIVE) },
                        onOpenProviderSettings = {
                            navController.navigateToSection(Routes.SETTINGS)
                        },
                    )
                }

                composable(
                    route = Routes.PLAYER,
                    arguments = listOf(
                        navArgument(Routes.ARG_PLAYBACK_KIND) { type = NavType.StringType },
                        navArgument(Routes.ARG_TARGET_ID) { type = NavType.StringType },
                        navArgument(Routes.ARG_SEASON) {
                            type = NavType.IntType
                            defaultValue = -1
                        },
                        navArgument(Routes.ARG_EPISODE) {
                            type = NavType.IntType
                            defaultValue = -1
                        },
                    ),
                ) { entry ->
                    val kind = entry.arguments?.getString(Routes.ARG_PLAYBACK_KIND).orEmpty()
                    val target = Routes.decode(
                        entry.arguments?.getString(Routes.ARG_TARGET_ID).orEmpty(),
                    )
                    val season = entry.arguments?.getInt(Routes.ARG_SEASON) ?: -1
                    val episode = entry.arguments?.getInt(Routes.ARG_EPISODE) ?: -1

                    val request = buildPlaybackRequest(kind, target, season, episode)
                    if (request == null) {
                        ComingSoonScreen(
                            title = "Cannot play that",
                            description = "That link does not describe something this app can play.",
                            onBack = { navController.popBackStack() },
                        )
                        return@composable
                    }

                    PlayerScreen(
                        request = request,
                        // Consumed once: reaching the player any other way runs its own discovery.
                        onExit = { navController.popBackStack() },
                        onPlayEpisode = { next: EpisodeRef ->
                            // Replace rather than stack, so BACK from episode 4 does not walk back
                            // through episodes 3, 2 and 1.
                            navController.navigate(
                                Routes.playerForEpisode(
                                    next.showId,
                                    next.seasonNumber,
                                    next.episodeNumber,
                                ),
                            ) {
                                popUpTo(Routes.PLAYER) { inclusive = true }
                            }
                        },
                    )
                }

                // ---- Sections not yet built -------------------------------------
                // Each is replaced by its real screen as it lands; see docs/HANDOVER.md.

                composable(Routes.FILMS) {
                    ComingSoonScreen(
                        title = "Films",
                        description = "A paged grid of films with genre and year filters, backed by " +
                            "TMDB discover.",
                        onBack = { navController.navigateToSection(Routes.HOME) },
                    )
                }

                composable(Routes.TV) {
                    ComingSoonScreen(
                        title = "TV shows",
                        description = "A paged grid of shows with genre and year filters.",
                        onBack = { navController.navigateToSection(Routes.HOME) },
                    )
                }

                composable(Routes.LIVE) {
                    LiveTvScreen(
                        onPlayChannel = { channel ->
                            navController.navigate(Routes.playerForChannel(channel.id))
                        },
                    )
                }

                composable(Routes.SEARCH) {
                    ComingSoonScreen(
                        title = "Search",
                        description = "Search films, TV shows and your live channels at once.",
                        onBack = { navController.navigateToSection(Routes.HOME) },
                    )
                }

                composable(Routes.LIBRARY) {
                    ComingSoonScreen(
                        title = "Library",
                        description = "Your watchlist, continue watching, and viewing history.",
                        onBack = { navController.navigateToSection(Routes.HOME) },
                    )
                }

                composable(Routes.SETTINGS) {
                    ComingSoonScreen(
                        title = "Settings",
                        description = "Account, playback, subtitles, providers, Live TV playlists, " +
                            "diagnostics, and attribution.",
                        onBack = { navController.navigateToSection(Routes.HOME) },
                    )
                }
            }
        }
    }
}

/**
 * Rebuilds a [PlaybackRequest] from route arguments.
 *
 * Titles are deliberately left blank: a route carries ids, not display text, and stuffing a title
 * into a URL would both bloat the back stack and go stale. `PlayerViewModel` adopts the real title
 * from the catalogue snapshot as soon as it loads.
 */
private fun buildPlaybackRequest(
    kind: String,
    targetId: String,
    season: Int,
    episode: Int,
): PlaybackRequest? = when (kind) {
    Routes.KIND_MOVIE -> MediaId.parseOrNull(targetId)
        ?.takeIf { it.type == MediaType.MOVIE }
        ?.let { PlaybackRequest.Movie(id = it, title = "") }

    Routes.KIND_EPISODE -> {
        val showId = MediaId.parseOrNull(targetId)?.takeIf { it.type == MediaType.TV }
        if (showId == null || season < 0 || episode < 0) {
            null
        } else {
            PlaybackRequest.TvEpisode(
                ref = EpisodeRef(showId, season, episode),
                showTitle = "",
                episodeTitle = "",
            )
        }
    }

    Routes.KIND_CHANNEL -> targetId
        .takeIf { it.isNotBlank() }
        ?.let { PlaybackRequest.LiveChannel(channelId = it, channelName = "") }

    else -> null
}

/**
 * Switches rail section.
 *
 * `popUpTo(HOME)` plus `launchSingleTop` keeps the back stack at most two deep for sections, so BACK
 * from any section goes to Home and BACK from Home exits — rather than replaying the user's whole
 * navigation history in reverse.
 */
private fun NavHostController.navigateToSection(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
