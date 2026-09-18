package com.ninetag.machum.screen.mainScreen

import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import com.ninetag.machum.entity.DocumentPropertyDefinitionChange
import com.ninetag.machum.entity.DocumentPropertyType
import com.ninetag.machum.external.*

internal data class PreparedPropertyChange(
    val expected: NoteFile,
    val updated: NoteFile,
    val raw: String,
    val name: String,
    val removesRow: Boolean,
    val definitionChange: DocumentPropertyDefinitionChange?,
)

internal class PropertyDraft(val id: Int, property: DocumentProperty?) {
    var original by mutableStateOf(property)
    var name by mutableStateOf(property?.key.orEmpty())
    var type by mutableStateOf(property?.type ?: DocumentPropertyType.TEXT)
    private val initialValue = property?.value
    var text by mutableStateOf(
        TextFieldValue(
            if (type in setOf(DocumentPropertyType.LIST, DocumentPropertyType.TAGS)) ""
            else initialValue.scalarText(),
        ),
    )
    var items by mutableStateOf(
        if (type in setOf(DocumentPropertyType.LIST, DocumentPropertyType.TAGS)) {
            (initialValue as? DocumentPropertyValue.ListValue)?.items
                ?: initialValue.scalarText().takeIf(String::isNotEmpty)
                    ?.let { listOf(DocumentPropertyListItem.Text(it)) }
                    .orEmpty()
        } else {
            (initialValue as? DocumentPropertyValue.ListValue)?.items.orEmpty()
        },
    )
    var checked by mutableStateOf((initialValue as? DocumentPropertyValue.BooleanValue)?.value ?: false)
    var booleanHasValue by mutableStateOf(initialValue is DocumentPropertyValue.BooleanValue)
    var booleanTextMode by mutableStateOf(type == DocumentPropertyType.BOOLEAN && property?.hasTypeMismatch == true)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var dirty by mutableStateOf(false)
    var valueEdited by mutableStateOf(false)
    var pendingDelete by mutableStateOf(false)
    var nameEditing = false
    val list: Boolean get() = type == DocumentPropertyType.LIST || type == DocumentPropertyType.TAGS
    val hasTypeMismatch: Boolean
        get() = original?.sourceType?.let { it != type && it != DocumentPropertyType.UNSUPPORTED } == true
    fun markEdited() {
        pendingDelete = false
        dirty = true
    }
    fun markValueEdited() {
        valueEdited = true
        markEdited()
    }
    fun commitInput() {
        if (!list || text.composition != null) return
        val value = text.text.trim()
        if (value.isNotEmpty() && items.none { it.display() == value }) items = items + DocumentPropertyListItem.Text(value)
        text = TextFieldValue()
    }
    fun value(): DocumentPropertyValue? = when (type) {
        DocumentPropertyType.LIST, DocumentPropertyType.TAGS -> DocumentPropertyValue.ListValue(items)
        DocumentPropertyType.NUMBER -> DocumentPropertyValue.NumberValue(text.text)
        DocumentPropertyType.BOOLEAN -> if (booleanTextMode) {
            text.text.trim().takeIf(String::isNotEmpty)?.equals("true", ignoreCase = true)
                ?.let { DocumentPropertyValue.BooleanValue(it) }
        } else {
            checked.takeIf { booleanHasValue }?.let { DocumentPropertyValue.BooleanValue(it) }
        }
        DocumentPropertyType.DATE -> DocumentPropertyValue.DateValue(text.text)
        DocumentPropertyType.DATE_TIME -> DocumentPropertyValue.DateTimeValue(text.text)
        else -> DocumentPropertyValue.Text(text.text)
    }
    fun hasValue(): Boolean = if (!valueEdited && original != null) {
        original?.value.hasContent()
    } else when {
        type == DocumentPropertyType.BOOLEAN && booleanTextMode -> text.text.isNotEmpty()
        type == DocumentPropertyType.BOOLEAN -> booleanHasValue
        list -> items.isNotEmpty() || text.text.isNotEmpty()
        else -> text.text.isNotEmpty()
    }
}
internal class PropertyForm {
    val rows = mutableStateListOf<PropertyDraft>()
    var nextId = 0
    fun reconcile(properties: List<DocumentProperty>) {
        rows.removeAll { row ->
            row.original != null && !row.dirty && !row.saving && !row.pendingDelete &&
                properties.none { it.key == row.original?.key }
        }
        properties.forEach { p ->
            val row = rows.firstOrNull { it.original?.key == p.key || (it.saving && it.name.trim() == p.key) }
            if (row == null) rows.add(PropertyDraft(nextId++, p))
            else if (!row.dirty && !row.saving && !row.pendingDelete && row.original != p) {
                val index = rows.indexOf(row)
                rows[index] = PropertyDraft(row.id, p)
            }
        }
        ensureEditableRow()
    }
    fun ensureEditableRow() {
        if (rows.none { it.original == null || (it.original?.readOnly == false && !DocumentPropertyProtectionPolicy.isAutomaticKey(it.original?.key)) }) {
            rows.add(PropertyDraft(nextId++, null))
        }
    }

