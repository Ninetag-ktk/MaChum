package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 마크다운 Live Preview 편집 컴포지션 (Phase 2 — raw text + 기호 투명화 방식).
 *
 * Material3 테마에 의존하지 않는 Basic 버전.
 * Material3 테마를 사용하려면 [MarkdownTextField] 를 사용.
 *
 * - [value]: 마크다운 문자열 (파일 내용). 파일 전환은 호출부의 `key(file.name)` 으로 처리.
 * - [onValueChange]: raw 텍스트를 그대로 전달. 디바운싱은 호출부에서 담당.
 */
@Composable
fun MarkdownBasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig(),
    scrollState: ScrollState = rememberScrollState(),
) {
    val state = remember { MarkdownEditorState(value) }
    val inputTransformation = remember { EditorInputTransformation() }
    val outputTransformation = remember(styleConfig) { RawMarkdownOutputTransformation(styleConfig) }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 텍스트 변경 시 raw text 를 그대로 emit
    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { onValueChange(it) }
    }

    BasicTextField(
        state = state.textFieldState,
        modifier = modifier
            .drawBehind {
                val layout = textLayoutResult ?: return@drawBehind
                val scrollOffset = scrollState.value.toFloat()
                drawBlockDecorations(
                    layout = layout,
                    blocks = outputTransformation.blockRanges,
                    activeBlockRange = outputTransformation.activeBlockRange,
                    config = styleConfig,
                    scrollOffset = scrollOffset,
                )
            }
            .onPreviewKeyEvent { handleEditorKeyEvent(it, state.textFieldState) },
        textStyle = textStyle,
        cursorBrush = cursorBrush,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        scrollState = scrollState,
        onTextLayout = { textLayoutResult = it() },
    )
}
