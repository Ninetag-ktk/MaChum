package com.ninetag.machum.entity

/** A persisted change to the property defaults of the document's current scope. */
data class DocumentPropertyDefinitionChange(
    val previousKey: String?,
    val key: String?,
    val type: DocumentPropertyType?,
) {
    fun normalized(): DocumentPropertyDefinitionChange = copy(
        previousKey = previousKey?.trim()?.takeIf(String::isNotEmpty),
        key = key?.trim()?.takeIf(String::isNotEmpty),
    )
}

/** Workspace-wide types paired with one scope's default keys. */
data class DocumentPropertyDefaults(
    val propertyTypes: Map<String, DocumentPropertyType>,
    val defaultPropertyKeys: List<String>,
) {
    /**
     * Applies an idempotent document definition change.
     *
     * Renames replace an existing default key, additions append, and deletions remove only from the
     * current scope. Old type entries remain available for existing documents in other scopes.
     */
    fun apply(change: DocumentPropertyDefinitionChange): DocumentPropertyDefaults {
        val normalized = change.normalized()
        val previousKey = normalized.previousKey
        val key = normalized.key
        require(previousKey != null || key != null) { "property definition change is empty" }
        // A quoted legacy YAML key can be read even though the app would not create that key today.
        // Allow removing or renaming it; only a key that will remain in settings needs validation.
        require(key == null || documentPropertyKeyError(key) == null) {
            "property definition contains an unsupported YAML key"
        }
        require(normalized.type != DocumentPropertyType.UNSUPPORTED) { "unsupported property type cannot be saved" }

        val keys = when {
            previousKey == null && key != null -> defaultPropertyKeys + key
            previousKey != null && key == null -> defaultPropertyKeys - previousKey
            previousKey != null && key != null && previousKey != key -> {
                val previousIndex = defaultPropertyKeys.indexOf(previousKey)
                val withoutPrevious = defaultPropertyKeys.filterNot { it == previousKey }
                when {
                    key in withoutPrevious -> withoutPrevious
                    previousIndex >= 0 -> withoutPrevious.toMutableList().apply {
                        add(previousIndex.coerceAtMost(size), key)
                    }
                    else -> withoutPrevious + key
                }
            }
            else -> defaultPropertyKeys
        }.map(String::trim).filter(String::isNotEmpty).distinct()

        val types = if (key != null && normalized.type != null) {
            propertyTypes + (key to normalized.type)
        } else propertyTypes

        return DocumentPropertyDefaults(types, keys)
    }
}

fun ProjectConfig.validateDocumentPropertyDefinitions(): ProjectConfig = apply {
    require(propertyTypes.keys.all { it.isNotBlank() && it == it.trim() }) {
        "property type keys must be non-blank and trimmed"
    }
    require(propertyTypes.values.none { it == DocumentPropertyType.UNSUPPORTED }) {
        "unsupported property type cannot be persisted"
    }
    val defaultKeys = folders.values.flatMap(FolderConfig::defaultPropertyKeys)
    require((propertyTypes.keys + defaultKeys).all { documentPropertyKeyError(it) == null }) {
        "property settings contain an unsupported YAML key"
    }
}

fun ProjectConfig.documentPropertyDefaults(relativePath: String): DocumentPropertyDefaults {
    val folder = folders[relativePath]
        ?: if (relativePath == BASE_FOLDER_PATH) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()
    return DocumentPropertyDefaults(propertyTypes, folder.defaultPropertyKeys)
}

fun ProjectConfig.applyDocumentPropertyDefinition(
    relativePath: String,
    change: DocumentPropertyDefinitionChange,
): ProjectConfig {
    val defaults = documentPropertyDefaults(relativePath).apply(change)
    val currentFolder = folders[relativePath]
        ?: if (relativePath == BASE_FOLDER_PATH) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()
    return copy(
        folders = folders + (relativePath to currentFolder.copy(defaultPropertyKeys = defaults.defaultPropertyKeys).normalized()),
        propertyTypes = defaults.propertyTypes,
    )
}

fun ProjectConfig.applyDocumentPropertyDefinitions(
    relativePath: String,
    changes: List<DocumentPropertyDefinitionChange>,
): ProjectConfig = changes.fold(this) { config, change ->
    config.applyDocumentPropertyDefinition(relativePath, change)
}
