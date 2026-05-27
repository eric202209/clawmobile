package com.user.ui.tools

import android.os.Bundle
import android.widget.ArrayAdapter
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.user.R
import com.user.databinding.ActivityToolsBinding
import com.user.util.JsonFormatter
import com.user.util.PromptFormatter
import com.user.util.TokenEstimator
import com.user.util.YamlValidator

class ToolsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityToolsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.tools_title)

        setupTokenEstimator()
        setupJsonFormatter()
        setupYamlValidator()
        setupPromptFormatter()
    }

    private fun setupTokenEstimator() {
        val models = TokenEstimator.Model.values()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            models.map { it.displayName }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.tokenModelSpinner.adapter = adapter

        binding.tokenEstimateButton.setOnClickListener {
            val text = binding.tokenInput.text?.toString() ?: ""
            val model = models[binding.tokenModelSpinner.selectedItemPosition]
            val count = TokenEstimator.estimate(text, model)
            binding.tokenResult.text = getString(R.string.tool_token_result, count, model.displayName)
            binding.tokenResult.setTextColor(getColor(R.color.text_primary))
        }
    }

    private fun setupJsonFormatter() {
        binding.jsonFormatButton.setOnClickListener {
            val input = binding.jsonInput.text?.toString() ?: ""
            when (val result = JsonFormatter.format(input)) {
                is JsonFormatter.Result.Success -> {
                    binding.jsonResult.text = result.formatted
                    binding.jsonResult.setTextColor(getColor(R.color.status_connected))
                }
                is JsonFormatter.Result.Failure -> {
                    binding.jsonResult.text = result.message
                    binding.jsonResult.setTextColor(getColor(R.color.status_failed))
                }
            }
        }
    }

    private fun setupYamlValidator() {
        binding.yamlValidateButton.setOnClickListener {
            val input = binding.yamlInput.text?.toString() ?: ""
            when (val result = YamlValidator.validate(input)) {
                is YamlValidator.Result.Valid -> {
                    binding.yamlResult.text = getString(R.string.tool_yaml_valid)
                    binding.yamlResult.setTextColor(getColor(R.color.status_connected))
                }
                is YamlValidator.Result.Invalid -> {
                    binding.yamlResult.text = result.message
                    binding.yamlResult.setTextColor(getColor(R.color.status_failed))
                }
            }
        }
    }

    private fun setupPromptFormatter() {
        binding.promptFormatButton.setOnClickListener {
            val input = binding.promptInput.text?.toString() ?: ""
            val formatted = PromptFormatter.format(input)
            val count = TokenEstimator.estimate(formatted)
            binding.promptResult.text = getString(R.string.tool_prompt_result, count)
            binding.promptResult.setTextColor(getColor(R.color.text_primary))
            binding.promptOutput.setText(formatted)
            binding.promptOutputLayout.visibility = View.VISIBLE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
