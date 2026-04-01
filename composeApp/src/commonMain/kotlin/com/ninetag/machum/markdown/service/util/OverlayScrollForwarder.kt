package com.ninetag.machum.markdown.service.util

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 오버레이 위에서 발생한 스크롤 제스처를 부모 [ScrollState]에 포워딩하는 Modifier.
 *
 * [scrollable]은 스크롤/플링 제스처만 가로채고 탭은 통과시키므로,
 * 오버레이 내부 BasicTextField의 포커스/커서 배치는 정상 동작한다.
 */
@Composable
internal fun overlayScrollForwarder(parentScrollState: ScrollState): Modifier {
    val forwardingState = rememberScrollableState { delta ->
        parentScrollState.dispatchRawDelta(-delta)
        delta
    }
    return Modifier.scrollable(
        state = forwardingState,
        orientation = Orientation.Vertical,
    )
}
