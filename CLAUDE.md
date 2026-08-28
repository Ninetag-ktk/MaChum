# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**MaChum (맞춤)** is a Compose Multiplatform markdown note-taking app with workflow-based project management, targeting Android and Desktop (JVM). The name and UI strings are in Korean.

## Build & Run Commands

```bash
# Run desktop app
./gradlew :desktopApp:run

# Run desktop app with hot reload
./gradlew :composeApp:runDesktop -t

# Build Android APK
./gradlew :androidApp:assembleDebug

# Install on connected Android device
./gradlew :androidApp:installDebug

# Run all tests
./gradlew test

# Package desktop distribution (DMG/MSI/Deb)
./gradlew :desktopApp:packageDistributionForCurrentOS
```

Tests are minimal (single placeholder). No linting configuration exists.

## Architecture

### Module Layout
- **`composeApp/`** — Shared Kotlin Multiplatform library (all business logic and UI)
- **`androidApp/`** — Android wrapper (`MainActivity`, Koin + FileKit init)
- **`desktopApp/`** — JVM wrapper (window setup, two-window flow: vault picker → main editor)

### App Navigation Flow (`App.kt`)
State-machine navigation, no navigation library — conditional composables based on `FileManager` state:
1. `VaultSelectionScreen` → pick vault root directory
2. `ProjectSelectionScreen` → pick project folder inside vault
3. `MainScreen` → main editor (HorizontalPager over markdown files)

**workflow 네비게이션 은퇴 완료** (`docs/folder-zone-model.md` §1.2, §7): `WorkflowScreen`/`WorkflowSelectionScreen` 단계 제거. workflow 화면 파일과 `FileManager`의 workflow 함수·StateFlow(`workflowList`/`workflow`/`needUpdateWorkflow`, `setWorkflow`/`pickWorkflow`/`getWorkflowList` 등)는 **삭제 없이 dormant 상태로 컴파일만 유지**(라이브 흐름에서 호출 안 함). `setFile()`의 빈-프로젝트 폴백은 workflow 첫 스텝 대신 임시 기본명(`"0. 제목"`)으로 대체 — 폴더-존 넘버링(§6.1)이 정교화 예정. 향후 폴더-존 네비게이션(폴더=스와이프 스코프)으로 확장.

### File System Abstraction (`external/FileManager.kt`)
The central class. Uses **expect/actual** pattern:
- `FileManager.android.kt`: Android Storage Access Framework (DocumentFile API, SAF permissions)
- `FileManager.jvm.kt`: Standard `java.io.File`

Key responsibilities:
- Persists vault/project selection via **AndroidX DataStore**
- Stores per-project config in `.machum.json` (`ProjectConfig`: **폴더-존 스키마** — `folders: Map<경로, FolderConfig{type, autoTags}>` + `fileIds`. 구 `workflow`/`workflowLastModified` 필드 제거, 읽기 시 `ignoreUnknownKeys`로 하위호환). read/write via `readConfig()`/`writeConfig()` using a lenient `configJson`.
- `.workflow/` subdirectory (vault) + `workflowList`/`workflow`/`needUpdateWorkflow` StateFlows: **workflow 은퇴로 dormant** (라이브 흐름 미사용, 삭제 안 함)
- `lastModified(file): Long?` — file mtime (epoch millis), null-safe wrapper over the `getLastModified()` expect/actual. Used for external-change detection (see Editor 섹션).
- `lastModified(file): Long?` — file mtime (epoch millis), null-safe wrapper over the `getLastModified()` expect/actual. Used for external-change detection (see Editor 섹션).

### Markdown Files (`external/NoteFile.kt`)
Markdown files use YAML frontMatter for metadata:
```yaml
---
id: a1b2c3d4
tags:
  - 당신을_구하던_삶
aliases:
  - 풀네임
plot: 1) 발단
---

# Body content...
```
`NoteFile` parses/injects this frontMatter; IDs are auto-generated if missing.

