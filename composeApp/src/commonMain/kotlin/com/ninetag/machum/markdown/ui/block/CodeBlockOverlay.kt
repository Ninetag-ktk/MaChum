package com.ninetag.machum.markdown.ui.block

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*
import com.ninetag.machum.markdown.service.util.overlayScrollForwarder

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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * CodeBlock 오버레이.
 *
 * 라운드 배경 + 모노스페이스 코드 TextField.
 * ``` 펜스는 숨기고 코드 내용만 편집 가능.
 * LongPress → raw 전환.
 */
@Composable
internal fun CodeBlockOverlay(
    data: OverlayBlockData.CodeBlockData,
    textFieldState: TextFieldState,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle = TextStyle.Default,
    scrollState: ScrollState? = null,
    modifier: Modifier = Modifier,
) {
    val codeState = remember(data.blockRange.textRange) { TextFieldState(data.code) }

    // 외부에서 데이터 변경 시 state 갱신
    LaunchedEffect(data.code) {
        if (codeState.text.toString() != data.code) {
            codeState.edit { replace(0, length, data.code) }
        }
    }

    // 코드 변경 → raw markdown 동기화
    LaunchedEffect(codeState) {
        snapshotFlow { codeState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .collectLatest { newCode ->
                syncCodeBlockToRaw(textFieldState, data, newCode)
            }
    }

    val scrollForwarder = scrollState?.let { overlayScrollForwarder(it) } ?: Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(scrollForwarder)
            .background(styleConfig.codeBlockBackground, RoundedCornerShape(8.dp))
            .pointerInput(data.blockRange.textRange) {
                detectTapGestures(
                    onLongPress = {
                        textFieldState.edit {
                            selection = TextRange(data.blockRange.textRange.first)
                        }
                    },
                )
            }
            .padding(8.dp)
    ) {
        BasicTextField(
            state = codeState,
            textStyle = textStyle.merge(TextStyle(fontFamily = FontFamily.Monospace)),
            modifier = Modifier.fillMaxWidth(),
        )
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
