package com.ninetag.machum.markdown.state

internal enum class CalloutBodyLayout {
    Standard,
    Dialogue,
}

internal enum class CalloutBodyBoundary {
    Previous,
    Next,
    Left,
}

internal enum class CalloutBodyAction {
    CreateBody,
    FocusBodyStart,
    FocusTitleEnd,
    MovePrevious,
    MoveNext,
    Ignore,
}

internal enum class CalloutBottomEntryTarget {
    Title,
    FirstBodyBlock,
    LastBodyBlock,
    NestedBottom,
}

/**
 * Standard·DL Callout이 공유하는 body 생성·진입·탈출 결정.
 *
 * 실제 FocusRequester 실행과 block 변경은 Compose 계층에 남기고, 입력 조건에 따른 목표만 계산한다.
 */
internal object CalloutBodyPolicy {

    fun activate(hasBody: Boolean): CalloutBodyAction =
        if (hasBody) CalloutBodyAction.FocusBodyStart else CalloutBodyAction.CreateBody

    fun titleDown(layout: CalloutBodyLayout, hasBody: Boolean): CalloutBodyAction = when (layout) {
        CalloutBodyLayout.Standard -> if (hasBody) {
            CalloutBodyAction.FocusBodyStart
        } else {
            CalloutBodyAction.MoveNext
        }
        CalloutBodyLayout.Dialogue -> CalloutBodyAction.MoveNext
    }

    fun titleRight(
        layout: CalloutBodyLayout,
        hasBody: Boolean,
        isAtTitleEnd: Boolean,
    ): CalloutBodyAction = when {
        layout != CalloutBodyLayout.Dialogue -> CalloutBodyAction.Ignore
        hasBody && isAtTitleEnd -> CalloutBodyAction.FocusBodyStart
        else -> CalloutBodyAction.Ignore
    }

    fun exit(layout: CalloutBodyLayout, boundary: CalloutBodyBoundary): CalloutBodyAction =
        when (layout) {
            CalloutBodyLayout.Standard -> when (boundary) {
                CalloutBodyBoundary.Previous -> CalloutBodyAction.FocusTitleEnd
                CalloutBodyBoundary.Next -> CalloutBodyAction.MoveNext
                CalloutBodyBoundary.Left -> CalloutBodyAction.Ignore
            }
            CalloutBodyLayout.Dialogue -> when (boundary) {
                CalloutBodyBoundary.Previous -> CalloutBodyAction.MovePrevious
                CalloutBodyBoundary.Next -> CalloutBodyAction.MoveNext
                CalloutBodyBoundary.Left -> CalloutBodyAction.FocusTitleEnd
            }
        }

    fun bottomEntryTarget(
        bodyBlockCount: Int,
        hasNestedBottom: Boolean,
    ): CalloutBottomEntryTarget = when {
        bodyBlockCount == 0 -> CalloutBottomEntryTarget.Title
        hasNestedBottom -> CalloutBottomEntryTarget.NestedBottom
        bodyBlockCount == 1 -> CalloutBottomEntryTarget.FirstBodyBlock
        else -> CalloutBottomEntryTarget.LastBodyBlock
    }
}
