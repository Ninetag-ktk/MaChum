package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

internal actual suspend fun listDirectoryEntries(
    directory: PlatformFile,
): List<PlatformDirectoryEntry> = withContext(Dispatchers.IO) {
    directory.file.listFiles().orEmpty().map { child ->
        PlatformDirectoryEntry(
            platformFile = PlatformFile(child),
            name = child.name,
            isDirectory = child.isDirectory,
        )
    }
}

internal actual suspend fun validateWorkspaceTrashChild(parent: PlatformFile, child: PlatformFile): Unit = withContext(Dispatchers.IO) {
    validateWorkspaceTrashPath(parent, child, directory = true)
}

internal actual suspend fun validateWorkspaceTrashFile(parent: PlatformFile, file: PlatformFile): Unit = withContext(Dispatchers.IO) {
    validateWorkspaceTrashPath(parent, file, directory = false)
}

private fun validateWorkspaceTrashPath(parent: PlatformFile, child: PlatformFile, directory: Boolean) {
    val parentPath = parent.file.toPath().toAbsolutePath().normalize()
    val childPath = child.file.toPath().toAbsolutePath().normalize()
    check(!Files.isSymbolicLink(parentPath) && parentPath.toRealPath() == parentPath) { "휴지통 경로에 링크가 있습니다." }
    check(childPath.parent == parentPath && childPath.toRealPath().parent == parentPath) { "Vault 직속 경로가 아닙니다." }
    check(if (directory) Files.isDirectory(childPath, LinkOption.NOFOLLOW_LINKS)
        else Files.isRegularFile(childPath, LinkOption.NOFOLLOW_LINKS)) { "요청한 파일 또는 폴더가 아닙니다." }
    Files.walk(childPath).use { paths ->
        paths.forEach { path ->
            check(!Files.isSymbolicLink(path) && path.toRealPath().startsWith(childPath)) { "링크 또는 외부 경로가 포함되어 있습니다." }
        }
    }
}

internal actual suspend fun moveWorkspaceItemNative(vault: PlatformFile, sourceParent: PlatformFile, source: PlatformFile, entry: PlatformFile): PlatformFile = withContext(Dispatchers.IO) {
    check(sourceParent.file.toPath().toRealPath().startsWith(vault.file.toPath().toRealPath())) { "Vault 밖의 파일은 이동할 수 없습니다." }
    validateWorkspaceTrashPath(sourceParent, source, directory = source.file.isDirectory)
    val trash = PlatformFile(entry.file.parentFile)
    check(trash.name == WORKSPACE_TRASH_NAME)
    validateWorkspaceTrashChild(vault, trash)
    validateWorkspaceTrashChild(trash, entry)
    val target = File(entry.file, source.name).toPath()
    check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "휴지통에 같은 이름이 있습니다." }
    // No copy/delete fallback and no REPLACE_EXISTING: a failed move leaves the source intact.
    PlatformFile(Files.move(source.file.toPath(), target).toFile())
}

internal actual suspend fun purgeWorkspaceTrashEntryNative(trash: PlatformFile, entry: PlatformFile): Unit = withContext(Dispatchers.IO) {
    check(trash.name == WORKSPACE_TRASH_NAME && workspaceTrashIdPattern.matches(entry.name))
    validateWorkspaceTrashChild(trash, entry)
    // walk does not follow links; revalidate each path and fail closed if the tree changed.
    val root = entry.file.toPath().toAbsolutePath().normalize()
    Files.walk(root).use { paths ->
        val entries = paths.filter {
            it != root && it != root.resolve(WORKSPACE_TRASH_RECEIPT) &&
                it != root.resolve(WORKSPACE_TRASH_RECEIPT_RECOVERY) &&
                it != root.resolve(WORKSPACE_TRASH_CONFIG_RECOVERY)
        }
            .sorted(Comparator.reverseOrder()).iterator()
        while (entries.hasNext()) {
            val path = entries.next()
            check(!Files.isSymbolicLink(path) && path.toRealPath().startsWith(root)) { "휴지통 항목 경로가 변경되었습니다." }
            Files.delete(path)
        }
    }
    Files.deleteIfExists(root.resolve(WORKSPACE_TRASH_CONFIG_RECOVERY))
    Files.deleteIfExists(root.resolve(WORKSPACE_TRASH_RECEIPT_RECOVERY))
    Files.delete(root.resolve(WORKSPACE_TRASH_RECEIPT))
    Files.delete(root)
}

internal actual suspend fun FileManager.createFile(
    parentDirectory: PlatformFile,
    name: String,
    content: String,
): PlatformFile? = createFileWithContentWriter(parentDirectory, name, content) { output, text ->
    output.write(text.toByteArray(Charsets.UTF_8))
}

