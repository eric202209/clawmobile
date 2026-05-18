package com.user.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.user.BuildConfig
import com.user.R

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@AboutActivity, R.color.background))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.about_title)
            setTitleTextColor(ContextCompat.getColor(this@AboutActivity, R.color.text_primary))
            navigationIcon = ContextCompat.getDrawable(this@AboutActivity, R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.touch_target_min))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val appName = text(getString(R.string.app_name), 24, R.color.text_primary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        content.addView(appName)

        val version = text(
            getString(R.string.about_version, BuildConfig.VERSION_NAME),
            14,
            R.color.text_secondary
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 12, 0, 28)
            setOnLongClickListener {
                startActivity(Intent(this@AboutActivity, DiagnosticsActivity::class.java))
                true
            }
        }
        content.addView(version)

        content.addView(text(getString(R.string.about_min_openclaw), 14, R.color.text_primary))

        val githubButton = MaterialButton(this).apply {
            text = getString(R.string.about_github)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/henrycode03/clawmobile")))
            }
        }
        content.addView(githubButton)

        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
    }

    private fun text(value: String, sizeSp: Int, colorRes: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp.toFloat()
            setTextColor(ContextCompat.getColor(this@AboutActivity, colorRes))
            setPadding(0, 8, 0, 8)
        }
}