**구조화된 프론트매터 (폴더-존 모델 step 0, 접근법 A — `docs/folder-zone-model.md` §4~§5):**
- 프론트매터를 top-level 키 단위 블록으로 파싱. **관리 키**(`id`/`tags`/`aliases`/`plot`)만 구조적으로 읽고/쓰고(`val id/plot/tags/aliases`, `withId/withPlot/withTags/withAliases`), **그 외 키·주석·키 순서·포맷은 원형 그대로 보존**(Obsidian 호환 계약). YAML 라이브러리 미사용(round-trip 재포맷 방지).
- 관리 키는 **실제 수정 시에만** 표준 형태로 정규화(스칼라 `key: value`, 리스트 block `- `). 미수정 시 verbatim 유지 → 순수 `parse→inject` 왕복은 본문 앞 공백 정규화를 빼면 무손실. `inject()` 는 diff 안정적(같은 내용 → 같은 문자열).
- 리스트 읽기는 block(`- `)/flow(`[a, b]`)/인라인 CSV 형태를 모두 인식. 관리 태그 일괄 동기화 로직(옛 관리 태그 제거 + 신규 추가 + 수동 태그 보존)은 상위 계층 담당 — `NoteFile` 은 `tags`/`withTags` 만 제공.
- `getId()` 는 제거되고 `val id` 로 대체됨(호출부는 `.id`).
- 테스트: `commonTest/.../external/NoteFileTest.kt`.

### Workflow System (`external/WorkflowParser.kt`)
Workflows are markdown files parsed into a `HeaderNode` tree (levels 1–4 via `#`–`####`). Leaf nodes become `WorkflowStep`s with dot-notation numbering (e.g., `"1-2-3"`). Blockquotes (`>`) become step descriptions. The tree serializes back to markdown via `toMarkdown()`.

### Markdown Editor Engine (`markdown/`)
**블록 기반 에디터로 전환 완료 (Phase 1+2 부분).** 문서를 블록 리스트(`List<EditorBlock>`)로 관리하고 각 블록이 독립 Composable로 렌더링된다. 상세 설계: `markdown/CLAUDE_sub.md`, 체크리스트: `markdown/compact.md`.

