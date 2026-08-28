# MaChum 현재 아키텍처

> 역할: 현재 코드가 실제로 어떻게 동작하는지 설명하는 source of truth  
> 마지막 검토: 2026-08-28  
> 제품 방향과 우선순위: [product-roadmap.md](product-roadmap.md)  
> 마크다운 에디터 내부 설계: [markdown-editor.md](markdown-editor.md)

---

## 1. 프로젝트 개요

MaChum(맞춤)은 Android와 Desktop(JVM)을 대상으로 하는 Compose Multiplatform 마크다운 집필 앱이다. Obsidian과 같은 vault를 공유하면서 원고 편집과 장기적인 버전 추적을 제공하는 것이 목표다.

현재 제품 흐름은 사용할 vault와 project를 고른 뒤 프로젝트 루트의 마크다운 파일을 좌우로 넘기며 편집하는 구조다. workflow 템플릿 네비게이션은 은퇴했고 관련 코드는 dormant 상태로만 남아 있다.

기술 버전의 authority는 `gradle/libs.versions.toml`이다. 문서에는 Kotlin, Compose, Android SDK 버전을 중복 기록하지 않는다.

---

## 2. 모듈 구조

```text
MaChum/
├── composeApp/   공통 Kotlin Multiplatform UI와 비즈니스 로직
├── androidApp/   Android application 진입점
├── desktopApp/   JVM Desktop application 진입점
└── docs/         제품·아키텍처·에디터 설계 문서
```

### `composeApp`

```text
com.ninetag.machum/
├── App.kt                 앱 상태 기반 화면 전환
├── di/                    Koin 공통 모듈
├── entity/                ProjectConfig, workflow 관련 모델
├── external/              파일 시스템, NoteFile, WorkflowParser
├── markdown/              블록 기반 마크다운 에디터
├── screen/                vault/project/main/editor 화면
└── theme/                 Material 테마와 색상·타이포그래피
```

### 플랫폼 진입점

- Android: `androidApp/.../MainActivity.kt`
  - Koin과 FileKit 초기화
  - `App()`을 Android Activity에 표시
- Desktop: `desktopApp/.../main.kt`
  - Koin과 FileKit 초기화
  - vault 미선택 시 전용 picker window
  - vault 선택 후 메인 `App()` window

---

## 3. 앱 화면 흐름

`App.kt`는 navigation library 없이 `FileManager.bookmarks`를 기준으로 화면을 선택한다.

```text
vaultData 없음
  → VaultSelectionScreen

projectData 없음
  → ProjectSelectionScreen

fileData 없음
  → FileManager.setFile(project)

모두 존재
  → MainScreen
```

현재 라이브 흐름에는 WorkflowScreen이나 WorkflowSelectionScreen이 없다.

`MainScreen`은 다음 역할을 가진다.

- `MainViewModel.fileList`를 `HorizontalPager` 페이지로 표시
- 페이지 정지 시 선택 파일을 bookmark에 반영
- top bar에서 파일명 변경
- `LocalWindowInfo.isWindowFocused`를 ViewModel에 전달해 외부 변경 폴링 활성화

상단의 commit, 파일 목록, index 버튼은 아직 동작하지 않는다.

---

## 4. 의존성 주입과 상태

Koin 공통 모듈은 다음 객체를 제공한다.

- `DataStore<Preferences>` singleton
- `FileManager` singleton
- `MainViewModel`

앱의 지속 bookmark는 DataStore에 저장한다.

```text
BOOKMARK_VAULT    PlatformFile bookmark bytes
BOOKMARK_PROJECT  PlatformFile bookmark bytes
BOOKMARK_FILE     파일명 문자열
```

UI와 비즈니스 상태는 주로 `StateFlow`를 사용한다. Compose에서는 `collectAsState()`로 구독한다.

---

## 5. 파일 시스템 계층

### 5.1 `FileManager`

`external/FileManager.kt`가 파일 작업의 공통 진입점이다.

주요 책임:

