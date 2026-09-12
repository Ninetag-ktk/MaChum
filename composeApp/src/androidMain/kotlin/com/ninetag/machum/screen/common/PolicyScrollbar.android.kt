package com.ninetag.machum.screen.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PolicyVerticalScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
internal actual fun PolicyLazyVerticalScrollbar(state: LazyListState, modifier: Modifier) = Unit
