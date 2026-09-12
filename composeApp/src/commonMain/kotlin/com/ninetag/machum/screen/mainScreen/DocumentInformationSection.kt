package com.ninetag.machum.screen.mainScreen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.*
import com.ninetag.machum.screen.common.MotionDropdownMenu
import com.ninetag.machum.screen.common.PolicyVerticalScrollbar
import com.ninetag.machum.screen.common.WorkspaceDisclosure
import com.ninetag.machum.theme.WorkspaceUiMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class PropertyDraft(val id: Int, property: DocumentProperty?) {
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
private class PropertyForm {
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
}

/** Drafts belong to the file; changing selection must never move an errored edit to another file. */
@Composable
internal fun DocumentInformationSection(
    modifier: Modifier = Modifier,
    workspaceIdentity: String,
    file: ProjectFile?,
    note: NoteFile?,
    expanded: Boolean,
    managedTags: Map<String, String>,
    sourceIsManaged: Boolean,
    protectedKeys: Set<String> = emptySet(),
    onSave: suspend (FileKey, NoteFile, NoteFile) -> String?,
    maxExpandedHeight: Dp = 260.dp,
) {
    val forms = remember { mutableMapOf<Pair<String, FileKey>, PropertyForm>() }
    val scope = rememberCoroutineScope()
    if (file == null || note == null) return
    val protection = DocumentPropertyProtectionPolicy(managedTags.keys, sourceIsManaged)
    val raw = note.inject()
    val parsed = remember(raw) { parseDocumentProperties(raw) }
    val form = forms.getOrPut(workspaceIdentity to file.key) { PropertyForm() }
    LaunchedEffect(workspaceIdentity, file.key, raw) { form.reconcile(parsed.properties) }
    val fileKey = file.key
    fun submit(row: PropertyDraft, explicitDelete: Boolean = false) {
        val deleting = explicitDelete || row.pendingDelete
        if (row.saving || (!row.dirty && !deleting)) return
        if (deleting && row.original == null) {
            form.rows.remove(row)
            form.ensureEditableRow()
            return
        }
        if (!deleting) row.commitInput()
        if (!deleting && row.nameEditing && row.name.trim() != row.original?.key) return
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
            return
        }
        if (deleting) row.pendingDelete = true
        if (original != null && current.firstOrNull { it.key == original.key } != original) {
            row.error = "속성이 변경되었습니다. 파일의 최신 값을 확인해 주세요."
            return
        }
        val name = row.name.trim()
        if (!deleting && original != null && original.key in protectedKeys && name != original.key) {
            row.error = "이 속성 이름은 변경하거나 삭제할 수 없습니다."
            return
        }
        if (!deleting && name.isEmpty() && row.hasValue()) { row.error = "값이 있는 속성에는 이름이 필요합니다."; return }
        if (!deleting && name != original?.key && current.any { it.key == name }) { row.error = "이미 있는 속성 이름입니다."; return }
        if (!deleting && (DocumentPropertyProtectionPolicy.isAutomaticKey(name) || DocumentPropertyProtectionPolicy.isAutomaticKey(original?.key))) { row.error = "자동 관리 속성은 변경할 수 없습니다."; return }
        if (!deleting && (protection.isManagedSourceKey(name) || protection.isManagedSourceKey(original?.key)) && row.type != DocumentPropertyType.TEXT) {
            row.error = "source는 텍스트 값 하나만 사용합니다."; return
        }
        if (!deleting && original != null && name == original.key && row.type == original.type && row.value() == original.value) {
            row.dirty = false
            row.error = null
            return
        }
        var nextRaw = currentRaw
        fun accept(result: DocumentPropertyResult): Boolean = when (result) {
            is DocumentPropertyResult.Success -> { nextRaw = result.raw; true }
            is DocumentPropertyResult.Failure -> { row.error = result.message; false }
        }
        if (deleting && original != null) {
            if (!accept(deleteDocumentProperty(nextRaw, original.key))) return
        } else if (name.isEmpty()) {
            if (original == null) { row.dirty = false; row.error = null; return }
            if (!accept(deleteDocumentProperty(nextRaw, original.key))) return
        } else {
            if (original != null && original.key != name && !accept(renameDocumentProperty(nextRaw, original.key, name))) return
            if (!accept(setDocumentProperty(nextRaw, name, row.value()))) return
        }
        if (nextRaw == currentRaw) { row.dirty = false; row.error = null; return }
        val updated = NoteFile.parse(nextRaw)
        row.saving = true
        row.error = null
        scope.launch {
            try {
                val error = onSave(fileKey, expected, updated)
                row.error = error
                if (error == null) {
                    row.dirty = false
                    row.pendingDelete = false
                    if (deleting || name.isEmpty()) {
                        form.rows.remove(row)
                        form.ensureEditableRow()
                    }
                    else row.original = parseDocumentProperties(nextRaw).properties.firstOrNull { it.key == name }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { row.error = e.message ?: "속성을 저장하지 못했습니다." }
            finally { row.saving = false }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
    ) {
        key(workspaceIdentity, fileKey) {
            val propertyScrollState = rememberScrollState()
            WorkspaceDisclosure(expanded = expanded, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().heightIn(max = maxExpandedHeight)) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(propertyScrollState)
                            .padding(start = 8.dp, end = 14.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        parsed.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        form.rows.forEach { row -> key(workspaceIdentity, fileKey, row.id) {
                            val locked = row.original?.readOnly == true || DocumentPropertyProtectionPolicy.isAutomaticKey(row.original?.key)
                            val tagRow = row.name == "tags"
                            val rowManagedTags = managedTags.takeIf { tagRow }.orEmpty()
                            val keyLocked = row.original?.key in protectedKeys || protection.isManagedTagsKey(row.name)
                            PropertyRow(row, locked, keyLocked, rowManagedTags,
                                protection.isManagedSourceKey(row.name), parsed.error == null,
                                onSubmit = { submit(row) }, onDelete = { submit(row, explicitDelete = true) })
                        } }
                        TextButton(onClick = { form.rows.add(PropertyDraft(form.nextId++, null)) }) {
                            Icon(Icons.Default.Add, null); Text("속성 추가")
                        }
                    }
                    PolicyVerticalScrollbar(propertyScrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun PropertyRow(row: PropertyDraft, locked: Boolean, keyLocked: Boolean, managed: Map<String, String>, source: Boolean,
    canEdit: Boolean, onSubmit: () -> Unit, onDelete: () -> Unit) {
    var typesExpanded by remember { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    var valueFocused by remember { mutableStateOf(false) }
    val enabled = !locked && !row.saving && canEdit
    val rowHeight = WorkspaceUiMetrics.hierarchyFolderRowHeight
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box {
                val typeEnabled = enabled && !source && managed.isEmpty()
                val deleteEnabled = !row.saving && canEdit && !locked && !keyLocked
                val menuEnabled = typeEnabled || deleteEnabled
                Box(
                    Modifier.size(rowHeight).clickable(enabled = menuEnabled) { typesExpanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        row.type.icon(),
                        "속성 유형: ${row.type.label()}",
                        modifier = Modifier.size(WorkspaceUiMetrics.hierarchyIconSize),
                        tint = if (menuEnabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MotionDropdownMenu(
                    expanded = typesExpanded,
                    onDismissRequest = { typesExpanded = false },
                    modifier = Modifier.width(212.dp),
                ) {
                    if (typeEnabled) DocumentPropertyType.entries.filter { it != DocumentPropertyType.UNSUPPORTED && (it != DocumentPropertyType.TAGS || row.name == "tags") }
                        .forEach { type -> PropertyMenuItem(
                            text = type.label(),
                            icon = type.icon(),
                            selected = row.type == type,
                            onClick = {
                            typesExpanded = false
                            if (row.type == type) return@PropertyMenuItem
                            if (row.list && (type == DocumentPropertyType.LIST || type == DocumentPropertyType.TAGS)) {
                                row.type = type
                                row.markEdited()
                                if (row.original != null) onSubmit()
                                return@PropertyMenuItem
                            }
                            // Do not silently collapse a list into a scalar or erase an existing value.
                            if (row.list && row.items.size > 1 && type != DocumentPropertyType.LIST && type != DocumentPropertyType.TAGS) {
                                row.error = "여러 값은 이 유형으로 변경할 수 없습니다."
                            } else {
                                val scalar = if (row.list) row.items.firstOrNull()?.display().orEmpty() else row.text.text
                                if (type == DocumentPropertyType.BOOLEAN && scalar !in listOf("true", "false", "")) {
                                    row.error = "true 또는 false 값만 체크박스로 변경할 수 있습니다."
                                    return@PropertyMenuItem
                                }
                                if (row.type == DocumentPropertyType.BOOLEAN && type != DocumentPropertyType.BOOLEAN) {
                                    row.error = "체크박스의 유형 변경은 아직 지원하지 않습니다."
                                    return@PropertyMenuItem
                                }
                                if (type == DocumentPropertyType.BOOLEAN) row.checked = scalar == "true"
                                row.type = type
                                if (row.list) { row.items = scalar.takeIf { it.isNotEmpty() }?.let { listOf(DocumentPropertyListItem.Text(it)) }.orEmpty(); row.text = TextFieldValue() }
                                else { row.text = TextFieldValue(scalar); row.items = emptyList() }
                                row.markEdited()
                                if (row.original != null) onSubmit()
                            }
                        }) }
                    if (typeEnabled && deleteEnabled) HorizontalDivider()
                    if (deleteEnabled) {
                        PropertyMenuItem(
                            text = "속성 삭제",
                            icon = Icons.Default.Delete,
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                typesExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            val nameEnabled = enabled && !keyLocked
            val fieldErrorMessage = row.error ?: row.original?.error
            BasicTextField(
                value = row.name,
                onValueChange = { row.name = it; row.markEdited() },
                enabled = nameEnabled,
                singleLine = true,
                textStyle = WorkspaceUiMetrics.labelTextStyle.copy(
                    color = if (nameEnabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.widthIn(min = 72.dp, max = 160.dp).weight(.38f, fill = false)
                    .height(rowHeight).semantics {
                        contentDescription = "속성 이름"
                        fieldErrorMessage?.let { error(it) }
                    }.onFocusChanged {
                    row.nameEditing = it.isFocused
                    if (nameFocused && !it.isFocused) onSubmit()
                    nameFocused = it.isFocused
                },
                decorationBox = { innerTextField ->
                    CompactPropertyFieldDecoration(
                        empty = row.name.isEmpty(),
                        placeholder = "key",
                        focused = nameFocused,
                        error = fieldErrorMessage != null,
                        innerTextField = innerTextField,
                    )
                },
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                if (row.list) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.items.forEachIndexed { index, item ->
                            val label = item.display()
                            val managedOrigin = managed[label]
                            PropertyValueChip(
                                label = label,
                                managedOrigin = managedOrigin,
                                canDelete = enabled && managedOrigin == null,
                                onDelete = {
                                    row.items = row.items.filterIndexed { i, _ -> i != index }; row.markEdited(); onSubmit()
                                },
                            )
                        }
                        PropertyValueTextField(
                            row = row,
                            enabled = enabled,
                            valueFocused = valueFocused,
                            onValueFocusedChange = { valueFocused = it },
                            onSubmit = onSubmit,
                            managedValues = managed.keys,
                            modifier = Modifier.widthIn(min = 96.dp, max = 160.dp),
                        )
                    }
                } else if (row.type == DocumentPropertyType.BOOLEAN) {
                    Checkbox(
                        checked = row.checked,
                        onCheckedChange = if (enabled) ({ row.checked = it; row.markEdited(); onSubmit() }) else null,
                        modifier = Modifier.size(rowHeight),
                    )
                } else {
                    PropertyValueTextField(
                        row = row,
                        enabled = enabled,
                        valueFocused = valueFocused,
                        onValueFocusedChange = { valueFocused = it },
                        onSubmit = onSubmit,
                        managedValues = emptySet(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (row.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        (row.error ?: row.original?.error)?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = WorkspaceUiMetrics.secondaryTextStyle)
            if (!locked) TextButton(onClick = onSubmit, enabled = !row.saving) { Text("다시 시도") }
        }
    }
}

@Composable
private fun PropertyMenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    selected: Boolean? = null,
    contentColor: Color = LocalContentColor.current,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight)
            .background(
                if (selected == true) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f)
                else Color.Transparent,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected?.let { this.selected = it }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(WorkspaceUiMetrics.hierarchyIconSize), tint = contentColor)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), color = contentColor, style = WorkspaceUiMetrics.labelTextStyle)
        if (selected == true) {
            Icon(Icons.Default.Check, null, Modifier.size(WorkspaceUiMetrics.hierarchyIconSize), tint = contentColor)
        }
    }
}

@Composable
private fun PropertyValueChip(
    label: String,
    managedOrigin: String?,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    val chip: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides WorkspaceUiMetrics.hierarchyFolderRowHeight,
        ) {
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(label, style = WorkspaceUiMetrics.labelTextStyle) },
                shape = CircleShape,
                modifier = if (managedOrigin == null) Modifier else Modifier.semantics {
                    contentDescription = "$label, $managedOrigin, 읽기 전용"
                },
                colors = InputChipDefaults.inputChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    trailingIconColor = MaterialTheme.colorScheme.primary,
                ),
                border = null,
                trailingIcon = if (canDelete) ({
                    IconButton(onClick = onDelete, Modifier.size(WorkspaceUiMetrics.inlineChipActionSize)) {
                        Icon(Icons.Default.Close, "값 삭제: $label", Modifier.size(WorkspaceUiMetrics.hierarchySecondaryIconSize))
                    }
                }) else null,
            )
        }
    }
    if (managedOrigin == null) {
        chip()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text("$label · $managedOrigin · 읽기 전용") } },
            state = rememberTooltipState(),
        ) { chip() }
    }
}

@Composable
private fun PropertyValueTextField(
    row: PropertyDraft,
    enabled: Boolean,
    valueFocused: Boolean,
    onValueFocusedChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    managedValues: Set<String>,
    modifier: Modifier,
) {
    val placeholder = when (row.type) {
        DocumentPropertyType.LIST, DocumentPropertyType.TAGS -> ""
        DocumentPropertyType.DATE -> "YYYY-MM-DD"
        DocumentPropertyType.DATE_TIME -> "YYYY-MM-DDTHH:mm"
        else -> "value"
    }
    val fieldErrorMessage = row.error ?: row.original?.error
    BasicTextField(
        value = row.text,
        onValueChange = { row.text = it; row.markEdited() },
        enabled = enabled,
        singleLine = true,
        textStyle = WorkspaceUiMetrics.labelTextStyle.copy(
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.height(WorkspaceUiMetrics.hierarchyFolderRowHeight)
            .semantics {
                contentDescription = if (placeholder.isEmpty()) "속성 값" else "속성 값: $placeholder"
                fieldErrorMessage?.let { error(it) }
            }.onFocusChanged {
            if (valueFocused && !it.isFocused && row.text.composition == null) onSubmit()
            onValueFocusedChange(it.isFocused)
        }.onPreviewKeyEvent {
            if (
                it.type == KeyEventType.KeyDown && it.key == Key.Backspace && row.list &&
                row.text.text.isEmpty() && row.text.composition == null && enabled
            ) {
                val last = row.items.lastOrNull()
                if (last != null && last.display() !in managedValues) {
                    row.items = row.items.dropLast(1)
                    row.markEdited()
                    onSubmit()
                }
                true
            } else false
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = if (row.type == DocumentPropertyType.NUMBER) KeyboardType.Decimal else KeyboardType.Text,
        ),
        keyboardActions = KeyboardActions(onDone = { if (row.text.composition == null) onSubmit() }),
        decorationBox = { innerTextField ->
            CompactPropertyFieldDecoration(
                empty = row.text.text.isEmpty(),
                placeholder = placeholder,
                focused = valueFocused,
                error = fieldErrorMessage != null,
                innerTextField = innerTextField,
            )
        },
    )
}

@Composable
private fun CompactPropertyFieldDecoration(
    empty: Boolean,
    placeholder: String,
    focused: Boolean,
    error: Boolean,
    innerTextField: @Composable () -> Unit,
) {
    val indicatorColor = when {
        error -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawLine(
                color = indicatorColor,
                start = androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f),
                strokeWidth = (if (focused || error) 2.dp else 1.dp).toPx(),
            )
        }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (empty && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                style = WorkspaceUiMetrics.secondaryTextStyle,
            )
        }
        innerTextField()
    }
}
private fun DocumentPropertyListItem.display(): String = when (this) {
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
private fun DocumentPropertyType.label() = when (this) {
    DocumentPropertyType.TEXT -> "텍스트"
    DocumentPropertyType.LIST -> "목록"
    DocumentPropertyType.NUMBER -> "숫자"
    DocumentPropertyType.BOOLEAN -> "체크박스"
    DocumentPropertyType.DATE -> "날짜"
    DocumentPropertyType.DATE_TIME -> "날짜 및 시간"
    DocumentPropertyType.TAGS -> "태그"
    DocumentPropertyType.UNSUPPORTED -> "원본 보존 속성"
}
private fun DocumentPropertyType.icon() = when (this) {
    DocumentPropertyType.LIST -> Icons.AutoMirrored.Filled.List
    DocumentPropertyType.NUMBER -> Icons.Default.Numbers
    DocumentPropertyType.BOOLEAN -> Icons.Default.CheckBox
    DocumentPropertyType.DATE -> Icons.Default.CalendarMonth
    DocumentPropertyType.DATE_TIME -> Icons.Default.Schedule
    DocumentPropertyType.TAGS -> Icons.Default.Tag
    else -> Icons.AutoMirrored.Filled.Subject
}
