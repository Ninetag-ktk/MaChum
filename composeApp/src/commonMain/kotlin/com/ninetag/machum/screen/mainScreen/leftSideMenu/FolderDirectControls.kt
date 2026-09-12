package com.ninetag.machum.screen.mainScreen.leftSideMenu

import com.ninetag.machum.screen.common.DrawerDropdownMenu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.normalizeTags
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import com.ninetag.machum.theme.WorkspaceUiMetrics
import com.ninetag.machum.theme.WorkspaceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Kept by the drawer, not the lazy row, so deselection never discards an in-flight/error draft. */
internal class FolderTagDraft(initial: List<String>) {
    var saved by mutableStateOf(initial)
        private set
    var tags by mutableStateOf(initial)
    var input by mutableStateOf(TextFieldValue())
    var applying by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val retained: Boolean get() = applying || error != null || tags != saved || input.text.isNotEmpty()
    fun sync(values: List<String>) {
        if (!applying && error == null && tags == saved && input.text.isEmpty()) {
            tags = values
            saved = values
        }
    }
    fun commitInput() {
        if (input.composition != null) return
        tags = normalizeTags(tags + input.text)
        input = TextFieldValue()
    }
    fun backspace(): Boolean {
        if (input.text.isNotEmpty() || input.composition != null || tags.isEmpty()) return false
        tags = tags.dropLast(1)
        return true
    }
    fun apply(scope: CoroutineScope, save: suspend (List<String>) -> Boolean) {
        if (applying) return
        commitInput()
        if (tags == saved && error == null) return
        val submitted = tags.toList()
        applying = true
        error = null
        scope.launch {
            try {
                if (save(submitted)) saved = submitted
                else error = "태그를 모두 적용하지 못했습니다. 초안을 유지했습니다. 다시 시도해 주세요."
            } catch (cancelled: CancellationException) {
                error = "태그 적용 완료를 확인하지 못했습니다. 다시 시도해 주세요."
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "태그 적용에 실패했습니다."
            } finally { applying = false }
        }
    }
}

internal enum class FolderPresentation(val label: String, val icon: ImageVector) {
    GENERAL("번호 없음", Icons.Default.Folder),
    DEFAULT("번호", Icons.Default.FormatListNumbered),
    PLOT("번호와 Plot", Icons.Default.AccountTree);
    fun apply(config: FolderConfig): FolderConfig = config.copy(
        type = if (this == GENERAL) FolderType.GENERAL else FolderType.DEFAULT,
        plotEnabled = this == PLOT,
    )
    companion object {
        fun of(config: FolderConfig): FolderPresentation = when {
            config.isPlot -> PLOT
            config.type == FolderType.DEFAULT -> DEFAULT
            else -> GENERAL
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderDirectControls(
    name: String,
    config: FolderConfig,
    draft: FolderTagDraft,
    scope: CoroutineScope,
    onSaveTags: suspend (List<String>) -> Boolean,
    onChangeType: suspend (FolderPresentation) -> Boolean,
) {
    LaunchedEffect(config.autoTags) { draft.sync(config.autoTags) }
    val enterAlpha = remember(name) { Animatable(0f) }
    LaunchedEffect(name) { enterAlpha.animateTo(1f, WorkspaceMotion.contentEnterSpec()) }
    var hadFocus by remember { mutableStateOf(false) }
    val latestSave by rememberUpdatedState(onSaveTags)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .graphicsLayer { alpha = enterAlpha.value },
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = when {
            draft.error != null -> BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            hadFocus -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            else -> null
        },
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    Modifier.weight(1f).onFocusChanged { focus ->
                        if (hadFocus && !focus.hasFocus) draft.apply(scope) { latestSave(it) }
                        hadFocus = focus.hasFocus
                    }.focusGroup().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        draft.tags.forEach { tag ->
                            FolderTagChip(
                                tag = tag,
                                enabled = !draft.applying,
                                onDelete = { draft.tags = draft.tags - tag },
                            )
                        }
                        BasicTextField(
                            value = draft.input,
                            onValueChange = { draft.input = it },
                            enabled = !draft.applying,
                            singleLine = true,
                            textStyle = WorkspaceUiMetrics.labelTextStyle.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface)),
                            modifier = Modifier.widthIn(min = 96.dp, max = 160.dp)
                                .heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight)
                                .testTag("folder-tag-input:$name").semantics {
                                    contentDescription = "폴더 자동 태그"
                                    draft.error?.let { error(it) }
                                }.onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown || draft.applying) false
                                else when (it.key) {
                                    Key.Enter -> if (draft.input.composition == null) { draft.commitInput(); true } else false
                                    Key.Backspace -> draft.backspace()
                                    else -> false
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { draft.commitInput() }),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { field ->
                                Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                                    if (draft.input.text.isEmpty()) {
                                        Text(
                                            if (draft.tags.isEmpty()) "폴더 자동 태그" else "태그 추가…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = WorkspaceUiMetrics.secondaryTextStyle,
                                        )
                                    }
                                    field()
                                }
                            },
                        )
                    }
                }
                Box(Modifier.padding(top = (WorkspaceUiMetrics.hierarchyToolbarHeight - WorkspaceUiMetrics.hierarchyActionSize) / 2)) {
                    FolderTypeAction(name, config, onChangeType)
                }
            }
            if (draft.applying) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("기존 문서에 태그 적용 중…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            draft.error?.let { error ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { draft.apply(scope) { latestSave(it) } }) { Text("다시 시도") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f))
        }
    }
}

