package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/**
 * Undo/Redo history가 사용하는 UI 비의존 불변 블록 값.
 *
 * [TextFieldState] 참조를 보관하지 않으므로 snapshot 이후 원본 편집과 독립적이다. 복원할 때 기존
 * block ID와 타입이 같으면 현재 TextFieldState를 재사용하고, 새 필드에만 state를 만든다.
 */
internal sealed interface EditorBlockSnapshot {
    val id: String

    data class Text(
        override val id: String,
        val text: String,
        val selection: TextRange,
        val rawMode: Boolean,
        val rawOrigin: RawOrigin?,
    ) : EditorBlockSnapshot

    data class Callout(
        override val id: String,
        val calloutType: String,
        val title: String,
        val titleSelection: TextRange,
        val bodyBlocks: List<EditorBlockSnapshot>,
        val foldMarker: Char?,
        val titlePrefix: String,
        val bodyLinePrefixes: List<String>,
    ) : EditorBlockSnapshot

    data class Code(
        override val id: String,
        val language: String,
        val code: String,
        val selection: TextRange,
        val openingIndent: String,
        val fence: String,
        val openingSuffix: String,
        val closingIndent: String,
        val closingFence: String,
        val closingSuffix: String,
        val emptyContentLineCount: Int,
    ) : EditorBlockSnapshot

    data class Table(
        override val id: String,
        val headers: List<String>,
        val headerSelections: List<TextRange>,
        val rows: List<List<String>>,
        val rowSelections: List<List<TextRange>>,
        val delimiterCells: List<String>,
        val leadingPipe: Boolean,
        val trailingPipe: Boolean,
        val delimiterLeadingPipe: Boolean,
        val delimiterTrailingPipe: Boolean,
        val rowLeadingPipes: List<Boolean>,
        val rowTrailingPipes: List<Boolean>,
        val headerSource: TableLineSource?,
        val delimiterSource: TableLineSource?,
        val rowSources: List<TableLineSource?>,
    ) : EditorBlockSnapshot

    data class HorizontalRule(
        override val id: String,
    ) : EditorBlockSnapshot

    data class Embed(
        override val id: String,
        val target: String,
    ) : EditorBlockSnapshot
}

/** 한 시점의 editor 문서와 cursor 상태. 파일 저장용이 아니라 in-memory history 전용이다. */
internal data class EditorDocumentSnapshot(
    val blocks: List<EditorBlockSnapshot>,
)

/** Undo/Redo restoration target. A null hint keeps the selection already stored in the snapshot. */
internal data class EditorHistoryFocusTarget(
    val endpoint: SelectionEndpoint,
    val cursorHint: CursorHint? = null,
)

internal fun captureEditorDocumentSnapshot(
    blocks: List<EditorBlock>,
): EditorDocumentSnapshot = EditorDocumentSnapshot(
    blocks = blocks.toEditorBlockSnapshots(),
)

/**
 * Finds the first editable field changed between two history states in document order.
 * Structural edits fall back to the nearest editable field that survives in [target].
 */
internal fun findEditorHistoryFocusTarget(
    current: EditorDocumentSnapshot,
    target: EditorDocumentSnapshot,
): EditorHistoryFocusTarget? = findChangedFocusTarget(
    current = current.blocks,
    target = target.blocks,
    containerPath = emptyList(),
)

internal fun EditorDocumentSnapshot.restoreBlocks(): List<EditorBlock> = blocks.toEditorBlocks()

/**
 * Undo/Redo 중에도 같은 편집 필드의 Compose 노드와 포커스를 유지하도록 호환되는 state를 재사용한다.
 * 구조가 바뀐 블록이나 새 표 셀처럼 대응되는 필드가 없을 때만 새 state를 만든다.
 */
internal fun EditorDocumentSnapshot.restoreBlocks(
    reusing: List<EditorBlock>,
): List<EditorBlock> {
    val currentById = reusing.associateBy(EditorBlock::id)
    return blocks.map { snapshot -> snapshot.restore(currentById[snapshot.id]) }
}

internal fun List<EditorBlock>.toEditorBlockSnapshots(): List<EditorBlockSnapshot> =
    map(EditorBlock::toSnapshot)

internal fun List<EditorBlockSnapshot>.toEditorBlocks(): List<EditorBlock> =
    map(EditorBlockSnapshot::restore)

