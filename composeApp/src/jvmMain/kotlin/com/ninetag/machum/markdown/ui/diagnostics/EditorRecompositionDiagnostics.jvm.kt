package com.ninetag.machum.markdown.ui.diagnostics

internal actual fun isEditorRecompositionDiagnosticsEnabled(): Boolean =
    java.lang.Boolean.getBoolean("machum.editor.recomposition.metrics") ||
        System.getenv("MACHUM_EDITOR_RECOMPOSITION_METRICS").equals("true", ignoreCase = true)
