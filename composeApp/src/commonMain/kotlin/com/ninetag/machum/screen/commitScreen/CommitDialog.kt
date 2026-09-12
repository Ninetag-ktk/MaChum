package com.ninetag.machum.screen.commitScreen

import com.ninetag.machum.screen.mainScreen.CommitDiffUiState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninetag.machum.commit.CommitChange
import com.ninetag.machum.commit.CommitChangeKind
import com.ninetag.machum.commit.LineDiffKind
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import com.ninetag.machum.theme.WorkspaceUiMetrics
import com.ninetag.machum.theme.semanticColors

/** 줄 diff의 표시 본문. 빠른 커밋 dialog와 이력 화면이 같은 표현을 사용한다. */
@Composable
internal fun CommitLineDiffContent(
    state: CommitDiffUiState,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 480.dp,
    scrollVertically: Boolean = true,
) {
    Column(
        modifier = modifier,
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
                    val verticalScrollState = rememberScrollState()
                    val horizontalScrollState = rememberScrollState()
                    val verticalScrollModifier = if (scrollVertically) {
                        Modifier
                            .heightIn(max = maxHeight)
                            .verticalScroll(verticalScrollState)
                    } else {
                        Modifier
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val paneWidth = maxWidth
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(verticalScrollModifier)
                                .horizontalScroll(horizontalScrollState)
                                .widthIn(min = paneWidth)
                                .width(IntrinsicSize.Max),
                        ) {
                            result.lines.forEach { line ->
                                val prefix = when (line.kind) {
                                    LineDiffKind.ADDED -> "+"
                                    LineDiffKind.DELETED -> "-"
                                    LineDiffKind.CONTEXT -> " "
                                    LineDiffKind.OMITTED -> "…"
                                }
                                val background = when (line.kind) {
                                    LineDiffKind.ADDED -> MaterialTheme.semanticColors.successContainer.copy(alpha = 0.45f)
                                    LineDiffKind.DELETED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.surface
                                }
                                val oldNumber = line.oldLineNumber?.toString()?.padStart(4) ?: "    "
                                val newNumber = line.newLineNumber?.toString()?.padStart(4) ?: "    "
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("$oldNumber $newNumber $prefix ") }
                                        append(line.text)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(background)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    softWrap = false,
                                    style = WorkspaceUiMetrics.bodyTextStyle,
                                    color = when (line.kind) {
                                        LineDiffKind.OMITTED -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
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
}

/** 세 복원 동작이 공유하는 위험 동작 확인 shell. */
@Composable
internal fun CommitRestoreConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    busyLabel: String,
    isRestoring: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(title, body) {
        cancelFocusRequester.requestFocus()
    }

    PolicyDialog(
        onDismissRequest = { if (!isRestoring) onDismissRequest() },
        title = title,
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isRestoring,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(if (isRestoring) busyLabel else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isRestoring,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .focusRequester(cancelFocusRequester),
            ) {
                Text("취소")
            }
        },
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isRestoring) LoadingRow(busyLabel)
        errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CommitChangeRow(
    modifier: Modifier = Modifier,
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
        Modifier.clickable(onClick = onClick)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PopupUiMetrics.RowMinHeight)
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
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (change.addedLines > 0 || change.deletedLines > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "+${change.addedLines}  -${change.deletedLines}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
                    contentDescription = "변경 내용 보기",
                    modifier = Modifier.size(PopupUiMetrics.IconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
