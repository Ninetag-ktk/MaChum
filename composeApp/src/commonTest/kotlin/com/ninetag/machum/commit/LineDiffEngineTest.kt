package com.ninetag.machum.commit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineDiffEngineTest {
    private val change = CommitChange("id", CommitChangeKind.MODIFIED, "A.md", "A.md")

    @Test
    fun buildsNumberedLineDiffWithContext() {
        val result = LineDiffEngine.build(
            change = change,
            oldContent = "one\ntwo\nold\nfour\nfive\nsix\nseven\neight",
            newContent = "one\ntwo\nnew\nextra\nfour\nfive\nsix\nseven\neight",
            contextLines = 1,
        )

        assertEquals(
            listOf(
                LineDiffKind.OMITTED,
                LineDiffKind.CONTEXT,
                LineDiffKind.DELETED,
                LineDiffKind.ADDED,
                LineDiffKind.ADDED,
                LineDiffKind.CONTEXT,
                LineDiffKind.OMITTED,
            ),
            result.lines.map { it.kind },
        )
        assertTrue(result.lines.any { it.text == "new" && it.newLineNumber == 3 })
        assertTrue(result.lines.any { it.text == "old" && it.oldLineNumber == 3 })
        assertFalse(result.isApproximate)
    }

    @Test
    fun unchangedRenameHasNoContentDiff() {
        val result = LineDiffEngine.build(
            change.copy(kind = CommitChangeKind.RENAMED),
            oldContent = "same",
            newContent = "same",
        )

        assertTrue(result.lines.isEmpty())
    }
}
