package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.PlotFileEntry
import com.ninetag.machum.external.PlotOrderAssignment
import kotlinx.coroutines.launch

@Composable
internal fun PlotOrderEditorDialog(
    entries: List<PlotFileEntry>,
    onDismissRequest: () -> Unit,
    onSave: suspend (List<PlotOrderAssignment>) -> Boolean,
) {
    val draft = remember(entries) {
        entries.map { entry ->
            PlotOrderDraftItem(
                fileKey = entry.projectFile.key,
                title = entry.title,
                stage = entry.stage,
            )
        }.toMutableStateList()
    }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun move(item: PlotOrderDraftItem, direction: Int) {
        val stage = item.stage
        if (stage == null) {
            if (direction < 0) changeStage(draft, item, PlotStage.EPILOGUE)
            return
        }
        val group = draft.filter { it.stage == stage }
        val groupIndex = group.indexOfFirst { it.fileKey == item.fileKey }
        val targetIndex = groupIndex + direction
        if (targetIndex in group.indices) {
            val first = draft.indexOfFirst { it.fileKey == item.fileKey }
            val second = draft.indexOfFirst { it.fileKey == group[targetIndex].fileKey }
            val temporary = draft[first]
            draft[first] = draft[second]
            draft[second] = temporary
            return
        }

        val adjacentStage = PlotStage.entries.getOrNull(stage.ordinal + direction) ?: return
        draft.removeAll { it.fileKey == item.fileKey }
        val moved = item.copy(stage = adjacentStage)
        val adjacentIndices = draft.indices.filter { draft[it].stage == adjacentStage }
        val insertionIndex = when {
            adjacentIndices.isEmpty() -> draft.size
            direction > 0 -> adjacentIndices.first()
            else -> adjacentIndices.last() + 1
        }
        draft.add(insertionIndex, moved)
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        title = { Text("플롯 순서 편집") },
        text = {
            Column {
                Text(
                    text = "드래그해서 순서를 바꾸거나 다른 단계로 이동할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                    PlotStage.entries.forEach { stage ->
                        item(key = "stage-${stage.code}") {
                            PlotStageHeader(stage.frontmatterValue)
                        }
                        items(
                            items = draft.filter { it.stage == stage },
                            key = { it.fileKey.relativePath },
                        ) { item ->
                            PlotOrderFileRow(
                                item = item,
                                onMove = { direction -> move(item, direction) },
                                onStageChange = { target -> changeStage(draft, item, target) },
                            )
                        }
                    }

                    val unclassified = draft.filter { it.stage == null }
                    if (unclassified.isNotEmpty()) {
                        item(key = "stage-unclassified") {
                            PlotStageHeader("미분류")
                        }
                        items(
                            items = unclassified,
                            key = { it.fileKey.relativePath },
                        ) { item ->
                            PlotOrderFileRow(
                                item = item,
                                onMove = { direction -> move(item, direction) },
                                onStageChange = { target -> changeStage(draft, item, target) },
                            )
                        }
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    val assignments = PlotStage.entries.flatMap { stage ->
                        draft.filter { it.stage == stage }.mapIndexed { index, item ->
                            PlotOrderAssignment(item.fileKey, stage, index)
                        }
                    }
                    scope.launch {
                        isSaving = true
                        errorMessage = null
                        if (onSave(assignments)) {
                            onDismissRequest()
                        } else {
                            errorMessage = "순서를 저장하지 못했습니다. 파일 상태를 확인해 주세요."
                        }
                        isSaving = false
                    }
                },
            ) {
                Text(if (isSaving) "저장 중…" else "저장")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismissRequest,
            ) {
                Text("취소")
            }
        },
    )
}

@Composable
private fun PlotStageHeader(label: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PlotOrderFileRow(
    item: PlotOrderDraftItem,
    onMove: (Int) -> Unit,
    onStageChange: (PlotStage) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val currentOnMove by rememberUpdatedState(onMove)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "순서 드래그",
            modifier = Modifier
                .padding(8.dp)
                .pointerInput(item.fileKey) {
                    val threshold = 36.dp.toPx()
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragDistance = 0f },
                        onDragEnd = { dragDistance = 0f },
                        onDragCancel = { dragDistance = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            dragDistance += amount.y
                            while (dragDistance >= threshold) {
                                currentOnMove(1)
                                dragDistance -= threshold
                            }
                            while (dragDistance <= -threshold) {
                                currentOnMove(-1)
                                dragDistance += threshold
                            }
                        },
                    )
                },
        )
        Text(
            text = item.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "파일 순서 메뉴")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("위로") },
                    leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onMove(-1)
                    },
                )
                DropdownMenuItem(
                    text = { Text("아래로") },
                    leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onMove(1)
                    },
                )
                HorizontalDivider()
                PlotStage.entries.forEach { stage ->
                    DropdownMenuItem(
                        text = { Text(stage.frontmatterValue) },
                        enabled = item.stage != stage,
                        onClick = {
                            menuExpanded = false
                            onStageChange(stage)
                        },
                    )
                }
            }
        }
    }
}

private fun changeStage(
    draft: MutableList<PlotOrderDraftItem>,
    item: PlotOrderDraftItem,
    stage: PlotStage,
) {
    draft.removeAll { it.fileKey == item.fileKey }
    draft.add(item.copy(stage = stage))
}

private data class PlotOrderDraftItem(
    val fileKey: FileKey,
    val title: String,
    val stage: PlotStage?,
)
