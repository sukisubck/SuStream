package com.sustream.tv.data.local

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.local.dao.AddonDao
import com.sustream.tv.data.local.entity.AddonConfigurationEntity
import com.sustream.tv.domain.model.AddonConfiguration
import com.sustream.tv.domain.model.AddonHealthState
import com.sustream.tv.domain.model.AddonTestResult
import com.sustream.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class AddonRepositoryImpl(
    private val dao: AddonDao,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : AddonRepository {

    override fun observeAddons(): Flow<List<AddonConfiguration>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeActiveAddons(): Flow<List<AddonConfiguration>> =
        dao.observeActive().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: String): AddonConfiguration? =
        withContext(dispatchers.io) { dao.getById(id)?.toDomain() }

    override suspend fun add(
        result: AddonTestResult,
        displayName: String,
    ): AppResult<AddonConfiguration> = withContext(dispatchers.io) {
        try {
            val id = UUID.randomUUID().toString()
            val entity = AddonConfigurationEntity(
                id = id,
                displayName = displayName.trim().ifBlank { result.addonName },
                normalisedBaseUrl = result.normalisedBaseUrl,
                authorisedByUser = true,
                addedAt = timeSource.nowMillis(),
                lastCheckedAt = null,
                lastHealthState = AddonHealthState.UNKNOWN.name,
            )
            dao.upsert(entity)
            AppResult.Success(entity.toDomain())
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("The addon could not be saved."))
        }
    }

    override suspend fun remove(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            dao.delete(id)
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("The addon could not be removed."))
        }
    }

    override suspend fun clear(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            dao.clear()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("Addons could not be cleared."))
        }
    }
}

private fun AddonConfigurationEntity.toDomain(): AddonConfiguration = AddonConfiguration(
    id = id,
    displayName = displayName,
    normalisedBaseUrl = normalisedBaseUrl,
    authorisedByUser = authorisedByUser,
    addedAt = Instant.ofEpochMilli(addedAt),
    lastCheckedAt = lastCheckedAt?.let { Instant.ofEpochMilli(it) },
    lastHealthState = runCatching { AddonHealthState.valueOf(lastHealthState) }
        .getOrDefault(AddonHealthState.UNKNOWN),
)