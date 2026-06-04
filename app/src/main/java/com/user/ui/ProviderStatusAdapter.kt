package com.user.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.user.R
import com.user.data.GatewayProviderStatus
import com.user.databinding.ItemProviderStatusBinding
import java.util.concurrent.TimeUnit

class ProviderStatusAdapter :
    ListAdapter<GatewayProviderStatus, ProviderStatusAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemProviderStatusBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GatewayProviderStatus) {
            binding.providerName.text = item.displayName
            binding.providerType.text = item.type
            binding.providerModel.text = item.activeModel
                ?: binding.root.context.getString(R.string.provider_status_model_unknown)
            binding.providerLatency.text = item.lastLatencyMs?.let {
                binding.root.context.getString(R.string.provider_status_latency_ms, it)
            } ?: binding.root.context.getString(R.string.provider_status_latency_unknown)
            binding.providerChecked.text = formatCheckedAt(item.lastCheckedAt)
            binding.statusStrip.setBackgroundColor(
                ContextCompat.getColor(binding.root.context, resolveStatusColor(item.status))
            )
        }

        private fun resolveStatusColor(status: String): Int = when (status.lowercase()) {
            "healthy", "active", "available", "connected", "online", "ready" ->
                R.color.status_connected
            "degraded", "slow", "warning" -> R.color.status_pending
            "error", "failed", "unavailable", "offline" -> R.color.status_failed
            else -> R.color.status_disconnected
        }

        private fun formatCheckedAt(checkedAt: Long): String {
            val context = binding.root.context
            val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - checkedAt)
            return when {
                minutes < 1 -> context.getString(R.string.provider_status_checked_now)
                minutes == 1L -> context.getString(R.string.provider_status_checked_one_minute)
                else -> context.getString(R.string.provider_status_checked_minutes, minutes)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProviderStatusBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<GatewayProviderStatus>() {
        override fun areItemsTheSame(a: GatewayProviderStatus, b: GatewayProviderStatus) =
            a.id == b.id
        override fun areContentsTheSame(a: GatewayProviderStatus, b: GatewayProviderStatus) =
            a == b
    }
}
