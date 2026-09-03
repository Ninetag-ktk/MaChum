package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.CommitChangeKind
import com.ninetag.machum.commit.CommitHistoryEntry
import com.ninetag.machum.commit.LineDiffKind

@Composable
internal fun CommitDialog(
    state: CommitUiState,
    onDismissRequest: () -> Unit,
    onCommit: (String) -> Unit,
    onDiffRequest: (String?, CommitChange) -> Unit,
    onDiffDismiss: () -> Unit,
    onRestoreRequest: (CommitHistoryEntry) -> Unit,
    onRestoreConfirm: () -> Unit,
    onRestoreDismiss: () -> Unit,
) {
    state.diff?.let { diff ->
        CommitFileDiffDialog(diff, onDiffDismiss)
        return
    }
    state.restore?.let { restore ->
        CommitRestoreDialog(restore, onRestoreConfirm, onRestoreDismiss)
        return
    }
    var message by remember(state.preview?.parentCommitId) { mutableStateOf("") }
    var historySelected by remember(state.preview?.parentCommitId) { mutableStateOf(false) }
    val busy = state.isLoading || state.isCommitting
    val canCommit = state.preview?.hasChanges == true && message.isNotBlank() && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = "프로젝트 커밋",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.isLoading -> LoadingRow("변경 사항을 확인하는 중…")
                    state.preview != null -> {
                        if (state.history.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(
                                    onClick = { historySelected = false },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        "현재 변경 (${state.preview.changes.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (!historySelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                                TextButton(
                                    onClick = { historySelected = true },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        "이력 (${state.history.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (historySelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        if (historySelected) {
                            CommitHistory(
                                history = state.history,
                                onDiffRequest = onDiffRequest,
                                headCommitId = state.preview.parentCommitId,
                                canRestore = !state.preview.hasChanges,
                                onRestoreRequest = onRestoreRequest,
                            )
                        } else {
                            CurrentChanges(
                                state = state,
                                message = message,
                                onMessageChange = { message = it },
                                enabled = !busy,
                                onDiffRequest = { change -> onDiffRequest(null, change) },
                            )
                        }
                    }
                }

                if (state.isCommitting) LoadingRow("커밋을 저장하는 중…")
                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (!historySelected && state.preview?.hasChanges == true) {
                TextButton(
                    enabled = canCommit,
                    onClick = { onCommit(message.trim()) },
                ) {
                    Text(if (state.isCommitting) "커밋 중…" else "커밋")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !busy) {
                Text(if (state.preview?.hasChanges == false || historySelected || state.errorMessage != null) "닫기" else "취소")
            }
        },
    )
}

