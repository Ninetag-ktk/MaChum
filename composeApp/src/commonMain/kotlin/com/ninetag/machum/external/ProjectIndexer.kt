package com.ninetag.machum.external

import com.ninetag.machum.entity.normalizeTag
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.entity.ProjectConfig
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val PROJECT_INDEX_METADATA_PARALLELISM = 4
private const val PROJECT_INDEX_STABLE_READ_ATTEMPTS = 2

internal class ProjectIndexer(
    private val fileManager: FileManager,
) {
    private val _state = MutableStateFlow<ProjectIndexState>(ProjectIndexState.Idle)
    val state: StateFlow<ProjectIndexState> = _state.asStateFlow()

    private var generation = 0L

    fun prepare(project: PlatformFile) {
        generation += 1
        _state.value = ProjectIndexState.Preparing(
            projectLocation = project.toString(),
            projectName = project.name,
            activationGeneration = generation,
        )
    }

    suspend fun index(project: PlatformFile, projectConfig: ProjectConfig? = null): ProjectIndexResult {
        val location = project.toString()
        if (_state.value.projectLocation == null) {
            prepare(project)
        }
        if (_state.value.projectLocation != location) {
            return ProjectIndexResult(
                activationGeneration = generation,
                projectLocation = location,
                projectName = project.name,
                total = 0,
                updated = 0,
                unchanged = 0,
                issues = emptyList(),
            )
        }
        val token = generation
        val projectTag = normalizeTag(project.name)
        val issues = mutableListOf<ProjectIndexIssue>()
        val files = mutableListOf<ProjectFile>()
        val filesByFolder = linkedMapOf<FolderKey, List<ProjectFile>>()
        val metadataBaseline = fileManager.workspaceMetadataIndex
            .snapshot(project, WorkspaceKind.PROJECT)
            ?: throw CancellationException("작업 공간이 변경되었습니다.")
        val indexedMetadata = mutableMapOf<FileKey, IndexedProjectMetadata>()

        val folders = runCatching { fileManager.listFolders(project) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                issues += ProjectIndexIssue(location, error.message ?: "디렉터리 목록을 읽지 못했습니다.")
                return completeIfCurrent(token, project, 0, 0, 0, issues)
            }
        var hierarchyEnumerationComplete = true
        folders.forEach { folder ->
            runCatching { fileManager.listProjectFiles(folder) }
                .onSuccess { folderFiles ->
                    val immutableFiles = folderFiles.toList()
                    filesByFolder[folder.key] = immutableFiles
                    files.addAll(immutableFiles)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    hierarchyEnumerationComplete = false
                    issues += ProjectIndexIssue(
                        relativePath = folder.key.relativePath.ifEmpty { "." },
                        message = error.message ?: "파일 목록을 읽지 못했습니다.",
                    )
                }
        }
        val hierarchySnapshot = if (hierarchyEnumerationComplete) {
            ProjectHierarchySnapshot(
                activationGeneration = token,
                projectLocation = location,
                folders = folders.toList(),
                filesByFolder = filesByFolder.toMap(),
                projectConfig = projectConfig,
            )
        } else {
            null
        }

        val candidates = mutableListOf<ProjectFile>()
        var unchanged = 0
        inspectFiles(files, projectTag).forEach { inspection ->
            val projectFile = inspection.file
            inspection.metadata?.let { metadata ->
                if (metadata.requiresUpdate) {
                    candidates += projectFile
                } else {
                    unchanged += 1
                    indexedMetadata[projectFile.key] = metadata
                }
            } ?: inspection.error?.let { error ->
                issues += ProjectIndexIssue(
                    relativePath = projectFile.key.relativePath,
                    message = error.message ?: "파일을 인덱싱하지 못했습니다.",
                )
            }
        }

        if (candidates.isEmpty()) {
            publishMetadataIndex(project, metadataBaseline, files, indexedMetadata)
            return completeIfCurrent(token, project, files.size, 0, unchanged, issues, hierarchySnapshot)
        }

        var updated = 0
        updateIfCurrent(token) {
            ProjectIndexState.Indexing(
                activationGeneration = token,
                projectLocation = location,
                projectName = project.name,
                processed = 0,
                total = candidates.size,
                updated = 0,
                failed = issues.size,
            )
        }
        candidates.forEachIndexed { index, projectFile ->
            runCatching {
                fileManager.ensureProjectMetadata(projectFile.platformFile, projectTag)
                inspectStableProjectMetadata(projectFile, projectTag).also { metadata ->
                    check(!metadata.requiresUpdate) {
                        "메타데이터를 기록한 직후 파일이 다시 변경되었습니다."
                    }
                }
            }.onSuccess { metadata ->
                indexedMetadata[projectFile.key] = metadata
                updated += 1
            }.onFailure { error ->
                if (error is CancellationException) throw error
                issues += ProjectIndexIssue(
                    relativePath = projectFile.key.relativePath,
                    message = error.message ?: "파일을 인덱싱하지 못했습니다.",
                )
            }
            updateIfCurrent(token) {
                ProjectIndexState.Indexing(
                    activationGeneration = token,
                    projectLocation = location,
                    projectName = project.name,
                    processed = index + 1,
                    total = candidates.size,
                    updated = updated,
                    failed = issues.size,
                )
            }
        }
        publishMetadataIndex(project, metadataBaseline, files, indexedMetadata)
        return completeIfCurrent(token, project, files.size, updated, unchanged, issues, hierarchySnapshot)
    }

    private suspend fun inspectFiles(
        files: List<ProjectFile>,
        projectTag: String,
    ): List<ProjectInspectionResult> {
        val permits = Semaphore(PROJECT_INDEX_METADATA_PARALLELISM)
        return coroutineScope {
            files.map { file ->
                async {
                    permits.withPermit {
                        try {
                            ProjectInspectionResult(
                                file = file,
                                metadata = inspectStableProjectMetadata(file, projectTag),
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            ProjectInspectionResult(file = file, error = error)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun inspectStableProjectMetadata(
        file: ProjectFile,
        projectTag: String,
    ): IndexedProjectMetadata {
        repeat(PROJECT_INDEX_STABLE_READ_ATTEMPTS) {
            val modifiedBefore = fileManager.lastModified(file.platformFile)
            val update = fileManager.inspectProjectMetadata(file.platformFile, projectTag)
            val modifiedAfter = fileManager.lastModified(file.platformFile)
            val beforeUsable = modifiedBefore != null && modifiedBefore > 0L
            val afterUsable = modifiedAfter != null && modifiedAfter > 0L
            if ((!beforeUsable && !afterUsable) || (beforeUsable && afterUsable && modifiedBefore == modifiedAfter)) {
                return IndexedProjectMetadata(
                    plotStage = update.noteFile.plotStage,
                    requiresUpdate = update.changed,
                    modifiedAt = modifiedAfter,
                )
            }
        }
        error("읽는 동안 파일이 변경되었습니다. 다음 진입에서 다시 시도해 주세요.")
    }

    private suspend fun publishMetadataIndex(
        project: PlatformFile,
        baseline: WorkspaceMetadataSnapshot,
        files: List<ProjectFile>,
        metadataByKey: Map<FileKey, IndexedProjectMetadata>,
    ) {
        val indexed = files.mapNotNull { file ->
            metadataByKey[file.key]?.let { metadata ->
                WorkspaceFileMetadata(
                    file = file,
                    modifiedAt = metadata.modifiedAt,
                    plot = IndexedPlot(metadata.plotStage),
                )
            }
        }
        fileManager.workspaceMetadataIndex.reconcileAll(
            project,
            WorkspaceKind.PROJECT,
            baseline,
            indexed,
        )
    }

    fun fail(project: PlatformFile, error: Throwable) {
        completeIfCurrent(
            token = generation,
            project = project,
            total = 0,
            updated = 0,
            unchanged = 0,
            issues = listOf(
                ProjectIndexIssue(
                    relativePath = project.toString(),
                    message = error.message ?: "프로젝트 인덱싱을 시작하지 못했습니다.",
                )
            ),
        )
    }

    fun reset() {
        generation += 1
        _state.value = ProjectIndexState.Idle
    }

    fun takeHierarchySnapshot(
        projectLocation: String,
        activationGeneration: Long,
    ): ProjectHierarchySnapshot? {
        while (true) {
            val ready = _state.value as? ProjectIndexState.Ready ?: return null
            if (
                ready.projectLocation != projectLocation ||
                ready.activationGeneration != activationGeneration
            ) return null
            val snapshot = ready.result.hierarchySnapshot ?: return null
            val consumed = ready.copy(result = ready.result.copy(hierarchySnapshot = null))
            if (_state.compareAndSet(ready, consumed)) return snapshot
        }
    }

    fun invalidateHierarchySnapshot() {
        while (true) {
            val ready = _state.value as? ProjectIndexState.Ready ?: return
            if (ready.result.hierarchySnapshot == null) return
            val invalidated = ready.copy(result = ready.result.copy(hierarchySnapshot = null))
            if (_state.compareAndSet(ready, invalidated)) return
        }
    }

    private fun completeIfCurrent(
        token: Long,
        project: PlatformFile,
        total: Int,
        updated: Int,
        unchanged: Int,
        issues: List<ProjectIndexIssue>,
        hierarchySnapshot: ProjectHierarchySnapshot? = null,
    ): ProjectIndexResult {
        val isCurrent = token == generation
        val result = ProjectIndexResult(
            activationGeneration = token,
            projectLocation = project.toString(),
            projectName = project.name,
            total = total,
            updated = updated,
            unchanged = unchanged,
            issues = issues.toList(),
            hierarchySnapshot = hierarchySnapshot?.takeIf { isCurrent },
        )
        if (isCurrent) _state.value = ProjectIndexState.Ready(result)
        return result
    }

    private inline fun updateIfCurrent(token: Long, state: () -> ProjectIndexState) {
        if (token == generation) _state.value = state()
    }
}

private data class IndexedProjectMetadata(
    val plotStage: PlotStage?,
    val requiresUpdate: Boolean,
    val modifiedAt: Long?,
)

private data class ProjectInspectionResult(
    val file: ProjectFile,
    val metadata: IndexedProjectMetadata? = null,
    val error: Exception? = null,
)

sealed interface ProjectIndexState {
    val projectLocation: String?
    val activationGeneration: Long?

    data object Idle : ProjectIndexState {
        override val projectLocation: String? = null
        override val activationGeneration: Long? = null
    }

    data class Preparing(
        override val projectLocation: String,
        val projectName: String,
        override val activationGeneration: Long,
    ) : ProjectIndexState

    data class Indexing(
        override val activationGeneration: Long,
        override val projectLocation: String,
        val projectName: String,
        val processed: Int,
        val total: Int,
        val updated: Int,
        val failed: Int,
    ) : ProjectIndexState

    data class Ready(val result: ProjectIndexResult) : ProjectIndexState {
        override val projectLocation: String = result.projectLocation
        override val activationGeneration: Long = result.activationGeneration
    }
}

data class ProjectIndexResult(
    val activationGeneration: Long,
    val projectLocation: String,
    val projectName: String,
    val total: Int,
    val updated: Int,
    val unchanged: Int,
    val issues: List<ProjectIndexIssue>,
    val hierarchySnapshot: ProjectHierarchySnapshot? = null,
) {
    val failed: Int get() = issues.size
}

data class ProjectHierarchySnapshot(
    val activationGeneration: Long,
    val projectLocation: String,
    val folders: List<ProjectFolder>,
    val filesByFolder: Map<FolderKey, List<ProjectFile>>,
    val projectConfig: ProjectConfig?,
)

data class ProjectIndexIssue(
    val relativePath: String,
    val message: String,
)
