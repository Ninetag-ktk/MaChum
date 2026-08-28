package com.ninetag.machum.screen.mainComposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.NoteFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.nameWithoutExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    // 외부(옵시디언 등) 변경 감지용 — 파일별로 마지막으로 "인지한" 수정 시각.
    // 앱 자신의 쓰기 직후에도 갱신하여, 폴링이 자기 쓰기를 외부 변경으로 오인하지 않게 한다.
    private val knownModified = mutableMapOf<String, Long>()

    // 앱/창 활성 상태 — 활성일 때만 폴링 (Phase 2) + 활성 전환 시 즉시 1회 검사 (Phase 1)
    private val _active = MutableStateFlow(false)

    private val saveCoordinator = DebouncedSaveCoordinator<String, NoteFile>(
        scope = viewModelScope,
        debounceMillis = SAVE_DEBOUNCE_MS,
    ) { fileName, noteFile ->
        val file = _fileList.value.find { it.name == fileName } ?: return@DebouncedSaveCoordinator
        fileManager.writeMarkdown(file, noteFile)
        // 자기 쓰기 mtime 기록 → 폴링이 외부 변경으로 오인하지 않도록
        fileManager.lastModified(file)?.let { knownModified[fileName] = it }
    }

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

        // 외부 변경 감지: 활성(포커스) 상태에서만 동작.
        // 활성 전환 즉시 1회 검사(Phase 1) → 이후 주기 폴링(Phase 2). 비활성 시 collectLatest 가 루프를 취소.
        viewModelScope.launch {
            _active.collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    checkExternalChanges()
                    delay(POLL_INTERVAL_MS.milliseconds)
                }
            }
        }
    }

    /** 앱/창 포커스 상태 전달 (MainScreen 의 LocalWindowInfo.isWindowFocused) */
    fun setActive(active: Boolean) {
        _active.value = active
    }

    /**
     * 열려있는(캐시된) 파일들의 외부 변경을 감지하여 자동 리로드한다.
     * - 파일 목록도 함께 갱신 (외부에서 파일 추가/삭제)
     * - mtime 이 마지막 인지 시각과 같으면 skip (자기 쓰기 포함)
     * - 내용이 실제로 다르면 캐시 교체 → EditorPage 의 value 가 바뀌어 에디터가 재파싱 (외부 우선)
     */
    private suspend fun checkExternalChanges() {
        val project = fileManager.bookmarks.value.projectData ?: return

        // 1. 파일 목록 갱신 (외부 추가/삭제 반영, 현재 인덱스는 파일명으로 보존)
        val freshList = fileManager.listFile(project)
        saveCoordinator.cancelMissing(freshList.mapTo(mutableSetOf()) { it.name })
        if (freshList.map { it.name } != _fileList.value.map { it.name }) {
            val currentName = _fileList.value.getOrNull(_currentIndex.value)?.name
            _fileList.value = freshList
            _currentIndex.value = currentName
                ?.let { name -> freshList.indexOfFirst { it.name == name } }
                ?.takeIf { it != -1 }
                ?: _currentIndex.value.coerceIn(0, (freshList.size - 1).coerceAtLeast(0))
        }

        // 2. 캐시된 파일들의 내용 변경 감지
        for (file in _fileList.value) {
            val name = file.name
            val cached = _noteFileCache.value[name] ?: continue // 아직 안 연 파일은 skip
            val diskModified = fileManager.lastModified(file) ?: continue
            if (knownModified[name] == diskModified) continue    // 변화 없음 (자기 쓰기 포함)

            // external wins: 외부 mtime 변경을 보면 이 파일의 stale pending write를 먼저 취소.
            saveCoordinator.cancel(name)
            val fresh = fileManager.readMarkdown(file)
            knownModified[name] = fileManager.lastModified(file) ?: diskModified
            // mtime 은 달라졌지만 내용은 동일할 수 있음(자기 쓰기 레이스 등) → 실제 diff 일 때만 교체
            if (fresh.inject() != cached.inject()) {
                _noteFileCache.value += name to fresh
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
            fileManager.lastModified(file)?.let { knownModified[file.name] = it }
        }
    }

    fun updateBody(fileName: String, newBody: String) {
        val current = _noteFileCache.value[fileName] ?: return
        if (current.body == newBody) return
        val updated = current.withBody(newBody)
        _noteFileCache.value += fileName to updated
        saveCoordinator.schedule(fileName, updated)
    }

    fun onRenameFile(file: PlatformFile, newName: String) {
        if (file.nameWithoutExtension == newName) return
        viewModelScope.launch {
            saveCoordinator.cancel(file.name)
            val renamed = fileManager.renameFile(file, newName)
            if (renamed == null) {
                _noteFileCache.value[file.name]?.let { saveCoordinator.schedule(file.name, it) }
                return@launch
            }
            // 캐시 key 교체 (Markdown 인스턴스는 그대로 유지)
            val cached = _noteFileCache.value[file.name]?:return@launch
            saveCoordinator.cancel(file.name)
            _noteFileCache.value = _noteFileCache.value
                .toMutableMap()
                .also {
                    it.remove(file.name)
                    it[renamed.name] = cached
                }
            // mtime 추적 key 도 교체 (stale 엔트리 방지)
            knownModified.remove(file.name)
            fileManager.lastModified(renamed)?.let { knownModified[renamed.name] = it }
            saveCoordinator.schedule(renamed.name, cached)
            fileManager.pickFile(renamed)
        }
    }

    companion object {
        // Phase 2 폴링 주기. 활성(포커스) 상태에서만 동작.
        private const val POLL_INTERVAL_MS = 1500L
        private const val SAVE_DEBOUNCE_MS = 500L
    }
}
