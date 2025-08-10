package com.faselhd.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.faselhd.app.models.Download
import com.faselhd.app.models.WatchHistory

@Database(entities = [WatchHistory::class, Download::class], version = 6)
@androidx.room.TypeConverters(com.faselhd.app.db.TypeConverters::class, com.faselhd.app.db.EpisodeListConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun downloadDao(): DownloadDao // <-- Add abstract function for the new DAO

    companion object {

        // ADD THE MIGRATION OBJECT
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the new 'isFinished' column to the existing table,
                // with a default value of 0 (false) for all old rows.
                db.execSQL("ALTER TABLE watch_history ADD COLUMN isFinished INTEGER NOT NULL DEFAULT 0")
            }
        }

        // ADD THE NEW MIGRATION
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the new 'animeUrl' column. The default value is an empty string.
                db.execSQL("ALTER TABLE watch_history ADD COLUMN animeUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `episodeUrl` TEXT NOT NULL, 
                        `animeTitle` TEXT NOT NULL, 
                        `episodeName` TEXT, 
                        `thumbnailUrl` TEXT, 
                        `downloadState` TEXT NOT NULL, 
                        `progress` INTEGER NOT NULL, 
                        `localFilePath` TEXT, 
                        PRIMARY KEY(`episodeUrl`)
                    )
                """)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_history ADD COLUMN episodeNumber INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN seasonEpisodes TEXT NOT NULL DEFAULT '[]'")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "anime_app_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3,MIGRATION_3_4, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}