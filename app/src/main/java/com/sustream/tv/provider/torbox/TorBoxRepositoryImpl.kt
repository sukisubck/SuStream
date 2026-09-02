package com.sustream.tv.provider.torbox

import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.log.Redact
import com.sustream.tv.core.log.Secret
import com.sustream.tv.core.net.UrlValidator
import com.sustream.tv.core.net.safeApiResponse
import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.prefs.SecureCredentialStore
import com.sustream.tv.domain.model.Authorisation
import com.sustream.tv.domain.model.PlayableSource
import com.sustream.tv.domain.model.ProviderAccount
import com.sustream.tv.domain.model.ProviderConnection
import com.sustream.tv.domain.model.ProviderFile
import com.sustream.tv.domain.model.ProviderId
import com.sustream.tv.domain.model.ProviderLibraryItem
import com.sustream.tv.domain.model.ResolvedStream
import com.sustream.tv.domain.model.StreamContainer
import com.sustream.tv.domain.repository.TorBoxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private const val TAG = "TorBox"

/**
 * TorBox integration, restricted to the user's own cloud account.
 *
 * ## Where the credential lives
 *
 * In a release build, **nowhere on the device**. The client calls the backend, which holds the API
 * key, and this class is not constructed at all. The direct-from-device path exists only when
 * [com.sustream.tv.core.config.AppConfig.allowDirectProvider] is true, which requires a debug
 * build *and* a key in `local.properties`. When it is used, the key goes into
 * [SecureCredentialStore], not into a preference file.
 *
 * ## Honesty about the contract
 *
 * Every field is optional and every parse failure degrades one value rather than failing the call.
 * An absent quota is reported as **unknown**, never as unlimited — the prototype's admin panel
 * displayed `quota: 'Unlimited'` for every provider, which is exactly the kind of confident
 * fabrication this avoids.
 */