- vault/project/file bookmark 저장과 복원
- project와 `.md` 파일 목록 조회
- 파일 생성·읽기·쓰기·삭제·rename
- `.machum.json` 생성·직렬화
- `NoteFile` 읽기와 ID 보장
- 파일 mtime 조회
- 은퇴한 workflow API의 dormant 유지

### 5.2 expect/actual 경계

공통 선언과 플랫폼 actual을 사용한다.

| 기능 | Android | Desktop |
|---|---|---|
| 파일·폴더 생성 | SAF `DocumentFile` | FileKit/JVM File |
| rename | `DocumentFile.renameTo` | `java.io.File.renameTo` |
| 권한 확인 | persisted URI permission | 항상 true |
| mtime | `COLUMN_LAST_MODIFIED` | `File.lastModified()` |
| clipboard entry | Android `ClipData` | AWT `StringSelection` |
| vault picker UI | Android picker | Desktop 별도 window |

Android SAF의 mtime은 provider나 클라우드 동기화 앱에 따라 갱신 시점이 달라질 수 있다.

### 5.3 현재 파일 목록의 한계

현재 `listFile(project)`는 전달받은 디렉터리의 직속 `.md`만 조회하고 이름 문자열순으로 정렬한다.

- 하위 폴더를 발견하지 않음
- numbered 파일을 숫자로 정렬하지 않음
- 파일 캐시 key가 파일명이므로 다른 폴더의 동명 파일을 구분하지 못함

폴더-존 도입 시 상대 경로 또는 안정 ID 기반 key로 변경해야 한다.

---

## 6. 프로젝트 설정

프로젝트 디렉터리 직속 `.machum.json`은 `ProjectConfig`로 직렬화한다.

```kotlin
ProjectConfig(
    folders: Map<String, FolderConfig>,
    fileIds: Map<String, String>,
)
```

`FolderConfig`는 `type`과 `autoTags`를 가진다. 구 workflow 필드는 제거됐으며 `ignoreUnknownKeys=true`로 기존 설정을 읽는다.

현재 구현 경계:

- 모델과 JSON round-trip 테스트: 검증 완료
- `.machum.json` 생성·로드·쓰기: 구현됨
- 현재 프로젝트 설정을 `StateFlow<ProjectConfig?>`로 노출: 구현됨
- 프로젝트 선택·복원·전환 시 설정 상태 교체: 구현됨
- 빈 설정 및 구 설정의 base(`""`) 기본값(`numbered`) 보완·저장: 검증 완료
- 설정 전체 변경과 폴더별 설정 변경 API: 구현됨
- 폴더 탐색·정렬·태그 동기화에 적용: 미구현

설정 상태의 `null`은 프로젝트가 선택되지 않았거나 설정을 아직 로드하지 못한 상태를 뜻한다.
프로젝트가 바뀌면 이전 설정 상태를 먼저 비우며, 새 프로젝트의 설정을 읽은 뒤에만 새 상태를 공개한다.
현재는 라이브 상태까지 연결됐고, 다음 단계에서 상대 경로 기반 파일·폴더 상태가 이 설정을 소비한다.

---

## 7. 마크다운 파일과 frontmatter

`NoteFile`은 YAML frontmatter와 본문을 분리한다.

관리 키:

- `id`: 파일 정체성과 향후 커밋 추적
- `tags`: 관리 태그와 수동 태그
- `aliases`: 파일 별칭
- `plot`: 서사 단계

정책:

1. 관리 키만 구조적으로 읽고 쓴다.
2. 미관리 키, 주석, 키 순서와 원래 포맷은 그대로 보존한다.
3. 관리 키도 실제 수정될 때만 표준 형태로 정규화한다.
4. YAML 라이브러리를 사용하지 않아 Obsidian 문서의 불필요한 전체 재포맷을 방지한다.
5. frontmatter가 없는 파일은 처음 읽을 때 `id`를 생성해 파일에 기록한다.

`NoteFileTest`는 관리 키 읽기·쓰기, 미지 키 보존, 안정적인 round-trip을 검증한다.

---

## 8. 편집과 저장 데이터 흐름

