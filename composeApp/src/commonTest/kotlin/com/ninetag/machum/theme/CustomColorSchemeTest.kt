package com.ninetag.machum.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class CustomColorSchemeTest {
    @Test
    fun fixedRolesUseTheCustomPaletteInEveryScheme() {
        val schemes = listOf(
            lightScheme,
            darkScheme,
            mediumContrastLightColorScheme,
            mediumContrastDarkColorScheme,
            highContrastLightColorScheme,
            highContrastDarkColorScheme,
        )

        schemes.forEach { scheme ->
            assertEquals(primaryContainerLight, scheme.primaryFixed)
            assertEquals(primaryDark, scheme.primaryFixedDim)
            assertEquals(onPrimaryDark, scheme.onPrimaryFixed)
            assertEquals(primaryContainerDark, scheme.onPrimaryFixedVariant)
            assertEquals(secondaryContainerLight, scheme.secondaryFixed)
            assertEquals(secondaryDark, scheme.secondaryFixedDim)
            assertEquals(tertiaryContainerLight, scheme.tertiaryFixed)
            assertEquals(tertiaryDark, scheme.tertiaryFixedDim)
        }
    }
}
