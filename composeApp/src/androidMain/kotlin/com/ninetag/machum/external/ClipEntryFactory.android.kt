package com.ninetag.machum.external

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

actual fun clipEntryOf(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("markdown", text))

actual suspend fun readClipboardText(clipboard: Clipboard): String? {
    val clipData = clipboard.getClipEntry()?.clipData ?: return null
    for (index in 0 until clipData.itemCount) {
        clipData.getItemAt(index).text?.let { return it.toString() }
    }
    return null
}