internal class TorBoxRepositoryImpl(
    private val api: TorBoxApi,
    private val credentialStore: SecureCredentialStore,
    private val urlValidator: UrlValidator,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : TorBoxRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val connection: MutableStateFlow<ProviderConnection> =
        MutableStateFlow(ProviderConnection.NotConnected)

    override fun observeConnection(): StateFlow<ProviderConnection> = connection.asStateFlow()

    override suspend fun connect(apiKey: Secret): AppResult<ProviderAccount> =
        withContext(dispatchers.io) {
            if (apiKey.isBlank) {
                return@withContext AppResult.Failure(
                    AppError.Unauthorised("Enter your API key.", refreshable = false),
                )
            }

            connection.value = ProviderConnection.Connecting

            // Store first, because the interceptor reads the key from the store when making the
            // verification call. Removed again below if verification fails, so a bad key is never
            // left behind.
            credentialStore.putProviderApiKey(PROVIDER_KEY_NAME, apiKey)

            when (val result = fetchAccount()) {
                is AppResult.Success -> {
                    connection.value = ProviderConnection.Connected(
                        account = result.value,
                        checkedAt = timeSource.now(),
                    )
                    result
                }

                is AppResult.Failure -> {
                    credentialStore.removeProviderApiKey(PROVIDER_KEY_NAME)
                    connection.value = ProviderConnection.Problem(
                        error = result.error,
                        checkedAt = timeSource.now(),
                    )
                    result
                }
            }
        }

    override suspend fun disconnect(): AppResult<Unit> = withContext(dispatchers.io) {
        credentialStore.removeProviderApiKey(PROVIDER_KEY_NAME)
        connection.value = ProviderConnection.NotConnected
        AppResult.Success(Unit)
    }

    override suspend fun refreshStatus(): AppResult<ProviderAccount> =
        withContext(dispatchers.io) {
            if (credentialStore.providerApiKey(PROVIDER_KEY_NAME) == null) {
                connection.value = ProviderConnection.NotConnected
                return@withContext AppResult.Failure(
                    AppError.Unauthorised("TorBox is not connected.", refreshable = false),
                )
            }

            when (val result = fetchAccount()) {
                is AppResult.Success -> {
                    connection.value = ProviderConnection.Connected(result.value, timeSource.now())
                    result
                }

                is AppResult.Failure -> {
                    connection.value = ProviderConnection.Problem(result.error, timeSource.now())
                    result
                }
            }
        }

    private suspend fun fetchAccount(): AppResult<ProviderAccount> =
        when (val response = safeApiResponse { api.me() }) {
            is AppResult.Failure -> response
            is AppResult.Success -> {
                val envelope = response.value
                if (!envelope.success) {
                    AppResult.Failure(providerError(envelope.error ?: envelope.detail))
                } else {
                    val user = decode<TorBoxUser>(envelope.data as? JsonObject)
                    if (user == null) {
                        AppResult.Failure(
                            AppError.ParseFailed("The provider's account response was unreadable."),
                        )
                    } else {
                        AppResult.Success(
                            ProviderAccount(
                                provider = ProviderId.TORBOX,
                                maskedIdentifier = maskEmail(user.email),
                                plan = user.plan?.contentOrNull(),
                                expiresAt = parseTimestamp(user.premiumExpiresAt),
                                // Absent means unknown, not unlimited. See the class comment.
                                quotaUsedBytes = null,
                                quotaTotalBytes = null,
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun library(page: Int): AppResult<List<ProviderLibraryItem>> =
        withContext(dispatchers.io) {
            val offset = (page - 1).coerceAtLeast(0) * TorBoxApi.DEFAULT_PAGE_SIZE

            when (val response = safeApiResponse { api.myList(offset = offset) }) {
                is AppResult.Failure -> response
                is AppResult.Success -> {
                    val envelope = response.value
                    if (!envelope.success) {
                        return@withContext AppResult.Failure(
                            providerError(envelope.error ?: envelope.detail),
                        )
                    }

                    val array = envelope.data as? JsonArray ?: JsonArray(emptyList())
                    val items = array.mapNotNull { element ->
                        val dto = decode<TorBoxItem>(element as? JsonObject) ?: return@mapNotNull null
                        toLibraryItem(dto)
                    }
                    AppResult.Success(items)
                }
            }
        }

    private fun toLibraryItem(dto: TorBoxItem): ProviderLibraryItem? {
        val id = dto.id?.contentOrNull() ?: return null
        val name = dto.name?.takeIf { it.isNotBlank() } ?: return null
        return ProviderLibraryItem(
            id = id,
            // The user's own naming is preserved verbatim. It is deliberately not parsed for
            // quality claims: guessing "4K" from a filename is how misleading badges happen.
            name = name,
            sizeBytes = dto.size?.contentOrNull()?.toLongOrNull(),
            addedAt = parseTimestamp(dto.createdAt),
            files = dto.files.mapNotNull { file ->
                val fileId = file.id?.contentOrNull() ?: return@mapNotNull null
                val fileName = (file.shortName ?: file.name)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ProviderFile(
                    id = fileId,
                    name = fileName,
                    sizeBytes = file.size?.contentOrNull()?.toLongOrNull(),
                    container = containerFor(fileName),
                )
            },
            isReady = dto.downloadFinished == true || dto.downloadPresent == true,
        )
    }

    override suspend fun downloadLink(
        itemId: String,
        fileId: String,
    ): AppResult<ResolvedStream> = withContext(dispatchers.io) {
        val key = credentialStore.providerApiKey(PROVIDER_KEY_NAME)
            ?: return@withContext AppResult.Failure(
                AppError.Unauthorised("TorBox is not connected.", refreshable = false),
            )

        when (
            val response = safeApiResponse {
                api.requestDownloadLink(
                    token = key.reveal(),
                    itemId = itemId,
                    fileId = fileId,
                )
            }
        ) {
            is AppResult.Failure -> response
            is AppResult.Success -> {
                val envelope = response.value
                if (!envelope.success) {
                    return@withContext AppResult.Failure(
                        providerError(envelope.error ?: envelope.detail),
                    )
                }

                // The link arrives as a bare string in `data` on this endpoint.
                val link = (envelope.data as? JsonPrimitive)?.contentOrNull()
                    ?: return@withContext AppResult.Failure(
                        AppError.ParseFailed("The provider did not return a playable link."),
                    )

                // Validated before it can reach the player. A provider response is untrusted input
                // like any other: nothing hands ExoPlayer a URI that skipped this check.
                val validated = urlValidator.validate(link, UrlValidator.Usage.APP_SERVICE)
                if (validated is AppResult.Failure) {
                    AppLog.w(TAG, "Rejected provider link: " + Redact.url(link))
                    return@withContext validated
                }

                AppResult.Success(
                    ResolvedStream(
                        source = PlayableSource(
                            id = itemId + ":" + fileId,
                            label = ProviderId.TORBOX.displayName,
                            container = StreamContainer.PROGRESSIVE,
                            authorisation = Authorisation.UserProviderLibrary(
                                provider = ProviderId.TORBOX.displayName,
                                accountRef = currentAccountRef(),
                            ),
                            providerName = ProviderId.TORBOX.displayName,
                            isLive = false,
                            resolutionKey = itemId + "/" + fileId,
                        ),
                        uri = link,
                        expiresAt = timeSource.now().plusSeconds(ASSUMED_LINK_TTL_SECONDS),
                    ),
                )
            }
        }
    }

    private fun currentAccountRef(): String =
        (connection.value as? ProviderConnection.Connected)?.account?.maskedIdentifier ?: "account"

    /**
     * Maps a provider-reported failure onto the error taxonomy.
     *
     * String matching is unavoidable here because the API reports these as free text rather than as
     * distinct codes. It is bounded and falls through to [AppError.Unknown], so an unrecognised
     * message degrades to a generic error rather than being silently swallowed.
     */
    private fun providerError(detail: String?): AppError {
        val lowered = detail?.lowercase().orEmpty()
        return when {
            lowered.contains("auth") || lowered.contains("token") || lowered.contains("api key") ->
                AppError.Unauthorised(detail, refreshable = false)

            lowered.contains("quota") || lowered.contains("limit") ->
                AppError.QuotaExceeded(detail)

            lowered.contains("not found") || lowered.contains("no such") ->
                AppError.NotFound(detail)

            lowered.contains("expired") -> AppError.Expired(detail)

            else -> AppError.Unknown(detail)
        }
    }

    private inline fun <reified T> decode(element: JsonObject?): T? {
        if (element == null) return null
        return runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()
    }

    private fun parseTimestamp(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            runCatching { Instant.parse(raw) }.getOrNull()
        }
    }

    /**
     * `alex.smith@example.com` -> `a***@example.com`.
     *
     * A TV is a shared display in a living room, so a full email address on the Settings screen is
     * shown to whoever is in the room. The domain is kept so the user can still tell which account
     * is connected.
     */
    private fun maskEmail(email: String?): String {
        if (email.isNullOrBlank()) return "connected account"
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        return email.first() + "***" + email.substring(at)
    }

    private fun containerFor(name: String): StreamContainer = when {
        name.endsWith(".m3u8", ignoreCase = true) -> StreamContainer.HLS
        name.endsWith(".mpd", ignoreCase = true) -> StreamContainer.DASH
        else -> StreamContainer.PROGRESSIVE
    }

    private fun JsonPrimitive.contentOrNull(): String? = content.trim().takeIf { it.isNotEmpty() }

    private companion object {
        const val PROVIDER_KEY_NAME = "torbox"

        /**
         * TODO(torbox-contract): the API does not report an expiry for a download link, so a
         *  conservative assumption is used. Being wrong in this direction is harmless: the player
         *  re-resolves slightly earlier than necessary, which the user never sees. Assuming too long
         *  would mean a dead URL mid-playback.
         */
        const val ASSUMED_LINK_TTL_SECONDS = 1_800L
    }
}
