package com.ninetag.machum.screen.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints

@Composable
internal actual fun PolicyVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    if (state.maxValue > 0) {
        Box(modifier) {
            PolicyScrollbarIntrinsicZeroLayout(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            ) {
                VerticalScrollbar(adapter = rememberScrollbarAdapter(state))
            }
        }
    }
}

@Composable
internal actual fun PolicyLazyVerticalScrollbar(state: LazyListState, modifier: Modifier) {
    if (state.canScrollForward || state.canScrollBackward) {
        Box(modifier) {
            PolicyScrollbarIntrinsicZeroLayout(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            ) {
                VerticalScrollbar(adapter = rememberScrollbarAdapter(state))
            }
        }
    }
}

@Composable
private fun PolicyScrollbarIntrinsicZeroLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = content,
        measurePolicy = PolicyScrollbarIntrinsicZeroMeasurePolicy,
    )
}

private object PolicyScrollbarIntrinsicZeroMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurables.firstOrNull()?.measure(constraints) ?: return layout(0, 0) {}
        return layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = 0

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = 0
}
