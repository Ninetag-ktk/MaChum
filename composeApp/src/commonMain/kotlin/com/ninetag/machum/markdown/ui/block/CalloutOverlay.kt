package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.util.overlayScrollForwarder
import com.ninetag.machum.markdown.state.MarkdownEditorState
import com.ninetag.machum.markdown.state.OverlayBlockData
import com.ninetag.machum.markdown.ui.MarkdownBasicTextFieldCore

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.time.Duration.Companion.milliseconds

/**
 * Callout 블록 오버레이.
 *
 * 제목: BasicTextField (단일 줄).
 * 내용: [MarkdownBasicTextFieldCore] (재귀적 마크다운 렌더링 — 중첩 오버레이 지원).
 *
 * 키보드 내비게이션:
 * - Title ↓ → Body 시작으로 포커스 이동
 * - Body ↑ (첫 줄) → Title 끝으로 포커스 이동
 * - Body Backspace (position 0) → 부모 에디터로 포커스 이동 (raw 표시)
 *
 * State 관리:
 * - titleState/bodyEditorState는 키 없이 remember → sync가 textRange를 변경해도 재생성 없음
 * - 부모 overlay 루프에서 key(textRange.first)로 블록 identity 관리
 * - rememberUpdatedState(data)로 sync 시 항상 최신 오프셋 사용
 */
@OptIn(FlowPreview::class)
@Composable
internal fun CalloutOverlay(
    data: OverlayBlockData.CalloutData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle = TextStyle.Default,
    scrollState: ScrollState? = null,
    overlayDepth: Int = 0,
    onRequestActivation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val decoStyle = styleConfig.calloutDecorationStyle(data.calloutType)
    val borderWidth = 3.dp

    // 키 없이 remember → sync로 인한 textRange 변경 시 state 재생성 방지
    val titleState = remember { TextFieldState(data.title) }
    val bodyEditorState = remember {
        MarkdownEditorState(data.bodyLines.joinToString("\n"))
    }
    var isCalloutFocused by remember { mutableStateOf(false) }
    // 블록 활성화 요청 후 sync 차단 (포커스 이탈 sync가 callout을 다시 쓰는 것 방지)
    var activating by remember { mutableStateOf(false) }

    // 항상 최신 data를 참조하는 State — sync에서 stale offset 방지
    val currentData by rememberUpdatedState(data)

    // Title ↔ Body 포커스 전환용 FocusRequester
    val titleFocusRequester = remember { FocusRequester() }
    val bodyFocusRequester = remember { FocusRequester() }

    // ── raw → 오버레이 동기화 (포커스 없을 때만) ──

    LaunchedEffect(data.title, isCalloutFocused) {
        if (!activating && !isCalloutFocused && titleState.text.toString() != data.title) {
            titleState.edit { replace(0, length, data.title) }
        }
    }
    LaunchedEffect(data.bodyLines, isCalloutFocused) {
        val newBody = data.bodyLines.joinToString("\n")
        if (!activating && !isCalloutFocused && bodyEditorState.textFieldState.text.toString() != newBody) {
            bodyEditorState.textFieldState.edit { replace(0, length, newBody) }
        }
    }

    // 포커스 이탈 시 즉시 동기화
    LaunchedEffect(isCalloutFocused) {
        if (!activating && !isCalloutFocused) {
            syncCalloutToRaw(
                textFieldState, currentData,
                titleState.text.toString(),
                bodyEditorState.textFieldState.text.toString(),
            )
        }
    }

    // ── 오버레이 → raw 동기화 (debounce 300ms) ──

    LaunchedEffect(Unit) {
        snapshotFlow { titleState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(300.milliseconds)
            .collectLatest { newTitle ->
                if (!activating && isCalloutFocused) {
                    syncCalloutToRaw(
                        textFieldState, currentData, newTitle,
                        bodyEditorState.textFieldState.text.toString(),
                    )
                }
            }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { bodyEditorState.textFieldState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(300.milliseconds)
            .collectLatest { newBody ->
                if (!activating && isCalloutFocused) {
                    syncCalloutToRaw(
                        textFieldState, currentData, titleState.text.toString(), newBody,
                    )
                }
            }
    }

    // ── 키보드 이벤트 핸들러 ──

    // 롱프레스: 부모 에디터로 커서 이동 (raw 편집 모드)
    val longPressModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onLongPress = {
                activating = true
                onRequestActivation()
            },
        )
    }

    // Title 키 이벤트: ↓ → Body 시작으로 이동
    val titleKeyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> {
                bodyFocusRequester.requestFocus()
                bodyEditorState.textFieldState.edit { selection = TextRange(0) }
                true
            }
            else -> false
        }
    }

    // Body 키 이벤트: Backspace(position 0) → raw 전환, ↑(첫 줄) → Title로 이동
    val bodyKeyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val sel = bodyEditorState.textFieldState.selection

        when (event.key) {
            Key.Backspace -> {
                if (sel.collapsed && sel.start == 0) {
                    // 부모 에디터로 포커스 이동 → 블록 활성화 (raw 표시)
                    activating = true
                    onRequestActivation()
                    true
                } else false
            }
            Key.DirectionUp -> {
                if (sel.collapsed) {
                    val text = bodyEditorState.textFieldState.text.toString()
                    val isFirstLine = text.lastIndexOf('\n', (sel.start - 1).coerceAtLeast(0)) == -1
                    if (isFirstLine) {
                        titleFocusRequester.requestFocus()
                        titleState.edit { selection = TextRange(length) }
                        true
                    } else false
                } else false
            }
            else -> false
        }
    }

    // ── UI ──

    // 포커스 중에는 스크롤 포워딩 비활성화 — body 높이 변경 시 부모 스크롤 점프 방지
    val scrollForwarder = if (!isCalloutFocused) {
        scrollState?.let { overlayScrollForwarder(it) } ?: Modifier
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(scrollForwarder)
            .background(decoStyle.containerColor, RoundedCornerShape(8.dp))
            .drawBehind {
                drawRect(
                    color = decoStyle.accentColor,
                    size = Size(borderWidth.toPx(), size.height),
                )
            }
            .onFocusChanged { isCalloutFocused = it.hasFocus }
            .padding(start = borderWidth + 4.dp, end = 4.dp)
    ) {
        BasicTextField(
            state = titleState,
            textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(titleFocusRequester)
                .then(titleKeyModifier)
                .then(longPressModifier),
            lineLimits = TextFieldLineLimits.SingleLine,
        )

        MarkdownBasicTextFieldCore(
            state = bodyEditorState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(bodyFocusRequester)
                .then(bodyKeyModifier)
                .then(longPressModifier),
            textStyle = textStyle,
            styleConfig = styleConfig,
            overlayDepth = overlayDepth + 1,
        )
    }
}

private fun syncCalloutToRaw(
    textFieldState: TextFieldState,
    data: OverlayBlockData.CalloutData,
    title: String,
    body: String,
) {
    val header = "> [!${data.calloutType}] $title"
    val bodyLines = body.lines().joinToString("\n") { line ->
        if (line.startsWith(">")) ">$line" else "> $line"
    }
    val newRaw = "$header\n$bodyLines"
    val start = data.blockRange.textRange.first
    val end = data.blockRange.textRange.last + 1
    textFieldState.edit {
        replace(
            start.coerceIn(0, length),
            end.coerceIn(0, length),
            newRaw,
        )
    }
}
