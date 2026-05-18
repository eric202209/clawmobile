package com.user.util

object ChatErrorMapper {
    fun offline(): UiState.Error =
        UiState.Error("No network connection", retryable = false)

    fun requestFailure(message: String?): UiState.Error =
        UiState.Error(message?.takeIf { it.isNotBlank() } ?: "Request failed")
}
