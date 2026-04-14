package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.ui.BlockNavigation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 테이블 블록 에디터.
 *
 * 그리드 레이아웃 + 셀별 BasicTextField.
 * 방향키로 셀 간 이동, Tab으로 다음 셀(마지막이면 행 추가), Enter로 행 삽입.
 * 포커스 시 오른쪽/아래에 열/행 추가 버튼 표시.
 */
@Composable
internal fun TableBlockEditor(
    block: EditorBlock.Table,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush = SolidColor(Color.Black),
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    navigation: BlockNavigation = BlockNavigation(),
    onBlockChanged: (EditorBlock.Table) -> Unit = {},
) {
    val shape = RoundedCornerShape(4.dp)
    val borderColor = styleConfig.blockquoteAccent
    val colCount = block.headerStates.size
    val totalRows = 1 + block.rowStates.size  // header + data rows

    // 2D FocusRequester grid: [row][col]
    // 첫 셀(header[0])은 block-level focusRequester를 사용 → 블록 진입 시 첫 셀에 포커스
    val focusGrid = remember(totalRows, colCount) {
        Array(totalRows) { row ->
            Array(colCount) { col ->
                if (row == 0 && col == 0) focusRequester else FocusRequester()
            }
        }
    }

    // 테이블 내 셀 중 하나라도 포커스 → 활성 (+ 버튼 표시)
    var tableFocused by remember { mutableStateOf(false) }
    var focusedCellCount by remember { mutableStateOf(0) }

    // 행 추가 후 지연 포커스
    var pendingFocusRow by remember { mutableStateOf(-1) }
    var pendingFocusCol by remember { mutableStateOf(-1) }
    var focusCellCounter by remember { mutableStateOf(0) }

    LaunchedEffect(focusCellCounter) {
        if (pendingFocusRow >= 0) {
            // recomposition 완료 대기 (focusGrid 재생성 후)
            kotlinx.coroutines.delay(100)
            try {
                val currentTotalRows = 1 + block.rowStates.size
                val currentColCount = block.headerStates.size
                val r = pendingFocusRow.coerceIn(0, currentTotalRows - 1)
                val c = pendingFocusCol.coerceIn(0, currentColCount - 1)
                focusGrid[r][c].requestFocus()
            } catch (_: Exception) {}
            pendingFocusRow = -1
        }
    }

    fun requestCellFocus(row: Int, col: Int) {
        val r = row.coerceIn(0, totalRows - 1)
        val c = col.coerceIn(0, colCount - 1)
        try { focusGrid[r][c].requestFocus() } catch (_: Exception) {}
    }

    fun addRow() {
        val newRow = List(colCount) { TextFieldState("") }
        onBlockChanged(block.copy(rowStates = block.rowStates + listOf(newRow)))
    }

    fun insertRowBelow(dataRowIndex: Int) {
        val newRow = List(colCount) { TextFieldState("") }
        val newRows = block.rowStates.toMutableList()
        newRows.add(dataRowIndex + 1, newRow)
        onBlockChanged(block.copy(rowStates = newRows))
    }

    fun addColumn() {
        val newHeaders = block.headerStates + TextFieldState("")
        val newRows = block.rowStates.map { row -> row + TextFieldState("") }
        onBlockChanged(block.copy(headerStates = newHeaders, rowStates = newRows))
    }

    // 셀 키 핸들러 생성
    fun cellKeyHandler(row: Int, col: Int, cellState: TextFieldState): Modifier =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val sel = cellState.selection
            when (event.key) {
                Key.DirectionRight -> {
                    if (sel.collapsed && sel.start >= cellState.text.length) {
                        if (col < colCount - 1) requestCellFocus(row, col + 1)
                        else if (row < totalRows - 1) requestCellFocus(row + 1, 0)
                        else navigation.onMoveToNext()
                        true
                    } else false
                }
                Key.DirectionLeft -> {
                    if (sel.collapsed && sel.start == 0) {
                        if (col > 0) requestCellFocus(row, col - 1)
                        else if (row > 0) requestCellFocus(row - 1, colCount - 1)
                        else navigation.onMoveToPrevious()
                        true
                    } else false
                }
                Key.DirectionUp -> {
                    if (row > 0) { requestCellFocus(row - 1, col); true }
                    else { navigation.onMoveToPrevious(); true }
                }
                Key.DirectionDown -> {
                    if (row < totalRows - 1) { requestCellFocus(row + 1, col); true }
                    else { navigation.onMoveToNext(); true }
                }
                Key.Tab -> {
                    if (col < colCount - 1) {
                        requestCellFocus(row, col + 1)
                    } else if (row < totalRows - 1) {
                        requestCellFocus(row + 1, 0)
                    } else {
                        // 마지막 셀 Tab → 새 행 추가
                        addRow()
                        pendingFocusRow = totalRows  // will be valid after recomposition
                        pendingFocusCol = 0
                        focusCellCounter++
                    }
                    true
                }
                Key.Enter -> {
                    // header(row=0) → dataRowIndex=-1 → insert at 0
                    // data row(row=N) → dataRowIndex=N-1 → insert at N
                    val dataRowIndex = row - 1
                    insertRowBelow(dataRowIndex)
                    pendingFocusRow = row + 1
                    pendingFocusCol = col
                    focusCellCounter++
                    true
                }
                else -> false
            }
        }

    Column(modifier = modifier) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 메인 테이블
        Column(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, borderColor, shape)
        ) {
            // Header row (row = 0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                for ((col, cellState) in block.headerStates.withIndex()) {
                    BasicTextField(
                        state = cellState,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusGrid[0][col])
                            .onFocusChanged { state ->
                                focusedCellCount += if (state.isFocused) 1 else -1
                                tableFocused = focusedCellCount > 0
                            }
                            .border(width = 0.5.dp, color = borderColor)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .then(cellKeyHandler(0, col, cellState)),
                        textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
                        cursorBrush = cursorBrush,
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                }
            }

            // Data rows (row = 1..totalRows-1)
            for ((rowIdx, row) in block.rowStates.withIndex()) {
                val gridRow = rowIdx + 1  // header가 0이므로
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    for ((col, cellState) in row.withIndex()) {
                        BasicTextField(
                            state = cellState,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusGrid[gridRow][col])
                                .onFocusChanged { state ->
                                    focusedCellCount += if (state.isFocused) 1 else -1
                                    tableFocused = focusedCellCount > 0
                                }
                                .border(width = 0.5.dp, color = borderColor)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .then(cellKeyHandler(gridRow, col, cellState)),
                            textStyle = textStyle,
                            cursorBrush = cursorBrush,
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )
                    }
                }
            }
        }

        // + 열 추가 버튼 (오른쪽, 포커스 시에만)
        if (tableFocused) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .clickable { addColumn() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "열 추가",
                    modifier = Modifier.size(16.dp),
                    tint = borderColor,
                )
            }
        }
    }

    // + 행 추가 버튼 (아래쪽, 포커스 시에만)
    if (tableFocused) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clickable { addRow() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "행 추가",
                modifier = Modifier.size(16.dp),
                tint = borderColor,
            )
        }
    }
    } // Column
}
