package com.sustream.tv.domain.repository

import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.ProviderAccount
import com.sustream.tv.domain.model.ProviderConnection
import com.sustream.tv.domain.model.ProviderLibraryItem
import com.sustream.tv.domain.model.ResolvedStream
import kotlinx.coroutines.flow.Flow

/**
 * Contract for discovering **authorised** playable sources for a title.
 *
 * ## Read this before implementing it
 *
 * This interface exists precisely so that source discovery has a single, auditable seam. An
 * implementation is only acceptable if every [PlayableSource] it returns carries an
 * [com.sustream.tv.domain.model.Authorisation] that is true — that is, the user either
 *
 *  * subscribes to the service the stream comes from ([com.sustream.tv.domain.model.Authorisation.UserPlaylist]), or
 *  * already holds the file in their own account ([com.sustream.tv.domain.model.Authorisation.UserProviderLibrary]).
 *
 * The following are **out of scope for any implementation of this interface**, per the brief and
 * per docs/DEFERRED_AND_RESTRICTED.md:
 *
 *  * querying torrent, DDL or Usenet *indexes* for a title;
 *  * resolving magnet links, info-hashes or `.torrent` files;
 *  * probing a debrid service's cache to discover whether an infringing copy is available;
 *  * bypassing DRM, geo-restrictions or a provider's access controls.
 *
 * Shipped implementations:
 *  * `MockAuthorisedSourceRepository` — fixtures. Every source is
 *    [com.sustream.tv.domain.model.Authorisation.Demo] and cannot resolve to a network URI.
 *  * `IptvBackedSourceRepository` — matches a title against the user's own playlists.
 *  * `CompositeAuthorisedSourceRepository` — fans out to the above and merges.
 */
interface AuthorisedSourceRepository {

    /** Stable name for diagnostics and for labelling results in the UI. */
    val providerName: String

    /**
     * True when this adapter has enough configuration to return anything.
     *
     * Drives the difference between "no source found" and "nothing configured", which are the two
     * availability states the brief asks to be distinguished.
     */
    suspend fun isConfigured(): Boolean

    /**
     * Finds authorised sources for a request.
     *
     * Returning an empty list is a normal, successful outcome: it means "your services do not carry
     * this", which is expected and is not an error.
     */
    suspend fun findSources(request: PlaybackRequest): AppResult<List<PlayableSource>>

    /**
     * Turns a chosen source into a validated, playable stream.
     *
     * Implementations must, in order: check the source's authorisation is still valid, obtain the
     * URI, run it through `UrlValidator`, and confirm it is reachable. A [ResolvedStream] is an
     * assertion that all four happened — nothing else in the app hands a URI to ExoPlayer.
     */
    suspend fun resolve(source: PlayableSource): AppResult<ResolvedStream>
}

/**
 * TorBox integration, scoped to the user's own cloud account.
 *
 * What this does: report account status, list what the user already has, and produce a download
 * link for a file they own.
 *
 * What this deliberately does not do: search TorBox or anywhere else for a title, add a torrent or
 * magnet, or check whether some hash is cached. Those are the operations that turn a debrid client
 * into an infringement tool, and they are absent by design rather than unimplemented by accident.
 *
 * In release builds the API key never reaches the device: the client calls the backend, which holds
 * the credential. The direct-from-device path exists only when
 * [com.sustream.tv.core.config.AppConfig.allowDirectProvider] is true, which requires a debug build.
 */
interface TorBoxRepository {

    fun observeConnection(): Flow<ProviderConnection>

    /** Verifies the key and stores it encrypted. Returns the account on success. */
    suspend fun connect(apiKey: Secret): AppResult<ProviderAccount>

    /** Clears stored credentials and any cached library listing. */
    suspend fun disconnect(): AppResult<Unit>

    /** Re-checks the account. Used by Settings and by Diagnostics. */
    suspend fun refreshStatus(): AppResult<ProviderAccount>

    /** Lists the items already in the user's account. */
    suspend fun library(page: Int = 1): AppResult<List<ProviderLibraryItem>>

    /**
     * Requests a time-limited download link for a file the user owns.
     *
     * The returned [ResolvedStream] carries the link's expiry so the player can re-resolve rather
     * than failing on a dead URL after the app has been backgrounded.
     */
    suspend fun downloadLink(
        itemId: String,
        fileId: String,
    ): AppResult<ResolvedStream>
}
