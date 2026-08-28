package com.ninetag.machum.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 프로젝트별 설정 (`.machum.json`, 프로젝트 디렉토리 직속).
 *
 * 폴더-존 모델 (docs/folder-zone-model.md §3.3):
 * - `folders`: 폴더 경로(프로젝트 디렉토리 기준 상대) → 폴더별 동작 선언. 키 `""` = 프로젝트 디렉토리(base) = 원고 스코프.
 *   미지정 폴더는 [FolderConfig] 기본값(`general`, autoTags 없음)으로 간주.
 * - `fileIds`: 커밋 정체성용 파일 ID 맵 (rename/이동 추적). 향후 커밋 기능에서 사용.
 *
 * 구 스키마의 `workflow`/`workflowLastModified` 필드는 제거됨(workflow 은퇴, §1.2, §7).
 * 기존 `.machum.json` 에 남아있는 해당 키는 읽을 때 무시된다(FileManager 의 `ignoreUnknownKeys`).
 */
@Serializable
data class ProjectConfig(
    val folders: Map<String, FolderConfig> = emptyMap(),
    val fileIds: Map<String, String> = emptyMap(),
)

/**
 * 폴더별 동작 선언 (docs/folder-zone-model.md §3.2, §4.1).
 * - [type]: 폴더 유형 (능력 중첩 general ⊂ plot ⊂ numbered).
 * - [autoTags]: 이 폴더 파일에 additive 병합되는 관리 태그.
 */
@Serializable
data class FolderConfig(
    val type: FolderType = FolderType.GENERAL,
    val autoTags: List<String> = emptyList(),
)

/**
 * 폴더 유형 (docs/folder-zone-model.md §3.2). JSON 직렬화 값은 소문자(`general`/`plot`/`numbered`).
 * - GENERAL: 넘버링·plot 없음 (일반 노트/워크시트)
 * - PLOT: plot 필드 (서사적 위치를 갖는 리소스, 예: Scene)
 * - NUMBERED: plot + 자동 넘버링 (원고 시퀀스, = 프로젝트 디렉토리 base 기본값)
 */
@Serializable
enum class FolderType {
    @SerialName("general") GENERAL,
    @SerialName("plot") PLOT,
    @SerialName("numbered") NUMBERED,
}
