package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey

/**
 * ViewModel 수명 동안 폴더별 마지막 선택 파일만 기억한다.
 *
 * 앱 재실행 복원은 기존 bookmark가 담당하므로 이 상태는 별도로 저장하지 않는다.
 */
internal class FolderFileSelectionMemory {
    private val selections = mutableMapOf<FolderKey, FileKey>()

    fun remember(fileKey: FileKey) {
        selections[fileKey.folder] = fileKey
    }

    fun preferred(folderKey: FolderKey, availableKeys: Collection<FileKey>): FileKey? {
        val selected = selections[folderKey] ?: return null
        if (selected in availableKeys) return selected
        selections.remove(folderKey)
        return null
    }

    fun renameFile(oldKey: FileKey, newKey: FileKey) {
        renameFiles(mapOf(oldKey to newKey))
    }

    /** Applies a rename batch from one snapshot so a new key cannot be mistaken for another old key. */
    fun renameFiles(keys: Map<FileKey, FileKey>) {
        val renamedSelections = selections.mapNotNull { (folder, selected) ->
            keys[selected]?.let { renamed -> folder to renamed }
        }
        renamedSelections.forEach { (folder, _) -> selections.remove(folder) }
        renamedSelections.filter { (folder, renamed) -> folder == renamed.folder }
            .forEach { (_, renamed) -> selections[renamed.folder] = renamed }
        renamedSelections.filter { (folder, renamed) -> folder != renamed.folder }
            .forEach { (_, renamed) -> selections.putIfAbsent(renamed.folder, renamed) }
    }

    fun renameFolder(oldKey: FolderKey, newKey: FolderKey) {
        val selected = selections.remove(oldKey) ?: return
        selections[newKey] = newKey.file(selected.fileName)
    }

    fun forget(folderKey: FolderKey) {
        selections.remove(folderKey)
    }

    fun clear() {
        selections.clear()
    }
}
