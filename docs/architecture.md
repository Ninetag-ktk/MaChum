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

Vault 선택은 Android와 Desktop이 같은 Material 3 콘텐츠를 사용하며 플랫폼 코드는 창 표시만 담당한다.
Project 선택은 현재 Vault 이름, 로딩·오류·빈 상태, 프로젝트 카드 목록과 새 프로젝트 생성 동작을 제공한다.

현재 라이브 흐름에는 WorkflowScreen이나 WorkflowSelectionScreen이 없다.

`MainScreen`은 다음 역할을 가진다.

- `MainViewModel.fileList`를 `HorizontalPager` 페이지로 표시
- 페이지 정지 시 선택 파일을 bookmark에 반영
- top bar에서 파일명 변경
- `LocalWindowInfo.isWindowFocused`를 ViewModel에 전달해 외부 변경 폴링 활성화

상단 파일 목록 버튼은 현재 폴더 파일 전환과 새 파일 생성을 제공한다. TopBar 왼쪽 메뉴 버튼은
Obsidian 스타일의 navigation drawer를 열고 커밋 버튼은 오른쪽에 둔다. 우측 하단 초기화 FAB는 확인 후
DataStore 북마크와 `FileManager`의 메모리 상태만 비우며 Vault의 실제 파일은 삭제하지 않는다.
commit과 index 버튼은 아직 동작하지 않는다.

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

### 5.3 폴더·파일 목록

지원하는 저장 구조는 `Vault → Project → File 또는 Folder → File`로 고정한다.

```text
Vault/
└── Project/
    ├── draft.md
    ├── 1. Concept/
    ├── 2. Outline/
    ├── 3. Character/
    │   └── hero.md
    └── 4. Scene/
        └── opening.md
```

폴더는 별도 도메인 계층이 아니라 현재 파일 목록을 정하는 탐색 범위다. 중첩 폴더와 재귀 파일
평탄화는 지원하지 않으며, 이를 위한 별도 repository·트리 cache·ViewModel도 두지 않는다.

`listFolders(project)`는 base(`""`)와 프로젝트 바로 아래의 비숨김 폴더만 반환한다.
`listProjectFiles(folder)`는 선택한 폴더의 직속 Markdown 파일을 `ProjectFile`로 반환한다.

- 폴더와 파일 key는 `/`로 정규화한 프로젝트 상대 경로
- `.`으로 시작하는 숨김 폴더는 탐색에서 제외
- 폴더 안의 폴더는 목록과 탐색 대상에서 제외
- 서로 다른 폴더의 동명 파일은 서로 다른 `FileKey` 사용
- 선택 파일 bookmark도 파일명이 아닌 상대 경로로 저장·복원

책임 흐름은 `FileManager` 직접 조회 → `MainViewModel`의 현재 폴더·파일 상태 → `MainScreen` 표시로 제한한다.
`MainViewModel`은 `folderList`와 `currentFolder`를 노출하고 현재 폴더의 파일만 pager 목록으로 유지한다.
navigation drawer의 넓은 본문에는 Project 루트를 제외한 현재 Project의 직속 하위 디렉터리만 표시한다. 하단 고정 행의 Project 영역은
Project 선택 dropdown을 열고 설정 아이콘은 현재 Vault 정보와 Vault 다시 선택 메뉴를 연다.
Vault 변경은 플랫폼 디렉터리 선택 화면으로 이동한다.
본문의 `디렉터리 추가` 버튼은 이름, 폴더 유형(`default`/`general`), Default 전용 Plot 옵션과 선택적인 자동 태그를 받는다.
UI 순서는 `Default`, `General`이며 신규 및 미설정 디렉터리의 기본 유형은 `default`다. General은 파일명을
그대로 사용해 이름순으로 정렬하고 General 전환 시 Plot을 해제한다. Default에서 Plot을 켜면 프롤로그부터 에필로그까지 7단계와 단계 내부 순번을 사용한다.
frontmatter는 `plot: 1) 발단`, 첫 발단 파일명은 `1-0. 제목.md` 형식이며 frontmatter 단계가 authority다.
생성 시 실제 직속 디렉터리와 `.machum.json`의 `FolderConfig`를 함께 만들며, 자동 태그는 이후 생성되는 파일의
frontmatter `tags`에 중복 없이 추가한다. 숨김 이름, 경로 구분자, 운영체제 예약어와 기존 이름은 생성 전에 거부한다.
하위 폴더에서는 TopBar에 뒤로가기와 현재 폴더명을 표시하며, 뒤로가기는 프로젝트 루트로 이동한다.
상단 dropdown은 현재 폴더의 파일 전환과 새 파일 생성만 담당한다. 새 파일 다이얼로그는 제목을 필수로 받고 유형별 최종
파일명을 미리 보여주며 금지 문자, `.md` 중복 확장자와 같은 폴더의 중복 이름을 생성 전에 거부한다. Default 폴더는 숫자
접두사로 정렬하고 최대 번호 다음 값을 생성한다. 번호가 하나도 없을 때 일반 Default 디렉터리는 1, Project 루트는 0에서
시작한다. Default + Plot은 각 단계 내부 순번을 0에서 시작한다. 기존 번호에는 자동 재번호를 수행하지 않으며 번호 없는
외부 파일은 번호 파일 뒤에 이름순으로 둔다.
Default + Plot 폴더의 새 파일은 같은 다이얼로그에서 단계를 필수로 선택하고 해당 단계 마지막 순번으로 생성한다. 파일 dropdown의
`플롯 순서 편집`은 7개 그룹에서 드래그·단계 변경을 제공하고 저장 시 임시 이름을 거쳐 일괄 rename한다.
알 수 없거나 단계가 없는 기존 파일은 `미분류` 마지막에 두며 명시적으로 저장하기 전에는 변경하지 않는다.

