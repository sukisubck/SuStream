package com.sustream.tv.data.local

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.local.dao.NotificationDao
import com.sustream.tv.data.local.entity.NotificationEntity
import com.sustream.tv.domain.model.AppNotification
import com.sustream.tv.domain.model.PlaylistStatus
import com.sustream.tv.domain.model.ProviderConnection
import com.sustream.tv.domain.model.ProviderId
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.NotificationRepository
import com.sustream.tv.domain.repository.SettingsRepository
import com.sustream.tv.domain.repository.TorBoxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Notifications, derived from things the app can actually determine.
 *
 * The prototype's notification list was fabricated marketing copy — *"Real-Debrid Sync Active
 * (284 days remaining). 4K caching enabled."* — reporting account telemetry the client has no way
 * to know. Everything here comes from state the app genuinely holds:
 *
 *  * a **service alert** is written when a playlist the user configured is in a failed state, or the
 *    provider connection reports a problem;
 *  * a **content alert** is written when a watchlist title becomes playable from an authorised
 *    source (wired in by the availability check, not invented here).
 *
 * There is no push transport. Fire TV has no Google Play Services, so FCM is unavailable, and this
 * interface exists so a transport can be added later without touching a screen. See
 * docs/IMPLEMENTATION_PLAN.md section 9, question 7.
 */
class NotificationRepositoryImpl(
    private val dao: NotificationDao,
    private val playlistRepository: IptvPlaylistRepository,
    private val providerRepository: TorBoxRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : NotificationRepository {

    override fun observeNotifications(): Flow<List<AppNotification>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    override suspend fun markRead(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        dao.markRead(id)
        AppResult.Success(Unit)
    }

    override suspend fun markAllRead(): AppResult<Unit> = withContext(dispatchers.io) {
        dao.markAllRead()
        AppResult.Success(Unit)
    }

    override suspend fun dismiss(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        dao.delete(id)
        AppResult.Success(Unit)
    }

    /**
     * Re-derives service alerts from current integration state.
     *
     * Idempotent: an existing alert for the same subject is replaced rather than appended, so a
     * playlist that fails on every refresh produces one notification rather than one per attempt.
     * Recovered subjects have their alert removed, so a fixed playlist stops nagging.
     */
    override suspend fun refresh(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val preferences = settingsRepository.current().notifications
            if (!preferences.serviceAlerts) return@withContext AppResult.Success(Unit)

            val now = timeSource.nowMillis()

            playlistRepository.observePlaylists().first().forEach { playlist ->
                val failed = playlist.status != PlaylistStatus.OK &&
                    playlist.status != PlaylistStatus.NEVER_SYNCED

                // Clear first in both branches: recovery must remove the old alert.
                dao.deleteForSubject(NOTIFICATION_KIND_SERVICE, playlist.id)

                if (failed) {
                    dao.upsert(
                        NotificationEntity(
                            id = "service:" + playlist.id,
                            kind = NOTIFICATION_KIND_SERVICE,
                            createdAt = now,
                            isRead = false,
                            title = playlist.name,
                            body = playlist.lastErrorDetail ?: describe(playlist.status),
                            mediaId = null,
                            subjectId = playlist.id,
                            subjectName = playlist.name,
                        ),
                    )
                }
            }

            val providerSubject = SUBJECT_PROVIDER_PREFIX + ProviderId.TORBOX.name
            dao.deleteForSubject(NOTIFICATION_KIND_SERVICE, providerSubject)

            val connection = providerRepository.observeConnection().first()
            if (connection is ProviderConnection.Problem) {
                dao.upsert(
                    NotificationEntity(
                        id = "service:" + providerSubject,
                        kind = NOTIFICATION_KIND_SERVICE,
                        createdAt = now,
                        isRead = false,
                        title = ProviderId.TORBOX.displayName,
                        body = connection.error.detail
                            ?: "The provider reported a problem.",
                        mediaId = null,
                        subjectId = providerSubject,
                        subjectName = ProviderId.TORBOX.displayName,
                    ),
                )
            }

            dao.trimTo()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("Alerts could not be updated."))
        }
    }

    override suspend fun clear(): AppResult<Unit> = withContext(dispatchers.io) {
        dao.clear()
        AppResult.Success(Unit)
    }

    private fun describe(status: PlaylistStatus): String = when (status) {
        PlaylistStatus.AUTH_FAILED -> "The provider rejected the stored sign-in details."
        PlaylistStatus.PARSE_FAILED -> "This playlist could not be read."
        PlaylistStatus.UNREACHABLE -> "This playlist's server could not be reached."
        PlaylistStatus.OK, PlaylistStatus.NEVER_SYNCED -> "This playlist needs attention."
    }
}
