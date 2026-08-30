package com.ninetag.machum.external

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectFileNameValidationTest {

    @Test
    fun acceptsPlainFileTitles() {
        assertTrue(isValidProjectFileTitle("도입부"))
        assertTrue(isValidProjectFileTitle("1화 초고"))
    }

    @Test
    fun rejectsExtensionPathsWhitespaceAndReservedNames() {
        assertFalse(isValidProjectFileTitle("도입부.md"))
        assertFalse(isValidProjectFileTitle("../도입부"))
        assertFalse(isValidProjectFileTitle("Scene/도입부"))
        assertFalse(isValidProjectFileTitle(" 도입부"))
        assertFalse(isValidProjectFileTitle("도입부 "))
        assertFalse(isValidProjectFileTitle("CON"))
    }
}
