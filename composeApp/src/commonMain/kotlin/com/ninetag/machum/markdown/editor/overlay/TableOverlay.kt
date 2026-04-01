package com.ninetag.machum.markdown.editor.overlay

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.editor.MarkdownStyleConfig
import com.ninetag.machum.markdown.editor.OverlayBlockData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Table 블록 오버레이.
 *
 * 그리드 레이아웃 + 셀별 TextField로 직접 편집 가능.
 * LongPress → raw 전환.
 */
@Composable
internal fun TableOverlay(
    data: OverlayBlockData.TableData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    modifier: Modifier = Modifier,
    width: Dp,
    height: Dp,
) {
    val columnCount = maxOf(
        data.headers.size,
        data.rows.maxOfOrNull { it.size } ?: 0
    ).coerceAtLeast(1)

    // 셀 상태: [row][col] — row 0 = headers
    val cellStates = remember(data.blockRange.textRange) {
        val allRows = mutableListOf(data.headers) + data.rows
        allRows.map { row ->
            row.mapTo(mutableStateListOf()) { cell -> TextFieldState(cell) }
        }
    }

    // 셀 변경 → raw markdown 동기화
    LaunchedEffect(cellStates) {
        for ((rowIdx, row) in cellStates.withIndex()) {
            for ((colIdx, cellState) in row.withIndex()) {
                snapshotFlow { cellState.text.toString() }
                    .distinctUntilChanged()
                    .drop(1)
                    .collectLatest {
                        syncTableToRaw(textFieldState, data, cellStates)
                    }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .size(width, height)
            .border(1.dp, Color(0x33000000))
            .pointerInput(data.blockRange.textRange) {
                detectTapGestures(
                    onLongPress = {
                        textFieldState.edit {
                            selection = TextRange(data.blockRange.textRange.first)
                        }
                    },
                )
            },
        userScrollEnabled = false,
    ) {
        itemsIndexed(cellStates) { rowIdx, row ->
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
) {
    if (state != null) {
        BasicTextField(
            state = state,
            textStyle = if (isHeader) TextStyle(fontWeight = FontWeight.Bold) else TextStyle.Default,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(0.5.dp, Color(0x22000000))
                .padding(6.dp),
            lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
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
