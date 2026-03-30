package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 마크다운 Live Preview 편집 컴포지션 (v2 — SpanStyle 방식).
 *
 * - [value]: 마크다운 문자열 (파일 내용). 파일 전환은 호출부의 `key(file.name)` 으로 처리.
 * - [onValueChange]: 편집 후 마크다운 직렬화 결과를 전달. 디바운싱은 호출부에서 담당.
 *
 * 내부에서 [MarkdownEditorState] 를 생성하여 clean text + spans 를 관리한다.
 */
@Composable
fun MarkdownTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val state = remember { MarkdownEditorState(value) }

    // 텍스트 또는 스팬 변경 시 마크다운 직렬화 결과를 emit
    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.text.toString() to state.spans }
            .distinctUntilChanged()
            .collectLatest { onValueChange(state.toMarkdown()) }
    }

    val inputTransformation = remember(state) {
        ChainedInputTransformation(
            EditorInputTransformation(),
            MarkdownPatternInputTransformation(state),
        )
    }

    BasicTextField(
        state = state.textFieldState,
        modifier = modifier,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        inputTransformation = inputTransformation,
        outputTransformation = MarkdownEditorOutputTransformation(state.spans),
    )
}

/**
 * EditorInputTransformation (auto-close) 에 이어 패턴 감지를 적용하는 변환기.
 */
private class MarkdownPatternInputTransformation(
    private val state: MarkdownEditorState,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        state.applyInput(this)
    }
}

/**
 * 두 InputTransformation 을 순서대로 적용하는 연결 변환기.
 */
private class ChainedInputTransformation(
    private val first: InputTransformation,
    private val second: InputTransformation,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        with(first) { transformInput() }
        with(second) { transformInput() }
    }
}
