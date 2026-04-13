package com.ninetag.machum.markdown.ui.block

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.OverlayBlockData

/**
 * 블록 타입에 따라 적절한 오버레이 Composable을 렌더링한다.
 *
 * 뷰포트 좌표([OverlayBlockData.viewportRect])에 맞춰 절대 위치에 배치된다.
 * 비활성 블록에만 표시되며, 활성 블록(커서 내부)은 호출 측에서 필터링한다.
 *
 * [contentPadding]: 부모 BasicTextField와 동일한 좌우 패딩.
 * Dialogue Callout만 예외(full width).
 */
@Composable
internal fun BlockOverlay(
    data: OverlayBlockData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle = TextStyle.Default,
    scrollState: ScrollState? = null,
    overlayDepth: Int = 0,
    contentPadding: Dp = 0.dp,
    onRequestActivation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val rect = data.viewportRect

    // [CONTENT_PADDING] Dialogue Callout만 패딩 제외 (full width)
    val isDialogue = data is OverlayBlockData.CalloutData && data.calloutType == "DIALOGUE"
    val overlayPadding = if (!isDialogue && contentPadding > 0.dp) {
        Modifier.padding(horizontal = contentPadding)
    } else {
        Modifier
    }

    // px 단위 오프셋 → 레이아웃 단계에서 적용 (recomposition 없이 위치 갱신)
    // offset 후 padding → 오버레이 위치 결정 후 내부 콘텐츠에 좌우 여백 적용
    val positioned = modifier
        .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
        .then(overlayPadding)

    when (data) {
        is OverlayBlockData.CalloutData -> CalloutOverlay(
            data = data,
            textFieldState = textFieldState,
            styleConfig = styleConfig,
            textStyle = textStyle,
            scrollState = scrollState,
            overlayDepth = overlayDepth,
            onRequestActivation = onRequestActivation,
            modifier = positioned,
        )
        is OverlayBlockData.CodeBlockData -> CodeBlockOverlay(
            data = data,
            textFieldState = textFieldState,
            styleConfig = styleConfig,
            textStyle = textStyle,
            scrollState = scrollState,
            onRequestActivation = onRequestActivation,
            modifier = positioned,
        )
        is OverlayBlockData.TableData -> TableOverlay(
            data = data,
            textFieldState = textFieldState,
            styleConfig = styleConfig,
            textStyle = textStyle,
            scrollState = scrollState,
            onRequestActivation = onRequestActivation,
            modifier = positioned,
        )
    }
}
