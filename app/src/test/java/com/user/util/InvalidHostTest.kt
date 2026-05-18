package com.user.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvalidHostTest {
    @Test
    fun garbageHostReturnsInlineHostError() {
        val result = GatewaySettingsValidator.validate(
            host = "bad host/name",
            portText = "8000",
            token = "token",
            useHttps = false
        )

        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Enter a host name or IP only", (result as ValidationResult.Invalid).hostError)
    }

    @Test
    fun invalidPortReturnsInlinePortError() {
        val result = GatewaySettingsValidator.validate(
            host = "192.168.1.10",
            portText = "70000",
            token = "token",
            useHttps = false
        )

        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Use 1-65535", (result as ValidationResult.Invalid).portError)
    }

    @Test
    fun validSettingsNormalizeHostAndBuildBaseUrl() {
        val result = GatewaySettingsValidator.validate(
            host = "https://openclaw.local/",
            portText = "8443",
            token = "token",
            useHttps = true
        )

        assertTrue(result is ValidationResult.Valid)
        val settings = (result as ValidationResult.Valid).settings
        assertEquals("openclaw.local", settings.host)
        assertEquals("https://openclaw.local:8443", settings.baseUrl)
    }
}
