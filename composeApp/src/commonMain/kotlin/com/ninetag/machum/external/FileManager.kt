package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.bookmarkData
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.fromBookmarkData
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileManager(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val BOOKMARK_VAULT = byteArrayPreferencesKey("bookmark_vault")
        private val BOOKMARK_PROJECT = byteArrayPreferencesKey("bookmark_project")
        private val BOOKMARK_FILE = byteArrayPreferencesKey("bookmark_file")
    }

    suspend fun setPreferences(bookmark: Bookmarks) {
        dataStore.edit { pref ->
            bookmark.vaultData?.let { pref[BOOKMARK_VAULT] = it.bookmarkData().bytes }
            bookmark.projectData?.let { pref[BOOKMARK_PROJECT] = it.bookmarkData().bytes }?:pref.remove(BOOKMARK_PROJECT)
            bookmark.fileData?.let { pref[BOOKMARK_FILE] = it.bookmarkData().bytes }?:pref.remove(BOOKMARK_FILE)
        }
        _bookmarks.value = bookmark
    }

    suspend fun getPreferences(): Bookmarks {
        return dataStore.data.first().let{ pref ->
            Bookmarks(
                vaultData = pref[BOOKMARK_VAULT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) },
                projectData = pref[BOOKMARK_PROJECT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) },
                fileData = pref[BOOKMARK_FILE]?.let { PlatformFile.fromBookmarkDataWithValidate(it) },
            )
        }
    }


    suspend fun clearPreferences() {
        dataStore.edit { pref ->
            pref.remove(BOOKMARK_VAULT)
            pref.remove(BOOKMARK_PROJECT)
            pref.remove(BOOKMARK_FILE)
        }
        _bookmarks.value = getPreferences()
    }

    private val _bookmarks = MutableStateFlow<Bookmarks?>(null)
    val bookmarks: StateFlow<Bookmarks?> = _bookmarks.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            _bookmarks.value = getPreferences()
        }
    }

    /**
     * 사용자가 직접 지정하는 루트 디렉토리
     * @return 앱이 실행될 디렉토리 지정
     */
    suspend fun pickVault(): PlatformFile? = withContext(Dispatchers.IO) {
        try {
            val initVault = getPreferences().vaultData
            val vault = FileKit.openDirectoryPicker(
                title = "폴더 선택",
                directory = initVault,
            )
            vault?.let{ setPreferences(Bookmarks(vaultData = it)) }
            vault
        } catch (e: Exception) {
            println(e.message)
            null
        }
    }

    /**
     * 사용자가 작업을 진행할 프로젝트 산텍 / 목록 방식
     * 자동으로 해당 프로젝트의 마지막 마크다운을 읽어옴
     * @param project 선택한 프로젝트 폴더
     * @return 해당 프로젝트 폴더의 마지막 마크다운 파일
     */
    suspend fun pickProject(project: PlatformFile): PlatformFile? = withContext(Dispatchers.IO) {
        val file = listFile(project).lastOrNull()
        setPreferences(getPreferences().copy(projectData = project, fileData = file))
        file
    }

    /**
     * 사용자가 작업할 파일 선택
     * 목록에서 선택하거나, 손가락 인터랙션으로 탐색
     * @param file 선택한 마크다운 파일
     * @return 선택한 마크다운 파일
     */
    suspend fun pickFile(file: PlatformFile): PlatformFile = withContext(Dispatchers.IO) {
        setPreferences(getPreferences().copy(fileData = file))
        file
    }

    suspend fun pickFileTest(): PlatformFile? {
        val bookmarks = getPreferences()
        val initDirectory = bookmarks.projectData
        val file = FileKit.openFilePicker(
            title = "파일 선택",
            type = FileKitType.File("md"),
            mode = FileKitMode.Single,
            directory = initDirectory,
        )
        try {
            println("파일: $file")
            println("파일 parent: ${file?.toString()}")
        } catch (e: Exception) {
            println(e.message)
        }
        if (file != null) {
            setPreferences(
                getPreferences().copy(fileData = file,)
            )
        }
        return file
    }

    /**
     * Vault 내부의 프로젝트 폴더 목록
     * @param vault 사용자 지정 또는 북마크에서 복원된 루트 디렉토리 경로
     * @return 프로젝트 폴더 목록
     */
    suspend fun listProject(vault: PlatformFile): List<PlatformFile> = withContext(Dispatchers.IO) {
        vault.list().filter { it.isDirectory() }
    }

    /**
     * Project 내부의 마크다운 파일 목록
     * @param project 사용자 선택 또는 북마크에서 복원된 프로젝트 폴더 경로
     * @return 마크다운 파일 목록
     */
    suspend fun listFile(project: PlatformFile): List<PlatformFile> = withContext(Dispatchers.IO) {
        project.list().filter { it.name.endsWith(".md") }
    }

    /**
     * 파일 읽기
     * @param file 읽을 파일
     * @return 파일 내용 (UTF-8)
     */
    suspend fun read(file: PlatformFile): String = withContext(Dispatchers.IO) { file.readString() }

    /**
     * 파일 쓰기 (생성 & 수정)
     * @param file 선택한 파일
     * @param content 파일 내용
     */
    suspend fun write(file: PlatformFile, content: String) = withContext(Dispatchers.IO) { file.writeString(content) }

    /**
     * 파일 삭제
     * @param file 삭제할 파일
     * @return 성공 여부
     */
    suspend fun delete(file: PlatformFile) = withContext(Dispatchers.IO) {
        try {
            file.delete()
            true
        } catch (e: Exception) {
            println(e)
            false
        }
    }

    suspend fun setVault(parentDirectory: PlatformFile, name: String): PlatformFile? = withContext(Dispatchers.IO) { createFolder(parentDirectory, name) }

    suspend fun createProject(name: String): PlatformFile? = withContext(Dispatchers.IO) { createFolder(_bookmarks.value!!.vaultData!!, name) }
}

/**
 * 디렉토리 생성
 * @param parentDirectory 폴더를 생성할 부모 디렉토리
 * @param name 생성할 폴더명
 * @return 생성된 디렉토리
 */
internal expect suspend fun FileManager.createFolder(parentDirectory: PlatformFile, name: String): PlatformFile?

internal expect suspend fun FileManager.createFile(parentDirectory: PlatformFile, name: String): PlatformFile?

fun PlatformFile.Companion.fromBookmarkDataWithValidate(bytes: ByteArray): PlatformFile? {
    val data = PlatformFile.fromBookmarkData(bytes)
    return if (data.exists()) data else null
}

data class Bookmarks(
    val vaultData: PlatformFile? = null,
    val projectData: PlatformFile? = null,
    val fileData: PlatformFile? = null,
)