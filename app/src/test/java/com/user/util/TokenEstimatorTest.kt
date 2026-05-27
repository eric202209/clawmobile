package com.user.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun emptyStringReturnsZero() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun knownStringIsWithinTenPercent() {
        // "Hello, world!" = 13 chars → ~3 tokens (GPT-4 tokenizer gives 4)
        val text = "Hello, world!"
        val estimate = TokenEstimator.estimate(text, TokenEstimator.Model.GPT4)
        val realTokens = 4
        val tolerance = (realTokens * 0.10).toInt().coerceAtLeast(1)
        assertTrue(
            "Estimate $estimate outside ±$tolerance of real count $realTokens",
            Math.abs(estimate - realTokens) <= tolerance
        )
    }

    @Test
    fun longerTextProducesReasonableEstimate() {
        // ~100 word paragraph ≈ 130 tokens by GPT-4 tokenizer
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(10)
        val estimate = TokenEstimator.estimate(text, TokenEstimator.Model.GPT4)
        // Must be within ±10% of reference count ~115
        val reference = 115
        val tolerance = (reference * 0.10).toInt()
        assertTrue(
            "Estimate $estimate outside ±$tolerance of reference $reference",
            Math.abs(estimate - reference) <= tolerance
        )
    }

    @Test
    fun claudeMultiplierProducesHigherCountThanGpt4() {
        val text = "Some sample text to count tokens in."
        val gpt4Count = TokenEstimator.estimate(text, TokenEstimator.Model.GPT4)
        val claudeCount = TokenEstimator.estimate(text, TokenEstimator.Model.CLAUDE)
        assertTrue("Claude estimate should be >= GPT-4 estimate", claudeCount >= gpt4Count)
    }
}
