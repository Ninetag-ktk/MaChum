package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ninetag.machum.entity.BASE_FOLDER_PATH
import com.ninetag.machum.entity.DEFAULT_PROJECT_FOLDERS
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.WorkflowStep
import com.ninetag.machum.entity.defaultProjectConfig
import com.ninetag.machum.entity.effectiveAutoTags
import com.ninetag.machum.entity.normalizeTag
import com.ninetag.machum.entity.withDefaultBaseFolder
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
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.collections.emptyList

class FileManager(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val projectConfigMutex = Mutex()
    private val projectIndexer = ProjectIndexer(this)

    val projectIndexState: StateFlow<ProjectIndexState> = projectIndexer.state

    val workflowParser = WorkflowParser()

    // .machum.json 직렬화: 구 스키마(workflow 필드 등) 무시 + 사람이 읽기 좋게 + 기본값도 기록
    private val configJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    companion object {
        private val BOOKMARK_VAULT = byteArrayPreferencesKey("bookmark_vault")
        private val BOOKMARK_PROJECT = byteArrayPreferencesKey("bookmark_project")
        private val BOOKMARK_FILE = stringPreferencesKey("bookmark_file")
    }

    // ToDo 테스트 이후 private 으로 변경
    suspend fun setPreferences(bookmark: Bookmarks): Bookmarks {
        val normalizedBookmark = bookmark.copy(
            fileRelativePath = bookmark.fileData?.let { bookmark.fileRelativePath ?: it.name },
        )
        dataStore.edit { pref ->
            normalizedBookmark.vaultData?.let { pref[BOOKMARK_VAULT] = it.bookmarkData().bytes }
            normalizedBookmark.projectData?.let { pref[BOOKMARK_PROJECT] = it.bookmarkData().bytes }?:pref.remove(BOOKMARK_PROJECT)
            normalizedBookmark.fileData
                ?.let { pref[BOOKMARK_FILE] = normalizedBookmark.fileRelativePath ?: it.name }
                ?: pref.remove(BOOKMARK_FILE)
        }
        if (!sameLocation(_bookmarks.value.projectData, normalizedBookmark.projectData)) {
            _projectConfig.value = null
        }
        _bookmarks.value = normalizedBookmark
        return normalizedBookmark
    }

    // ToDo 테스트 이후 private 으로 변경
    suspend fun getPreferences(): Bookmarks {
        return dataStore.data.first().let{ pref ->
            val vault = pref[BOOKMARK_VAULT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) }
            val project = pref[BOOKMARK_PROJECT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) }
            val fileRelativePath = pref[BOOKMARK_FILE]
            val file = project?.let { root ->
                fileRelativePath?.let { resolveRelativeFile(root, it) }
            }
            Bookmarks(
                vaultData = vault,
                projectData = project,
                fileData = file,
                fileRelativePath = fileRelativePath,
            )
        }
    }

    /** 앱이 보관한 선택 상태를 초기화한다. Vault 안의 실제 파일은 삭제하지 않는다. */
    suspend fun reset() {
        dataStore.edit { pref ->
            pref.remove(BOOKMARK_VAULT)
            pref.remove(BOOKMARK_PROJECT)
            pref.remove(BOOKMARK_FILE)
        }
        _bookmarks.value = Bookmarks()
        _projectConfig.value = null
        _workflowList.value = emptyList()
        _workflow.value = emptyList()
        _needUpdateWorkflow.value = false
        _currentNoteFile.value = null
        projectIndexer.reset()
    }

    private val _bookmarks = MutableStateFlow(Bookmarks())
    val bookmarks: StateFlow<Bookmarks> = _bookmarks.asStateFlow()

    /** null이면 선택된 프로젝트가 없거나 해당 프로젝트의 설정을 아직 로드하지 못한 상태다. */
    private val _projectConfig = MutableStateFlow<ProjectConfig?>(null)
    val projectConfig: StateFlow<ProjectConfig?> = _projectConfig.asStateFlow()

    private val _workflowList = MutableStateFlow<List<PlatformFile>>(emptyList())
    val workflowList: StateFlow<List<PlatformFile>> = _workflowList.asStateFlow()

    private val _workflow = MutableStateFlow<List<WorkflowStep>>(emptyList())
    val workflow: StateFlow<List<WorkflowStep>> = _workflow.asStateFlow()

    private val _needUpdateWorkflow = MutableStateFlow(false)
    val needUpdateWorkflow = _needUpdateWorkflow.asStateFlow()

    private val _currentNoteFile = MutableStateFlow<NoteFile?>(null)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            validateVault() ?: return@launch
            validProject() ?: return@launch
        }
    }

    private suspend fun validateVault(): PlatformFile? {
        val vault = getPreferences().vaultData ?: return null
        if (!validPermission(vault)) {
            reset()
            return null
        }
        val bookmark = Bookmarks(vaultData = vault)
        _bookmarks.value = bookmark
        _projectConfig.value = null
        return vault
    }

    private suspend fun validProject(): PlatformFile? {
        val bookmarks = getPreferences()
        val project = bookmarks.projectData ?: return null
        projectIndexer.prepare(project)
        _bookmarks.value = bookmarks
        loadProjectConfig(project) ?: return null
        return project
    }

    suspend fun createProject(name: String): PlatformFile? = withContext(Dispatchers.IO) {
        val vault = _bookmarks.value.vaultData ?: return@withContext null
        val projectName = name.trim()
        if (!isValidProjectFolderName(projectName)) return@withContext null
        if (vault.list().any { it.name.equals(projectName, ignoreCase = true) }) {
            return@withContext null
        }

        val project = createFolder(vault, projectName) ?: return@withContext null
        val initialized = runCatching { initializeNewProject(project) }.getOrDefault(false)
        if (!initialized) {
            rollbackNewProject(project)
            return@withContext null
        }
        project
    }

    suspend fun createWorkflow(name: String = "New_Workflow"): PlatformFile? = withContext(Dispatchers.IO) {
        val parentDirectory = _bookmarks.value.vaultData!!.list().find { it.name == ".workflow" }?:return@withContext null
        createFile(parentDirectory, name)
    }

    suspend fun createNextFile(name: String): PlatformFile? = withContext(Dispatchers.IO) {
        createFile(_bookmarks.value.projectData!!, name)
            ?.also { file ->
                readMarkdown(file)
                pickFile(ProjectFile(FolderKey.Base.file(file.name), file))
            }
    }

    suspend fun createChildFile(numbering: String, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        // ToDo 로직 작성 필요
        null
    }

    suspend fun getWorkflowList() = withContext(Dispatchers.IO) {
        val workflowDir = _bookmarks.value.vaultData!!.list().find { it.name == ".workflow" }
        _workflowList.value = listFile(workflowDir!!)
    }

    suspend fun getWorkflow(workflow: String): PlatformFile? = withContext(Dispatchers.IO) {
        val workflowDir = _bookmarks.value.vaultData!!.list().find { it.name == ".workflow" }
        val file = workflowDir!!.list().find { it.name == "$workflow.md" }
        file
    }

    /**
     * Vault 내부의 프로젝트 폴더 목록
     * @param vault 사용자 지정 또는 북마크에서 복원된 루트 디렉토리 경로
     * @return 프로젝트 폴더 목록
     */
    suspend fun listProject(vault: PlatformFile): List<PlatformFile> = withContext(Dispatchers.IO) {
        vault.list()
            .filter { it.isDirectory() && !it.name.startsWith(".") }
            .sortedBy { it.name }
    }

    /**
     * Project 내부의 마크다운 파일 목록
     * @param project 사용자 선택 또는 북마크에서 복원된 프로젝트 폴더 경로
     * @return 마크다운 파일 목록
     */
    suspend fun listFile(project: PlatformFile): List<PlatformFile> = withContext(Dispatchers.IO) {
        project.list()
            .filter { it.name.endsWith(".md") }
            .sortedBy { it.name }
    }

    /** 프로젝트 base와 바로 아래의 비숨김 폴더를 반환한다. 중첩 폴더는 지원 범위 밖이다. */
    suspend fun listFolders(project: PlatformFile): List<ProjectFolder> = withContext(Dispatchers.IO) {
        listOf(ProjectFolder(FolderKey.Base, project)) + project.list()
            .filter { it.isDirectory() && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .map { folder -> ProjectFolder(FolderKey.of(folder.name), folder) }
    }

    /** 지정한 프로젝트 폴더의 직속 Markdown 파일을 상대 경로 정체성과 함께 반환한다. */
    suspend fun listProjectFiles(folder: ProjectFolder): List<ProjectFile> = withContext(Dispatchers.IO) {
        folder.platformFile.list()
            .filter { !it.isDirectory() && it.name.endsWith(".md", ignoreCase = true) }
            .sortedBy { it.name }
            .map { file -> ProjectFile(folder.key.file(file.name), file) }
    }

    /** 지정 폴더에 Markdown 파일을 만들고 상대 경로 정체성을 부여한다. */
    suspend fun createProjectFile(folder: ProjectFolder, name: String): ProjectFile? = withContext(Dispatchers.IO) {
        createFile(folder.platformFile, name)
            ?.let { file ->
                readMarkdown(file)
                ProjectFile(folder.key.file(file.name), file)
            }
    }

    /**
     * 폴더 설정 변경 전후의 관리 태그 차이를 실제 Markdown 파일에 반영한다.
     * base 설정 변경은 모든 폴더, 하위 폴더 설정 변경은 해당 폴더의 직속 파일만 대상으로 한다.
     */
    suspend fun synchronizeAutoTags(
        previousConfig: ProjectConfig,
        updatedConfig: ProjectConfig,
        editedRelativePath: String,
    ): List<AutoTagSyncUpdate> = withContext(Dispatchers.IO) {
        val project = _bookmarks.value.projectData ?: return@withContext emptyList()
        val targetFolders = listFolders(project).filter { folder ->
            editedRelativePath == BASE_FOLDER_PATH || folder.key.relativePath == editedRelativePath
        }

        targetFolders.flatMap { folder ->
            val previousTags = previousConfig.effectiveAutoTags(folder.key.relativePath)
            val updatedTags = updatedConfig.effectiveAutoTags(folder.key.relativePath)
            if (previousTags == updatedTags) return@flatMap emptyList()

            listProjectFiles(folder).mapNotNull { projectFile ->
                val noteFile = readMarkdown(projectFile.platformFile)
                val mergedTags = mergeManagedTags(noteFile.tags, previousTags, updatedTags)
                val projectTag = normalizeTag(project.name)
                val requiredTags = (listOf(projectTag) + mergedTags.filterNot { it == projectTag }).distinct()
                if (requiredTags == noteFile.tags) return@mapNotNull null

                val updatedNoteFile = noteFile.withTags(requiredTags)
                writeMarkdown(projectFile.platformFile, updatedNoteFile)
                AutoTagSyncUpdate(projectFile, updatedNoteFile)
            }
        }
    }

    /** PLOT 순서 초안을 frontmatter와 정확한 파일명에 일괄 반영한다. */
    suspend fun applyPlotOrder(
        folder: ProjectFolder,
        assignments: List<PlotOrderAssignment>,
    ): List<PlotOrderUpdate>? = withContext(Dispatchers.IO) {
        if (assignments.isEmpty()) return@withContext emptyList()
        if (assignments.map { it.fileKey }.toSet().size != assignments.size) return@withContext null

        val filesByKey = listProjectFiles(folder).associateBy(ProjectFile::key)
        val assignmentKeys = assignments.mapTo(mutableSetOf()) { it.fileKey }
        val untouchedNames = filesByKey.values
            .filterNot { it.key in assignmentKeys }
            .mapTo(mutableSetOf()) { it.platformFile.name.lowercase() }

        val works = assignments.mapIndexed { index, assignment ->
            val source = filesByKey[assignment.fileKey] ?: return@withContext null
            val noteFile = readMarkdown(source.platformFile)
            val title = source.plotTitle()
            val finalBaseName = assignment.stage.fileName(assignment.order, title)
            val finalFileName = "$finalBaseName.md"
            if (finalFileName.lowercase() in untouchedNames) return@withContext null
            PlotRenameWork(
                oldKey = source.key,
                originalBaseName = source.platformFile.nameWithoutExtension,
                originalNoteFile = noteFile,
                updatedNoteFile = noteFile.withPlotStage(assignment.stage),
                finalBaseName = finalBaseName,
                temporaryBaseName = ".machum-plot-${noteFile.id ?: index}-$index",
                currentFile = source.platformFile,
            )
        }
        if (works.map { it.finalBaseName.lowercase() }.toSet().size != works.size) {
            return@withContext null
        }

        try {
            works.forEach { work ->
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    work.temporaryBaseName,
                ) ?: error("temporary plot rename failed")
            }
            works.forEach { work ->
                writeMarkdown(work.currentFile, work.updatedNoteFile)
            }
            works.forEach { work ->
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    work.finalBaseName,
                ) ?: error("final plot rename failed")
            }
        } catch (error: Exception) {
            rollbackPlotOrder(folder, works)
            return@withContext null
        }

        works.map { work ->
            PlotOrderUpdate(
                oldKey = work.oldKey,
                projectFile = ProjectFile(folder.key.file(work.currentFile.name), work.currentFile),
                noteFile = work.updatedNoteFile,
            )
        }
    }

    /** 현재 Project 바로 아래에 디렉터리를 만들고 대응하는 설정을 함께 저장한다. */
    suspend fun createProjectFolder(
        name: String,
        folderConfig: FolderConfig,
    ): ProjectFolder? = withContext(Dispatchers.IO) {
        val project = _bookmarks.value.projectData ?: return@withContext null
        val directoryName = name.trim()
        if (!isValidProjectFolderName(directoryName)) return@withContext null
        if (project.list().any { it.name.equals(directoryName, ignoreCase = true) }) {
            return@withContext null
        }

        val directory = createFolder(project, directoryName) ?: return@withContext null
        val key = FolderKey.of(directory.name)
        setFolderConfig(key.relativePath, folderConfig) ?: return@withContext null
        ProjectFolder(key, directory)
    }

    /**
     * 저장소를 선택
     * @return 저장소
     */
    suspend fun pickVault(): PlatformFile? = withContext(Dispatchers.IO) {
        try {
            val initVault = getPreferences().vaultData
            val vault = FileKit.openDirectoryPicker(
                directory = initVault,
            )
            vault?.let{
                createFolder(vault, ".workflow")
                setPreferences(Bookmarks(vaultData = it)).vaultData
            }
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 사용자가 작업을 진행할 프로젝트 산텍 / 목록 방식
     * 자동으로 해당 프로젝트의 마지막 마크다운을 읽어옴
     * @param project 선택한 프로젝트 폴더
     * @return 해당 프로젝트 폴더의 마지막 마크다운 파일
     */
    fun pickProject(project: PlatformFile) {
        projectIndexer.prepare(project)
        scope.launch {
            try {
                setPreferences(getPreferences().copy(projectData = project, fileData = null))
                loadProjectConfig(project)
            } catch (e: Exception) {
                projectIndexer.fail(project, e)
                println(e)
            }
        }
    }

    /**
     * 워크플로우 선택시 동작
     * @param workflow 워크플로우 파일
     */
    // workflow 은퇴(dormant). 라이브 흐름 미사용 — WorkflowSelectionScreen(은퇴 화면)만 참조.
    fun pickWorkflow(workflow: PlatformFile) {
        scope.launch {
            try {
                _workflow.value = workflowParser.parse(workflow.readString())
            } catch (e: Exception) {
                throw e
            }
        }
    }

    /**
     * 사용자가 작업할 파일 선택
     * 목록에서 선택하거나, 손가락 인터랙션으로 탐색
     * @param file 선택한 마크다운 파일
     * @return 선택한 마크다운 파일
     */
    suspend fun pickFile(projectFile: ProjectFile): PlatformFile = withContext(Dispatchers.IO) {
        val file = projectFile.platformFile
        // 포커스 변경 시에만 갱신
        _currentNoteFile.value = readMarkdown(file)
        setPreferences(
            getPreferences().copy(
                fileData = file,
                fileRelativePath = projectFile.key.relativePath,
            )
        ).fileData!!
    }

    suspend fun pickFileTest(): PlatformFile? {
        val bookmarks = getPreferences()
        val initDirectory = bookmarks.projectData
        val file = FileKit.openFilePicker(
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
                getPreferences().copy(fileData = file, fileRelativePath = file.name)
            )
        }
        return file
    }

    /**
     * 파일 읽기
     * @param file 읽을 파일
     * @return 파일 내용 (UTF-8)
     */
    suspend fun read(file: PlatformFile): String = withContext(Dispatchers.IO) {
        file.readString()
    }

    private suspend fun readConfig(configFile: PlatformFile): ProjectConfig? = withContext(Dispatchers.IO) {
        val content = configFile.readString()
        if (content.isBlank()) return@withContext null

        configJson.decodeFromString(ProjectConfig.serializer(), content)
    }

    /**
     * 프로젝트 설정을 읽어 라이브 상태로 전환한다.
     * 빈 파일 또는 base 설정이 없는 구 설정은 기본값을 보완해 즉시 디스크에도 기록한다.
     */
    private suspend fun loadProjectConfig(project: PlatformFile): ProjectConfig? = withContext(Dispatchers.IO) {
        projectConfigMutex.withLock {
            val configFile = setConfig(project) ?: return@withLock null
            val stored = readConfig(configFile)
            val normalized = (stored ?: ProjectConfig()).withDefaultBaseFolder()

            if (stored != normalized) {
                persistConfig(configFile, normalized)
            }

            projectIndexer.index(project)

            if (!sameLocation(_bookmarks.value.projectData, project)) return@withLock null
            _projectConfig.value = normalized
            normalized
        }
    }

    /**
     * 파일의 마지막 수정 시각 (epoch millis). 조회 실패 시 null.
     * 외부(옵시디언 등) 변경 감지에 사용.
     */
    suspend fun lastModified(file: PlatformFile): Long? = withContext(Dispatchers.IO) {
        try {
            file.getLastModified()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun readMarkdown(file: PlatformFile): NoteFile = readMarkdown(
        file = file,
        requiredProjectTag = _bookmarks.value.projectData?.name?.let(::normalizeTag),
    )

    internal suspend fun inspectProjectMetadata(
        file: PlatformFile,
        requiredProjectTag: String?,
    ): ProjectMetadataUpdate = withContext(Dispatchers.IO) {
        val raw = NoteFile.parse(file.readString())
        val withId = raw.ensureId()
        val normalized = requiredProjectTag
            ?.takeIf(String::isNotBlank)
            ?.let { projectTag ->
                withId.withTags(listOf(projectTag) + withId.tags.filterNot { it == projectTag })
            }
            ?: withId
        val changed = raw.inject() != normalized.inject()
        ProjectMetadataUpdate(normalized, changed)
    }

    internal suspend fun ensureProjectMetadata(
        file: PlatformFile,
        requiredProjectTag: String?,
    ): ProjectMetadataUpdate = withContext(Dispatchers.IO) {
        inspectProjectMetadata(file, requiredProjectTag).also { update ->
            if (update.changed) file.writeString(update.noteFile.inject())
        }
    }

    private suspend fun readMarkdown(
        file: PlatformFile,
        requiredProjectTag: String?,
    ): NoteFile = ensureProjectMetadata(file, requiredProjectTag).noteFile

    /**
     * 파일 쓰기 (생성 & 수정)
     * @param file 선택한 파일
     * @param body 파일 내용
     */
    suspend fun write(file: PlatformFile, body: String) = withContext(Dispatchers.IO) {
        file.writeString(body)
    }

    /** 설정 전체를 현재 프로젝트에 저장하고 라이브 상태를 함께 갱신한다. */
    suspend fun writeConfig(projectConfig: ProjectConfig): ProjectConfig? = withContext(Dispatchers.IO) {
        projectConfigMutex.withLock {
            val project = _bookmarks.value.projectData ?: return@withLock null
            persistProjectConfig(project, projectConfig)
        }
    }

    /** 현재 설정을 원자적으로 변경하고 저장한다. 설정이 로드되지 않았다면 null을 반환한다. */
    suspend fun updateProjectConfig(
        transform: (ProjectConfig) -> ProjectConfig,
    ): ProjectConfig? = withContext(Dispatchers.IO) {
        projectConfigMutex.withLock {
            val project = _bookmarks.value.projectData ?: return@withLock null
            val current = _projectConfig.value ?: return@withLock null
            persistProjectConfig(project, transform(current))
        }
    }

    /** 상대 경로에 해당하는 폴더 설정을 추가하거나 교체한다. 빈 경로는 base 폴더다. */
    suspend fun setFolderConfig(
        relativePath: String,
        folderConfig: FolderConfig,
    ): ProjectConfig? = updateProjectConfig { current ->
        current.copy(folders = current.folders + (relativePath to folderConfig))
    }

    private suspend fun persistProjectConfig(
        project: PlatformFile,
        projectConfig: ProjectConfig,
    ): ProjectConfig? {
        val configFile = setConfig(project) ?: return null
        val normalized = projectConfig.withDefaultBaseFolder()
        persistConfig(configFile, normalized)

        if (!sameLocation(_bookmarks.value.projectData, project)) return null
        _projectConfig.value = normalized
        return normalized
    }

    private suspend fun persistConfig(configFile: PlatformFile, projectConfig: ProjectConfig) {
        val content = configJson.encodeToString(ProjectConfig.serializer(), projectConfig)
        configFile.writeString(content)
    }

    suspend fun writeMarkdown(file: PlatformFile, noteFile: NoteFile) = withContext(Dispatchers.IO) {
        file.writeString(noteFile.inject())
    }

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
            throw e
        }
    }

    /**
     * 저장소 생성 및 초기 세팅
     * @param parentDirectory 저장소가 생성될 위치
     * @param name 저장소의 이름
     * @return 저장소
     */
    suspend fun setVault(parentDirectory: PlatformFile, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        createFolder(parentDirectory, name)?.let{
            createFolder(it, ".workflow")
            setPreferences(getPreferences().copy(vaultData = it)).vaultData
        }
    }

    /**
     * 프로젝트 생성 및 초기 세팅
     * @param name 프로젝트의 이름
     * @return 프로젝트
     */
    suspend fun setProject(name: String): PlatformFile? = withContext(Dispatchers.IO) {
        createProject(name)?.let{
            projectIndexer.prepare(it)
            setPreferences(getPreferences().copy(projectData = it, fileData = null))
            loadProjectConfig(it)
            it
        }
    }

    private suspend fun initializeNewProject(project: PlatformFile): Boolean {
        DEFAULT_PROJECT_FOLDERS.forEach { template ->
            createFolder(project, template.name) ?: return false
        }
        val configFile = setConfig(project) ?: return false
        persistConfig(configFile, defaultProjectConfig())
        return true
    }

    private suspend fun rollbackNewProject(project: PlatformFile) {
        runCatching {
            project.list().forEach { child -> child.delete() }
            project.delete()
        }
    }

    suspend fun setFile(project: PlatformFile): PlatformFile = withContext(Dispatchers.IO) {
        try {
            val config = _projectConfig.value ?: loadProjectConfig(project)
            val folderConfig = config?.folders?.get("") ?: FolderConfig()
            val baseFolder = ProjectFolder(FolderKey.Base, project)
            val files = listProjectFiles(baseFolder).sortedFor(folderConfig)
            files.lastOrNull()
                ?.let { projectFile ->
                    setPreferences(
                        getPreferences().copy(
                            fileData = projectFile.platformFile,
                            fileRelativePath = projectFile.key.relativePath,
                        )
                    ).fileData
                }
                ?:run{
                    val name = if (folderConfig.type == FolderType.DEFAULT) "0. 제목" else "제목"
                    createProjectFile(baseFolder, name)
                        ?.let {
                            setPreferences(
                                getPreferences().copy(
                                    fileData = it.platformFile,
                                    fileRelativePath = it.key.relativePath,
                                )
                            ).fileData
                        }
                        ?:throw Exception()
                }
        } catch (e: Exception) {
            throw e
        }
    }

    // TODO
    suspend fun renameProject(project: PlatformFile, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        null
    }

    suspend fun renameWorkflow(file: PlatformFile, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        val workflowDir = _bookmarks.value.vaultData!!.list().find { it.name == ".workflow" } ?: return@withContext null
        renameMarkdown(workflowDir, file, name).also{
            getWorkflowList()
        }
    }

    suspend fun renameFile(projectFile: ProjectFile, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        val project = _bookmarks.value.projectData ?: return@withContext null
        val parent = resolveFolder(project, projectFile.key.folder) ?: return@withContext null
        renameMarkdown(parent, projectFile.platformFile, name)
    }

    private fun resolveFolder(project: PlatformFile, key: FolderKey): PlatformFile? {
        if (key == FolderKey.Base) return project
        if ('/' in key.relativePath) return null
        return project.list().find { it.isDirectory() && it.name == key.relativePath }
    }

    private fun resolveRelativeFile(project: PlatformFile, relativePath: String): PlatformFile? {
        val key = runCatching { FileKey.of(relativePath) }.getOrNull() ?: return null
        val parent = resolveFolder(project, key.folder) ?: return null
        return parent.list().find { !it.isDirectory() && it.name == key.fileName }
    }

    private fun sameLocation(first: PlatformFile?, second: PlatformFile?): Boolean =
        first?.toString() == second?.toString()

    private suspend fun rollbackPlotOrder(
        folder: ProjectFolder,
        works: List<PlotRenameWork>,
    ) {
        works.forEachIndexed { index, work ->
            runCatching {
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    ".machum-plot-rollback-$index",
                ) ?: work.currentFile
            }
        }
        works.forEach { work ->
            runCatching {
                writeMarkdown(work.currentFile, work.originalNoteFile)
                work.currentFile = renameMarkdownExact(
                    folder.platformFile,
                    work.currentFile,
                    work.originalBaseName,
                ) ?: work.currentFile
            }
        }
    }
}