internal suspend fun FileManager.createFileWithContentWriter(
    parentDirectory: PlatformFile,
    name: String,
    content: String,
    writeContent: (java.io.OutputStream, String) -> Unit,
): PlatformFile? = withContext(Dispatchers.IO) {
    val siblings = parentDirectory.file.listFiles() ?: return@withContext null
    val names = siblings.map { it.name.lowercase() }.toSet()
    var fileName = name
    var index = 1
    while ("$fileName.md".lowercase() in names) fileName = "${name}_${index++}"
    val newFile = File(parentDirectory.file, "$fileName.md")
    // CREATE_NEW prevents a late collision from truncating another writer's file.
    val output = try {
        Files.newOutputStream(newFile.toPath(), StandardOpenOption.CREATE_NEW)
    } catch (_: FileAlreadyExistsException) {
        return@withContext null
    }
    try {
        output.use { writeContent(it, content) }
    } catch (error: Exception) {
        throw incompleteFileCreation(PlatformFile(newFile), content, error)
    }
    PlatformFile(newFile)
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

internal actual suspend fun FileManager.setConfig(
    parentDirectory: PlatformFile,
    fileName: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        if (!parentDirectory.exists()) return@withContext null
        val newFile = parentDirectory / fileName
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

internal actual suspend fun FileManager.createFolderExclusive(
    parentDirectory: PlatformFile,
    name: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    val siblings = parentDirectory.file.listFiles() ?: return@withContext null
    if (siblings.any { it.name.equals(name, ignoreCase = true) }) return@withContext null
    // createDirectory fails when the target appears concurrently; mkdirs/reuse are inappropriate.
    try {
        PlatformFile(Files.createDirectory(File(parentDirectory.file, name).toPath()).toFile())
    } catch (_: FileAlreadyExistsException) {
        null
    }
}

internal actual suspend fun FileManager.deleteEmptyFolderExclusive(directory: PlatformFile): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Files.delete(directory.file.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }

internal actual suspend fun FileManager.renameMarkdownExact(
    parentDirectory: PlatformFile,
    file: PlatformFile,
    name: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        if (!parentDirectory.exists()) return@withContext null
        val extension = file.name.substringAfterLast('.', missingDelimiterValue = "md")
        val target = File(parentDirectory.file, "$name.$extension")
        if (target.exists()) return@withContext null
        if (!file.file.renameTo(target)) return@withContext null
        PlatformFile(target)
    } catch (error: Exception) {
        null
    }
}

internal actual suspend fun moveProjectFileNative(
    project: PlatformFile,
    sourceParent: PlatformFile,
    source: PlatformFile,
    targetParent: PlatformFile,
): PlatformFile = withContext(Dispatchers.IO) {
    val projectPath = project.file.toPath().toAbsolutePath().normalize()
    check(!Files.isSymbolicLink(projectPath) && Files.isDirectory(projectPath, LinkOption.NOFOLLOW_LINKS)) {
        "현재 Project 경로가 올바르지 않습니다."
    }
    val projectReal = projectPath.toRealPath()

    fun validatedProjectDirectory(directory: PlatformFile): java.nio.file.Path {
        val path = directory.file.toPath().toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Project 폴더가 올바르지 않습니다."
        }
        val real = path.toRealPath()
        check(real == projectReal || real.parent == projectReal) {
            "Project 루트 또는 직속 폴더가 아닙니다."
        }
        return real
    }

    val sourceDirectory = validatedProjectDirectory(sourceParent)
    val targetDirectory = validatedProjectDirectory(targetParent)
    check(sourceDirectory != targetDirectory) { "같은 폴더로 이동할 수 없습니다." }

    val sourcePath = source.file.toPath().toAbsolutePath().normalize()
    check(sourcePath.parent == sourceDirectory && !Files.isSymbolicLink(sourcePath) &&
        Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS) &&
        sourcePath.toRealPath().parent == sourceDirectory && source.name.endsWith(".md", ignoreCase = true)) {
        "Project 폴더의 직속 Markdown 파일이 아닙니다."
    }
    val targetPath = targetDirectory.resolve(source.name)
    check(!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) { "대상 폴더에 같은 이름의 문서가 있습니다." }
    check(Files.list(targetDirectory).use { children ->
        children.noneMatch { it.fileName.toString().equals(source.name, ignoreCase = true) }
    }) { "대상 폴더에 같은 이름의 항목이 있습니다." }

    // No copy/delete fallback and no REPLACE_EXISTING: provider ownership and bytes stay intact.
    PlatformFile(Files.move(sourcePath, targetPath).toFile())
}

internal actual suspend fun FileManager.renameDirectoryExact(
    parentDirectory: PlatformFile,
    directory: PlatformFile,
    name: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        if (!parentDirectory.exists() || !directory.exists()) return@withContext null
        val target = File(parentDirectory.file, name)
        if (target.exists()) return@withContext null
        if (!directory.file.renameTo(target)) return@withContext null
        PlatformFile(target)
    } catch (error: Exception) {
        null
    }
}

internal actual suspend fun FileManager.deleteDirectoryExact(
    directory: PlatformFile,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val children = directory.file.listFiles() ?: return@withContext false
        if (children.any { child -> child.isDirectory ||
                (!child.name.endsWith(".md", ignoreCase = true) && !isManagedFolderIdentity(PlatformFile(child))) }) {
            return@withContext false
        }
        children.all(File::delete) && directory.file.delete()
    } catch (error: Exception) {
        false
    }
}
