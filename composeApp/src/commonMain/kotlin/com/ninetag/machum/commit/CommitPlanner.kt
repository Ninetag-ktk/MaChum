package com.ninetag.machum.commit

internal object CommitPlanner {
    fun snapshot(files: List<WorkingFile>): ProjectSnapshot {
        val duplicateIds = files.groupBy(WorkingFile::fileId).filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            throw CommitConflictException(
                "동일한 파일 ID가 여러 파일에 사용되고 있습니다: ${duplicateIds.sorted().joinToString()}",
            )
        }
        val duplicatePaths = files.groupBy(WorkingFile::relativePath).filterValues { it.size > 1 }.keys
        if (duplicatePaths.isNotEmpty()) {
            throw CommitConflictException(
                "동일한 상대 경로가 여러 번 발견되었습니다: ${duplicatePaths.sorted().joinToString()}",
            )
        }

        val blobs = linkedMapOf<String, String>()
        val entries = files
            .map { file ->
                val blobHash = sha256Utf8(file.content)
                blobs.putIfAbsent(blobHash, file.content)
                CommitTreeEntry(
                    fileId = file.fileId,
                    relativePath = file.relativePath,
                    blobHash = blobHash,
                )
            }
            .sortedWith(compareBy(CommitTreeEntry::fileId, CommitTreeEntry::relativePath))
        return ProjectSnapshot(CommitTree(entries), blobs)
    }

    fun changes(previous: CommitTree?, current: CommitTree): List<CommitChange> {
        val oldById = previous?.entries.orEmpty().associateBy(CommitTreeEntry::fileId)
        val newById = current.entries.associateBy(CommitTreeEntry::fileId)
        val ids = (oldById.keys + newById.keys).sorted()

        return ids.mapNotNull { fileId ->
            val old = oldById[fileId]
            val new = newById[fileId]
            when {
                old == null && new != null -> CommitChange(
                    fileId = fileId,
                    kind = CommitChangeKind.ADDED,
                    newPath = new.relativePath,
                    newBlobHash = new.blobHash,
                )
                old != null && new == null -> CommitChange(
                    fileId = fileId,
                    kind = CommitChangeKind.DELETED,
                    oldPath = old.relativePath,
                    oldBlobHash = old.blobHash,
                )
                old == null || new == null -> null
                old.relativePath != new.relativePath && old.blobHash != new.blobHash -> CommitChange(
                    fileId = fileId,
                    kind = CommitChangeKind.RENAMED_AND_MODIFIED,
                    oldPath = old.relativePath,
                    newPath = new.relativePath,
                    oldBlobHash = old.blobHash,
                    newBlobHash = new.blobHash,
                )
                old.relativePath != new.relativePath -> CommitChange(
                    fileId = fileId,
                    kind = CommitChangeKind.RENAMED,
                    oldPath = old.relativePath,
                    newPath = new.relativePath,
                    oldBlobHash = old.blobHash,
                    newBlobHash = new.blobHash,
                )
                old.blobHash != new.blobHash -> CommitChange(
                    fileId = fileId,
                    kind = CommitChangeKind.MODIFIED,
                    oldPath = old.relativePath,
                    newPath = new.relativePath,
                    oldBlobHash = old.blobHash,
                    newBlobHash = new.blobHash,
                )
                else -> null
            }
        }.sortedWith(compareBy(CommitChange::displayPath, CommitChange::fileId))
    }
}

