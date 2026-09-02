package com.sustream.tv.data.local

import com.sustream.tv.core.result.AppError
import com.sustream.tv.core.result.AppResult
import com.sustream.tv.core.util.DispatcherProvider
import com.sustream.tv.core.util.TimeSource
import com.sustream.tv.data.local.dao.LibraryDao
import com.sustream.tv.domain.model.Favourite
import com.sustream.tv.domain.repository.FavouritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Favourite titles and favourite live channels.
 *
 * Device-only, never synced. A favourite channel is meaningless on another device, since it belongs
 * to a playlist configured here; and syncing a channel id would leak which IPTV service the user
 * subscribes to, for no benefit.
 */
class FavouritesRepositoryImpl(
    private val dao: LibraryDao,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : FavouritesRepository {

    override fun observeFavourites(): Flow<List<Favourite>> =
        dao.observeFavourites().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeIsFavourite(favourite: Favourite): Flow<Boolean> =
        dao.observeIsFavourite(favourite.storageKey)

    /** @return true when the item is now a favourite. */
    override suspend fun toggle(favourite: Favourite): AppResult<Boolean> =
        withContext(dispatchers.io) {
            try {
                if (dao.isFavourite(favourite.storageKey)) {
                    dao.deleteFavourite(favourite.storageKey)
                    AppResult.Success(false)
                } else {
                    dao.upsertFavourite(favourite.toEntity(timeSource.nowMillis()))
                    AppResult.Success(true)
                }
            } catch (throwable: Throwable) {
                AppResult.Failure(AppError.Storage("That could not be saved to your favourites."))
            }
        }

    override suspend fun clear(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            dao.clearFavourites()
            dao.pruneOrphanSnapshots()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError.Storage("Favourites could not be cleared."))
        }
    }
}
