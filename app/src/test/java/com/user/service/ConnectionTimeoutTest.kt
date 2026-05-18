package com.user.service

import com.user.data.BackendSettings
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class ConnectionTimeoutTest {
    @Test
    fun nonResponsiveHostTimesOutAndReturnsFailure() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()

        try {
            val settings = BackendSettings(
                host = server.hostName,
                port = server.port,
                token = "token",
                useHttps = false
            )
            var result: Result<Unit>? = null
            val elapsedMs = measureTimeMillis {
                result = GatewayHealthChecker(timeoutSeconds = 1).check(settings, "token")
            }

            assertTrue(result?.isFailure == true)
            assertTrue("Expected timeout within 10s, took ${elapsedMs}ms", elapsedMs < 10_000)
        } finally {
            server.shutdown()
        }
    }
}
