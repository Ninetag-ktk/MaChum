package com.ninetag.machum.markdown.state

import com.ninetag.machum.markdown.service.*

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult

/**
 * TextBlock 의 OutputTransformation.
 *
 * - 비활성 줄: 문법 marker·링크 문법·목록 선행 공백을 출력 문자열에서 제거
 * - 활성 토큰: 해당 토큰의 문법 마커는 raw로 노출하고 내용 SpanStyle은 즉시 적용
 * - dissolve rawMode: 마커와 내용 스타일을 모두 적용하지 않는 완전 raw 표시
 *
 * 활성 판별: 커서가 위치한 토큰의 마커만 raw 표시. 그 외 모든 마커는 숨김 처리.
 * 포커스 없으면 모든 줄에 서식 적용 (raw zone 없음).
 */
internal class RawMarkdownOutputTransformation(
    private val config: MarkdownStyleConfig = MarkdownStyleConfig(),
    private val context: MarkdownRenderContext = MarkdownRenderContext.RootText,
    private val isInternalLinkResolved: (String) -> Boolean = { true },
    private val explicitRawLinkRange: () -> IntRange? = { null },
) : OutputTransformation {

    private var cachedText: String = ""
    private var cachedScan = ScanResult(emptyList(), emptyList())
    private var previewOffsetMapping = MarkdownPreviewOffsetMapping(emptyList())
    private var sourceLength = 0
    private var visualLength = 0
    private var visualInteractions: List<VisualMarkdownInteraction> = emptyList()
    private var layoutMappingSnapshot = MarkdownLayoutMappingSnapshot(
        sourceText = "",
        visualText = "",
        offsetMapping = MarkdownPreviewOffsetMapping(emptyList()),
    )

    val taskCheckboxes: List<VisualTaskCheckbox>
        get() = if (context == MarkdownRenderContext.TableCell) emptyList() else visualInteractions.mapNotNull { visual ->
            (visual.interaction as? MarkdownInteraction.TaskCheckbox)?.let {
                VisualTaskCheckbox(visual.range, it.checked)
            }
        }

    var externalLinkIndicators: List<VisualExternalLinkIndicator> = emptyList()
        private set
    var linkActionSlots: List<VisualMarkdownInteraction> = emptyList()
        private set
    var linkActionSlotStyle: SpanStyle by mutableStateOf(SpanStyle(
        fontSize = MarkdownEditorStyleTokens.bodyFontSize,
        letterSpacing = (MarkdownEditorStyleTokens.inlineActionHorizontalPadding.value * 2).sp,
    ))
    var linkActionLineHeight: TextUnit by mutableStateOf(MarkdownEditorStyleTokens.bodyLineHeight)
    private var measuredLinkActionHeights: Map<MarkdownInteraction, TextUnit> by mutableStateOf(emptyMap())

    fun updateLinkActionHeights(layout: TextLayoutResult, density: Density) {
        if (layout.layoutInput.text.text != layoutMappingSnapshot.visualText) return
        measuredLinkActionHeights = linkActionSlots.associate { slot ->
            val line = layout.getLineForOffset(slot.range.first)
            slot.interaction to with(density) { (layout.getLineBottom(line) - layout.getLineTop(line)).toSp() }
        }
    }

    fun sourceToVisualOffset(offset: Int): Int =
        previewOffsetMapping.sourceToVisual(offset.coerceIn(0, sourceLength)).coerceIn(0, visualLength)

    fun visualToSourceOffset(offset: Int): Int =
        previewOffsetMapping.visualToSource(offset.coerceIn(0, visualLength)).coerceIn(0, sourceLength)

    fun displayIndentSourceOffsetAt(visualOffset: Int): Int? =
        previewOffsetMapping.normalizedSourceOffsetAt(visualOffset.coerceIn(0, visualLength))

    /** Immutable mapping generation paired with the text layout produced from it. */
    fun currentLayoutMappingSnapshot(): MarkdownLayoutMappingSnapshot = layoutMappingSnapshot

    fun interactionAtVisualOffset(visualOffset: Int): MarkdownInteraction? {
        val safeOffset = visualOffset.coerceIn(0, visualLength)
        return visualInteractions.firstOrNull { safeOffset in it.range }?.interaction
    }

    fun isInteractionVisible(interaction: MarkdownInteraction): Boolean =
        visualInteractions.any { it.interaction == interaction }

    fun interactionAtVisualPosition(
        position: androidx.compose.ui.geometry.Offset,
        layout: androidx.compose.ui.text.TextLayoutResult,
        checkboxSize: Float,
        hitSlop: Float,
    ): MarkdownInteraction? {
        if (layout.layoutInput.text.text != layoutMappingSnapshot.visualText) return null
        val layoutLength = layout.layoutInput.text.length
        val checkbox = taskCheckboxes
            .mapNotNull { task ->
                if (task.range.first !in 0 until layoutLength) return@mapNotNull null
                val bounds = layout.getBoundingBox(task.range.first)
                val line = layout.getLineForOffset(task.range.first)
                val center = androidx.compose.ui.geometry.Offset(
                    x = bounds.center.x,
                    y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f,
                )
                task to center
            }
            .filter { (_, center) ->
                val halfHitSize = checkboxSize / 2f + hitSlop
                position.x >= center.x - halfHitSize && position.x <= center.x + halfHitSize &&
                    position.y >= center.y - halfHitSize && position.y <= center.y + halfHitSize
            }
            .minByOrNull { (_, center) ->
                val dx = position.x - center.x
                val dy = position.y - center.y
                dx * dx + dy * dy
            }
            ?.first
        if (checkbox != null) {
            return visualInteractions.firstOrNull { it.range == checkbox.range }?.interaction
        }
        val offset = layout.getOffsetForPosition(position)
        return interactionAtVisualOffset(offset)
            ?: if (offset > 0 && layout.getBoundingBox(offset - 1).contains(position)) {
                // Text hit-testing returns caret boundaries; the final glyph can round past the link.
                interactionAtVisualOffset(offset - 1)
            } else null
    }

    /** DrawBehind 에서 사용하는 출력 좌표의 데코레이션 블록 목록 (BLOCKQUOTE, HORIZONTAL_RULE) */
    var blockRanges: List<BlockRange> = emptyList()
        private set

    /** rawMode가 아닌 줄의 inline code 본문 범위 (DrawBehind 에서 RoundRect 외곽선 그리기용) */
    var inlineCodeRanges: List<IntRange> = emptyList()
        private set

    /**
     * 현재 raw zone 의 텍스트 범위 목록. transformOutput 시 갱신.
     * - isRawMode=true → 전체 텍스트 (모든 줄이 raw)
     * - isFocused=true → 활성 토큰의 문법 마커
     * - 그 외 → 빈 리스트
     *
     * BlockDecorationDrawer 가 BLOCKQUOTE 좌측 바를 그릴 때 이 범위와 겹치는 줄은 skip.
     * (raw 마커가 보이는 상태에서 좌측 바도 함께 그리면 시각적 충돌)
     */
    var currentRawZones: List<IntRange> = emptyList()
        private set

    /**
     * BasicTextField의 포커스 여부. false이면 커서 줄 예외(raw zone)를 적용하지 않는다.
     */
    var isFocused: Boolean by mutableStateOf(false)

    /**
     * dissolve 된 raw TextBlock(rawMode=true) 인지 여부.
     * true 면 전체 텍스트가 raw zone 으로 처리되어 인라인 서식이 모든 줄에서 비활성.
     * (docs/markdown-editor.md의 dissolve 정책)
     */
    var isRawMode: Boolean by mutableStateOf(false)

    override fun TextFieldBuffer.transformOutput() {
        val text = toString()
        if (text.isEmpty()) {
            previewOffsetMapping = MarkdownPreviewOffsetMapping(emptyList())
            sourceLength = 0
            visualLength = 0
            layoutMappingSnapshot = MarkdownLayoutMappingSnapshot(
                sourceText = "",
                visualText = "",
                offsetMapping = previewOffsetMapping,
            )
            blockRanges = emptyList()
            inlineCodeRanges = emptyList()
            currentRawZones = emptyList()
            visualInteractions = emptyList()
            externalLinkIndicators = emptyList()
            linkActionSlots = emptyList()
            return
        }

        // 텍스트가 변경된 경우에만 semantic scan을 다시 수행한다.
        if (text != cachedText) {
            cachedText = text
            cachedScan = if (context == MarkdownRenderContext.TableCell) {
                ScanResult(InlineStyleScanner.computeMultiLineSpans(text, 0), emptyList())
            } else MarkdownPatternScanner.scan(text)
        }

        val presentation = markdownPresentation(
            text = text,
            context = context,
            config = config,
            isFocused = isFocused,
            isRawMode = isRawMode,
            selectionStart = selection.start,
            selectionEnd = selection.end,
            scanResult = cachedScan,
            explicitRawLinkRange = explicitRawLinkRange(),
            linkActionSlotStyle = linkActionSlotStyle,
            linkActionLineHeight = linkActionLineHeight,
            measuredLinkActionHeights = measuredLinkActionHeights,
        )
        previewOffsetMapping = MarkdownPreviewOffsetMapping(presentation.edits)
        sourceLength = text.length
        visualLength = presentation.visualText.length
        layoutMappingSnapshot = MarkdownLayoutMappingSnapshot(
            sourceText = text,
            visualText = presentation.visualText,
            offsetMapping = previewOffsetMapping,
        )
        presentation.edits.asReversed().forEach { edit ->
            replace(edit.range.first, edit.range.last + 1, edit.replacement)
        }
        presentation.styledRanges.forEach { styled ->
            val start = styled.range.first.coerceIn(0, length)
            val end = (styled.range.last + 1).coerceIn(start, length)
            if (start < end) addStyle(styled.style, start, end)
        }
        presentation.paragraphStyledRanges.forEach { styled ->
            val start = styled.range.first.coerceIn(0, length)
            val end = (styled.range.last + 1).coerceIn(start, length)
            if (start < end) addStyle(styled.style, start, end)
        }
        markdownInteractions(text).forEach { interaction ->
            val internal = interaction as? MarkdownInteraction.InternalLink ?: return@forEach
            if (isInternalLinkResolved(internal.target)) return@forEach
            val outputRange = previewOffsetMapping.mapRange(internal.activationRange) ?: return@forEach
            val start = outputRange.first.coerceIn(0, length)
            val end = (outputRange.last + 1).coerceIn(start, length)
            if (start < end) addStyle(config.unresolvedLink, start, end)
        }
        currentRawZones = presentation.rawZones
        blockRanges = presentation.blockRanges
        inlineCodeRanges = presentation.inlineCodeRanges
        val interactions = markdownInteractions(text)
        linkActionSlots = interactions.filterIsInstance<MarkdownInteraction.ExternalLink>().mapNotNull { interaction ->
            val mappedSyntax = previewOffsetMapping.mapRange(interaction.syntaxRange)
            val isRaw = mappedSyntax != null && presentation.rawZones.any(mappedSyntax::intersectsMarkdown)
            if (isRaw) return@mapNotNull null
            previewOffsetMapping.insertionRangeAt(
                sourceOffset = interaction.activationRange.last + 1,
                replacementLength = EXTERNAL_LINK_INDICATOR_PLACEHOLDER.length,
            )?.let { VisualMarkdownInteraction(it, interaction) }
        }
        externalLinkIndicators = linkActionSlots.mapNotNull { slot ->
            (slot.interaction as? MarkdownInteraction.ExternalLink)?.let { VisualExternalLinkIndicator(slot.range, it) }
        }
        visualInteractions = interactions.mapNotNull { interaction ->
            if (context == MarkdownRenderContext.TableCell && interaction is MarkdownInteraction.TaskCheckbox) {
                return@mapNotNull null
            }
            val mappedSyntax = previewOffsetMapping.mapRange(interaction.syntaxRange)
            val isRaw = mappedSyntax != null && presentation.rawZones.any(mappedSyntax::intersectsMarkdown)
            interaction.activationRange(isRaw)?.let(previewOffsetMapping::mapRange)?.let { range ->
                val activationRange = if (interaction is MarkdownInteraction.ExternalLink) {
                    val indicator = linkActionSlots.firstOrNull { it.interaction == interaction }
                    indicator?.let { range.first..it.range.last } ?: range
                } else range
                VisualMarkdownInteraction(activationRange, interaction)
            }
        }
    }

}

