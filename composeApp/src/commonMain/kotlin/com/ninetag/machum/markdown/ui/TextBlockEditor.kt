package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.util.handleEditorKeyEvent
import com.ninetag.machum.markdown.state.EditorInputTransformation
import com.ninetag.machum.markdown.state.RawMarkdownOutputTransformation
import com.ninetag.machum.markdown.state.EditorBlock

import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration.Companion.milliseconds

/**
 * 일반 텍스트 블록 에디터.
 *
 * 인라인 서식(OutputTransformation, BlockDecorationDrawer)을 적용.
 * 블록 분할 패턴(```, > [!TYPE], ---)을 감지하여 [navigation]을 통해 분리 요청.
 */
@Composable
internal fun TextBlockEditor(
    block: EditorBlock.Text,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    cursorBrush: Brush = SolidColor(Color.Black),
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    navigation: BlockNavigation = BlockNavigation(),
    cursorHint: CursorHint? = null,
) {
    val normalizedTextStyle = remember(textStyle) {
        val effectiveLineHeight = if (textStyle.lineHeight.isUnspecified) 1.5.em else textStyle.lineHeight
        textStyle.copy(
            lineHeight = effectiveLineHeight,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Proportional,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }

    val inputTransformation = remember { EditorInputTransformation() }

    // isFocused를 key로 사용하여 포커스 변경 시 새 인스턴스 생성 → transformOutput() 재실행
    var isFocused by remember { mutableStateOf(false) }
    val outputTransformation = remember(styleConfig, isFocused) {
        RawMarkdownOutputTransformation(styleConfig).apply {
            this.isFocused = isFocused
            applyBlockTransparent = false
        }
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 포커스 시 커서 힌트 적용 (AtX: TextLayoutResult로 정밀 위치 계산)
    LaunchedEffect(cursorHint, isFocused) {
        if (!isFocused || cursorHint == null) return@LaunchedEffect
        if (cursorHint is CursorHint.AtX) {
            // TextLayoutResult가 준비될 때까지 잠시 대기
            kotlinx.coroutines.delay(20)
            val layout = textLayoutResult ?: return@LaunchedEffect
            val targetLine = if (cursorHint.lastLine) layout.lineCount - 1 else 0
            val lineTop = layout.getLineTop(targetLine)
            val lineBottom = layout.getLineBottom(targetLine)
            val y = (lineTop + lineBottom) / 2
            val offset = layout.getOffsetForPosition(
                androidx.compose.ui.geometry.Offset(cursorHint.x, y)
            )
            block.textFieldState.edit {
                selection = androidx.compose.ui.text.TextRange(offset)
            }
        }
        // Start/End는 MarkdownBlockEditor의 LaunchedEffect에서 처리
    }

    // 블록 분할 패턴 감지: 텍스트를 재파싱하여 블록 서식이 포함되면 분리
    // 주의: endsWith("\n\n") 자동 분리는 비활성화됨 (#16 빈 줄 TextBlock 포함과 충돌)
    // 블록 생성은 #20 Smart Enter에서 처리 예정
    LaunchedEffect(block.textFieldState) {
        snapshotFlow { block.textFieldState.text.toString() }
            .distinctUntilChanged()
            .debounce(150.milliseconds)
            .collectLatest { text ->
                // 텍스트에 블록 패턴(callout, codeblock, table)이 포함되면 재파싱으로 분리
                navigation.onReparse()
            }
    }

    // 블록 간 커서 이동 + Backspace 병합
    val blockKeyHandler = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val sel = block.textFieldState.selection

        when (event.key) {
            Key.Backspace -> {
                if (sel.collapsed && sel.start == 0) {
                    navigation.onMergeWithPrevious()
                    true
                } else false
            }
            Key.DirectionUp -> {
                if (sel.collapsed) {
                    val text = block.textFieldState.text.toString()
                    // sel.start == 0이면 무조건 첫 줄 (leading \n이 있어도)
                    val isFirstLine = sel.start == 0 || text.lastIndexOf('\n', sel.start - 1) == -1
                    if (isFirstLine) {
                        val cursorX = textLayoutResult?.let { layout ->
                            layout.getHorizontalPosition(sel.start, usePrimaryDirection = true)
                        } ?: 0f
                        navigation.onMoveToPreviousWithX(cursorX)
                        true
                    } else false
                } else false
            }
            Key.DirectionDown -> {
                if (sel.collapsed) {
                    val text = block.textFieldState.text.toString()
                    val isLastLine = text.indexOf('\n', sel.start) == -1
                    if (isLastLine) {
                        val cursorX = textLayoutResult?.let { layout ->
                            layout.getHorizontalPosition(sel.start, usePrimaryDirection = true)
                        } ?: 0f
                        navigation.onMoveToNextWithX(cursorX)
                        true
                    } else false
                } else false
            }
            Key.DirectionLeft -> {
                if (sel.collapsed && sel.start == 0) {
                    navigation.onMoveLeft()
                    true
                } else false
            }
            else -> false
        }
    }

    BasicTextField(
        state = block.textFieldState,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .drawBehind {
                val layout = textLayoutResult ?: return@drawBehind
                drawBlockDecorations(
                    layout = layout,
                    blocks = outputTransformation.blockRanges,
                    activeBlockRanges = outputTransformation.activeBlockRanges,
                    config = styleConfig,
                    scrollOffset = 0f,
                    isNested = false,
                    inlineCodeRanges = outputTransformation.inlineCodeRanges,
                )
            }
            .then(blockKeyHandler)
            .onPreviewKeyEvent { handleEditorKeyEvent(it, block.textFieldState) },
        textStyle = normalizedTextStyle,
        cursorBrush = cursorBrush,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        onTextLayout = { textLayoutResult = it() },
    )
}
