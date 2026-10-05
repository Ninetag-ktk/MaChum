package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.external.WorkspaceLinkScanner
import com.ninetag.machum.markdown.state.MarkdownInteraction
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.MarkdownBlockReferenceCreation
import com.ninetag.machum.markdown.state.markdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.markdownBlockReferenceReplacement
import com.ninetag.machum.markdown.state.MarkdownEmbedPreview
import com.ninetag.machum.markdown.state.RawMarkdownOutputTransformation
import com.ninetag.machum.markdown.state.VisualExternalLinkIndicator
import com.ninetag.machum.theme.platformUsesTouchUi

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.key.*
import com.ninetag.machum.markdown.state.markdownInteractions
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds

internal data class MarkdownInteractionHandlers(
    val openExternalLink: (String) -> Unit = {},
    val openInternalLink: (String) -> Unit = {},
    val completeInternalLink: (MarkdownLinkCompletionRequest) -> List<MarkdownLinkCompletionCandidate> = { emptyList() },
    val createBlockReference: suspend (MarkdownBlockReferenceCreation, () -> Boolean) -> String? = { _, _ -> null },
    val applyCurrentDocumentBlockReference: (TextFieldState, MarkdownLinkCompletionRequest, MarkdownLinkCompletionCandidate) -> Boolean = { _, _, _ -> false },
    val blockReferenceError: (String) -> Unit = {},
    val isInternalLinkResolved: (String) -> Boolean = { true },
    val resolveEmbed: suspend (String) -> MarkdownEmbedPreview = {
        MarkdownEmbedPreview.Unavailable("임베드 대상을 찾을 수 없습니다.")
    },
)

/** Both keyboard and popup selections use this guarded source edit. */
internal suspend fun applyMarkdownLinkCompletion(
    source: TextFieldState,
    request: MarkdownLinkCompletionRequest,
    candidate: MarkdownLinkCompletionCandidate,
    handlers: MarkdownInteractionHandlers,
    isEditing: () -> Boolean,
    beforeSourceEdit: () -> Unit = {},
): Boolean = coroutineScope {
    val operationJob = currentCoroutineContext()[Job]
    val originalText = source.text.toString()
    val originalSelection = source.selection
    val originalComposition = source.composition
    var abandoned = false
    val isCurrent = {
        !abandoned && operationJob?.isActive != false && isEditing() && source.composition == originalComposition &&
            source.text.toString() == originalText && source.selection == originalSelection &&
            markdownLinkCompletionRequest(source.text.toString(), source.selection) == request
    }
    if (!isCurrent()) return@coroutineScope false
    val creation = candidate.blockCreation
    if (creation?.isCurrentDocument == true) {
        val applied = try {
            handlers.applyCurrentDocumentBlockReference(source, request, candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!applied) handlers.blockReferenceError("블록 참조를 만들 수 없습니다. 대상을 다시 선택해 주세요.")
        return@coroutineScope applied
    }
    val observer = if (creation != null) launch {
        snapshotFlow { isCurrent() }.first { !it }
        abandoned = true
    } else null
    try {
        val replacement = if (creation != null) {
            if (markdownBlockReferenceReplacement(candidate.replacement, "") == null) return@coroutineScope false
            val id = handlers.createBlockReference(creation, isCurrent) ?: return@coroutineScope false
            if (!id.matches(Regex("[a-z0-9]+"))) return@coroutineScope false
            markdownBlockReferenceReplacement(candidate.replacement, id) ?: return@coroutineScope false
        } else candidate.replacement
        if (!isCurrent()) return@coroutineScope false
        beforeSourceEdit()
        source.edit {
            replace(request.replacementStart, request.replacementEndExclusive, replacement)
            selection = TextRange(request.replacementStart + replacement.length)
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        handlers.blockReferenceError("블록 참조를 만들 수 없습니다. 다시 시도해 주세요.")
        false
    } finally {
        abandoned = true
        observer?.cancel()
    }
}

internal val LocalMarkdownInteractionHandlers = staticCompositionLocalOf {
    MarkdownInteractionHandlers()
}

/** Open a completed internal link only when an unmodified key enters its visible boundary. */
internal fun handleInternalLinkRawKey(
    event: KeyEvent,
    source: TextFieldState,
    isFocused: Boolean,
    isRawMode: Boolean,
    explicitRawRange: IntRange?,
    onOpen: (String, IntRange) -> Unit,
): Boolean {
    if (!isFocused || isRawMode || event.type != KeyEventType.KeyDown ||
        event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed ||
        source.composition != null || !source.selection.collapsed
    ) return false
    if (event.key != Key.DirectionLeft && event.key != Key.DirectionRight && event.key != Key.Backspace) return false
    val text = source.text.toString()
    val caret = source.selection.start
    val excludedCode = WorkspaceLinkScanner.excludedCode(text)
    val link = markdownInteractions(text).filterIsInstance<MarkdownInteraction.InternalLink>().firstOrNull {
        val raw = explicitRawRange?.let { range -> it.syntaxRange.first <= range.last && range.first <= it.syntaxRange.last } == true
        !raw && !excludedCode[it.syntaxRange.first] && if (event.key == Key.DirectionRight) {
            caret == it.syntaxRange.first || caret == it.activationRange.first
        } else {
            caret == it.syntaxRange.last + 1 || caret == it.activationRange.last + 1
        }
    } ?: return false
    onOpen(text, link.syntaxRange)
    source.edit {
        selection = TextRange(if (event.key == Key.DirectionRight) link.activationRange.first else link.activationRange.last + 1)
    }
    return true
}

internal data class MarkdownPointerRelease(
    val change: PointerInputChange,
    val exceededTouchSlop: Boolean,
)

/** Observes the same gesture as BasicTextField without treating its selection consumption as cancellation. */
internal suspend fun AwaitPointerEventScope.waitForMarkdownPointerRelease(
    pointerId: PointerId,
    startPosition: Offset,
    touchSlop: Float,
): MarkdownPointerRelease? {
    var exceededTouchSlop = false
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Final)
            .changes
            .firstOrNull { it.id == pointerId }
            ?: return null
        if ((change.position - startPosition).getDistance() > touchSlop) {
            exceededTouchSlop = true
        }
        if (!change.pressed) return MarkdownPointerRelease(change, exceededTouchSlop)
    }
}