internal data class MarkdownLayoutMappingSnapshot(
    val sourceText: String,
    val visualText: String,
    private val offsetMapping: MarkdownPreviewOffsetMapping,
) {
    fun sourceToVisualOffset(offset: Int): Int =
        offsetMapping.sourceToVisual(offset.coerceIn(0, sourceText.length)).coerceIn(0, visualText.length)

    fun visualToSourceOffset(offset: Int): Int =
        offsetMapping.visualToSource(offset.coerceIn(0, visualText.length)).coerceIn(0, sourceText.length)
}

internal data class VisualMarkdownInteraction(
    val range: IntRange,
    val interaction: MarkdownInteraction,
)

internal enum class MarkdownSpanApplication {
    Everywhere,
    OutsideRawZones,
}

internal data class MarkdownPreviewEdit(
    val range: IntRange,
    val replacement: String,
    val normalizePointerToStart: Boolean = false,
)

internal fun markdownPreviewEdits(
    text: String,
    spans: List<MarkdownSpan>,
    rawZones: List<IntRange>,
    isRawMode: Boolean,
    includeListMarkers: Boolean = true,
    includeParagraphIndents: Boolean = true,
    includeAutomaticParagraphIndents: Boolean = true,
    reserveInternalLinkActionSlots: Boolean = false,
): List<MarkdownPreviewEdit> {
    if (isRawMode) return emptyList()

    val lists = if (includeListMarkers) markdownListPresentations(text) else emptyList()
    val orderedMarkerPadding = orderedMarkerPaddingByList(text, lists)
    val hiddenSyntaxEdits = spans.asSequence()
        .filter { it.role == MarkdownInlineRole.HiddenSyntax }
        .map(MarkdownSpan::range)
        .filterNot { range -> rawZones.any(range::intersectsMarkdown) }
        .distinct()
        .map { range -> MarkdownPreviewEdit(range, "") }
    val syntaxMarkerEdits = spans.asSequence()
        .filter { it.role == MarkdownInlineRole.Marker }
        .map(MarkdownSpan::range)
        .filterNot { range -> rawZones.any(range::intersectsMarkdown) }
        .distinct()
        .map { range -> MarkdownPreviewEdit(range, "") }
    val markerEdits = if (includeListMarkers) {
        lists.asSequence()
            .map { list ->
                val sourceMarker = text.substring(list.markerRange)
                val marker = if (list.hasRawPrefix(rawZones)) {
                    sourceMarker
                } else {
                    when (list.taskChecked) {
                        true, false -> "□"
                        null -> if (list.isOrdered) sourceMarker else "•"
                    }
                }
                val displayMarker = marker + orderedMarkerPadding[list.markerRange.first].orEmpty()
                MarkdownPreviewEdit(
                    range = list.markerRange,
                    replacement = displayMarker,
                )
            }
    } else {
        emptySequence()
    }
    val listIndentEdits = if (includeListMarkers) {
        lists.asSequence()
            .map { list ->
                MarkdownPreviewEdit(
                    list.range.first until list.markerRange.first,
                    MarkdownEditorStyleTokens.listIndent(list.depth),
                    normalizePointerToStart = true,
                )
            }
    } else {
        emptySequence()
    }

    val paragraphIndentEdits = if (includeParagraphIndents) {
        markdownPlainParagraphRanges(text, includeLeadingWhitespace = true).asSequence()
            .flatMap { range ->
                val leadingSpaces = text.substring(range).takeWhile { it == ' ' }.length
                if (leadingSpaces == 0) {
                    if (!includeAutomaticParagraphIndents || text[range.first].isWhitespace()) emptySequence() else sequenceOf(
                        MarkdownPreviewEdit(
                            range.first until range.first,
                            MarkdownEditorStyleTokens.paragraphFirstLineIndent,
                            normalizePointerToStart = true,
                        ),
                    )
                } else {
                    (0 until leadingSpaces / 2).asSequence().map { pair ->
                        val start = range.first + pair * 2
                        MarkdownPreviewEdit(
                            start until start + 2,
                            MarkdownEditorStyleTokens.paragraphFirstLineIndent,
                        )
                    }
                }
            }
    } else {
        emptySequence()
    }

    val externalLinkIndicatorEdits = markdownInteractions(text).asSequence()
        .filter { it is MarkdownInteraction.ExternalLink || reserveInternalLinkActionSlots && it is MarkdownInteraction.InternalLink }
        .filterNot { link -> rawZones.any(link.syntaxRange::intersectsMarkdown) }
        .map { link ->
            val sourceOffset = link.activationRange.last + 1
            MarkdownPreviewEdit(
                range = sourceOffset until sourceOffset,
                replacement = if (link is MarkdownInteraction.InternalLink) INTERNAL_LINK_ACTION_PLACEHOLDER else EXTERNAL_LINK_INDICATOR_PLACEHOLDER,
                normalizePointerToStart = true,
            )
        }

    return (hiddenSyntaxEdits + syntaxMarkerEdits + markerEdits + listIndentEdits + paragraphIndentEdits + externalLinkIndicatorEdits)
        .sortedWith(compareBy<MarkdownPreviewEdit>({ it.range.first }, { if (it.range.isEmpty()) 0 else 1 }))
        .toList()
}

