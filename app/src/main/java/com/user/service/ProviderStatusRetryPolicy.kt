package com.user.service

object ProviderStatusRetryPolicy {
    fun shouldRetry(error: Throwable): Boolean =
        error !is GatewayProviderStatusException.Auth
}
