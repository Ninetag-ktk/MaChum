package com.ninetag.machum.external

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.FlavorListener
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

@OptIn(ExperimentalComposeUiApi::class)
actual fun clipEntryOf(text: String): ClipEntry =
    ClipEntry(StringSelection(text))

@OptIn(ExperimentalComposeUiApi::class)
actual suspend fun readClipboardText(clipboard: Clipboard): String? {
    return try {
        val transferable = clipboard.getClipEntry()?.asAwtTransferable ?: return null
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        withContext(Dispatchers.IO) {
            transferable.getTransferData(DataFlavor.stringFlavor)
        } as? String
    } catch (_: IllegalStateException) {
        null
    } catch (_: UnsupportedFlavorException) {
        null
    } catch (_: IOException) {
        null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun rememberClipboardHasText(clipboard: Clipboard, isActive: Boolean): Boolean {
    val native = remember(clipboard) { runCatching { clipboard.awtClipboard }.getOrNull() }
    val foreground = isActive && LocalWindowInfo.current.isWindowFocused
    var hasText by remember(native) { mutableStateOf(false) }
    DisposableEffect(native, foreground) {
        fun refresh() {
            hasText = try {
                native?.isDataFlavorAvailable(DataFlavor.stringFlavor) == true
            } catch (_: IllegalStateException) {
                false
            }
        }
        if (foreground && native != null) {
            val listener = FlavorListener { refresh() }
            native.addFlavorListener(listener)
            refresh()
            onDispose { native.removeFlavorListener(listener) }
        } else {
            hasText = false
            onDispose { }
        }
    }
    return foreground && hasText
}

// Compose Desktop does not support contentReceiver; hardware paste keeps its existing path.
@OptIn(ExperimentalFoundationApi::class)
actual fun consumeClipboardText(
    content: TransferableContent,
    consumeText: (String) -> Boolean,
): TransferableContent? = content
