package com.ninetag.machum.screen.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.paddingDefault(): Modifier = this.windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top)).padding( horizontal = 16.dp )

@Composable
fun Modifier.paddingDefaultTop(): Modifier = this.windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))

@Composable
fun Modifier.paddingDefaultVertical(): Modifier = this.windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Vertical))