package com.user.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.user.BuildConfig
import com.user.R
import com.user.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.about_title)

        binding.appVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.appVersion.setOnLongClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            true
        }

        binding.githubButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/henrycode03/clawmobile")))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
