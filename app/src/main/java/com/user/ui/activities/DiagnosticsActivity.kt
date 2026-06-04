package com.user.ui.activities

import android.os.Bundle
import android.os.Debug
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.user.R

class DiagnosticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@DiagnosticsActivity, R.color.background))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.diagnostics_title)
            setTitleTextColor(ContextCompat.getColor(this@DiagnosticsActivity, R.color.text_primary))
            navigationIcon = ContextCompat.getDrawable(this@DiagnosticsActivity, R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.touch_target_min))

        val memInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val dbSize = getDatabasePath("openclaw_database").length()
        val workManager = WorkManager.getInstance(this)
        val workerNames = listOf(
            com.user.service.ProviderStatusWorker.WORK_NAME,
            com.user.service.StatusMonitorWorker.WORK_NAME,
            com.user.service.InterventionPollService.WORK_NAME,
            com.user.service.PermissionPollService.WORK_NAME
        )
        val workerFailures = workerNames.sumOf { name ->
            runCatching {
                workManager.getWorkInfosForUniqueWork(name).get()
                    .count { it.state == WorkInfo.State.FAILED }
            }.getOrDefault(0)
        }
        val rows = listOf(
            "Room DB: ${dbSize / 1024} KB",
            "Dalvik heap: ${memInfo.dalvikPss} KB",
            "Native heap: ${memInfo.nativePss} KB",
            "Other memory: ${memInfo.otherPss} KB",
            "WorkManager failures: $workerFailures",
            "WebSocket reconnects: available in Phase 3"
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        rows.forEach { row ->
            content.addView(TextView(this).apply {
                text = row
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@DiagnosticsActivity, R.color.text_primary))
                setPadding(0, 10, 0, 10)
            })
        }
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
    }
}
