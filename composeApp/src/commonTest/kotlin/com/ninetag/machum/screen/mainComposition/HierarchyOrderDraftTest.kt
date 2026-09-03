package com.ninetag.machum.screen.mainComposition

import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.PlotFileEntry
import com.ninetag.machum.external.ProjectFile
import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HierarchyOrderDraftTest {

    @Test
    fun defaultDraftMovesOnlyNumberedFilesAndKeepsUnnumberedPosition() {
        val files = listOf(file("1. 하나.md"), file("참고.md"), file("2. 둘.md"))
        val draft = defaultHierarchyOrderDraft(FolderKey.Base, files)
        val moved = draft.move(files[2].key, -1)

        assertEquals(listOf(files[2].key, files[0].key), moved.currentKeys)
        assertEquals(
            listOf("2. 둘.md", "참고.md", "1. 하나.md"),
            moved.reorder(files).map { it.key.fileName },
        )
        assertEquals("0. 둘", moved.displayName(files[2]))
        assertEquals("1. 하나", moved.displayName(files[0]))
        val folderMoved = defaultHierarchyOrderDraft(FolderKey.of("Concept"), files)
            .move(files[2].key, -1)
        assertEquals("1. 둘", folderMoved.displayName(files[2]))
        assertEquals("2. 하나", folderMoved.displayName(files[0]))
        assertFalse(moved.contains(files[1].key))
        assertTrue(moved.hasChanges)
    }

    @Test
    fun plotDraftMovesWithinStageAndAcrossAdjacentStage() {
        val setupOne = plotEntry("1-1. 하나.md", PlotStage.SETUP, 1)
        val setupTwo = plotEntry("1-2. 둘.md", PlotStage.SETUP, 2)
        val development = plotEntry("2-1. 셋.md", PlotStage.DEVELOPMENT, 1)
        val draft = plotHierarchyOrderDraft(
            FolderKey.of("Scene"),
            listOf(setupOne, setupTwo, development),
        )

        val withinStage = draft.move(setupTwo.projectFile.key, -1)
        val nextStage = withinStage.move(setupTwo.projectFile.key, 1).move(setupTwo.projectFile.key, 1)

        assertEquals(
            listOf(setupTwo.projectFile.key, development.projectFile.key),
            nextStage.currentItems
                .filter { it.stage == PlotStage.DEVELOPMENT }
                .map(HierarchyPlotOrderItem::fileKey),
        )
        assertEquals(
            listOf(1, 2),
            nextStage.assignments()
                .filter { it.stage == PlotStage.DEVELOPMENT }
                .map { it.order },
        )
    }

    @Test
    fun unclassifiedPlotItemCanMoveIntoEpilogueButDefinedItemCannotMoveToUnclassified() {
        val epilogue = plotEntry("6-1. 끝.md", PlotStage.EPILOGUE, 1)
        val unclassified = plotEntry("외부.md", null, null)
        val draft = plotHierarchyOrderDraft(
            FolderKey.of("Scene"),
            listOf(epilogue, unclassified),
        )

        val unchanged = draft.move(epilogue.projectFile.key, 1)
        val moved = unchanged.move(unclassified.projectFile.key, -1)

        assertEquals(draft, unchanged)
        assertEquals(
            listOf(epilogue.projectFile.key, unclassified.projectFile.key),
            moved.currentItems
                .filter { it.stage == PlotStage.EPILOGUE }
                .map(HierarchyPlotOrderItem::fileKey),
        )
        assertEquals(2, moved.assignments().count { it.stage == PlotStage.EPILOGUE })
    }

    @Test
    fun legacyZeroBasedPlotItemsAreWrittenBackAsOneBasedAssignments() {
        val legacyFirst = plotEntry("1-0. 하나.md", PlotStage.SETUP, 0)
        val second = plotEntry("1-1. 둘.md", PlotStage.SETUP, 1)
        val moved = plotHierarchyOrderDraft(
            FolderKey.of("Scene"),
            listOf(legacyFirst, second),
        ).move(second.projectFile.key, -1)

        assertEquals(
            listOf(1, 2),
            moved.assignments().map { it.order },
        )
        assertEquals(
            listOf(1, 2),
            moved.reorder(listOf(legacyFirst, second)).map { it.order },
        )
    }

    private fun file(name: String): ProjectFile = ProjectFile(FileKey.of(name), PlatformFile(name))

    private fun plotEntry(name: String, stage: PlotStage?, order: Int?): PlotFileEntry =
        PlotFileEntry(file(name), stage, order)
}
