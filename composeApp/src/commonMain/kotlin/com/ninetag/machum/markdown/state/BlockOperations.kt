package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState

/**
 * 블록 리스트에 대한 분할/병합/변환 연산.
 *
 * 모든 함수는 새 리스트를 반환한다 (immutable 패턴).
 * [onBlocksChanged]에 전달하여 상태를 갱신.
 */
object BlockOperations {

    private val calloutHeaderRegex = Regex("^(>+) ?\\[!(\\w+)]\\s*(.*)")
    private val codeFenceRegex = Regex("^```(\\w*)")

    /**
     * TextBlock의 텍스트를 검사하여 블록 분리가 필요한 패턴을 감지한다.
     * 마지막 줄이 블록 시작 패턴이면 해당 줄을 분리하여 새 블록으로 만든다.
     *
     * @return 변경된 블록 리스트, 또는 변경 없으면 null
     */
    fun trySplitTextBlock(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): SplitResult? {
        val block = blocks.getOrNull(blockIndex) as? EditorBlock.Text ?: return null
        val text = block.textFieldState.text.toString()
        val lines = text.split('\n')
        if (lines.size < 2) return null

        val lastLine = lines.last()

        // ``` → CodeBlock 분리
        val codeFenceMatch = codeFenceRegex.find(lastLine)
        if (codeFenceMatch != null) {
            val beforeText = lines.dropLast(1).joinToString("\n").trimEnd()
            val language = codeFenceMatch.groupValues[1]
            val newBlocks = blocks.toMutableList()

            if (beforeText.isNotEmpty()) {
                newBlocks[blockIndex] = EditorBlock.Text(
                    id = block.id,
                    textFieldState = TextFieldState(beforeText),
                )
                val newCode = EditorBlock.Code(language = language, codeState = TextFieldState(""))
                newBlocks.add(blockIndex + 1, newCode)
                return SplitResult(newBlocks, focusBlockIndex = blockIndex + 1)
            } else {
                val newCode = EditorBlock.Code(language = language, codeState = TextFieldState(""))
                newBlocks[blockIndex] = newCode
                return SplitResult(newBlocks, focusBlockIndex = blockIndex)
            }
        }

        // > [!TYPE] → Callout 분리
        val calloutMatch = calloutHeaderRegex.find(lastLine)
        if (calloutMatch != null) {
            val beforeText = lines.dropLast(1).joinToString("\n").trimEnd()
            val calloutType = calloutMatch.groupValues[2]
            val title = calloutMatch.groupValues[3]
            val newBlocks = blocks.toMutableList()

            val newCallout = EditorBlock.Callout(
                calloutType = calloutType,
                titleState = TextFieldState(title),
                bodyBlocks = listOf(EditorBlock.Text(textFieldState = TextFieldState(""))),
            )

            if (beforeText.isNotEmpty()) {
                newBlocks[blockIndex] = EditorBlock.Text(
                    id = block.id,
                    textFieldState = TextFieldState(beforeText),
                )
                newBlocks.add(blockIndex + 1, newCallout)
                return SplitResult(newBlocks, focusBlockIndex = blockIndex + 1)
            } else {
                newBlocks[blockIndex] = newCallout
                return SplitResult(newBlocks, focusBlockIndex = blockIndex)
            }
        }

        // --- → HorizontalRule 분리
        if (isHorizontalRule(lastLine)) {
            val beforeText = lines.dropLast(1).joinToString("\n").trimEnd()
            val newBlocks = blocks.toMutableList()

            if (beforeText.isNotEmpty()) {
                newBlocks[blockIndex] = EditorBlock.Text(
                    id = block.id,
                    textFieldState = TextFieldState(beforeText),
                )
                newBlocks.add(blockIndex + 1, EditorBlock.HorizontalRule())
                // HR 뒤에 빈 TextBlock 추가 (커서 위치)
                newBlocks.add(blockIndex + 2, EditorBlock.Text(textFieldState = TextFieldState("")))
                return SplitResult(newBlocks, focusBlockIndex = blockIndex + 2)
            } else {
                newBlocks[blockIndex] = EditorBlock.HorizontalRule()
                newBlocks.add(blockIndex + 1, EditorBlock.Text(textFieldState = TextFieldState("")))
                return SplitResult(newBlocks, focusBlockIndex = blockIndex + 1)
            }
        }

        return null
    }

