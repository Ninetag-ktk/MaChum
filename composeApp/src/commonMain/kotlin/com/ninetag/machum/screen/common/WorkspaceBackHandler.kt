package com.ninetag.machum.screen.common

import androidx.compose.runtime.Composable

/** Android 시스템 뒤로 처리. Desktop Esc는 해당 화면의 키 이벤트가 처리한다. */
@Composable
internal expect fun WorkspaceBackHandler(enabled: Boolean, onBack: () -> Unit)
