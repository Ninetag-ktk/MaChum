package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.uuid.Uuid

/**
 * 블록 기반 에디터의 문서 모델.
 *
 * 각 블록은 독립적인 TextFieldState를 보유하며, LazyColumn의 아이템으로 렌더링된다.
 * [id]는 LazyColumn key로 사용되어 블록 재활용 시 state를 유지한다.
 * [toMarkdown]으로 직렬화하여 raw markdown 문자열을 복원한다.
 */
sealed class EditorBlock {
    abstract val id: String
    abstract fun toMarkdown(): String

    companion object {
        /**
         * Block→Block 사이 빈 줄을 표현하는 마커 문자 (Zero-Width Space).
         *
         * 독립 TextField에서 "" = 높이 0, "\n" = 2줄 높이 → 정확히 1줄 높이 불가.
         * ZWSP는 보이지 않으면서 1줄 높이를 차지하여 빈 줄 1개를 정확히 렌더링.
         * [toMarkdown] 시 "" (빈 문자열)로 치환하여 원본 빈 줄을 복원한다.
         */
        const val BLANK_LINE_MARKER = "\u200B"
    }

    /**
     * 일반 텍스트 블록.
     * Heading, BulletList, OrderedList, Blockquote, 일반 텍스트를 포함한다.
     * 인라인 서식은 RawMarkdownOutputTransformation이 처리.
     *
     * [rawMode]/[rawOrigin] 은 dissolve(서식 해제) 동작용 transient 필드.
     * - rawMode=true 면 tryReparse 가 origin 비교로 reparse 를 skip 하여 raw 유지
     * - 직렬화 시점([toMarkdown])에는 둘 다 무시됨 (저장/로드는 항상 raw=false)
     * 상세: docs/markdown-editor.md.
     */
    data class Text(
        override val id: String = generateId(),
        val textFieldState: TextFieldState,
        val rawMode: Boolean = false,
        val rawOrigin: RawOrigin? = null,
    ) : EditorBlock() {
        override fun toMarkdown(): String =
            textFieldState.text.toString().replace(BLANK_LINE_MARKER, "")
    }

    /**
     * Callout 블록 (`> [!TYPE] Title` + body).
     * body는 재귀적 블록 리스트로 중첩 Callout/Table/CodeBlock을 포함할 수 있다.
     */
    data class Callout(
        override val id: String = generateId(),
        val calloutType: String,
        val titleState: TextFieldState,
        val bodyBlocks: List<EditorBlock>,
        val foldMarker: Char? = null,
        val titlePrefix: String = " ",
        val bodyLinePrefixes: List<String> = emptyList(),
    ) : EditorBlock() {
        override fun toMarkdown(): String {
            val header = "> [!$calloutType]${foldMarker ?: ""}$titlePrefix${titleState.text}"
            if (bodyBlocks.isEmpty()) return header
            val body = bodyBlocks.joinToString("\n", transform = EditorBlock::toMarkdown)
                .lines()
                .mapIndexed { index, line -> "${bodyLinePrefixes.getOrElse(index) { "> " }}$line" }
                .joinToString("\n")
            return "$header\n$body"
        }
    }

