package com.user.util

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

object JsonFormatter {
    sealed class Result {
        data class Success(val formatted: String) : Result()
        data class Failure(val message: String) : Result()
    }

    fun format(input: String): Result {
        if (input.isBlank()) return Result.Failure("Input is empty")
        val trimmed = input.trim()
        return try {
            val tokener = JSONTokener(trimmed)
            val value = tokener.nextValue()
            val trailing = tokener.nextClean()
            if (trailing.code != 0) {
                return Result.Failure(
                    "Invalid JSON at position ${trimmed.length}: unexpected trailing content"
                )
            }
            when (value) {
                is JSONObject -> Result.Success(value.toString(2))
                is JSONArray -> Result.Success(value.toString(2))
                else -> Result.Failure("Invalid JSON at position 1: expected an object or array")
            }
        } catch (e: JSONException) {
            Result.Failure("Invalid JSON at position ${extractPosition(e)}: ${e.message}")
        }
    }

    private fun extractPosition(error: JSONException): Int {
        val message = error.message.orEmpty()
        val patterns = listOf(
            Regex("character\\s+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("position\\s+(\\d+)", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 1
    }
}
