package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.external.isValidProjectFileTitle

internal fun projectFileTitleError(title: String): String? = when {
    title.isBlank() -> "파일 제목을 입력해 주세요."
    title != title.trim() -> "제목 앞뒤의 공백을 제거해 주세요."
    title.endsWith(".md", ignoreCase = true) -> "확장자 .md는 입력하지 않아도 됩니다."
    !isValidProjectFileTitle(title) -> "파일 제목으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다."
    else -> null
}
