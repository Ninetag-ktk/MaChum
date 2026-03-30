package com.ninetag.machum.screen.mainComposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.NoteFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.nameWithoutExtension
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MainViewModel(private val fileManager: FileManager) : ViewModel() {
    private val _fileList = MutableStateFlow<List<PlatformFile>>(emptyList())
    val fileList: StateFlow<List<PlatformFile>> = _fileList.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // key: 파일명, value: Markdown 인스턴스
    private val _noteFileCache = MutableStateFlow<Map<String, NoteFile>>(emptyMap())
    val noteFileCache: StateFlow<Map<String, NoteFile>> = _noteFileCache.asStateFlow()

    // debounce 저장을 위한 내부 트리거 (파일명, Markdown)
    private val _saveRequest = MutableStateFlow<Pair<String, NoteFile>?>(null)

    init {
        viewModelScope.launch {
            fileManager.bookmarks.collect { bookmarks ->
                val project = bookmarks.projectData ?: return@collect
                val list = fileManager.listFile(project)
                if (list != fileList.value) {
                    _fileList.value = list
                    val index = bookmarks.fileData
                        ?.let { file -> list.indexOfFirst { it.name == file.name } }
                        ?.takeIf { it != -1 }
                        ?: 0
                    _currentIndex.value = index
                }
            }
        }

        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _saveRequest
                .filterNotNull()
                .debounce(500L.milliseconds)
                .collect { (fileName, markdown) ->
                    val file = _fileList.value.find { it.name == fileName }?:return@collect
                    fileManager.writeMarkdown(file, markdown)
                }
        }
    }

    fun onPageChanged(index: Int) {
        viewModelScope.launch {
            val file = _fileList.value.getOrNull(index) ?: return@launch
            fileManager.pickFile(file)
        }
    }

    fun loadPage(file: PlatformFile) {
        viewModelScope.launch {
            if (_noteFileCache.value.containsKey(file.name)) return@launch
            val markdown = fileManager.readMarkdown(file)
            _noteFileCache.value += (file.name to markdown)
        }
    }

    fun updateBody(fileName: String, newBody: String) {
        val current = _noteFileCache.value[fileName] ?: return
        val updated = current.withBody(newBody)
        _noteFileCache.value += fileName to updated
        _saveRequest.value = fileName to updated
    }

    fun onRenameFile(file: PlatformFile, newName: String) {
        if (file.nameWithoutExtension == newName) return
        viewModelScope.launch {
            val renamed = fileManager.renameFile(file, newName) ?: return@launch
            // 캐시 key 교체 (Markdown 인스턴스는 그대로 유지)
            val cached = _noteFileCache.value[file.name]?:return@launch
            _noteFileCache.value = _noteFileCache.value
                .toMutableMap()
                .also {
                    it.remove(file.name)
                    it[renamed.name] = cached
                }
            fileManager.pickFile(renamed)
        }
    }
}