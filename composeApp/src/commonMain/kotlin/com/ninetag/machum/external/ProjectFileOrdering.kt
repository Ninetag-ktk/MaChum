package com.ninetag.machum.external

import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage

private val numberedPrefixRegex = Regex("""^(\d+)\.\s*""")
private val plotPrefixRegex = Regex("""^(\d+)-(\d+)\.\s*""")

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

fun List<PlotFileEntry>.sortedForPlot(): List<PlotFileEntry> = sortedWith(
    compareBy<PlotFileEntry> { it.stage == null }
        .thenBy { it.stage?.code ?: Int.MAX_VALUE }
        .thenBy { it.order == null }
        .thenBy { it.order ?: Int.MAX_VALUE }
        .thenBy { it.projectFile.key.fileName.lowercase() },
)

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
