package com.ninetag.machum.markdown.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens

internal enum class EditorQuickBarCommand(val label: String, val description: String) {
    DL("DL", "DL 콜아웃 삽입"),
    UNDO("취소", "실행 취소"),
    REDO("다시", "다시 실행"),
    INDENT("들여쓰기", "들여쓰기"),
    OUTDENT("내어쓰기", "내어쓰기"),
    PASTE("붙여넣기", "붙여넣기"),
    COPY("복사", "복사"),
    CUT("잘라내기", "잘라내기"),
}

/** Focus stays in the input; horizontalScroll owns overflow gestures. */
@Composable
internal fun EditorQuickBar(
    enabled: (EditorQuickBarCommand) -> Boolean,
    onCommand: (EditorQuickBarCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.wrapContentWidth().padding(
            horizontal = MarkdownEditorStyleTokens.quickBarHorizontalPadding,
            vertical = MarkdownEditorStyleTokens.quickBarVerticalPadding,
        ),
        shape = RoundedCornerShape(MarkdownEditorStyleTokens.quickBarCornerRadius),
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())
            .testTag("editor-quick-bar")
            .padding(horizontal = MarkdownEditorStyleTokens.quickBarInnerHorizontalPadding)) {
            EditorQuickBarCommand.entries.forEach { command ->
                IconButton(
                    onClick = { onCommand(command) },
                    enabled = enabled(command),
                    modifier = Modifier.size(MarkdownEditorStyleTokens.quickBarButtonSize)
                        .focusProperties { canFocus = false }
                        .testTag("editor-quick-bar-${command.name.lowercase()}")
                        .semantics { contentDescription = command.description },
                ) {
                    Icon(
                        imageVector = when (command) {
                            EditorQuickBarCommand.DL -> Icons.AutoMirrored.Outlined.Chat
                            EditorQuickBarCommand.UNDO -> Icons.AutoMirrored.Outlined.Undo
                            EditorQuickBarCommand.REDO -> Icons.AutoMirrored.Outlined.Redo
                            EditorQuickBarCommand.INDENT -> Icons.AutoMirrored.Outlined.FormatIndentIncrease
                            EditorQuickBarCommand.OUTDENT -> Icons.AutoMirrored.Outlined.FormatIndentDecrease
                            EditorQuickBarCommand.PASTE -> Icons.Outlined.ContentPaste
                            EditorQuickBarCommand.COPY -> Icons.Outlined.ContentCopy
                            EditorQuickBarCommand.CUT -> Icons.Outlined.ContentCut
                        },
                        contentDescription = null,
                        modifier = Modifier.size(MarkdownEditorStyleTokens.quickBarIconSize),
                    )
                }
            }
        }
    }
}
