package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.TextUnit

/** Markdown meaning, independent from theme values and Compose style identity. */
internal sealed interface MarkdownInlineRole {
    data object Marker : MarkdownInlineRole
    data object HiddenSyntax : MarkdownInlineRole
    data object Bold : MarkdownInlineRole
    data object Italic : MarkdownInlineRole
    data object BoldItalic : MarkdownInlineRole
    data object Strikethrough : MarkdownInlineRole
    data object Highlight : MarkdownInlineRole
    data object InlineCodeMarker : MarkdownInlineRole
    data object InlineCode : MarkdownInlineRole
    data object Link : MarkdownInlineRole
    /** Keeps ![[...]] distinct so a later cell renderer can replace it without reparsing source. */
    data object Embed : MarkdownInlineRole
    data class Heading(val level: Int) : MarkdownInlineRole
    data object BulletPrefix : MarkdownInlineRole
    data object OrderedPrefix : MarkdownInlineRole
    data class TaskPrefix(val checked: Boolean) : MarkdownInlineRole
    data object CheckedTaskBody : MarkdownInlineRole
    data object BlockTransparent : MarkdownInlineRole
}

internal data class MarkdownSpan(
    val range: IntRange,
    val role: MarkdownInlineRole,
)

internal enum class MarkdownRenderContext {
    RootText,
    NestedText,
    TableCell,
}

internal data class MarkdownStyledRange(
    val range: IntRange,
    val style: SpanStyle,
)

internal data class MarkdownParagraphStyledRange(
    val range: IntRange,
    val style: ParagraphStyle,
)

/** A single, pure result shared by output rendering, decoration, and table measurement. */
internal data class MarkdownPresentation(
    val visualText: String,
    val styledRanges: List<MarkdownStyledRange>,
    val paragraphStyledRanges: List<MarkdownParagraphStyledRange>,
    val blockRanges: List<BlockRange>,
    val inlineCodeRanges: List<IntRange>,
    val rawZones: List<IntRange>,
    val edits: List<MarkdownPreviewEdit>,
)

