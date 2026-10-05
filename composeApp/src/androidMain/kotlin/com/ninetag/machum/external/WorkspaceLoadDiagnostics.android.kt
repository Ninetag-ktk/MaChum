package com.ninetag.machum.external

import android.content.Context
import android.content.pm.ApplicationInfo
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private object WorkspaceDiagnosticsContext : KoinComponent {
    val context: Context by inject()
}

internal actual fun workspaceLoadDiagnosticsEnabled(): Boolean = runCatching {
    WorkspaceDiagnosticsContext.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}.getOrDefault(false)
