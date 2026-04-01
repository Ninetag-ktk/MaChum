package com.ninetag.machum.markdown.editor.overlay

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.editor.MarkdownStyleConfig
import com.ninetag.machum.markdown.editor.OverlayBlockData
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Callout 블록 오버레이.
 *
 * 기존 CalloutRenderer 형태: 배경색 + 왼쪽 테두리 + 제목(위) / 내용(아래).
 * 제목과 내용에 각각 BasicTextField를 배치하여 직접 편집 가능.
 * 내용 TextField에는 인라인 서식(bold, italic 등) Live Preview 적용.
 *
 * 포커스 전략 (콜아웃 컨테이너 레벨):
 * - `hasFocus` true (자식 중 하나라도 포커스): 편집 모드, 오버레이 → raw 동기화
 * - `hasFocus` false: 표시 모드, raw → 오버레이 동기화, 모든 서식 적용
 */
@OptIn(FlowPreview::class)
@Composable
internal fun CalloutOverlay(
    data: OverlayBlockData.CalloutData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle = TextStyle.Default,
    scrollState: ScrollState? = null,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") width: Dp,
    @Suppress("UNUSED_PARAMETER") height: Dp,
) {
    val decoStyle = styleConfig.calloutDecorationStyle(data.calloutType)
    val borderWidth = 3.dp

    val titleState = remember(data.blockRange.textRange) { TextFieldState(data.title) }
    val bodyState = remember(data.blockRange.textRange) { TextFieldState(data.bodyLines.joinToString("\n")) }
    // 콜아웃 전체 포커스 추적 (hasFocus = 자식 중 포커스된 것이 있으면 true)
    var isCalloutFocused by remember { mutableStateOf(false) }
    // body 개별 포커스 (인라인 서식 커서 줄 판별용)
    var isBodyFocused by remember { mutableStateOf(false) }

    // isBodyFocused 변경 시 새 인스턴스 생성 → BasicTextField 재렌더링 트리거
    val bodyOutputTransformation = remember(styleConfig, isBodyFocused) {
        InlineOnlyOutputTransformation(styleConfig).apply { isFocused = isBodyFocused }
    }

    // raw → 오버레이 동기화 (콜아웃에 포커스가 없을 때만)
    LaunchedEffect(data.title, isCalloutFocused) {
        if (!isCalloutFocused && titleState.text.toString() != data.title) {
            titleState.edit { replace(0, length, data.title) }
        }
    }
    LaunchedEffect(data.bodyLines, isCalloutFocused) {
        val newBody = data.bodyLines.joinToString("\n")
        if (!isCalloutFocused && bodyState.text.toString() != newBody) {
            bodyState.edit { replace(0, length, newBody) }
        }
    }

    // 오버레이 → raw 동기화 (debounce 300ms, 콜아웃 포커스 중에만)
    LaunchedEffect(titleState) {
        snapshotFlow { titleState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(300)
            .collectLatest { newTitle ->
                if (isCalloutFocused) {
                    syncCalloutToRaw(textFieldState, data, newTitle, bodyState.text.toString())
                }
            }
    }
    LaunchedEffect(bodyState) {
        snapshotFlow { bodyState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(300)
            .collectLatest { newBody ->
                if (isCalloutFocused) {
                    syncCalloutToRaw(textFieldState, data, titleState.text.toString(), newBody)
                }
            }
    }

    // LongPress → raw 전환
    val longPressModifier = Modifier.pointerInput(data.blockRange.textRange) {
        detectTapGestures(
            onLongPress = {
                textFieldState.edit {
                    selection = TextRange(data.blockRange.textRange.first)
                }
            },
        )
    }

    val scrollForwarder = scrollState?.let { overlayScrollForwarder(it) } ?: Modifier

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
            // 콜아웃 전체 포커스 감지 (hasFocus: 자식 중 하나라도 포커스)
            .onFocusChanged { isCalloutFocused = it.hasFocus }
            .padding(start = borderWidth + 4.dp, end = 4.dp)
    ) {
        // 제목 TextField — 메인 TextField와 동일 textStyle + bold
        BasicTextField(
            state = titleState,
            textStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold)),
            modifier = Modifier
                .fillMaxWidth()
                .then(longPressModifier),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        // 내용 TextField — 메인 TextField와 동일 textStyle + 인라인 서식
        BasicTextField(
            state = bodyState,
            textStyle = textStyle,
            outputTransformation = bodyOutputTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isBodyFocused = it.isFocused }
                .then(longPressModifier),
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
    val bodyLines = body.lines().joinToString("\n") { "> $it" }
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
