package com.ninetag.machum.commit

import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal actual fun sha256Utf8(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

internal actual suspend fun FileManager.createCommitStorageFile(
    parentDirectory: PlatformFile,
    name: String,
    mimeType: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    val target = File(parentDirectory.file, name)
    when {
        target.isFile -> PlatformFile(target)
        target.exists() -> null
        target.createNewFile() -> PlatformFile(target)
        else -> null
    }
}

