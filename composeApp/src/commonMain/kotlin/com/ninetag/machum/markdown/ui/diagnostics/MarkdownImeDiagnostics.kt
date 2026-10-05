package com.ninetag.machum.markdown.ui.diagnostics

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import com.ninetag.machum.markdown.service.MarkdownEditorDebugOptions
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

internal expect fun writeMarkdownImeDiagnostic(line: String)

/** Callers pass fixed event names, identities and ranges only, never document text or paths. */
@OptIn(ExperimentalAtomicApi::class)
internal object MarkdownImeDiagnostics {
    private val sequence = AtomicLong(0L)
    fun log(detail: String) {
        if (!MarkdownEditorDebugOptions.logImeDiagnostics) return
        writeMarkdownImeDiagnostic("MaChumIme|seq=${sequence.addAndFetch(1L)}|time=${Clock.System.now().toEpochMilliseconds()}|$detail")
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MonitorMarkdownImeInput(state: TextFieldState, content: @Composable () -> Unit) {
    if (MarkdownEditorDebugOptions.logImeDiagnostics) {
        val interceptor = remember(state) {
            PlatformTextInputInterceptor { request, next ->
                fun event(phase: String) = MarkdownImeDiagnostics.log(
                    "session|phase=$phase|request=${request.hashCode()}|state=${state.hashCode()}|selection=${state.selection}|composition=${state.composition}",
                )
                event("start")
                try { next.startInputMethod(request) } finally { event("end") }
            }
        }
        InterceptPlatformTextInput(interceptor, content)
    } else content()
}
