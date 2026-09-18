package com.ninetag.machum.external

import kotlin.time.TimeSource
import kotlin.time.TimeMark

/**
 * Low-volume workspace loading checkpoints for device diagnosis.
 *
 * Keep document bodies and file names out of these messages. The stable `MaChumLoad` prefix lets
 * Logcat separate application progress from the platform's per-frame `View` messages.
 */
internal object WorkspaceLoadDiagnostics {
    fun event(stage: String, detail: String = "") {
        println(message("EVENT", stage, detail))
    }

    fun begin(stage: String, detail: String = ""): WorkspaceLoadTrace {
        println(message("START", stage, detail))
        return WorkspaceLoadTrace(stage, TimeSource.Monotonic.markNow())
    }

    private fun message(status: String, stage: String, detail: String): String =
        buildString {
            append("MaChumLoad|")
            append(status)
            append('|')
            append(stage)
            if (detail.isNotBlank()) {
                append('|')
                append(detail.replace('\n', ' '))
            }
        }
}

internal class WorkspaceLoadTrace(
    private val stage: String,
    private val startedAt: TimeMark,
) {
    fun complete(detail: String = "") {
        WorkspaceLoadDiagnostics.event(
            stage = "$stage.complete",
            detail = elapsedDetail(detail),
        )
    }

    fun fail(error: Throwable) {
        WorkspaceLoadDiagnostics.event(
            stage = "$stage.failed",
            detail = elapsedDetail("error=${error::class.simpleName}:${error.message.orEmpty()}"),
        )
    }

    private fun elapsedDetail(detail: String): String = buildString {
        append("elapsedMs=")
        append(startedAt.elapsedNow().inWholeMilliseconds)
        if (detail.isNotBlank()) {
            append('|')
            append(detail)
        }
    }
}