internal fun Modifier.observeMarkdownLinkHover(
    restartKey: Any?,
    markdownProvider: () -> RawMarkdownOutputTransformation,
    layoutProvider: () -> TextLayoutResult?,
    overlayHoveredProvider: () -> Boolean,
    onLinkHovered: (MarkdownInteraction?) -> Unit,
): Modifier {
    if (platformUsesTouchUi) return this
    return pointerInput(restartKey) {
        coroutineScope {
        val hoverScope = this
        awaitPointerEventScope {
            var clearJob: Job? = null

            fun scheduleClear() {
                clearJob?.cancel()
                clearJob = hoverScope.launch {
                    delay(150.milliseconds)
                    if (!overlayHoveredProvider()) onLinkHovered(null)
                }
            }

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Exit -> scheduleClear()
                    PointerEventType.Enter,
                    PointerEventType.Move,
                    -> {
                        val layout = layoutProvider() ?: continue
                        when (val interaction = markdownProvider().interactionAtVisualPosition(
                            change.position,
                            layout,
                            MarkdownEditorStyleTokens.taskCheckboxSize.toPx(),
                            MarkdownEditorStyleTokens.taskCheckboxHitSlop.toPx(),
                        )) {
                            is MarkdownInteraction.ExternalLink,
                            -> {
                                clearJob?.cancel()
                                onLinkHovered(interaction)
                            }
                            else -> scheduleClear()
                        }
                    }
                }
            }
        }
        }
    }
}

internal fun MarkdownInteraction.visualBodyEndBounds(
    markdown: RawMarkdownOutputTransformation,
    layout: TextLayoutResult,
): Rect? {
    if (!markdown.isInteractionVisible(this)) return null
    markdown.linkActionSlots.firstOrNull { it.interaction == this }?.let { slot ->
        val glyph = layout.getBoundingBox(slot.range.first)
        val line = layout.getLineForOffset(slot.range.first)
        return Rect(glyph.left, layout.getLineTop(line), glyph.right, layout.getLineBottom(line))
    }
    val visualStart = markdown.sourceToVisualOffset(activationRange.first)
    val visualEnd = markdown.sourceToVisualOffset(activationRange.last + 1)
    if (visualEnd <= visualStart || layout.lineCount == 0) return null
    val endOffset = (visualEnd - 1).coerceIn(0, layout.layoutInput.text.length)
    val line = layout.getLineForOffset(endOffset)
    val lineTop = layout.getLineTop(line)
    val lineBottom = layout.getLineBottom(line)
    val bodyEndX = layout.getHorizontalPosition(visualEnd, usePrimaryDirection = true)
    return Rect(bodyEndX, lineTop, bodyEndX, lineBottom)
}

internal fun VisualExternalLinkIndicator.visualBounds(layout: TextLayoutResult): Rect? {
    if (range.isEmpty() || layout.lineCount == 0) return null
    val offset = range.first.coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
    val line = layout.getLineForOffset(offset)
    val glyph = layout.getBoundingBox(offset)
    return Rect(glyph.left, layout.getLineTop(line), glyph.right, layout.getLineBottom(line))
}
