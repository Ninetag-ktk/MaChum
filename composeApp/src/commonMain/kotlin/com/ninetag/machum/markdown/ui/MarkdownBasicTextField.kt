package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*
import com.ninetag.machum.markdown.service.util.handleEditorKeyEvent

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import com.ninetag.machum.markdown.ui.block.BlockOverlay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** 재귀적 오버레이의 최대 깊이. 이 값 이상이면 오버레이 생성을 중단한다. */
private const val MAX_OVERLAY_DEPTH = 10

/**
 * 마크다운 Live Preview 편집 컴포지션 (Phase 2+3 — raw text + 오버레이 Composable).
 *
 * Material3 테마에 의존하지 않는 Basic 버전.
 * Material3 테마를 사용하려면 [MarkdownTextField] 를 사용.
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

    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { onValueChange(it) }
    }

    // [CONTENT_PADDING] fontSize × 4 좌우 패딩 (Dialogue Callout만 제외)
    val textPadding = with(LocalDensity.current) {
        if (textStyle.fontSize.isSp) textStyle.fontSize.toDp() * 3f else 0.dp
    }

    MarkdownBasicTextFieldCore(
        state = state,
        modifier = modifier,
        textStyle = textStyle,
        cursorBrush = cursorBrush,
        styleConfig = styleConfig,
        scrollState = scrollState,
        overlayDepth = 0,
        contentPadding = textPadding,
    )
}

/**
 * [MarkdownBasicTextField]의 핵심 구현.
 *
 * [overlayDepth]가 [MAX_OVERLAY_DEPTH] 이상이면 오버레이를 생성하지 않는다.
 * CalloutOverlay 등 재귀적 오버레이에서 직접 호출한다.
 */
