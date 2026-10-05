package com.ninetag.machum.markdown.service

import com.ninetag.machum.external.workspaceLoadDiagnosticsEnabled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Shared visual values for the Markdown editor. */
object MarkdownEditorStyleTokens {
    // 본문 typography
    val bodyFontSize: TextUnit = 16.sp
    val bodyLineHeight: TextUnit = bodyFontSize * 1.25f
    const val paragraphFirstLineIndent: String = "\u3000"

    // inline code typography
    val inlineCodeFontSize: TextUnit = 0.875.em
    val inlineCodeHorizontalPadding: Dp = 2.dp
    val inlineCodeCornerRadius: Dp = 4.dp
    val inlineCodeBorderWidth: Dp = 1.dp

    // task checkbox preview
    val taskCheckboxSize: Dp = 12.dp
    val taskCheckboxHitSlop: Dp = 10.dp

    // fenced code block layout
    val codeBlockPadding: Dp = 12.dp
    val codeBlockCornerRadius: Dp = 8.dp

    // Block actions: embed navigation and raw dissolve share the same dimensions.
    val blockActionSize: Dp = 28.dp
    val blockActionIconSize: Dp = 18.dp
    val blockActionCornerRadius: Dp = 6.dp
    val inlineActionHorizontalPadding: Dp = 2.dp

    // Quick bar layout
    val quickBarIconSize: Dp = 20.dp
    val quickBarButtonSize: Dp = 48.dp
    val quickBarHorizontalPadding: Dp = 8.dp
    val quickBarInnerHorizontalPadding: Dp = 4.dp
    val quickBarVerticalPadding: Dp = 4.dp
    val quickBarCornerRadius: Dp = 24.dp

    // 제목 typography
    val heading1FontSize: TextUnit = 26.sp
    val heading2FontSize: TextUnit = 22.sp
    val heading3FontSize: TextUnit = 20.sp
    val heading4FontSize: TextUnit = 18.sp
    val heading5FontSize: TextUnit = 16.sp
    val heading6FontSize: TextUnit = 14.sp
    const val headingLineHeightMultiplier: Float = 1.0f
    val headingOnSurfaceBlendFractions: List<Float> = listOf(0f, 0.08f, 0.16f, 0.24f, 0.32f, 0.40f)

    // 목록 preview indentation (output-only; never written into Markdown source)
    const val orderedListFontFeatures: String = "tnum"
    private const val listBaseIndent: String = "\u2003"
    private const val listDepthIndent: String = "\u2003\u2002"

    fun listIndent(depth: Int): String =
        listBaseIndent + listDepthIndent.repeat(depth.coerceAtLeast(0))

    // table layout
    val tableMinimumColumnWidth: TextUnit = 2.em
    val tableCellHorizontalPadding: Dp = 6.dp
    val tableCellVerticalPadding: Dp = 4.dp
    val tableBorderWidth: Dp = 0.5.dp
    val tableControlWidth: Dp = 16.dp
    val tableHandleThickness: Dp = 3.dp
    val tableAddControlWidth: Dp = 24.dp
    val tableAddControlHeight: Dp = 18.dp

    // 본문 layout
    val desktopMaxWidth: Dp = 700.dp
    val bodyHorizontalPadding: Dp = 16.dp
    val bodyTopPadding: Dp = 16.dp
    val bodyBottomPadding: Dp = 48.dp
}

/** Temporary visual diagnostics for Markdown layout work. Keep disabled for normal use. */
object MarkdownEditorDebugOptions {
    const val showTextLayoutGuides: Boolean = false
    val logImeDiagnostics: Boolean
        get() = workspaceLoadDiagnosticsEnabled()
    val textBlockBoundsColor: Color = Color(0xFFB455FF)
    val textBlockBoundsWidth: Dp = 1.dp
}

internal fun markdownHeadingColor(primary: Color, onSurface: Color, level: Int): Color =
    lerp(
        start = primary,
        stop = onSurface,
        fraction = MarkdownEditorStyleTokens.headingOnSurfaceBlendFractions[(level - 1).coerceIn(0, 5)],
    )
