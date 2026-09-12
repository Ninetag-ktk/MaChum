package com.ninetag.machum.screen.commitScreen

import com.ninetag.machum.screen.mainScreen.CommitDiffUiState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.CommitFileSide
import com.ninetag.machum.commit.CommitHistoryEntry
import com.ninetag.machum.commit.CommitPreview
import kotlin.time.Instant

/**
 * 파일 복원 action의 현재 적용 가능 여부와, 비활성일 때 사용자에게 보여 줄 이유다.
 * 저장소의 dirty/충돌 판정은 ViewModel이 수행하고 이 화면에는 표시 결과만 전달한다.
 */
internal data class CommitRestoreActionAvailability(
    val enabled: Boolean,
    val disabledReason: String? = null,
) {
    companion object {
        val Enabled = CommitRestoreActionAvailability(enabled = true)
    }
}

@Composable
internal fun CommitHistoryListPane(
    isLoading: Boolean,
    history: List<CommitHistoryEntry>,
    headCommitId: String?,
    selectedCommitId: String?,
    errorMessage: String?,
    onRetry: () -> Unit,
    onOpenCommitDialog: () -> Unit,
    onCommitSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
) {
    when {
        isLoading && history.isEmpty() -> EmptyPaneMessage(
            text = "커밋 이력을 불러오는 중…",
            loading = true,
            modifier = modifier,
        )
        errorMessage != null && history.isEmpty() -> EmptyPaneMessage(
            text = errorMessage,
            actionLabel = "다시 시도",
            onAction = onRetry,
            isError = true,
            modifier = modifier,
        )
        history.isEmpty() -> EmptyPaneMessage(
            text = "아직 커밋 이력이 없습니다.",
            actionLabel = "첫 커밋 만들기",
            onAction = onOpenCommitDialog,
            modifier = modifier,
        )
        else -> Column(modifier) {
            if (errorMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = onRetry) { Text("다시 시도") }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(history, key = { it.commit.id }) { entry ->
                    CommitHistoryListItem(
                        entry = entry,
                        isHead = entry.commit.id == headCommitId,
                        selected = entry.commit.id == selectedCommitId,
                        onClick = { onCommitSelected(entry.commit.id) },
                        showChevron = showChevron,
                    )
                    if (showChevron) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CommitHistoryListItem(
    entry: CommitHistoryEntry,
    isHead: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    showChevron: Boolean = false,
) {
    val timelineColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = if (isHead || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (selected) 1.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.drawBehind {
                val x = 14.dp.toPx()
                val y = 22.dp.toPx()
                drawLine(timelineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawCircle(markerColor, 4.dp.toPx(), Offset(x, y))
            }.padding(start = 32.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.commit.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isHead) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "현재",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (showChevron) Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = formatCommitTime(entry.commit.createdAtEpochMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "변경 ${entry.changes.size}개 · ${entry.commit.id.take(8)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CommitHistoryDetail(
    entry: CommitHistoryEntry,
    isHead: Boolean,
    workingPreview: CommitPreview?,
    onProjectRestoreRequest: (CommitHistoryEntry) -> Unit,
    onHeadRevertRequest: (CommitHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    fixedRestoreFooter: Boolean = false,
    inlineDiff: (@Composable () -> Unit)? = null,
    changeContent: @Composable (CommitChange) -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.commit.message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (isHead) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "현재",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        Text(
            text = "${formatCommitTime(entry.commit.createdAtEpochMillis)} · ${entry.commit.id.take(8)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text(
            text = "변경된 파일 ${entry.changes.size}개",
            style = MaterialTheme.typography.labelLarge,
        )
        entry.changes.forEach { change ->
            changeContent(change)
        }

        inlineDiff?.invoke()

    }
    val restore: @Composable () -> Unit = {
        HorizontalDivider()
        val projectStateKnown = workingPreview != null
        val projectDirty = workingPreview?.hasChanges == true
        val snapshotRestoreEnabled = projectStateKnown && (!isHead || projectDirty)
        Button(
            onClick = { onProjectRestoreRequest(entry) },
            enabled = snapshotRestoreEnabled,
        ) {
            Text(if (isHead) "현재 커밋 시점으로 복원…" else "이 커밋 시점으로 복원…")
        }
        when {
            !projectStateKnown ->
                DisabledReason("현재 변경 상태를 확인한 뒤 복원할 수 있습니다.")
            isHead && !projectDirty ->
                DisabledReason("작업 파일이 이미 현재 커밋 시점과 같습니다.")
        }

        if (isHead) {
            if (entry.commit.parentId == null) {
                DisabledReason("이 커밋보다 이전 기준점이 없습니다.")
            } else {
                OutlinedButton(
                    onClick = { onHeadRevertRequest(entry) },
                    enabled = projectStateKnown,
                ) {
                    Text("최근 커밋의 변경 되돌리기…")
                }
            }
        }

    }
    if (fixedRestoreFooter) BoxWithConstraints(modifier) {
        val restoreFooterMaxHeight = maxHeight * 0.45f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            Column(Modifier.fillMaxWidth().heightIn(max = restoreFooterMaxHeight).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) { restore() }
        }
    } else Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) { content(); restore() }
}
@Composable
internal fun CommitHistoryFileDiff(
    entry: CommitHistoryEntry,
    state: CommitDiffUiState,
    fileContentRestoreAvailability: (CommitChange, CommitFileSide) -> CommitRestoreActionAvailability,
    fileRestoreAvailability: (CommitChange, CommitFileSide) -> CommitRestoreActionAvailability,
    onRetry: (String, CommitChange) -> Unit,
    onFileContentRestoreRequest: (CommitChange, CommitFileSide) -> Unit,
    onFileRestoreRequest: (CommitChange, CommitFileSide) -> Unit,
    modifier: Modifier = Modifier,
    scrollContent: Boolean = true,
) {
    val change = entry.changes.firstOrNull { it.fileId == state.fileId }
    var selectedSide by remember(state.commitId, state.fileId) {
        mutableStateOf(CommitFileSide.AFTER)
    }
    Column(
        modifier = modifier
            .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (change == null) {
            Text(
                text = "선택한 커밋에서 파일 변경을 찾을 수 없습니다.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        Text(
            text = change.displayPath,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        CommitLineDiffContent(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            maxHeight = 420.dp,
        )
        if (state.errorMessage != null) {
            TextButton(onClick = { onRetry(entry.commit.id, change) }) {
                Text("diff 다시 시도")
            }
        }

        if (state.result != null) {
            HorizontalDivider()
            Text(
                text = "복원할 버전",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileSideChip(
                    side = CommitFileSide.BEFORE,
                    path = change.oldPath,
                    selected = selectedSide == CommitFileSide.BEFORE,
                    onClick = { selectedSide = it },
                )
                FileSideChip(
                    side = CommitFileSide.AFTER,
                    path = change.newPath,
                    selected = selectedSide == CommitFileSide.AFTER,
                    onClick = { selectedSide = it },
                )
            }

            val selectedPath = change.path(selectedSide)
            Text(
                text = "선택: ${selectedSide.label} · ${selectedPath ?: "파일 없음"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val sideHasFile = selectedPath != null && change.blobHash(selectedSide) != null
            val requestedContentAvailability = fileContentRestoreAvailability(change, selectedSide)
            val contentAvailability = if (!sideHasFile) {
                CommitRestoreActionAvailability(
                    enabled = false,
                    disabledReason = "선택한 쪽에는 파일이 없습니다. 단일 파일 전체 복원을 사용하세요.",
                )
            } else {
                requestedContentAvailability
            }
            val wholeFileAvailability = fileRestoreAvailability(change, selectedSide)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onFileContentRestoreRequest(change, selectedSide) },
                    enabled = contentAvailability.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("파일 내용 복원…")
                }
                contentAvailability.disabledReason?.let { DisabledReason(it) }

                Button(
                    onClick = { onFileRestoreRequest(change, selectedSide) },
                    enabled = wholeFileAvailability.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("단일 파일 전체 복원…")
                }
                wholeFileAvailability.disabledReason?.let { DisabledReason(it) }
            }
        }
    }
}

@Composable
private fun FileSideChip(
    side: CommitFileSide,
    path: String?,
    selected: Boolean,
    onClick: (CommitFileSide) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onClick(side) },
        label = {
            Text(
                text = if (path == null) "${side.label} · 파일 없음" else side.label,
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun DisabledReason(reason: String) {
    Text(
        text = reason,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyPaneMessage(
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    isError: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (loading) LoadingRow(text) else Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            actionLabel?.let { label ->
                TextButton(onClick = onAction) { Text(label) }
            }
        }
    }
}

private val CommitFileSide.label: String
    get() = when (this) {
        CommitFileSide.BEFORE -> "변경 전"
        CommitFileSide.AFTER -> "변경 후"
    }

private fun CommitChange.path(side: CommitFileSide): String? = when (side) {
    CommitFileSide.BEFORE -> oldPath
    CommitFileSide.AFTER -> newPath
}

private fun CommitChange.blobHash(side: CommitFileSide): String? = when (side) {
    CommitFileSide.BEFORE -> oldBlobHash
    CommitFileSide.AFTER -> newBlobHash
}

private fun formatCommitTime(epochMillis: Long): String = runCatching {
    Instant.fromEpochMilliseconds(epochMillis)
        .toString()
        .replace('T', ' ')
        .removeSuffix("Z")
}.getOrElse { epochMillis.toString() }
