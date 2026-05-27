package com.user

import android.app.Application
import android.os.Build
import android.os.StrictMode
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.user.data.ChatDatabase
import com.user.data.PrefsManager
import com.user.repository.ChatRepository
import com.user.service.InterventionPollService
import com.user.service.PermissionPollService
import java.util.concurrent.TimeUnit

class ClawMobileApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        enableDebugStrictMode()
        PDFBoxResourceLoader.init(this)
        schedulePermissionPolling()
        scheduleInterventionPolling()
    }

    private fun enableDebugStrictMode() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        val vmPolicyBuilder = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vmPolicyBuilder.detectCredentialProtectedWhileLocked()
        }
        StrictMode.setVmPolicy(vmPolicyBuilder.build())
    }

    private fun scheduleInterventionPolling() {
        val request = PeriodicWorkRequestBuilder<InterventionPollService>(
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            InterventionPollService.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun schedulePermissionPolling() {
        val request = PeriodicWorkRequestBuilder<PermissionPollService>(
            30, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PermissionPollService.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    val database by lazy { ChatDatabase.getDatabase(this) }
    val prefsManager by lazy { PrefsManager(this) }

    // DAO instances for use in activities
    val gitConnectionDao by lazy { database.gitConnectionDao() }
    val projectContextDao by lazy { database.projectContextDao() }
    val chatDao by lazy { database.chatDao() }
    val taskDao by lazy { database.taskDao() }
    val cachedResponseDao by lazy { database.cachedResponseDao() }
    val noteDao by lazy { database.noteDao() }

    val repository by lazy {
        ChatRepository(
            chatDao,
            gitConnectionDao,
            projectContextDao,
            taskDao,
            prefsManager
        )
    }
}
