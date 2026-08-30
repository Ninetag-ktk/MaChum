package com.ninetag.machum.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FileKeyTest {

    @Test
    fun normalizesSeparatorsAndDotSegments() {
        assertEquals("Scene/intro.md", FileKey.of("/Scene\\./intro.md").relativePath)
    }

    @Test
    fun duplicateNamesInDifferentFoldersHaveDifferentKeys() {
        assertNotEquals(FileKey.of("A/intro.md"), FileKey.of("B/intro.md"))
    }

    @Test
    fun renamePreservesParentFolder() {
        assertEquals("Scene/new.md", FileKey.of("Scene/old.md").rename("new.md").relativePath)
    }

    @Test
    fun baseAndProjectFolderCreateFileKeys() {
        assertEquals("root.md", FolderKey.Base.file("root.md").relativePath)
        assertEquals("Scene/intro.md", FolderKey.of("Scene").file("intro.md").relativePath)
    }

    @Test
    fun rejectsParentTraversal() {
        assertFailsWith<IllegalArgumentException> { FileKey.of("../outside.md") }
    }
}
