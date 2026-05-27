package com.user.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.user.R
import com.user.data.PrefsManager
import com.user.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefsManager: PrefsManager
    private val pages = listOf(
        OnboardingPage(
            eyebrow = "Welcome",
            title = "Claw Mobile is your phone control plane",
            body = "Use it for quick supervision: chat with the Gateway, check runs, capture notes, and use local AI utilities when the server is offline.",
            bullets = listOf(
                "Chat: send short operational requests to OpenClaw",
                "Runs: inspect tasks, sessions, checkpoints, and project progress",
                "Notes and Tools: keep scratchpad context and format prompts locally",
            ),
        ),
        OnboardingPage(
            eyebrow = "Setup",
            title = "Connect the Gateway first",
            body = "The Gateway is required for chat. Use Settings to enter the host, port, token, and HTTPS mode, then test the connection before returning to chat.",
            bullets = listOf(
                "Use a LAN IP or Tailscale IP from your phone",
                "Do not use localhost unless you are testing from an emulator tunnel",
                "The Orchestrator URL is optional and belongs in its own Settings section",
            ),
        ),
        OnboardingPage(
            eyebrow = "Daily use",
            title = "Keep mobile commands short",
            body = "Claw Mobile works best as a compact operations surface, not a desktop replacement. Ask for status, blockers, summaries, or a specific session action.",
            bullets = listOf(
                "show blockers all",
                "diagnose task <task_id>",
                "resume session <session_id>",
                "summarize why project <project_id> is stuck",
            ),
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        prefsManager = PrefsManager(this)

        val adapter = OnboardingPagerAdapter(pages)
        binding.onboardingPager.adapter = adapter
        TabLayoutMediator(binding.pageIndicator, binding.onboardingPager) { _, _ -> }.attach()
        configurePageIndicators()
        binding.skipButton.setOnClickListener { completeOnboarding() }
        binding.primaryButton.setOnClickListener {
            val nextIndex = binding.onboardingPager.currentItem + 1
            if (nextIndex < pages.size) {
                binding.onboardingPager.currentItem = nextIndex
            } else {
                completeOnboarding()
            }
        }
        binding.secondaryButton.setOnClickListener {
            val previousIndex = binding.onboardingPager.currentItem - 1
            if (previousIndex >= 0) {
                binding.onboardingPager.currentItem = previousIndex
            }
        }

        binding.onboardingPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateActions(position)
                updatePageIndicators(position)
            }
        })

        updateActions(0)
    }

    private fun applySystemInsets() {
        val rootStart = binding.root.paddingStart
        val rootTop = binding.root.paddingTop
        val rootEnd = binding.root.paddingEnd
        val rootBottom = binding.root.paddingBottom
        val buttonRowBottom = binding.buttonRow.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(
                rootStart + systemBars.left,
                rootTop + systemBars.top,
                rootEnd + systemBars.right,
                rootBottom
            )
            binding.buttonRow.updatePadding(bottom = buttonRowBottom + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateActions(position: Int) {
        val isFirst = position == 0
        val isLast = position == pages.lastIndex

        binding.secondaryButton.isEnabled = !isFirst
        binding.secondaryButton.alpha = if (isFirst) 0.45f else 1f
        binding.primaryButton.text = if (isLast) {
            getString(R.string.onboarding_finish)
        } else {
            getString(R.string.onboarding_next)
        }
    }

    private fun configurePageIndicators() {
        val inactiveSize = 6.dpToPx()
        val spacing = 4.dpToPx()

        repeat(pages.size) { index ->
            val tab = binding.pageIndicator.getTabAt(index) ?: return@repeat
            tab.customView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(inactiveSize, inactiveSize).apply {
                    marginStart = spacing
                    marginEnd = spacing
                }
                background = AppCompatResources.getDrawable(
                    context,
                    R.drawable.bg_onboarding_indicator_inactive
                )
            }
        }

        updatePageIndicators(binding.onboardingPager.currentItem)
    }

    private fun updatePageIndicators(selectedPosition: Int) {
        val inactiveSize = 6.dpToPx()
        val activeWidth = 20.dpToPx()
        val spacing = 4.dpToPx()

        repeat(pages.size) { index ->
            val indicatorView = binding.pageIndicator.getTabAt(index)?.customView ?: return@repeat
            val params = (indicatorView.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(inactiveSize, inactiveSize)
            params.width = if (index == selectedPosition) activeWidth else inactiveSize
            params.height = inactiveSize
            params.marginStart = spacing
            params.marginEnd = spacing
            indicatorView.layoutParams = params
            indicatorView.background = AppCompatResources.getDrawable(
                this,
                if (index == selectedPosition) {
                    R.drawable.bg_onboarding_indicator_active
                } else {
                    R.drawable.bg_onboarding_indicator_inactive
                }
            )
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun completeOnboarding() {
        prefsManager.onboardingCompleted = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
