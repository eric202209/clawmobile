package com.user.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.user.service.RoomMaintenanceWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionExpiryTest {

    private lateinit var db: ChatDatabase
    private lateinit var sessionDao: GatewaySessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = db.gatewaySessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sessionsOlderThan30Days_areDeleted() = runTest {
        val now = System.currentTimeMillis()
        val thirtyOneDaysAgo = now - (31L * 24 * 60 * 60 * 1000)
        val oneDayAgo = now - (1L * 24 * 60 * 60 * 1000)

        sessionDao.upsertAll(
            listOf(
                GatewaySession("old-1", "gw-1", "Old session", null, thirtyOneDaysAgo, "done", false, 5, thirtyOneDaysAgo, thirtyOneDaysAgo),
                GatewaySession("recent-1", "gw-2", "Recent session", null, oneDayAgo, "done", false, 3, oneDayAgo, oneDayAgo)
            )
        )

        val cutoff = now - RoomMaintenanceWorker.SESSION_RETENTION_MILLIS
        sessionDao.deleteExpired(cutoff)

        val remaining = sessionDao.getPage(50, 0)
        assertEquals(1, remaining.size)
        assertEquals("recent-1", remaining.first().id)
    }

    @Test
    fun sessionsWithin30Days_arePreserved() = runTest {
        val now = System.currentTimeMillis()
        val recentTime = now - (1L * 60 * 60 * 1000)

        sessionDao.upsertAll(
            listOf(
                GatewaySession("fresh-1", "gw-1", "Fresh session", null, recentTime, "running", true, 10, recentTime, recentTime)
            )
        )

        val cutoff = now - RoomMaintenanceWorker.SESSION_RETENTION_MILLIS
        sessionDao.deleteExpired(cutoff)

        assertEquals(1, sessionDao.count())
    }

    @Test
    fun sessionsExactly30DaysOld_arePreserved() = runTest {
        val now = System.currentTimeMillis()
        val exactlyThirtyDays = now - RoomMaintenanceWorker.SESSION_RETENTION_MILLIS + 1000

        sessionDao.upsertAll(
            listOf(
                GatewaySession("boundary-1", "gw-1", "Boundary session", null, exactlyThirtyDays, "done", false, 0, exactlyThirtyDays, exactlyThirtyDays)
            )
        )

        val cutoff = now - RoomMaintenanceWorker.SESSION_RETENTION_MILLIS
        sessionDao.deleteExpired(cutoff)

        assertEquals(1, sessionDao.count())
    }
}
