package com.ninetag.machum.external

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

actual fun clipEntryOf(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("markdown", text))