    /** 코드 블록. backtick/tilde 펜스 원문은 에디터에서 숨기되 직렬화할 때 복원한다. */
    data class Code(
        override val id: String = generateId(),
        val language: String,
        val codeState: TextFieldState,
        val openingIndent: String = "",
        val fence: String = "```",
        val openingSuffix: String = language,
        val closingIndent: String = "",
        val closingFence: String = fence,
        val closingSuffix: String = "",
        val emptyContentLineCount: Int = 1,
    ) : EditorBlock() {
        override fun toMarkdown(): String {
            val code = codeState.text.toString()
            val fenceChar = fence.first()
            val longestRun = code.lineSequence()
                .mapNotNull { line -> closingFenceRunLength(line, fenceChar, fence.length) }
                .maxOrNull()
                ?: 0
            val requiredLength = maxOf(fence.length, longestRun + 1)
            val safeOpeningFence = if (requiredLength == fence.length) fence else fenceChar.toString().repeat(requiredLength)
            val safeClosingFence = if (
                closingFence.all { it == fenceChar } && closingFence.length >= requiredLength
            ) closingFence else safeOpeningFence
            val opening = "$openingIndent$safeOpeningFence$openingSuffix"
            val closing = "$closingIndent$safeClosingFence$closingSuffix"
            return when {
                code.isNotEmpty() -> "$opening\n$code\n$closing"
                emptyContentLineCount > 0 -> "$opening\n\n$closing"
                else -> "$opening\n$closing"
            }
        }
    }

    /** 테이블 블록. 정렬 구분자와 선택적인 외곽 pipe를 직렬화할 때 복원한다. */
    data class Table(
        override val id: String = generateId(),
        val headerStates: List<TextFieldState>,
        val rowStates: List<List<TextFieldState>>,
        val delimiterCells: List<String> = List(headerStates.size) { "---" },
        val leadingPipe: Boolean = true,
        val trailingPipe: Boolean = true,
        val delimiterLeadingPipe: Boolean = leadingPipe,
        val delimiterTrailingPipe: Boolean = trailingPipe,
        val rowLeadingPipes: List<Boolean> = List(rowStates.size) { leadingPipe },
        val rowTrailingPipes: List<Boolean> = List(rowStates.size) { trailingPipe },
        val headerSource: TableLineSource? = null,
        val delimiterSource: TableLineSource? = null,
        val rowSources: List<TableLineSource?> = emptyList(),
    ) : EditorBlock() {
        override fun toMarkdown(): String {
            fun line(cells: List<String>, hasLeadingPipe: Boolean, hasTrailingPipe: Boolean): String {
                val content = cells.joinToString(" | ", transform = ::escapeTableCellPipes)
                return buildString {
                    if (hasLeadingPipe) append("| ")
                    append(content)
                    if (hasTrailingPipe) append(" |")
                }
            }

            val originalColumnCount = listOfNotNull(
                headerSource?.cells?.size,
                delimiterSource?.cells?.size,
                rowSources.mapNotNull { it?.cells?.size }.maxOrNull(),
            ).maxOrNull() ?: headerStates.size

            fun serialize(
                cells: List<String>,
                source: TableLineSource?,
                hasLeadingPipe: Boolean,
                hasTrailingPipe: Boolean,
            ): String {
                val unchanged = source != null &&
                    cells.size == originalColumnCount &&
                    cells.indices.all { cells[it] == source.cells.getOrElse(it) { "" } }
                if (unchanged) return source.raw
                val lastValueIndex = cells.indexOfLast(String::isNotEmpty)
                val cellCount = if (source == null) {
                    cells.size
                } else {
                    maxOf(
                        source.cells.size,
                        if (cells.size > originalColumnCount) cells.size else lastValueIndex + 1,
                    )
                }
                return line(cells.take(cellCount), hasLeadingPipe, hasTrailingPipe)
            }

            val headerLine = serialize(headerStates.map { it.text.toString() }, headerSource, leadingPipe, trailingPipe)
            val sepLine = serialize(
                List(headerStates.size) { delimiterCells.getOrElse(it) { "---" }.ifBlank { "---" } },
                delimiterSource,
                delimiterLeadingPipe,
                delimiterTrailingPipe,
            )
            val dataLines = rowStates.mapIndexed { index, row ->
                serialize(
                    row.map { it.text.toString() },
                    rowSources.getOrNull(index),
                    rowLeadingPipes.getOrElse(index) { leadingPipe },
                    rowTrailingPipes.getOrElse(index) { trailingPipe },
                )
            }
                .joinToString("\n")
            return "$headerLine\n$sepLine" + if (dataLines.isNotEmpty()) "\n$dataLines" else ""
        }
    }