private data class PlotRenameWork(
    val oldKey: FileKey,
    val originalBaseName: String,
    val originalNoteFile: NoteFile,
    val updatedNoteFile: NoteFile,
    val finalBaseName: String,
    val temporaryBaseName: String,
    var currentFile: PlatformFile,
)

data class AutoTagSyncUpdate(
    val projectFile: ProjectFile,
    val noteFile: NoteFile,
)

internal data class ProjectMetadataUpdate(
    val noteFile: NoteFile,
    val changed: Boolean,
)

internal fun mergeManagedTags(
    existingTags: List<String>,
    previousManagedTags: List<String>,
    updatedManagedTags: List<String>,
): List<String> = buildList {
    val previousManaged = previousManagedTags.toSet()
    existingTags.filterNot { it in previousManaged }.forEach { tag ->
        if (tag !in this) add(tag)
    }
    updatedManagedTags.forEach { tag ->
        if (tag !in this) add(tag)
    }
}

internal fun isValidProjectFolderName(name: String): Boolean {
    return isValidProjectEntryName(name)
}

internal fun isValidProjectFileTitle(title: String): Boolean {
    if (title.endsWith(".md", ignoreCase = true)) return false
    return isValidProjectEntryName(title)
}

private fun isValidProjectEntryName(name: String): Boolean {
    if (name.isBlank() || name != name.trim()) return false
    if (name == "." || name == ".." || name.startsWith('.')) return false
    if (name.endsWith('.') || name.any { it.isISOControl() }) return false
    if (name.any { it in PROJECT_FOLDER_INVALID_CHARACTERS }) return false

    val deviceName = name.substringBefore('.').uppercase()
    return deviceName !in PROJECT_FOLDER_RESERVED_NAMES
}

