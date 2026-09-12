package com.ninetag.machum.screen.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop scrollbar affordances. Touch platforms keep the native gesture interaction. */
@Composable
internal expect fun PolicyVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier)

@Composable
internal expect fun PolicyLazyVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier)
