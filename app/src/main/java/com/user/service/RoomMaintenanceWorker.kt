package com.user.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.user.ClawMobileApplication

class RoomMaintenanceWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ClawMobileApplication
        val cutoff = System.currentTimeMillis() - SESSION_RETENTION_MILLIS
        app.gatewaySessionDao.deleteExpired(cutoff)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "room_maintenance"
        const val SESSION_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000L
    }
}