    /** Prepares a row change without IO; validation and no-ops preserve the draft. */
    fun prepareSave(
        row: PropertyDraft,
        note: NoteFile,
        protection: DocumentPropertyProtectionPolicy,
        protectedKeys: Set<String>,
        explicitDelete: Boolean = false,
        typeHints: Map<String, DocumentPropertyType> = emptyMap(),
    ): PreparedPropertyChange? {
        val deleting = explicitDelete || row.pendingDelete
        if (row.saving || (!row.dirty && !deleting)) return null
        if (deleting && row.original == null) {
            rows.remove(row)
            ensureEditableRow()
            return null
        }
        if (!deleting && row.name.trim() == "aliases" && row.type != DocumentPropertyType.LIST) {
            val scalar = if (row.list) row.items.firstOrNull()?.display().orEmpty() else row.text.text
            row.type = DocumentPropertyType.LIST
            row.items = scalar.takeIf(String::isNotEmpty)?.let { listOf(DocumentPropertyListItem.Text(it)) }.orEmpty()
            row.text = TextFieldValue()
        }
        if (!deleting) row.commitInput()
        if (!deleting && row.nameEditing && row.name.trim() != row.original?.key) return null
        val expected = note
        val currentRaw = expected.inject()
        val current = parseDocumentProperties(currentRaw, typeHints).properties
        val original = row.original
        if (deleting && original != null && (
            original.readOnly || original.key in protectedKeys || DocumentPropertyProtectionPolicy.isAutomaticKey(original.key) ||
                protection.isManagedTagsKey(original.key)
        )) {
            row.error = "이 속성은 삭제할 수 없습니다."
            row.pendingDelete = false
            return null
        }
        if (deleting) row.pendingDelete = true
        val currentOriginal = original?.let { saved ->
            current.firstOrNull { it.key == saved.key }
                ?.copy(type = saved.type, typeFromSettings = saved.typeFromSettings)
        }
        if (original != null && currentOriginal != original) {
            row.error = "속성이 변경되었습니다. 파일의 최신 값을 확인해 주세요."
            return null
        }
        val name = row.name.trim()
        if (!deleting && original != null && original.key in protectedKeys && name != original.key) {
            row.error = "이 속성 이름은 변경하거나 삭제할 수 없습니다."
            return null
        }
        if (!deleting && name in protectedKeys && name != original?.key) {
            row.error = "이 이름은 작업 공간에서 관리하는 속성에 예약되어 있습니다."
            return null
        }
        if (!deleting && name.isEmpty() && row.hasValue()) { row.error = "값이 있는 속성에는 이름이 필요합니다."; return null }
        if (!deleting && name != original?.key && current.any { it.key == name }) { row.error = "이미 있는 속성 이름입니다."; return null }
        if (!deleting && (DocumentPropertyProtectionPolicy.isAutomaticKey(name) || DocumentPropertyProtectionPolicy.isAutomaticKey(original?.key))) { row.error = "자동 관리 속성은 변경할 수 없습니다."; return null }
        if (!deleting && (protection.isManagedSourceKey(name) || protection.isManagedSourceKey(original?.key)) && row.type != DocumentPropertyType.TEXT) {
            row.error = "source는 텍스트 값 하나만 사용합니다."; return null
        }
        if (!deleting && row.type == DocumentPropertyType.BOOLEAN && row.booleanTextMode && row.valueEdited) {
            val booleanText = row.text.text.trim()
            if (booleanText.isNotEmpty() && !booleanText.equals("true", true) && !booleanText.equals("false", true)) {
                row.error = "true 또는 false 값만 체크박스로 저장할 수 있습니다."
                return null
            }
        }
        if (!deleting && original != null && name == original.key && row.type == original.type &&
            (!row.valueEdited || row.value() == original.value)
        ) {
            row.dirty = false
            row.error = null
            return null
        }
        var nextRaw = currentRaw
        fun accept(result: DocumentPropertyResult): Boolean = when (result) {
            is DocumentPropertyResult.Success -> { nextRaw = result.raw; true }
            is DocumentPropertyResult.Failure -> { row.error = result.message; false }
        }
        if (deleting && original != null) {
            if (!accept(deleteDocumentProperty(nextRaw, original.key))) return null
        } else if (name.isEmpty()) {
            if (original == null) { row.dirty = false; row.error = null; return null }
            if (!accept(deleteDocumentProperty(nextRaw, original.key))) return null
        } else {
            if (original != null && original.key != name && !accept(renameDocumentProperty(nextRaw, original.key, name))) return null
            if ((original == null || row.valueEdited) && !accept(setDocumentProperty(nextRaw, name, row.value()))) return null
        }
        val persistedType = if (!deleting && row.hasValue()) row.type else null
        val definitionAllowed = listOf(original?.key, name.takeIf { !deleting })
            .filterNotNull()
            .none { key ->
                key in protectedKeys || DocumentPropertyProtectionPolicy.isAutomaticKey(key) ||
                    protection.isManagedTagsKey(key) || protection.isManagedSourceKey(key)
            }
        val definitionChange = when {
            !definitionAllowed -> null
            deleting && original != null -> DocumentPropertyDefinitionChange(original.key, null, null)
            !deleting && original == null && name.isNotEmpty() -> DocumentPropertyDefinitionChange(null, name, persistedType)
            !deleting && original != null && original.key != name ->
                DocumentPropertyDefinitionChange(original.key, name, persistedType)
            !deleting && original != null && persistedType != null &&
                (row.type != original.type || !original.typeFromSettings) ->
                DocumentPropertyDefinitionChange(name, name, persistedType)
            else -> null
        }
        if (nextRaw == currentRaw && definitionChange == null) { row.dirty = false; row.error = null; return null }
        val updated = NoteFile.parse(nextRaw)
        row.saving = true
        row.error = null
        return PreparedPropertyChange(
            expected = expected,
            updated = updated,
            raw = nextRaw,
            name = name,
            removesRow = deleting || name.isEmpty(),
            definitionChange = definitionChange,
        )
    }

