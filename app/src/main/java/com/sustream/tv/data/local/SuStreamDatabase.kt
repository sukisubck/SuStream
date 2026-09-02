package com.sustream.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sustream.tv.data.local.dao.AddonDao
import com.sustream.tv.data.local.dao.IptvDao
import com.sustream.tv.data.local.dao.LibraryDao
import com.sustream.tv.data.local.dao.NotificationDao
import com.sustream.tv.data.local.entity.AddonConfigurationEntity
import com.sustream.tv.data.local.entity.ChannelEntity
import com.sustream.tv.data.local.entity.EpgProgrammeEntity
import com.sustream.tv.data.local.entity.FavouriteEntity
import com.sustream.tv.data.local.entity.MediaSnapshotEntity
import com.sustream.tv.data.local.entity.NotificationEntity
import com.sustream.tv.data.local.entity.PlaylistEntity
import com.sustream.tv.data.local.entity.WatchProgressEntity
import com.sustream.tv.data.local.entity.WatchlistEntity

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
        AddonConfigurationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SuStreamDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao
    abstract fun iptvDao(): IptvDao
    abstract fun notificationDao(): NotificationDao
    abstract fun addonDao(): AddonDao

    companion object {
        const val NAME = "sustream.db"

        /**
         * Version 1 → 2: add addon_configuration table.
         * No existing data is touched; the new table starts empty.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `addon_configuration` (
                        `id` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `normalisedBaseUrl` TEXT NOT NULL,
                        `authorisedByUser` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        `lastCheckedAt` INTEGER,
                        `lastHealthState` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_addon_configuration_addedAt` ON `addon_configuration` (`addedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_addon_configuration_authorisedByUser` ON `addon_configuration` (`authorisedByUser`)",
                )
            }
        }

        fun create(context: Context): SuStreamDatabase =
            Room.databaseBuilder(context, SuStreamDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()

        fun createInMemory(context: Context): SuStreamDatabase =
            Room.inMemoryDatabaseBuilder(context, SuStreamDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}