package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import com.ninetag.machum.markdown.parser.MarkdownParser
import com.ninetag.machum.markdown.parser.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MarkdownTextFieldState(
    initialText: String,
    private val parser: MarkdownParser,
    private val coroutineScope: CoroutineScope,
) {
    val documentState = TextFieldState(initialText)

    private val _parseResult = MutableStateFlow(parser.parse(initialText))
    val parseResult: StateFlow<ParseResult> = _parseResult.asStateFlow()

    // 렌더링 전용: 160ms 디바운스 후 파싱 결과 기반 정확한 블록 범위
    private val _blockRanges = MutableStateFlow(
        computeBlockRanges(initialText, _parseResult.value)
    )
    val blockRanges: StateFlow<List<IntRange>> = _blockRanges.asStateFlow()

    // active 블록 감지 전용: 빈 줄 기준 즉시 계산 (파싱 없음)
    private val _liveBlockRanges = MutableStateFlow(
        computeLiveBlockRanges(initialText)
    )
    val liveBlockRanges: StateFlow<List<IntRange>> = _liveBlockRanges.asStateFlow()

    init {
        coroutineScope.launch {
            // 텍스트 변경 → liveBlockRanges 즉시 갱신 (debounce 없음)
            snapshotFlow { documentState.text.toString() }
                .distinctUntilChanged()
                .collectLatest { text ->
                    _liveBlockRanges.value = computeLiveBlockRanges(text)
                }
        }

        // 텍스트 변경 → 파싱 + blockRanges 갱신 (debounce 160ms)
        @OptIn(FlowPreview::class)
        coroutineScope.launch {
            snapshotFlow { documentState.text.toString() }
                .distinctUntilChanged()
                .debounce(160.milliseconds)
                .collectLatest { text ->
                    val result = parser.parse(text)
                    _parseResult.value = result
                    _blockRanges.value = computeBlockRanges(text, result)
                }
        }
    }
}

/**
 * 빈 줄(\n\n) 기준으로 블록 범위를 즉시 계산한다. 파싱 없이 O(n) 텍스트 스캔만 수행.
 * active 블록 감지 전용으로 사용하며, 렌더링용 blockRanges와 블록 수가 다를 수 있다.
 */
internal fun computeLiveBlockRanges(text: String): List<IntRange> {
    if (text.isEmpty()) return listOf(0..0)
    val ranges = mutableListOf<IntRange>()
    var blockStart = 0
    var i = 0
    while (i < text.length) {
        if (text[i] == '\n' && i + 1 < text.length && text[i + 1] == '\n') {
            ranges.add(blockStart..i)
            // 연속된 빈 줄 건너뜀
            i += 2
            while (i < text.length && text[i] == '\n') i++
            blockStart = i
        } else {
            i++
        }
    }
    ranges.add(blockStart..(text.length - 1).coerceAtLeast(blockStart))
    return ranges
}

internal fun computeBlockRanges(text: String, parseResult: ParseResult): List<IntRange> {
    val lines = text.lines()
    val lineOffsets = IntArray(lines.size + 1)
    for (i in lines.indices) {
        lineOffsets[i + 1] = lineOffsets[i] + lines[i].length + 1 // +1 for \n
    }

    val starts = IntArray(parseResult.blocks.size) { Int.MAX_VALUE }
    val ends = IntArray(parseResult.blocks.size) { 0 }

    parseResult.lineToBlockIndex.forEach { (lineNum, blockIdx) ->
        if (lineNum < lines.size) {
            starts[blockIdx] = minOf(starts[blockIdx], lineOffsets[lineNum])
            ends[blockIdx] = maxOf(ends[blockIdx], lineOffsets[lineNum] + lines[lineNum].length)
        }
    }

    return parseResult.blocks.indices.map { idx ->
        val s = starts[idx].takeIf { it != Int.MAX_VALUE } ?: 0
        s..ends[idx]
    }
}