internal class MarkdownPreviewOffsetMapping(
    private val edits: List<MarkdownPreviewEdit>,
) {
    fun sourceToVisual(offset: Int): Int = mapBoundary(offset, isEnd = false)

    fun visualToSource(offset: Int): Int {
        var sourceCursor = 0
        var visualCursor = 0
        for (edit in edits) {
            val editStart = edit.range.first
            val editEnd = edit.range.last + 1
            val unchangedLength = editStart - sourceCursor
            if (offset < visualCursor + unchangedLength) {
                return sourceCursor + (offset - visualCursor).coerceAtLeast(0)
            }
            visualCursor += unchangedLength
            sourceCursor = editStart

            val replacementLength = edit.replacement.length
            val sourceLength = (editEnd - editStart).coerceAtLeast(0)
            val replacementEnd = visualCursor + replacementLength
            if (edit.normalizePointerToStart && offset < replacementEnd) return editStart
            if (replacementLength == 0 && offset == visualCursor) return editEnd
            if (sourceLength == 0 && offset < replacementEnd) return editStart
            if (sourceLength > 0 && replacementLength > 0 && offset <= replacementEnd) {
                val relative = (offset - visualCursor).coerceIn(0, replacementLength)
                return editStart + (relative * sourceLength / replacementLength)
            }
            visualCursor += replacementLength
            sourceCursor = editEnd
        }
        return sourceCursor + (offset - visualCursor).coerceAtLeast(0)
    }

    fun mapRange(range: IntRange): IntRange? {
        if (range.isEmpty()) return null
        val start = mapBoundary(range.first, isEnd = false)
        val end = mapBoundary(range.last + 1, isEnd = true)
        return if (start < end) start until end else null
    }

    fun normalizedSourceOffsetAt(visualOffset: Int): Int? {
        var sourceCursor = 0
        var visualCursor = 0
        for (edit in edits) {
            val editStart = edit.range.first
            val unchangedLength = editStart - sourceCursor
            visualCursor += unchangedLength
            sourceCursor = editStart

            val replacementEnd = visualCursor + edit.replacement.length
            if (edit.normalizePointerToStart && visualOffset in visualCursor until replacementEnd) return editStart
            sourceCursor = if (edit.range.isEmpty()) editStart else edit.range.last + 1
            visualCursor = replacementEnd
        }
        return null
    }

    fun insertionRangeAt(sourceOffset: Int, replacementLength: Int): IntRange? {
        if (replacementLength <= 0) return null
        val start = mapBoundary(sourceOffset, isEnd = true)
        return start until (start + replacementLength)
    }

    private fun mapBoundary(offset: Int, isEnd: Boolean): Int {
        var delta = 0
        for (edit in edits) {
            val editStart = edit.range.first
            val editEnd = edit.range.last + 1
            if (edit.range.isEmpty()) {
                if (offset < editStart) return offset + delta
                if (offset == editStart) {
                    return offset + delta + if (isEnd) 0 else edit.replacement.length
                }
                delta += edit.replacement.length
                continue
            }
            if (offset <= editStart) return offset + delta
            if (offset >= editEnd) {
                delta += edit.replacement.length - (editEnd - editStart)
                continue
            }
            return editStart + delta + if (isEnd) edit.replacement.length else 0
        }
        return offset + delta
    }
}