private const val PROJECT_FOLDER_INVALID_CHARACTERS = "<>:\"/\\|?*"
private val PROJECT_FOLDER_RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { index ->
        add("COM$index")
        add("LPT$index")
    }
}

/**
 * 파일/디렉토리 생성
 * @param parentDirectory 폴더를 생성할 부모 디렉토리
 * @param name 생성할 파일/폴더명
 * @return 생성된 디렉토리
 */
internal expect suspend fun FileManager.createFile(parentDirectory: PlatformFile, name: String): PlatformFile?

internal expect suspend fun FileManager.createFolder(parentDirectory: PlatformFile, name: String): PlatformFile?

internal expect suspend fun FileManager.renameMarkdown(parentDirectory: PlatformFile, file: PlatformFile, name: String): PlatformFile?

internal expect suspend fun FileManager.renameMarkdownExact(parentDirectory: PlatformFile, file: PlatformFile, name: String): PlatformFile?

internal expect suspend fun FileManager.setConfig(parentDirectory: PlatformFile): PlatformFile?

internal expect suspend fun FileManager.validPermission(file: PlatformFile): Boolean

internal expect fun PlatformFile.getLastModified(): Long?

internal suspend fun PlatformFile.getDescription(): String {
    val firstLine = this.readString().lines().first()
    return if (!firstLine.startsWith('#') && !firstLine.startsWith('>')) firstLine
    else ""
}

internal fun PlatformFile.Companion.fromBookmarkDataWithValidate(bytes: ByteArray): PlatformFile? {
    val data = PlatformFile.fromBookmarkData(bytes)
    return if (data.exists()) data else null
}

internal fun PlatformFile.markdownName(): MarkdownName {
    val parts = nameWithoutExtension.split(". ", limit = 2)
    return if (parts.size == 2) {
        MarkdownName(parts[0], parts[1])
    } else {
        MarkdownName("", parts[0])
    }
}

data class Bookmarks(
    val vaultData: PlatformFile? = null,
    val projectData: PlatformFile? = null,
    val fileData: PlatformFile? = null,
    val fileRelativePath: String? = null,
)

data class MarkdownName(val numbering: String, val title: String)
