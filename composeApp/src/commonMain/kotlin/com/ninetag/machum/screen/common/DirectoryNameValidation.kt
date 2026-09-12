package com.ninetag.machum.screen.common

import com.ninetag.machum.external.isValidProjectFolderName

/**
 * 디렉터리 이름 입력의 오류 문구만 계산한다. 빈 입력은 오류를 숨기므로 호출자는 제출 시 비어 있지 않은지도 확인한다.
 * 이름 그대로의 설정 저장, 이름 변경 필요 여부와 처리 중 상태는 각 화면에서 판단한다.
 * Unicode 변환으로 대소문자 변경과 중복이 겹치면 대소문자 변경 오류를 먼저 표시한다.
 */
internal fun directoryNameError(
    name: String,
    existingNames: Set<String> = emptySet(),
    currentName: String? = null,
    invalidNameMessage: String = "폴더 이름으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다.",
    duplicateNameMessage: String = "같은 이름의 디렉터리가 이미 있습니다.",
    caseOnlyRenameMessage: String = "대소문자만 바꾸는 이름 변경은 지원하지 않습니다.",
): String? {
    if (name.isBlank()) return null
    if (name != name.trim()) return "이름 앞뒤의 공백을 제거해 주세요."
    if (!isValidProjectFolderName(name)) return invalidNameMessage
    if (currentName != null && name != currentName && name.equals(currentName, ignoreCase = true)) {
        return caseOnlyRenameMessage
    }
    val normalizedName = name.lowercase()
    if (existingNames.any { existing ->
            !existing.equals(currentName, ignoreCase = true) && existing.lowercase() == normalizedName
        }
    ) {
        return duplicateNameMessage
    }
    return null
}