```text
PlatformFile
  → FileManager.readMarkdown()
  → NoteFile(frontmatter + body)
  → MainViewModel.noteFileCache
  → EditorPage
  → MarkdownBlockTextFieldM3(value = noteFile.body)
  → List<EditorBlock>
  → 사용자 편집
  → blocks.toMarkdown()
  → MainViewModel.updateBody()
  → DebouncedSaveCoordinator (file key별 500ms)
  → NoteFile.withBody()
  → FileManager.writeMarkdown()
```

저장 debounce의 소유자는 ViewModel 계층의 `DebouncedSaveCoordinator`다. 실효 저장 지연은 500ms이며,
같은 파일의 새 요청만 이전 요청을 취소한다. 다른 파일의 pending save는 서로 취소하지 않는다.
외부 mtime 변경·삭제·rename 시에는 해당 파일의 stale pending save를 취소한다.

---

## 9. 외부 변경 감지

파일은 Obsidian이나 클라우드 동기화 앱에서도 변경될 수 있다. 현재 정책은 **외부 우선(external wins)**이다.

### 활성 조건

`MainScreen`이 창 포커스를 `MainViewModel.setActive()`로 전달한다.

- 포커스 획득: 즉시 검사
- 포커스 유지: 1.5초 주기 폴링
- 포커스 상실: `collectLatest`가 폴링 루프 취소

### 검사 순서

1. 프로젝트의 파일 목록 재조회
2. 현재 파일명을 기준으로 페이지 인덱스 보존
3. 캐시된 파일의 mtime 비교
4. 앱 자신의 마지막 쓰기 mtime과 같으면 건너뜀
5. 파일을 다시 읽고 `NoteFile.inject()` 결과를 비교
6. 실제 내용이 다를 때만 cache 교체와 에디터 재파싱

### 허용된 트레이드오프

진짜 외부 변경을 반영하면 에디터 블록 ID, focus, cursor, selection이 초기화될 수 있다. 외부 우선 정책상 현재는 이를 수용한다.

### 검증 필요

- Desktop 수동 검증
- Android SAF provider별 mtime 검증
- 미저장 입력과 외부 변경의 충돌
- rename·삭제와 저장 debounce의 경합

---

## 10. 블록 에디터 경계

마크다운 본문은 `List<EditorBlock>`으로 관리한다.

```text
MarkdownBlockTextField
  ├── 외부 value 동기화
  ├── DocumentSelection 소유
  └── MarkdownBlockEditor
       ├── TextBlockEditor
       ├── CalloutBlockEditor
       ├── CodeBlockEditor
       └── TableBlockEditor
```

각 블록은 독립 `TextFieldState`와 안정 ID를 가진다. `LazyColumn` key는 블록 ID를 사용한다. 자세한 파싱·포커스·selection·dissolve 계약은 [markdown-editor.md](markdown-editor.md)에만 기록한다.

---

## 11. 은퇴한 workflow 코드

workflow 기반 템플릿과 배정 플로우는 제품 네비게이션에서 은퇴했다.

현재 남아 있는 항목:

- `WorkflowParser`
- workflow entity
- `workflowSceen` 패키지의 화면
- `WorkflowSelectionScreen`
- `FileManager`의 workflow StateFlow와 함수
- vault의 `.workflow/` 생성

이 코드는 컴파일만 유지하며 라이브 흐름에서 호출하지 않는다. `workflowSceen` 패키지명에는 `r`이 빠진 기존 오타가 있으므로 관련 코드를 정리하기 전에는 임의 rename하지 않는다.

---

## 12. 빌드와 검증

```bash
# 공통 JVM 컴파일
./gradlew :composeApp:compileKotlinJvm

# JVM 테스트
./gradlew :composeApp:jvmTest

# Desktop 실행
./gradlew :desktopApp:run

# Android APK
./gradlew :androidApp:assembleDebug

# 전체 테스트
./gradlew test
```

현재 자동 테스트는 `NoteFile`, `ProjectConfig`, 블록 파서·직렬화, `BlockOperations`,
`DocumentSelection`, key별 저장 debounce와 Table 비정형 행 정규화를 검증한다.
실제 키보드·마우스·창 포커스·Android SAF 상호작용은 [P0 수동 테스트](p0-manual-test.md)를 따른다.
