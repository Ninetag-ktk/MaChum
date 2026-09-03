package com.ninetag.machum.markdown.state

import kotlin.test.Test
import kotlin.test.assertEquals

class CalloutBodyPolicyTest {

    @Test
    fun activateCreatesOnlyWhenBodyIsEmpty() {
        assertEquals(CalloutBodyAction.CreateBody, CalloutBodyPolicy.activate(hasBody = false))
        assertEquals(CalloutBodyAction.FocusBodyStart, CalloutBodyPolicy.activate(hasBody = true))
    }

    @Test
    fun titleEntryRespectsStandardAndDialogueLayout() {
        assertEquals(
            CalloutBodyAction.FocusBodyStart,
            CalloutBodyPolicy.titleDown(CalloutBodyLayout.Standard, hasBody = true),
        )
        assertEquals(
            CalloutBodyAction.MoveNext,
            CalloutBodyPolicy.titleDown(CalloutBodyLayout.Standard, hasBody = false),
        )
        assertEquals(
            CalloutBodyAction.MoveNext,
            CalloutBodyPolicy.titleDown(CalloutBodyLayout.Dialogue, hasBody = true),
        )
        assertEquals(
            CalloutBodyAction.FocusBodyStart,
            CalloutBodyPolicy.titleRight(
                layout = CalloutBodyLayout.Dialogue,
                hasBody = true,
                isAtTitleEnd = true,
            ),
        )
        assertEquals(
            CalloutBodyAction.Ignore,
            CalloutBodyPolicy.titleRight(
                layout = CalloutBodyLayout.Dialogue,
                hasBody = false,
                isAtTitleEnd = true,
            ),
        )
        assertEquals(
            CalloutBodyAction.Ignore,
            CalloutBodyPolicy.titleRight(
                layout = CalloutBodyLayout.Dialogue,
                hasBody = true,
                isAtTitleEnd = false,
            ),
        )
        assertEquals(
            CalloutBodyAction.Ignore,
            CalloutBodyPolicy.titleRight(
                layout = CalloutBodyLayout.Standard,
                hasBody = true,
                isAtTitleEnd = true,
            ),
        )
    }

    @Test
    fun bodyExitKeepsLayoutSpecificPreviousAndLeftBehavior() {
        assertEquals(
            CalloutBodyAction.FocusTitleEnd,
            CalloutBodyPolicy.exit(CalloutBodyLayout.Standard, CalloutBodyBoundary.Previous),
        )
        assertEquals(
            CalloutBodyAction.Ignore,
            CalloutBodyPolicy.exit(CalloutBodyLayout.Standard, CalloutBodyBoundary.Left),
        )
        assertEquals(
            CalloutBodyAction.MovePrevious,
            CalloutBodyPolicy.exit(CalloutBodyLayout.Dialogue, CalloutBodyBoundary.Previous),
        )
        assertEquals(
            CalloutBodyAction.FocusTitleEnd,
            CalloutBodyPolicy.exit(CalloutBodyLayout.Dialogue, CalloutBodyBoundary.Left),
        )
        assertEquals(
            CalloutBodyAction.MoveNext,
            CalloutBodyPolicy.exit(CalloutBodyLayout.Standard, CalloutBodyBoundary.Next),
        )
        assertEquals(
            CalloutBodyAction.MoveNext,
            CalloutBodyPolicy.exit(CalloutBodyLayout.Dialogue, CalloutBodyBoundary.Next),
        )
    }

    @Test
    fun bottomEntryPrefersNestedThenFirstOrLastBody() {
        assertEquals(
            CalloutBottomEntryTarget.Title,
            CalloutBodyPolicy.bottomEntryTarget(bodyBlockCount = 0, hasNestedBottom = false),
        )
        assertEquals(
            CalloutBottomEntryTarget.FirstBodyBlock,
            CalloutBodyPolicy.bottomEntryTarget(bodyBlockCount = 1, hasNestedBottom = false),
        )
        assertEquals(
            CalloutBottomEntryTarget.LastBodyBlock,
            CalloutBodyPolicy.bottomEntryTarget(bodyBlockCount = 2, hasNestedBottom = false),
        )
        assertEquals(
            CalloutBottomEntryTarget.NestedBottom,
            CalloutBodyPolicy.bottomEntryTarget(bodyBlockCount = 2, hasNestedBottom = true),
        )
        assertEquals(
            CalloutBottomEntryTarget.NestedBottom,
            CalloutBodyPolicy.bottomEntryTarget(bodyBlockCount = 1, hasNestedBottom = true),
        )
    }
}
