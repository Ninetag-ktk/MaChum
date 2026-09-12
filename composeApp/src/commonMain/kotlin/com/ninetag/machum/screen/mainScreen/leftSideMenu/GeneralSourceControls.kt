package com.ninetag.machum.screen.mainScreen.leftSideMenu

import com.ninetag.machum.screen.common.DrawerDropdownMenu
import com.ninetag.machum.screen.common.WorkspaceDisclosure

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.*
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import com.ninetag.machum.theme.WorkspaceUiMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One drawer-owned surface retains failed operation plans for an explicit retry. */
@Composable
internal fun GeneralSourceControls(
    state: GeneralSourceState,
    currentFile: ProjectFile?,
    onFileSelected: (ProjectFile) -> Unit,
    onPlanRename: suspend (String, String) -> GeneralSourcePlan,
    onPlanDelete: suspend (String) -> GeneralSourcePlan,
    onPlanAssign: suspend (ProjectFile, String) -> GeneralSourcePlan,
    onApply: suspend (GeneralSourcePlan) -> String?,
    collapsedGroups: Set<String?>,
    onToggleGroup: (String?) -> Unit,
    creationInProgress: Boolean,
    onCreateFile: (String?) -> Unit,
    onFileTrashRequested: (ProjectFile) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<GeneralSourcePlan?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    val targets = remember { mutableMapOf<String, Rect>() }
    var hover by remember { mutableStateOf<String?>(null) }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(currentFile?.key) {
        if (!busy && !confirmation && !applied) renaming = null
    }
    fun execute(request: GeneralSourcePlan) {
        if (busy) return
        plan = request
        applied = true
        busy = true
        error = null
        scope.launch {
            try {
                error = onApply(request)
                if (error == null) { plan = null; confirmation = false; renaming = null; applied = false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "구분 적용에 실패했습니다." }
            finally { busy = false }
        }
    }
    fun prepare(action: suspend () -> GeneralSourcePlan, confirm: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val request = action()
                plan = request
                applied = false
                busy = false
                if (confirm || request.mergeRequired) confirmation = true else execute(request)
            } catch (cancelled: CancellationException) { busy = false; throw cancelled }
            catch (failure: Exception) { busy = false; error = failure.message ?: "구분 작업을 준비하지 못했습니다." }
        }
    }
    val hasInlineRename = renaming != null && renaming in state.groups
    Column(Modifier.fillMaxWidth()) {
        if (busy && !hasInlineRename) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("구분 적용 중…", Modifier.padding(8.dp))
        }
        error?.takeIf { !hasInlineRename }?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!confirmation && plan != null) TextButton(enabled = !busy, onClick = { plan?.let(::execute) }) { Text("남은 적용 다시 시도") }
        }
        val groups: List<String?> = state.groups.sorted() + listOf(null)
        groups.forEach { group ->
            val entries = state.files.filter { if (group == null) it.value.isNullOrEmpty() else it.value == group }
            key(group) {
                Column(Modifier.fillMaxWidth().then(if (group == null) Modifier else Modifier.onGloballyPositioned { targets[group] = it.boundsInRoot() })) {
                    GeneralSourceTreeRow(
                        name = group ?: "미분류", expanded = group !in collapsedGroups,
                        hasChildren = entries.isNotEmpty(),
                        highlighted = hover == group && group != null,
                        selected = entries.any { it.file.key == currentFile?.key },
                        createEnabled = !busy && !creationInProgress,
                        onToggle = { onToggleGroup(group) }, onCreate = { onCreateFile(group) },
                        onRename = group?.let { { renaming = group; nameDraft = group; error = null; plan = null; applied = false } },
                        onDelete = group?.let { { prepare({ onPlanDelete(group) }, true) } },
                        menuEnabled = !busy && !(applied && plan != null),
                        nameEditor = if (renaming == group && group != null) {{
                            HierarchyInlineNameEditor(
                                value = nameDraft, onValueChange = { nameDraft = it; error = null },
                                fieldLabel = "구분 이름", busy = busy || confirmation,
                                errorMessage = error.takeUnless { confirmation },
                                onCancel = { renaming = null },
                                onSubmit = {
                                    val retry = plan?.takeIf { applied }
                                    if (retry != null) execute(retry)
                                    else if (nameDraft.trim() == group) renaming = null
                                    else prepare({ onPlanRename(group, nameDraft) }, false)
                                },
                                textStyle = WorkspaceUiMetrics.labelTextStyle,
                                backEnabled = !confirmation,
                                inputReadOnly = busy || confirmation || (applied && plan != null),
                                submitLabel = if (applied && plan != null) "남은 적용 다시 시도" else "이름 변경 저장",
                            )
                        }} else null,
                    )
                    WorkspaceDisclosure(expanded = group !in collapsedGroups) {
                    Column { entries.forEach { entry ->
                        var moveMenu by remember(entry.file.key) { mutableStateOf(false) }
                        var bounds by remember(entry.file.key) { mutableStateOf(Rect.Zero) }
                        var position by remember(entry.file.key) { mutableStateOf(Offset.Zero) }
                        val drag = Modifier.pointerInput(entry.file.key, busy) {
                            if (!busy) detectDragGestures(
                                onDragStart = { position = bounds.topLeft + it },
                                onDragCancel = { hover = null },
                                onDragEnd = {
                                    val target = hover
                                    hover = null
                                    if (target != null && target != entry.value && target in latestState.groups) prepare({ onPlanAssign(entry.file, target) }, false)
                                },
                                onDrag = { change, amount ->
                                    change.consume(); position += amount
                                    hover = targets.entries.firstOrNull { it.key in latestState.groups && it.value.contains(position) }?.key
                                },
                            )
                        }
                        HierarchyDocumentRow(
                            label = entry.file.key.relativePath.removeSuffix(".md"), depth = 1,
                            groupChild = true,
                            selected = currentFile?.key == entry.file.key,
                            onClick = { onFileSelected(entry.file) },
                            onTrashRequested = if (busy || (applied && plan != null)) null else { { onFileTrashRequested(entry.file) } },
                            dragModifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }.then(drag),
                            dragDescription = "${entry.file.key.fileName} 구분 이동 핸들", supportingText = entry.error,
                            trailingAction = {
                                if (currentFile?.key == entry.file.key) Box {
                                    HierarchyIconButton(
                                        contentDescription = "${entry.file.key.fileName} 구분 변경",
                                        imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                        onClick = { moveMenu = true },
                                        enabled = !busy,
                                    )
                                    DrawerDropdownMenu(moveMenu, { moveMenu = false }, PopupUiMetrics.MenuWidth) {
                                        state.groups.sorted().forEach { target ->
                                            DropdownMenuItem(text = { Text(target) }, enabled = target != entry.value,
                                                onClick = { moveMenu = false; prepare({ onPlanAssign(entry.file, target) }, false) })
                                        }
                                    }
                                } else Spacer(Modifier.size(WorkspaceUiMetrics.hierarchyFolderRowHeight))
                            },
                        )
                    } }
                    }
                }
            }
        }
    }
    if (confirmation) plan?.let { request ->
        val deletion = request.kind == GeneralSourceOperation.DELETE
        val action = if (deletion) "구분 삭제" else "병합"
        PolicyDialog(
            onDismissRequest = { if (!busy) { confirmation = false; if (!applied) { plan = null; error = null } } },
            title = action,
            confirmButton = { Button(enabled = !busy, onClick = { execute(request) }, colors = if (deletion) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text(if (busy) "적용 중…" else action) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = false; if (!applied) { plan = null; error = null } }) { Text(if (applied) "닫기" else "취소") } },
        ) {
            Text(if (deletion) "${request.oldName}의 문서 ${request.files.size}개에서 source 값을 비웁니다. 파일은 유지하며 문서는 미분류로 전환됩니다." else "${request.oldName}의 문서 ${request.files.size}개를 ${request.targetName} 구분에 합칩니다. 해당 파일의 source만 변경합니다.")
            if (deletion && state.groups.size == 1) Text("마지막 구분을 삭제하면 구분 기능이 종료되고 새 문서에 source를 자동 추가하지 않습니다.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Header actions are siblings: creation never toggles the group's expansion. */
@Composable
internal fun GeneralSourceTreeRow(
    name: String,
    expanded: Boolean,
    hasChildren: Boolean,
    highlighted: Boolean,
    selected: Boolean,
    createEnabled: Boolean,
    onToggle: () -> Unit,
    onCreate: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    menuEnabled: Boolean,
    nameEditor: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val showMenu = { if (menuEnabled && onRename != null) menu = true }
    Box {
        HierarchyGroupRow(
            label = name, depth = 0, expanded = expanded, onToggle = onToggle,
            hasChildren = hasChildren,
            onCreate = onCreate, createEnabled = createEnabled, highlighted = highlighted,
            selected = selected,
            onContextMenu = if (onRename != null && menuEnabled && nameEditor == null) showMenu else null,
            nameEditor = nameEditor,
        )
        DrawerDropdownMenu(menu, { menu = false }, PopupUiMetrics.MenuWidth) {
            onRename?.let { rename -> DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menu = false; rename() }, leadingIcon = { Icon(Icons.Default.Edit, null) }) }
            onDelete?.let { delete ->
                HorizontalDivider()
                DropdownMenuItem(text = { Text("구분 삭제", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
            }
        }
    }
}