    /** 수평선 (`---`). 읽기 전용. */
    data class HorizontalRule(
        override val id: String = generateId(),
    ) : EditorBlock() {
        override fun toMarkdown(): String = "---"
    }

    /** 임베드 (`![[파일명]]`). 읽기 전용. */
    data class Embed(
        override val id: String = generateId(),
        val target: String,
    ) : EditorBlock() {
        override fun toMarkdown(): String = "![[$target]]"
    }
}

data class TableLineSource(
    val raw: String,
    val cells: List<String>,
)

internal fun EditorBlock.Table.removeDataRow(index: Int): EditorBlock.Table? {
    if (index !in rowStates.indices) return null
    return copy(
        rowStates = rowStates.filterIndexed { rowIndex, _ -> rowIndex != index },
        rowLeadingPipes = rowLeadingPipes.filterIndexed { rowIndex, _ -> rowIndex != index },
        rowTrailingPipes = rowTrailingPipes.filterIndexed { rowIndex, _ -> rowIndex != index },
        rowSources = rowSources.filterIndexed { rowIndex, _ -> rowIndex != index },
    )
}

internal fun EditorBlock.Table.removeColumn(index: Int): EditorBlock.Table? {
    if (headerStates.size <= 1 || index !in headerStates.indices) return null
    return copy(
        headerStates = headerStates.filterIndexed { column, _ -> column != index },
        rowStates = rowStates.map { row -> row.filterIndexed { column, _ -> column != index } },
        delimiterCells = delimiterCells.filterIndexed { column, _ -> column != index },
        // A structural edit cannot reuse a raw line with the old column count.
        headerSource = null,
        delimiterSource = null,
        rowSources = List(rowStates.size) { null },
    )
}

private fun closingFenceRunLength(line: String, fenceChar: Char, minimumLength: Int): Int? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3) return null
    val content = line.drop(indent)
    val runLength = content.takeWhile { it == fenceChar }.length
    if (runLength < minimumLength || content.drop(runLength).isNotBlank()) return null
    return runLength
}

private fun escapeTableCellPipes(cell: String): String = buildString(cell.length) {
    for ((index, char) in cell.withIndex()) {
        if (char == '|') {
            var precedingBackslashes = 0
            var cursor = index - 1
            while (cursor >= 0 && cell[cursor] == '\\') {
                precedingBackslashes++
                cursor--
            }
            if (precedingBackslashes % 2 == 0) append('\\')
        }
        append(char)
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun generateId(): String = Uuid.random().toString()

/**
 * dissolve 된 [EditorBlock.Text] 의 원본 블록 종류.
 * tryReparse 가 이 값과 reparse 결과 타입을 비교하여 마커가 살아있는지 판정한다.
 * 같으면 reparse 를 skip 하여 raw 유지, 다르면 일반 reparse 진행.
 */
enum class RawOrigin { CODE, CALLOUT, TABLE, EMBED }

/**
 * 블록 리스트를 raw markdown 문자열로 직렬화한다.
 *
 * 모든 블록 사이에 "\n" 조인. 빈 줄은 TextBlock 안의 \n이 담당:
 * - Text("text1\n") + "\n" + Code = "text1\n\n```..." (trailing \n + join = \n\n = 빈 줄 1개)
 * - Code + "\n" + Text("\ntext2") = "```...\n\ntext2" (join + leading \n = \n\n = 빈 줄 1개)
 * - Code + "\n" + Text(ZWSP) + "\n" + Code = "```...\n\n```..." (Block간 빈 줄 = ZWSP → "" 치환)
 */
fun List<EditorBlock>.toMarkdown(): String {
    if (isEmpty()) return ""
    return buildString {
        for ((i, block) in this@toMarkdown.withIndex()) {
            if (i > 0) append('\n')
            append(block.toMarkdown())
        }
    }
}
