package com.sustream.tv.provider.torbox

import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.domain.model.ProviderAccount
import com.sustream.tv.domain.model.ProviderConnection
import com.sustream.tv.domain.model.ProviderFile
import com.sustream.tv.domain.model.ProviderId
import com.sustream.tv.domain.model.ProviderLibraryItem
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.StreamContainer
import com.sustream.tv.domain.repository.TorBoxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provider integration for development and tests.
 *
 * Default in every build where no provider key is configured, and the only implementation any test
 * uses — so the suite never touches the live TorBox API, never consumes a real account's quota, and
 * runs identically offline.
 *
 * It cannot produce a playable network URI: [downloadLink] always fails with
 * [AppError.UnsupportedFormat]. That is deliberate. The mock exercises the *flow* — connect,
 * status, library, choose a file, attempt to resolve, show the failure — without pretending to have
 * content it does not have.
 *
 * @param failConnect makes [connect] fail, so the provider error path is testable.
 */
class MockTorBoxRepository(
    private val timeSource: TimeSource,
    private val failConnect: Boolean = false,
) : TorBoxRepository {

    private val connection = MutableStateFlow<ProviderConnection>(ProviderConnection.NotConnected)

    override fun observeConnection(): Flow<ProviderConnection> = connection.asStateFlow()

    override suspend fun connect(apiKey: Secret): AppResult<ProviderAccount> {
        if (apiKey.isBlank) {
            return AppResult.Failure(
                AppError.Unauthorised("Enter your API key.", refreshable = false),
            )
        }
        if (failConnect) {
            val error = AppError.Unauthorised(
                "The provider rejected that key (demo).",
                refreshable = false,
            )
            connection.value = ProviderConnection.Problem(error, timeSource.now())
            return AppResult.Failure(error)
        }

        val account = demoAccount()
        connection.value = ProviderConnection.Connected(account, timeSource.now())
        return AppResult.Success(account)
    }

    override suspend fun disconnect(): AppResult<Unit> {
        connection.value = ProviderConnection.NotConnected
        return AppResult.Success(Unit)
    }

    override suspend fun refreshStatus(): AppResult<ProviderAccount> {
        val current = connection.value
        if (current !is ProviderConnection.Connected) {
            return AppResult.Failure(
                AppError.Unauthorised("TorBox is not connected.", refreshable = false),
            )
        }
        val account = demoAccount()
        connection.value = ProviderConnection.Connected(account, timeSource.now())
        return AppResult.Success(account)
    }

    override suspend fun library(page: Int): AppResult<List<ProviderLibraryItem>> {
        if (connection.value !is ProviderConnection.Connected) {
            return AppResult.Failure(
                AppError.Unauthorised("TorBox is not connected.", refreshable = false),
            )
        }
        // One page only: paging beyond the fixture would pretend to more data than exists.
        if (page > 1) return AppResult.Success(emptyList())

        return AppResult.Success(
            listOf(
                ProviderLibraryItem(
                    id = "demo-item-1",
                    name = "Demo library item (single file)",
                    sizeBytes = 4_294_967_296L,
                    addedAt = timeSource.now().minusSeconds(86_400),
                    files = listOf(
                        ProviderFile(
                            id = "demo-file-1",
                            name = "demo-single-file.mkv",
                            sizeBytes = 4_294_967_296L,
                            container = StreamContainer.PROGRESSIVE,
                        ),
                    ),
                    isReady = true,
                ),
                ProviderLibraryItem(
                    id = "demo-item-2",
                    name = "Demo library item (season folder)",
                    sizeBytes = 12_884_901_888L,
                    addedAt = timeSource.now().minusSeconds(604_800),
                    files = (1..4).map { episode ->
                        ProviderFile(
                            id = "demo-file-2-" + episode,
                            name = "demo.s01e0" + episode + ".mkv",
                            sizeBytes = 3_221_225_472L,
                            container = StreamContainer.PROGRESSIVE,
                        )
                    },
                    isReady = true,
                ),
                ProviderLibraryItem(
                    id = "demo-item-3",
                    name = "Demo library item (still processing)",
                    sizeBytes = null,
                    addedAt = timeSource.now().minusSeconds(120),
                    files = emptyList(),
                    isReady = false,
                ),
            ),
        )
    }

    /**
     * Always fails.
     *
     * The mock has no content and will not fabricate a URL. Failing here is what keeps the demo
     * honest: the UI shows the real "this source could not be prepared" state instead of a player
     * that opens onto nothing.
     */
    override suspend fun downloadLink(
        itemId: String,
        fileId: String,
    ): AppResult<ResolvedStream> = AppResult.Failure(
        AppError.UnsupportedFormat(
            "This is demo provider data, so there is no real stream to play. Connect a provider " +
                "account, or add one of your own Live TV playlists.",
        ),
    )

    private fun demoAccount() = ProviderAccount(
        provider = ProviderId.TORBOX,
        maskedIdentifier = "d***@example.com",
        plan = "Demo plan",
        expiresAt = timeSource.now().plusSeconds(30L * 86_400),
        quotaUsedBytes = null,
        quotaTotalBytes = null,
    )
}
