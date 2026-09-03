package com.ninetag.machum.markdown.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockNavigationTest {

    @Test
    fun defaultActionsAreSafeNoOps() {
        val navigation = BlockNavigation()

        navigation.focus.onMoveToPrevious()
        navigation.focus.onMoveToNext()
        navigation.focus.onMoveToPreviousWithX(12f)
        navigation.focus.onMoveToNextWithX(12f)
        navigation.focus.onMoveLeft()
        navigation.mutation.onMergeWithPrevious()
        navigation.mutation.onSplitBlock()
        navigation.mutation.onSplitByEmptyLine()
        navigation.mutation.onReparse()
        navigation.mutation.onReparseSilent()
        navigation.mutation.onDissolveSelf()
        navigation.mutation.onClearRawMode()
        navigation.selection.onExtendSelectionToPrevious()
        navigation.selection.onExtendSelectionToNext()
        navigation.selection.onSelectSelfAsAtomic()
    }

    @Test
    fun actionGroupsKeepTheirCallbacksIndependent() {
        val calls = mutableListOf<String>()
        val navigation = BlockNavigation(
            focus = BlockFocusActions(
                onMoveToNext = { calls += "focus" },
            ),
            mutation = BlockMutationActions(
                onReparse = { calls += "mutation" },
            ),
            selection = BlockSelectionActions(
                onSelectSelfAsAtomic = { calls += "selection" },
            ),
        )

        navigation.focus.onMoveToNext()
        navigation.mutation.onReparse()
        navigation.selection.onSelectSelfAsAtomic()

        assertEquals(listOf("focus", "mutation", "selection"), calls)
    }
}
