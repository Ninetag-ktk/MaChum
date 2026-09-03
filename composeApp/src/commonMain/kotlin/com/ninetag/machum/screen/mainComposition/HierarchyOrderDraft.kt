package com.ninetag.machum.screen.mainComposition

import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.PlotFileEntry
import com.ninetag.machum.external.PlotOrderAssignment
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.numberedPrefix
import com.ninetag.machum.external.plotTitle

internal sealed interface HierarchyOrderDraft {
    val folderKey: FolderKey
    val hasChanges: Boolean

    fun contains(fileKey: FileKey): Boolean

    fun move(fileKey: FileKey, direction: Int): HierarchyOrderDraft
}

internal data class DefaultHierarchyOrderDraft(
    override val folderKey: FolderKey,
    val originalKeys: List<FileKey>,
    val currentKeys: List<FileKey> = originalKeys,
) : HierarchyOrderDraft {
    override val hasChanges: Boolean
        get() = currentKeys != originalKeys

    override fun contains(fileKey: FileKey): Boolean = fileKey in currentKeys

    override fun move(fileKey: FileKey, direction: Int): DefaultHierarchyOrderDraft {
        if (direction != -1 && direction != 1) return this
        val sourceIndex = currentKeys.indexOf(fileKey)
        val targetIndex = sourceIndex + direction
        if (sourceIndex < 0 || targetIndex !in currentKeys.indices) return this

        val reordered = currentKeys.toMutableList()
        reordered[sourceIndex] = reordered[targetIndex]
        reordered[targetIndex] = fileKey
        return copy(currentKeys = reordered)
    }

    fun reorder(files: List<ProjectFile>): List<ProjectFile> {
        val filesByKey = files.associateBy(ProjectFile::key)
        val reorderedManaged = currentKeys.mapNotNull(filesByKey::get).iterator()
        return files.map { file ->
            if (file.numberedPrefix() != null && reorderedManaged.hasNext()) {
                reorderedManaged.next()
            } else {
                file
            }
        }
    }

    fun displayName(file: ProjectFile): String? {
        if (!hasChanges) return null
        val index = currentKeys.indexOf(file.key)
        if (index < 0) return null
        val startAt = if (folderKey == FolderKey.Base) 0 else 1
        return "${startAt + index}. ${file.plotTitle()}"
    }
}

internal data class HierarchyPlotOrderItem(
    val fileKey: FileKey,
    val stage: PlotStage?,
)

internal data class PlotHierarchyOrderDraft(
    override val folderKey: FolderKey,
    val originalItems: List<HierarchyPlotOrderItem>,
    val currentItems: List<HierarchyPlotOrderItem> = originalItems,
) : HierarchyOrderDraft {
    override val hasChanges: Boolean
        get() = currentItems != originalItems

    override fun contains(fileKey: FileKey): Boolean = currentItems.any { it.fileKey == fileKey }

    override fun move(fileKey: FileKey, direction: Int): PlotHierarchyOrderDraft {
        if (direction != -1 && direction != 1) return this
        val source = currentItems.find { it.fileKey == fileKey } ?: return this
        val stageGroups = PlotStage.entries.associateWith { stage ->
            currentItems.filterTo(mutableListOf()) { it.stage == stage }
        }
        val unclassified = currentItems.filterTo(mutableListOf()) { it.stage == null }

        if (source.stage == null) {
            if (direction > 0 || !unclassified.remove(source)) return this
            stageGroups.getValue(PlotStage.EPILOGUE)
                .add(source.copy(stage = PlotStage.EPILOGUE))
            return copy(currentItems = rebuildPlotItems(stageGroups, unclassified))
        }

        val sourceGroup = stageGroups.getValue(source.stage)
        val sourceIndex = sourceGroup.indexOfFirst { it.fileKey == fileKey }
        if (sourceIndex < 0) return this
        val targetIndex = sourceIndex + direction
        if (targetIndex in sourceGroup.indices) {
            val moved = sourceGroup.removeAt(sourceIndex)
            sourceGroup.add(targetIndex, moved)
            return copy(currentItems = rebuildPlotItems(stageGroups, unclassified))
        }

        val targetStage = PlotStage.entries.getOrNull(source.stage.ordinal + direction) ?: return this
        val moved = sourceGroup.removeAt(sourceIndex).copy(stage = targetStage)
        val targetGroup = stageGroups.getValue(targetStage)
        targetGroup.add(if (direction > 0) 0 else targetGroup.size, moved)
        return copy(currentItems = rebuildPlotItems(stageGroups, unclassified))
    }

    fun reorder(entries: List<PlotFileEntry>): List<PlotFileEntry> {
        if (!hasChanges) return entries
        val entriesByKey = entries.associateBy { it.projectFile.key }
        val nextOrderByStage = mutableMapOf<PlotStage, Int>()
        return currentItems.mapNotNull { item ->
            val entry = entriesByKey[item.fileKey] ?: return@mapNotNull null
            val order = item.stage?.let { stage ->
                nextOrderByStage.getOrPut(stage) { PlotStage.FIRST_ORDER }
                    .also { nextOrderByStage[stage] = it + 1 }
            }
            entry.copy(stage = item.stage, order = order)
        }
    }

    fun assignments(): List<PlotOrderAssignment> = PlotStage.entries.flatMap { stage ->
        currentItems.filter { it.stage == stage }.mapIndexed { index, item ->
            PlotOrderAssignment(item.fileKey, stage, index + PlotStage.FIRST_ORDER)
        }
    }
}

internal fun defaultHierarchyOrderDraft(
    folderKey: FolderKey,
    files: List<ProjectFile>,
): DefaultHierarchyOrderDraft {
    val managedKeys = files.filter { it.numberedPrefix() != null }.map(ProjectFile::key)
    return DefaultHierarchyOrderDraft(folderKey = folderKey, originalKeys = managedKeys)
}

internal fun plotHierarchyOrderDraft(
    folderKey: FolderKey,
    entries: List<PlotFileEntry>,
): PlotHierarchyOrderDraft {
    val orderedItems = buildList {
        PlotStage.entries.forEach { stage ->
            entries.filter { it.stage == stage }.forEach { entry ->
                add(HierarchyPlotOrderItem(entry.projectFile.key, stage))
            }
        }
        entries.filter { it.stage == null }.forEach { entry ->
            add(HierarchyPlotOrderItem(entry.projectFile.key, null))
        }
    }
    return PlotHierarchyOrderDraft(folderKey = folderKey, originalItems = orderedItems)
}

private fun rebuildPlotItems(
    stageGroups: Map<PlotStage, List<HierarchyPlotOrderItem>>,
    unclassified: List<HierarchyPlotOrderItem>,
): List<HierarchyPlotOrderItem> = buildList {
    PlotStage.entries.forEach { stage -> addAll(stageGroups.getValue(stage)) }
    addAll(unclassified)
}
