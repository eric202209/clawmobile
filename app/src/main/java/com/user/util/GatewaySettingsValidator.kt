package com.user.util

import com.user.data.BackendSettings

object GatewaySettingsValidator {
    fun validate(host: String, portText: String, token: String, useHttps: Boolean): ValidationResult {
        val cleanHost = host
            .trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        val port = portText.trim().toIntOrNull()

        return when {
            cleanHost.isBlank() -> ValidationResult.Invalid(hostError = "Host is required")
            cleanHost.any { it.isWhitespace() } ||
                cleanHost.contains("/") ||
                cleanHost.contains(":") -> ValidationResult.Invalid(hostError = "Enter a host name or IP only")
            port == null || port !in 1..65535 -> ValidationResult.Invalid(portError = "Use 1-65535")
            token.isBlank() -> ValidationResult.Invalid(tokenError = "Gateway Token cannot be empty")
            else -> ValidationResult.Valid(
                BackendSettings(
                    host = cleanHost,
                    port = port,
                    token = token.trim(),
                    useHttps = useHttps
                )
            )
        }
    }
}

sealed class ValidationResult {
    data class Valid(val settings: BackendSettings) : ValidationResult()
    data class Invalid(
        val hostError: String? = null,
        val portError: String? = null,
        val tokenError: String? = null
    ) : ValidationResult()
}
