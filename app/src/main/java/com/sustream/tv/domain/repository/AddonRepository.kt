package com.sustream.tv.domain.repository

import com.sustream.tv.core.result.AppResult
import com.sustream.tv.domain.model.AddonConfiguration
import com.sustream.tv.domain.model.AddonTestResult
import kotlinx.coroutines.flow.Flow

interface AddonRepository {

    fun observeAddons(): Flow<List<AddonConfiguration>>

    /** Only addons where `authorisedByUser == true`. */
    fun observeActiveAddons(): Flow<List<AddonConfiguration>>

    suspend fun getById(id: String): AddonConfiguration?

    /**
     * Saves a probed-and-verified addon as active.
     *
     * [result] carries the normalised URL and the manifest details that were verified. [displayName]
     * is the label the user chose; falls back to the addon's own name when blank.
     */
    suspend fun add(result: AddonTestResult, displayName: String): AppResult<AddonConfiguration>

    suspend fun remove(id: String): AppResult<Unit>

    suspend fun clear(): AppResult<Unit>
}