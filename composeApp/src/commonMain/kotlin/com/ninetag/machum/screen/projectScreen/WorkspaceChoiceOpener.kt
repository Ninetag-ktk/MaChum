package com.ninetag.machum.screen.projectScreen

import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.PlatformFile

/** Every selection uses normal entry after revalidating the explicit Vault child. */
internal suspend fun openWorkspaceChoice(
    fileManager: FileManager,
    vault: PlatformFile,
    directory: PlatformFile,
    configure: Boolean = false,
): Boolean {
    check(fileManager.bookmarks.value.vaultData?.toString() == vault.toString()) { "Vault가 변경되었습니다." }
    check(fileManager.listProject(vault).any { it.toString() == directory.toString() }) {
        "작업 공간이 이동되었거나 삭제되었습니다. 목록을 다시 불러와 주세요."
    }
    fileManager.requestOpenWorkspace(directory, configure)
    return fileManager.workspaceOpenRequest.value == null
}
