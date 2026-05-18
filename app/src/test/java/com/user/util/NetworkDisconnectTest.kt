package com.user.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDisconnectTest {
    @Test
    fun offlineStateIsNonRetryableUiError() {
        val state = ChatErrorMapper.offline()

        assertEquals("No network connection", state.message)
        assertFalse(state.retryable)
    }

    @Test
    fun requestFailureStateIsRetryableUiError() {
        val state = ChatErrorMapper.requestFailure("socket closed")

        assertEquals("socket closed", state.message)
        assertTrue(state.retryable)
    }
}
