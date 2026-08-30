package com.ninetag.machum.external

import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.PlotStage
import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectFileOrderingTest {

    @Test
    fun numberedFilesSortNumericallyAndUnnumberedFilesFollow() {
        val files = listOf("10. 열.md", "외부.md", "2. 둘.md", "0. 영.md").map(::projectFile)

        assertEquals(
            listOf("0. 영.md", "2. 둘.md", "10. 열.md", "외부.md"),
            files.sortedFor(FolderConfig()).map { it.key.fileName },
        )
    }

    @Test
    fun nextNumberUsesLargestExistingPrefix() {
        val files = listOf("0. 영.md", "4. 넷.md", "외부.md").map(::projectFile)

        assertEquals(5, files.nextNumber())
        assertEquals(1, listOf(projectFile("외부.md")).nextNumber())
        assertEquals(0, listOf(projectFile("외부.md")).nextNumber(startAt = 0))
        assertEquals("1. 새 장면", emptyList<ProjectFile>().nextDefaultFileName("새 장면"))
        assertEquals("0. 새 장면", emptyList<ProjectFile>().nextDefaultFileName("새 장면", startAt = 0))
        assertEquals("5. 새 장면", files.nextDefaultFileName("새 장면"))
    }

    @Test
    fun plotPrefixAndTitleUseStageAndOneBasedOrder() {
        val file = projectFile("1-12. 사건의 시작.md")

        assertEquals(PlotFilePrefix(stageCode = 1, order = 12), file.plotPrefix())
        assertEquals(12, file.plotOrder())
        assertEquals("사건의 시작", file.plotTitle())
    }

    @Test
    fun plotFilesSortByStageThenOrderWithUnclassifiedLast() {
        val entries = listOf(
            PlotFileEntry(projectFile("2-1. 전개.md"), PlotStage.DEVELOPMENT, 1),
            PlotFileEntry(projectFile("외부.md"), null, null),
            PlotFileEntry(projectFile("1-2. 둘.md"), PlotStage.SETUP, 2),
            PlotFileEntry(projectFile("1-1. 하나.md"), PlotStage.SETUP, 1),
            PlotFileEntry(projectFile("0-1. 프롤로그.md"), PlotStage.PROLOGUE, 1),
        )

        assertEquals(
            listOf("0-1. 프롤로그.md", "1-1. 하나.md", "1-2. 둘.md", "2-1. 전개.md", "외부.md"),
            entries.sortedForPlot().map { it.projectFile.key.fileName },
        )
        assertEquals(3, entries.nextPlotOrder(PlotStage.SETUP))
        assertEquals("1-3. 새 사건", entries.nextPlotFileName(PlotStage.SETUP, "새 사건"))
        assertEquals(0, entries.nextPlotOrder(PlotStage.CRISIS))
        assertEquals("3-0. 새 위기", entries.nextPlotFileName(PlotStage.CRISIS, "새 위기"))
        assertEquals(PlotFilePrefix(stageCode = 1, order = 0), projectFile("1-0. 첫 장면.md").plotPrefix())
    }

    private fun projectFile(name: String): ProjectFile =
        ProjectFile(FileKey.of(name), PlatformFile(name))
}
