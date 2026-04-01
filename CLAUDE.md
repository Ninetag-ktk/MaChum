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
The `markdown/editor/` package implements a live-preview markdown editor using raw text + symbol transparency (OutputTransformation). Key components:
- **`MarkdownPatternScanner`** — scans entire document for block/inline patterns, produces `ScanResult` (spans + block ranges). Also contains `BlockType`, `BlockRange`, `ScanResult` data classes.
- **`InlineStyleScanner`** — computes per-block `SpanStyle` ranges (MARKER transparent + content styled).
- **`RawMarkdownOutputTransformation`** — `OutputTransformation` impl; active line shows raw, inactive lines show styled preview.
- **`OverlayBlockParser`** — parses raw text into `OverlayBlockData` (Callout/Table/CodeBlock) for overlay composables. Contains `OverlayBlockData` sealed class.
- **`overlay/`** — Composable overlays (Callout, Table, CodeBlock) with internal TextFields for direct editing + raw markdown sync.

Sealed types: `MarkdownBlock` and `InlineToken` in `markdown/token/` (used by `InlineStyleScanner` and `MarkdownPatternScanner`).

### Editor (`screen/mainComposition/`)
- **`MainViewModel`**: manages file list, active page index, and a note cache (`Map<String, NoteFile>`). Debounces saves 500ms via a `_saveRequest` StateFlow.
- **`EditorPage`**: block-based editor — double newline (`\n\n`) splits a block; each block shows markdown preview (unselected) or `BasicTextField` (selected). Uses `mikepenz` markdown renderer for previews.

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