internal data class MarkdownListPresentation(
    val range: IntRange,
    val depth: Int,
    val markerRange: IntRange,
    val isOrdered: Boolean,
    val taskChecked: Boolean? = null,
    val prefixRange: IntRange = range.first..(markerRange.last + 1),
)

internal fun MarkdownListPresentation.hasRawPrefix(rawZones: List<IntRange>): Boolean {
    return rawZones.any(prefixRange::intersectsMarkdown)
}

internal fun markdownListPresentations(text: String): List<MarkdownListPresentation> = buildList {
    var lineStart = 0
    while (lineStart < text.length) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline
        val line = text.substring(lineStart, lineEnd)
        val match = listLinePresentationRegex.find(line)
        if (match != null) {
            val indent = match.groupValues[1]
            val marker = match.groupValues[2]
            val taskState = match.groups[3]?.value?.takeIf(String::isNotEmpty)
            var columns = 0
            for (character in indent) columns += if (character == '\t') 4 else 1
            add(
                MarkdownListPresentation(
                    range = lineStart until (if (newline < 0) lineEnd else lineEnd + 1),
                    depth = (columns + LIST_INDENT_COLUMNS - 1) / LIST_INDENT_COLUMNS,
                    markerRange = if (taskState == null) {
                        (lineStart + indent.length) until (lineStart + indent.length + marker.length)
                    } else {
                        (lineStart + indent.length) until (lineStart + match.value.length - 1)
                    },
                    isOrdered = marker.first().isDigit(),
                    taskChecked = taskState?.equals("x", ignoreCase = true),
                    prefixRange = lineStart until (lineStart + match.value.length),
                ),
            )
        }
        if (newline < 0) break
        lineStart = newline + 1
    }
}

