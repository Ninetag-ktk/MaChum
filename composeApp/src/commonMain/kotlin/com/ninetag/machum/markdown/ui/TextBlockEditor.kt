package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.util.handleEditorKeyEvent
import com.ninetag.machum.markdown.state.EditorInputTransformation
import com.ninetag.machum.markdown.state.RawMarkdownOutputTransformation
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.ui.selection.resetDocumentSelectionOnFocus

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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.time.Duration.Companion.milliseconds

/**
 * 일반 텍스트 블록 에디터.
 *
 * 인라인 서식(OutputTransformation, BlockDecorationDrawer)을 적용.
 * 블록 분할 패턴(```, > [!TYPE], ---)을 감지하여 [navigation]을 통해 분리 요청.
 */
@OptIn(FlowPreview::class)
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
    /**
     * 빈 마지막 줄 + Enter → trailing \n 제거 + onMoveToNext 호출.
     * Callout body 안 TextBlock 에서만 true (CalloutBlockEditor 의 body MarkdownBlockEditor 호출 →
     * BlockItem → TextBlockEditor 체인). 외부 TextBlock 은 false (default) 로 두어 ZWSP/격하 결과물의
     * 의도치 않은 탈출 방지. CLAUDE_sub.md #20 정책 v2.
     */
    escapeOnEmptyEnter: Boolean = false,
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

    // isFocused / block.rawMode 를 key 로 사용하여 변경 시 새 인스턴스 생성 → transformOutput() 재실행
    var isFocused by remember { mutableStateOf(false) }
    val outputTransformation = remember(styleConfig, isFocused, block.rawMode) {
        RawMarkdownOutputTransformation(styleConfig).apply {
            this.isFocused = isFocused
            this.isRawMode = block.rawMode
        }
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 포커스 시 커서 힌트 적용 (AtX: TextLayoutResult로 정밀 위치 계산)
    LaunchedEffect(cursorHint, isFocused) {
        if (!isFocused || cursorHint == null) return@LaunchedEffect
        if (cursorHint is CursorHint.AtX) {
            // TextLayoutResult가 준비될 때까지 잠시 대기
            kotlinx.coroutines.delay(20.milliseconds)
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
    //
    // dissolve 정책 v3 (CLAUDE_sub.md 섹션 10):
    // rawMode=true 블록은 편집 중 reparse 를 보류한다. focus-out 시점에 약간의 delay 후 reparse.
    // (key 에 block.rawMode 포함 → dissolve/자동해제로 rawMode 가 변하면 LaunchedEffect 재시작)
    LaunchedEffect(block.textFieldState, block.rawMode) {
        if (block.rawMode) return@LaunchedEffect
        snapshotFlow { block.textFieldState.text.toString() }
            .distinctUntilChanged()
            .debounce(150.milliseconds)
            .collectLatest { _ ->
                navigation.onReparse()
            }
    }

    // ZWSP 자동 제거 (Block→Block 빈 줄 placeholder 의 격하):
    // ZWSP(BLANK_LINE_MARKER) 는 빈 줄 표현용 1글자 마커. 사용자가 ZWSP 블록에 입력(텍스트/Enter)하는 순간
    // 그 블록은 더 이상 "빈 줄 placeholder" 가 아니라 일반 TextBlock 이므로 ZWSP 를 제거해야 한다.
    // 제거하지 않으면 ZWSP 가 줄 시작에 박혀 있어 InlineStyleScanner 의 line prefix 매칭(`# `, `> ` 등)이 깨진다.
    LaunchedEffect(block.textFieldState) {
        snapshotFlow { block.textFieldState.text.toString() }
            .filter { it.contains(EditorBlock.BLANK_LINE_MARKER) && it.length > 1 }
            .collectLatest { text ->
                val cleaned = text.replace(EditorBlock.BLANK_LINE_MARKER, "")
                block.textFieldState.edit { replace(0, length, cleaned) }
            }
    }

    // 빈 raw 블록 자동 정리 (Block 의 마커를 모두 지워서 일반 TextField 처럼 된 transient 상태):
    // rawMode=true 인 블록의 텍스트가 빈 순간 즉시 rawMode 해제 → 그냥 일반 빈 TextBlock 으로 전환.
    // (block.id/textFieldState 유지, 플래그만 false 로 변환)
    LaunchedEffect(block.rawMode) {
        if (!block.rawMode) return@LaunchedEffect
        snapshotFlow { block.textFieldState.text.toString().isEmpty() }
            .distinctUntilChanged()
            .collectLatest { isEmpty ->
                if (isEmpty) navigation.onClearRawMode()
            }
    }

    // dissolve 정책 v3: rawMode=true 인 블록이 focus-out 되면 200ms delay 후 reparse 1회.
    // - transient focus-out (블록간 이동 중 잠깐 잃었다 다시 받는 케이스) 은
    //   key=isFocused 가 LaunchedEffect 를 cancel 시켜 reparse 발동 안 함.
    // - rawMode 가드 덕분에 일반 TextBlock 의 Smart Enter / 방향키 이동에는 영향 없음.
    // - silent 변형 호출: 사용자가 이미 다른 블록으로 포커스를 옮긴 상태이므로 새 rendering
    //   블록으로 focus 를 끌어가지 않음 (사용자 포커스 위치 보존).
    LaunchedEffect(isFocused, block.rawMode) {
        if (block.rawMode && !isFocused) {
            kotlinx.coroutines.delay(200.milliseconds)
            navigation.onReparseSilent()
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
            // Smart Enter 블록 탈출 (#20 정책 v2):
            // 외부 TextBlock 은 미적용 (escapeOnEmptyEnter=false default). Callout body 안 TextBlock 만
            // 활성화 (CalloutBlockEditor 가 body MarkdownBlockEditor 호출 시 enableEnterEscape=true 전달).
            // 조건: 마지막 줄이 빈 상태 (lineStart == lineEnd) + Enter → trailing \n 제거 + onMoveToNext.
            // body 안 다음 블록이 있으면 그쪽으로, 마지막이면 onEscapeToNext 체인 → Callout 외부 탈출.
            Key.Enter -> {
                if (escapeOnEmptyEnter && sel.collapsed) {
                    val text = block.textFieldState.text.toString()
                    val nextNewline = text.indexOf('\n', sel.start)
                    val isLastLine = nextNewline == -1
                    val lineStart = if (sel.start == 0) 0 else text.lastIndexOf('\n', sel.start - 1) + 1
                    val lineEnd = if (isLastLine) text.length else nextNewline
                    val isCurrentLineEmpty = lineStart == lineEnd
                    if (isLastLine && isCurrentLineEmpty) {
                        if (lineStart > 0) {
                            block.textFieldState.edit {
                                replace(lineStart - 1, lineStart, "")
                            }
                        }
                        navigation.onMoveToNext()
                        true
                    } else false
                } else false
            }
            Key.DirectionUp -> {
                // 시각(visual) 줄 기준으로 판정한다. soft-wrap 된 문단에서도 "첫 시각 줄"에서만
                // 블록을 탈출하고, 그 외 시각 줄 사이 이동은 BasicTextField 의 네이티브 처리에 맡긴다.
                // (기존 '\n' 기준은 줄바꿈 없이 감긴 긴 문단에서 커서 위치와 무관하게 항상 블록을
                //  탈출하는 버그가 있었음 — CLAUDE_sub.md #19. textLayoutResult 로 시각 줄을 판정해 해결.)
                val layout = textLayoutResult
                if (sel.collapsed && layout != null && layout.getLineForOffset(sel.start) == 0) {
                    if (event.isShiftPressed) {
                        // Shift+↑: 블록 단위 selection 확장 (Phase 1 Step B)
                        navigation.onExtendSelectionToPrevious()
                    } else {
                        val cursorX = layout.getHorizontalPosition(sel.start, usePrimaryDirection = true)
                        navigation.onMoveToPreviousWithX(cursorX)
                    }
                    true
                } else false
            }
            Key.DirectionDown -> {
                // 시각 줄 기준: "마지막 시각 줄"에서만 다음 블록으로 탈출. 그 외 시각 줄 사이 이동은 네이티브.
                val layout = textLayoutResult
                if (sel.collapsed && layout != null && layout.getLineForOffset(sel.start) == layout.lineCount - 1) {
                    if (event.isShiftPressed) {
                        // Shift+↓: 블록 단위 selection 확장 (Phase 1 Step B)
                        navigation.onExtendSelectionToNext()
                    } else {
                        val cursorX = layout.getHorizontalPosition(sel.start, usePrimaryDirection = true)
                        navigation.onMoveToNextWithX(cursorX)
                    }
                    true
                } else false
            }
            Key.DirectionLeft -> {
                if (sel.collapsed && sel.start == 0) {
                    navigation.onMoveLeft()
                    true
                } else false
            }
            Key.DirectionRight -> {
                // ← 와 대칭. cursor 가 text 끝에 도달하면 다음 블록 시작 위치로 진입.
                // BasicTextField 기본 동작은 블록 경계에서 멈추므로 명시 핸들러 필요.
                // raw 블록의 multi-line 텍스트(예: dissolve 된 Callout 의 `> [!NOTE] ...\n> body`) 에서
                // 끝까지 도달 시 다음 블록으로 자연스럽게 넘어가야 하는 케이스를 해결.
                if (sel.collapsed && sel.start == block.textFieldState.text.length) {
                    navigation.onMoveToNext()
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
            .resetDocumentSelectionOnFocus(block.id)
            .drawBehind {
                val layout = textLayoutResult ?: return@drawBehind
                drawBlockDecorations(
                    layout = layout,
                    blocks = outputTransformation.blockRanges,
                    config = styleConfig,
                    scrollOffset = 0f,
                    inlineCodeRanges = outputTransformation.inlineCodeRanges,
                    rawZones = outputTransformation.currentRawZones,
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
