package com.ninetag.machum.markdown.state

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownLinkCompletionTest {
    @Test
    fun completedPreviewCaretDoesNotRequestCandidatesWithoutExplicitRawEditing() {
        assertNull(markdownLinkCompletionEditingRequest("[[시인]]", TextRange(3)))
        assertNull(markdownLinkCompletionEditingRequest("[label](Target)", TextRange(10)))
        assertEquals("Target", markdownLinkCompletionEditingRequest("![[Target]]", TextRange(5))?.query)
        assertNull(markdownLinkCompletionEditingRequest("\\![[Target]]", TextRange(6)))
        assertEquals("Target", markdownLinkCompletionEditingRequest("\\\\![[Target]]", TextRange(7))?.query)
        assertEquals("시인", markdownLinkCompletionEditingRequest("[[시인]]", TextRange(3), 0..5)?.query)
        assertEquals("Target", markdownLinkCompletionEditingRequest("[label](Target)", TextRange(10), rawMode = true)?.query)
    }

    @Test
    fun incompleteDraftsAndWrappedSelectionsRemainEligibleWithoutOpeningRaw() {
        assertEquals("시인", markdownLinkCompletionEditingRequest("[[시인", TextRange(4))?.query)
        assertEquals("Target", markdownLinkCompletionEditingRequest("[label](Target", TextRange(10))?.query)
        assertEquals("selection", markdownLinkCompletionEditingRequest("[[selection]]", TextRange(2, 11))?.displayText)
        assertNull(markdownLinkCompletionEditingRequest("[[Target|label]]", TextRange(10), rawMode = true))
    }

    @Test
    fun wikiAliasKeepsTargetCompletionAndSuppressesOnlyAfterSeparator() {
        val text = "[[Folder/Document|label]]"
        val pipe = text.indexOf('|')

        for (cursor in 2..pipe) {
            val request = markdownLinkCompletionRequest(text, cursor)
            assertEquals("Folder/Document", request?.query)
            assertEquals(pipe, request?.replacementEndExclusive)
            assertEquals(true, request?.hasExistingAlias)
        }
        for (cursor in pipe + 1..text.indexOf("]]")) assertNull(markdownLinkCompletionRequest(text, cursor))
        assertNull(markdownLinkCompletionRequest(text, text.length))
    }

    @Test
    fun movingCaretBackAndDeletingAliasSeparatorResumesTargetCompletion() {
        val withAlias = "[[Folder/Document|label]]"
        assertNull(markdownLinkCompletionRequest(withAlias, withAlias.indexOf('|') + 1))
        assertEquals("Folder/Document", markdownLinkCompletionRequest(withAlias, 3)?.query)

        val target = "[[Folder/Document]]"
        assertEquals("Folder/Document", markdownLinkCompletionRequest(target, 3)?.query)

        val escapedPipe = "[[Folder\\|Document]]"
        assertEquals("Folder\\|Document", markdownLinkCompletionRequest(escapedPipe, 3)?.query)
    }

    @Test
    fun embedAndHeadingAliasCompletionUsesOnlyTargetAndPreservesAliasRange() {
        val embed = "![[Folder/Document|label]]"
        val embedRequest = markdownLinkCompletionRequest(embed, 4)!!
        assertEquals("Folder/Document", embedRequest.query)
        assertEquals(embed.indexOf('|'), embedRequest.replacementEndExclusive)

        val heading = "[[Folder#Heading|label]]"
        val headingRequest = markdownLinkCompletionRequest(heading, 4)!!
        assertEquals(MarkdownLinkCompletionKind.HEADING, headingRequest.kind)
        assertEquals("Folder", headingRequest.file)
        assertEquals("Heading", headingRequest.query)

        assertEquals(
            "![[Folder/Resolved|label]]",
            embed.replaceRange(embedRequest.replacementStart, embedRequest.replacementEndExclusive, "Folder/Resolved"),
        )
    }

    @Test
    fun selectedTextCanStillBeWrappedAsWikiLinkWithoutAlias() {
        assertEquals(
            "selection",
            markdownLinkCompletionRequest("[[selection]]", TextRange(2, 11))?.displayText,
        )
        assertNull(markdownLinkCompletionRequest("[[target|selection]]", TextRange(2, 18)))
    }

    @Test
    fun markdownCompletionUsesWholeInternalTarget() {
        val text = "[label](Folder/Document.md)"

        assertEquals("Folder/Document.md", markdownLinkCompletionRequest(text, 10)?.query)
        assertNull(markdownLinkCompletionRequest("[label](https://example.com)", 10))
    }

    @Test
    fun markdownLinkInsideWikiAliasDoesNotStartCompletion() {
        val aliasedWikiLink = "[[Document|[label](OtherDocument)]]"

        assertNull(markdownLinkCompletionRequest(aliasedWikiLink, aliasedWikiLink.indexOf("OtherDocument") + 3))
        assertEquals("OtherDocument", markdownLinkCompletionRequest("[label](OtherDocument)", 15)?.query)
    }

    @Test
    fun lookupKeyIgnoresInnerCursorButTracksTargetChanges() {
        val text = "[[Folder/Document]]"
        val first = markdownLinkCompletionRequest(text, 3)!!
        val second = markdownLinkCompletionRequest(text, 15)!!

        assertEquals(
            markdownLinkCompletionLookupKey(text, first),
            markdownLinkCompletionLookupKey(text, second),
        )

        val changed = "[[Folder/Other]]"
        val changedRequest = markdownLinkCompletionRequest(changed, 3)!!
        kotlin.test.assertNotEquals(
            markdownLinkCompletionLookupKey(text, first),
            markdownLinkCompletionLookupKey(changed, changedRequest),
        )
    }

    @Test
    fun blockNavigationStartsAtOwningParagraph() {
        val text = "first line\nsecond line ^id"
        assertEquals(0, markdownNavigationOffset(text, MarkdownNavigationTarget(1, blockId = "id")))
    }

    @Test
    fun standaloneBlockIdStartsAtPreviousParagraph() {
        val text = "# heading\nfirst line\nsecond line\n\n^id"
        assertEquals(text.indexOf("first line"), markdownNavigationOffset(text, MarkdownNavigationTarget(1, blockId = "id")))

        val windowsText = "# heading\r\nfirst line\r\nsecond line\r\n\r\n^id"
        assertEquals(
            windowsText.indexOf("first line"),
            markdownNavigationOffset(windowsText, MarkdownNavigationTarget(2, blockId = "id")),
        )
    }

    @Test
    fun standaloneBlockIdStartsAtListQuoteTableAndFence() {
        val list = "preface\n\n- one\n  continued\n- two\n\n^id"
        assertEquals(list.indexOf("- one"), markdownNavigationOffset(list, MarkdownNavigationTarget(1, blockId = "id")))

        val quote = "preface\n\n> one\n> two\n\n^id"
        assertEquals(quote.indexOf("> one"), markdownNavigationOffset(quote, MarkdownNavigationTarget(1, blockId = "id")))

        val table = "preface | text\n| A | B |\n|---|---|\n| 1 | 2 |\n\n^id"
        assertEquals(table.indexOf("| A"), markdownNavigationOffset(table, MarkdownNavigationTarget(1, blockId = "id")))

        val code = "preface\n````\n```\ncode\n````\n\n^id"
        assertEquals(code.indexOf("```"), markdownNavigationOffset(code, MarkdownNavigationTarget(1, blockId = "id")))

        val callout = "preface\n> [!NOTE] title\n> body\n\n^id"
        assertEquals(callout.indexOf("> [!NOTE]"), markdownNavigationOffset(callout, MarkdownNavigationTarget(1, blockId = "id")))
    }

    @Test
    fun horizontalRulesRemainSeparateBlockBoundaries() {
        val paragraph = "***\nparagraph ^paragraph"
        val rule = "paragraph\n***\n\n^rule"

        assertEquals(paragraph.indexOf("paragraph"), markdownNavigationOffset(paragraph, MarkdownNavigationTarget(1, blockId = "paragraph")))
        assertEquals(rule.indexOf("***"), markdownNavigationOffset(rule, MarkdownNavigationTarget(2, blockId = "rule")))
    }
}