private fun orderedMarkerPaddingByList(
    text: String,
    lists: List<MarkdownListPresentation>,
): Map<Int, String> = buildMap {
    var blockStart = 0
    while (blockStart < lists.size) {
        var blockEnd = blockStart + 1
        while (blockEnd < lists.size && lists[blockEnd].range.first == lists[blockEnd - 1].range.last + 1) {
            blockEnd++
        }

        val block = lists.subList(blockStart, blockEnd)
        val maxDigitsByDepth = block.asSequence()
            .filter { it.isOrdered && it.taskChecked == null }
            .groupBy(MarkdownListPresentation::depth)
            .mapValues { (_, entries) ->
                entries.maxOf { entry -> text.substring(entry.markerRange).count(Char::isDigit) }
            }
        block.asSequence()
            .filter { it.isOrdered && it.taskChecked == null }
            .forEach { entry ->
                val digits = text.substring(entry.markerRange).count(Char::isDigit)
                val missingDigits = maxDigitsByDepth.getValue(entry.depth) - digits
                if (missingDigits > 0) {
                    put(entry.markerRange.first, ORDERED_LIST_ALIGNMENT_SPACE.repeat(missingDigits))
                }
            }
        blockStart = blockEnd
    }
}

private const val LIST_INDENT_COLUMNS = 4
private const val ORDERED_LIST_ALIGNMENT_SPACE = "\u2007"
private val listLinePresentationRegex =
    Regex("^([ \\t]*)([-*+]|\\d+\\.)[ \\t]{1,4}(?:\\[([ xX])][ \\t]+)?")

