package com.ninetag.machum.markdown.renderer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class CalloutStyle(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector,
)

val LocalCalloutStyles = compositionLocalOf<Map<String, CalloutStyle>> { emptyMap() }

@Composable
fun calloutStyleFor(type: String): CalloutStyle {
    val custom = LocalCalloutStyles.current[type.uppercase()]
    if (custom != null) return custom

    val scheme = MaterialTheme.colorScheme
    return when (type.uppercase()) {
        "NOTE"      -> CalloutStyle(scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Default.Info)
        "TIP"       -> CalloutStyle(scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Default.Lightbulb)
        "IMPORTANT" -> CalloutStyle(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Default.Star)
        "WARNING"   -> CalloutStyle(Color(0xFFFFF3E0), Color(0xFF6D4C41), Icons.Default.Warning)
        "DANGER",
        "CAUTION"   -> CalloutStyle(scheme.errorContainer, scheme.onErrorContainer, Icons.Default.Error)
        "QUESTION"  -> CalloutStyle(scheme.surfaceVariant, scheme.onSurfaceVariant, Icons.AutoMirrored.Filled.HelpOutline)
        "SUCCESS"   -> CalloutStyle(Color(0xFFE8F5E9), Color(0xFF1B5E20), Icons.Default.CheckCircle)
        else        -> CalloutStyle(scheme.surfaceVariant, scheme.onSurfaceVariant, Icons.AutoMirrored.Filled.Notes)
    }
}
