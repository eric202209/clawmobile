package com.user.ui.activities

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.user.R
import com.user.databinding.ActivityGatewayDashboardBinding
import com.user.ui.ProviderStatusAdapter
import com.user.viewmodel.DashboardUiState
import com.user.viewmodel.GatewayDashboardViewModel

class GatewayDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGatewayDashboardBinding
    private val viewModel: GatewayDashboardViewModel by viewModels()
    private lateinit var adapter: ProviderStatusAdapter
    private var lastReady: DashboardUiState.Ready? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGatewayDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.dashboard_title)

        adapter = ProviderStatusAdapter()
        binding.providerList.layoutManager = LinearLayoutManager(this)
        binding.providerList.adapter = adapter

        binding.retryButton.setOnClickListener { viewModel.refresh() }
        binding.refreshButton.setOnClickListener { viewModel.refresh() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }

        viewModel.state.observe(this, ::renderState)
    }

    private fun renderState(state: DashboardUiState) {
        binding.swipeRefresh.isRefreshing = state is DashboardUiState.Refreshing

        when (state) {
            is DashboardUiState.Idle -> {
                binding.healthCard.visibility = View.GONE
                binding.errorCard.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
                binding.staleBanner.visibility = View.GONE
            }
            is DashboardUiState.Refreshing -> {
                lastReady?.let { renderReady(it) }
            }
            is DashboardUiState.Ready -> {
                lastReady = state
                renderReady(state)
                binding.errorCard.visibility = View.GONE
            }
            is DashboardUiState.Error -> {
                if (lastReady == null) {
                    binding.healthCard.visibility = View.GONE
                    binding.emptyState.visibility = View.GONE
                }
                binding.errorCard.visibility = View.VISIBLE
                binding.errorMessage.text = state.message
                binding.staleBanner.visibility = View.GONE
            }
        }
    }

    private fun renderReady(state: DashboardUiState.Ready) {
        binding.healthCard.visibility = View.VISIBLE
        binding.staleBanner.visibility = if (state.staleCache) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (state.providers.isEmpty()) View.VISIBLE else View.GONE
        applyHealthStyle(state.isHealthy)
        adapter.submitList(state.providers)
    }

    private fun applyHealthStyle(isHealthy: Boolean) {
        val (colorRes, label) = if (isHealthy) {
            R.color.status_connected to getString(R.string.dashboard_health_healthy)
        } else {
            R.color.status_failed to getString(R.string.dashboard_health_unreachable)
        }
        binding.healthStatus.text = label
        binding.healthIndicator.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
