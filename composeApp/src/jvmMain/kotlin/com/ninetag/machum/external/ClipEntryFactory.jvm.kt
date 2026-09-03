package com.ninetag.machum.external

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
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
