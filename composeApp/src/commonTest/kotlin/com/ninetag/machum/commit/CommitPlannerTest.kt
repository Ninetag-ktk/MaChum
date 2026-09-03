package com.ninetag.machum.commit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommitPlannerTest {
    @Test
    fun initialSnapshot_marksEveryFileAsAdded() {
        val snapshot = CommitPlanner.snapshot(
            listOf(
                WorkingFile("b", "2. Outline/Plan.md", "plan"),
                WorkingFile("a", "0. Draft.md", "draft"),
            ),
        )

        val changes = CommitPlanner.changes(previous = null, current = snapshot.tree)

        assertEquals(listOf(CommitChangeKind.ADDED, CommitChangeKind.ADDED), changes.map { it.kind })
        assertEquals(listOf("0. Draft.md", "2. Outline/Plan.md"), changes.map { it.displayPath })
    }

    @Test
    fun comparisonDetectsAddModifyDeleteRenameAndRenameWithModification() {
        val previous = CommitPlanner.snapshot(
            listOf(
                WorkingFile("modified", "A.md", "old"),
                WorkingFile("deleted", "B.md", "deleted"),
                WorkingFile("renamed", "Old.md", "same"),
                WorkingFile("both", "Before.md", "before"),
            ),
        )
        val current = CommitPlanner.snapshot(
            listOf(
                WorkingFile("modified", "A.md", "new"),
                WorkingFile("renamed", "New.md", "same"),
                WorkingFile("both", "After.md", "after"),
                WorkingFile("added", "C.md", "added"),
            ),
        )

        val byId = CommitPlanner.changes(previous.tree, current.tree).associateBy { it.fileId }

        assertEquals(CommitChangeKind.ADDED, byId.getValue("added").kind)
        assertEquals(CommitChangeKind.MODIFIED, byId.getValue("modified").kind)
        assertEquals(CommitChangeKind.DELETED, byId.getValue("deleted").kind)
        assertEquals(CommitChangeKind.RENAMED, byId.getValue("renamed").kind)
        assertEquals(CommitChangeKind.RENAMED_AND_MODIFIED, byId.getValue("both").kind)
        assertEquals("Old.md", byId.getValue("renamed").oldPath)
        assertEquals("New.md", byId.getValue("renamed").newPath)
    }

    @Test
    fun unchangedContentReusesTheSameBlobHash() {
        val first = CommitPlanner.snapshot(listOf(WorkingFile("id", "Old.md", "same content")))
        val second = CommitPlanner.snapshot(listOf(WorkingFile("id", "New.md", "same content")))

        assertEquals(first.tree.entries.single().blobHash, second.tree.entries.single().blobHash)
        assertEquals(CommitChangeKind.RENAMED, CommitPlanner.changes(first.tree, second.tree).single().kind)
    }

    @Test
    fun duplicateFileIdStopsSnapshotCreation() {
        val error = assertFailsWith<CommitConflictException> {
            CommitPlanner.snapshot(
                listOf(
                    WorkingFile("same", "A.md", "a"),
                    WorkingFile("same", "B.md", "b"),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("same"))
    }

    @Test
    fun sha256MatchesKnownValue() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Utf8("abc"),
        )
    }
}