    fun completeSave(row: PropertyDraft, change: PreparedPropertyChange, error: String?) {
        try {
            row.error = error
            if (error == null) {
                row.dirty = false
                row.valueEdited = false
                row.pendingDelete = false
                if (change.removesRow) {
                    rows.remove(row)
                    ensureEditableRow()
                } else {
                    row.original = parseDocumentProperties(change.raw).properties.firstOrNull { it.key == change.name }
                        ?.copy(type = row.type)
                    if (row.type == DocumentPropertyType.BOOLEAN && row.original?.value is DocumentPropertyValue.BooleanValue) {
                        row.booleanTextMode = false
                        row.checked = (row.original?.value as DocumentPropertyValue.BooleanValue).value
                        row.booleanHasValue = true
                    }
                }
            }
        } finally { row.saving = false }
    }

    fun cancelSave(row: PropertyDraft) {
        row.saving = false
    }
}

internal fun DocumentPropertyListItem.display(): String = when (this) {
    is DocumentPropertyListItem.Text -> value
    is DocumentPropertyListItem.NumberValue -> value
}
private fun DocumentPropertyValue?.scalarText(): String = when (this) {
    is DocumentPropertyValue.Text -> value
    is DocumentPropertyValue.BooleanValue -> value.toString()
    is DocumentPropertyValue.NumberValue -> value
    is DocumentPropertyValue.DateValue -> value
    is DocumentPropertyValue.DateTimeValue -> value
    is DocumentPropertyValue.ListValue -> items.joinToString(", ") { it.display() }
    else -> ""
}

private fun DocumentPropertyValue?.hasContent(): Boolean = when (this) {
    is DocumentPropertyValue.BooleanValue -> true
    is DocumentPropertyValue.ListValue -> items.isNotEmpty()
    is DocumentPropertyValue.Text -> value.isNotEmpty()
    is DocumentPropertyValue.NumberValue -> value.isNotEmpty()
    is DocumentPropertyValue.DateValue -> value.isNotEmpty()
    is DocumentPropertyValue.DateTimeValue -> value.isNotEmpty()
    null -> false
}