private fun EditorBlock.toSnapshot(): EditorBlockSnapshot = when (this) {
    is EditorBlock.Text -> EditorBlockSnapshot.Text(
        id = id,
        text = textFieldState.text.toString(),
        selection = textFieldState.selection,
        rawMode = rawMode,
        rawOrigin = rawOrigin,
    )
    is EditorBlock.Callout -> EditorBlockSnapshot.Callout(
        id = id,
        calloutType = calloutType,
        title = titleState.text.toString(),
        titleSelection = titleState.selection,
        bodyBlocks = bodyBlocks.toEditorBlockSnapshots(),
        foldMarker = foldMarker,
        titlePrefix = titlePrefix,
        bodyLinePrefixes = bodyLinePrefixes,
    )
    is EditorBlock.Code -> EditorBlockSnapshot.Code(
        id = id,
        language = language,
        code = codeState.text.toString(),
        selection = codeState.selection,
        openingIndent = openingIndent,
        fence = fence,
        openingSuffix = openingSuffix,
        closingIndent = closingIndent,
        closingFence = closingFence,
        closingSuffix = closingSuffix,
        emptyContentLineCount = emptyContentLineCount,
    )
    is EditorBlock.Table -> EditorBlockSnapshot.Table(
        id = id,
        headers = headerStates.map { it.text.toString() },
        headerSelections = headerStates.map { it.selection },
        rows = rowStates.map { row -> row.map { it.text.toString() } },
        rowSelections = rowStates.map { row -> row.map { it.selection } },
        delimiterCells = delimiterCells,
        leadingPipe = leadingPipe,
        trailingPipe = trailingPipe,
        delimiterLeadingPipe = delimiterLeadingPipe,
        delimiterTrailingPipe = delimiterTrailingPipe,
        rowLeadingPipes = rowLeadingPipes,
        rowTrailingPipes = rowTrailingPipes,
        headerSource = headerSource,
        delimiterSource = delimiterSource,
        rowSources = rowSources,
    )
    is EditorBlock.HorizontalRule -> EditorBlockSnapshot.HorizontalRule(id)
    is EditorBlock.Embed -> EditorBlockSnapshot.Embed(id, target)
}

private fun EditorBlockSnapshot.restore(): EditorBlock = when (this) {
    is EditorBlockSnapshot.Text -> EditorBlock.Text(
        id = id,
        textFieldState = TextFieldState(text, selection),
        rawMode = rawMode,
        rawOrigin = rawOrigin,
    )
    is EditorBlockSnapshot.Callout -> EditorBlock.Callout(
        id = id,
        calloutType = calloutType,
        titleState = TextFieldState(title, titleSelection),
        bodyBlocks = bodyBlocks.toEditorBlocks(),
        foldMarker = foldMarker,
        titlePrefix = titlePrefix,
        bodyLinePrefixes = bodyLinePrefixes,
    )
    is EditorBlockSnapshot.Code -> EditorBlock.Code(
        id = id,
        language = language,
        codeState = TextFieldState(code, selection),
        openingIndent = openingIndent,
        fence = fence,
        openingSuffix = openingSuffix,
        closingIndent = closingIndent,
        closingFence = closingFence,
        closingSuffix = closingSuffix,
        emptyContentLineCount = emptyContentLineCount,
    )
    is EditorBlockSnapshot.Table -> EditorBlock.Table(
        id = id,
        headerStates = headers.mapIndexed { index, text ->
            TextFieldState(text, headerSelections[index])
        },
        rowStates = rows.mapIndexed { rowIndex, row ->
            row.mapIndexed { columnIndex, text ->
                TextFieldState(text, rowSelections[rowIndex][columnIndex])
            }
        },
        delimiterCells = delimiterCells,
        leadingPipe = leadingPipe,
        trailingPipe = trailingPipe,
        delimiterLeadingPipe = delimiterLeadingPipe,
        delimiterTrailingPipe = delimiterTrailingPipe,
        rowLeadingPipes = rowLeadingPipes,
        rowTrailingPipes = rowTrailingPipes,
        headerSource = headerSource,
        delimiterSource = delimiterSource,
        rowSources = rowSources,
    )
    is EditorBlockSnapshot.HorizontalRule -> EditorBlock.HorizontalRule(id)
    is EditorBlockSnapshot.Embed -> EditorBlock.Embed(id, target)
}