권장 프로젝트 하이라키는 Project 루트를 장별 원고 영역으로 사용하고, 직속 디렉터리를 다음처럼 구성한다.

| 경로 | 유형 | 시작 번호 | 역할 |
|---|---|---:|---|
| Project 루트 | Default | 0 | 실제 장별 원고 |
| `1. Concept` | Default | 1 | 모티브, 글감, 메시지 등 구상 산출물 |
| `2. Outline` | Default | 1 | 캐릭터 초안, 전체 플롯, 발단부 구상 등 설계 산출물 |
| `3. Character` | General | 해당 없음 | 구체화된 캐릭터별 기준 문서 |
| `4. Scene` | Default + Plot | 단계별 0 | 구체화된 장면 구상과 서사 순서 |

영문명은 짧고 문학 작업에서 의미가 분명한 `Concept`와 `Outline`을 사용한다. 이 디렉터리는 앱이
새 프로젝트를 만들 때 번호가 붙은 위 순서로 자동 생성하는 제품 템플릿이다. 실제 디렉터리와 동일한 경로의
`FolderConfig`를 `.machum.json`에 한 번에 기록한다. 기존 프로젝트를 선택할 때는 누락 폴더를 소급 생성하지 않는다.
같은 이름의 Project가 이미 있으면 기존 디렉터리를 재사용하거나 덮어쓰지 않고 생성을 거부한다. 새 디렉터리의
기본 폴더 또는 설정 기록 중 실패하면 생성된 항목을 정리하고 실패로 반환한다.

남은 한계는 폴더별 마지막 선택 복원과 폴더 삭제 UI다.

---

## 6. 프로젝트 설정

프로젝트 디렉터리 직속 `.machum.json`은 `ProjectConfig`로 직렬화한다.

```kotlin
ProjectConfig(
    folders: Map<String, FolderConfig>,
    fileIds: Map<String, String>,
)
```

`FolderConfig`는 `type`, `plotEnabled`, `autoTags`를 가진다. 구 workflow 필드는 제거됐으며 `ignoreUnknownKeys=true`로 기존 설정을 읽는다.

현재 구현 경계:

- 모델과 JSON round-trip 테스트: 검증 완료
- `.machum.json` 생성·로드·쓰기: 구현됨
- 현재 프로젝트 설정을 `StateFlow<ProjectConfig?>`로 노출: 구현됨
- 프로젝트 선택·복원·전환 시 설정 상태 교체: 구현됨
- 빈 설정 및 구 설정의 base(`""`) 기본값(`default`) 보완·저장: 검증 완료
- 설정 전체 변경과 폴더별 설정 변경 API: 구현됨
- 폴더 탐색과 현재 폴더 상태에 적용: 구현·통합 검증 필요
- Default 정렬과 디렉터리 설정 편집 UI: 구현·수동 검증 필요
- autoTags 변경 시 기존 파일 동기화: 구현·자동 검증 완료

사이드바의 각 디렉터리 설정 아이콘에서 유형, Plot, 자동 태그를 수정한다. 유형과 Plot 변경은 기존 파일명을
자동으로 바꾸지 않고 이후 생성·정렬에만 적용한다. 자동 태그 변경 전에는 대상 파일의 pending save를 모두
flush하며, 이전 관리 태그를 제거하고 새 관리 태그를 추가하되 그 외 수동 태그는 보존한다. base 자동 태그
변경은 프로젝트 전체 직속 파일과 각 하위 디렉터리 파일에 적용한다.

생성·편집 다이얼로그는 `FolderConfigEditor`와 동일한 편집 상태를 공유한다. 설정 저장 순서(pending save
flush → `.machum.json` 갱신 → 기존 파일 관리 태그 동기화)는 `FolderSettingsService`가 담당하고,
`MainViewModel`은 반환된 파일을 화면 cache와 mtime 추적 상태에 반영한다.

