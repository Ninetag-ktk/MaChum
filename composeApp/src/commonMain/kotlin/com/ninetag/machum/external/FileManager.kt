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
import io.github.vinceglb.filekit.fromBookmarkData
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class FileManager(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val BOOKMARK_DIRECTORY = byteArrayPreferencesKey("bookmark_directory")
        private val BOOKMARK_FILE = byteArrayPreferencesKey("bookmark_file")
    }

    suspend fun setPreferences(bookmark: Bookmarks) {
        dataStore.edit { pref ->
            bookmark.directory?.let { pref[BOOKMARK_DIRECTORY] = it }
            bookmark.file?.let { pref[BOOKMARK_FILE] = it }?:pref.remove(BOOKMARK_FILE)
        }
    }

    suspend fun getPreferences(): Bookmarks {
        return dataStore.data.first().let{pref ->
            Bookmarks(
                directory = pref[BOOKMARK_DIRECTORY],
                file = pref[BOOKMARK_FILE]
            )
        }
    }

    suspend fun clearPreferences() {
        dataStore.edit { pref ->
            pref.remove(BOOKMARK_DIRECTORY)
            pref.remove(BOOKMARK_FILE)
        }
    }

    suspend fun pickDirectory(): PlatformFile? {
        try {
            val initDirectory = getPreferences().directory?.let {PlatformFile.fromBookmarkData(it)}
            val directory = FileKit.openDirectoryPicker(
                title = "폴더 선택",
                directory = initDirectory,
            )
            if (directory != null) setPreferences(Bookmarks(directory = directory.bookmarkData().bytes))
            return directory
        } catch (e: Exception) {
            println(e.message)
            return null
        }
    }

    suspend fun pickFile(): PlatformFile? {
        val bookmarks = getPreferences()
        val initDirectory = bookmarks.directory?.let{PlatformFile.fromBookmarkData(it) }
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
            setPreferences(Bookmarks(
                file = file.bookmarkData().bytes,
            ))
        }
        return file
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

    suspend fun list(directory: PlatformFile): List<PlatformFile> = withContext(Dispatchers.IO) { directory.list() }
}

@Suppress("ArrayInDataClass")
data class Bookmarks(
    val directory: ByteArray? = null,
    val file: ByteArray? = null,
)