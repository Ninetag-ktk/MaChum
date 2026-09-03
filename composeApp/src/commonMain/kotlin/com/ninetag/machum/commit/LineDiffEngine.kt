package com.ninetag.machum.commit

internal object LineDiffEngine {
    private const val MAX_LCS_CELLS = 4_000_000L
    private const val DEFAULT_CONTEXT_LINES = 3
    private const val MAX_DISPLAY_LINES = 2_000

    fun build(
        change: CommitChange,
        oldContent: String?,
        newContent: String?,
        contextLines: Int = DEFAULT_CONTEXT_LINES,
    ): FileLineDiff {
        require(contextLines >= 0) { "contextLines must not be negative" }
        val oldLines = oldContent.toDiffLines()
        val newLines = newContent.toDiffLines()
        val exact = oldLines.size.toLong() * newLines.size <= MAX_LCS_CELLS
        val raw = if (exact) exactDiff(oldLines, newLines) else approximateDiff(oldLines, newLines)
        val contextual = withContext(raw, contextLines)
        val truncated = contextual.size > MAX_DISPLAY_LINES
        val visible = if (truncated) {
            contextual.take(MAX_DISPLAY_LINES - 1) + LineDiffLine(
                kind = LineDiffKind.OMITTED,
                text = "나머지 ${contextual.size - MAX_DISPLAY_LINES + 1}줄 생략",
            )
        } else {
            contextual
        }
        return FileLineDiff(
            change = change,
            lines = visible,
            isTruncated = truncated,
            isApproximate = !exact,
        )
    }

    private fun exactDiff(oldLines: List<String>, newLines: List<String>): List<LineDiffLine> {
        val lcs = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (oldIndex in oldLines.lastIndex downTo 0) {
            for (newIndex in newLines.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (oldLines[oldIndex] == newLines[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<LineDiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < oldLines.size || newIndex < newLines.size) {
            when {
                oldIndex < oldLines.size &&
                    newIndex < newLines.size &&
                    oldLines[oldIndex] == newLines[newIndex] -> {
                    result += LineDiffLine(
                        kind = LineDiffKind.CONTEXT,
                        text = oldLines[oldIndex],
                        oldLineNumber = oldIndex + 1,
                        newLineNumber = newIndex + 1,
                    )
                    oldIndex += 1
                    newIndex += 1
                }
                newIndex < newLines.size &&
                    (oldIndex == oldLines.size || lcs[oldIndex][newIndex + 1] > lcs[oldIndex + 1][newIndex]) -> {
                    result += LineDiffLine(
                        kind = LineDiffKind.ADDED,
                        text = newLines[newIndex],
                        newLineNumber = newIndex + 1,
                    )
                    newIndex += 1
                }
                else -> {
                    result += LineDiffLine(
                        kind = LineDiffKind.DELETED,
                        text = oldLines[oldIndex],
                        oldLineNumber = oldIndex + 1,
                    )
                    oldIndex += 1
                }
            }
        }
        return result
    }

    private fun approximateDiff(oldLines: List<String>, newLines: List<String>): List<LineDiffLine> {
        var prefix = 0
        val commonLimit = minOf(oldLines.size, newLines.size)
        while (prefix < commonLimit && oldLines[prefix] == newLines[prefix]) prefix += 1

        var suffix = 0
        while (
            suffix < commonLimit - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) {
            suffix += 1
        }

        return buildList {
            repeat(prefix) { index ->
                add(LineDiffLine(LineDiffKind.CONTEXT, oldLines[index], index + 1, index + 1))
            }
            for (index in prefix until oldLines.size - suffix) {
                add(LineDiffLine(LineDiffKind.DELETED, oldLines[index], oldLineNumber = index + 1))
            }
            for (index in prefix until newLines.size - suffix) {
                add(LineDiffLine(LineDiffKind.ADDED, newLines[index], newLineNumber = index + 1))
            }
            repeat(suffix) { offset ->
                val oldIndex = oldLines.size - suffix + offset
                val newIndex = newLines.size - suffix + offset
                add(
                    LineDiffLine(
                        LineDiffKind.CONTEXT,
                        oldLines[oldIndex],
                        oldIndex + 1,
                        newIndex + 1,
                    ),
                )
            }
        }
    }

    private fun withContext(lines: List<LineDiffLine>, contextLines: Int): List<LineDiffLine> {
        val changedIndices = lines.indices.filter { lines[it].kind != LineDiffKind.CONTEXT }
        if (changedIndices.isEmpty()) return emptyList()
        val keep = BooleanArray(lines.size)
        changedIndices.forEach { changed ->
            val start = maxOf(0, changed - contextLines)
            val end = minOf(lines.lastIndex, changed + contextLines)
            for (index in start..end) keep[index] = true
        }

        return buildList {
            var index = 0
            while (index < lines.size) {
                if (keep[index]) {
                    add(lines[index])
                    index += 1
                } else {
                    val start = index
                    while (index < lines.size && !keep[index]) index += 1
                    add(
                        LineDiffLine(
                            kind = LineDiffKind.OMITTED,
                            text = "동일한 ${index - start}줄 생략",
                        ),
                    )
                }
            }
        }
    }

    private fun String?.toDiffLines(): List<String> = when {
        this == null || isEmpty() -> emptyList()
        else -> split('\n')
    }
}
