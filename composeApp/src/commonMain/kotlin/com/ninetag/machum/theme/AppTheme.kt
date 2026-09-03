package com.ninetag.machum.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

enum class ContrastLevel {
    Standard,
    Medium,
    High,
}

private val workspaceShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

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
            shapes = workspaceShapes,
            typography = Typography().withFontFamily(customFontFamily()),
            content = content
        )
    }
}
