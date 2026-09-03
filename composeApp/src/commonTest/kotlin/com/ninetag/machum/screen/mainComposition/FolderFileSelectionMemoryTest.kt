package com.ninetag.machum.screen.mainComposition

import com.ninetag.machum.external.FolderKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderFileSelectionMemoryTest {
    @Test
    fun `remembers one selected file per folder`() {
        val memory = FolderFileSelectionMemory()
        val character = FolderKey.of("3. Character")
        val scene = FolderKey.of("4. Scene")
        val hero = character.file("hero.md")
        val opening = scene.file("1-0. opening.md")

        memory.remember(hero)
        memory.remember(opening)

        assertEquals(hero, memory.preferred(character, listOf(hero)))
        assertEquals(opening, memory.preferred(scene, listOf(opening)))
    }

    @Test
    fun `drops a remembered file that no longer exists`() {
        val memory = FolderFileSelectionMemory()
        val folder = FolderKey.of("3. Character")
        val removed = folder.file("removed.md")

        memory.remember(removed)

        assertNull(memory.preferred(folder, emptyList()))
        assertNull(memory.preferred(folder, listOf(folder.file("other.md"))))
    }

    @Test
    fun `tracks file and folder renames`() {
        val memory = FolderFileSelectionMemory()
        val oldFolder = FolderKey.of("3. Character")
        val renamedFolder = FolderKey.of("3. Cast")
        val oldFile = oldFolder.file("hero.md")
        val renamedFile = oldFolder.file("main_hero.md")

        memory.remember(oldFile)
        memory.renameFile(oldFile, renamedFile)
        assertEquals(renamedFile, memory.preferred(oldFolder, listOf(renamedFile)))

        memory.renameFolder(oldFolder, renamedFolder)
        val movedFile = renamedFolder.file("main_hero.md")
        assertNull(memory.preferred(oldFolder, listOf(renamedFile)))
        assertEquals(movedFile, memory.preferred(renamedFolder, listOf(movedFile)))
    }

    @Test
    fun `forgets deleted folders and clears project state`() {
        val memory = FolderFileSelectionMemory()
        val character = FolderKey.of("3. Character")
        val scene = FolderKey.of("4. Scene")
        val hero = character.file("hero.md")
        val opening = scene.file("opening.md")

        memory.remember(hero)
        memory.remember(opening)
        memory.forget(character)
        assertNull(memory.preferred(character, listOf(hero)))

        memory.clear()
        assertNull(memory.preferred(scene, listOf(opening)))
    }
}
