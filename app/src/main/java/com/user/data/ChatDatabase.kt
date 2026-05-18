package com.user.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatMessage::class,
        ChatSession::class,
        GitConnection::class,
        ProjectContext::class,
        ProjectFile::class,
        Task::class,
        ApiRequest::class,
        ApiResponse::class,
        CachedResponse::class
    ],
    version = 9,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun gitConnectionDao(): GitConnectionDao
    abstract fun projectContextDao(): ProjectContextDao
    abstract fun taskDao(): TaskDao
    abstract fun apiRequestDao(): ApiRequestDao
    abstract fun cachedResponseDao(): CachedResponseDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_responses` " +
                        "(`cacheKey` TEXT NOT NULL, `json` TEXT NOT NULL, `cachedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`))"
                )
            }
        }

        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "openclaw_database"
                )
                    .addMigrations(MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}



