package com.ninetag.machum.screen.mainComposition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectFileTitlePolicyTest {

    @Test
    fun creationAndRenamePolicyRejectsWhitespaceExtensionAndReservedName() {
        assertEquals("파일 제목을 입력해 주세요.", projectFileTitleError(" "))
        assertEquals("제목 앞뒤의 공백을 제거해 주세요.", projectFileTitleError(" 제목"))
        assertEquals("확장자 .md는 입력하지 않아도 됩니다.", projectFileTitleError("제목.md"))
        assertEquals(
            "파일 제목으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다.",
            projectFileTitleError("CON"),
        )
    }

    @Test
    fun validTitleHasNoError() {
        assertNull(projectFileTitleError("1. 발단"))
    }
}
