package com.ninetag.machum.external

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ninetag.machum.entity.PlotStage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileManagerFolderTest {

    @Test
    fun discoversVisibleProjectFoldersAndBuildsRelativeFileKeys() = withFileManager { fileManager, project ->
        File(project, "root.md").writeText("root")
        File(project, ".hidden").apply { mkdirs() }
        File(project, ".hidden/secret.md").writeText("secret")
        File(project, "Character").apply { mkdirs() }
        File(project, "Character/same.md").writeText("character")
        File(project, "Scene/Act1").apply { mkdirs() }
        File(project, "Scene/same.md").writeText("scene")
        File(project, "Scene/Act1/intro.MD").writeText("intro")

        val folders = fileManager.listFolders(PlatformFile(project))
        assertEquals(
            listOf("", "Character", "Scene"),
            folders.map { it.key.relativePath },
        )

        val character = folders.first { it.key == FolderKey.of("Character") }
        val scene = folders.first { it.key == FolderKey.of("Scene") }
        assertEquals(listOf("Character/same.md"), fileManager.listProjectFiles(character).map { it.key.relativePath })
        assertEquals(listOf("Scene/same.md"), fileManager.listProjectFiles(scene).map { it.key.relativePath })
    }

    @Test
    fun projectFolderFileBookmarkRestoresByRelativePath() = withFileManager { fileManager, project ->
        val scene = File(project, "Scene").apply { mkdirs() }
        val note = File(scene, "same.md").apply { writeText("scene") }
        val projectFile = ProjectFile(FileKey.of("Scene/same.md"), PlatformFile(note))

        fileManager.setPreferences(
            Bookmarks(
                projectData = PlatformFile(project),
                fileData = projectFile.platformFile,
                fileRelativePath = projectFile.key.relativePath,
            )
        )

        val restored = fileManager.getPreferences()
        assertEquals("Scene/same.md", restored.fileRelativePath)
        assertNotNull(restored.fileData)
        assertEquals(projectFile.platformFile.toString(), restored.fileData.toString())
    }

    @Test
    fun projectFolderFileRenameUsesItsActualParentFolder() = withFileManager { fileManager, project ->
        val scene = File(project, "Scene").apply { mkdirs() }
        val note = File(scene, "old.md").apply { writeText("scene") }
        val projectFile = ProjectFile(FileKey.of("Scene/old.md"), PlatformFile(note))
        fileManager.setPreferences(Bookmarks(projectData = PlatformFile(project)))

        val renamed = fileManager.renameFile(projectFile, "new")

        assertNotNull(renamed)
        assertEquals("scene", File(scene, "new.md").readText())
        assertNull(File(project, "new.md").takeIf { it.exists() })
    }

    @Test
    fun applyPlotOrderRenamesExactlyAndWritesStageFrontmatter() = withFileManager { fileManager, project ->
        val scene = File(project, "Scene").apply { mkdirs() }
        File(scene, "0. 시작.md").writeText("---\nplot: 1) 발단\n---\n\n시작")
        File(scene, "메모.md").writeText("메모")
        val folder = ProjectFolder(FolderKey.of("Scene"), PlatformFile(scene))
        val files = fileManager.listProjectFiles(folder).associateBy { it.key.fileName }

        val result = fileManager.applyPlotOrder(
            folder = folder,
            assignments = listOf(
                PlotOrderAssignment(files.getValue("0. 시작.md").key, PlotStage.SETUP, 2),
                PlotOrderAssignment(files.getValue("메모.md").key, PlotStage.DEVELOPMENT, 1),
            ),
        )

        assertNotNull(result)
        assertEquals(setOf("1-2. 시작.md", "2-1. 메모.md"), scene.listFiles()!!.filter { it.isFile }.map { it.name }.toSet())
        assertEquals(PlotStage.SETUP, NoteFile.parse(File(scene, "1-2. 시작.md").readText()).plotStage)
        assertEquals(PlotStage.DEVELOPMENT, NoteFile.parse(File(scene, "2-1. 메모.md").readText()).plotStage)
    }

    private fun withFileManager(
        block: suspend (FileManager, File) -> Unit,
    ) = runBlocking {
        val testRoot = Files.createTempDirectory("machum-folders").toFile()
        val project = File(testRoot, "Project").apply { mkdirs() }
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope) {
            File(testRoot, "preferences.preferences_pb").absolutePath.toPath()
        }

        try {
            block(FileManager(dataStore), project)
        } finally {
            dataStoreScope.cancel()
            testRoot.deleteRecursively()
        }
    }
}
