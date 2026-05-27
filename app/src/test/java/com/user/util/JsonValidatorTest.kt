package com.user.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonValidatorTest {

    @Test
    fun validObjectFormatsSuccessfully() {
        val result = JsonFormatter.format("""{"key":"value","num":42}""")
        assertTrue(result is JsonFormatter.Result.Success)
        val formatted = (result as JsonFormatter.Result.Success).formatted
        assertTrue(formatted.contains("\"key\""))
        assertTrue(formatted.contains("\"value\""))
    }

    @Test
    fun validArrayFormatsSuccessfully() {
        val result = JsonFormatter.format("""[1,2,3]""")
        assertTrue(result is JsonFormatter.Result.Success)
    }

    @Test
    fun invalidJsonReturnsFailureWithMessage() {
        val result = JsonFormatter.format("""{bad json here""")
        assertTrue("Expected failure for invalid JSON", result is JsonFormatter.Result.Failure)
        val msg = (result as JsonFormatter.Result.Failure).message
        assertTrue("Message should mention JSON", msg.contains("JSON", ignoreCase = true))
        assertTrue("Message should include an error position", msg.contains("position"))
    }

    @Test
    fun emptyInputReturnsFailureWithClearMessage() {
        val result = JsonFormatter.format("")
        assertTrue(result is JsonFormatter.Result.Failure)
        assertEquals("Input is empty", (result as JsonFormatter.Result.Failure).message)
    }

    @Test
    fun trailingContentReturnsFailure() {
        val result = JsonFormatter.format("""{"key":"value"} trailing""")
        assertTrue(result is JsonFormatter.Result.Failure)
    }

    @Test
    fun blankInputReturnsFailure() {
        val result = JsonFormatter.format("   ")
        assertTrue(result is JsonFormatter.Result.Failure)
    }
}
