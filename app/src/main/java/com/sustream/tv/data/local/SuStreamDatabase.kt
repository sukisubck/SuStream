package com.sustream.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sustream.tv.data.local.dao.IptvDao
import com.sustream.tv.data.local.dao.LibraryDao
import com.sustream.tv.data.local.dao.NotificationDao
import com.sustream.tv.data.local.entity.ChannelEntity
import com.sustream.tv.data.local.entity.EpgProgrammeEntity
import com.sustream.tv.data.local.entity.FavouriteEntity
import com.sustream.tv.data.local.entity.MediaSnapshotEntity
import com.sustream.tv.data.local.entity.NotificationEntity
import com.sustream.tv.data.local.entity.PlaylistEntity
import com.sustream.tv.data.local.entity.WatchProgressEntity
import com.sustream.tv.data.local.entity.WatchlistEntity

/**
 * On-device store for everything that must survive process death and work offline.
 *
 * Two policies worth stating explicitly:
 *
 *  * **Schemas are exported** to `app/schemas/` (the `room.schemaLocation` KSP argument), so every
 *    version bump arrives with a reviewable diff and a migration can be written against the real
 *    previous schema rather than from memory.
 *  * **There is no destructive fallback.** `fallbackToDestructiveMigration` would silently delete a
 *    user's watchlist, history and configured playlists on an upgrade where a migration was
 *    forgotten. Failing loudly in development is far better than losing user data in the field.
 *
 * Nothing stored here is a secret — Xtream passwords and provider tokens live in
 * `EncryptedSharedPreferences` — which is why the database itself is not encrypted and can be
 * included in a diagnostic dump.
 */
@Database(
    entities = [
        MediaSnapshotEntity::class,
        WatchlistEntity::class,
        WatchProgressEntity::class,
        FavouriteEntity::class,
        PlaylistEntity::class,
        ChannelEntity::class,
        EpgProgrammeEntity::class,
        NotificationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SuStreamDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao
    abstract fun iptvDao(): IptvDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        const val NAME = "sustream.db"

        fun create(context: Context): SuStreamDatabase =
            Room.databaseBuilder(context, SuStreamDatabase::class.java, NAME)
                // Write-ahead logging: reads do not block the writer, which matters because
                // playback writes progress while the UI is reading Continue Watching.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        /** In-memory instance for tests, so a suite never touches the device database. */
        fun createInMemory(context: Context): SuStreamDatabase =
            Room.inMemoryDatabaseBuilder(context, SuStreamDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