private fun EditorBlockSnapshot.restore(current: EditorBlock?): EditorBlock = when {
    this is EditorBlockSnapshot.Text && current is EditorBlock.Text -> current.copy(
        textFieldState = current.textFieldState.restore(text, selection),
        rawMode = rawMode,
        rawOrigin = rawOrigin,
    )
    this is EditorBlockSnapshot.Callout && current is EditorBlock.Callout -> current.copy(
        calloutType = calloutType,
        titleState = current.titleState.restore(title, titleSelection),
        bodyBlocks = EditorDocumentSnapshot(bodyBlocks).restoreBlocks(reusing = current.bodyBlocks),
        foldMarker = foldMarker,
        titlePrefix = titlePrefix,
        bodyLinePrefixes = bodyLinePrefixes,
    )
    this is EditorBlockSnapshot.Code && current is EditorBlock.Code -> current.copy(
        language = language,
        codeState = current.codeState.restore(code, selection),
        openingIndent = openingIndent,
        fence = fence,
        openingSuffix = openingSuffix,
        closingIndent = closingIndent,
        closingFence = closingFence,
        closingSuffix = closingSuffix,
        emptyContentLineCount = emptyContentLineCount,
    )
    this is EditorBlockSnapshot.Table && current is EditorBlock.Table -> current.copy(
        headerStates = headers.mapIndexed { column, text ->
            current.headerStates.getOrNull(column)?.restore(text, headerSelections[column])
                ?: TextFieldState(text, headerSelections[column])
        },
        rowStates = rows.mapIndexed { row, cells ->
            cells.mapIndexed { column, text ->
                current.rowStates.getOrNull(row)?.getOrNull(column)
                    ?.restore(text, rowSelections[row][column])
                    ?: TextFieldState(text, rowSelections[row][column])
            }
        },
        delimiterCells = delimiterCells,
        leadingPipe = leadingPipe,
        trailingPipe = trailingPipe,
        delimiterLeadingPipe = delimiterLeadingPipe,
        delimiterTrailingPipe = delimiterTrailingPipe,
        rowLeadingPipes = rowLeadingPipes,
        rowTrailingPipes = rowTrailingPipes,
        headerSource = headerSource,
        delimiterSource = delimiterSource,
        rowSources = rowSources,
    )
    this is EditorBlockSnapshot.HorizontalRule && current is EditorBlock.HorizontalRule -> current
    this is EditorBlockSnapshot.Embed && current is EditorBlock.Embed -> current.copy(target = target)
    else -> restore()
}

private fun TextFieldState.restore(text: String, selection: TextRange): TextFieldState = apply {
    if (this.text.toString() == text && this.selection == selection) return@apply
    edit {
        if (toString() != text) replace(0, length, text)
        this.selection = selection
    }
}

private fun findChangedFocusTarget(
    current: List<EditorBlockSnapshot>,
    target: List<EditorBlockSnapshot>,
    containerPath: List<String>,
): EditorHistoryFocusTarget? {
    val commonSize = minOf(current.size, target.size)
    for (index in 0 until commonSize) {
        val before = current[index]
        val after = target[index]
        if (before.id != after.id || before::class != after::class) {
            return nearestEditableTarget(target, index, containerPath)
        }
        changedFocusTarget(before, after, containerPath)?.let { return it }
    }
    return if (current.size != target.size) {
        nearestEditableTarget(target, commonSize, containerPath)
    } else {
        null
    }
}

private fun changedFocusTarget(
    current: EditorBlockSnapshot,
    target: EditorBlockSnapshot,
    containerPath: List<String>,
): EditorHistoryFocusTarget? = when {
    current is EditorBlockSnapshot.Text && target is EditorBlockSnapshot.Text -> {
        if (
            current.text != target.text ||
            current.rawMode != target.rawMode ||
            current.rawOrigin != target.rawOrigin
        ) {
            target.textFocusTarget(
                containerPath = containerPath,
                offset = if (current.text != target.text) {
                    restoredCursorOffset(current.text, target.text, current.selection.end)
                } else {
                    target.selection.end
                },
            )
        } else null
    }
    current is EditorBlockSnapshot.Callout && target is EditorBlockSnapshot.Callout -> {
        if (
            current.title != target.title ||
            current.calloutType != target.calloutType ||
            current.foldMarker != target.foldMarker ||
            current.titlePrefix != target.titlePrefix
        ) {
            target.titleFocusTarget(containerPath)
        } else {
            findChangedFocusTarget(
                current = current.bodyBlocks,
                target = target.bodyBlocks,
                containerPath = containerPath + target.id,
            ) ?: if (current.bodyLinePrefixes != target.bodyLinePrefixes) {
                target.firstEditableTarget(containerPath)
            } else null
        }
    }
    current is EditorBlockSnapshot.Code && target is EditorBlockSnapshot.Code -> {
        if (
            current.code != target.code ||
            current.language != target.language ||
            current.openingIndent != target.openingIndent ||
            current.fence != target.fence ||
            current.openingSuffix != target.openingSuffix ||
            current.closingIndent != target.closingIndent ||
            current.closingFence != target.closingFence ||
            current.closingSuffix != target.closingSuffix ||
            current.emptyContentLineCount != target.emptyContentLineCount
        ) target.codeFocusTarget(containerPath) else null
    }
    current is EditorBlockSnapshot.Table && target is EditorBlockSnapshot.Table ->
        changedTableFocusTarget(current, target, containerPath)
    current is EditorBlockSnapshot.Embed && target is EditorBlockSnapshot.Embed -> null
    current is EditorBlockSnapshot.HorizontalRule && target is EditorBlockSnapshot.HorizontalRule -> null
    else -> target.firstEditableTarget(containerPath)
}

