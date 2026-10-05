package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState

/** A table coordinate where row 0 is the header and rows 1..n are data rows. */
internal data class TableCellCoordinate(
    val row: Int,
    val column: Int,
)

/** Directional rectangular selection. Anchor is stable while focus follows keyboard/mouse input. */
internal data class TableCellSelection(
    val anchor: TableCellCoordinate,
    val focus: TableCellCoordinate,
    val kind: TableSelectionKind = TableSelectionKind.Cells,
    val fromKeyboard: Boolean = false,
) {
    val firstRow: Int get() = minOf(anchor.row, focus.row)
    val lastRow: Int get() = maxOf(anchor.row, focus.row)
    val firstColumn: Int get() = minOf(anchor.column, focus.column)
    val lastColumn: Int get() = maxOf(anchor.column, focus.column)

    fun contains(row: Int, column: Int): Boolean =
        row in firstRow..lastRow && column in firstColumn..lastColumn
}

internal enum class TableSelectionKind { Cells, Rows, Columns }

internal fun TableCellSelection.coerceTo(table: EditorBlock.Table): TableCellSelection? {
    val rowCount = table.rowStates.size + 1
    val columnCount = table.headerStates.size
    if (rowCount <= 0 || columnCount <= 0) return null
    fun TableCellCoordinate.coerce() = TableCellCoordinate(
        row = row.coerceIn(0, rowCount - 1),
        column = column.coerceIn(0, columnCount - 1),
    )
    val safeAnchor = anchor.coerce()
    val safeFocus = focus.coerce()
    return when (kind) {
        TableSelectionKind.Cells -> copy(anchor = safeAnchor, focus = safeFocus)
        TableSelectionKind.Rows -> {
            TableCellSelection(
                anchor = TableCellCoordinate(safeAnchor.row, 0),
                focus = TableCellCoordinate(safeFocus.row, columnCount - 1),
                kind = kind,
            )
        }
        TableSelectionKind.Columns -> TableCellSelection(
            anchor = TableCellCoordinate(0, safeAnchor.column),
            focus = TableCellCoordinate(rowCount - 1, safeFocus.column),
            kind = kind,
        )
    }
}

internal fun EditorBlock.Table.rowSelection(anchorRow: Int, focusRow: Int): TableCellSelection? {
    if (headerStates.isEmpty()) return null
    return TableCellSelection(
        anchor = TableCellCoordinate(anchorRow.coerceIn(0, rowStates.size), 0),
        focus = TableCellCoordinate(focusRow.coerceIn(0, rowStates.size), headerStates.lastIndex),
        kind = TableSelectionKind.Rows,
    )
}

internal fun EditorBlock.Table.columnSelection(anchorColumn: Int, focusColumn: Int): TableCellSelection? {
    if (headerStates.isEmpty()) return null
    return TableCellSelection(
        anchor = TableCellCoordinate(0, anchorColumn.coerceIn(headerStates.indices)),
        focus = TableCellCoordinate(rowStates.size, focusColumn.coerceIn(headerStates.indices)),
        kind = TableSelectionKind.Columns,
    )
}

internal fun EditorBlock.Table.moveDataRow(fromIndex: Int, toIndex: Int): EditorBlock.Table {
    if (fromIndex !in rowStates.indices || toIndex !in rowStates.indices) return this
    return moveRow(fromIndex + 1, toIndex + 1)
}

/** Moves a visual row, including the header at index 0. The first resulting row remains the Markdown header. */
internal fun EditorBlock.Table.moveRow(fromIndex: Int, toIndex: Int): EditorBlock.Table {
    val rows = listOf(headerStates) + rowStates
    if (fromIndex !in rows.indices || toIndex !in rows.indices || fromIndex == toIndex) return this
    val leadingPipes = listOf(leadingPipe) +
        List(rowStates.size) { rowLeadingPipes.getOrElse(it) { leadingPipe } }
    val trailingPipes = listOf(trailingPipe) +
        List(rowStates.size) { rowTrailingPipes.getOrElse(it) { trailingPipe } }
    val sources = listOf(headerSource) + List(rowStates.size) { rowSources.getOrNull(it) }
    val movedRows = rows.moveItem(fromIndex, toIndex)
    val movedLeadingPipes = leadingPipes.moveItem(fromIndex, toIndex)
    val movedTrailingPipes = trailingPipes.moveItem(fromIndex, toIndex)
    val movedSources = sources.moveItem(fromIndex, toIndex)
    return copy(
        headerStates = movedRows.first(),
        rowStates = movedRows.drop(1),
        leadingPipe = movedLeadingPipes.first(),
        trailingPipe = movedTrailingPipes.first(),
        rowLeadingPipes = movedLeadingPipes.drop(1),
        rowTrailingPipes = movedTrailingPipes.drop(1),
        headerSource = movedSources.first(),
        rowSources = movedSources.drop(1),
    )
}

