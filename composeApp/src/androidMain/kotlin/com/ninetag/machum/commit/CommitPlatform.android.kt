package com.ninetag.machum.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
    val koin = object : KoinComponent {
        val context: Context by inject()
    }
    val parent = DocumentFile.fromTreeUri(
        koin.context,
        parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"),
    ) ?: return@withContext null
    parent.findFile(name)?.let { existing ->
        return@withContext existing.takeIf(DocumentFile::isFile)?.let { PlatformFile(it.uri) }
    }
    parent.createFile(mimeType, name)?.let { PlatformFile(it.uri) }
}

