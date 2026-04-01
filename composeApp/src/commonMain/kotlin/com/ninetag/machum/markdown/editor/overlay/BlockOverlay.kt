package com.ninetag.machum.markdown.editor.overlay

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import com.ninetag.machum.markdown.editor.MarkdownStyleConfig
import com.ninetag.machum.markdown.editor.OverlayBlockData

/**
 * 블록 타입에 따라 적절한 오버레이 Composable을 렌더링한다.
 *
 * 뷰포트 좌표([OverlayBlockData.viewportRect])에 맞춰 절대 위치에 배치된다.
 * 비활성 블록에만 표시되며, 활성 블록(커서 내부)은 호출 측에서 필터링한다.
 */
@Composable
internal fun BlockOverlay(
    data: OverlayBlockData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle = TextStyle.Default,
    scrollState: ScrollState? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rect = data.viewportRect

    val widthDp = with(density) { rect.width.toDp() }
    val heightDp = with(density) { rect.height.toDp() }

    // px 단위 오프셋 → 레이아웃 단계에서 적용 (recomposition 없이 위치 갱신)
    val positioned = modifier.offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }

    when (data) {
        is OverlayBlockData.CalloutData -> CalloutOverlay(
            data = data,
            textFieldState = textFieldState,
            styleConfig = styleConfig,
            textStyle = textStyle,
            scrollState = scrollState,
            modifier = positioned,
            width = widthDp,
            height = heightDp,
        )
        is OverlayBlockData.CodeBlockData -> {} // CodeBlock은 DrawBehind 방식 유지
        is OverlayBlockData.TableData -> TableOverlay(
            data = data,
            textFieldState = textFieldState,
            styleConfig = styleConfig,
            textStyle = textStyle,
            scrollState = scrollState,
            modifier = positioned,
            width = widthDp,
            height = heightDp,
        )
    }
}
