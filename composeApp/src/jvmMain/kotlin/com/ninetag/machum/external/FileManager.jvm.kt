package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun FileManager.createFolder(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val directory = PlatformFile(parentDirectory, name)
        directory.createDirectories()
        directory
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
        if (!parentDirectory.exists()) return@withContext null
        val newFile = parentDirectory / "$name.md"
        newFile.writeString("")
        newFile
    } catch (e: Exception) {
        println("파일 생성 실패: $e")
        null
    }
}