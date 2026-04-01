package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.ninetag.machum.markdown.editor.overlay.BlockOverlay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 마크다운 Live Preview 편집 컴포지션 (Phase 2+3 — raw text + 오버레이 Composable).
 *
 * Material3 테마에 의존하지 않는 Basic 버전.
 * Material3 테마를 사용하려면 [MarkdownTextField] 를 사용.
 *
 * 구조:
 * ```
 * Box(clipToBounds) {
 *     BasicTextField(raw text — 인라인 서식 + 블록 투명)
 *     // 비활성 블록 위에 오버레이 Composable
 *     for (block in overlayBlocks) { BlockOverlay(...) }
 * }
 * ```
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
    val scrollOffset by remember { derivedStateOf { scrollState.value.toFloat() } }

    // 텍스트 변경 시 raw text 를 그대로 emit
    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { onValueChange(it) }
    }

    // 오버레이 블록 데이터 계산
    val overlayBlocks: List<OverlayBlockData> by remember {
        derivedStateOf {
            val layout = textLayoutResult ?: return@derivedStateOf emptyList()
            val rawText = state.textFieldState.text.toString()
            val activeRanges = outputTransformation.activeBlockRanges
            val offset = scrollOffset

            outputTransformation.blockRanges
                .filter { it.textRange !in activeRanges }
                .mapNotNull { block ->
                    val rect = OverlayPositionCalculator.compute(layout, block.textRange, offset)
                        ?: return@mapNotNull null
                    if (!OverlayPositionCalculator.isVisible(rect, layout.size.height.toFloat()))
                        return@mapNotNull null
                    val blockText = rawText.substring(
                        block.textRange.first.coerceIn(0, rawText.length),
                        (block.textRange.last + 1).coerceIn(0, rawText.length),
                    )
                    OverlayBlockParser.parse(block, blockText, rect)
                }
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        BasicTextField(
            state = state.textFieldState,
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val layout = textLayoutResult ?: return@drawBehind
                    drawBlockDecorations(
                        layout = layout,
                        blocks = outputTransformation.blockRanges,
                        activeBlockRanges = outputTransformation.activeBlockRanges,
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

        // 비활성 블록 오버레이 레이어
        for (block in overlayBlocks) {
            BlockOverlay(
                data = block,
                textFieldState = state.textFieldState,
                styleConfig = styleConfig,
                textStyle = textStyle,
                scrollState = scrollState,
            )
        }
    }
}
