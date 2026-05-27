package com.user.util

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

object YamlValidator {
    sealed class Result {
        object Valid : Result()
        data class Invalid(val message: String) : Result()
    }

    fun validate(input: String): Result {
        if (input.isBlank()) return Result.Invalid("Input is empty")
        return try {
            Yaml().load<Any>(input)
            Result.Valid
        } catch (e: YAMLException) {
            Result.Invalid("Invalid YAML: ${e.message}")
        }
    }
}
