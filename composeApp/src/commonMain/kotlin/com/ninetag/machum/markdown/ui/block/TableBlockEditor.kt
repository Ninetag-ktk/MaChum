package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.ui.EditorQuickBarSelectionCommands
import com.ninetag.machum.markdown.ui.editorQuickBarTarget
import com.ninetag.machum.markdown.state.markdownOperationTextRange

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.service.MarkdownEditorDebugOptions
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.EditorInputTransformation
import com.ninetag.machum.markdown.state.CursorHint
import com.ninetag.machum.markdown.state.MarkdownInteraction
import com.ninetag.machum.markdown.state.RawMarkdownOutputTransformation
import com.ninetag.machum.markdown.state.MarkdownRenderContext
import com.ninetag.machum.markdown.state.MarkdownPreviewEdit
import com.ninetag.machum.markdown.state.MarkdownPreviewOffsetMapping
import com.ninetag.machum.markdown.state.TableCellCoordinate
import com.ninetag.machum.markdown.state.TableCellSelection
import com.ninetag.machum.markdown.state.TableSelectionKind
import com.ninetag.machum.markdown.state.columnSelection
import com.ninetag.machum.markdown.state.coerceTo
import com.ninetag.machum.markdown.state.deleteSelection
import com.ninetag.machum.markdown.state.forDeletion
import com.ninetag.machum.markdown.state.pasteClipboardTsv
import com.ninetag.machum.markdown.state.pasteTsv
import com.ninetag.machum.markdown.state.moveColumn
import com.ninetag.machum.markdown.state.moveRow
import com.ninetag.machum.markdown.state.rowSelection
import com.ninetag.machum.markdown.state.selectionAsTsv
import com.ninetag.machum.markdown.state.markdownPresentation
import com.ninetag.machum.markdown.state.markdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.markdownLinkCompletionEditingRequest
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionLookupKey
import com.ninetag.machum.markdown.state.markdownLinkCompletionLookupKey
import com.ninetag.machum.markdown.state.rawEditOffset
import com.ninetag.machum.markdown.state.MarkdownLinkRawEdit
import com.ninetag.machum.markdown.state.isOrdinaryLink
import com.ninetag.machum.markdown.ui.BlockNavigation
import com.ninetag.machum.markdown.ui.LocalMarkdownInteractionHandlers
import com.ninetag.machum.markdown.ui.MarkdownInteractionHandlers
import com.ninetag.machum.markdown.ui.applyMarkdownLinkCompletion
import com.ninetag.machum.markdown.ui.handleInternalLinkRawKey
import com.ninetag.machum.markdown.ui.LocalEditorFullBringIntoView
import com.ninetag.machum.markdown.ui.LocalEditorHistoryBoundary
import com.ninetag.machum.markdown.ui.MarkdownLinkCompletionDropdown
import com.ninetag.machum.markdown.ui.drawBlockDecorations
import com.ninetag.machum.markdown.ui.observeMarkdownLinkHover
import com.ninetag.machum.markdown.ui.visualBodyEndBounds
import com.ninetag.machum.markdown.ui.visualBounds
import com.ninetag.machum.markdown.ui.waitForMarkdownPointerRelease
import com.ninetag.machum.markdown.service.util.handleEditorKeyEvent
import com.ninetag.machum.external.clipEntryOf
import com.ninetag.machum.external.consumeClipboardText
import com.ninetag.machum.external.readClipboardText
import com.ninetag.machum.theme.platformUsesTouchUi

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * 테이블 블록 에디터.
 *
 * 그리드 레이아웃 + 셀별 BasicTextField.
 * 방향키와 Tab/Shift+Tab으로 셀 간 이동, 마지막 셀에서 Tab/Enter 시 열/행을 추가.
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
    cursorHint: CursorHint? = null,
    cursorHintRequestId: Long? = null,
    onCursorHintApplied: (Long) -> Unit = {},
    /** Undo/Redo 시 행·열 추가 뒤 남아 있던 지연 포커스 요청을 폐기하는 수명 세대. */
    focusEpoch: Any = Unit,
    onBlockChanged: (EditorBlock.Table) -> Unit = {},
    onRegisterBottomEntryFR: (FocusRequester?) -> Unit = {},
) {
    val shape = RoundedCornerShape(4.dp)
    val borderColor = styleConfig.blockquoteAccent
    val reorderIndicatorColor = MaterialTheme.colorScheme.primary

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

    // ↑ 진입 시 마지막 행 첫 열로 포커스되도록 bottomEntryFR 등록. grid 교체·블록 해제 시
    // 이전 requester를 제거해 부모가 stale requester 대신 기본 requester로 fallback하게 한다.
    DisposableEffect(focusGrid, onRegisterBottomEntryFR) {
        val registration = onRegisterBottomEntryFR
        registration(focusGrid[totalRows - 1][0])
        onDispose { registration(null) }
    }
    val bringIntoViewGrid = remember(totalRows, colCount) {
        Array(totalRows) { Array(colCount) { BringIntoViewRequester() } }
    }
    val cellLayoutSnapshots = remember { mutableStateMapOf<TextFieldState, TableCellLayoutSnapshot>() }
    val explicitLinkRawEdits = remember { mutableStateMapOf<TextFieldState, MarkdownLinkRawEdit>() }
    val focusedCells = remember { mutableStateMapOf<TextFieldState, Boolean>() }
    fun explicitRawLinkRange(cellState: TextFieldState): IntRange? = explicitLinkRawEdits[cellState]
        ?.range(cellState.text.toString(), cellState.selection.start, cellState.selection.end)
    fun openLinkRawEdit(cellState: TextFieldState, link: MarkdownInteraction) {
        if (!link.isOrdinaryLink()) return
        val source = cellState.text.toString()
        val range = tableCellMarkdownOffsetToSource(source, link.syntaxRange.first) until
            tableCellMarkdownOffsetToSource(source, link.syntaxRange.last + 1)
        explicitLinkRawEdits[cellState] = MarkdownLinkRawEdit.open(source, range)
    }
    fun refreshLinkRawEdit(cellState: TextFieldState) {
        if (explicitLinkRawEdits[cellState] == null) return
        val source = cellState.text.toString()
        val start = source.lastIndexOf("[[", cellState.selection.start)
        val end = source.indexOf("]]", start.coerceAtLeast(0))
        if (start >= 0 && end >= start) explicitLinkRawEdits[cellState] = MarkdownLinkRawEdit.open(source, start..end + 1)
    }
    val rowHeightsPx = remember(totalRows) { mutableStateMapOf<Int, Int>() }
    val fullBringIntoView = LocalEditorFullBringIntoView.current

    LaunchedEffect(focusEpoch, cursorHintRequestId, focusGrid) {
        val cell = cursorHint as? CursorHint.TableCell ?: return@LaunchedEffect
        val requestId = cursorHintRequestId ?: return@LaunchedEffect
        var fullRevealActive = false
        try {
            // History restores its viewport for two frames before focus-driven bring-into-view is
            // re-enabled. Match the parent block-focus delay so an off-screen cell can then scroll in.
            kotlinx.coroutines.delay(50.milliseconds)
            val row = cell.row.coerceIn(0, focusGrid.lastIndex)
            val column = cell.column.coerceIn(0, focusGrid[row].lastIndex)
            val cellState = if (row == 0) {
                currentBlock.headerStates[column]
            } else {
                currentBlock.rowStates[row - 1][column]
            }
            cell.offset?.coerceIn(0, cellState.text.length)?.let { offset ->
                cellState.edit { selection = androidx.compose.ui.text.TextRange(offset) }
            }
            var focused = runCatching { focusGrid[row][column].requestFocus() }.getOrDefault(false)
            if (!focused) {
                kotlinx.coroutines.delay(50.milliseconds)
                focused = runCatching { focusGrid[row][column].requestFocus() }.getOrDefault(false)
            }
            if (!focused) return@LaunchedEffect

            val sourceText = cellState.text.toString()
            val sourceOffset = cell.offset?.coerceIn(0, sourceText.length) ?: cellState.selection.end
            val expectedVisualText = tableCellRenderedText(sourceText, sourceOffset, styleConfig, explicitRawLinkRange(cellState))
            val layout = snapshotFlow {
                cellLayoutSnapshots[cellState]?.takeIf { snapshot ->
                    snapshot.sourceText == sourceText && snapshot.layout.layoutInput.text.text == expectedVisualText
                }
            }.filterNotNull().first().layout
            val visualOffset = tableCellSourceToVisualOffset(sourceText, sourceOffset, styleConfig, explicitRawLinkRange(cellState))
                .coerceIn(0, layout.layoutInput.text.length)
            val cursor = layout.getCursorRect(visualOffset)
            val line = layout.getLineForOffset(visualOffset)
            val lineRect = Rect(
                left = cursor.left,
                top = layout.getLineTop(line),
                right = cursor.right.coerceAtLeast(cursor.left + 1f),
                bottom = layout.getLineBottom(line),
            )
            fullBringIntoView?.value = true
            fullRevealActive = true
            withFrameNanos { }
            bringIntoViewGrid[row][column].bringIntoView(lineRect)
        } finally {
            if (fullRevealActive) fullBringIntoView?.value = false
            // A failed or cancelled attachment must not block later coordinator requests.
            onCursorHintApplied(requestId)
        }
    }

    // 테이블 포커스 추적: 외부 Column의 hasFocus 사용 (자식 셀 중 하나라도 포커스면 true)
    var tableFocused by remember { mutableStateOf(false) }
    var activeLinkRawEditCell by remember { mutableStateOf<TextFieldState?>(null) }
    var activeRow by remember { mutableStateOf(0) }
    var activeColumn by remember { mutableStateOf(0) }
    var hoveredRowHandle by remember { mutableStateOf<Int?>(null) }
    var hoveredColumnHandle by remember { mutableStateOf<Int?>(null) }
    var rowAddHovered by remember { mutableStateOf(false) }
    var columnAddHovered by remember { mutableStateOf(false) }
    var reorderPreview by remember(block.id, focusEpoch) { mutableStateOf<TableReorderPreview?>(null) }
    var tableCellSelection by remember(block.id, focusEpoch) { mutableStateOf<TableCellSelection?>(null) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val historyBoundary = LocalEditorHistoryBoundary.current
    // 행 추가 후 지연 포커스
    var pendingFocusRow by remember(focusEpoch) { mutableStateOf(-1) }
    var pendingFocusCol by remember(focusEpoch) { mutableStateOf(-1) }
    var focusCellCounter by remember(focusEpoch) { mutableStateOf(0) }
    var pendingFocusShape by remember(focusEpoch) { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(focusEpoch, focusCellCounter, focusGrid) {
        // A parent may apply structural changes later; keep the request until its grid exists.
        if (pendingFocusShape != null && pendingFocusShape != (focusGrid.size to focusGrid[0].size)) {
            return@LaunchedEffect
        }
        if (pendingFocusRow >= 0) {
            kotlinx.coroutines.delay(100.milliseconds)
            try {
                val r = pendingFocusRow.coerceIn(0, focusGrid.lastIndex)
                val c = pendingFocusCol.coerceIn(0, focusGrid[0].lastIndex)
                focusGrid[r][c].requestFocus()
            } catch (_: Exception) {}
            pendingFocusRow = -1
            pendingFocusShape = null
        }
    }

    fun revealCell(row: Int, col: Int) {
        val r = row.coerceIn(0, totalRows - 1)
        val c = col.coerceIn(0, colCount - 1)
        coroutineScope.launch {
            fullBringIntoView?.value = true
            try {
                withFrameNanos { }
                bringIntoViewGrid[r][c].bringIntoView()
            } finally {
                fullBringIntoView?.value = false
            }
        }
    }

    fun requestCellFocus(row: Int, col: Int) {
        val r = row.coerceIn(0, totalRows - 1)
        val c = col.coerceIn(0, colCount - 1)
        try {
            if (focusGrid[r][c].requestFocus()) revealCell(r, c)
        } catch (_: Exception) {}
    }

    fun requestHorizontalCellFocus(row: Int, col: Int, atStart: Boolean) {
        val cellState = if (row == 0) {
            currentBlock.headerStates[col]
        } else {
            currentBlock.rowStates[row - 1][col]
        }
        cellState.edit {
            selection = androidx.compose.ui.text.TextRange(if (atStart) 0 else length)
        }
        requestCellFocus(row, col)
    }

    fun applyTableSelectionChange(updated: EditorBlock.Table, selection: TableCellSelection?) {
        val updatedSelection = selection?.coerceTo(updated)
        if (updated.toMarkdown() != currentBlock.toMarkdown()) {
            historyBoundary?.markNextChangeAtomic()
        }
        tableCellSelection = updatedSelection
        val sameShape = updated.headerStates.size == currentBlock.headerStates.size &&
            updated.rowStates.size == currentBlock.rowStates.size
        if (sameShape) {
            currentBlock.headerStates.zip(updated.headerStates).forEach { (target, source) ->
                val value = source.text.toString()
                if (target.text.toString() != value) target.edit { replace(0, length, value) }
            }
            currentBlock.rowStates.zip(updated.rowStates).forEach { (targetRow, sourceRow) ->
                targetRow.zip(sourceRow).forEach { (target, source) ->
                    val value = source.text.toString()
                    if (target.text.toString() != value) target.edit { replace(0, length, value) }
                }
            }
        } else {
            pendingFocusShape = updated.rowStates.size + 1 to updated.headerStates.size
            onBlockChanged(updated)
        }
        // Structural changes can synchronously clear UI selection when the old cell loses focus.
        val focus = updatedSelection?.focus ?: return
        pendingFocusRow = focus.row
        pendingFocusCol = focus.column
        focusCellCounter++
    }

    fun deleteTableSelection(selection: TableCellSelection) {
        val deletion = selection.forDeletion(currentBlock)
        val updated = currentBlock.deleteSelection(selection)
        val structureChanged = deletion.kind != TableSelectionKind.Cells && updated !== currentBlock
        applyTableSelectionChange(
            updated,
            selection.takeIf { deletion.kind == TableSelectionKind.Cells },
        )
        if (structureChanged) {
            pendingFocusRow = when (deletion.kind) {
                TableSelectionKind.Rows -> deletion.firstRow.coerceAtMost(updated.rowStates.size)
                else -> activeRow.coerceAtMost(updated.rowStates.size)
            }
            pendingFocusCol = deletion.firstColumn.coerceAtMost(updated.headerStates.lastIndex)
            focusCellCounter++
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun cellContentReceiver(row: Int, column: Int): Modifier =
        if (!platformUsesTouchUi) Modifier else Modifier.contentReceiver { content ->
            consumeClipboardText(content) { text ->
                val anchor = tableCellSelection?.coerceTo(currentBlock)?.anchor
                    ?: TableCellCoordinate(row, column)
                val result = currentBlock.pasteClipboardTsv(anchor, text)
                if (result == null) false else {
                    applyTableSelectionChange(result.table, result.selection)
                    true
                }
            }
        }

    fun quickBarSelectionCommands(row: Int, column: Int, state: TextFieldState) =
        EditorQuickBarSelectionCommands(
            snapshot = {
                Triple(currentBlock.toMarkdown(), tableCellSelection, TableCellCoordinate(activeRow, activeColumn))
            },
            copy = {
                val selection = tableCellSelection?.coerceTo(currentBlock)
                if (selection != null) currentBlock.selectionAsTsv(selection) else {
                    val text = state.text.toString()
                    val range = markdownOperationTextRange(text, state.selection)
                    if (range.collapsed) null else text.substring(range.min, range.max)
                }
            },
            cut = {
                val selection = tableCellSelection?.coerceTo(currentBlock)
                if (selection != null) deleteTableSelection(selection) else {
                    val range = markdownOperationTextRange(state.text.toString(), state.selection)
                    if (!range.collapsed) historyBoundary?.markNextChangeAtomic()
                    state.edit {
                        replace(range.min, range.max, "")
                        this.selection = androidx.compose.ui.text.TextRange(range.min)
                    }
                }
            },
            paste = { text ->
                val selection = tableCellSelection?.coerceTo(currentBlock)
                if (selection != null || '\t' in text) {
                    val anchor = selection?.anchor ?: TableCellCoordinate(row, column)
                    currentBlock.pasteTsv(anchor, text)?.let { result ->
                        applyTableSelectionChange(result.table, result.selection)
                    }
                } else {
                    val range = state.selection
                    val previous = state.text.toString()
                    if (previous.replaceRange(range.min, range.max, text) != previous) {
                        historyBoundary?.markNextChangeAtomic()
                    }
                    state.edit {
                        replace(range.min, range.max, text)
                        this.selection = androidx.compose.ui.text.TextRange(range.min + text.length)
                    }
                }
            },
        )

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

    fun selectRows(anchor: Int, focus: Int) {
        val selection = currentBlock.rowSelection(anchor, focus) ?: return
        tableCellSelection = selection
    }

    fun selectColumns(anchor: Int, focus: Int) {
        val selection = currentBlock.columnSelection(anchor, focus) ?: return
        tableCellSelection = selection
    }

    fun moveRow(from: Int, to: Int) {
        val updated = currentBlock.moveRow(from, to)
        if (updated === currentBlock) {
            requestCellFocus(from, activeColumn)
            return
        }
        historyBoundary?.markNextChangeAtomic()
        tableCellSelection = updated.rowSelection(to, to)
        activeRow = to
        hoveredRowHandle = null
        onBlockChanged(updated)
        pendingFocusRow = to
        pendingFocusCol = activeColumn.coerceIn(updated.headerStates.indices)
        focusCellCounter++
    }

    fun moveColumn(from: Int, to: Int) {
        val updated = currentBlock.moveColumn(from, to)
        if (updated === currentBlock) {
            requestCellFocus(activeRow, from)
            return
        }
        historyBoundary?.markNextChangeAtomic()
        tableCellSelection = updated.columnSelection(to, to)
        activeColumn = to
        hoveredColumnHandle = null
        onBlockChanged(updated)
        pendingFocusRow = activeRow.coerceIn(0, updated.rowStates.size)
        pendingFocusCol = to
        focusCellCounter++
    }

    // 셀 키 핸들러
    fun cellKeyHandler(
        row: Int,
        col: Int,
        cellState: TextFieldState,
        layoutProvider: () -> TextLayoutResult?,
    ): Modifier =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val sel = cellState.selection
            val selectedCells = tableCellSelection?.coerceTo(currentBlock)
            val ctrlOrCmd = event.isCtrlPressed || event.isMetaPressed
            if (selectedCells == null && handleInternalLinkRawKey(
                    event, cellState, focusedCells[cellState] == true, false, explicitRawLinkRange(cellState),
                ) { text, range -> explicitLinkRawEdits[cellState] = MarkdownLinkRawEdit.open(text, range) }
            ) return@onPreviewKeyEvent true

            when {
                selectedCells != null && ctrlOrCmd && event.key == Key.C -> {
                    val tsv = currentBlock.selectionAsTsv(selectedCells)
                    coroutineScope.launch { clipboard.setClipEntry(clipEntryOf(tsv)) }
                    return@onPreviewKeyEvent true
                }
                selectedCells != null && ctrlOrCmd && event.key == Key.X -> {
                    val tableSnapshot = currentBlock.toMarkdown()
                    val cutTsv = currentBlock.selectionAsTsv(selectedCells)
                    coroutineScope.launch {
                        val copied = runCatching {
                            clipboard.setClipEntry(clipEntryOf(cutTsv))
                        }.isSuccess
                        if (
                            copied &&
                            currentBlock.toMarkdown() == tableSnapshot &&
                            tableCellSelection == selectedCells
                        ) {
                            deleteTableSelection(selectedCells)
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                ctrlOrCmd && event.key == Key.V -> {
                    val tableSnapshot = currentBlock.toMarkdown()
                    val activeCellSnapshot = TableCellCoordinate(row, col)
                    val pasteAnchor = selectedCells?.anchor ?: TableCellCoordinate(row, col)
                    coroutineScope.launch {
                        val text = runCatching { readClipboardText(clipboard) }.getOrNull()
                            ?: return@launch
                        if (
                            currentBlock.toMarkdown() != tableSnapshot ||
                            (selectedCells != null && tableCellSelection != selectedCells) ||
                            (selectedCells == null &&
                                TableCellCoordinate(activeRow, activeColumn) != activeCellSnapshot)
                        ) return@launch
                        val result = currentBlock.pasteTsv(pasteAnchor, text)
                            ?: return@launch
                        applyTableSelectionChange(result.table, result.selection)
                    }
                    return@onPreviewKeyEvent true
                }
                selectedCells != null && (event.key == Key.Delete || event.key == Key.Backspace) -> {
                    deleteTableSelection(selectedCells)
                    return@onPreviewKeyEvent true
                }
                selectedCells != null && event.key == Key.Escape -> {
                    tableCellSelection = null
                    return@onPreviewKeyEvent true
                }
                event.isShiftPressed && (selectedCells != null || sel.collapsed) && (
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                        event.key == Key.DirectionUp || event.key == Key.DirectionDown
                    ) -> {
                    val anchor = selectedCells?.anchor ?: TableCellCoordinate(row, col)
                    val from = selectedCells?.focus ?: anchor
                    val target = when (event.key) {
                        Key.DirectionLeft -> from.copy(column = (from.column - 1).coerceAtLeast(0))
                        Key.DirectionRight -> from.copy(column = (from.column + 1).coerceAtMost(colCount - 1))
                        Key.DirectionUp -> from.copy(row = (from.row - 1).coerceAtLeast(0))
                        else -> from.copy(row = (from.row + 1).coerceAtMost(totalRows - 1))
                    }
                    tableCellSelection = TableCellSelection(anchor, target, fromKeyboard = true)
                    requestCellFocus(target.row, target.column)
                    return@onPreviewKeyEvent true
                }
            }

            if (!event.isShiftPressed && selectedCells != null && (
                event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                    event.key == Key.DirectionUp || event.key == Key.DirectionDown
                )
            ) {
                tableCellSelection = null
            }
            when (event.key) {
                Key.DirectionRight -> {
                    if (sel.collapsed && sel.start >= cellState.text.length) {
                        nextTableColumnAtRightBoundary(col, colCount)?.let {
                            requestHorizontalCellFocus(row, it, atStart = true)
                        }
                        true
                    } else false
                }
                Key.DirectionLeft -> {
                    if (sel.collapsed && sel.start == 0) {
                        if (col > 0) requestHorizontalCellFocus(row, col - 1, atStart = false)
                        else if (row > 0) requestHorizontalCellFocus(row - 1, colCount - 1, atStart = false)
                        else navigation.focus.onMoveToPrevious()
                        true
                    } else false
                }
                Key.DirectionUp -> {
                    val layout = layoutProvider()
                    val visualOffset = tableCellSourceToVisualOffset(
                        source = cellState.text,
                        sourceOffset = sel.start,
                        styleConfig = styleConfig,
                        explicitRawLinkRange = explicitRawLinkRange(cellState),
                    )
                    val currentLine = layout?.getLineForOffset(visualOffset)
                    if (isAtTableCellVisualBoundary(sel.collapsed, currentLine, layout?.lineCount ?: 0, moveUp = true)) {
                        if (row > 0) requestCellFocus(row - 1, col)
                        else navigation.focus.onMoveToPrevious()
                        true
                    } else false
                }
                Key.DirectionDown -> {
                    val layout = layoutProvider()
                    val visualOffset = tableCellSourceToVisualOffset(
                        source = cellState.text,
                        sourceOffset = sel.start,
                        styleConfig = styleConfig,
                        explicitRawLinkRange = explicitRawLinkRange(cellState),
                    )
                    val currentLine = layout?.getLineForOffset(visualOffset)
                    if (isAtTableCellVisualBoundary(sel.collapsed, currentLine, layout?.lineCount ?: 0, moveUp = false)) {
                        if (row < totalRows - 1) requestCellFocus(row + 1, col)
                        else navigation.focus.onMoveToNext()
                        true
                    } else false
                }
                Key.Tab -> {
                    if (event.isShiftPressed) {
                        if (col > 0) requestCellFocus(row, col - 1)
                        else if (row > 0) requestCellFocus(row - 1, colCount - 1)
                        else navigation.focus.onMoveToPrevious()
                    } else {
                        if (col < colCount - 1) {
                            requestCellFocus(row, col + 1)
                        } else {
                            addColumn()
                            pendingFocusRow = row
                            pendingFocusCol = colCount
                            focusCellCounter++
                        }
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

    val horizontalScrollState = rememberScrollState()
    val horizontalScrollBoundary = remember { tableHorizontalScrollBoundary() }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val interactionHandlers = LocalMarkdownInteractionHandlers.current
    @Composable
    fun inputFor(cellState: TextFieldState): EditorInputTransformation = remember(cellState) {
        EditorInputTransformation(cellState) { text, range ->
            explicitLinkRawEdits[cellState] = MarkdownLinkRawEdit.open(text, range)
        }
    }
    @Composable
    fun outputFor(cellState: TextFieldState): Pair<OutputTransformation, RawMarkdownOutputTransformation> {
        val latestCellState by rememberUpdatedState(cellState)
        LaunchedEffect(cellState, cellState.text.toString(), cellState.selection) {
            if (explicitRawLinkRange(cellState) == null) explicitLinkRawEdits.remove(cellState)
        }
        DisposableEffect(cellState) {
            onDispose { explicitLinkRawEdits.remove(cellState) }
        }
        val markdown = remember(styleConfig, focusedCells[cellState] == true, interactionHandlers) {
            RawMarkdownOutputTransformation(
                styleConfig,
                MarkdownRenderContext.TableCell,
                interactionHandlers.isInternalLinkResolved,
                { explicitRawLinkRange(latestCellState)?.let { tableCellRawRangeToMarkdown(latestCellState.text, it) } },
            ).apply {
                isFocused = focusedCells[cellState] == true
            }
        }
        markdown.linkActionSlotStyle = inlineLinkActionSlotStyle(textStyle, density)
        val cellStyle = if (cellState in currentBlock.headerStates) textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)) else textStyle
        markdown.linkActionLineHeight = with(density) { textMeasurer.measure("\u2002\u2003", style = cellStyle).size.height.toSp() }
        val output = remember(markdown) {
            if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                println(
                    "MaChumIme|transform|kind=table-cell|state=${cellState.hashCode()}|" +
                        "generation=${markdown.hashCode()}|identity=${markdown.hashCode()}|" +
                        "raw=false|renderFocused=${focusedCells[cellState] == true}",
                )
            }
            OutputTransformation {
                tableCellLineBreakRegex.findAll(toString()).toList().asReversed().forEach { match ->
                    replace(match.range.first, match.range.last + 1, "\n")
                }
                with(markdown) { transformOutput() }
            }
        }
        return output to markdown
    }
    fun Modifier.linkInteractions(
        row: Int,
        column: Int,
        cellState: TextFieldState,
        markdownProvider: () -> RawMarkdownOutputTransformation,
        layoutProvider: () -> TextLayoutResult?,
        onRequestFocus: () -> Unit,
    ): Modifier = pointerInput(cellState) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) {
                return@awaitEachGesture
            }
            if (currentEvent.keyboardModifiers.isPointerShiftPressed) {
                down.consume()
                val anchor = tableCellSelection?.anchor
                    ?: if (tableFocused) {
                        TableCellCoordinate(activeRow, activeColumn)
                    } else {
                        TableCellCoordinate(row, column)
                    }
                tableCellSelection = TableCellSelection(
                    anchor = anchor,
                    focus = TableCellCoordinate(row, column),
                    kind = TableSelectionKind.Cells,
                )
                onRequestFocus()
                waitForMarkdownPointerRelease(
                    down.id,
                    down.position,
                    viewConfiguration.touchSlop,
                )
                return@awaitEachGesture
            }
            tableCellSelection = null
            val markdown = markdownProvider()
            val layout = layoutProvider()
            val interaction = layout?.let {
                markdown.interactionAtVisualPosition(
                    down.position,
                    it,
                    MarkdownEditorStyleTokens.taskCheckboxSize.toPx(),
                    MarkdownEditorStyleTokens.taskCheckboxHitSlop.toPx(),
                )
            }
            if (interaction == null) {
                waitForMarkdownPointerRelease(
                    down.id,
                    down.position,
                    viewConfiguration.touchSlop,
                )
                return@awaitEachGesture
            }

            if (interaction is MarkdownInteraction.TaskCheckbox || down.type == PointerType.Touch) down.consume()

            val release = waitForMarkdownPointerRelease(
                down.id,
                down.position,
                viewConfiguration.touchSlop,
            )
            val up = release?.change ?: return@awaitEachGesture
            val stayedInPlace = !release.exceededTouchSlop
            val isLongPress = stayedInPlace &&
                down.type == PointerType.Touch &&
                up.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
            if (isLongPress) {
                openLinkRawEdit(cellState, interaction)
                val sourceOffset = tableCellMarkdownOffsetToSource(
                    source = cellState.text,
                    markdownOffset = interaction.rawEditOffset(),
                )
                cellState.edit {
                    selection = androidx.compose.ui.text.TextRange(sourceOffset)
                }
                onRequestFocus()
            } else if (stayedInPlace) {
                when (interaction) {
                    is MarkdownInteraction.ExternalLink -> {
                        val sourceOffset = tableCellMarkdownOffsetToSource(
                            source = cellState.text,
                            markdownOffset = interaction.syntaxRange.last + 1,
                        )
                        cellState.edit {
                            selection = androidx.compose.ui.text.TextRange(sourceOffset)
                        }
                        interactionHandlers.openExternalLink(interaction.url)
                    }
                    is MarkdownInteraction.InternalLink -> {
                        val sourceOffset = tableCellMarkdownOffsetToSource(
                            source = cellState.text,
                            markdownOffset = interaction.syntaxRange.last + 1,
                        )
                        cellState.edit {
                            selection = androidx.compose.ui.text.TextRange(sourceOffset)
                        }
                        interactionHandlers.openInternalLink(interaction.target)
                    }
                    is MarkdownInteraction.TaskCheckbox -> {
                        val stateOffset = tableCellMarkdownOffsetToSource(
                            source = cellState.text,
                            markdownOffset = interaction.stateOffset,
                        )
                        cellState.edit {
                            replace(stateOffset, stateOffset + 1, if (interaction.checked) " " else "x")
                        }
                    }
                    is MarkdownInteraction.Embed -> {
                        val sourceOffset = tableCellMarkdownOffsetToSource(
                            source = cellState.text,
                            markdownOffset = interaction.syntaxRange.last + 1,
                        )
                        cellState.edit {
                            selection = androidx.compose.ui.text.TextRange(sourceOffset)
                        }
                        interactionHandlers.openInternalLink(interaction.target)
                    }
                }
            }
        }
    }

    // Measure the viewport separately so the inner table can grow beyond it and scroll locally.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val leftControlWidth = tableControlGutterWidth(platformUsesTouchUi)
        val rightControlWidth = maxOf(leftControlWidth, MarkdownEditorStyleTokens.blockActionSize)
        val topControlHeight = rightControlWidth
        val columnSeparatorWidth = MarkdownEditorStyleTokens.tableBorderWidth
        val columnWidths = measuredTableColumnWidths(currentBlock, styleConfig, textStyle, textMeasurer, density, reserveLinkActions = true)
        val columnWidthsPx = columnWidths.map { with(density) { it.toPx() } }
        val separatorWidthPx = with(density) { columnSeparatorWidth.toPx() }
        val rowSizesPx = List(totalRows) { rowHeightsPx[it]?.toFloat() ?: 1f }
        val tableContentWidth = tableContentWidth(columnWidths)
        val tableViewportWidth = tableViewportWidth(maxWidth, leftControlWidth, rightControlWidth)
        val scrollContentWidth = maxOf(tableContentWidth, tableViewportWidth)
        val rawButtonEndPadding = tableRawButtonEndPadding(
            totalWidth = maxWidth,
            leftControlWidth = leftControlWidth,
            rightControlWidth = rightControlWidth,
            tableContentWidth = tableContentWidth,
        )
        val selectedRow = tableCellSelection
            ?.takeIf { it.kind == TableSelectionKind.Rows }
            ?.focus
            ?.row
        val selectedColumn = tableCellSelection
            ?.takeIf { it.kind == TableSelectionKind.Columns }
            ?.focus
            ?.column
        val visibleRowHandle = if (platformUsesTouchUi) {
            selectedRow ?: activeRow.takeIf { tableFocused }
        } else {
            reorderPreview?.takeIf { it.kind == TableSelectionKind.Rows }?.from ?: hoveredRowHandle
        }
        val visibleColumnHandle = if (platformUsesTouchUi) {
            selectedColumn ?: activeColumn.takeIf { tableFocused }
        } else {
            reorderPreview?.takeIf { it.kind == TableSelectionKind.Columns }?.from ?: hoveredColumnHandle
        }
        val showRowAdd = if (platformUsesTouchUi) tableFocused else rowAddHovered
        val showColumnAdd = if (platformUsesTouchUi) tableFocused else columnAddHovered
        val tableCenterGap = tableLeftHandleOffset(tableViewportWidth, tableContentWidth)
        val tableRight = leftControlWidth + tableCenterGap + minOf(tableContentWidth, tableViewportWidth)

        // Keep the button outside the horizontal scroll node, but align it to the visible
        // table edge instead of the editor viewport edge.
        RawEditableBlock(
            focused = tableFocused,
            onRawEdit = navigation.mutation.onDissolveSelf,
            buttonEndPadding = rawButtonEndPadding,
            showRawEditButton = activeLinkRawEditCell == null,
            buttonSize = rightControlWidth,
            buttonTopPadding = 0.dp,
        ) {
        // Outer Column tracks focus; scroll is constrained to the table viewport only.
        Column(
            modifier = Modifier
                .onFocusChanged {
                    tableFocused = it.hasFocus
                    if (!it.hasFocus) tableCellSelection = null
                },
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Spacer(Modifier.width(leftControlWidth))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .consumeHorizontalWheelAtBoundary(horizontalScrollState)
                        .nestedScroll(horizontalScrollBoundary)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    Row(
                        modifier = Modifier.width(scrollContentWidth),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .width(tableContentWidth)
                                .drawWithContent {
                                    drawContent()
                                    val preview = reorderPreview ?: return@drawWithContent
                                    val strokeWidth = 2.dp.toPx()
                                    when (preview.kind) {
                                        TableSelectionKind.Columns -> {
                                            val x = tableReorderInsertionOffset(
                                                itemSizes = columnWidthsPx,
                                                separatorSize = separatorWidthPx,
                                                from = preview.from,
                                                to = preview.to,
                                            ) ?: return@drawWithContent
                                            val boundedX = x.coerceIn(strokeWidth / 2f, size.width - strokeWidth / 2f)
                                            drawLine(
                                                color = reorderIndicatorColor,
                                                start = Offset(boundedX, 0f),
                                                end = Offset(boundedX, size.height),
                                                strokeWidth = strokeWidth,
                                            )
                                        }

                                        TableSelectionKind.Rows -> {
                                            val rowOffset = tableReorderInsertionOffset(
                                                itemSizes = rowSizesPx,
                                                separatorSize = separatorWidthPx,
                                                from = preview.from,
                                                to = preview.to,
                                            ) ?: return@drawWithContent
                                            val y = topControlHeight.toPx() + rowOffset
                                            val boundedY = y.coerceIn(strokeWidth / 2f, size.height - strokeWidth / 2f)
                                            drawLine(
                                                color = reorderIndicatorColor,
                                                start = Offset(0f, boundedY),
                                                end = Offset(size.width, boundedY),
                                                strokeWidth = strokeWidth,
                                            )
                                        }

                                        TableSelectionKind.Cells -> Unit
                                    }
                                },
                        ) {
                            Row(modifier = Modifier.width(tableContentWidth).height(topControlHeight)) {
                                columnWidths.forEachIndexed { col, width ->
                                    if (col > 0) Spacer(Modifier.width(columnSeparatorWidth))
                                    val isSelected = tableCellSelection?.let {
                                        it.kind == TableSelectionKind.Columns && col in it.firstColumn..it.lastColumn
                                    } == true
                                    val visible = col == visibleColumnHandle
                                    Box(
                                        modifier = Modifier
                                            .width(width)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(leftControlWidth)
                                                .tableControlHover(
                                                    enabled = !platformUsesTouchUi,
                                                    onHoverChanged = { hovered ->
                                                        hoveredColumnHandle = if (hovered) {
                                                            col
                                                        } else {
                                                            hoveredColumnHandle.takeUnless { it == col }
                                                        }
                                                    },
                                                )
                                                .then(
                                                    if (visible) Modifier.tableReorderHandle(
                                                        kind = TableSelectionKind.Columns,
                                                        anchorIndex = col,
                                                        itemSizes = columnWidthsPx,
                                                        separatorSize = separatorWidthPx,
                                                        anchorHandleOffset = (
                                                            (columnWidthsPx[col] - with(density) { leftControlWidth.toPx() }) / 2f
                                                        ).coerceAtLeast(0f),
                                                        onPressed = {
                                                            reorderPreview = TableReorderPreview(
                                                                TableSelectionKind.Columns,
                                                                col,
                                                                col,
                                                            )
                                                            selectColumns(col, col)
                                                        },
                                                        onTargetChanged = { from, to ->
                                                            reorderPreview = TableReorderPreview(
                                                                TableSelectionKind.Columns,
                                                                from,
                                                                to,
                                                            )
                                                        },
                                                        onFinished = { from, to ->
                                                            reorderPreview = null
                                                            moveColumn(from, to)
                                                        },
                                                        onCancelled = { reorderPreview = null },
                                                    ) else Modifier,
                                                )
                                                .then(
                                                    if (visible || !platformUsesTouchUi) Modifier.semantics {
                                                        role = Role.Button
                                                        selected = isSelected
                                                        contentDescription = "${col + 1}열 선택 및 이동"
                                                        onClick {
                                                            selectColumns(col, col)
                                                            requestCellFocus(activeRow, col)
                                                            true
                                                        }
                                                    } else Modifier,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (visible) {
                                            Box(
                                                Modifier
                                                    .width(MarkdownEditorStyleTokens.tableControlWidth)
                                                    .height(MarkdownEditorStyleTokens.tableHandleThickness)
                                                    .background(borderColor, RoundedCornerShape(50)),
                                            )
                                            }
                                        }
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .width(tableContentWidth)
                                    .border(MarkdownEditorStyleTokens.tableBorderWidth, borderColor, shape),
                            ) {
                            Row(
                                modifier = Modifier
                                    .width(tableContentWidth)
                                    .height(IntrinsicSize.Min)
                                    .onSizeChanged { rowHeightsPx[0] = it.height },
                            ) {
                                for ((col, cellState) in currentBlock.headerStates.withIndex()) {
                                    if (col > 0) {
                                        Box(Modifier.width(columnSeparatorWidth).fillMaxHeight().background(borderColor))
                                    }
                                    val (outputTransformation, markdownTransformation) = outputFor(cellState)
                                    val currentMarkdownTransformation = rememberUpdatedState(markdownTransformation)
                                    var textLayoutResult by remember(cellState) { mutableStateOf<TextLayoutResult?>(null) }
                                    var hoveredLink by remember(cellState) { mutableStateOf<MarkdownInteraction?>(null) }
                                    var rawEditOverlayHovered by remember(cellState) { mutableStateOf(false) }
                                    val linkCompletion = rememberTableCellLinkCompletion(
                                        cellState = cellState,
                                        focused = focusedCells[cellState] == true,
                                        complete = interactionHandlers.completeInternalLink,
                                        handlers = interactionHandlers,
                                        isFocused = { focusedCells[cellState] == true },
                                        onApplied = { refreshLinkRawEdit(cellState) },
                                        explicitRawRange = explicitRawLinkRange(cellState),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(columnWidths[col])
                                            .tableCellDragSelection(
                                                start = TableCellCoordinate(0, col),
                                                columnSizes = columnWidthsPx,
                                                rowSizes = rowSizesPx,
                                                separatorSize = separatorWidthPx,
                                                enabled = !platformUsesTouchUi,
                                                onSelection = {
                                                    val collapseOffset = cellState.selection.start
                                                    if (!cellState.selection.collapsed) {
                                                        cellState.edit {
                                                            selection = androidx.compose.ui.text.TextRange(collapseOffset)
                                                        }
                                                    }
                                                    tableCellSelection = it
                                                },
                                            )
                                            .background(
                                                if (tableCellSelection?.contains(0, col) == true) {
                                                    styleConfig.selectionAccent
                                                } else {
                                                    androidx.compose.ui.graphics.Color.Transparent
                                                },
                                            )
                                            .padding(
                                                horizontal = MarkdownEditorStyleTokens.tableCellHorizontalPadding,
                                                vertical = MarkdownEditorStyleTokens.tableCellVerticalPadding,
                                            )
                                            .observeMarkdownLinkHover(
                                                restartKey = cellState,
                                                markdownProvider = { currentMarkdownTransformation.value },
                                                layoutProvider = { textLayoutResult },
                                                overlayHoveredProvider = { rawEditOverlayHovered },
                                                onLinkHovered = { interaction ->
                                                    hoveredLink = interaction
                                                    if (interaction != null) {
                                                        activeLinkRawEditCell = cellState
                                                    } else if (activeLinkRawEditCell === cellState) {
                                                        activeLinkRawEditCell = null
                                                    }
                                                },
                                            ),
                                    ) {
                                    BasicTextField(
                                        state = cellState,
                                        modifier = Modifier
                                             .fillMaxWidth()
                                             .then(linkCompletion.keyModifier)
                                             .then(cellKeyHandler(0, col, cellState) { textLayoutResult })
                                             .then(cellContentReceiver(0, col))
                                            .onPreviewKeyEvent { handleEditorKeyEvent(it, cellState) }
                                            .focusRequester(focusGrid[0][col])
                                            .editorQuickBarTarget(cellState,
                                                usesMarkdownPreview = true,
                                                selectionCommands = quickBarSelectionCommands(0, col, cellState))
                                                    .onFocusChanged {
                                                        if (!it.isFocused) explicitLinkRawEdits.remove(cellState)
                                                        if (focusedCells[cellState] != it.isFocused) {
                                                            focusedCells[cellState] = it.isFocused
                                                            if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                                                                println(
                                                                    "MaChumIme|focus|kind=table-cell|state=${cellState.hashCode()}|" +
                                                                        "focused=${it.isFocused}|row=0|col=$col",
                                                                )
                                                            }
                                                        }
                                                        if (it.isFocused) {
                                                            activeRow = 0
                                                            activeColumn = col
                                                        }
                                                    }
                                            .resetDocumentSelectionOnFocus(block.id)
                                            .bringIntoViewRequester(bringIntoViewGrid[0][col])
                                            .linkInteractions(
                                                row = 0,
                                                column = col,
                                                cellState = cellState,
                                                 markdownProvider = { currentMarkdownTransformation.value },
                                                 layoutProvider = { textLayoutResult },
                                                 onRequestFocus = { requestCellFocus(0, col) },
                                             )
                                             .drawBehind {
                                                textLayoutResult?.let { layout ->
                                                    drawBlockDecorations(
                                                        layout = layout,
                                                        blocks = emptyList(),
                                                        config = styleConfig,
                                                        inlineCodeRanges = markdownTransformation.inlineCodeRanges,
                                                        rawZones = markdownTransformation.currentRawZones,
                                                        taskCheckboxes = markdownTransformation.taskCheckboxes,
                                                    )
                                                }
                                            },
                                        textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
                                        cursorBrush = cursorBrush,
                                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = Int.MAX_VALUE),
                                        outputTransformation = outputTransformation,
                                        inputTransformation = inputFor(cellState),
                                        onTextLayout = {
                                            it()?.let { layout ->
                                                textLayoutResult = layout
                                                val sourceText = cellState.text.toString()
                                                val expected = tableCellRenderedText(
                                                    source = sourceText,
                                                    sourceOffset = cellState.selection.end,
                                                    styleConfig = styleConfig,
                                                    explicitRawLinkRange = explicitRawLinkRange(cellState),
                                                )
                                                if (layout.layoutInput.text.text == expected) {
                                                    cellLayoutSnapshots[cellState] = TableCellLayoutSnapshot(sourceText, layout)
                                                }
                                            }
                                         },
                                     )
                                        TableCellLinkCompletionDropdown(
                                            controller = linkCompletion,
                                            source = cellState.text.toString(),
                                            sourceOffset = cellState.selection.end,
                                            styleConfig = styleConfig,
                                            layout = textLayoutResult,
                                            width = columnWidths[col],
                                            explicitRawLinkRange = explicitRawLinkRange(cellState),
                                        )
                                        textLayoutResult?.let { layout ->
                                            currentMarkdownTransformation.value.externalLinkIndicators.forEach { indicator ->
                                                if (hoveredLink == indicator.interaction) return@forEach
                                                indicator.visualBounds(layout)?.let { rawBounds ->
                                                    ExternalLinkIndicatorOverlay(
                                                        slotBounds = rawBounds,
                                                        buttonSize = with(density) { rawBounds.height.toDp() },
                                                        onClick = {
                                                            val sourceOffset = tableCellMarkdownOffsetToSource(
                                                                source = cellState.text,
                                                                markdownOffset = indicator.interaction.syntaxRange.last + 1,
                                                            )
                                                            cellState.edit {
                                                                selection = androidx.compose.ui.text.TextRange(sourceOffset)
                                                            }
                                                            interactionHandlers.openExternalLink(indicator.interaction.url)
                                                        },
                                                        onHovered = {
                                                            hoveredLink = indicator.interaction
                                                            activeLinkRawEditCell = cellState
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                        hoveredLink?.let { link ->
                                            val transformation = currentMarkdownTransformation.value
                                            val layout = textLayoutResult ?: return@let
                                            val indicatorBounds = if (link is MarkdownInteraction.ExternalLink) {
                                                transformation.externalLinkIndicators
                                                    .firstOrNull { it.interaction == link }
                                                    ?.visualBounds(layout)
                                            } else null
                                            (indicatorBounds ?: link.visualBodyEndBounds(transformation, layout))?.let { rawBounds ->
                                                RawEditOverlay(
                                                    slotBounds = rawBounds,
                                                    buttonSize = with(density) { rawBounds.height.toDp() },
                                                    horizontalGap = 0.dp,
                                                    onClick = {
                                                        openLinkRawEdit(cellState, link)
                                                        val sourceOffset = tableCellMarkdownOffsetToSource(
                                                            source = cellState.text,
                                                            markdownOffset = link.rawEditOffset(),
                                                        )
                                                        cellState.edit {
                                                            selection = androidx.compose.ui.text.TextRange(sourceOffset)
                                                        }
                                                        requestCellFocus(0, col)
                                                        hoveredLink = null
                                                        if (activeLinkRawEditCell === cellState) activeLinkRawEditCell = null
                                                    },
                                                    onHoverChanged = { hovered ->
                                                        rawEditOverlayHovered = hovered
                                                        if (!hovered) {
                                                            hoveredLink = null
                                                            if (activeLinkRawEditCell === cellState) activeLinkRawEditCell = null
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Box(Modifier.height(MarkdownEditorStyleTokens.tableBorderWidth).fillMaxWidth().background(borderColor))

                            for ((rowIdx, row) in currentBlock.rowStates.withIndex()) {
                                if (rowIdx > 0) {
                                    Box(Modifier.height(MarkdownEditorStyleTokens.tableBorderWidth).fillMaxWidth().background(borderColor))
                                }
                                val gridRow = rowIdx + 1
                                Row(
                                    modifier = Modifier
                                        .width(tableContentWidth)
                                        .height(IntrinsicSize.Min)
                                        .onSizeChanged { rowHeightsPx[gridRow] = it.height },
                                ) {
                                    for ((col, cellState) in row.withIndex()) {
                                        if (col > 0) {
                                            Box(Modifier.width(columnSeparatorWidth).fillMaxHeight().background(borderColor))
                                        }
                                        val (outputTransformation, markdownTransformation) = outputFor(cellState)
                                        val currentMarkdownTransformation = rememberUpdatedState(markdownTransformation)
                                        var textLayoutResult by remember(cellState) { mutableStateOf<TextLayoutResult?>(null) }
                                        var hoveredLink by remember(cellState) { mutableStateOf<MarkdownInteraction?>(null) }
                                        var rawEditOverlayHovered by remember(cellState) { mutableStateOf(false) }
                                        val linkCompletion = rememberTableCellLinkCompletion(
                                            cellState = cellState,
                                            focused = focusedCells[cellState] == true,
                                            complete = interactionHandlers.completeInternalLink,
                                            handlers = interactionHandlers,
                                            isFocused = { focusedCells[cellState] == true },
                                            onApplied = { refreshLinkRawEdit(cellState) },
                                            explicitRawRange = explicitRawLinkRange(cellState),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .width(columnWidths[col])
                                                .tableCellDragSelection(
                                                    start = TableCellCoordinate(gridRow, col),
                                                    columnSizes = columnWidthsPx,
                                                    rowSizes = rowSizesPx,
                                                    separatorSize = separatorWidthPx,
                                                    enabled = !platformUsesTouchUi,
                                                    onSelection = {
                                                        val collapseOffset = cellState.selection.start
                                                        if (!cellState.selection.collapsed) {
                                                            cellState.edit {
                                                                selection = androidx.compose.ui.text.TextRange(collapseOffset)
                                                            }
                                                        }
                                                        tableCellSelection = it
                                                    },
                                                )
                                                .background(
                                                    if (tableCellSelection?.contains(gridRow, col) == true) {
                                                        styleConfig.selectionAccent
                                                    } else {
                                                        androidx.compose.ui.graphics.Color.Transparent
                                                    },
                                                )
                                                .padding(
                                                    horizontal = MarkdownEditorStyleTokens.tableCellHorizontalPadding,
                                                    vertical = MarkdownEditorStyleTokens.tableCellVerticalPadding,
                                                )
                                                .observeMarkdownLinkHover(
                                                    restartKey = cellState,
                                                    markdownProvider = { currentMarkdownTransformation.value },
                                                    layoutProvider = { textLayoutResult },
                                                    overlayHoveredProvider = { rawEditOverlayHovered },
                                                    onLinkHovered = { interaction ->
                                                        hoveredLink = interaction
                                                        if (interaction != null) {
                                                            activeLinkRawEditCell = cellState
                                                        } else if (activeLinkRawEditCell === cellState) {
                                                            activeLinkRawEditCell = null
                                                        }
                                                    },
                                                ),
                                        ) {
                                        BasicTextField(
                                            state = cellState,
                                            modifier = Modifier
                                                 .fillMaxWidth()
                                                 .then(linkCompletion.keyModifier)
                                                 .then(cellKeyHandler(gridRow, col, cellState) { textLayoutResult })
                                                 .then(cellContentReceiver(gridRow, col))
                                                .onPreviewKeyEvent { handleEditorKeyEvent(it, cellState) }
                                                .focusRequester(focusGrid[gridRow][col])
                                                 .editorQuickBarTarget(cellState,
                                                    usesMarkdownPreview = true,
                                                    selectionCommands = quickBarSelectionCommands(gridRow, col, cellState))
                                                    .onFocusChanged {
                                                            if (!it.isFocused) explicitLinkRawEdits.remove(cellState)
                                                        if (focusedCells[cellState] != it.isFocused) {
                                                            focusedCells[cellState] = it.isFocused
                                                            if (MarkdownEditorDebugOptions.logImeDiagnostics) {
                                                                println(
                                                                    "MaChumIme|focus|kind=table-cell|state=${cellState.hashCode()}|" +
                                                                        "focused=${it.isFocused}|row=$gridRow|col=$col",
                                                                )
                                                            }
                                                        }
                                                        if (it.isFocused) {
                                                            activeRow = gridRow
                                                            activeColumn = col
                                                        }
                                                    }
                                                .resetDocumentSelectionOnFocus(block.id)
                                                .bringIntoViewRequester(bringIntoViewGrid[gridRow][col])
                                                .linkInteractions(
                                                    row = gridRow,
                                                    column = col,
                                                    cellState = cellState,
                                                     markdownProvider = { currentMarkdownTransformation.value },
                                                     layoutProvider = { textLayoutResult },
                                                     onRequestFocus = { requestCellFocus(gridRow, col) },
                                                 )
                                                 .drawBehind {
                                                    textLayoutResult?.let { layout ->
                                                        drawBlockDecorations(
                                                            layout = layout,
                                                            blocks = emptyList(),
                                                            config = styleConfig,
                                                            inlineCodeRanges = markdownTransformation.inlineCodeRanges,
                                                            rawZones = markdownTransformation.currentRawZones,
                                                            taskCheckboxes = markdownTransformation.taskCheckboxes,
                                                        )
                                                    }
                                                },
                                            textStyle = textStyle,
                                            cursorBrush = cursorBrush,
                                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = Int.MAX_VALUE),
                                            outputTransformation = outputTransformation,
                                            inputTransformation = inputFor(cellState),
                                            onTextLayout = {
                                                it()?.let { layout ->
                                                    textLayoutResult = layout
                                                    val sourceText = cellState.text.toString()
                                                    val expected = tableCellRenderedText(
                                                        source = sourceText,
                                                        sourceOffset = cellState.selection.end,
                                                        styleConfig = styleConfig,
                                                        explicitRawLinkRange = explicitRawLinkRange(cellState),
                                                    )
                                                    if (layout.layoutInput.text.text == expected) {
                                                        cellLayoutSnapshots[cellState] = TableCellLayoutSnapshot(sourceText, layout)
                                                    }
                                                }
                                             },
                                         )
                                            TableCellLinkCompletionDropdown(
                                                controller = linkCompletion,
                                                source = cellState.text.toString(),
                                                sourceOffset = cellState.selection.end,
                                                styleConfig = styleConfig,
                                                layout = textLayoutResult,
                                                width = columnWidths[col],
                                                explicitRawLinkRange = explicitRawLinkRange(cellState),
                                            )
                                            textLayoutResult?.let { layout ->
                                                currentMarkdownTransformation.value.externalLinkIndicators.forEach { indicator ->
                                                    if (hoveredLink == indicator.interaction) return@forEach
                                                    indicator.visualBounds(layout)?.let { rawBounds ->
                                                        ExternalLinkIndicatorOverlay(
                                                            slotBounds = rawBounds,
                                                            buttonSize = with(density) { rawBounds.height.toDp() },
                                                            onClick = {
                                                                val sourceOffset = tableCellMarkdownOffsetToSource(
                                                                    source = cellState.text,
                                                                    markdownOffset = indicator.interaction.syntaxRange.last + 1,
                                                                )
                                                                cellState.edit {
                                                                    selection = androidx.compose.ui.text.TextRange(sourceOffset)
                                                                }
                                                                interactionHandlers.openExternalLink(indicator.interaction.url)
                                                            },
                                                            onHovered = {
                                                                hoveredLink = indicator.interaction
                                                                activeLinkRawEditCell = cellState
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                            hoveredLink?.let { link ->
                                                val transformation = currentMarkdownTransformation.value
                                                val layout = textLayoutResult ?: return@let
                                                val indicatorBounds = if (link is MarkdownInteraction.ExternalLink) {
                                                    transformation.externalLinkIndicators
                                                        .firstOrNull { it.interaction == link }
                                                        ?.visualBounds(layout)
                                                } else null
                                                (indicatorBounds ?: link.visualBodyEndBounds(transformation, layout))?.let { rawBounds ->
                                                    RawEditOverlay(
                                                        slotBounds = rawBounds,
                                                        buttonSize = with(density) { rawBounds.height.toDp() },
                                                        horizontalGap = 0.dp,
                                                        onClick = {
                                                            openLinkRawEdit(cellState, link)
                                                            val sourceOffset = tableCellMarkdownOffsetToSource(
                                                                source = cellState.text,
                                                                markdownOffset = link.rawEditOffset(),
                                                            )
                                                            cellState.edit {
                                                                selection = androidx.compose.ui.text.TextRange(sourceOffset)
                                                            }
                                                            requestCellFocus(gridRow, col)
                                                            hoveredLink = null
                                                            if (activeLinkRawEditCell === cellState) activeLinkRawEditCell = null
                                                        },
                                                        onHoverChanged = { hovered ->
                                                            rawEditOverlayHovered = hovered
                                                            if (!hovered) {
                                                                hoveredLink = null
                                                                if (activeLinkRawEditCell === cellState) activeLinkRawEditCell = null
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }

                Spacer(Modifier.width(rightControlWidth))
            }

            Box(
                modifier = Modifier
                    .width(tableCenterGap + leftControlWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopEnd,
            ) {
                Column(Modifier.width(leftControlWidth).fillMaxHeight()) {
                    Spacer(Modifier.height(topControlHeight))
                    repeat(totalRows) { rowIndex ->
                        if (rowIndex > 0) {
                            Box(
                                Modifier
                                    .height(MarkdownEditorStyleTokens.tableBorderWidth)
                                    .fillMaxWidth(),
                            )
                        }
                        val isSelected = tableCellSelection?.let {
                            it.kind == TableSelectionKind.Rows && rowIndex in it.firstRow..it.lastRow
                        } == true
                        val visible = rowIndex == visibleRowHandle
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(density) { (rowHeightsPx[rowIndex] ?: 0).toDp() }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(leftControlWidth)
                                    .tableControlHover(
                                        enabled = !platformUsesTouchUi,
                                        onHoverChanged = { hovered ->
                                            hoveredRowHandle = if (hovered) {
                                                rowIndex
                                            } else {
                                                hoveredRowHandle.takeUnless { it == rowIndex }
                                            }
                                        },
                                    )
                                    .then(
                                        if (visible) Modifier.tableReorderHandle(
                                            kind = TableSelectionKind.Rows,
                                            anchorIndex = rowIndex,
                                            itemSizes = rowSizesPx,
                                            separatorSize = separatorWidthPx,
                                            anchorHandleOffset = (
                                                (rowSizesPx[rowIndex] - with(density) { leftControlWidth.toPx() }) / 2f
                                            ).coerceAtLeast(0f),
                                            onPressed = {
                                                reorderPreview = TableReorderPreview(
                                                    TableSelectionKind.Rows,
                                                    rowIndex,
                                                    rowIndex,
                                                )
                                                selectRows(rowIndex, rowIndex)
                                            },
                                            onTargetChanged = { from, to ->
                                                reorderPreview = TableReorderPreview(
                                                    TableSelectionKind.Rows,
                                                    from,
                                                    to,
                                                )
                                            },
                                            onFinished = { from, to ->
                                                reorderPreview = null
                                                moveRow(from, to)
                                            },
                                            onCancelled = { reorderPreview = null },
                                        ) else Modifier,
                                    )
                                    .then(
                                        if (visible || !platformUsesTouchUi) Modifier.semantics {
                                            role = Role.Button
                                            selected = isSelected
                                            contentDescription = if (rowIndex == 0) {
                                                "헤더 행 선택 및 이동"
                                            } else {
                                                "${rowIndex}행 선택 및 이동"
                                            }
                                            onClick {
                                                selectRows(rowIndex, rowIndex)
                                                requestCellFocus(rowIndex, activeColumn)
                                                true
                                            }
                                        } else Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (visible) {
                                Box(
                                    Modifier
                                        .width(MarkdownEditorStyleTokens.tableHandleThickness)
                                        .height(MarkdownEditorStyleTokens.tableControlWidth)
                                        .background(borderColor, RoundedCornerShape(50)),
                                )
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(tableRight + rightControlWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopEnd,
            ) {
                Column(
                    modifier = Modifier
                        .width(rightControlWidth)
                        .padding(top = topControlHeight)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(rightControlWidth)
                            .tableControlHover(
                                enabled = !platformUsesTouchUi,
                                onHoverChanged = { columnAddHovered = it },
                            )
                            .then(
                                if (!platformUsesTouchUi) Modifier.semantics {
                                    role = Role.Button
                                    contentDescription = "열 추가"
                                    onClick {
                                        addColumn()
                                        true
                                    }
                                } else Modifier,
                            )
                            .then(if (showColumnAdd) Modifier.clickable { addColumn() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showColumnAdd) {
                            Box(
                                Modifier
                                    .width(MarkdownEditorStyleTokens.tableAddControlWidth)
                                    .height(MarkdownEditorStyleTokens.tableAddControlHeight)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(50),
                                    ),
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
                }
            }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(leftControlWidth),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(leftControlWidth)
                        .tableControlHover(
                            enabled = !platformUsesTouchUi,
                            onHoverChanged = { rowAddHovered = it },
                        )
                        .then(
                            if (!platformUsesTouchUi) Modifier.semantics {
                                role = Role.Button
                                contentDescription = "행 추가"
                                onClick {
                                    addRow()
                                    true
                                }
                            } else Modifier,
                        )
                        .then(if (showRowAdd) Modifier.clickable { addRow() } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showRowAdd) {
                        Box(
                            Modifier
                                .width(MarkdownEditorStyleTokens.tableAddControlWidth)
                                .height(MarkdownEditorStyleTokens.tableAddControlHeight)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50),
                                ),
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
                }
            }
        }
    }
    }
}

private fun Modifier.tableReorderHandle(
    kind: TableSelectionKind,
    anchorIndex: Int,
    itemSizes: List<Float>,
    separatorSize: Float,
    anchorHandleOffset: Float,
    onPressed: () -> Unit,
    onTargetChanged: (from: Int, to: Int) -> Unit,
    onFinished: (from: Int, to: Int) -> Unit,
    onCancelled: () -> Unit,
): Modifier = pointerInput(kind, anchorIndex, itemSizes, separatorSize, anchorHandleOffset) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) {
                return@awaitEachGesture
            }
            down.consume()
            onPressed()
            val startOffset = itemSizes.take(anchorIndex).sum() +
                separatorSize * anchorIndex +
                anchorHandleOffset
            var lastFocusIndex = anchorIndex
            var dragged = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!dragged) {
                    dragged = down.type == PointerType.Mouse ||
                        (change.position - down.position).getDistance() >= viewConfiguration.touchSlop
                    if (dragged) onTargetChanged(anchorIndex, anchorIndex)
                }
                if (!dragged) continue
                val position = if (kind == TableSelectionKind.Rows) change.position.y else change.position.x
                val focusIndex = tableAxisIndexAtPosition(
                    itemSizes = itemSizes,
                    separatorSize = separatorSize,
                    position = startOffset + position,
                )
                if (lastFocusIndex != focusIndex) {
                    lastFocusIndex = focusIndex
                    onTargetChanged(anchorIndex, focusIndex)
                }
                change.consume()
            }
            onFinished(anchorIndex, lastFocusIndex)
        }
    } finally {
        onCancelled()
    }
}

@Composable
private fun Modifier.tableControlHover(
    enabled: Boolean,
    onHoverChanged: (Boolean) -> Unit,
): Modifier {
    if (!enabled) return this
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        onHoverChanged(hovered)
    }
    return hoverable(interaction)
}

private fun Modifier.tableCellDragSelection(
    start: TableCellCoordinate,
    columnSizes: List<Float>,
    rowSizes: List<Float>,
    separatorSize: Float,
    enabled: Boolean,
    onSelection: (TableCellSelection) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(start, columnSizes, rowSizes, separatorSize) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) {
                return@awaitEachGesture
            }
            val startX = columnSizes.take(start.column).sum() + separatorSize * start.column
            val startY = rowSizes.take(start.row).sum() + separatorSize * start.row
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val target = TableCellCoordinate(
                    row = tableAxisIndexAtPosition(rowSizes, separatorSize, startY + change.position.y),
                    column = tableAxisIndexAtPosition(columnSizes, separatorSize, startX + change.position.x),
                )
                if (target != start) {
                    onSelection(TableCellSelection(start, target, TableSelectionKind.Cells))
                    change.consume()
                }
            }
        }
    }
}

internal fun tableAxisIndexAtPosition(
    itemSizes: List<Float>,
    separatorSize: Float,
    position: Float,
): Int {
    if (itemSizes.isEmpty()) return 0
    if (position <= 0f) return 0
    var end = 0f
    itemSizes.forEachIndexed { index, size ->
        end += size
        if (position < end + separatorSize / 2f) return index
        end += separatorSize
    }
    return itemSizes.lastIndex
}

internal fun tableReorderInsertionOffset(
    itemSizes: List<Float>,
    separatorSize: Float,
    from: Int,
    to: Int,
): Float? {
    if (from !in itemSizes.indices || to !in itemSizes.indices || from == to) return null
    val boundary = if (from < to) to + 1 else to
    return itemSizes.take(boundary).sum() + separatorSize * minOf(boundary, itemSizes.lastIndex)
}

internal fun tableControlGutterWidth(touchUi: Boolean): Dp = if (touchUi) 24.dp else 28.dp

internal fun tableViewportWidth(totalWidth: Dp, leftControlWidth: Dp, rightControlWidth: Dp): Dp =
    (totalWidth - leftControlWidth - rightControlWidth).coerceAtLeast(0.dp)

internal fun tableLeftHandleOffset(viewportWidth: Dp, tableContentWidth: Dp): Dp =
    (viewportWidth - tableContentWidth).coerceAtLeast(0.dp) / 2f

internal fun tableViewportWidth(totalWidth: Dp, sideControlWidth: Dp): Dp =
    tableViewportWidth(totalWidth, sideControlWidth, sideControlWidth)

internal fun nextTableColumnAtRightBoundary(column: Int, columnCount: Int): Int? =
    (column + 1).takeIf { it < columnCount }

internal fun tableRawButtonEndPadding(
    totalWidth: Dp,
    leftControlWidth: Dp,
    rightControlWidth: Dp,
    tableContentWidth: Dp,
): Dp {
    val viewportWidth = tableViewportWidth(totalWidth, leftControlWidth, rightControlWidth)
    val visibleTableWidth = minOf(tableContentWidth, viewportWidth)
    val tableRight = leftControlWidth + (viewportWidth - visibleTableWidth) / 2f + visibleTableWidth
    return (totalWidth - tableRight - rightControlWidth).coerceAtLeast(0.dp)
}

internal fun tableRawButtonEndPadding(
    totalWidth: Dp,
    sideControlWidth: Dp,
    tableContentWidth: Dp,
): Dp = tableRawButtonEndPadding(
    totalWidth = totalWidth,
    leftControlWidth = sideControlWidth,
    rightControlWidth = sideControlWidth,
    tableContentWidth = tableContentWidth,
)

private data class TableReorderPreview(
    val kind: TableSelectionKind,
    val from: Int,
    val to: Int,
)

private data class TableCellLinkCompletionController(
    val candidates: List<com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate>,
    val selectedIndex: Int,
    val keyModifier: Modifier,
    val apply: (Int) -> Unit,
)

@Composable
private fun rememberTableCellLinkCompletion(
    cellState: TextFieldState,
    focused: Boolean,
    complete: (com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest) ->
        List<com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate>,
    handlers: MarkdownInteractionHandlers,
    isFocused: () -> Boolean,
    onApplied: () -> Unit,
    explicitRawRange: IntRange?,
): TableCellLinkCompletionController {
    val scope = rememberCoroutineScope()
    val historyBoundary = LocalEditorHistoryBoundary.current
    val latestFocused by rememberUpdatedState(isFocused)
    val latestOnApplied by rememberUpdatedState(onApplied)
    var applying by remember(cellState) { mutableStateOf(false) }
    var applyEpoch by remember(cellState) { mutableStateOf(0L) }
    LaunchedEffect(focused) { if (!focused) applyEpoch++ }
    var dismissed by remember(cellState) {
        mutableStateOf<com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest?>(null)
    }
    var visibleCompletion by remember(cellState) {
        mutableStateOf<Pair<
            com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest,
            List<com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate>,
        >?>(null)
    }
    var lastCompletionLookupKey by remember(cellState) {
        mutableStateOf<MarkdownLinkCompletionLookupKey?>(null)
    }
    var lastCompletionRevision by remember(cellState) { mutableStateOf<Any?>(null) }
    val latestComplete by rememberUpdatedState(complete)
    val source = cellState.text.toString()
    val currentSelection = cellState.selection
    val rawCompletionRequest = markdownLinkCompletionEditingRequest(source, currentSelection, explicitRawRange)
    val currentCompletionRequest = rawCompletionRequest
        ?.takeUnless { it == dismissed }
    val currentCompletionLookupKey = currentCompletionRequest?.let {
        markdownLinkCompletionLookupKey(source, it)
    }
    val completionRevision = com.ninetag.machum.markdown.ui.LocalMarkdownCompletionRevision.current
    LaunchedEffect(focused, source, currentSelection, rawCompletionRequest, dismissed, completionRevision) {
        val revisionChanged = lastCompletionRevision != completionRevision
        if (revisionChanged) {
            lastCompletionRevision = completionRevision
        }
        if (dismissed != null && rawCompletionRequest != dismissed) {
            dismissed = null
            lastCompletionLookupKey = null
        } else if (!focused) {
            visibleCompletion = null
            lastCompletionLookupKey = null
        } else {
            if (!revisionChanged && currentCompletionLookupKey == lastCompletionLookupKey) return@LaunchedEffect
            // Keep editing immediate; debounce only the potentially expensive index lookup.
            kotlinx.coroutines.delay(500.milliseconds)
            val completionRequest = markdownLinkCompletionEditingRequest(source, currentSelection, explicitRawRange)
                ?.takeUnless { it == dismissed }
            val lookupKey = completionRequest?.let {
                markdownLinkCompletionLookupKey(source, it)
            }
            if (!revisionChanged && lookupKey == lastCompletionLookupKey) return@LaunchedEffect
            val completion = completionRequest
                ?.let { it to latestComplete(it) }
            lastCompletionLookupKey = lookupKey
            if (visibleCompletion != completion) visibleCompletion = completion
        }
    }
    val request = visibleCompletion?.first
    val visibleIsCurrent = rawCompletionRequest != null &&
        request == rawCompletionRequest &&
        markdownLinkCompletionLookupKey(source, request) == lastCompletionLookupKey
    val candidates = if (visibleIsCurrent) visibleCompletion?.second.orEmpty() else emptyList()
    var selectedIndex by remember(request, candidates) { mutableStateOf(0) }
    val apply: (Int) -> Unit = apply@{ index ->
        val active = request
        val candidate = candidates.getOrNull(index)
        if (!applying && active != null && candidate != null) {
            if (markdownLinkCompletionRequest(cellState.text.toString(), cellState.selection) != active) {
                return@apply
            }
            val epoch = applyEpoch
            applying = true
            scope.launch {
                try {
                    if (applyMarkdownLinkCompletion(
                            cellState, active, candidate, handlers,
                            isEditing = { latestFocused() && applyEpoch == epoch },
                            beforeSourceEdit = { historyBoundary?.markNextChangeAtomic() },
                        )) {
                        latestOnApplied()
                        dismissed = markdownLinkCompletionRequest(cellState.text.toString(), cellState.selection)
                        visibleCompletion = null
                    }
                } finally {
                    applying = false
                }
            }
        }
    }
    val keyModifier = Modifier.onPreviewKeyEvent { event ->
        val currentRequest = if (cellState.composition == null) {
            markdownLinkCompletionRequest(cellState.text.toString(), cellState.selection)
        } else null
        if (
            event.type != KeyEventType.KeyDown ||
            request == null ||
            currentRequest != request ||
            candidates.isEmpty()
        ) {
            return@onPreviewKeyEvent false
        }
        when (event.key) {
            Key.DirectionDown -> {
                selectedIndex = (selectedIndex + 1) % candidates.size
                true
            }
            Key.DirectionUp -> {
                selectedIndex = (selectedIndex - 1 + candidates.size) % candidates.size
                true
            }
            Key.Enter, Key.Tab -> {
                apply(selectedIndex)
                true
            }
            Key.Escape -> {
                applyEpoch++
                dismissed = request
                visibleCompletion = null
                true
            }
            else -> false
        }
    }
    return TableCellLinkCompletionController(candidates, selectedIndex, keyModifier, apply)
}

@Composable
private fun TableCellLinkCompletionDropdown(
    controller: TableCellLinkCompletionController,
    source: String,
    sourceOffset: Int,
    styleConfig: MarkdownStyleConfig,
    layout: TextLayoutResult?,
    width: Dp,
    explicitRawLinkRange: IntRange? = null,
) {
    if (controller.candidates.isEmpty() || layout == null) return
    val visualOffset = tableCellSourceToVisualOffset(source, sourceOffset, styleConfig, explicitRawLinkRange)
        .coerceIn(0, layout.layoutInput.text.length)
    MarkdownLinkCompletionDropdown(
        candidates = controller.candidates,
        selectedIndex = controller.selectedIndex,
        width = width,
        yOffsetPx = layout.getCursorRect(visualOffset).bottom.roundToInt(),
        onSelect = controller.apply,
    )
}

private val tableCellLineBreakRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

private data class TableCellLayoutSnapshot(
    val sourceText: String,
    val layout: TextLayoutResult,
)

internal fun tableCellVisualText(source: CharSequence): String =
    tableCellLineBreakRegex.replace(source, "\n")

private fun tableCellRawRangeToMarkdown(source: CharSequence, range: IntRange): IntRange {
    val edits = tableCellLineBreakRegex.findAll(source).map { MarkdownPreviewEdit(it.range, "\n") }.toList()
    val mapping = MarkdownPreviewOffsetMapping(edits)
    return mapping.sourceToVisual(range.first) until mapping.sourceToVisual(range.last + 1)
}

internal fun tableCellRenderedText(
    source: CharSequence,
    sourceOffset: Int,
    styleConfig: MarkdownStyleConfig,
    explicitRawLinkRange: IntRange? = null,
): String {
    val text = source.toString()
    val safeOffset = sourceOffset.coerceIn(0, text.length)
    val breakEdits = tableCellLineBreakRegex.findAll(text).map { match ->
        MarkdownPreviewEdit(match.range, "\n")
    }.toList()
    val breakMapping = MarkdownPreviewOffsetMapping(breakEdits)
    val brokenText = tableCellVisualText(text)
    val brokenOffset = breakMapping.sourceToVisual(safeOffset)
    return markdownPresentation(
        text = brokenText,
        context = MarkdownRenderContext.TableCell,
        config = styleConfig,
        isFocused = true,
        selectionStart = brokenOffset,
        selectionEnd = brokenOffset,
        explicitRawLinkRange = explicitRawLinkRange?.let { tableCellRawRangeToMarkdown(source, it) },
    ).visualText
}

/** Maps Markdown offsets after `<br>` preview replacement back to the persisted cell source. */
internal fun tableCellMarkdownOffsetToSource(
    source: CharSequence,
    markdownOffset: Int,
): Int {
    val text = source.toString()
    val breakEdits = tableCellLineBreakRegex.findAll(text).map { match ->
        MarkdownPreviewEdit(match.range, "\n")
    }.toList()
    val visualLength = tableCellVisualText(text).length
    return MarkdownPreviewOffsetMapping(breakEdits)
        .visualToSource(markdownOffset.coerceIn(0, visualLength))
        .coerceIn(0, text.length)
}

internal fun tableCellSourceToVisualOffset(
    source: CharSequence,
    sourceOffset: Int,
    styleConfig: MarkdownStyleConfig,
    explicitRawLinkRange: IntRange? = null,
): Int {
    val text = source.toString()
    val safeOffset = sourceOffset.coerceIn(0, text.length)
    val breakEdits = tableCellLineBreakRegex.findAll(text).map { match ->
        MarkdownPreviewEdit(match.range, "\n")
    }.toList()
    val breakMapping = MarkdownPreviewOffsetMapping(breakEdits)
    val brokenText = tableCellVisualText(text)
    val brokenOffset = breakMapping.sourceToVisual(safeOffset)
    val presentation = markdownPresentation(
        text = brokenText,
        context = MarkdownRenderContext.TableCell,
        config = styleConfig,
        isFocused = true,
        selectionStart = brokenOffset,
        selectionEnd = brokenOffset,
        explicitRawLinkRange = explicitRawLinkRange?.let { tableCellRawRangeToMarkdown(source, it) },
    )
    return MarkdownPreviewOffsetMapping(presentation.edits)
        .sourceToVisual(brokenOffset)
        .coerceIn(0, presentation.visualText.length)
}

internal fun isAtTableCellVisualBoundary(
    selectionCollapsed: Boolean,
    currentLine: Int?,
    lineCount: Int,
    moveUp: Boolean,
): Boolean {
    if (!selectionCollapsed || currentLine == null || lineCount <= 0) return false
    return if (moveUp) currentLine == 0 else currentLine == lineCount - 1
}

/**
 * ScrollState skips nested-scroll dispatch when a wheel starts at its horizontal boundary.
 * Claim only outward wheel input at that boundary before the document pager can start scrolling.
 */
internal fun Modifier.consumeHorizontalWheelAtBoundary(
    scrollState: androidx.compose.foundation.ScrollState,
): Modifier =
    pointerInput(scrollState) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val unconsumed = event.changes.filterNot { it.isConsumed }
            if (unconsumed.isEmpty()) continue
            val delta = unconsumed.fold(Offset.Zero) { total, change -> total + change.scrollDelta }
            val shiftPressed = event.keyboardModifiers.isPointerShiftPressed
            val axisDelta = horizontalWheelDelta(delta, shiftPressed)
            val pointsOutsideBoundary = when {
                axisDelta > 0f -> !scrollState.canScrollForward
                axisDelta < 0f -> !scrollState.canScrollBackward
                else -> false
            }
            if (pointsOutsideBoundary) {
                unconsumed.forEach { it.consume() }
            }
        }
    }
}

internal fun isHorizontalWheelIntent(delta: Offset, shiftPressed: Boolean): Boolean =
    shiftPressed || abs(delta.x) > abs(delta.y)

internal fun horizontalWheelDelta(delta: Offset, shiftPressed: Boolean): Float = when {
    !isHorizontalWheelIntent(delta, shiftPressed) -> 0f
    abs(delta.x) > abs(delta.y) -> delta.x
    else -> delta.y
}
