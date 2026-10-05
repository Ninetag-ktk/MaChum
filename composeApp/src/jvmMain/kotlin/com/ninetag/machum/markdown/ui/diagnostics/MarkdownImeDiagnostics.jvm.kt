package com.ninetag.machum.markdown.ui.diagnostics

import java.io.File

private val imeLogFile by lazy {
    System.getProperty("machum.imeLogFile")?.takeIf { it.isNotBlank() }?.let(::File)
}

internal actual fun writeMarkdownImeDiagnostic(line: String) {
    println(line)
    imeLogFile?.let { file ->
        // An optional diagnostic sink must never interrupt the editor's input session.
        runCatching { file.appendText("$line\n", Charsets.UTF_8) }
    }
}
