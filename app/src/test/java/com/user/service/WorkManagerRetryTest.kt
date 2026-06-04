package com.user.service

import com.user.data.BackendSettings
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagerRetryTest {

    @Test
    fun authFailure_isNotRetryable() {
        val error = GatewayProviderStatusException.Auth("401")
        assertFalse("Auth failures must not trigger retry", ProviderStatusRetryPolicy.shouldRetry(error))
    }

    @Test
    fun networkFailure_isRetryable() {
        val error = java.io.IOException("Connection refused")
        assertTrue("Network errors must trigger retry", ProviderStatusRetryPolicy.shouldRetry(error))
    }

    @Test
    fun malformedResponse_isRetryable() {
        val error = GatewayProviderStatusException.Malformed("Bad JSON")
        assertTrue("Malformed responses must trigger retry", ProviderStatusRetryPolicy.shouldRetry(error))
    }

    @Test
    fun workerClient_returnsAuthException_on401() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        server.start()
        try {
            val result = GatewayProviderStatusClient(timeoutSeconds = 1).fetch(
                settings = BackendSettings(
                    host = server.hostName,
                    port = server.port,
                    useHttps = false
                ),
                token = "bad-token"
            )
            assertTrue(result.isFailure)
            assertTrue(
                "Expect Auth exception for 401",
                result.exceptionOrNull() is GatewayProviderStatusException.Auth
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun workerClient_returnsNonAuthException_on500() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        server.start()
        try {
            val result = GatewayProviderStatusClient(timeoutSeconds = 1).fetch(
                settings = BackendSettings(
                    host = server.hostName,
                    port = server.port,
                    useHttps = false
                ),
                token = "token"
            )
            assertTrue(result.isFailure)
            assertFalse(
                "500 must not be an Auth exception",
                result.exceptionOrNull() is GatewayProviderStatusException.Auth
            )
        } finally {
            server.shutdown()
        }
    }
}
