package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.util.overlayScrollForwarder
import com.ninetag.machum.markdown.state.OverlayBlockData

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * CodeBlock 오버레이.
 *
 * 라운드 배경 + 모노스페이스 코드 TextField.
 * 펜스 줄(```)은 OutputTransformation에서 marker(0.01sp)로 축소되므로,
 * 오버레이 상하에 lineHeight 만큼 패딩을 적용하여 높이를 보정한다.
 */
@Composable
internal fun CodeBlockOverlay(
    data: OverlayBlockData.CodeBlockData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle = TextStyle.Default,
    scrollState: ScrollState? = null,
    onRequestActivation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val codeState = remember { TextFieldState(data.code) }

    // 외부에서 데이터 변경 시 state 갱신
    LaunchedEffect(data.code) {
        if (codeState.text.toString() != data.code) {
            codeState.edit { replace(0, length, data.code) }
        }
    }

    // 코드 변경 → raw markdown 동기화
    LaunchedEffect(Unit) {
        snapshotFlow { codeState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .collectLatest { newCode ->
                syncCodeBlockToRaw(textFieldState, data, newCode)
            }
    }

    val scrollForwarder = scrollState?.let { overlayScrollForwarder(it) } ?: Modifier

    // 펜스 줄 축소 보정: lineHeight 만큼 상하 패딩
    val fencePadding = resolveLineHeightDp(textStyle)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(scrollForwarder)
            .background(styleConfig.codeBlockBackground, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onRequestActivation() })
            }
            .padding(horizontal = 8.dp, vertical = fencePadding)
    ) {
        BasicTextField(
            state = codeState,
            textStyle = textStyle.merge(TextStyle(fontFamily = FontFamily.Monospace)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * TextStyle의 lineHeight를 Dp로 변환한다.
 * - sp: 밀도 기반 변환
 * - em: fontSize × em 값 → Dp
 * - unspecified: fontSize × 1.5 → Dp
 *
 * 펜스/헤더 줄이 marker(0.01sp)로 축소된 경우의 높이 보정에 사용.
 */
@Composable
internal fun resolveLineHeightDp(textStyle: TextStyle): Dp {
    val density = LocalDensity.current
    return with(density) {
        val lh = textStyle.lineHeight
        val fs = textStyle.fontSize
        when {
            lh.isSp -> lh.toDp()
            lh.isEm && fs.isSp -> (fs * lh.value).toDp()
            fs.isSp -> (fs * 1.5f).toDp()
            else -> 24.dp // 모든 단위가 미확정인 경우 fallback
        }
    }
}

private fun syncCodeBlockToRaw(
    textFieldState: TextFieldState,
    data: OverlayBlockData.CodeBlockData,
    newCode: String,
) {
    val fence = if (data.language.isNotEmpty()) "```${data.language}" else "```"
    val newRaw = "$fence\n$newCode\n```"
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
