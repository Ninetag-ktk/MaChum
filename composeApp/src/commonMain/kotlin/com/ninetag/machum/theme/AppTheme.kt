package com.ninetag.machum.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class ContrastLevel {
    Standard,
    Medium,
    High,
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    contrastLevel: ContrastLevel = ContrastLevel.Standard,
    content: @Composable () -> Unit
) {
    val colorScheme = when (contrastLevel) {
        ContrastLevel.Standard -> if (darkTheme) darkScheme else lightScheme
        ContrastLevel.Medium -> if (darkTheme) mediumContrastDarkColorScheme else mediumContrastLightColorScheme
        ContrastLevel.High -> if (darkTheme) highContrastDarkColorScheme else highContrastLightColorScheme
    }
    val semanticColors = if (darkTheme) darkSemanticColors else lightSemanticColors

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography().withFontFamily(customFontFamily()),
            content = content
        )
    }
}
