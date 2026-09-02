package com.sustream.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The single Room database for SuStream.
 *
 * Entity list and schema version are the source of truth — do not bump [version] without
 * writing a [androidx.room.migration.Migration] or the app will wipe user data on upgrade.
 *
 * DAOs are declared as abstract functions so Room generates the implementations at compile time.
 * AppContainer accesses them through this class and passes them to repositories.
 *
 * TODO: add entity classes to the @Database annotation entities list as they are created.
 */
@Database(
    entities = [
        // TODO: PlaylistEntity::class,
        // TODO: ChannelEntity::class,
        // TODO: EpgProgrammeEntity::class,
        // TODO: AddonConfigurationEntity::class,
        // TODO: MediaSnapshotEntity::class,
        // TODO: WatchlistEntity::class,
        // TODO: WatchProgressEntity::class,
        // TODO: FavouriteEntity::class,
        // TODO: NotificationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    // TODO: uncomment as DAOs are implemented
    // abstract fun playlistDao(): PlaylistDao
    // abstract fun channelDao(): ChannelDao
    // abstract fun epgDao(): EpgDao
    // abstract fun addonDao(): AddonDao
    // abstract fun mediaSnapshotDao(): MediaSnapshotDao
    // abstract fun watchlistDao(): WatchlistDao
    // abstract fun watchProgressDao(): WatchProgressDao
    // abstract fun favouriteDao(): FavouriteDao
    // abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun build(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room
                    .databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    // Schema is still in flux — acceptable to wipe during development.
                    // Replace with a Migration before shipping to users.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }

        private const val DB_NAME = "sustream.db"
    }
}
