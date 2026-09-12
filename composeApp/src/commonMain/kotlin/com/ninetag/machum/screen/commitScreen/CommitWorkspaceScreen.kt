package com.ninetag.machum.screen.commitScreen

import androidx.compose.animation.core.Animatable
import com.ninetag.machum.screen.mainScreen.CommitCreateUiState
import com.ninetag.machum.screen.mainScreen.CommitHistoryUiState

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.CommitChangeKind
import com.ninetag.machum.commit.CommitFileSide
import com.ninetag.machum.commit.CommitHistoryEntry
import com.ninetag.machum.theme.WorkspaceUiMetrics
import com.ninetag.machum.theme.WorkspaceMotion
import com.ninetag.machum.theme.semanticColors

internal enum class CommitWorkspaceTab { Changes, History }

/** Presentation only: tab, draft, diff, and restore lifetimes belong to the caller. */
@Composable
internal fun CommitWorkspaceScreen(
    projectName: String,
    selectedTab: CommitWorkspaceTab,
    createState: CommitCreateUiState,
    historyState: CommitHistoryUiState,
    message: String,
    onMessageChange: (String) -> Unit,
    onTabSelected: (CommitWorkspaceTab) -> Unit,
    onBack: () -> Unit,
    onCommit: (String) -> Unit,
    onCreateDiffRequest: (CommitChange) -> Unit,
    onHistoryRetry: () -> Unit,
    onCommitSelected: (String) -> Unit,
    onHistoryDiffRequest: (String, CommitChange) -> Unit,
    fileContentRestoreAvailability: (CommitChange, CommitFileSide) -> CommitRestoreActionAvailability,
    fileRestoreAvailability: (CommitChange, CommitFileSide) -> CommitRestoreActionAvailability,
    onProjectRestoreRequest: (CommitHistoryEntry) -> Unit,
    onHeadRevertRequest: (CommitHistoryEntry) -> Unit,
    onFileContentRestoreRequest: (CommitChange, CommitFileSide) -> Unit,
    onFileRestoreRequest: (CommitChange, CommitFileSide) -> Unit,
    onCreateRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = createState.isCommitting || historyState.restore?.isRestoring == true
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(modifier.fillMaxSize().safeDrawingPadding().imePadding().onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
            if (!busy && historyState.restore == null) onBack()
            true
        } else false
    }.focusRequester(focusRequester).focusable(), color = MaterialTheme.colorScheme.background) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideHeader = maxWidth >= WorkspaceUiMetrics.commitHistoryWideMinWidth
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = WorkspaceUiMetrics.topBarHeight).padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Default.ArrowBack, "이전 화면", Modifier.size(WorkspaceUiMetrics.iconSize))
                }
                if (wideHeader) {
                    Text("커밋", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(projectName, style = WorkspaceUiMetrics.bodyTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text("커밋", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(projectName, style = WorkspaceUiMetrics.secondaryTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                CommitWorkspaceTab.entries.forEach { tab ->
                    Column(if (wideHeader) Modifier.width(120.dp) else Modifier.weight(1f)) {
                    Tab(selected = tab == selectedTab, enabled = !busy,
                        onClick = { onTabSelected(tab) },
                        text = { Text(if (tab == CommitWorkspaceTab.Changes) "변경사항" else "이력") })
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == selectedTab) MaterialTheme.colorScheme.primary else Color.Transparent))
                    }
                }
            }
            HorizontalDivider()
            val tabEnterAlpha = remember(selectedTab) { Animatable(0f) }
            LaunchedEffect(selectedTab) {
                tabEnterAlpha.animateTo(1f, WorkspaceMotion.contentEnterSpec())
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = tabEnterAlpha.value },
            ) {
                val wide = maxWidth >= WorkspaceUiMetrics.commitHistoryWideMinWidth
                when (selectedTab) {
                    CommitWorkspaceTab.Changes -> CommitChangesWorkspace(
                        createState, message, onMessageChange, onCommit, onCreateDiffRequest, onCreateRetry, wide,
                    )
                    CommitWorkspaceTab.History -> {
                        val entry = historyState.history.firstOrNull { it.commit.id == historyState.selectedCommitId }
                        val head = historyState.workingPreview?.parentCommitId ?: historyState.history.firstOrNull()?.commit?.id
                        val openChanges = { onTabSelected(CommitWorkspaceTab.Changes) }
                        val fileDiff: @Composable (Boolean) -> Unit = { scroll ->
                            if (entry != null && historyState.diff != null) CommitHistoryFileDiff(
                                entry, historyState.diff, fileContentRestoreAvailability, fileRestoreAvailability,
                                onHistoryDiffRequest, onFileContentRestoreRequest, onFileRestoreRequest,
                                modifier = Modifier.fillMaxWidth(), scrollContent = scroll,
                            )
                        }
                        val detail: @Composable () -> Unit = {
                            if (entry == null) {
                                CommitWorkspaceMessage("확인할 커밋을 선택하세요.")
                            } else CommitHistoryDetail(
                                entry = entry, isHead = entry.commit.id == head,
                                workingPreview = historyState.workingPreview,
                                onProjectRestoreRequest = onProjectRestoreRequest,
                                onHeadRevertRequest = onHeadRevertRequest,
                                modifier = Modifier.fillMaxSize(),
                                fixedRestoreFooter = wide,
                                inlineDiff = if (wide && historyState.diff != null) ({ fileDiff(false) }) else null,
                                changeContent = { change -> CommitWorkspaceChangeRow(change,
                                    selected = historyState.diff?.fileId == change.fileId,
                                    showChevron = !wide,
                                    onClick = { onHistoryDiffRequest(entry.commit.id, change) }) },
                            )
                        }
                        val list: @Composable (Modifier) -> Unit = { listModifier ->
                            CommitHistoryListPane(
                                historyState.isLoading, historyState.history, head, historyState.selectedCommitId,
                                historyState.errorMessage, onHistoryRetry, openChanges, onCommitSelected, listModifier,
                                showChevron = !wide,
                            )
                        }
                        if (wide) Row(Modifier.fillMaxSize()) {
                            list(Modifier.width(WorkspaceUiMetrics.commitHistoryListWidth).fillMaxHeight())
                            VerticalDivider(Modifier.fillMaxHeight())
                            Box(Modifier.weight(1f)) { detail() }
                        } else when {
                            entry != null && historyState.diff != null -> fileDiff(true)
                            entry != null -> detail()
                            else -> list(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun CommitChangesWorkspace(
    state: CommitCreateUiState,
    message: String,
    onMessageChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    onDiffRequest: (CommitChange) -> Unit,
    onRetry: () -> Unit,
    wide: Boolean,
) {
    val busy = state.isLoading || state.isCommitting
    val canCommit = state.preview?.hasChanges == true && message.isNotBlank() && !busy
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val footerMaxHeight = maxHeight * 0.65f
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val files: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    Text("변경 파일 ${state.preview?.changes?.size ?: 0}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    when {
                        state.isLoading -> CommitWorkspaceMessage("변경 사항을 확인하는 중…", loading = true)
                        state.preview == null && state.errorMessage != null -> Column(Modifier.padding(16.dp)) {
                            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry, enabled = !busy) { Text("다시 시도") }
                        }
                        state.preview?.hasChanges != true -> CommitWorkspaceMessage("커밋할 변경 사항이 없습니다.")
                        else -> LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
                            items(state.preview.changes, key = { it.fileId }) { change ->
                                CommitWorkspaceChangeRow(change, selected = state.diff?.fileId == change.fileId,
                                    showChevron = !wide,
                                    onClick = if (busy) null else ({ onDiffRequest(change) }))
                            }
                        }
                    }
                }
            }
            val diff: @Composable () -> Unit = {
                val selected = state.diff
                if (selected == null) CommitWorkspaceMessage("변경 파일을 선택하면 비교 내용을 볼 수 있습니다.")
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selected.displayPath, style = MaterialTheme.typography.titleMedium)
                    Text("변경 비교", style = WorkspaceUiMetrics.secondaryTextStyle)
                    CommitLineDiffContent(selected, Modifier.fillMaxWidth(), scrollVertically = false)
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                files(Modifier.width(WorkspaceUiMetrics.commitHistoryListWidth).fillMaxHeight())
                VerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(1f)) { diff() }
            } else if (state.diff != null) diff() else files(Modifier.fillMaxSize())
        }
        if (wide || state.diff == null) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().heightIn(max = footerMaxHeight).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.preview != null) state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("커밋 메시지", style = MaterialTheme.typography.labelLarge)
                val input: @Composable (Modifier) -> Unit = { modifier ->
                    OutlinedTextField(message, onMessageChange, modifier = modifier.onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && (it.isCtrlPressed || it.isMetaPressed) && canCommit) {
                            onCommit(message.trim()); true
                        } else false
                    }, enabled = !busy, placeholder = { Text("이번 변경 내용을 요약해 주세요") },
                        shape = MaterialTheme.shapes.medium,
                        singleLine = false, minLines = if (wide) 1 else 2, maxLines = if (wide) 3 else 4,
                        textStyle = WorkspaceUiMetrics.bodyTextStyle)
                }
                val commit: @Composable (Modifier) -> Unit = { modifier ->
                    Button(onClick = { onCommit(message.trim()) }, enabled = canCommit, shape = MaterialTheme.shapes.medium, modifier = modifier.heightIn(min = 48.dp)) {
                        Text(if (state.isCommitting) "커밋 중…" else "커밋 만들기")
                    }
                }
                if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    input(Modifier.weight(1f)); commit(Modifier.widthIn(min = 160.dp))
                } else { input(Modifier.fillMaxWidth()); commit(Modifier.fillMaxWidth()) }
                Text(if (wide) "변경된 파일 전체를 기록합니다.  Ctrl/Cmd + Enter" else "변경된 파일 전체를 기록합니다.", style = WorkspaceUiMetrics.secondaryTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}

@Composable
private fun CommitWorkspaceMessage(message: String, loading: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (loading) LoadingRow(message) else Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CommitWorkspaceChangeRow(change: CommitChange, selected: Boolean, showChevron: Boolean = false, onClick: (() -> Unit)?) {
    val (label, tint) = when (change.kind) {
        CommitChangeKind.ADDED -> "추가" to MaterialTheme.semanticColors.success
        CommitChangeKind.MODIFIED -> "수정" to MaterialTheme.colorScheme.tertiary
        CommitChangeKind.DELETED -> "삭제" to MaterialTheme.colorScheme.error
        CommitChangeKind.RENAMED -> "이름 변경" to MaterialTheme.colorScheme.secondary
        CommitChangeKind.RENAMED_AND_MODIFIED -> "이름 변경·수정" to MaterialTheme.colorScheme.tertiary
    }
    val path = change.displayPath.replace('\\', '/')
    Surface(shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Description, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis, style = WorkspaceUiMetrics.bodyTextStyle)
                if ('/' in path) Text(path.substringBeforeLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = WorkspaceUiMetrics.secondaryTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = tint.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
                Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = tint, style = MaterialTheme.typography.labelLarge)
            }
            if (showChevron) Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showChevron) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
