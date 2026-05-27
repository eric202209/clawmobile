package com.user.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.user.R
import com.user.data.PrefsManager
import com.user.databinding.ActivityMainBinding
import com.user.databinding.BottomSheetAttachmentBinding
import com.user.service.AgentInfo
import com.user.ui.ChatAdapter
import com.user.ui.tasks.TaskListActivity
import com.user.util.UiState
import com.user.viewmodel.ChatViewModel

/**
 * Main chat activity - primary interface for user interaction
 */
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PREFILL_MESSAGE = "prefill_message"
    }


    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var prefsManager: PrefsManager
    private var selectedFileUri: Uri? = null
    private val commandTemplates = listOf(
        "show blockers all",
        "diagnose task <task_id>",
        "what should I do next for project <project_id>",
        "find the best session to resume for project <project_id>",
        "summarize why project <project_id> is stuck",
        "what failed most recently",
        "compare recent failures across projects",
        "resume session <session_id>",
    )
    private val inputHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var selectedAgentId: String? = null

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.let { binding.messageEditText.setText(it) }
            }
        }

    private val galleryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let(::setSelectedFile)
            }
        }

    private val documentPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let(::setSelectedFile)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefsManager = PrefsManager(this)
        if (!prefsManager.onboardingCompleted) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (prefsManager.gatewayToken.isEmpty()) {
            Toast.makeText(this, "Please enter your Gateway Token", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        setupRecyclerView()
        setupObservers()
        setupInputHandlers()
        setupCommandSuggestions()
        updateAttachmentUi()
        restoreInputHistory()
        handlePrefillIntent(intent)

        val sessionId = intent.getStringExtra("session_id")
        val sessionTitle = intent.getStringExtra("session_title")
        if (sessionId != null) {
            supportActionBar?.title = sessionTitle ?: getString(R.string.toolbar_title)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            viewModel.loadSession(sessionId)
        } else {
            supportActionBar?.title = getString(R.string.toolbar_title)
            viewModel.startNewSession()
            requestNotificationPermission()
            viewModel.stopService(this)
        }

        applyToolbarIconTints()
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStart() {
        super.onStart()
        viewModel.stopService(this)
        viewModel.connect()
    }

    override fun onStop() {
        super.onStop()
        viewModel.disconnect()
        viewModel.stopService(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePrefillIntent(intent)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
            if (itemDecorationCount == 0) {
                addItemDecoration(MessageSpacingDecoration(4.dpToPx()))
            }
        }
    }

    private fun setupObservers() {
        viewModel.status.observe(this) { status ->
            binding.statusText.text = status.removePrefix("● ").removePrefix("○ ").removePrefix("✕ ")
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.setTextColor(resolveStatusColor(status))

            if (status.startsWith("●")) {
                binding.statusText.postDelayed({
                    if (!isFinishing) {
                        binding.statusText.visibility = View.GONE
                    }
                }, 1500)
            }
        }

        viewModel.messages.observe(this) { messages ->
            chatAdapter.submitList(messages.toList())
            if (messages.isNotEmpty()) {
                binding.recyclerView.scrollToPosition(messages.size - 1)
            }
        }

        viewModel.agents.observe(this) { setupAgentSpinner(it) }
        viewModel.isSending.observe(this) { binding.sendButton.isEnabled = !it }
        viewModel.showTyping.observe(this) { show ->
            binding.typingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        }
        viewModel.pairingRequired.observe(this) { showPairingDialog(it) }
        viewModel.toast.observe(this) { message ->
            message ?: return@observe
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
        viewModel.isOnline.observe(this) { online ->
            if (online) {
                binding.offlineBanner.hide()
            } else {
                binding.offlineBanner.showWithTimestamp(null)
            }
        }
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Error -> {
                    binding.alertsText.text = state.message
                    binding.cardAlerts.visibility = View.VISIBLE
                    binding.errorRetryButton.visibility =
                        if (state.retryable) View.VISIBLE else View.GONE
                    binding.emptyStateText.visibility = View.GONE
                }
                is UiState.Success -> {
                    binding.cardAlerts.visibility = View.GONE
                    binding.errorRetryButton.visibility = View.GONE
                    binding.emptyStateText.visibility =
                        if (state.data.isEmpty()) View.VISIBLE else View.GONE
                }
                UiState.Loading -> {
                    binding.cardAlerts.visibility = View.GONE
                    binding.errorRetryButton.visibility = View.GONE
                    binding.emptyStateText.visibility = View.GONE
                }
            }
        }
        binding.offlineBanner.retryAction = { viewModel.connect() }
        binding.errorRetryButton.setOnClickListener { viewModel.connect() }
    }

    private fun setupInputHandlers() {
        binding.sendButton.setOnClickListener { sendMessage() }
        binding.messageEditText.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN &&
                        !event.isShiftPressed -> {
                    sendMessage()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                        event.action == KeyEvent.ACTION_DOWN -> {
                    recallPreviousInput()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        event.action == KeyEvent.ACTION_DOWN -> {
                    recallNextInput()
                    true
                }
                else -> false
            }
        }
        binding.attachButton.setOnClickListener {
            if (selectedFileUri != null) {
                clearSelectedFile()
            } else {
                showAttachmentSheet()
            }
        }
        binding.voiceButton.setOnClickListener { startVoiceInput() }
        binding.removePreviewButton.setOnClickListener { clearSelectedFile() }
    }

    private fun setupCommandSuggestions() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            commandTemplates
        )
        binding.messageEditText.apply {
            setAdapter(adapter)
            threshold = 1
            setOnItemClickListener { _, _, position, _ ->
                val selectedTemplate = adapter.getItem(position) ?: return@setOnItemClickListener
                applyCommandTemplate(selectedTemplate)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val text = s?.toString()?.trimStart().orEmpty()
                    if (shouldSuggestCommands(text) && hasFocus()) {
                        post { showDropDown() }
                    }
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }

    private fun shouldSuggestCommands(text: String): Boolean {
        if (text.isEmpty()) return false
        val normalized = text.lowercase()
        return normalized.startsWith("show") ||
                normalized.startsWith("diagnose") ||
                normalized.startsWith("what") ||
                normalized.startsWith("find") ||
                normalized.startsWith("summarize") ||
                normalized.startsWith("compare") ||
                normalized.startsWith("resume") ||
                normalized.startsWith("retry") ||
                normalized.startsWith("restart")
    }

    private fun prefillCommand(command: String) {
        binding.messageEditText.setText(command)
        binding.messageEditText.setSelection(command.length)
        binding.messageEditText.requestFocus()
        binding.messageEditText.dismissDropDown()
        Toast.makeText(this, getString(R.string.command_prefilled), Toast.LENGTH_SHORT).show()
    }

    private fun handlePrefillIntent(intent: Intent?) {
        val command = intent?.getStringExtra(EXTRA_PREFILL_MESSAGE)?.takeIf { it.isNotBlank() } ?: return
        prefillCommand(command)
        intent.removeExtra(EXTRA_PREFILL_MESSAGE)
    }

    private fun applyCommandTemplate(template: String) {
        binding.messageEditText.setText(template)
        binding.messageEditText.requestFocus()
        val placeholderStart = template.indexOf('<')
        val placeholderEnd = template.indexOf('>').takeIf { it > placeholderStart }
        if (placeholderStart >= 0 && placeholderEnd != null) {
            binding.messageEditText.setSelection(placeholderStart, placeholderEnd + 1)
            Toast.makeText(
                this,
                getString(R.string.command_template_ready),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            binding.messageEditText.setSelection(template.length)
            Toast.makeText(
                this,
                getString(R.string.command_suggestion_selected),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.messageEditText.dismissDropDown()
    }

    private fun setupAgentSpinner(agents: List<AgentInfo>) {
        if (agents.isEmpty()) return

        val adapter = ArrayAdapter(
            this,
            R.layout.item_agent_spinner,
            agents.map { it.name }
        ).also { it.setDropDownViewResource(R.layout.item_agent_spinner_dropdown) }

        binding.agentSpinner.adapter = adapter
        val selectedIndex = agents.indexOfFirst { it.agentId == selectedAgentId }
            .takeIf { it >= 0 }
            ?: 0
        selectedAgentId = agents[selectedIndex].agentId
        binding.agentSpinner.setSelection(selectedIndex, false)
        binding.agentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val agentId = agents.getOrNull(position)?.agentId ?: return
                if (agentId == selectedAgentId) return

                selectedAgentId = agentId
                viewModel.switchAgent(agentId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showPairingDialog(deviceId: String?) {
        deviceId ?: return
        AlertDialog.Builder(this)
            .setTitle("Device Pairing Required")
            .setMessage(
                "Run on your GX10 device:\n\n" +
                        "1. Run: openclaw gateway call device.pair.list --json\n\n" +
                        "2. Find your device ID from the list above\n\n" +
                        "3. Run:\n" +
                        "   openclaw gateway call device.pair.approve \\\n" +
                        "   --params '{\"requestID\":\"<device-id>\"}' --json\n\n" +
                        "Device ID to approve:\n${deviceId.take(12)}…\n\n" +
                        "After approving, tap Retry to reconnect."
            )
            .setPositiveButton("Retry") { _, _ ->
                viewModel.clearPairingDialog()
                viewModel.connect()
                Toast.makeText(this, "Retrying connection...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()

        if (selectedFileUri != null) {
            viewModel.sendFile(this, selectedFileUri!!, text)
            clearSelectedFile(clearInput = false)
            binding.messageEditText.text?.clear()
            return
        }

        if (text.isEmpty()) return
        rememberInput(text)
        binding.messageEditText.text?.clear()
        binding.messageEditText.dismissDropDown()
        viewModel.sendMessage(text)
    }

    private fun rememberInput(text: String) {
        if (inputHistory.lastOrNull() != text) {
            inputHistory.add(text)
            prefsManager.saveRecentInputHistory(inputHistory)
        }
        historyIndex = inputHistory.size
    }

    private fun recallPreviousInput() {
        if (inputHistory.isEmpty()) return
        if (historyIndex > 0) {
            historyIndex -= 1
        } else {
            historyIndex = 0
        }
        applyHistoryEntry()
    }

    private fun recallNextInput() {
        if (inputHistory.isEmpty()) return
        if (historyIndex < inputHistory.lastIndex) {
            historyIndex += 1
            applyHistoryEntry()
        } else {
            historyIndex = inputHistory.size
            binding.messageEditText.text?.clear()
        }
    }

    private fun applyHistoryEntry() {
        if (historyIndex !in inputHistory.indices) return
        val text = inputHistory[historyIndex]
        binding.messageEditText.setText(text)
        binding.messageEditText.setSelection(text.length)
    }

    private fun restoreInputHistory() {
        inputHistory.clear()
        inputHistory.addAll(prefsManager.getRecentInputHistory())
        historyIndex = inputHistory.size
    }

    private fun showAttachmentSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val sheetBinding = BottomSheetAttachmentBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)

        sheetBinding.galleryOptionButton.setOnClickListener {
            bottomSheet.dismiss()
            openGalleryPicker()
        }
        sheetBinding.documentOptionButton.setOnClickListener {
            bottomSheet.dismiss()
            openDocumentPicker()
        }

        bottomSheet.show()
    }

    private fun openGalleryPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        galleryPickerLauncher.launch(Intent.createChooser(intent, getString(R.string.attachment_gallery)))
    }

    private fun openDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/pdf",
                    "text/plain",
                    "text/markdown",
                    "application/json",
                    "text/csv",
                    "application/xml",
                    "text/xml"
                )
            )
        }
        documentPickerLauncher.launch(
            Intent.createChooser(intent, getString(R.string.attachment_documents))
        )
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message…")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setSelectedFile(uri: Uri) {
        selectedFileUri = uri
        showFilePreview(uri)
        updateAttachmentUi()
    }

    private fun clearSelectedFile(clearInput: Boolean = false) {
        selectedFileUri = null
        binding.imagePreviewLayout.visibility = View.GONE
        binding.imagePreviewImage.setImageDrawable(null)
        if (clearInput) {
            binding.messageEditText.text?.clear()
        }
        updateAttachmentUi()
    }

    private fun updateAttachmentUi() {
        val hasAttachment = selectedFileUri != null
        binding.attachButton.setImageResource(
            if (hasAttachment) android.R.drawable.ic_delete else R.drawable.ic_add
        )
        binding.attachButton.contentDescription = getString(
            if (hasAttachment) R.string.remove_attachment else R.string.more_options
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                200
            )
        }
    }

    private fun showFilePreview(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri)
            val fileName = getFileName(uri)

            binding.imagePreviewText.text = fileName ?: getString(R.string.image_attached)
            binding.imagePreviewLayout.visibility = View.VISIBLE

            if (mimeType?.startsWith("image/") == true) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    binding.imagePreviewImage.setImageBitmap(bitmap)
                }
            } else {
                binding.imagePreviewImage.setImageResource(android.R.drawable.ic_menu_save)
            }
        } catch (_: Exception) {
            binding.imagePreviewLayout.visibility = View.GONE
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        applyToolbarIconTints(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_chat -> {
                startActivity(Intent(this, MainActivity::class.java))
                true
            }
            R.id.action_history -> {
                startActivity(Intent(this, ChatHistoryActivity::class.java))
                true
            }
            R.id.action_tasks -> {
                startActivity(Intent(this, TaskListActivity::class.java))
                true
            }
            R.id.action_tools -> {
                startActivity(Intent(this, com.user.ui.tools.ToolsActivity::class.java))
                true
            }
            R.id.action_notes -> {
                startActivity(Intent(this, NotesActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun resolveStatusColor(status: String): Int {
        val colorRes = when {
            status.startsWith("✕") -> R.color.status_failed
            status.startsWith("●") -> R.color.status_connected
            status.contains("error", ignoreCase = true) -> R.color.status_failed
            else -> R.color.status_disconnected
        }
        return ContextCompat.getColor(this, colorRes)
    }

    private fun applyToolbarIconTints(menu: Menu? = null) {
        val iconColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.toolbar.navigationIcon?.mutate()?.let { navigationIcon ->
            DrawableCompat.setTint(navigationIcon, iconColor)
            binding.toolbar.navigationIcon = navigationIcon
        }

        menu?.let {
            for (index in 0 until it.size()) {
                val menuItem = it.getItem(index)
                menuItem.icon?.mutate()?.let { icon ->
                    DrawableCompat.setTint(icon, iconColor)
                    menuItem.icon = icon
                }
            }
        }

        binding.toolbar.overflowIcon = AppCompatResources.getDrawable(this, R.drawable.ic_more_vert)
            ?.mutate()
            ?.also { overflowIcon ->
                DrawableCompat.setTint(overflowIcon, iconColor)
            }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private class MessageSpacingDecoration(
        private val spacingPx: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.top = spacingPx / 2
            outRect.bottom = spacingPx / 2
        }
    }
}
