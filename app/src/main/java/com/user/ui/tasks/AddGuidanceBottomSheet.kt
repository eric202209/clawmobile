package com.user.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.user.databinding.BottomSheetAddGuidanceBinding
import com.user.viewmodel.GuidancePriority
import com.user.viewmodel.HumanGuidanceViewModel

class AddGuidanceBottomSheet(
    private val isGlobalAdvancedMode: Boolean,
    private val onSave: (Map<String, Any>) -> Unit,
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddGuidanceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAddGuidanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (isGlobalAdvancedMode) {
            binding.purposeValidation.visibility = View.VISIBLE
        }

        binding.advancedTargetingSwitch.setOnCheckedChangeListener { _, checked ->
            binding.advancedTargetingSection.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.cancelButton.setOnClickListener { dismiss() }

        binding.saveGuidanceButton.setOnClickListener {
            val message = binding.messageInput.text?.toString()?.trim().orEmpty()
            if (message.isBlank()) {
                binding.messageInputLayout.error = "Message is required"
                return@setOnClickListener
            }
            binding.messageInputLayout.error = null

            val priority = when (binding.priorityToggleGroup.checkedButtonId) {
                binding.priorityLow.id -> GuidancePriority.LOW
                binding.priorityHigh.id -> GuidancePriority.HIGH
                else -> GuidancePriority.NORMAL
            }

            val backendTargets: List<String>
            val purposeTargets: List<String>

            if (binding.advancedTargetingSwitch.isChecked) {
                backendTargets = buildList {
                    if (binding.backendAll.isChecked) add("all")
                    if (binding.backendDirectOllama.isChecked) add("direct_ollama")
                    if (binding.backendLocalOpenclaw.isChecked) add("local_openclaw")
                }.ifEmpty { listOf("all") }

                purposeTargets = buildList {
                    if (binding.purposeAll.isChecked) add("all")
                    if (binding.purposePlanning.isChecked) add("planning")
                    if (binding.purposeExecution.isChecked) add("execution")
                    if (binding.purposeRepair.isChecked) add("repair")
                    if (isGlobalAdvancedMode && binding.purposeValidation.isChecked) add("validation")
                }.ifEmpty { listOf("all") }
            } else {
                backendTargets = listOf("all")
                purposeTargets = listOf("all")
            }

            val payload = HumanGuidanceViewModel.buildCreatePayload(
                message = message,
                scope = "project",
                priority = priority,
                backendTargets = backendTargets,
                modelTargets = listOf("all"),
                purposeTargets = purposeTargets,
            )
            onSave(payload)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