@Composable
private fun CurrentChanges(
    state: CommitUiState,
    message: String,
    onMessageChange: (String) -> Unit,
    enabled: Boolean,
    onDiffRequest: (CommitChange) -> Unit,
) {
    val preview = state.preview ?: return
    if (!preview.hasChanges) {
        Text(
            text = "커밋할 변경 사항이 없습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        text = "변경된 파일 ${preview.changes.size}개",
        style = MaterialTheme.typography.labelLarge,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        preview.changes.forEach { change ->
            CommitChangeRow(change, onClick = { onDiffRequest(change) })
        }
    }
    OutlinedTextField(
        value = message,
        onValueChange = onMessageChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("커밋 메시지") },
        placeholder = { Text("이번 변경 내용을 요약해 주세요") },
        shape = RoundedCornerShape(6.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        enabled = enabled,
        minLines = 2,
        maxLines = 4,
    )
}

@Composable
private fun CommitHistory(
    history: List<CommitHistoryEntry>,
    onDiffRequest: (String, CommitChange) -> Unit,
    headCommitId: String?,
    canRestore: Boolean,
    onRestoreRequest: (CommitHistoryEntry) -> Unit,
) {
    var expandedId by remember(history.firstOrNull()?.commit?.id) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        history.forEach { entry ->
            val expanded = expandedId == entry.commit.id
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable {
                        expandedId = if (expanded) null else entry.commit.id
                    }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = entry.commit.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${entry.commit.id.take(8)} · 변경 ${entry.changes.size}개",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded) {
                    Column(
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        entry.changes.forEach { change ->
                            CommitChangeRow(
                                change = change,
                                onClick = { onDiffRequest(entry.commit.id, change) },
                            )
                        }
                        if (entry.commit.id != headCommitId) {
                            TextButton(
                                enabled = canRestore,
                                onClick = { onRestoreRequest(entry) },
                            ) {
                                Text("이 상태로 복구")
                            }
                            if (!canRestore) {
                                Text(
                                    text = "현재 변경 사항을 먼저 커밋해야 복구할 수 있습니다.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitFileDiffDialog(
    state: CommitDiffUiState,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = state.displayPath,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    state.isLoading -> LoadingRow("줄별 diff를 계산하는 중…")
                    state.errorMessage != null -> Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.result != null -> {
                        val result = state.result
                        if (result.lines.isEmpty()) {
                            Text(
                                text = "파일 내용은 변경되지 않았습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 480.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    result.lines.forEach { line ->
                                        val prefix = when (line.kind) {
                                            LineDiffKind.ADDED -> "+"
                                            LineDiffKind.DELETED -> "-"
                                            LineDiffKind.CONTEXT -> " "
                                            LineDiffKind.OMITTED -> "…"
                                        }
                                        val background = when (line.kind) {
                                            LineDiffKind.ADDED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                            LineDiffKind.DELETED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                        val oldNumber = line.oldLineNumber?.toString()?.padStart(4) ?: "    "
                                        val newNumber = line.newLineNumber?.toString()?.padStart(4) ?: "    "
                                        Text(
                                            text = "$oldNumber $newNumber $prefix ${line.text}",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(background)
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = when (line.kind) {
                                                LineDiffKind.OMITTED -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (result.isApproximate) {
                            Text(
                                text = "문서가 커서 공통 앞뒤 구간을 제외한 부분은 전체 교체로 표시했습니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (result.isTruncated) {
                            Text(
                                text = "표시 가능한 diff 줄 수를 초과해 나머지를 생략했습니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !state.isLoading) {
                Text("뒤로")
            }
        },
    )
}

@Composable
private fun CommitRestoreDialog(
    state: CommitRestoreUiState,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.isRestoring) onDismissRequest() },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = "이 커밋 상태로 복구할까요?",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = state.entry.commit.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${state.entry.commit.id.take(8)} · 파일 ${state.entry.changes.size}개 변경",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "프로젝트 파일을 이 시점의 상태로 되돌립니다. 기존 커밋 이력과 HEAD는 유지되며, " +
                        "복구 결과는 새 변경 사항으로 표시됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.isRestoring) LoadingRow("프로젝트를 복구하는 중…")
                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.isRestoring) {
                Text(if (state.isRestoring) "복구 중…" else "복구")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !state.isRestoring) {
                Text("취소")
            }
        },
    )
}

@Composable
private fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CommitChangeRow(
    change: CommitChange,
    onClick: (() -> Unit)? = null,
) {
    val (label, color) = when (change.kind) {
        CommitChangeKind.ADDED -> "추가" to MaterialTheme.colorScheme.primary
        CommitChangeKind.MODIFIED -> "수정" to MaterialTheme.colorScheme.tertiary
        CommitChangeKind.DELETED -> "삭제" to MaterialTheme.colorScheme.error
        CommitChangeKind.RENAMED -> "이름 변경" to MaterialTheme.colorScheme.secondary
        CommitChangeKind.RENAMED_AND_MODIFIED -> "이름 변경·수정" to MaterialTheme.colorScheme.tertiary
    }
    val interactionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = change.displayPath,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (change.addedLines > 0 || change.deletedLines > 0) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "+${change.addedLines}  -${change.deletedLines}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (change.oldPath != null && change.newPath != null && change.oldPath != change.newPath) {
            Text(
                text = "${change.oldPath} → ${change.newPath}",
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
