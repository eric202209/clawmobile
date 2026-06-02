package com.user.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderStatusDaoTest {
    private lateinit var db: ChatDatabase
    private lateinit var dao: ProviderStatusDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.providerStatusDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertAllInsertsAndUpdatesProviders() = runTest {
        dao.upsertAll(
            listOf(
                GatewayProviderStatus(
                    id = "openclaw",
                    type = "gateway",
                    displayName = "OpenClaw",
                    status = "healthy",
                    activeModel = "main",
                    lastLatencyMs = 10,
                    lastCheckedAt = 100
                )
            )
        )
        dao.upsertAll(
            listOf(
                GatewayProviderStatus(
                    id = "openclaw",
                    type = "gateway",
                    displayName = "OpenClaw Gateway",
                    status = "offline",
                    activeModel = null,
                    lastLatencyMs = null,
                    lastCheckedAt = 200
                )
            )
        )

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("OpenClaw Gateway", all.first().displayName)
        assertEquals("offline", all.first().status)
        assertEquals(200L, all.first().lastCheckedAt)
    }

    @Test
    fun getActiveProvidersReturnsHealthyStatusesOnly() = runTest {
        dao.upsertAll(
            listOf(
                provider("a", "healthy"),
                provider("b", "ready"),
                provider("c", "offline"),
                provider("d", "error")
            )
        )

        val active = dao.getActiveProviders()
        assertEquals(listOf("a", "b"), active.map { it.id })
    }

    @Test
    fun deleteStaleRemovesOldRows() = runTest {
        dao.upsertAll(listOf(provider("old", "healthy", checkedAt = 100), provider("fresh", "healthy", checkedAt = 500)))

        val deleted = dao.deleteStale(300)

        assertEquals(1, deleted)
        assertEquals(listOf("fresh"), dao.getAll().map { it.id })
    }

    @Test
    fun migrate10To11CreatesProviderStatusTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val migrationDb = helper.writableDatabase
            ChatDatabase.MIGRATION_10_11.migrate(migrationDb)
            migrationDb.execSQL(
                "INSERT INTO gateway_provider_status " +
                    "(id, type, displayName, status, activeModel, lastLatencyMs, lastCheckedAt) VALUES " +
                    "('gateway', 'gateway', 'Gateway', 'healthy', NULL, NULL, 123)"
            )
            migrationDb.query("SELECT id, status, activeModel FROM gateway_provider_status").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("gateway", cursor.getString(0))
                assertEquals("healthy", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        } finally {
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun provider(
        id: String,
        status: String,
        checkedAt: Long = 100
    ): GatewayProviderStatus =
        GatewayProviderStatus(
            id = id,
            type = "llm",
            displayName = id,
            status = status,
            activeModel = null,
            lastLatencyMs = null,
            lastCheckedAt = checkedAt
        )

    companion object {
        private const val TEST_DB = "provider-status-migration-test"
    }
}