블록 에디터 핵심 컴포넌트 (구현 완료):
- **`EditorBlock`** (`state/`) — sealed class: Text, Callout, Code, Table, HorizontalRule, Embed. 각 블록이 자체 TextFieldState 보유.
- **`MarkdownBlockParser`** (`state/`) — raw markdown → `List<EditorBlock>` 파싱 (Callout body 재귀)
- **`BlockOperations`** (`state/`) — 블록 분할/병합 로직 (```, `> [!TYPE]`, `---`, `\n\n`, Backspace)
- **`MarkdownBlockEditor`** (`ui/`) — LazyColumn 기반 블록 렌더링 + FocusRequester 맵 + BlockNavigation
- **`MarkdownBlockTextField`** / **`MarkdownBlockTextFieldM3`** (`ui/`) — 공개 API (value/onValueChange)
- **`TextBlockEditor`** (`ui/`) — BasicTextField + OutputTransformation (인라인 서식) + 패턴 감지
- **`CalloutBlockEditor`** (`ui/block/`) — Standard + DL(Dialogue) 변형, 재귀적 body
- **`CodeBlockEditor`**, **`TableBlockEditor`**, **`HorizontalRuleDivider`** (`ui/block/`)

v1 컴포넌트 (EditorPage에서 미사용, 제거 예정):
- `MarkdownBasicTextField`, `MarkdownTextField`, `MarkdownEditorState`
- `OverlayBlockParser`, `OverlayPositionCalculator`, `OverlayScrollForwarder`
- `BlockOverlay`, `CalloutOverlay`, `CodeBlockOverlay`, `TableOverlay`

v1에서 블록 에디터가 재활용하는 컴포넌트:
- `InlineStyleScanner`, `MarkdownPatternScanner` — TextBlockEditor의 OutputTransformation 내부
- `RawMarkdownOutputTransformation` — TextBlockEditor에서 `applyBlockTransparent=false`로 사용
- `EditorInputTransformation`, `EditorKeyboardShortcuts`, `RawStyleToggle` — TextBlockEditor에서 사용
- `BlockDecorationDrawer` — TextBlockEditor drawBehind
- `MarkdownStyleConfig` — 전체 스타일 설정

### Editor (`screen/mainComposition/`)
- **`MainViewModel`**: manages file list, active page index, and a note cache (`Map<String, NoteFile>`). Debounces saves 500ms via a `_saveRequest` StateFlow.
- **`EditorPage`**: Uses `MarkdownBlockTextFieldM3` (block-based editor). `key(file.name)` for file switching, `MutableStateFlow` + `debounce(500ms)` for save.

#### 외부 변경 자동 리로드 (external-change auto-reload)

파일은 앱 안에서도, 외부(옵시디언 등)에서도 편집된다. 외부 수정사항을 근실시간으로 다시 읽어와 에디터에 반영한다. **정책: 외부 우선(external wins) — 충돌 시 조건 없이 자동 리로드.**

- **감지 방식은 mtime 폴링** (WatchService/이벤트 아님). 활성(포커스) 상태에서만 동작:
  - `MainScreen` 이 `LocalWindowInfo.isWindowFocused` 를 `snapshotFlow` 로 관찰 → `MainViewModel.setActive(focused)` (공통 코드, Android/Desktop 공통 동작)
  - `MainViewModel` 이 `_active` 를 `collectLatest` 로 구독 → 활성 전환 시 **즉시 1회 검사(Phase 1)** + 이후 **`POLL_INTERVAL_MS`(1.5s) 주기 폴링(Phase 2)**. 포커스 상실 시 루프 자동 취소(배터리/IO 절약)
- **`checkExternalChanges()`** 매 폴링: (1) `listFile` 로 파일 목록 갱신(외부 추가/삭제 반영, 현재 인덱스는 파일명으로 보존) → (2) 캐시된 파일별 `lastModified` 비교. mtime 이 같으면 skip, 다르면 파일을 읽어 `NoteFile.inject()` 로 실제 내용 diff → 다를 때만 `_noteFileCache` 교체 → `EditorPage` 의 `value` 변경 → 에디터 전체 재파싱
- **자기쓰기 구분** (`knownModified: Map<파일명, mtime>`): 앱 자신의 저장(`writeMarkdown`) 직후 / 초기 로드(`loadPage`) / rename 시 mtime 을 기록. 폴링이 이 값과 같으면 "내 쓰기" 로 간주해 무시 → **일반 타이핑이 리로드를 유발하지 않음**. mtime 만 갱신되고 내용이 같은 경우(저장 레이스, touch 등)는 `inject()` diff 가 2차로 걸러 spurious 리로드를 막음
- **클라우드 동기화 지연(구글 드라이브 등) 대응이 폴링을 채택한 핵심 이유**: 외부 편집이 로컬 파일시스템에 반영되는 시점이 늦거나 불규칙하다. 일회성 이벤트라면 놓치지만, 폴링은 포커스 중 계속 재검사 + 포커스 복귀 시 즉시 검사하므로 **바이트가 로컬에 뒤늦게 도착해도 그 시점에 잡는다**. 동시 편집(같은 파일을 양쪽에서 동시에)은 우려 대상 아님 — 외부 우선으로 단순 처리
- **트레이드오프**: 진짜 외부 변경 시 에디터가 전체 재파싱되어 커서/포커스/selection 이 초기화된다(외부 우선 정책상 수용). 자기쓰기 구분 + 내용 diff 로 실제 외부 변경 시점에만 발생
- **미검증**: 데스크탑 수동 검증 대기. Android SAF `COLUMN_LAST_MODIFIED` 는 파일매니저/동기화앱 구현에 따라 mtime 갱신 타이밍이 달라 실기 확인 권장

### Dependency Injection
Koin 4.x. Module in `di/commonModule.kt`:
- `single<DataStore<Preferences>>` (FileKit `databasesDir` path)
- `single { FileManager(dataStore) }`
- `viewModel { MainViewModel(fileManager) }`

## Key Conventions

- **Expect/actual** is used for `FileManager` (file I/O, permissions, last-modified) and `Platform` (version string) and `VaultPickerUI` (platform file picker dialog) and `clipEntryOf` (`external/ClipEntryFactory.kt` — builds a platform `ClipEntry` from text for the new `LocalClipboard` API: Android `ClipData.newPlainText`, Desktop `StringSelection`).
- All reactive state uses `StateFlow`; UI collects via `.collectAsState()`.
- Config file `.machum.json` lives alongside note files in each project folder.
- Workflow files live in `<vault>/.workflow/*.md`.
- The `workflowSceen` package has a typo (missing `r`) — do not rename without updating all imports.
- Kotlin version: **2.3.20**; Compose Multiplatform: **1.10.3**; min Android SDK: **24**.