@Composable
private fun FolderTagChip(tag: String, enabled: Boolean, onDelete: () -> Unit) {
    val actionSize = WorkspaceUiMetrics.hierarchyActionSize
    Box(
        Modifier.heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    CircleShape,
                )
                .testTag("folder-tag-chip:$tag"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tag,
                Modifier.weight(1f, fill = false)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = WorkspaceUiMetrics.labelTextStyle,
            )
            Spacer(Modifier.width(actionSize))
        }
        HierarchyIconButton(
            imageVector = Icons.Default.Close,
            contentDescription = "$tag 삭제",
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterEnd),
            iconModifier = Modifier.testTag("folder-tag-delete-icon:$tag"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTypeAction(name: String, config: FolderConfig, onChange: suspend (FolderPresentation) -> Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf<FolderPresentation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val current = FolderPresentation.of(config)
    val anchorSize = WorkspaceUiMetrics.hierarchyActionSize
    val popupWidth = PopupUiMetrics.RowMinHeight * FolderPresentation.entries.size + PopupUiMetrics.ItemSpacing
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(current.label) } },
            state = rememberTooltipState(),
        ) {
            HierarchyIconButton(
                imageVector = current.icon,
                contentDescription = "폴더 유형: ${current.label}",
                onClick = { expanded = true },
                iconModifier = Modifier.testTag("folder-type-icon:$name"),
            )
        }
        DrawerDropdownMenu(
            expanded,
            { expanded = false },
            popupWidth,
            offset = DpOffset(anchorSize - popupWidth, PopupUiMetrics.ItemSpacing),
        ) {
            Row {
                FolderPresentation.entries.forEach { option ->
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(option.label) } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = {
                            expanded = false
                            if (option != current) { requested = option; error = null }
                        }, modifier = Modifier.size(PopupUiMetrics.RowMinHeight)
                            .background(
                                if (option == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                MaterialTheme.shapes.small,
                            ).semantics { selected = option == current }) {
                            Icon(
                                option.icon,
                                option.label,
                                Modifier.size(WorkspaceUiMetrics.hierarchyIconSize),
                                tint = if (option == current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
    requested?.let { target ->
        PolicyDialog(
            onDismissRequest = { if (!busy) requested = null },
            title = "폴더 유형 변경",
            confirmButton = { Button(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            if (onChange(target)) requested = null
                            else error = "유형을 변경하지 못했습니다. 다시 시도해 주세요."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "유형을 변경하지 못했습니다."
                        } finally { busy = false }
                    }
                }
            }) { Text(if (busy) "변경 중…" else "변경") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { requested = null }) { Text("취소") } },
        ) {
            Text("$name: ${current.label} → ${target.label}")
            Text("폴더의 번호·Plot 표시 규칙을 변경합니다. 기존 파일명과 본문은 유지합니다.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