internal fun markdownPresentation(
    text: String,
    context: MarkdownRenderContext,
    config: MarkdownStyleConfig,
    isFocused: Boolean = false,
    isRawMode: Boolean = false,
    selectionStart: Int = 0,
    selectionEnd: Int = 0,
    scanResult: ScanResult? = null,
    explicitRawLinkRange: IntRange? = null,
    reserveInternalLinkActionSlots: Boolean = false,
    linkActionSlotStyle: SpanStyle? = null,
    linkActionLineHeight: TextUnit = MarkdownEditorStyleTokens.bodyLineHeight,
    measuredLinkActionHeights: Map<MarkdownInteraction, TextUnit> = emptyMap(),
): MarkdownPresentation {
    if (text.isEmpty()) {
        return MarkdownPresentation("", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    val scan = scanResult ?: if (context == MarkdownRenderContext.TableCell) {
        ScanResult(InlineStyleScanner.computeMultiLineSpans(text, 0), emptyList())
    } else MarkdownPatternScanner.scan(text)
    val sourceRawZones = when {
        isRawMode -> listOf(0 until text.length)
        !isFocused -> emptyList()
        else -> {
            val links = markdownInteractions(text).filter { it.isOrdinaryLink() }
            activeMarkdownSyntaxZones(scan.spans, selectionStart, selectionEnd)
                .flatMap { zone -> subtractMarkdownRanges(zone, links.map { it.syntaxRange }) } +
                listOfNotNull(explicitRawLinkRange)
        }
    }
    val edits = markdownPreviewEdits(
        text = text,
        spans = scan.spans,
        rawZones = sourceRawZones,
        isRawMode = isRawMode,
        includeListMarkers = context != MarkdownRenderContext.TableCell,
        includeParagraphIndents = context != MarkdownRenderContext.TableCell,
        includeAutomaticParagraphIndents = context == MarkdownRenderContext.RootText,
        reserveInternalLinkActionSlots = reserveInternalLinkActionSlots,
    )
    val mapping = MarkdownPreviewOffsetMapping(edits)
    val visualText = StringBuilder(text).apply {
        edits.asReversed().forEach { edit ->
            replace(edit.range.first, edit.range.last + 1, edit.replacement)
        }
    }.toString()

    val styledRanges = buildList {
        for (span in scan.spans) {
            if (span.role == MarkdownInlineRole.InlineCodeMarker && !isRawMode) {
                mapping.mapRange(span.range)?.let { outputRange ->
                    val isRevealed = sourceRawZones.any(span.range::intersectsMarkdown)
                    add(
                        MarkdownStyledRange(
                            outputRange,
                            config.inlineCode.text.copy(
                                color = if (isRevealed) config.inlineCode.text.color else Color.Transparent,
                            ),
                        ),
                    )
                }
                continue
            }
            val sourceRanges = when (markdownSpanApplication(span.role, isFocused, isRawMode)) {
                MarkdownSpanApplication.Everywhere -> listOf(span.range)
                MarkdownSpanApplication.OutsideRawZones -> subtractMarkdownRanges(span.range, sourceRawZones)
            }
            sourceRanges.forEach { sourceRange ->
                mapping.mapRange(sourceRange)?.let { outputRange ->
                    val style = if (span.role is MarkdownInlineRole.TaskPrefix && context != MarkdownRenderContext.TableCell) {
                        config.styleFor(span.role).copy(color = Color.Transparent)
                    } else config.styleFor(span.role)
                    add(MarkdownStyledRange(outputRange, style))
                }
            }
        }
    }
    val paragraphStyledRanges = if (isRawMode) emptyList() else scan.spans.asSequence()
        .mapNotNull { span ->
            val heading = span.role as? MarkdownInlineRole.Heading ?: return@mapNotNull null
            val lineStart = text.lastIndexOf('\n', span.range.first).let { it + 1 }
            val nextNewline = text.indexOf('\n', span.range.last + 1)
            val lineEndExclusive = if (nextNewline < 0) text.length else nextNewline + 1
            val headingLineHeight = config.headingStyle(heading.level).fontSize *
                MarkdownEditorStyleTokens.headingLineHeightMultiplier
            mapping.mapRange(lineStart until lineEndExclusive)?.let { visualRange ->
                MarkdownParagraphStyledRange(
                    range = visualRange,
                    style = ParagraphStyle(
                        lineHeight = if (headingLineHeight < MarkdownEditorStyleTokens.bodyLineHeight) {
                            MarkdownEditorStyleTokens.bodyLineHeight
                        } else {
                            headingLineHeight
                        },
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None,
                            mode = LineHeightStyle.Mode.Fixed,
                        ),
                    ),
                )
            }
        }
        .distinctBy { it.range.first }
        .toList()
    val actionStyles = if (linkActionSlotStyle == null || isRawMode) emptyList() else markdownInteractions(text)
        .filter { it is MarkdownInteraction.ExternalLink || reserveInternalLinkActionSlots && it is MarkdownInteraction.InternalLink }
        .filterNot { link -> sourceRawZones.any(link.syntaxRange::intersectsMarkdown) }
        .mapNotNull { link ->
            val range = mapping.insertionRangeAt(link.activationRange.last + 1, 1) ?: return@mapNotNull null
            val fontSize = linkActionSlotStyle.fontSize.takeOrElse { MarkdownEditorStyleTokens.bodyFontSize }
            val headingHeight = paragraphStyledRanges.firstOrNull { range.first in it.range }?.style?.lineHeight
            val lineHeight = measuredLinkActionHeights[link] ?: headingHeight ?: linkActionLineHeight
            val heightSp = if (lineHeight.isEm) lineHeight.value * fontSize.value else lineHeight.value
            val spaceWidth = fontSize.value * if (link is MarkdownInteraction.InternalLink) 0.5f else 1f
            MarkdownStyledRange(range, linkActionSlotStyle.copy(
                letterSpacing = (linkActionSlotStyle.letterSpacing.value + heightSp - spaceWidth).sp,
            ))
        }
    return MarkdownPresentation(
        visualText = visualText,
        styledRanges = styledRanges + actionStyles,
        paragraphStyledRanges = paragraphStyledRanges,
        blockRanges = scan.blocks.mapNotNull { block ->
            mapping.mapRange(block.textRange)?.let { block.copy(textRange = it) }
        },
        inlineCodeRanges = if (isRawMode) emptyList() else scan.spans.mapNotNull { span ->
            if (span.role == MarkdownInlineRole.InlineCode) mapping.mapRange(span.range) else null
        },
        rawZones = sourceRawZones.mapNotNull(mapping::mapRange),
        edits = edits,
    )
}

internal fun MarkdownStyleConfig.styleFor(role: MarkdownInlineRole): SpanStyle = when (role) {
    MarkdownInlineRole.Marker -> marker
    MarkdownInlineRole.HiddenSyntax -> hiddenSyntax
    MarkdownInlineRole.Bold -> bold
    MarkdownInlineRole.Italic -> italic
    MarkdownInlineRole.BoldItalic -> boldItalic
    MarkdownInlineRole.Strikethrough -> strikethrough
    MarkdownInlineRole.Highlight -> highlight
    MarkdownInlineRole.InlineCodeMarker -> inlineCode.text.copy(color = Color.Transparent)
    MarkdownInlineRole.InlineCode -> inlineCode.text
    MarkdownInlineRole.Link -> link
    MarkdownInlineRole.Embed -> link
    is MarkdownInlineRole.Heading -> headingStyle(role.level)
    MarkdownInlineRole.BulletPrefix -> bulletPrefix
    MarkdownInlineRole.OrderedPrefix -> orderedPrefix
    is MarkdownInlineRole.TaskPrefix -> bulletPrefix
    MarkdownInlineRole.CheckedTaskBody -> checkedTaskBody
    MarkdownInlineRole.BlockTransparent -> blockTransparent
}

internal val MarkdownInlineRole.isSyntax: Boolean
    get() = when (this) {
        MarkdownInlineRole.Marker,
        MarkdownInlineRole.InlineCodeMarker,
        MarkdownInlineRole.HiddenSyntax,
        MarkdownInlineRole.BulletPrefix,
        MarkdownInlineRole.OrderedPrefix,
        is MarkdownInlineRole.TaskPrefix,
        MarkdownInlineRole.BlockTransparent -> true
        else -> false
    }

private fun subtractMarkdownRanges(range: IntRange, exclusions: List<IntRange>): List<IntRange> {
    var segments = listOf(range)
    for (exclusion in exclusions) {
        segments = segments.flatMap { segment ->
            if (!segment.intersectsMarkdown(exclusion)) listOf(segment) else buildList {
                if (segment.first < exclusion.first) add(segment.first until exclusion.first)
                if (segment.last > exclusion.last) add((exclusion.last + 1)..segment.last)
            }
        }
    }
    return segments
}

internal fun IntRange.intersectsMarkdown(other: IntRange): Boolean =
    first <= other.last && last >= other.first
