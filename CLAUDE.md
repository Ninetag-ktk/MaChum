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
2. `WorkflowScreen` → create/edit workflow markdown files
3. `ProjectSelectionScreen` → pick project folder inside vault
4. `WorkflowSelectionScreen` → assign workflow to project
5. `MainScreen` → main editor (HorizontalPager over markdown files)

### File System Abstraction (`external/FileManager.kt`)
The central class. Uses **expect/actual** pattern:
- `FileManager.android.kt`: Android Storage Access Framework (DocumentFile API, SAF permissions)
- `FileManager.jvm.kt`: Standard `java.io.File`

Key responsibilities:
- Persists vault/project selection via **AndroidX DataStore**
- Stores per-project config in `.machum.json` (`ProjectConfig`: workflow path + file ID map)
- Stores workflow files in a `.workflow/` subdirectory within the vault
- Exposes reactive state via `StateFlow`s: `bookmarks`, `workflowList`, `workflow`, `needUpdateWorkflow`

### Markdown Files (`external/NoteFile.kt`)
Markdown files use YAML frontMatter for metadata:
```yaml
---
id: a1b2c3d4
---

# Body content...
```
`NoteFile` parses/injects this frontMatter; IDs are auto-generated if missing.

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

### Dependency Injection
Koin 4.x. Module in `di/commonModule.kt`:
- `single<DataStore<Preferences>>` (FileKit `databasesDir` path)
- `single { FileManager(dataStore) }`
- `viewModel { MainViewModel(fileManager) }`

## Key Conventions

- **Expect/actual** is used for `FileManager` (file I/O, permissions, last-modified) and `Platform` (version string) and `VaultPickerUI` (platform file picker dialog).
- All reactive state uses `StateFlow`; UI collects via `.collectAsState()`.
- Config file `.machum.json` lives alongside note files in each project folder.
- Workflow files live in `<vault>/.workflow/*.md`.
- The `workflowSceen` package has a typo (missing `r`) — do not rename without updating all imports.
- Kotlin version: **2.3.20**; Compose Multiplatform: **1.10.3**; min Android SDK: **24**.
