package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.util.overlayScrollForwarder

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.OverlayBlockData
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.time.Duration.Companion.milliseconds

/**
 * Table 블록 오버레이.
 *
 * 그리드 레이아웃 + 셀별 TextField로 직접 편집 가능.
 * LongPress → raw 전환.
 *
 * State 관리: CalloutOverlay와 동일 패턴.
 * - remember 키 없이 셀 상태 생성 (부모 key(type, index)로 identity 관리)
 * - 포커스 중: 오버레이 → raw 동기화 (debounce 300ms)
 * - 포커스 아웃: raw → 오버레이 동기화 + 조건부 즉시 sync
 */
@OptIn(FlowPreview::class)
@Composable
internal fun TableOverlay(
    data: OverlayBlockData.TableData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle = TextStyle.Default,
    scrollState: ScrollState? = null,
    onRequestActivation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val columnCount = maxOf(
        data.headers.size,
        data.rows.maxOfOrNull { it.size } ?: 0
    ).coerceAtLeast(1)

    // 키 없이 remember → sync로 인한 textRange 변경 시 state 재생성 방지
    val cellStates = remember {
        val allRows = mutableListOf(data.headers) + data.rows
        allRows.map { row ->
            row.mapTo(mutableStateListOf()) { cell -> TextFieldState(cell) }
        }
    }

    var isTableFocused by remember { mutableStateOf(false) }
    val currentData by rememberUpdatedState(data)

    // raw → 오버레이 동기화 (포커스 없을 때만)
    LaunchedEffect(data.headers, data.rows, isTableFocused) {
        if (!isTableFocused) {
            val allRows = mutableListOf(data.headers) + data.rows
            for ((rowIdx, row) in allRows.withIndex()) {
                val cellRow = cellStates.getOrNull(rowIdx) ?: continue
                for ((colIdx, cellValue) in row.withIndex()) {
                    val cellState = cellRow.getOrNull(colIdx) ?: continue
                    if (cellState.text.toString() != cellValue) {
                        cellState.edit { replace(0, length, cellValue) }
                    }
                }
            }
        }
    }

    // 포커스 이탈 시 조건부 동기화
    LaunchedEffect(isTableFocused) {
        if (!isTableFocused) {
            val currentCells = cellStates.map { row -> row.map { it.text.toString() } }
            val originalCells = mutableListOf(currentData.headers) + currentData.rows
            if (currentCells != originalCells) {
                syncTableToRaw(textFieldState, currentData, cellStates)
            }
        }
    }

    // 오버레이 → raw 동기화 (debounce 300ms, 포커스 중에만)
    LaunchedEffect(Unit) {
        // 모든 셀의 텍스트를 하나의 문자열로 합쳐서 변경 감지
        snapshotFlow {
            cellStates.joinToString("\u0000") { row ->
                row.joinToString("\u0001") { it.text.toString() }
            }
        }
            .distinctUntilChanged()
            .drop(1)
            .debounce(300.milliseconds)
            .collectLatest {
                if (isTableFocused) {
                    syncTableToRaw(textFieldState, currentData, cellStates)
                }
            }
    }

    val scrollForwarder = if (!isTableFocused) {
        scrollState?.let { overlayScrollForwarder(it) } ?: Modifier
    } else {
        Modifier
    }

    // 구분자 줄(| --- |)이 marker로 축소된 높이를 상하 padding으로 보정
    // lineHeight ÷ 2 = 상하 균등 분배
    val separatorPadding = resolveLineHeightDp(textStyle) / 2

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(scrollForwarder)
            .padding(vertical = separatorPadding)  // border 외부: 축소된 구분자 줄 높이 보정
            .border(1.dp, Color(0x33000000))
            .onFocusChanged { isTableFocused = it.hasFocus }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onRequestActivation() },
                )
            },
    ) {
        for ((rowIdx, row) in cellStates.withIndex()) {
            val isHeader = rowIdx == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                for (colIdx in 0 until columnCount) {
                    TableCell(
                        state = row.getOrNull(colIdx),
                        isHeader = isHeader,
                        textStyle = textStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    state: TextFieldState?,
    isHeader: Boolean,
    textStyle: TextStyle,
) {
    if (state != null) {
        BasicTextField(
            state = state,
            textStyle = if (isHeader) textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)) else textStyle,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(0.5.dp, textStyle.color)
                .padding(horizontal = 6.dp),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(textStyle.color),
        )
    }
}

private fun syncTableToRaw(
    textFieldState: TextFieldState,
    data: OverlayBlockData.TableData,
    cellStates: List<List<TextFieldState>>,
) {
    if (cellStates.isEmpty()) return
    val headerRow = cellStates.first()
    val headerLine = "| " + headerRow.joinToString(" | ") { it.text.toString() } + " |"
    val separatorLine = "| " + headerRow.joinToString(" | ") { "---" } + " |"
    val dataLines = cellStates.drop(1).joinToString("\n") { row ->
        "| " + row.joinToString(" | ") { it.text.toString() } + " |"
    }
    val newRaw = if (dataLines.isNotEmpty()) {
        "$headerLine\n$separatorLine\n$dataLines"
    } else {
        "$headerLine\n$separatorLine"
    }
    val start = data.blockRange.textRange.first
    val end = data.blockRange.textRange.last + 1
    textFieldState.edit {
        replace(
            start.coerceIn(0, length),
            end.coerceIn(0, length),
            newRaw,
        )
    }
}