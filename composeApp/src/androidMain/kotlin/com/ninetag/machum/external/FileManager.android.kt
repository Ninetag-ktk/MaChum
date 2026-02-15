package com.ninetag.machum.external

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue

internal actual suspend fun FileManager.createFile(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val koin = object : KoinComponent {
            val context: Context by inject()
        }
        val parentDoc = DocumentFile.fromTreeUri(
            koin.context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"))
            ?:return@withContext null

        var fileName = name
        var index = 1
        var existing = parentDoc.findFile("${fileName}.md")

        while (existing != null && existing.isFile) {
            fileName = "${name}_${index}"
            existing = parentDoc.findFile("${fileName}.md")
            index ++
        }

        val newFile = parentDoc.createFile("text/markdown", "${fileName}.md")?:return@withContext null
        PlatformFile(newFile.uri)
    } catch (e: Exception) {
        println("파일 생성 실패: $e")
        throw e
    }
}

internal actual suspend fun FileManager.createFolder(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val koin = object : KoinComponent {
            val context: Context by inject()
        }
        val parentDoc = DocumentFile.fromTreeUri(
            koin.context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"))
            ?:return@withContext null
        val existing = parentDoc.findFile(name)
        if (existing != null && existing.isDirectory) return@withContext  PlatformFile(existing.uri)
        val newDir = parentDoc.createDirectory(name)?:return@withContext  null
        PlatformFile(newDir.uri)
    } catch (e: Exception) {
        println("폴더 생성 실패: $e")
        throw e
    }
}

internal actual suspend fun FileManager.setConfig(
    parentDirectory: PlatformFile
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val koin = object : KoinComponent {
            val context: Context by inject()
        }
        val parentDoc = DocumentFile.fromTreeUri(
            koin.context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"))
            ?:return@withContext null
        val existing = parentDoc.findFile(".machum.json")
        if (existing != null && existing.isFile) return@withContext PlatformFile(existing.uri)
        val newFile = parentDoc.createFile("application/json", ".machum.json")?:return@withContext null
        PlatformFile(newFile.uri)
    } catch (e: Exception) {
        throw e
    }
}

internal actual suspend fun FileManager.validPermission(file: PlatformFile): Boolean {
    return try {
        val koin = object : KoinComponent {
            val context: Context by inject()
        }
        koin.context.contentResolver
            .persistedUriPermissions
            .any {
                it.isReadPermission &&
                        it.isWritePermission
            }
    } catch (e: Exception) {
        println("Config 생성 실패: $e")
        throw e
    }
}

internal actual fun PlatformFile.getLastModified(): Long? {
    return try {
        val koin = object : KoinComponent {
            val context: Context by inject()
        }
        val cursor = koin.context.contentResolver.query(
            toAndroidUri("com.ninetag.machum.fileprovider"),
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(0)
            } else null
        }
    } catch (e: Exception) {
        println("파일 메타데이터 쿼리 실패: $e")
        throw e
    }
}

internal actual fun String.forPlatformFile(): String = this.replace("/", "%2F")