internal fun markdownPlainParagraphRanges(
    text: String,
    includeLeadingWhitespace: Boolean = false,
): List<IntRange> = buildList {
    var lineStart = 0
    while (lineStart < text.length) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline
        val line = text.substring(lineStart, lineEnd)
        val content = if (includeLeadingWhitespace) line.trimStart(' ', '\t', '\u3000') else line
        if (isPlainParagraphLine(content)) {
            add(lineStart until if (newline < 0) lineEnd else lineEnd + 1)
        }
        if (newline < 0) break
        lineStart = newline + 1
    }
}

internal fun isPlainParagraphLine(line: String): Boolean {
    if (line.isBlank() || line == EditorBlock.BLANK_LINE_MARKER || line.first().isWhitespace()) return false
    if (listLinePresentationRegex.find(line) != null) return false
    if (headingLineRegex.containsMatchIn(line) || line.startsWith(">")) return false
    if (line.startsWith("![[") || line.startsWith("```") || line.startsWith("~~~")) return false
    val compact = line.filterNot(Char::isWhitespace)
    if (compact.length >= 3 && compact.first() in "-*_" && compact.all { it == compact.first() }) return false
    return true
}

private val headingLineRegex = Regex("^#{1,6} ")

/**
 * OutputTransformation의 interaction 상태별 span 적용 정책.
 *
 * - unfocused: marker와 content를 모두 preview
 * - focused: 활성 토큰의 marker/prefix만 raw, content style은 유지
 * - rawMode: 전체 raw zone 바깥에만 적용(실제로는 전체가 raw이므로 모든 style 비활성)
 */
