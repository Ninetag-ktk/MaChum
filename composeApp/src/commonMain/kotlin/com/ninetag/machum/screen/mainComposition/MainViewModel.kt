package com.ninetag.machum.screen.mainComposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val fileManager: FileManager) : ViewModel() {
    private val _fileList = MutableStateFlow<List<PlatformFile>>(emptyList())
    val fileList: StateFlow<List<PlatformFile>> = _fileList.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

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
    }

    fun onPageChanged(index: Int) {
        viewModelScope.launch {
            val file = _fileList.value.getOrNull(index) ?: return@launch
            fileManager.pickFile(file)
        }
    }
}