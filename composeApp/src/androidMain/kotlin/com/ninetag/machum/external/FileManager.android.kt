package com.ninetag.machum.external

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue

internal actual suspend fun FileManager.createFolder(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val koin = object : KoinComponent {
            val context: Context by inject()
        }
        val parentDoc = DocumentFile.fromTreeUri(koin.context, parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"))?:return@withContext null
        val newDir = parentDoc.createDirectory(name)?:return@withContext null
        PlatformFile(newDir.uri)
    } catch (e: Exception) {
        println("폴더 생성 실패: $e")
        null
    }
}

internal actual suspend fun FileManager.createFile(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val koin = object : KoinComponent {
            val context: Context by inject()
        }
        val parentDoc = DocumentFile.fromTreeUri(koin.context, parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"))?:return@withContext null
        val existing = parentDoc.findFile("${name}.md")
        if (existing != null && existing.isFile) return@withContext PlatformFile(existing.uri)
        val newFile = parentDoc.createFile("text/markdown", "${name}.md")?:return@withContext null
        PlatformFile(newFile.uri)
    } catch (e: Exception) {
        println("파일 생성 실패: $e")
        null
    }
}