private fun changedTableFocusTarget(
    current: EditorBlockSnapshot.Table,
    target: EditorBlockSnapshot.Table,
    containerPath: List<String>,
): EditorHistoryFocusTarget? {
    val commonHeaders = minOf(current.headers.size, target.headers.size)
    for (column in 0 until commonHeaders) {
        if (current.headers[column] != target.headers[column]) {
            return target.tableCellFocusTarget(
                containerPath = containerPath,
                row = 0,
                column = column,
                offset = restoredCursorOffset(
                    current = current.headers[column],
                    target = target.headers[column],
                    currentCursor = current.headerSelections[column].end,
                ),
            )
        }
    }
    if (current.headers.size != target.headers.size) {
        return target.tableCellFocusTarget(
            containerPath,
            row = 0,
            column = target.headers.lastIndex.coerceAtLeast(0),
        )
    }
    val commonRows = minOf(current.rows.size, target.rows.size)
    for (row in 0 until commonRows) {
        val commonColumns = minOf(current.rows[row].size, target.rows[row].size)
        for (column in 0 until commonColumns) {
            if (current.rows[row][column] != target.rows[row][column]) {
                return target.tableCellFocusTarget(
                    containerPath = containerPath,
                    row = row + 1,
                    column = column,
                    offset = restoredCursorOffset(
                        current = current.rows[row][column],
                        target = target.rows[row][column],
                        currentCursor = current.rowSelections[row][column].end,
                    ),
                )
            }
        }
        if (current.rows[row].size != target.rows[row].size) {
            return target.tableCellFocusTarget(
                containerPath,
                row = row + 1,
                column = target.rows[row].lastIndex.coerceAtLeast(0),
            )
        }
    }
    if (current.rows.size != target.rows.size) {
        return target.tableCellFocusTarget(
            containerPath,
            row = target.rows.lastIndex.coerceAtLeast(-1) + 1,
            column = 0,
        )
    }
    val metadataChanged =
        current.delimiterCells != target.delimiterCells ||
            current.leadingPipe != target.leadingPipe ||
            current.trailingPipe != target.trailingPipe ||
            current.delimiterLeadingPipe != target.delimiterLeadingPipe ||
            current.delimiterTrailingPipe != target.delimiterTrailingPipe ||
            current.rowLeadingPipes != target.rowLeadingPipes ||
            current.rowTrailingPipes != target.rowTrailingPipes ||
            current.headerSource != target.headerSource ||
            current.delimiterSource != target.delimiterSource ||
            current.rowSources != target.rowSources
    return if (metadataChanged) target.firstEditableTarget(containerPath) else null
}

private fun nearestEditableTarget(
    target: List<EditorBlockSnapshot>,
    preferredIndex: Int,
    containerPath: List<String>,
): EditorHistoryFocusTarget? {
    for (index in preferredIndex.coerceAtLeast(0) until target.size) {
        target[index].firstEditableTarget(containerPath)?.let { return it }
    }
    for (index in minOf(preferredIndex - 1, target.lastIndex) downTo 0) {
        target[index].lastEditableTarget(containerPath)?.let { return it }
    }
    return null
}

