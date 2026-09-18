package com.ninetag.machum.entity

fun documentPropertyKeyError(key: String): String? = when {
    key.isEmpty() -> "Property key cannot be empty"
    key != key.trim() -> "Property key cannot start or end with whitespace"
    key.any { it == ':' || it == '#' || it == '\r' || it == '\n' } -> "Property key contains unsupported characters"
    key.first() in "-?:,[]{}&*!|>'\"%@`" -> "Property key starts with an unsupported YAML indicator"
    else -> null
}
