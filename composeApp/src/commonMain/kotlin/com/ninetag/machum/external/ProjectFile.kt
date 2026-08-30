package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile

private fun normalizeRelativePath(relativePath: String, allowEmpty: Boolean): String {
    val normalized = relativePath
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }

    require(allowEmpty || normalized.isNotEmpty()) { "relativePath must identify a file" }
    require(".." !in normalized) { "relativePath must stay inside the project" }
    return normalized.joinToString("/")
}

/** 프로젝트 base(`""`) 또는 하위 폴더를 식별하는 정규화된 상대 경로. */
@JvmInline
value class FolderKey private constructor(val relativePath: String) {
    fun file(fileName: String): FileKey {
        require('/' !in fileName && '\\' !in fileName) {
            "fileName must not contain a path separator"
        }
        return FileKey.of(if (relativePath.isEmpty()) fileName else "$relativePath/$fileName")
    }

    override fun toString(): String = relativePath

    companion object {
        val Base: FolderKey = FolderKey("")

        fun of(relativePath: String): FolderKey =
            FolderKey(normalizeRelativePath(relativePath, allowEmpty = true))
    }
}

/**
 * 프로젝트 안에서 파일을 식별하는 정규화된 상대 경로.
 *
 * 플랫폼 절대 경로나 파일명만 사용하지 않으므로, 이후 하위 폴더를 탐색할 때도
 * 서로 다른 폴더의 동명 파일을 안전하게 구분할 수 있다.
 */
@JvmInline
value class FileKey private constructor(val relativePath: String) {
    val fileName: String
        get() = relativePath.substringAfterLast('/')

    val folder: FolderKey
        get() = FolderKey.of(relativePath.substringBeforeLast('/', missingDelimiterValue = ""))

    fun rename(newFileName: String): FileKey {
        require('/' !in newFileName && '\\' !in newFileName) {
            "newFileName must not contain a path separator"
        }
        val parent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        return of(if (parent.isEmpty()) newFileName else "$parent/$newFileName")
    }

    override fun toString(): String = relativePath

    companion object {
        fun of(relativePath: String): FileKey {
            return FileKey(normalizeRelativePath(relativePath, allowEmpty = false))
        }
    }
}

/** 프로젝트 폴더의 상대 경로 정체성과 플랫폼 파일 객체. */
data class ProjectFolder(
    val key: FolderKey,
    val platformFile: PlatformFile,
)

/** 파일 시스템 객체와 프로젝트 상대 경로 정체성을 함께 운반한다. */
data class ProjectFile(
    val key: FileKey,
    val platformFile: PlatformFile,
)
