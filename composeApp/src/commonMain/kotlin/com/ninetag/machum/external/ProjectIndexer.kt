package com.ninetag.machum.external

import com.ninetag.machum.entity.normalizeTag
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        )
    }

    suspend fun index(project: PlatformFile): ProjectIndexResult {
        val location = project.toString()
        if (_state.value.projectLocation == null) {
            prepare(project)
        }
        if (_state.value.projectLocation != location) {
            return ProjectIndexResult(
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

        val folders = runCatching { fileManager.listFolders(project) }
            .getOrElse { error ->
                issues += ProjectIndexIssue(location, error.message ?: "디렉터리 목록을 읽지 못했습니다.")
                return completeIfCurrent(token, project, 0, 0, 0, issues)
            }
        folders.forEach { folder ->
            runCatching { fileManager.listProjectFiles(folder) }
                .onSuccess(files::addAll)
                .onFailure { error ->
                    issues += ProjectIndexIssue(
                        relativePath = folder.key.relativePath.ifEmpty { "." },
                        message = error.message ?: "파일 목록을 읽지 못했습니다.",
                    )
                }
        }

        val candidates = mutableListOf<ProjectFile>()
        var unchanged = 0
        files.forEach { projectFile ->
            runCatching {
                fileManager.inspectProjectMetadata(projectFile.platformFile, projectTag).changed
            }.onSuccess { changed ->
                if (changed) candidates += projectFile
                else unchanged += 1
            }.onFailure { error ->
                issues += ProjectIndexIssue(
                    relativePath = projectFile.key.relativePath,
                    message = error.message ?: "파일을 인덱싱하지 못했습니다.",
                )
            }
        }

        if (candidates.isEmpty()) {
            return completeIfCurrent(token, project, files.size, 0, unchanged, issues)
        }

        var updated = 0
        updateIfCurrent(token) {
            ProjectIndexState.Indexing(
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
            }.onSuccess {
                updated += 1
            }.onFailure { error ->
                issues += ProjectIndexIssue(
                    relativePath = projectFile.key.relativePath,
                    message = error.message ?: "파일을 인덱싱하지 못했습니다.",
                )
            }
            updateIfCurrent(token) {
                ProjectIndexState.Indexing(
                    projectLocation = location,
                    projectName = project.name,
                    processed = index + 1,
                    total = candidates.size,
                    updated = updated,
                    failed = issues.size,
                )
            }
        }
        return completeIfCurrent(token, project, files.size, updated, unchanged, issues)
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

    private fun completeIfCurrent(
        token: Long,
        project: PlatformFile,
        total: Int,
        updated: Int,
        unchanged: Int,
        issues: List<ProjectIndexIssue>,
    ): ProjectIndexResult {
        val result = ProjectIndexResult(
            projectLocation = project.toString(),
            projectName = project.name,
            total = total,
            updated = updated,
            unchanged = unchanged,
            issues = issues.toList(),
        )
        updateIfCurrent(token) { ProjectIndexState.Ready(result) }
        return result
    }

    private inline fun updateIfCurrent(token: Long, state: () -> ProjectIndexState) {
        if (token == generation) _state.value = state()
    }
}

sealed interface ProjectIndexState {
    val projectLocation: String?

    data object Idle : ProjectIndexState {
        override val projectLocation: String? = null
    }

    data class Preparing(
        override val projectLocation: String,
        val projectName: String,
    ) : ProjectIndexState

    data class Indexing(
        override val projectLocation: String,
        val projectName: String,
        val processed: Int,
        val total: Int,
        val updated: Int,
        val failed: Int,
    ) : ProjectIndexState

    data class Ready(val result: ProjectIndexResult) : ProjectIndexState {
        override val projectLocation: String = result.projectLocation
    }
}

data class ProjectIndexResult(
    val projectLocation: String,
    val projectName: String,
    val total: Int,
    val updated: Int,
    val unchanged: Int,
    val issues: List<ProjectIndexIssue>,
) {
    val failed: Int get() = issues.size
}

data class ProjectIndexIssue(
    val relativePath: String,
    val message: String,
)
