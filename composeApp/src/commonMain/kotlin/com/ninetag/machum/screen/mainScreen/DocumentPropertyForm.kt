package com.ninetag.machum.screen.mainScreen

import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import com.ninetag.machum.external.*

internal data class PreparedPropertyChange(
    val expected: NoteFile,
    val updated: NoteFile,
    val raw: String,
    val name: String,
    val removesRow: Boolean,
)

internal class PropertyDraft(val id: Int, property: DocumentProperty?) {
    var original by mutableStateOf(property)
    var name by mutableStateOf(property?.key.orEmpty())
    var type by mutableStateOf(property?.type ?: DocumentPropertyType.TEXT)
    var text by mutableStateOf(TextFieldValue(property?.value.scalarText()))
    var items by mutableStateOf((property?.value as? DocumentPropertyValue.ListValue)?.items.orEmpty())
    var checked by mutableStateOf((property?.value as? DocumentPropertyValue.BooleanValue)?.value ?: false)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var dirty by mutableStateOf(false)
    var pendingDelete by mutableStateOf(false)
    var nameEditing = false
    val list: Boolean get() = type == DocumentPropertyType.LIST || type == DocumentPropertyType.TAGS
    fun markEdited() {
        pendingDelete = false
        dirty = true
    }
    fun commitInput() {
        if (!list || text.composition != null) return
        val value = text.text.trim()
        if (value.isNotEmpty() && items.none { it.display() == value }) items = items + DocumentPropertyListItem.Text(value)
        text = TextFieldValue()
    }
    fun value(): DocumentPropertyValue = when (type) {
        DocumentPropertyType.LIST, DocumentPropertyType.TAGS -> DocumentPropertyValue.ListValue(items)
        DocumentPropertyType.NUMBER -> DocumentPropertyValue.NumberValue(text.text)
        DocumentPropertyType.BOOLEAN -> DocumentPropertyValue.BooleanValue(checked)
        DocumentPropertyType.DATE -> DocumentPropertyValue.DateValue(text.text)
        DocumentPropertyType.DATE_TIME -> DocumentPropertyValue.DateTimeValue(text.text)
        else -> DocumentPropertyValue.Text(text.text)
    }
    fun hasValue(): Boolean = if (type == DocumentPropertyType.BOOLEAN) true else items.isNotEmpty() || text.text.isNotEmpty()
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
    ): PreparedPropertyChange? {
        val deleting = explicitDelete || row.pendingDelete
        if (row.saving || (!row.dirty && !deleting)) return null
        if (deleting && row.original == null) {
            rows.remove(row)
            ensureEditableRow()
            return null
        }
        if (!deleting) row.commitInput()
        if (!deleting && row.nameEditing && row.name.trim() != row.original?.key) return null
        val expected = note
        val currentRaw = expected.inject()
        val current = parseDocumentProperties(currentRaw).properties
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
        if (original != null && current.firstOrNull { it.key == original.key } != original) {
            row.error = "속성이 변경되었습니다. 파일의 최신 값을 확인해 주세요."
            return null
        }
        val name = row.name.trim()
        if (!deleting && original != null && original.key in protectedKeys && name != original.key) {
            row.error = "이 속성 이름은 변경하거나 삭제할 수 없습니다."
            return null
        }
        if (!deleting && name.isEmpty() && row.hasValue()) { row.error = "값이 있는 속성에는 이름이 필요합니다."; return null }
        if (!deleting && name != original?.key && current.any { it.key == name }) { row.error = "이미 있는 속성 이름입니다."; return null }
        if (!deleting && (DocumentPropertyProtectionPolicy.isAutomaticKey(name) || DocumentPropertyProtectionPolicy.isAutomaticKey(original?.key))) { row.error = "자동 관리 속성은 변경할 수 없습니다."; return null }
        if (!deleting && (protection.isManagedSourceKey(name) || protection.isManagedSourceKey(original?.key)) && row.type != DocumentPropertyType.TEXT) {
            row.error = "source는 텍스트 값 하나만 사용합니다."; return null
        }
        if (!deleting && original != null && name == original.key && row.type == original.type && row.value() == original.value) {
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
            if (!accept(setDocumentProperty(nextRaw, name, row.value()))) return null
        }
        if (nextRaw == currentRaw) { row.dirty = false; row.error = null; return null }
        val updated = NoteFile.parse(nextRaw)
        row.saving = true
        row.error = null
        return PreparedPropertyChange(expected, updated, nextRaw, name, deleting || name.isEmpty())
    }

    fun completeSave(row: PropertyDraft, change: PreparedPropertyChange, error: String?) {
        try {
            row.error = error
            if (error == null) {
                row.dirty = false
                row.pendingDelete = false
                if (change.removesRow) {
                    rows.remove(row)
                    ensureEditableRow()
                } else {
                    row.original = parseDocumentProperties(change.raw).properties.firstOrNull { it.key == change.name }
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
    is DocumentPropertyValue.NumberValue -> value
    is DocumentPropertyValue.DateValue -> value
    is DocumentPropertyValue.DateTimeValue -> value
    else -> ""
}