private fun EditorBlockSnapshot.firstEditableTarget(
    containerPath: List<String>,
): EditorHistoryFocusTarget? = when (this) {
    is EditorBlockSnapshot.Text -> textFocusTarget(containerPath)
    is EditorBlockSnapshot.Callout -> titleFocusTarget(containerPath)
    is EditorBlockSnapshot.Code -> codeFocusTarget(containerPath)
    is EditorBlockSnapshot.Table -> tableCellFocusTarget(containerPath, row = 0, column = 0)
    is EditorBlockSnapshot.HorizontalRule,
    is EditorBlockSnapshot.Embed,
    -> null
}

private fun EditorBlockSnapshot.lastEditableTarget(
    containerPath: List<String>,
): EditorHistoryFocusTarget? = when (this) {
    is EditorBlockSnapshot.Text -> textFocusTarget(containerPath)
    is EditorBlockSnapshot.Callout -> bodyBlocks.asReversed().firstNotNullOfOrNull {
        it.lastEditableTarget(containerPath + id)
    } ?: titleFocusTarget(containerPath)
    is EditorBlockSnapshot.Code -> codeFocusTarget(containerPath)
    is EditorBlockSnapshot.Table -> {
        val lastRow = rows.lastIndex + 1
        val lastColumn = rows.lastOrNull()?.lastIndex ?: headers.lastIndex
        tableCellFocusTarget(containerPath, lastRow.coerceAtLeast(0), lastColumn.coerceAtLeast(0))
    }
    is EditorBlockSnapshot.HorizontalRule,
    is EditorBlockSnapshot.Embed,
    -> null
}

private fun EditorBlockSnapshot.Text.textFocusTarget(
    containerPath: List<String>,
    offset: Int = selection.end,
): EditorHistoryFocusTarget = EditorHistoryFocusTarget(
    endpoint = SelectionEndpoint(containerPath, id, offset),
    cursorHint = CursorHint.RestoredSelection(offset),
)

/** Cursor boundary in [target] nearest to the changed text, independent of stale selection state. */
internal fun restoredCursorOffset(
    current: String,
    target: String,
    currentCursor: Int? = null,
): Int {
    val commonLimit = minOf(current.length, target.length)
    var prefix = 0
    while (prefix < commonLimit && current[prefix] == target[prefix]) prefix++

    var currentSuffixStart = current.length
    var targetSuffixStart = target.length
    while (
        currentSuffixStart > prefix &&
        targetSuffixStart > prefix &&
        current[currentSuffixStart - 1] == target[targetSuffixStart - 1]
    ) {
        currentSuffixStart--
        targetSuffixStart--
    }
    val currentChangedLength = currentSuffixStart - prefix
    val targetChangedLength = targetSuffixStart - prefix
    currentCursor?.coerceIn(0, current.length)?.let { cursor ->
        val candidateStart = cursor - currentChangedLength
        if (
            candidateStart >= 0 &&
            candidateStart + targetChangedLength <= target.length &&
            current.regionMatches(0, target, 0, candidateStart) &&
            current.regionMatches(
                cursor,
                target,
                candidateStart + targetChangedLength,
                current.length - cursor,
            )
        ) {
            return candidateStart + targetChangedLength
        }
    }
    return targetSuffixStart
}

private fun EditorBlockSnapshot.Callout.titleFocusTarget(
    containerPath: List<String>,
): EditorHistoryFocusTarget = EditorHistoryFocusTarget(
    endpoint = SelectionEndpoint(containerPath, id, titleSelection.end),
)

private fun EditorBlockSnapshot.Code.codeFocusTarget(
    containerPath: List<String>,
): EditorHistoryFocusTarget = EditorHistoryFocusTarget(
    endpoint = SelectionEndpoint(containerPath, id, selection.end),
)

private fun EditorBlockSnapshot.Table.tableCellFocusTarget(
    containerPath: List<String>,
    row: Int,
    column: Int,
    offset: Int? = null,
): EditorHistoryFocusTarget? {
    if (headers.isEmpty()) return null
    val safeRow = row.coerceIn(0, rows.size)
    val rowSize = if (safeRow == 0) headers.size else rows[safeRow - 1].size
    if (rowSize == 0) return null
    val safeColumn = column.coerceIn(0, rowSize - 1)
    val selection = if (safeRow == 0) {
        headerSelections[safeColumn]
    } else {
        rowSelections[safeRow - 1][safeColumn]
    }
    return EditorHistoryFocusTarget(
        endpoint = SelectionEndpoint(containerPath, id, SelectionEndpoint.ATOMIC_START),
        cursorHint = CursorHint.TableCell(safeRow, safeColumn, offset ?: selection.end),
    )
}
