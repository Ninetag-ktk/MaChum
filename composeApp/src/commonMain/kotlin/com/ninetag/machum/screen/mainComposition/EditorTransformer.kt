package com.ninetag.machum.screen.mainComposition

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

class EditorTransformer(
    val textFieldState: TextFieldState,
    private val parser: MarkdownParser,
    coroutineScope: CoroutineScope,
) {
    private val _parseResult = MutableStateFlow(ParseResult(emptyList(), emptyMap()))
    val parseResult: StateFlow<ParseResult> = _parseResult.asStateFlow()

    init {
        // 초기 파싱: 디바운싱 없이 즉시 실행
        _parseResult.value = parser.parse(textFieldState.text.toString())

        // 이후 변경 시 디바운싱 적용
        @OptIn(FlowPreview::class)
        coroutineScope.launch {
            snapshotFlow { textFieldState.text.toString() }
                .distinctUntilChanged()
                .debounce(160.milliseconds)
                .collectLatest { text ->
                    _parseResult.value = parser.parse(text)
                }
        }
    }
}