@Composable
internal fun MarkdownBasicTextFieldCore(
    state: MarkdownEditorState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig(),
    scrollState: ScrollState = rememberScrollState(),
    parentScrollState: ScrollState? = null,
    overlayDepth: Int = 0,
    // [CONTENT_PADDING] 텍스트 좌우 여백 (Overlay Block은 이 패딩에서 제외 → full width)
    contentPadding: Dp = 0.dp,
) {
    // Bold/Italic 등 SpanStyle 적용 시 폰트 메트릭 차이로 줄 높이가 불균일해지는 것 방지
    // lineHeight가 미설정이면 1.5em으로 기본값 지정 (LineHeightStyle은 lineHeight 필수)
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
    val canGenerateOverlays = overlayDepth < MAX_OVERLAY_DEPTH
    val outputTransformation = remember(styleConfig, canGenerateOverlays) {
        RawMarkdownOutputTransformation(styleConfig).apply {
            applyBlockTransparent = canGenerateOverlays
        }
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollOffset by remember { derivedStateOf { scrollState.value.toFloat() } }

    // 이전 유효 오버레이 데이터를 보관 — stale layout 시 깜빡임 방지
    var lastValidOverlayBlocks by remember { mutableStateOf<List<OverlayBlockData>>(emptyList()) }

    val overlayBlocks: List<OverlayBlockData> by remember {
        derivedStateOf {
            if (overlayDepth >= MAX_OVERLAY_DEPTH) return@derivedStateOf emptyList()
            val layout = textLayoutResult ?: return@derivedStateOf lastValidOverlayBlocks
            val rawText = state.textFieldState.text.toString()

            // TextLayoutResult가 현재 텍스트와 일치하지 않으면 이전 데이터 유지
            if (layout.layoutInput.text.length != rawText.length) return@derivedStateOf lastValidOverlayBlocks

            val activeRanges = outputTransformation.activeBlockRanges
            val offset = scrollOffset

            val result = outputTransformation.blockRanges
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
            lastValidOverlayBlocks = result
            result
        }
    }

    // 오버레이 스크롤 포워딩용: parentScrollState가 있으면 루트 스크롤, 없으면 자체 스크롤
    val overlayForwardScrollState = parentScrollState ?: scrollState

    // depth=0: fillMaxSize (화면 전체, 빈 영역 클릭 가능)
    // depth>0: fillMaxWidth (텍스트 높이만큼, 무한 확장 방지)
    val fillModifier = if (overlayDepth == 0) Modifier.fillMaxSize() else Modifier.fillMaxWidth()

    // 오버레이에서 부모 BasicTextField로 포커스를 되돌리기 위한 FocusRequester
    val editorFocusRequester = remember { FocusRequester() }

    // ── [OVERLAY_LAYOUT] ──
    // 모든 depth에서 Layout 사용: overlay의 offset 위치 + 크기를 부모 높이 측정에 반영
    // → overlay chrome(padding/icon)이 투명 텍스트보다 커도 부모가 자동 확장
    // depth=0: clipToBounds로 뷰포트 밖 클리핑 유지

    // 공통 content: BasicTextField + overlay 블록들
    val editorContent: @Composable () -> Unit = {
        // [CONTENT_PADDING] BasicTextField에만 좌우 패딩 적용
        // Overlay Block은 Box/Layout의 직계 자식이므로 패딩 영향 없이 full width
        val textFieldPadding = if (contentPadding > 0.dp) {
            Modifier.padding(horizontal = contentPadding)
        } else {
            Modifier
        }

        BasicTextField(
            state = state.textFieldState,
            modifier = fillModifier
                .then(textFieldPadding)
                .focusRequester(editorFocusRequester)
                .onFocusChanged { outputTransformation.isFocused = it.isFocused }
                .drawBehind {
                    val layout = textLayoutResult ?: return@drawBehind
                    drawBlockDecorations(
                        layout = layout,
                        blocks = outputTransformation.blockRanges,
                        activeBlockRanges = outputTransformation.activeBlockRanges,
                        config = styleConfig,
                        scrollOffset = scrollOffset,
                        isNested = overlayDepth > 0,
                        inlineCodeRanges = outputTransformation.inlineCodeRanges,
                    )
                }
                .onPreviewKeyEvent { handleEditorKeyEvent(it, state.textFieldState) },
            textStyle = normalizedTextStyle,
            cursorBrush = cursorBrush,
            inputTransformation = inputTransformation,
            outputTransformation = outputTransformation,
            scrollState = scrollState,
            onTextLayout = { textLayoutResult = it() },
        )

        for ((index, block) in overlayBlocks.withIndex()) {
            key(block.blockRange.type, index) {
                BlockOverlay(
                    data = block,
                    textFieldState = state.textFieldState,
                    styleConfig = styleConfig,
                    textStyle = textStyle,
                    scrollState = overlayForwardScrollState,
                    overlayDepth = overlayDepth,
                    contentPadding = contentPadding,
                    onRequestActivation = {
                        state.textFieldState.edit {
                            selection = TextRange(block.blockRange.textRange.first)
                        }
                        editorFocusRequester.requestFocus()
                    },
                )
            }
        }
    }

    // ── [OVERLAY_LAYOUT] ──
    // depth=0: BasicTextField가 자체 ScrollState로 스크롤 관리 → Layout 높이 = TextField 높이
    //          clipToBounds로 뷰포트 밖 클리핑. overlay 높이 오차는 lineHeight 맞춤으로 해소.
    // depth>0: overlay의 offset + 크기를 부모 높이에 반영 → chrome 오차만큼 자동 확장
    Layout(
        content = editorContent,
        modifier = if (overlayDepth == 0) modifier.clipToBounds() else modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val textFieldHeight = placeables.firstOrNull()?.height ?: 0

        var maxBottom = textFieldHeight
        if (overlayDepth > 0) {
            // depth>0에서만 overlay 높이를 부모 측정에 반영
            for (i in 1..placeables.lastIndex) {
                val blockIndex = i - 1
                if (blockIndex < overlayBlocks.size) {
                    val top = overlayBlocks[blockIndex].viewportRect.top.toInt()
                    val bottom = top + placeables[i].height
                    if (bottom > maxBottom) maxBottom = bottom
                }
            }
        }

        layout(constraints.maxWidth, maxBottom) {
            placeables.firstOrNull()?.place(0, 0)
            for (i in 1..placeables.lastIndex) {
                placeables[i].place(0, 0)
            }
        }
    }
}
