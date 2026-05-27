package com.user.util

import kotlin.math.ceil

object TokenEstimator {
    enum class Model(val displayName: String, val multiplier: Float) {
        GPT4("GPT-4", 1.0f),
        CLAUDE("Claude", 1.05f),
        LLAMA("Llama", 1.10f)
    }

    fun estimate(text: String, model: Model = Model.GPT4): Int {
        if (text.isEmpty()) return 0
        return ceil(text.length / 4.0 * model.multiplier).toInt()
    }
}