    /**
     * 빈 줄 두 개(\n\n)로 TextBlock을 분리한다.
     * 텍스트 끝이 \n\n이면 뒤에 새 TextBlock을 생성.
     */
    fun trySplitByEmptyLine(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): SplitResult? {
        val block = blocks.getOrNull(blockIndex) as? EditorBlock.Text ?: return null
        val text = block.textFieldState.text.toString()

        if (!text.endsWith("\n\n")) return null

        val beforeText = text.removeSuffix("\n\n").trimEnd()
        val newBlocks = blocks.toMutableList()

        newBlocks[blockIndex] = EditorBlock.Text(
            id = block.id,
            textFieldState = TextFieldState(beforeText),
        )
        newBlocks.add(blockIndex + 1, EditorBlock.Text(textFieldState = TextFieldState("")))
        return SplitResult(newBlocks, focusBlockIndex = blockIndex + 1)
    }

    /**
     * 현재 블록의 시작에서 Backspace → 이전 블록과 병합.
     * TextBlock + TextBlock → 하나의 TextBlock으로 합침.
     * 비TextBlock의 경우 삭제하고 이전 블록에 포커스.
     */
    fun mergeWithPrevious(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): SplitResult? {
        if (blockIndex <= 0) return null
        val current = blocks[blockIndex]
        val previous = blocks[blockIndex - 1]
        val newBlocks = blocks.toMutableList()

        // TextBlock + TextBlock → 병합
        if (current is EditorBlock.Text && previous is EditorBlock.Text) {
            val prevText = previous.textFieldState.text.toString()
            val curText = current.textFieldState.text.toString()
            val merged = if (prevText.isEmpty()) curText
                         else if (curText.isEmpty()) prevText
                         else "$prevText\n$curText"
            newBlocks[blockIndex - 1] = EditorBlock.Text(
                id = previous.id,
                textFieldState = TextFieldState(merged),
            )
            newBlocks.removeAt(blockIndex)
            return SplitResult(
                newBlocks,
                focusBlockIndex = blockIndex - 1,
                focusCursorOffset = prevText.length,
            )
        }

        // 빈 특수 블록 삭제
        if (current is EditorBlock.Code && current.codeState.text.isEmpty()) {
            newBlocks.removeAt(blockIndex)
            return SplitResult(newBlocks, focusBlockIndex = blockIndex - 1)
        }

        if (current is EditorBlock.HorizontalRule) {
            newBlocks.removeAt(blockIndex)
            return SplitResult(newBlocks, focusBlockIndex = blockIndex - 1)
        }

        return null
    }

    /**
     * TextBlock의 텍스트를 MarkdownBlockParser로 재파싱하여,
     * 여러 블록으로 분리 가능하면 분리한다.
     *
     * 사용자가 TextBlock 안에서 callout, codeblock, table, HR 등을 입력했을 때
     * 해당 패턴을 감지하여 적절한 블록으로 변환.
     *
     * @return 변경된 블록 리스트, 또는 분리할 것이 없으면 null
     */
    fun tryReparse(
        blocks: List<EditorBlock>,
        blockIndex: Int,
    ): SplitResult? {
        val block = blocks.getOrNull(blockIndex) as? EditorBlock.Text ?: return null
        val text = block.textFieldState.text.toString()
        if (text.isEmpty()) return null

        val parsed = MarkdownBlockParser.parse(text)
        // 단일 TextBlock이면 변경 없음
        if (parsed.size <= 1 && parsed.firstOrNull() is EditorBlock.Text) return null

        val newBlocks = blocks.toMutableList()
        newBlocks.removeAt(blockIndex)
        newBlocks.addAll(blockIndex, parsed)

        // 마지막 파싱된 블록에 포커스 (보통 사용자가 마지막에 입력 중)
        val focusIdx = blockIndex + parsed.lastIndex
        return SplitResult(newBlocks, focusBlockIndex = focusIdx)
    }

    private fun isHorizontalRule(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val ch = trimmed[0]
        if (ch != '-' && ch != '*' && ch != '_') return false
        return trimmed.all { it == ch || it == ' ' }
    }
}

data class SplitResult(
    val newBlocks: List<EditorBlock>,
    val focusBlockIndex: Int,
    val focusCursorOffset: Int = 0,
)
