package com.user.ui.activities

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.user.ClawMobileApplication
import com.user.BuildConfig
import com.user.R
import com.user.data.AppPreferences
import com.user.data.BackendSettings
import com.user.data.GatewayProviderStatus
import com.user.data.GitConnection
import com.user.data.PrefsManager
import com.user.data.previewSecret
import com.user.databinding.ActivitySettingsBinding
import com.user.service.GatewayHealthChecker
import com.user.service.GatewayProviderStatusClient
import com.user.service.OrchestratorApiClient
import com.user.util.GatewaySettingsValidator
import com.user.util.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingActivity"

/**
 * Settings activity for configuring gateway, GitHub, and Orchestrator integration.
 *
 * Network Configuration Guide:
 *
 * OpenClaw Gateway (Required):
 * - Local WiFi (phone on same network as host): http://<host-lan-ip>:18789
 *   Example: If your computer has IP xx.x.x.xxx, use http://xx.x.x.xxx:18789
 * - Android Emulator: http://localhost:18789 or http://xxx.x.x.x:18789
 * - Mobile Data / Remote: Tailscale IP of host:18789
 *
 * Orchestrator Dashboard/API (Optional):
 * - Local WiFi (phone on same network as host): http://<host-lan-ip>:8080
 *   Example: If your computer has IP xx.x.x.xxx, use http://xx.x.x.xxx:8080
 * - Android Emulator ONLY: http://xxx.xx.x.x:8080 (Docker bridge network)
 *   Dashboard UI: http://xxx.xx.x.x:3000
 *
 * IMPORTANT: Find your host machine's LAN IP with: ip addr show | grep "inet "
 * Look for the WiFi/Ethernet interface (e.g., eth0, wlan0), NOT lo or docker0
 * Example output: inet xx.x.x.xxx/xx brd ... scope global wlP9s9
 *
 * Note: Orchestrator integration is optional. The app works fully without it,
 * using only local data from the OpenClaw Gateway.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsManager
    private lateinit var appPreferences: AppPreferences
    private val gatewayHealthChecker = GatewayHealthChecker()
    private val providerStatusClient = GatewayProviderStatusClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(com.user.R.string.main_menu_settings)

        prefs = PrefsManager(this)
        appPreferences = AppPreferences(this)

        // Load existing values or use defaults from local.properties (BuildConfig)
        val gatewaySettings = AppPreferences.parseUrl(prefs.serverUrl)
        binding.gatewayHostInput.setText(gatewaySettings.host)
        binding.gatewayPortInput.setText(gatewaySettings.port.toString())
        binding.useHttpsSwitch.isChecked = gatewaySettings.useHttps
        binding.serverUrlInput.setText(gatewaySettings.baseUrl)

        // Pre-fill gateway token from BuildConfig if not already saved
        val savedGatewayToken = prefs.gatewayToken
        val defaultApiKey = BuildConfig.MOBILE_GATEWAY_API_KEY
        if (savedGatewayToken.isEmpty() && defaultApiKey.isNotEmpty()) {
            binding.gatewayTokenInput.setText(defaultApiKey)
            binding.gatewayTokenInput.hint = "Auto-filled from local.properties"
        } else {
            binding.gatewayTokenInput.setText(savedGatewayToken)
        }

        binding.githubTokenInput.setText(prefs.githubToken)
        binding.githubApiUrlInput.setText(prefs.githubApiUrl)
        binding.githubDefaultRepoInput.setText(prefs.githubDefaultRepo)

        // Load Orchestrator settings (optional)
        binding.orchestratorServerUrlInput.setText(prefs.orchestratorServerUrl)

        // Pre-fill orchestrator API key from saved value or BuildConfig
        val savedOrchApiKey = prefs.orchestratorApiKey
        if (savedOrchApiKey.isEmpty() && defaultApiKey.isNotEmpty()) {
            binding.orchestratorApiKeyInput.setText(defaultApiKey)
            binding.orchestratorApiKeyInput.hint = "Auto-filled from local.properties"
        } else {
            binding.orchestratorApiKeyInput.setText(savedOrchApiKey)
        }

        // Always show Orchestrator section so users can configure it
        binding.orchestratorSection.visibility = android.view.View.VISIBLE

        binding.orchestratorTestButton.setOnClickListener {
            testOrchestratorConnection()
        }
        binding.gatewayTestButton.setOnClickListener {
            testGatewayConnection()
        }
        binding.providerStatusRefreshButton.setOnClickListener {
            refreshProviderStatus()
        }
        loadCachedProviderStatus()

        // Auto-test connection when URL field loses focus (T021)
        binding.orchestratorServerUrlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && binding.orchestratorServerUrlInput.text?.isNotBlank() == true) {
                testOrchestratorConnection()
            }
        }

        binding.saveButton.setOnClickListener {
            val backendSettings = readGatewaySettings() ?: return@setOnClickListener
            val serverUrl = backendSettings.baseUrl
            val gatewayToken = binding.gatewayTokenInput.text.toString().trim()
            val githubToken = binding.githubTokenInput.text.toString().trim()
            val githubApiUrl = binding.githubApiUrlInput.text.toString().trim()
            val githubDefaultRepo = binding.githubDefaultRepoInput.text.toString().trim()

            // Orchestrator settings (optional) - save exactly as entered
            var orchestratorServerUrl = binding.orchestratorServerUrlInput.text.toString().trim()
            val orchestratorApiKey = binding.orchestratorApiKeyInput.text.toString().trim()

            Log.d(TAG, "BEFORE SAVING:")
            Log.d(TAG, "  orchestratorServerUrl input: '$orchestratorServerUrl'")
            Log.d(TAG, "  gatewayToken: '${previewSecret(gatewayToken)}'")

            when {
                serverUrl.isEmpty() -> {
                    Toast.makeText(this, "OpenClaw Gateway URL cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                gatewayToken.isEmpty() -> {
                    Toast.makeText(this, "Gateway Token cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                else -> {
                    prefs.serverUrl = serverUrl
                    prefs.gatewayToken = gatewayToken
                    CoroutineScope(Dispatchers.IO).launch {
                        appPreferences.saveBackendSettings(backendSettings.copy(token = gatewayToken))
                    }

                    // GitHub settings (optional)
                    prefs.githubToken = githubToken
                    prefs.githubApiUrl = githubApiUrl.ifBlank { "https://api.github.com" }
                    prefs.githubDefaultRepo = githubDefaultRepo

                    val gitDao = (application as ClawMobileApplication).gitConnectionDao
                    CoroutineScope(Dispatchers.IO).launch {
                        if (githubToken.isBlank()) {
                            gitDao.deleteConnectionById("github_default")
                        } else {
                            gitDao.insertConnection(
                                GitConnection(
                                    platform = "GITHUB",
                                    apiUrl = prefs.githubApiUrl,
                                    token = githubToken,
                                    defaultRepo = githubDefaultRepo.ifBlank { null }
                                )
                            )
                        }
                    }

                    // Orchestrator settings (optional) - save exactly as entered
                    val apiKeyToUse = orchestratorApiKey.ifEmpty { gatewayToken }
                    if (orchestratorServerUrl.isNotBlank()) {
                        prefs.orchestratorApiKey = apiKeyToUse
                        Toast.makeText(
                            this,
                            "Orchestrator configured: $orchestratorServerUrl",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // Clear Orchestrator settings if URL is empty - keep API key synced with gateway token
                        prefs.orchestratorServerUrl = ""
                        if (gatewayToken.isNotEmpty()) {
                            prefs.orchestratorApiKey = gatewayToken
                        }
                    }

                    // Keep Orchestrator section visible for continued configuration
                    binding.orchestratorSection.visibility = android.view.View.VISIBLE

                    Log.d(TAG, "SAVING settings:")
                    Log.d(TAG, "  serverUrl = '$serverUrl'")
                    Log.d(TAG, "  orchestratorServerUrl = '$orchestratorServerUrl'")
                    Log.d(TAG, "  orchestratorApiKey = '${previewSecret(apiKeyToUse)}'")

                    prefs.serverUrl = serverUrl
                    prefs.orchestratorServerUrl = orchestratorServerUrl

                    // Verify what was actually saved
                    val savedOrchUrl = prefs.orchestratorServerUrl
                    val savedApiKey = prefs.orchestratorApiKey
                    Log.d(TAG, "VERIFIED SAVED - Url: '$savedOrchUrl', ApiKey: '${previewSecret(savedApiKey)}'")

                    Toast.makeText(this, "Settings saved! Reconnecting...", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun testOrchestratorConnection() {
        val orchestratorServerUrl = binding.orchestratorServerUrlInput.text.toString().trim()
        val gatewayToken = binding.gatewayTokenInput.text.toString().trim()
        val orchestratorApiKey = binding.orchestratorApiKeyInput.text.toString().trim()
        val apiKeyToUse = orchestratorApiKey.ifEmpty { gatewayToken }

        if (orchestratorServerUrl.isBlank()) {
            showOrchestratorTestStatus(getString(R.string.settings_orchestrator_test_missing_url), false)
            return
        }

        if (apiKeyToUse.isBlank()) {
            showOrchestratorTestStatus(getString(R.string.settings_orchestrator_test_missing_key), false)
            return
        }

        binding.orchestratorTestButton.isEnabled = false
        showOrchestratorTestStatus(getString(R.string.settings_orchestrator_test_in_progress), neutral = true)

        val client = OrchestratorApiClient(
            prefs = prefs,
            gatewayToken = gatewayToken,
            overrideServerUrl = orchestratorServerUrl,
            overrideApiKey = apiKeyToUse
        )

        CoroutineScope(Dispatchers.Main).launch {
            client.testConnection().onSuccess { success ->
                if (success) {
                    showOrchestratorTestStatus(getString(R.string.settings_orchestrator_test_success), true)
                } else {
                    showOrchestratorTestStatus(getString(R.string.settings_orchestrator_test_failed), false)
                }
            }.onFailure { error ->
                val message = error.message ?: getString(R.string.settings_orchestrator_test_failed)
                showOrchestratorTestStatus(message, false)
            }
            binding.orchestratorTestButton.isEnabled = true
        }
    }

    private fun readGatewaySettings(): BackendSettings? {
        val host = binding.gatewayHostInput.text?.toString()?.trim().orEmpty()
        val port = binding.gatewayPortInput.text?.toString()?.trim().orEmpty()
        val token = binding.gatewayTokenInput.text?.toString()?.trim().orEmpty()

        binding.gatewayHostInput.error = null
        binding.gatewayPortInput.error = null
        binding.gatewayTokenInput.error = null

        return when (val result = GatewaySettingsValidator.validate(
            host = host,
            portText = port,
            token = token,
            useHttps = binding.useHttpsSwitch.isChecked
        )) {
            is ValidationResult.Valid -> result.settings.also {
                binding.serverUrlInput.setText(it.baseUrl)
            }
            is ValidationResult.Invalid -> {
                binding.gatewayHostInput.error = result.hostError
                binding.gatewayPortInput.error = result.portError
                binding.gatewayTokenInput.error = result.tokenError
                null
            }
        }
    }

    private fun testGatewayConnection() {
        val settings = readGatewaySettings() ?: return
        val token = binding.gatewayTokenInput.text?.toString()?.trim().orEmpty()
        if (token.isBlank()) {
            showGatewayTestStatus("Enter a Gateway Token first.", success = false)
            return
        }

        binding.gatewayTestButton.isEnabled = false
        showGatewayTestStatus("Testing Gateway connection...", neutral = true)

        CoroutineScope(Dispatchers.Main).launch {
            val result = gatewayHealthChecker.check(settings, token)
            result.onSuccess {
                showGatewayTestStatus("Connected to Gateway successfully.", success = true)
            }.onFailure { error ->
                showGatewayTestStatus(error.message ?: "Could not connect to Gateway.", success = false)
            }
            binding.gatewayTestButton.isEnabled = true
        }
    }

    private fun loadCachedProviderStatus() {
        lifecycleScope.launch {
            val providers = withContext(Dispatchers.IO) {
                (application as ClawMobileApplication).providerStatusDao.getAll()
            }
            showProviderStatuses(providers)
        }
    }

    private fun refreshProviderStatus() {
        val settings = readGatewaySettings() ?: return
        val token = binding.gatewayTokenInput.text?.toString()?.trim().orEmpty()
        if (token.isBlank()) {
            showProviderStatusMessage(getString(R.string.provider_status_missing_token), success = false)
            return
        }

        binding.providerStatusRefreshButton.isEnabled = false
        showProviderStatusMessage(getString(R.string.provider_status_refreshing), neutral = true)

        lifecycleScope.launch {
            val result = providerStatusClient.fetch(settings, token)
            result.onSuccess { providers ->
                val cachedProviders = withContext(Dispatchers.IO) {
                    val dao = (application as ClawMobileApplication).providerStatusDao
                    dao.upsertAll(providers)
                    dao.deleteStale(System.currentTimeMillis() - PROVIDER_STATUS_STALE_MS)
                    dao.getAll()
                }
                showProviderStatuses(cachedProviders)
            }.onFailure { error ->
                showProviderStatusMessage(
                    error.message ?: getString(R.string.provider_status_failed),
                    success = false
                )
            }
            binding.providerStatusRefreshButton.isEnabled = true
        }
    }

    private fun showProviderStatuses(providers: List<GatewayProviderStatus>) {
        if (providers.isEmpty()) {
            showProviderStatusMessage(getString(R.string.provider_status_empty), neutral = true)
            return
        }

        binding.providerStatusSummary.visibility = View.VISIBLE
        binding.providerStatusSummary.text = resources.getQuantityString(
            R.plurals.provider_status_summary,
            providers.size,
            providers.size
        )
        binding.providerStatusSummary.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.providerStatusList.visibility = View.VISIBLE
        binding.providerStatusList.text = providers.joinToString("\n\n") { provider ->
            val model = provider.activeModel ?: getString(R.string.provider_status_model_unknown)
            val latency = provider.lastLatencyMs?.let {
                getString(R.string.provider_status_latency_ms, it)
            } ?: getString(R.string.provider_status_latency_unknown)
            val checked = formatProviderCheckedAt(provider.lastCheckedAt)
            getString(
                R.string.provider_status_row,
                provider.displayName,
                provider.status,
                model,
                latency,
                checked
            )
        }
        binding.providerStatusList.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
    }

    private fun showProviderStatusMessage(
        message: String,
        success: Boolean? = null,
        neutral: Boolean = false
    ) {
        binding.providerStatusSummary.visibility = View.VISIBLE
        binding.providerStatusSummary.text = message
        val colorRes = when {
            neutral -> R.color.timestamp_text
            success == true -> R.color.status_completed
            else -> R.color.status_failed
        }
        binding.providerStatusSummary.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.providerStatusList.visibility = View.GONE
    }

    private fun formatProviderCheckedAt(checkedAtMillis: Long): String {
        val elapsedMinutes = ((System.currentTimeMillis() - checkedAtMillis) / 60_000).coerceAtLeast(0)
        return when {
            elapsedMinutes == 0L -> getString(R.string.provider_status_checked_now)
            elapsedMinutes == 1L -> getString(R.string.provider_status_checked_one_minute)
            else -> getString(R.string.provider_status_checked_minutes, elapsedMinutes)
        }
    }

    private fun showGatewayTestStatus(message: String, success: Boolean? = null, neutral: Boolean = false) {
        binding.gatewayTestStatus.visibility = View.VISIBLE
        binding.gatewayTestStatus.text = message
        val colorRes = when {
            neutral -> R.color.timestamp_text
            success == true -> R.color.status_completed
            else -> R.color.status_failed
        }
        binding.gatewayTestStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showOrchestratorTestStatus(message: String, success: Boolean? = null, neutral: Boolean = false) {
        binding.orchestratorTestStatus.visibility = View.VISIBLE
        binding.orchestratorTestStatus.text = message
        val colorRes = when {
            neutral -> R.color.timestamp_text
            success == true -> R.color.status_completed
            else -> R.color.status_failed
        }
        binding.orchestratorTestStatus.setTextColor(ContextCompat.getColor(this, colorRes))

        // Update MD3 connection status indicator (T021)
        when {
            neutral -> {
                binding.connectionStatusText.text = getString(R.string.connection_status_checking)
                binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.connectionStatusIcon.setImageResource(android.R.drawable.presence_away)
            }
            success == true -> {
                binding.connectionStatusText.text = getString(R.string.connection_status_connected)
                binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                binding.connectionStatusIcon.setImageResource(android.R.drawable.presence_online)
            }
            else -> {
                binding.connectionStatusText.text = getString(R.string.connection_status_disconnected)
                binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_failed))
                binding.connectionStatusIcon.setImageResource(android.R.drawable.presence_offline)
            }
        }
    }

    /**
     * Show a toast with network troubleshooting tips
     */
    private fun showNetworkTroubleshootingTips() {
        val message = """
            Network Troubleshooting:
            1. Check Android device is on same WiFi as host machine
            2. Find host IP: ip addr show | grep "inet "
            3. Use the WiFi/Ethernet IP (e.g., xx.x.x.xxx), NOT localhost or Docker IPs
            4. Make sure firewall allows port 8080: sudo ufw allow 8080/tcp
        """.trimIndent()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PROVIDER_STATUS_STALE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