설정 상태의 `null`은 프로젝트가 선택되지 않았거나 설정을 아직 로드하지 못한 상태를 뜻한다.
프로젝트가 바뀌면 이전 설정 상태를 먼저 비우며, 새 프로젝트의 설정을 읽은 뒤에만 새 상태를 공개한다.
현재는 라이브 상태까지 연결됐고, 다음 단계에서 상대 경로 기반 파일·폴더 상태가 이 설정을 소비한다.

---

## 7. 마크다운 파일과 frontmatter

`NoteFile`은 YAML frontmatter와 본문을 분리한다.

관리 키:

- `id`: 파일 정체성과 향후 커밋 추적
- `tags`: 프로젝트명 태그, 폴더 관리 태그와 수동 태그
- `aliases`: 파일 별칭
- `plot`: 서사 단계

정책:

1. 관리 키만 구조적으로 읽고 쓴다.
2. 미관리 키, 주석, 키 순서와 원래 포맷은 그대로 보존한다.
3. 관리 키도 실제 수정될 때만 표준 형태로 정규화한다.
4. YAML 라이브러리를 사용하지 않아 Obsidian 문서의 불필요한 전체 재포맷을 방지한다.
5. frontmatter가 없는 파일은 처음 읽을 때 `id`를 생성해 파일에 기록한다.
6. 프로젝트를 열면 루트와 직속 디렉터리의 모든 Markdown 파일에 프로젝트명을 필수 태그로 추가한다.
7. 앱이 기록하는 태그의 공백은 `_`로 정규화한다. 예: 프로젝트 `폴더 1` → `폴더_1`.

프로젝트 선택 직후 `ProjectIndexer`가 MainScreen 진입 전에 루트와 직속 디렉터리의 Markdown 파일을
점검한다. 파일별로 `id`와 프로젝트명 태그를 독립적으로 검사하고 누락된 값이 있을 때만 디스크를
갱신한다. 변경 대상이 없으면 별도 화면 없이 바로 진입하고, 변경 대상이 있을 때만 처리 수와 전체 수를
표시하는 로딩 화면을 보여준다. 완료되면 결과 확인 없이 MainScreen으로 자동 진입한다. 한 파일의
읽기·쓰기 실패는 다른 파일의 인덱싱을 중단하지 않는다.

이 초기 인덱싱은 frontmatter 필수값 보완 절차다. 열지 않은 파일의 메타데이터를 세션 동안 검색·그룹핑하는
인메모리 메타 인덱스는 별도 단계로 유지한다.

`NoteFileTest`는 관리 키 읽기·쓰기, 미지 키 보존, 안정적인 round-trip을 검증한다.

---

## 8. 편집과 저장 데이터 흐름

```text
PlatformFile
  → ProjectFile(FileKey = 프로젝트 상대 경로)
  → FileManager.readMarkdown()
  → NoteFile(frontmatter + body)
  → MainViewModel.noteFileCache
  → EditorPage
  → MarkdownBlockTextFieldM3(value = noteFile.body)
  → List<EditorBlock>
  → 사용자 편집
  → blocks.toMarkdown()
  → MainViewModel.updateBody()
  → DebouncedSaveCoordinator (FileKey별 500ms)
  → NoteFile.withBody()
  → FileManager.writeMarkdown()
```

저장 debounce의 소유자는 ViewModel 계층의 `DebouncedSaveCoordinator`다. 실효 저장 지연은 500ms이며,
같은 파일의 새 요청만 이전 요청을 취소한다. 다른 파일의 pending save는 서로 취소하지 않는다.
외부 mtime 변경·삭제·rename 시에는 해당 파일의 stale pending save를 취소한다.

`FileKey`는 `/`로 정규화한 프로젝트 상대 경로다. 따라서 프로젝트의 다른 직속 폴더를 탐색해도
동일한 파일명을 가진 파일들이 cache, mtime, pending save를 공유하지 않는다. `ProjectFile`은
이 키와 플랫폼별 `PlatformFile`을 함께 운반한다. 프로젝트가 바뀌면 이전 프로젝트의 cache,
mtime, pending save를 모두 비운다. frontmatter `id`와 `ProjectConfig.fileIds`를 이용한 rename·이동
추적은 폴더 발견 이후 별도 단계에서 연결한다.

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
2. 현재 `FileKey`를 기준으로 페이지 인덱스 보존
3. 캐시된 파일의 mtime 비교
4. 앱 자신의 마지막 쓰기 mtime과 같으면 건너뜀
5. 파일을 다시 읽고 `NoteFile.inject()` 결과를 비교
6. 실제 내용이 다를 때만 cache 교체와 에디터 재파싱

### 허용된 트레이드오프

진짜 외부 변경을 반영하면 에디터 블록 ID, focus, cursor, selection이 초기화될 수 있다. 외부 우선 정책상 현재는 이를 수용한다.

### 검증 상태

- Desktop 저장·외부 변경·rename·삭제 경합 수동 검증 완료
- Android DocumentsUI 저장·mtime 수동 검증 완료
- provider별 mtime 동작은 새 provider 지원 시 회귀 검증

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
