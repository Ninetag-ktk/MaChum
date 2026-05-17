package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.CalloutDecorationStyle
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.toMarkdown
import com.ninetag.machum.markdown.state.MarkdownBlockParser
import com.ninetag.machum.markdown.ui.selection.LocalDocumentSelection
import com.ninetag.machum.markdown.ui.selection.documentSelectionShortcuts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * v2 블록 기반 마크다운 에디터의 공개 API.
 *
 * v1 `MarkdownBasicTextField`와 동일한 `value`/`onValueChange` 인터페이스를 유지하여
 * EditorPage에서 drop-in replacement로 사용 가능.
 *
 * 내부적으로:
 * 1. `value` → `MarkdownBlockParser.parse()` → `List<EditorBlock>`
 * 2. `MarkdownBlockEditor`로 블록별 렌더링
 * 3. 블록 변경 → `toMarkdown()` → `onValueChange` 콜백
 */
@Composable
fun MarkdownBlockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig(),
) {
    var blocks by remember { mutableStateOf(MarkdownBlockParser.parse(value)) }
    var lastExternalValue by remember { mutableStateOf(value) }
    var lastInternalValue by remember { mutableStateOf(value) }

    // Cross-block selection 상태 (Phase 1) — 최상위에서만 호이스팅. 재귀 Callout body 는 자체 미관리
    val documentSelection = remember { mutableStateOf<DocumentSelection>(DocumentSelection.None) }

    // 외부 value 변경 감지 (파일 전환, undo 등) → 재파싱
    if (value != lastExternalValue && value != lastInternalValue) {
        blocks = MarkdownBlockParser.parse(value)
        lastExternalValue = value
        lastInternalValue = value
        documentSelection.value = DocumentSelection.None  // 외부 변경 시 selection 리셋
    }
    // 동일 외부 value가 다시 들어온 경우 (key 재생성 등)
    if (value != lastExternalValue && value == lastInternalValue) {
        lastExternalValue = value
    }

    // 블록 내 TextFieldState 변경 감지 → raw markdown 직렬화 → onValueChange
    LaunchedEffect(blocks) {
        snapshotFlow { blocks.toMarkdown() }
            .distinctUntilChanged()
            .collectLatest { markdown ->
                lastInternalValue = markdown
                onValueChange(markdown)
            }
    }

    // Ctrl+A/C/Esc + 방향키 자동 해제 단축키 — Modifier 확장 헬퍼 (selection/SelectionUiHelpers.kt) 호출.
    val shortcutHandler = Modifier.documentSelectionShortcuts(
        rootBlocks = blocks,
        documentSelection = documentSelection,
    )

    // native BasicTextField selection 색과 documentSelection 시각화 색을 통합:
    // 사용자가 어느 메커니즘으로 selection 을 만들든 같은 색으로 보이도록 LocalTextSelectionColors
    // 의 backgroundColor 를 styleConfig.selectionAccent 로 동기화. handleColor 는 기존 값 유지
    // (handle 은 진한 색이어야 자연스러움).
    val existingSelectionColors = LocalTextSelectionColors.current
    val unifiedSelectionColors = remember(existingSelectionColors, styleConfig.selectionAccent) {
        TextSelectionColors(
            handleColor = existingSelectionColors.handleColor,
            backgroundColor = styleConfig.selectionAccent,
        )
    }

    CompositionLocalProvider(
        LocalTextSelectionColors provides unifiedSelectionColors,
        LocalDocumentSelection provides documentSelection,
    ) {
        Box(modifier = shortcutHandler) {
            MarkdownBlockEditor(
                blocks = blocks,
                onBlocksChanged = { newBlocks -> blocks = newBlocks },
                modifier = modifier,
                styleConfig = styleConfig,
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                isNested = false,
                documentSelection = documentSelection,
                containerPath = emptyList(),
            )
        }
    }
}

/**
 * Material3 테마를 자동 적용하는 블록 에디터.
 * v1 [com.ninetag.machum.markdown.ui.MarkdownTextField]의 drop-in replacement.
 */
@Composable
fun MarkdownBlockTextFieldM3(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    styleConfig: MarkdownStyleConfig = defaultMaterialBlockStyleConfig(),
) {
    MarkdownBlockTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        styleConfig = styleConfig,
    )
}

@Composable
private fun defaultMaterialBlockStyleConfig(): MarkdownStyleConfig {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val codeBlockBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val selectionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    val scheme = MaterialTheme.colorScheme
    val calloutStyles = remember(scheme) {
        mapOf(
            "NOTE" to CalloutDecorationStyle(
                scheme.primaryContainer.copy(alpha = 0.4f), scheme.primary
            ),
            "TIP" to CalloutDecorationStyle(
                scheme.tertiaryContainer.copy(alpha = 0.4f), scheme.tertiary
            ),
            "IMPORTANT" to CalloutDecorationStyle(
                scheme.secondaryContainer.copy(alpha = 0.4f), scheme.secondary
            ),
            "WARNING" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.3f), scheme.error.copy(alpha = 0.7f)
            ),
            "DANGER" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.4f), scheme.error
            ),
            "CAUTION" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.4f), scheme.error
            ),
            "QUESTION" to CalloutDecorationStyle(
                scheme.surfaceVariant.copy(alpha = 0.5f), scheme.onSurfaceVariant
            ),
            "SUCCESS" to CalloutDecorationStyle(
                scheme.tertiaryContainer.copy(alpha = 0.4f), scheme.tertiary
            ),
        )
    }

    return remember(linkColor, codeBackground, highlightColor, codeBlockBg, calloutStyles, selectionBg) {
        MarkdownStyleConfig(
            link = SpanStyle(color = linkColor),
            highlight = SpanStyle(background = highlightColor),
            codeInline = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.85.em),
            codeInlineBackground = codeBackground,
            codeBlockBackground = codeBlockBg,
            calloutStyles = calloutStyles,
            selectionAccent = selectionBg,
        )
    }
}
