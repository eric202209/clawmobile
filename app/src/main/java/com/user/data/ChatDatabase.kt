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
        CachedResponse::class,
        Note::class,
        GatewayProviderStatus::class,
        GatewaySession::class
    ],
    version = 12,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun gitConnectionDao(): GitConnectionDao
    abstract fun projectContextDao(): ProjectContextDao
    abstract fun taskDao(): TaskDao
    abstract fun apiRequestDao(): ApiRequestDao
    abstract fun cachedResponseDao(): CachedResponseDao
    abstract fun noteDao(): NoteDao
    abstract fun providerStatusDao(): ProviderStatusDao
    abstract fun gatewaySessionDao(): GatewaySessionDao

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

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `tags` TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gateway_provider_status` " +
                        "(`id` TEXT NOT NULL, `type` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `activeModel` TEXT, `lastLatencyMs` INTEGER, " +
                        "`lastCheckedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gateway_sessions` " +
                        "(`id` TEXT NOT NULL, `gatewayId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`projectId` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`isActive` INTEGER NOT NULL, " +
                        "`messageCount` INTEGER NOT NULL, `lastActivity` INTEGER NOT NULL, " +
                        "`cachedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
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
                    .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