internal fun EditorBlock.Table.moveColumn(fromIndex: Int, toIndex: Int): EditorBlock.Table {
    if (fromIndex !in headerStates.indices || toIndex !in headerStates.indices || fromIndex == toIndex) return this
    return copy(
        headerStates = headerStates.moveItem(fromIndex, toIndex),
        rowStates = rowStates.map { row -> row.moveItem(fromIndex, toIndex) },
        delimiterCells = delimiterCells.moveItem(fromIndex, toIndex),
        // Column order no longer matches any original raw line.
        headerSource = null,
        delimiterSource = null,
        rowSources = List(rowStates.size) { null },
    )
}

internal fun EditorBlock.Table.selectionAsTsv(selection: TableCellSelection): String {
    val range = selection.coerceTo(this) ?: return ""
    return (range.firstRow..range.lastRow).joinToString("\n") { row ->
        (range.firstColumn..range.lastColumn).joinToString("\t") { column ->
            cellText(row, column)
        }
    }
}

internal fun EditorBlock.Table.clearSelection(selection: TableCellSelection): EditorBlock.Table {
    val range = selection.coerceTo(this) ?: return this
    return replaceCellTexts { row, column, current ->
        if (range.contains(row, column)) "" else current
    }
}

/** Only keyboard rectangles covering one complete axis opt into structural deletion. */
internal fun TableCellSelection.forDeletion(table: EditorBlock.Table): TableCellSelection {
    val range = coerceTo(table) ?: return this
    if (kind != TableSelectionKind.Cells || !fromKeyboard) return range
    val allRows = range.firstRow == 0 && range.lastRow == table.rowStates.size
    val allColumns = range.firstColumn == 0 && range.lastColumn == table.headerStates.lastIndex
    return range.copy(kind = when {
        allColumns && !allRows -> TableSelectionKind.Rows
        allRows && !allColumns -> TableSelectionKind.Columns
        else -> TableSelectionKind.Cells
    })
}

/** Reuses handle deletion for eligible keyboard ranges; other rectangles only clear content. */
internal fun EditorBlock.Table.deleteSelection(selection: TableCellSelection): EditorBlock.Table {
    val deletion = selection.forDeletion(this)
    return when (deletion.kind) {
        TableSelectionKind.Cells -> clearSelection(selection)
        TableSelectionKind.Rows -> {
            val range = deletion.coerceTo(this) ?: return this
            val rows = listOf(headerStates) + rowStates
            val retained = rows.indices.filterNot { it in range.firstRow..range.lastRow }
            if (retained.isEmpty()) return this
            val leadingPipes = listOf(leadingPipe) +
                List(rowStates.size) { rowLeadingPipes.getOrElse(it) { leadingPipe } }
            val trailingPipes = listOf(trailingPipe) +
                List(rowStates.size) { rowTrailingPipes.getOrElse(it) { trailingPipe } }
            val sources = listOf(headerSource) + List(rowStates.size) { rowSources.getOrNull(it) }
            copy(
                headerStates = rows[retained.first()],
                rowStates = retained.drop(1).map(rows::get),
                leadingPipe = leadingPipes[retained.first()],
                trailingPipe = trailingPipes[retained.first()],
                rowLeadingPipes = retained.drop(1).map(leadingPipes::get),
                rowTrailingPipes = retained.drop(1).map(trailingPipes::get),
                headerSource = sources[retained.first()],
                rowSources = retained.drop(1).map(sources::get),
            )
        }
        TableSelectionKind.Columns -> {
            val range = deletion.coerceTo(this) ?: return this
            val selectedCount = range.lastColumn - range.firstColumn + 1
            val firstRemovedColumn = if (selectedCount >= headerStates.size) 1 else range.firstColumn
            (range.lastColumn downTo firstRemovedColumn)
                .fold(this) { table, column -> table.removeColumn(column) ?: table }
        }
    }
}

