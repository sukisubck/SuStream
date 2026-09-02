package com.sustream.tv.domain.repository

import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.PlaybackRequest
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.ResolvedStream

/**
 * Contract for discovering **authorised** playable sources for a title.
 *
 * ## Read this before implementing it
 *
 * An implementation is only acceptable if every [PlayableSource] it returns carries an
 * [com.sustream.tv.domain.model.Authorisation] that is true — that is, the user either
 *
 *  * subscribes to the service the stream comes from ([com.sustream.tv.domain.model.Authorisation.UserPlaylist]), or
 *  * already holds the file in their own account ([com.sustream.tv.domain.model.Authorisation.UserProviderLibrary]).
 *
 * The following are **out of scope**, per docs/DEFERRED_AND_RESTRICTED.md:
 *  * querying torrent, DDL or Usenet indexes for a title;
 *  * resolving magnet links, info-hashes or `.torrent` files;
 *  * probing a debrid service's cache;
 *  * bypassing DRM, geo-restrictions or a provider's access controls.
 */
interface AuthorisedSourceRepository {

    val providerName: String

    suspend fun isConfigured(): Boolean

    suspend fun findSources(request: PlaybackRequest): AppResult<List<PlayableSource>>

    suspend fun resolve(source: PlayableSource): AppResult<ResolvedStream>
}