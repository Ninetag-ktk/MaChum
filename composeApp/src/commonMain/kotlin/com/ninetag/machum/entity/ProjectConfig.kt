package com.ninetag.machum.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 프로젝트별 설정 (`.machum.json`, 프로젝트 디렉토리 직속).
 *
 * 폴더-존 모델 (docs/product-roadmap.md):
 * - `folders`: 폴더 경로(프로젝트 디렉토리 기준 상대) → 폴더별 동작 선언. 키 `""` = 프로젝트 디렉토리(base) = 원고 스코프.
 *   미지정 폴더는 [FolderConfig] 기본값(`default`, Plot 꺼짐, autoTags 없음)으로 간주.
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

/** 프로젝트 디렉토리 자체를 가리키는 [ProjectConfig.folders] 키. */
const val BASE_FOLDER_PATH = ""

/** 제품 정책상 프로젝트 디렉토리(base)의 초기 폴더 설정. */
val DEFAULT_BASE_FOLDER_CONFIG = FolderConfig(type = FolderType.DEFAULT)

/** 새 프로젝트에 순서대로 생성하는 기본 작업 영역. */
val DEFAULT_PROJECT_FOLDERS = listOf(
    ProjectFolderTemplate(
        name = "1. Concept",
        config = FolderConfig(type = FolderType.DEFAULT, autoTags = listOf("구상")),
    ),
    ProjectFolderTemplate(
        name = "2. Outline",
        config = FolderConfig(type = FolderType.DEFAULT, autoTags = listOf("설계")),
    ),
    ProjectFolderTemplate(
        name = "3. Character",
        config = FolderConfig(type = FolderType.GENERAL, autoTags = listOf("캐릭터")),
    ),
    ProjectFolderTemplate(
        name = "4. Scene",
        config = FolderConfig(
            type = FolderType.DEFAULT,
            plotEnabled = true,
            autoTags = listOf("장면구상"),
        ),
    ),
)

data class ProjectFolderTemplate(
    val name: String,
    val config: FolderConfig,
)

/** 새 프로젝트의 `.machum.json`에 기록할 기본 설정. */
fun defaultProjectConfig(): ProjectConfig = ProjectConfig(
    folders = buildMap {
        put(BASE_FOLDER_PATH, DEFAULT_BASE_FOLDER_CONFIG)
        DEFAULT_PROJECT_FOLDERS.forEach { folder -> put(folder.name, folder.config) }
    },
)

/**
 * base 폴더 설정이 없는 구 설정/빈 설정에 제품 기본값을 보완한다.
 * 사용자가 이미 지정한 base 설정은 그대로 보존한다.
 */
fun ProjectConfig.withDefaultBaseFolder(): ProjectConfig = copy(
    folders = buildMap {
        put(BASE_FOLDER_PATH, DEFAULT_BASE_FOLDER_CONFIG)
        folders.forEach { (path, config) -> put(path, config.normalized()) }
    },
)

/** base 자동 태그와 대상 폴더 자동 태그를 합친 실제 관리 태그 목록. */
fun ProjectConfig.effectiveAutoTags(relativePath: String): List<String> {
    val baseTags = folders[BASE_FOLDER_PATH]?.autoTags.orEmpty()
    if (relativePath == BASE_FOLDER_PATH) return normalizeTags(baseTags)
    return normalizeTags(baseTags + folders[relativePath]?.autoTags.orEmpty())
}

/**
 * 폴더별 동작 선언 (docs/product-roadmap.md).
 * - [FolderConfig.type]: 파일 생성·정렬 방식을 결정하는 폴더 유형.
 * - [FolderConfig.autoTags]: 이 폴더 파일에 additive 병합되는 관리 태그.
 */
@Serializable(with = FolderConfigSerializer::class)
data class FolderConfig(
    val type: FolderType = FolderType.DEFAULT,
    val plotEnabled: Boolean = false,
    val autoTags: List<String> = emptyList(),
) {
    val isPlot: Boolean
        get() = type == FolderType.DEFAULT && plotEnabled

    fun normalized(): FolderConfig = copy(
        plotEnabled = isPlot,
        autoTags = normalizeTags(autoTags),
    )
}

/**
 * 폴더 유형 (docs/product-roadmap.md). JSON 직렬화 값은 소문자(`default`/`general`).
 * - GENERAL: 파일명을 그대로 사용하고 이름순으로 정렬하는 자유 형식.
 * - DEFAULT: 숫자 접두사를 자동 부여하고 숫자순으로 정렬하는 제품 기본값.
 */
@Serializable
enum class FolderType {
    @SerialName("default") DEFAULT,
    @SerialName("general") GENERAL,
}

/** `numbered`/`plot` 구 설정을 새 Default + Plot 옵션 구조로 읽기 위한 호환 직렬화기. */
object FolderConfigSerializer : KSerializer<FolderConfig> {
    override val descriptor: SerialDescriptor = FolderConfigSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FolderConfig) {
        val normalized = value.normalized()
        val surrogate = FolderConfigSurrogate(
            type = when (normalized.type) {
                FolderType.DEFAULT -> "default"
                FolderType.GENERAL -> "general"
            },
            plotEnabled = normalized.isPlot,
            autoTags = normalized.autoTags,
        )
        encoder.encodeSerializableValue(FolderConfigSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): FolderConfig {
        val surrogate = decoder.decodeSerializableValue(FolderConfigSurrogate.serializer())
        return when (surrogate.type.lowercase()) {
            "default" -> FolderConfig(FolderType.DEFAULT, surrogate.plotEnabled, surrogate.autoTags)
            "general" -> FolderConfig(FolderType.GENERAL, false, surrogate.autoTags)
            "numbered" -> FolderConfig(FolderType.DEFAULT, false, surrogate.autoTags)
            "plot" -> FolderConfig(FolderType.DEFAULT, true, surrogate.autoTags)
            else -> throw SerializationException("Unknown folder type: ${surrogate.type}")
        }
    }
}

@Serializable
private data class FolderConfigSurrogate(
    val type: String = "default",
    val plotEnabled: Boolean = false,
    val autoTags: List<String> = emptyList(),
)
