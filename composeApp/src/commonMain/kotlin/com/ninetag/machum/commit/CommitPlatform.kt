package com.ninetag.machum.commit

import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile

internal expect fun sha256Utf8(value: String): String

internal expect suspend fun FileManager.createCommitStorageFile(
    parentDirectory: PlatformFile,
    name: String,
    mimeType: String,
): PlatformFile?