internal fun markdownSpanApplication(
    role: MarkdownInlineRole,
    isFocused: Boolean,
    isRawMode: Boolean,
): MarkdownSpanApplication {
    if (isRawMode) return MarkdownSpanApplication.OutsideRawZones
    if (!isFocused) return MarkdownSpanApplication.Everywhere

    return if (role.isSyntax) {
        MarkdownSpanApplication.OutsideRawZones
    } else {
        MarkdownSpanApplication.Everywhere
    }
}

internal fun activeMarkdownSyntaxZones(
    spans: List<MarkdownSpan>,
    selectionStart: Int,
    selectionEnd: Int,
): List<IntRange> {
    val cursorStart = minOf(selectionStart, selectionEnd)
    val cursorEnd = maxOf(selectionStart, selectionEnd)
    val syntax = spans.filter { it.role.isSyntax }
    val activeContent = spans
        .asSequence()
        .filterNot { it.role.isSyntax }
        .filter { span -> cursorStart >= span.range.first && cursorEnd <= span.range.last + 1 }
        .minByOrNull { span -> span.range.last - span.range.first }
        ?.range
    if (activeContent != null) {
        return syntaxMarkersAround(activeContent, syntax)
    }
    val directlySelected = syntax
        .filter { span ->
            if (cursorStart == cursorEnd &&
                (span.role == MarkdownInlineRole.BulletPrefix ||
                    span.role == MarkdownInlineRole.OrderedPrefix ||
                    span.role is MarkdownInlineRole.TaskPrefix)
            ) {
                cursorStart in span.range
            } else {
                cursorStart <= span.range.last + 1 && cursorEnd >= span.range.first
            }
        }
        .map(MarkdownSpan::range)
    val markerAdjacentContent = spans
        .asSequence()
        .filterNot { it.role.isSyntax }
        .filter { span -> directlySelected.any { marker ->
            marker.last + 1 == span.range.first || span.range.last + 1 == marker.first
        } }
        .minByOrNull { span -> span.range.last - span.range.first }
        ?.range
    return markerAdjacentContent?.let { syntaxMarkersAround(it, syntax) } ?: directlySelected
}

private fun syntaxMarkersAround(
    content: IntRange,
    syntax: List<MarkdownSpan>,
): List<IntRange> = syntax
    .filterNot { span ->
        span.role == MarkdownInlineRole.BulletPrefix ||
            span.role == MarkdownInlineRole.OrderedPrefix ||
            span.role is MarkdownInlineRole.TaskPrefix
    }
    .filter { span -> span.range.last + 1 == content.first || span.range.first == content.last + 1 }
    .map(MarkdownSpan::range)
