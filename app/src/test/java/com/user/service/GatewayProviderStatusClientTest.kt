package com.user.service

import com.user.data.BackendSettings
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProviderStatusClientTest {
    private val client = GatewayProviderStatusClient(nowMillis = { CHECKED_AT })

    @Test
    fun parseProvidersFromGatewayResponse() {
        val providers = client.parse(
            """
            {
              "providers": [
                {
                  "id": "openai",
                  "type": "llm",
                  "displayName": "OpenAI",
                  "status": "healthy",
                  "activeModel": "gpt-4.1",
                  "lastLatencyMs": 123
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, providers.size)
        val provider = providers.first()
        assertEquals("openai", provider.id)
        assertEquals("llm", provider.type)
        assertEquals("OpenAI", provider.displayName)
        assertEquals("healthy", provider.status)
        assertEquals("gpt-4.1", provider.activeModel)
        assertEquals(123L, provider.lastLatencyMs)
        assertEquals(CHECKED_AT, provider.lastCheckedAt)
    }

    @Test
    fun parseNestedProviderResponse() {
        val providers = client.parse(
            """
            {
              "data": {
                "providerStatuses": [
                  {
                    "provider_id": "anthropic",
                    "provider_type": "llm",
                    "display_name": "Anthropic",
                    "health": "ready",
                    "model_name": "claude-sonnet",
                    "latency_ms": "87"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals("anthropic", providers.first().id)
        assertEquals("ready", providers.first().status)
        assertEquals(87L, providers.first().lastLatencyMs)
    }

    @Test
    fun providerApiKeysAreNotPersisted() {
        val providers = client.parse(
            """
            {
              "providers": [
                {
                  "id": "openrouter",
                  "type": "llm",
                  "displayName": "OpenRouter",
                  "status": "healthy",
                  "activeModel": "server-selected",
                  "apiKey": "sk-secret",
                  "openaiApiKey": "sk-openai-secret"
                }
              ]
            }
            """.trimIndent()
        )

        val persistedFields = providers.first().toString()
        assertFalse(persistedFields.contains("sk-secret"))
        assertFalse(persistedFields.contains("sk-openai-secret"))
        assertFalse(persistedFields.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun malformedResponseReturnsMalformedFailure() {
        val result = runCatching { client.parse("""{"gateway":"ok"}""") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GatewayProviderStatusException.Malformed)
    }

    @Test
    fun fetchReturnsAuthFailureOnUnauthorizedGateway() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        server.start()
        try {
            val result = GatewayProviderStatusClient(timeoutSeconds = 1).fetch(
                settings = BackendSettings(
                    host = server.hostName,
                    port = server.port,
                    token = "gateway-token",
                    useHttps = false
                ),
                token = "gateway-token"
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is GatewayProviderStatusException.Auth)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetchReturnsFailureOnNetworkError() = runTest {
        val result = GatewayProviderStatusClient(timeoutSeconds = 1).fetch(
            settings = BackendSettings(
                host = "127.0.0.1",
                port = 9,
                token = "gateway-token",
                useHttps = false
            ),
            token = "gateway-token"
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun fetchReadsProvidersStatusEndpoint() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"providers":[{"id":"gateway","type":"gateway","displayName":"Gateway","status":"healthy"}]}""")
        )
        server.start()
        try {
            val result = GatewayProviderStatusClient(timeoutSeconds = 1, nowMillis = { CHECKED_AT }).fetch(
                settings = BackendSettings(
                    host = server.hostName,
                    port = server.port,
                    token = "gateway-token",
                    useHttps = false
                ),
                token = "gateway-token"
            )

            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("/providers/status", request.path)
            assertEquals("Bearer gateway-token", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    companion object {
        private const val CHECKED_AT = 1_700_000_000_000L
    }
}
