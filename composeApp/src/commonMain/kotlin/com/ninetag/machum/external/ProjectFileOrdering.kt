package com.ninetag.machum.external

import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage

private val numberedPrefixRegex = Regex("""^(\d+)\.\s*""")
private val plotPrefixRegex = Regex("""^(\d+)-(\d+)\.\s*""")
private val hierarchicalNumberPrefixRegex = Regex("""^(\d+(?:-\d+)*)\.\s*""")

fun ProjectFile.numberedPrefix(): Int? =
    numberedPrefixRegex.find(key.fileName)?.groupValues?.get(1)?.toIntOrNull()

data class PlotFilePrefix(
    val stageCode: Int,
    val order: Int,
)

data class PlotFileEntry(
    val projectFile: ProjectFile,
    val stage: PlotStage?,
    val order: Int?,
) {
    val title: String
        get() = projectFile.plotTitle()
}

data class PlotOrderAssignment(
    val fileKey: FileKey,
    val stage: PlotStage,
    val order: Int,
)

data class PlotOrderUpdate(
    val oldKey: FileKey,
    val projectFile: ProjectFile,
    val noteFile: NoteFile,
)

fun ProjectFile.plotPrefix(): PlotFilePrefix? {
    val match = plotPrefixRegex.find(key.fileName) ?: return null
    val stageCode = match.groupValues[1].toIntOrNull() ?: return null
    val order = match.groupValues[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return PlotFilePrefix(stageCode, order)
}

fun ProjectFile.plotOrder(): Int? = plotPrefix()?.order ?: numberedPrefix()

fun ProjectFile.plotTitle(): String = key.fileName
    .let { name -> if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name }
    .replace(plotPrefixRegex, "")
    .replace(numberedPrefixRegex, "")
    .ifBlank { "제목" }

fun List<PlotFileEntry>.sortedForPlot(): List<PlotFileEntry> {
    val classified = filter { it.stage != null }.sortedWith(
        compareBy<PlotFileEntry> { it.stage?.code ?: Int.MAX_VALUE }
            .thenBy { it.order == null }
            .thenBy { it.order ?: Int.MAX_VALUE }
            .thenBy { it.projectFile.key.fileName.lowercase() },
    )
    val unclassified = filter { it.stage == null }.sortedWith { left, right ->
        compareHierarchicalFileNames(left.projectFile, right.projectFile)
    }
    return classified + unclassified
}

private fun compareHierarchicalFileNames(left: ProjectFile, right: ProjectFile): Int {
    val leftNumbers = left.hierarchicalNumberPrefix()
    val rightNumbers = right.hierarchicalNumberPrefix()
    if (leftNumbers != null && rightNumbers != null) {
        repeat(minOf(leftNumbers.size, rightNumbers.size)) { index ->
            val compared = leftNumbers[index].compareTo(rightNumbers[index])
            if (compared != 0) return compared
        }
        val lengthCompared = leftNumbers.size.compareTo(rightNumbers.size)
        if (lengthCompared != 0) return lengthCompared
    } else if (leftNumbers != null) {
        return -1
    } else if (rightNumbers != null) {
        return 1
    }
    return left.key.fileName.lowercase().compareTo(right.key.fileName.lowercase())
}

private fun ProjectFile.hierarchicalNumberPrefix(): List<Int>? {
    val prefix = hierarchicalNumberPrefixRegex.find(key.fileName)
        ?.groupValues
        ?.get(1)
        ?: return null
    return prefix.split('-').map { segment -> segment.toIntOrNull() ?: return null }
}

fun List<ProjectFile>.sortedFor(folderConfig: FolderConfig): List<ProjectFile> = when (folderConfig.type) {
    FolderType.DEFAULT -> sortedWith(
        compareBy<ProjectFile> { it.numberedPrefix() == null }
            .thenBy { it.numberedPrefix() ?: Int.MAX_VALUE }
            .thenBy { it.key.fileName.lowercase() }
    )
    FolderType.GENERAL -> sortedBy { it.key.fileName.lowercase() }
}

fun List<ProjectFile>.nextNumber(startAt: Int = 1): Int {
    require(startAt >= 0) { "startAt must not be negative" }
    return mapNotNull(ProjectFile::numberedPrefix).maxOrNull()?.plus(1) ?: startAt
}

fun List<ProjectFile>.nextDefaultFileName(title: String, startAt: Int = 1): String =
    "${nextNumber(startAt)}. $title"

fun List<PlotFileEntry>.nextPlotOrder(stage: PlotStage): Int =
    filter { it.stage == stage }
        .mapNotNull(PlotFileEntry::order)
        .maxOrNull()
        ?.plus(1)
        ?: PlotStage.FIRST_ORDER

fun List<PlotFileEntry>.nextPlotFileName(stage: PlotStage, title: String): String =
    stage.fileName(nextPlotOrder(stage), title)
