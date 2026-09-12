package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.WorkspaceKind

/** 파일 생성을 요청한 작업 공간과 폴더를 확인 시점까지 고정한다. */
internal data class CreateFileRequest(
    val workspaceLocation: String,
    val workspaceKind: WorkspaceKind,
    val folderKey: FolderKey,
    val initialPlotStage: PlotStage? = null,
    val initialSource: String? = null,
) {
    fun matchesWorkspace(location: String?, kind: WorkspaceKind): Boolean =
        workspaceLocation == location && workspaceKind == kind
}

