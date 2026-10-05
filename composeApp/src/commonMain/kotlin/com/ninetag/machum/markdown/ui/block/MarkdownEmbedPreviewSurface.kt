package com.ninetag.machum.markdown.ui.block

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.state.MarkdownEmbedPreview
import com.ninetag.machum.markdown.ui.LocalMarkdownInteractionHandlers
import com.ninetag.machum.markdown.ui.LocalMarkdownCompletionRevision
import com.ninetag.machum.markdown.ui.waitForMarkdownPointerRelease
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The same preview survives source editing; display aliases are not part of the load key. */
@Composable
internal fun MarkdownEmbedPreviewSurface(
    target: String,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
    val canonicalTarget = target.substringBefore('|').trim()
    val completionRevision = LocalMarkdownCompletionRevision.current
    val latestResolver by rememberUpdatedState(LocalMarkdownInteractionHandlers.current.resolveEmbed)
    val latestEdit by rememberUpdatedState(onEdit)
    var preview by remember(canonicalTarget) { mutableStateOf<MarkdownEmbedPreview?>(null) }
    LaunchedEffect(canonicalTarget, completionRevision) {
        val resolved = latestResolver(canonicalTarget)
        currentCoroutineContext().ensureActive()
        preview = resolved
    }

    Box(modifier.fillMaxWidth()) {
        val editModifier = if (onEdit == null) Modifier else Modifier
            .semantics { onClick("임베드 원문 편집") { latestEdit?.invoke(); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) {
                        return@awaitEachGesture
                    }
                    val release = waitForMarkdownPointerRelease(down.id, down.position, viewConfiguration.touchSlop)
                        ?: return@awaitEachGesture
                    if (!release.exceededTouchSlop &&
                        release.change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                    ) latestEdit?.invoke()
                }
            }
        // The button is a sibling, so opening the target cannot also enter source editing.
        Box(Modifier.fillMaxWidth().padding(end = MarkdownEditorStyleTokens.blockActionSize + 4.dp).then(editModifier)) {
            when (val current = preview) {
                null -> Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is MarkdownEmbedPreview.Document -> MarkdownEmbedDocumentPreview(current.markdown, styleConfig, textStyle)
                is MarkdownEmbedPreview.Image -> Image(
                    current.image,
                    current.description,
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentScale = ContentScale.Fit,
                )
                is MarkdownEmbedPreview.Attachment -> Text(current.name, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is MarkdownEmbedPreview.Unavailable -> Text(current.message, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (preview != null && preview !is MarkdownEmbedPreview.Unavailable) {
            Box(
                Modifier.align(Alignment.TopEnd).size(MarkdownEditorStyleTokens.blockActionSize)
                    .clip(RoundedCornerShape(MarkdownEditorStyleTokens.blockActionCornerRadius)).clickable(
                    role = Role.Button,
                    onClickLabel = "임베드 대상 열기",
                    onClick = { onOpen(canonicalTarget) },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "임베드 대상 열기",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MarkdownEditorStyleTokens.blockActionIconSize),
                )
            }
        }
    }
}
