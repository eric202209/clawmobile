package com.user.util

object PromptFormatter {
    fun format(input: String): String = input
        .lines()
        .map { it.trimEnd() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
