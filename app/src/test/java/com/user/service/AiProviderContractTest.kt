package com.user.service

import com.user.data.ChatMessage
import com.user.data.MessageStatus
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderContractTest {

    private fun providerFor(server: MockWebServer): OpenClawProvider = OpenClawProvider(
        getBaseUrl = { "http://${server.hostName}:${server.port}" },
        getToken = { "test-token" }
    )

    @Test
    fun healthCheckReturnsFailureOnUnreachableHost() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val result = providerFor(server).healthCheck()
            assertTrue("Expected failure on non-responsive host", result.isFailure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun healthCheckReturnsFailureOnHttpError() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        server.start()
        try {
            val result = providerFor(server).healthCheck()
            assertTrue("Expected failure on HTTP 401", result.isFailure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendMessageNeverThrowsOnNetworkError() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.start()
        val history = listOf(
            ChatMessage(sessionId = "s1", message = "hi", isUser = true, status = MessageStatus.SENT)
        )
        try {
            val result = providerFor(server).sendMessage("test message", history)
            assertTrue("Expected Result.failure, not an exception", result.isFailure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendMessageReturnsSuccessOnValidResponse() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"response":"Hello from server"}""")
        )
        server.start()
        try {
            val result = providerFor(server).sendMessage("hello", emptyList())
            assertTrue("Expected success", result.isSuccess)
            assertTrue(result.getOrNull() == "Hello from server")
        } finally {
            server.shutdown()
        }
    }
}
