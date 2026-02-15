package com.ninetag.machum.external

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ninetag.machum.entity.ProjectConfig
import com.ninetag.machum.entity.WorkflowStep
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
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.collections.emptyList

class FileManager(private val dataStore: DataStore<Preferences>) {
    val workflowParser = WorkflowParser()
    companion object {
        private val BOOKMARK_VAULT = byteArrayPreferencesKey("bookmark_vault")
        private val BOOKMARK_PROJECT = byteArrayPreferencesKey("bookmark_project")
        private val BOOKMARK_FILE = stringPreferencesKey("bookmark_file")
    }

    // ToDo 테스트 이후 private 으로 변경
    suspend fun setPreferences(bookmark: Bookmarks): Bookmarks {
        dataStore.edit { pref ->
            bookmark.vaultData?.let { pref[BOOKMARK_VAULT] = it.bookmarkData().bytes }
            bookmark.projectData?.let { pref[BOOKMARK_PROJECT] = it.bookmarkData().bytes }?:pref.remove(BOOKMARK_PROJECT)
            bookmark.fileData?.let { pref[BOOKMARK_FILE] = it.name }?:pref.remove(BOOKMARK_FILE)
        }
        _bookmarks.value = bookmark
        return bookmark
    }

    // ToDo 테스트 이후 private 으로 변경
    suspend fun getPreferences(): Bookmarks {
        return dataStore.data.first().let{ pref ->
            val vault = pref[BOOKMARK_VAULT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) }
            val project = pref[BOOKMARK_PROJECT]?.let { PlatformFile.fromBookmarkDataWithValidate(it) }
            val file = project?.let { pref[BOOKMARK_FILE]?.let{
                PlatformFile(project.path + "/$it".forPlatformFile()).takeIf { it.exists() }
            } }
            Bookmarks(
                vaultData = vault,
                projectData = project,
                fileData = file,
            )
        }
    }

    private suspend fun clearPreferences() {
        dataStore.edit { pref ->
            pref.remove(BOOKMARK_VAULT)
            pref.remove(BOOKMARK_PROJECT)
            pref.remove(BOOKMARK_FILE)
        }
        _bookmarks.value = getPreferences()
    }

    // ToDo 테스트 이후 삭제
    suspend fun clearPreferencesTest() {
        dataStore.edit { pref ->
//            pref.remove(BOOKMARK_VAULT)
            pref.remove(BOOKMARK_PROJECT)
            pref.remove(BOOKMARK_FILE)
        }
        _bookmarks.value = getPreferences()
    }

    private val _bookmarks = MutableStateFlow<Bookmarks?>(null)
    val bookmarks: StateFlow<Bookmarks?> = _bookmarks.asStateFlow()

    private val _workflowList = MutableStateFlow<List<PlatformFile>>(emptyList())
    val workflowList: StateFlow<List<PlatformFile>> = _workflowList.asStateFlow()

    private val _workflow = MutableStateFlow<List<WorkflowStep>>(emptyList())
    val workflow: StateFlow<List<WorkflowStep>> = _workflow.asStateFlow()

    private val _needUpdateWorkflow = MutableStateFlow(false)
    val needUpdateWorkflow = _needUpdateWorkflow.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            validateVault() ?: return@launch
            validProject() ?: return@launch
        }
    }

    private suspend fun validateVault(): PlatformFile? {
        val vault = getPreferences().vaultData ?: return null
        if (!validPermission(vault)) {
            clearPreferences()
            return null
        }
        val bookmark = Bookmarks(vaultData = vault)
        _bookmarks.value = bookmark
        getWorkflowList()
        if (_workflowList.value.isEmpty()) setPreferences(bookmark)
        return vault
    }

    private suspend fun validProject(): PlatformFile? {
        val project = getPreferences().projectData ?: return null
        _bookmarks.value = getPreferences()
        setConfig(project) ?: return null
        setWorkflow()
        return project
    }

    suspend fun createProject(name: String): PlatformFile? = withContext(Dispatchers.IO) { createFolder(_bookmarks.value!!.vaultData!!, name) }

    suspend fun createWorkflow(name: String = "New_Workflow"): PlatformFile? = withContext(Dispatchers.IO) {
        val parentDirectory = PlatformFile(_bookmarks.value!!.vaultData!!.path + "/.workflow".forPlatformFile())
        createFile(parentDirectory, name)
    }

    suspend fun createNextFile(name: String): PlatformFile? = withContext(Dispatchers.IO) { createFile(_bookmarks.value!!.projectData!!, name) }

    suspend fun createChildFile(numbering: String, name: String): PlatformFile? = withContext(Dispatchers.IO) {
        // ToDo 로직 작성 필요
        null
    }

    suspend fun getWorkflowList() = withContext(Dispatchers.IO) {
        val workflowDir = PlatformFile(_bookmarks.value!!.vaultData!!.path + "/.workflow".forPlatformFile())
        _workflowList.value = listFile(workflowDir)
    }

    suspend fun getWorkflow(workflow: String): PlatformFile? = withContext(Dispatchers.IO) {
        val file = PlatformFile(_bookmarks.value!!.vaultData!!.path + "/.workflow/".forPlatformFile() + "$workflow.md")
        if (file.exists()) file else null
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

    /**
     * 저장소를 선택
     * @return 저장소
     */
    suspend fun pickVault(): PlatformFile? = withContext(Dispatchers.IO) {
        try {
            val initVault = getPreferences().vaultData
            val vault = FileKit.openDirectoryPicker(
                title = "폴더 선택",
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
    suspend fun pickProject(project: PlatformFile): PlatformFile? = withContext(Dispatchers.IO) {
        try {
            setPreferences(getPreferences().copy(projectData = project))
            setConfig(project)
            setWorkflow()
            project
        } catch (e: Exception) {
            println(e)
            null
        }
    }

    /**
     * 워크플로우 선택시 동작
     * @param workflow 워크플로우 파일
     */
    suspend fun pickWorkflow(workflow: PlatformFile) = withContext(Dispatchers.IO) {
        try {
            writeConfig(
                ProjectConfig(
                    workflow = workflow.nameWithoutExtension,
                    workflowLastModified = workflow.getLastModified()
                )
            )
            _workflow.value = workflowParser.parse(workflow.readString())
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 사용자가 작업할 파일 선택
     * 목록에서 선택하거나, 손가락 인터랙션으로 탐색
     * @param file 선택한 마크다운 파일
     * @return 선택한 마크다운 파일
     */
    suspend fun pickFile(file: PlatformFile): PlatformFile = withContext(Dispatchers.IO) {
        setPreferences(getPreferences().copy(fileData = file)).fileData!!
    }

    suspend fun pickFileTest(): PlatformFile? {
        val bookmarks = getPreferences()
        val initDirectory = bookmarks.projectData
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
            setPreferences(
                getPreferences().copy(fileData = file,)
            )
        }
        return file
    }

    /**
     * 파일 읽기
     * @param file 읽을 파일
     * @return 파일 내용 (UTF-8)
     */
    suspend fun read(file: PlatformFile): String = withContext(Dispatchers.IO) { file.readString() }

    private suspend fun readConfig(): ProjectConfig? = withContext(Dispatchers.IO) {
        try {
            val configFile = PlatformFile(_bookmarks.value!!.projectData!!.path + "/.machum.json".forPlatformFile())
            if (!configFile.exists()) return@withContext null

            val content = configFile.readString()
            if (content.isBlank()) return@withContext null

            Json.decodeFromString(ProjectConfig.serializer(), content)
        } catch (e: Exception) {
            println("Config 읽기 실패: $e")
            throw e
        }
    }

    /**
     * 파일 쓰기 (생성 & 수정)
     * @param file 선택한 파일
     * @param content 파일 내용
     */
    suspend fun write(file: PlatformFile, content: String) = withContext(Dispatchers.IO) { file.writeString(content) }

    suspend fun writeConfig(projectConfig: ProjectConfig) = withContext(Dispatchers.IO) {
        try {
            val configFile = PlatformFile(_bookmarks.value!!.projectData!!.path + "/.machum.json".forPlatformFile())
            val content = Json.encodeToString(ProjectConfig.serializer(), projectConfig)
            configFile.writeString(content)
        } catch (e: Exception) {
            println("Config 읽기 실패: $e")
            throw e
        }
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
        createFolder(_bookmarks.value!!.vaultData!!, name)?.let{
            setPreferences(getPreferences().copy(projectData = it))
            setConfig(it)
            setWorkflow()
            it
        }
    }

    /**
     * 프로젝트 config 파일에 workflow 정보 입력
     * 마지막 수정 시간이 다른 경우 플래그를 통해 상태 갱신 요청
     */
    suspend fun setWorkflow() = withContext(Dispatchers.IO) {
        val config = readConfig()
        if (config != null) {
            val workflowFile = getWorkflow(config.workflow) ?: return@withContext
            if (workflowFile.getLastModified() != workflowFile.getLastModified()) _needUpdateWorkflow.value = true
            _workflow.value = workflowParser.parse(workflowFile.readString())
        } else {
            if (_workflowList.value.size == 1) {
                println("workflow Count is 1")
                val workflow = _workflowList.value.first()
                writeConfig(
                    ProjectConfig(
                        workflow = workflow.nameWithoutExtension,
                        workflowLastModified = workflow.getLastModified()
                    )
                )
                _workflow.value = workflowParser.parse(workflow.readString())
            }
        }
    }

    suspend fun setFile(project: PlatformFile): PlatformFile = withContext(Dispatchers.IO) {
        try {
            listFile(project).lastOrNull()
                ?.let{
                    setPreferences(getPreferences().copy(fileData = it)).fileData
                }
                ?:run{
                    val firstStep = _workflow.value.first()
                    val name = "${firstStep.numbering}. ${firstStep.title}"
                    createFile(project, name)
                        ?.let { setPreferences(getPreferences().copy(fileData = it)).fileData }
                        ?:throw Exception()
                }
        } catch (e: Exception) {
            throw e
        }
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

internal expect suspend fun FileManager.setConfig(parentDirectory: PlatformFile): PlatformFile?

internal expect suspend fun FileManager.validPermission(file: PlatformFile): Boolean

internal expect fun PlatformFile.getLastModified(): Long?

internal suspend fun PlatformFile.getDescription(): String {
    val firstLine = this.readString().lines().first()
    if (!firstLine.startsWith('#') && !firstLine.startsWith('>')) return firstLine
    else return ""
}

internal fun PlatformFile.Companion.fromBookmarkDataWithValidate(bytes: ByteArray): PlatformFile? {
    val data = PlatformFile.fromBookmarkData(bytes)
    return if (data.exists()) data else null
}

internal expect fun String.forPlatformFile(): String

data class Bookmarks(
    val vaultData: PlatformFile? = null,
    val projectData: PlatformFile? = null,
    val fileData: PlatformFile? = null,
)