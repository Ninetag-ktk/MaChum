package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.ui.BlockNavigation

import androidx.compose.foundation.background
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
import com.ninetag.machum.markdown.ui.selection.resetDocumentSelectionOnFocus
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds

/**
 * 테이블 블록 에디터.
 *
 * 그리드 레이아웃 + 셀별 BasicTextField.
 * 방향키로 셀 간 이동, Tab으로 다음 열(마지막이면 열 추가), Enter로 다음 행(마지막이면 행 추가).
 * 포커스 시 오른쪽/아래에 열/행 추가 버튼 표시.
 */
@Composable
internal fun TableBlockEditor(
    block: EditorBlock.Table,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
    focusRequester: FocusRequester = remember { FocusRequester() },
    navigation: BlockNavigation = BlockNavigation(),
    onBlockChanged: (EditorBlock.Table) -> Unit = {},
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit = {},
) {
    val shape = RoundedCornerShape(4.dp)
    val borderColor = styleConfig.blockquoteAccent

    // rememberUpdatedState로 최신 block 참조 보장 (LazyColumn stale 방지)
    val currentBlock by rememberUpdatedState(block)
    val colCount = currentBlock.headerStates.size
    val totalRows = 1 + currentBlock.rowStates.size

    // 2D FocusRequester grid
    val focusGrid = remember(totalRows, colCount) {
        Array(totalRows) { row ->
            Array(colCount) { col ->
                if (row == 0 && col == 0) focusRequester else FocusRequester()
            }
        }
    }

    // ↑ 진입 시 마지막 행 첫 열로 포커스되도록 bottomEntryFR 등록
    LaunchedEffect(totalRows, colCount) {
        onRegisterBottomEntryFR(focusGrid[totalRows - 1][0])
    }

    // 테이블 포커스 추적: 외부 Column의 hasFocus 사용 (자식 셀 중 하나라도 포커스면 true)
    var tableFocused by remember { mutableStateOf(false) }

    // 행 추가 후 지연 포커스
    var pendingFocusRow by remember { mutableStateOf(-1) }
    var pendingFocusCol by remember { mutableStateOf(-1) }
    var focusCellCounter by remember { mutableStateOf(0) }

    LaunchedEffect(focusCellCounter) {
        if (pendingFocusRow >= 0) {
            kotlinx.coroutines.delay(100.milliseconds)
            try {
                val r = pendingFocusRow.coerceIn(0, focusGrid.lastIndex)
                val c = pendingFocusCol.coerceIn(0, focusGrid[0].lastIndex)
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
        val b = currentBlock
        val newRow = List(b.headerStates.size) { TextFieldState("") }
        onBlockChanged(b.copy(rowStates = b.rowStates + listOf(newRow)))
    }

    fun addColumn() {
        val b = currentBlock
        val newHeaders = b.headerStates + TextFieldState("")
        val newRows = b.rowStates.map { row -> row + TextFieldState("") }
        onBlockChanged(b.copy(headerStates = newHeaders, rowStates = newRows))
    }

    // 셀 키 핸들러
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
                    } else {
                        addColumn()
                        pendingFocusRow = row
                        pendingFocusCol = colCount
                        focusCellCounter++
                    }
                    true
                }
                Key.Enter -> {
                    if (row < totalRows - 1) {
                        requestCellFocus(row + 1, col)
                    } else {
                        addRow()
                        pendingFocusRow = totalRows
                        pendingFocusCol = col
                        focusCellCounter++
                    }
                    true
                }
                else -> false
            }
        }

    // 외부 Column: hasFocus로 테이블 포커스 추적
    Column(
        modifier = modifier.onFocusChanged { tableFocused = it.hasFocus }
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 메인 테이블
            Column(
                modifier = Modifier
                    .weight(1f)
                    .border(0.5.dp, borderColor, shape)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    for ((col, cellState) in currentBlock.headerStates.withIndex()) {
                        if (col > 0) {
                            Box(Modifier.width(0.5.dp).fillMaxHeight().background(borderColor))
                        }
                        BasicTextField(
                            state = cellState,
                            modifier = Modifier
                                .weight(1f)
                                .then(cellKeyHandler(0, col, cellState))
                                .focusRequester(focusGrid[0][col])
                                .resetDocumentSelectionOnFocus(block.id)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
                            cursorBrush = cursorBrush,
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )
                    }
                }

                // Header-body separator
                Box(Modifier.height(0.5.dp).fillMaxWidth().background(borderColor))

                // Data rows
                for ((rowIdx, row) in currentBlock.rowStates.withIndex()) {
                    if (rowIdx > 0) {
                        Box(Modifier.height(0.5.dp).fillMaxWidth().background(borderColor))
                    }
                    val gridRow = rowIdx + 1
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    ) {
                        for ((col, cellState) in row.withIndex()) {
                            if (col > 0) {
                                Box(Modifier.width(0.5.dp).fillMaxHeight().background(borderColor))
                            }
                            BasicTextField(
                                state = cellState,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(cellKeyHandler(gridRow, col, cellState))
                                    .focusRequester(focusGrid[gridRow][col])
                                    .resetDocumentSelectionOnFocus(block.id)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                textStyle = textStyle,
                                cursorBrush = cursorBrush,
                                lineLimits = TextFieldLineLimits.SingleLine,
                            )
                        }
                    }
                }
            }

            // + 열 추가 버튼 (오른쪽, 영역 항상 유지 / 포커스 시에만 아이콘 표시)
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .then(if (tableFocused) Modifier.clickable { addColumn() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (tableFocused) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "열 추가",
                        modifier = Modifier.size(16.dp),
                        tint = borderColor,
                    )
                }
            }
        }

        // + 행 추가 버튼 (아래쪽, 영역 항상 유지 / 포커스 시에만 아이콘 표시)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .then(if (tableFocused) Modifier.clickable { addRow() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (tableFocused) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "행 추가",
                    modifier = Modifier.size(16.dp),
                    tint = borderColor,
                )
            }
        }
    }
}
