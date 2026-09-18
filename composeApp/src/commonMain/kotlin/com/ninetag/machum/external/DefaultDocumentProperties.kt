package com.ninetag.machum.external

private val automaticDocumentPropertyKeys = setOf("id", "plot")

/**
 * Adds empty properties to a newly created document without replacing any existing frontmatter.
 * Types remain in workspace settings; an empty value is represented uniformly as `key:`.
 */
fun injectDefaultDocumentProperties(
    raw: String,
    defaultKeys: List<String>,
    managedKeys: Set<String>,
): String {
    val protected = automaticDocumentPropertyKeys + managedKeys
    val normalizedKeys = defaultKeys.map(String::trim).filter(String::isNotEmpty).distinct()
        .filterNot(protected::contains)
    if (normalizedKeys.isEmpty()) return raw
    val existing = parseDocumentProperties(raw).properties.mapTo(mutableSetOf(), DocumentProperty::key)
    return normalizedKeys.fold(raw) { updated, key ->
        if (key in existing) updated else when (val result = setDocumentProperty(updated, key, null)) {
            is DocumentPropertyResult.Success -> result.raw
            is DocumentPropertyResult.Failure -> error(result.message)
        }
    }
}
