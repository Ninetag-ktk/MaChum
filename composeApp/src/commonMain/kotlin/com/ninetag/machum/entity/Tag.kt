package com.ninetag.machum.entity

/** Obsidian 태그 항목으로 저장할 문자열을 정규화한다. */
fun normalizeTag(value: String): String = value
    .trim()
    .removePrefix("#")
    .trim()
    .replace(Regex("\\s+"), "_")

fun normalizeTags(values: List<String>): List<String> = values
    .map(::normalizeTag)
    .filter(String::isNotEmpty)
    .distinct()
