package com.user

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.SpannableString
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.user.data.PrefsManager
import com.user.databinding.ActivityMainBinding
import com.user.service.AgentInfo
import com.user.ui.ChatAdapter
import com.user.viewmodel.ChatViewModel
import android.net.Uri
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private val viewModel: ChatViewModel by viewModels()
    private var cameraImageUri: android.net.Uri? = null
    private var selectedImageUri: android.net.Uri? = null  // Stores selected image for preview
    private var isCameraOrGalleryActive = false  // Track if camera/gallery is currently active to prevent pairing dialog

    companion object {
        private const val VOICE_REQUEST_CODE = 100
        private const val CAMERA_REQUEST_CODE  = 101
        private const val GALLERY_REQUEST_CODE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // ── Restore camera URI after Activity recreation ──────
        savedInstanceState?.getString("camera_image_uri")?.let {
            cameraImageUri = Uri.parse(it)
        }

        if (PrefsManager(this).gatewayToken.isEmpty()) {
            Toast.makeText(this, "Please enter your Gateway Token", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        setupRecyclerView()
        setupObservers()
        setupInputHandlers()

        val sessionId    = intent.getStringExtra("session_id")
        val sessionTitle = intent.getStringExtra("session_title")
        if (sessionId != null) {
            title = sessionTitle ?: "Chat"
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            viewModel.loadSession(sessionId, sessionTitle)
        } else {
            viewModel.startNewSession()
            requestNotificationPermission()
            viewModel.startService(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist camera URI so it survives Activity recreation
        cameraImageUri?.let { outState.putString("camera_image_uri", it.toString()) }
    }

    // ── Setup ─────────────────────────────────────────────────

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
                .also { it.stackFromEnd = true }
            adapter = chatAdapter
        }
    }

    private fun setupObservers() {
        viewModel.status.observe(this) { status ->
            when {
                status.startsWith("✕") || status.startsWith("○") -> {
                    binding.statusText.text = status
                    binding.statusText.visibility = View.VISIBLE
                }
                status.startsWith("●") -> {
                    binding.statusText.text = status
                    binding.statusText.visibility = View.VISIBLE
                    binding.statusText.postDelayed({
                        binding.statusText.visibility = View.GONE
                    }, 1500)
                }
                else -> {
                    binding.statusText.text = status
                    binding.statusText.visibility = View.VISIBLE
                }
            }
        }
        viewModel.messages.observe(this) { messages ->
            chatAdapter.submitList(messages.toList())
            if (messages.isNotEmpty())
                binding.recyclerView.scrollToPosition(messages.size - 1)
        }
        viewModel.agents.observe(this)         { setupAgentSpinner(it) }
        viewModel.isSending.observe(this)      { binding.sendButton.isEnabled = !it }
        viewModel.showTyping.observe(this)     { show ->
            binding.typingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        }
        viewModel.pairingRequired.observe(this) { showPairingDialog(it) }
        viewModel.toast.observe(this) { message ->
            message ?: return@observe
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    private fun setupInputHandlers() {
        binding.sendButton.setOnClickListener    { sendMessage() }
        binding.voiceButton.setOnClickListener   { startVoiceInput() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
        binding.messageEditText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                sendMessage(); true
            } else false
        }
        binding.attachButton.setOnClickListener {
            if (selectedImageUri != null) {
                // Remove selected image
                selectedImageUri = null
                binding.attachButton.setImageResource(android.R.drawable.ic_input_add)
                binding.attachButton.setContentDescription("Attach image")
                binding.imagePreviewLayout.visibility = android.view.View.GONE
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Attach Image")
                    .setItems(arrayOf("📷  Camera", "🖼️  Gallery")) { _, which ->
                        when (which) {
                            0 -> openCamera()
                            1 -> openGallery()
                        }
                    }
                    .show()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun setupAgentSpinner(agents: List<AgentInfo>) {
        if (agents.isEmpty()) return
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            agents.map { it.name }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.agentSpinner.adapter = adapter
        binding.agentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) { viewModel.switchAgent(agents[position].agentId) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
            .setPositiveButton("Retry", { _, _ ->
                // Clear the pairing dialog state and retry connection
                viewModel.clearPairingDialog()
                viewModel.connect()
                Toast.makeText(this, "Retrying connection...", Toast.LENGTH_SHORT).show()
            })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()

        // If there's a selected image, send it with the message
        if (selectedImageUri != null) {
            viewModel.sendImage(this, selectedImageUri!!, text)
            // Clear the selected image after sending
            selectedImageUri = null
            binding.attachButton.setImageResource(android.R.drawable.ic_input_add)
            binding.attachButton.setContentDescription("Attach image")
            binding.imagePreviewLayout.visibility = android.view.View.GONE
            binding.messageEditText.text?.clear()
            return
        }

        // No image, send text message only
        if (text.isEmpty()) return
        binding.messageEditText.text?.clear()
        viewModel.sendMessage(text)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message…")
        }
        try { startActivityForResult(intent, VOICE_REQUEST_CODE) }
        catch (e: Exception) {
            Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200
                )
            }
        }
    }

    // ── Overrides ─────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Set camera/gallery active status before handling result
        when (requestCode) {
            VOICE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()?.let { binding.messageEditText.setText(it) }
                }
            }
            CAMERA_REQUEST_CODE -> {
               if (resultCode == RESULT_OK) {
                   val uri = cameraImageUri
                   if (uri != null) {
                       selectedImageUri = uri
                       showImagePreview(uri)
                       binding.attachButton.setImageResource(android.R.drawable.ic_delete)
                       binding.attachButton.setContentDescription("Remove image")
                   } else {
                       Toast.makeText(this, "Camera error: no image URI", Toast.LENGTH_SHORT).show()
                   }
               }
                isCameraOrGalleryActive = false
                viewModel.setCameraOrGalleryActive(false)
            }
            GALLERY_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        // Store selected image and show preview
                        selectedImageUri = uri
                        showImagePreview(uri)
                        binding.attachButton.setImageResource(android.R.drawable.ic_delete)
                        binding.attachButton.setContentDescription("Remove image")
                    }
                }
                // Mark camera/gallery as inactive and notify ViewModel
                isCameraOrGalleryActive = false
                viewModel.setCameraOrGalleryActive(false)
            }
        }
    }

    // Show image preview when a photo is selected
    private fun showImagePreview(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            binding.imagePreviewImage.setImageBitmap(bitmap)
            binding.imagePreviewLayout.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        for (i in 0 until menu.size()) {
            val item  = menu.getItem(i)
            val title = SpannableString(item.title)
            title.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.BLACK),
                0, title.length, android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
            )
            item.title = title
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_chat  -> { startActivity(Intent(this, MainActivity::class.java)); true }
            R.id.action_history   -> { startActivity(Intent(this, SessionsActivity::class.java)); true }
            R.id.action_settings  -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openCamera() {
        // Track that camera is active to prevent reconnection triggering pairing dialog
        isCameraOrGalleryActive = true
        viewModel.setCameraOrGalleryActive(true)
        val photoFile = java.io.File(
            java.io.File(cacheDir, "photos").also { it.mkdirs() },
            "photo_${System.currentTimeMillis()}.jpg"
        )
        cameraImageUri = FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", photoFile
        )
        val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraImageUri)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
        } else {
            isCameraOrGalleryActive = false
            viewModel.setCameraOrGalleryActive(false)
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        isCameraOrGalleryActive = true
        viewModel.setCameraOrGalleryActive(true)
        val intent = android.content.Intent(android.content.Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        startActivityForResult(intent, GALLERY_REQUEST_CODE)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}