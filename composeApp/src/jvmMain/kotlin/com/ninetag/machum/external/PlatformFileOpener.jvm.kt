package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop

internal actual suspend fun openPlatformFile(file: PlatformFile): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        check(Desktop.isDesktopSupported())
        Desktop.getDesktop().open(file.file)
    }.isSuccess
}
