package com.user.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.user.R
import com.user.data.GatewaySettingsResolver
import com.user.data.PrefsManager
import com.user.ui.activities.GatewayDashboardActivity

class StatusMonitorWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val prefs = PrefsManager(ctx)
    private val notificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        if (!prefs.gatewayAlertsEnabled) return Result.success()
        val settings = GatewaySettingsResolver.resolve(applicationContext)
        if (settings.token.isEmpty()) return Result.success()

        val wasHealthy = prefs.lastGatewayHealthy
        val isHealthy = GatewayHealthChecker(timeoutSeconds = 10)
            .check(settings, settings.token)
            .isSuccess

        prefs.lastGatewayHealthy = isHealthy

        if (wasHealthy && !isHealthy) {
            ensureNotificationChannel()
            postGatewayDownNotification()
        }
        return Result.success()
    }

    private fun postGatewayDownNotification() {
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, GatewayDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(applicationContext.getString(R.string.gateway_alert_down_title))
            .setContentText(applicationContext.getString(R.string.gateway_alert_down_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.gateway_alert_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val WORK_NAME = "status_monitor"
        const val CHANNEL_ID = "gateway_alerts"
        private const val NOTIFICATION_ID = 20000
    }
}
