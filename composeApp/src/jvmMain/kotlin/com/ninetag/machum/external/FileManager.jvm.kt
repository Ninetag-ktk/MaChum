package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual suspend fun FileManager.createFile(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        if (!parentDirectory.exists()) return@withContext null

        var fileName = name
        var index = 1
        var newFile = parentDirectory / "$fileName.md"

        while (newFile.exists()) {
            fileName = "${name}_${index}"
            newFile = parentDirectory / "$fileName.md"
            index++
        }

        newFile.writeString("")
        newFile
    } catch (e: Exception) {
        println("파일 생성 실패: $e")
        null
    }
}

internal actual suspend fun FileManager.createFolder(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val directory = PlatformFile(parentDirectory, name)
        if (directory.exists()) return@withContext directory
        directory.createDirectories()
        directory
    } catch (e: Exception) {
        println("폴더 생성 실패: $e")
        throw e
    }
}

//internal actual suspend fun FileManager.renameMarkdown(
//    parentDirectory: PlatformFile,
//    file: PlatformFile,
//    name: String
//): PlatformFile? = withContext(Dispatchers.IO) {
//    try {
//        val doc = file.file
//        val parentDoc = File(parentDirectory.file, name)
//        if (!doc.renameTo())
//    }
//}
//
//private fun rotateMarkdownFileName(
//    parent: PlatformFile,
//    name: String,
//): String {
//    var fileName = name
//    var index = 1
//    var existing = parent.findFile("${fileName}.md")
//    while (existing != null && existing.isFile) {
//        fileName = "${name}_${index}"
//        existing = parent.findFile("${fileName}.md")
//        index ++
//    }
//    return fileName
//}

internal actual suspend fun FileManager.setConfig(
    parentDirectory: PlatformFile
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        if (!parentDirectory.exists()) return@withContext null
        val newFile = parentDirectory / ".machum.json"
        if (newFile.exists()) return@withContext newFile
        newFile.writeString("")
        newFile
    } catch (e: Exception) {
        println("Config 생성 실패: $e")
        throw e
    }
}

internal actual suspend fun FileManager.validPermission(file: PlatformFile): Boolean = true

internal actual fun PlatformFile.getLastModified(): Long? {
    return try {
        file.lastModified()
    } catch (e: Exception) {
        println("파일 메타데이터 쿼리 실패: $e")
        throw e
    }
}

internal actual fun String.forPlatformFile(): String = this