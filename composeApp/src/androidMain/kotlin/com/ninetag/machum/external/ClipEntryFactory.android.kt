package com.ninetag.machum.external

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo

actual fun clipEntryOf(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("markdown", text))

actual suspend fun readClipboardText(clipboard: Clipboard): String? {
    val clipData = clipboard.getClipEntry()?.clipData ?: return null
    for (index in 0 until clipData.itemCount) {
        clipData.getItemAt(index).text?.let { return it.toString() }
    }
    return null
}

@Composable
internal actual fun rememberClipboardHasText(clipboard: Clipboard, isActive: Boolean): Boolean {
    val native = clipboard.nativeClipboard
    val foreground = isActive && LocalWindowInfo.current.isWindowFocused
    var hasText by remember(native) { mutableStateOf(false) }
    DisposableEffect(native, foreground) {
        fun refresh() {
            hasText = try {
                native.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
            } catch (_: SecurityException) {
                false
            }
        }
        if (foreground) {
            val listener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }
            native.addPrimaryClipChangedListener(listener)
            refresh()
            onDispose { native.removePrimaryClipChangedListener(listener) }
        } else {
            hasText = false
            onDispose { }
        }
    }
    return foreground && hasText
}

@OptIn(ExperimentalFoundationApi::class)
actual fun consumeClipboardText(
    content: TransferableContent,
    consumeText: (String) -> Boolean,
): TransferableContent? {
    if (content.source != TransferableContent.Source.Clipboard) return content
    val clipData = content.clipEntry.clipData
    // Mixed payload must stay native: a partial paste could target a cell replaced by the TSV edit.
    if ((0 until clipData.itemCount).any {
        val item = clipData.getItemAt(it)
        item.text == null || item.uri != null || item.intent != null
    }) return content
    val texts = (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).text?.toString() }
    if (texts.isEmpty() || !consumeText(texts.joinToString("\n"))) return content
    return content.consume { it.text != null }
}
