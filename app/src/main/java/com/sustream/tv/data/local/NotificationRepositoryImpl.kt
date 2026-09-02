package com.sustream.tv.data.local

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.local.dao.NotificationDao
import com.sustream.tv.data.local.entity.NotificationEntity
import com.sustream.tv.domain.model.AddonHealthState
import com.sustream.tv.domain.model.AppNotification
import com.sustream.tv.domain.model.PlaylistStatus
import com.sustream.tv.domain.repository.AddonRepository
import com.sustream.tv.domain.repository.IptvPlaylistRepository
import com.sustream.tv.domain.repository.NotificationRepository
import com.sustream.tv.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class NotificationRepositoryImpl(
    private val dao: NotificationDao,
    private val playlistRepository: IptvPlaylistRepository,
    private val addonRepository: AddonRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : NotificationRepository {

    override fun observeNotifications(): Flow<List<AppNotification>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    override suspend fun markRead(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        dao.markRead(id); AppResult.Success(Unit)
    }

    override suspend fun markAllRead(): AppResult<Unit> = withContext(dispatchers.io) {
        dao.markAllRead(); AppResult.Success(Unit)
    }

    override suspend fun dismiss(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        dao.delete(id); AppResult.Success(Unit)
    }

    override suspend fun refresh(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val preferences = settingsRepository.current().notifications
            if (!preferences.serviceAlerts) return@withContext AppResult.Success(Unit)

            val now = timeSource.nowMillis()

            // Playlist alerts (unchanged)
            playlistRepository.observePlaylists().first().forEach { playlist ->
                val failed = playlist.status != PlaylistStatus.OK &&
                        playlist.status != PlaylistStatus.NEVER_SYNCED
                dao.deleteForSubject(NOTIFICATION_KIND_SERVICE, playlist.id)
                if (failed) {
                    dao.upsert(
                        NotificationEntity(
                            id = "service:" + playlist.id,
                            kind = NOTIFICATION_KIND_SERVICE,
                            createdAt = now,
                            isRead = false,
                            title = playlist.name,
                            body = playlist.lastErrorDetail ?: describePlaylist(playlist.status),
                            mediaId = null,
                            subjectId = playlist.id,
                            subjectName = playlist.name,
                        ),
                    )
                }
            }

            // Addon health alerts (replaces TorBox provider check)
            addonRepository.observeActiveAddons().first().forEach { addon ->
                val subjectId = SUBJECT_ADDON_PREFIX + addon.id
                dao.deleteForSubject(NOTIFICATION_KIND_SERVICE, subjectId)
                if (addon.lastHealthState == AddonHealthState.FAILING) {
                    dao.upsert(
                        NotificationEntity(
                            id = "service:$subjectId",
                            kind = NOTIFICATION_KIND_SERVICE,
                            createdAt = now,
                            isRead = false,
                            title = addon.displayName,
                            body = "This addon is not responding.",
                            mediaId = null,
                            subjectId = subjectId,
                            subjectName = addon.displayName,
                        ),
                    )
                }
            }

            dao.trimTo()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("Alerts could not be updated."))
        }
    }

    override suspend fun clear(): AppResult<Unit> = withContext(dispatchers.io) {
        dao.clear(); AppResult.Success(Unit)
    }

    private fun describePlaylist(status: PlaylistStatus): String = when (status) {
        PlaylistStatus.AUTH_FAILED   -> "The provider rejected the stored sign-in details."
        PlaylistStatus.PARSE_FAILED  -> "This playlist could not be read."
        PlaylistStatus.UNREACHABLE   -> "This playlist's server could not be reached."
        PlaylistStatus.OK, PlaylistStatus.NEVER_SYNCED -> "This playlist needs attention."
    }

    private companion object {
        const val NOTIFICATION_KIND_SERVICE = "SERVICE_ALERT"
        const val SUBJECT_ADDON_PREFIX = "addon:"
    }
}