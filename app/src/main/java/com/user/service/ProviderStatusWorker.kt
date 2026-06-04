package com.user.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.user.ClawMobileApplication
import com.user.data.GatewaySettingsResolver

class ProviderStatusWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ClawMobileApplication
        val sources = GatewaySettingsResolver.resolveProviderStatusSources(applicationContext)
        if (sources.isEmpty()) return Result.success()

        return GatewayProviderStatusClient(timeoutSeconds = 10)
            .fetchAny(sources)
            .fold(
                onSuccess = { providers ->
                    app.providerStatusDao.upsertAll(providers)
                    Result.success()
                },
                onFailure = { error ->
                    if (ProviderStatusRetryPolicy.shouldRetry(error)) Result.retry()
                    else Result.failure()
                }
            )
    }

    companion object {
        const val WORK_NAME = "provider_status_refresh"
    }
}