internal data class TablePasteResult(
    val table: EditorBlock.Table,
    val selection: TableCellSelection,
)

/** Native cell paste retains ordinary text editing unless the payload has TSV columns. */
internal fun EditorBlock.Table.pasteClipboardTsv(
    anchor: TableCellCoordinate,
    clipboardText: String,
): TablePasteResult? = if ('\t' in clipboardText) pasteTsv(anchor, clipboardText) else null

internal fun EditorBlock.Table.pasteTsv(
    anchor: TableCellCoordinate,
    clipboardText: String,
): TablePasteResult? {
    if (clipboardText.isEmpty()) return null
    val pastedRows = clipboardText
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .let { rows -> if (rows.size > 1 && rows.last().isEmpty()) rows.dropLast(1) else rows }
        .map { it.split('\t') }
    if (pastedRows.isEmpty() || pastedRows.all { it.isEmpty() }) return null

    val safeAnchor = TableCellCoordinate(
        row = anchor.row.coerceAtLeast(0),
        column = anchor.column.coerceAtLeast(0),
    )
    val requiredRows = maxOf(rowStates.size + 1, safeAnchor.row + pastedRows.size)
    val pastedColumnCount = pastedRows.maxOfOrNull { it.size } ?: 1
    val requiredColumns = maxOf(headerStates.size, safeAnchor.column + pastedColumnCount)

    val values = MutableList(requiredRows) { row ->
        MutableList(requiredColumns) { column ->
            if (row <= rowStates.size && column < headerStates.size) cellText(row, column) else ""
        }
    }
    pastedRows.forEachIndexed { rowOffset, row ->
        row.forEachIndexed { columnOffset, value ->
            values[safeAnchor.row + rowOffset][safeAnchor.column + columnOffset] = value
        }
    }

    val expandedColumns = requiredColumns != headerStates.size
    val requiredDataRows = requiredRows - 1
    val updated = copy(
        headerStates = values.first().map(::TextFieldState),
        rowStates = values.drop(1).map { row -> row.map(::TextFieldState) },
        delimiterCells = delimiterCells.take(requiredColumns) +
            List((requiredColumns - delimiterCells.size).coerceAtLeast(0)) { "---" },
        rowLeadingPipes = rowLeadingPipes.take(requiredDataRows) +
            List((requiredDataRows - rowLeadingPipes.size).coerceAtLeast(0)) { leadingPipe },
        rowTrailingPipes = rowTrailingPipes.take(requiredDataRows) +
            List((requiredDataRows - rowTrailingPipes.size).coerceAtLeast(0)) { trailingPipe },
        headerSource = if (expandedColumns) null else headerSource,
        delimiterSource = if (expandedColumns) null else delimiterSource,
        rowSources = if (expandedColumns) {
            List(requiredDataRows) { null }
        } else {
            rowSources.take(requiredDataRows) +
                List((requiredDataRows - rowSources.size).coerceAtLeast(0)) { null }
        },
    )
    val focus = TableCellCoordinate(
        row = safeAnchor.row + pastedRows.lastIndex,
        column = safeAnchor.column + pastedColumnCount - 1,
    )
    return TablePasteResult(
        table = updated,
        selection = TableCellSelection(safeAnchor, focus),
    )
}

private fun EditorBlock.Table.replaceCellTexts(
    transform: (row: Int, column: Int, current: String) -> String,
): EditorBlock.Table = copy(
    headerStates = headerStates.mapIndexed { column, state ->
        TextFieldState(transform(0, column, state.text.toString()))
    },
    rowStates = rowStates.mapIndexed { row, states ->
        states.mapIndexed { column, state ->
            TextFieldState(transform(row + 1, column, state.text.toString()))
        }
    },
)

private fun EditorBlock.Table.cellText(row: Int, column: Int): String =
    if (row == 0) {
        headerStates.getOrNull(column)?.text?.toString().orEmpty()
    } else {
        rowStates.getOrNull(row - 1)?.getOrNull(column)?.text?.toString().orEmpty()
    }

private fun <T> List<T>.moveItem(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
