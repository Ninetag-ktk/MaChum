# Graph Report - MaChum  (2026-09-18)

## Corpus Check
- 262 files · ~217,857 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 22 file(s) not represented in the graph (top: .xml 7, .toml 5, (none) 3)

## Summary
- 3621 nodes · 10704 edges · 174 communities (141 shown, 33 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 1052 edges (avg confidence: 0.87)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `cd4575a0`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- EditorTopBarLayoutTest
- hasPendingPropertyDefinitionSync
- GeneralSourceService
- SidebarWorkspaceMenus.kt
- CommitHistoryScreen.kt
- ProjectNavigationDrawer.kt
- HierarchyRowsCompositionTest
- ProjectCommitService
- test
- MainViewModelHierarchyTest
- ProjectCommitServiceTest
- FileManagerWorkspaceTest
- FolderDirectControls.kt
- EditorHistory
- FileManagerFolderTest.kt
- FolderKey
- .remember
- DocumentPropertyProtectionPolicy
- PlotStage
- MainViewModelWorkspaceSelectionTest
- MainViewModelFolderSettingsTest.kt
- WorkspaceKind
- ProjectConfig
- FileManager.kt
- DocumentProperties.kt
- MarkdownBlockEditor.kt
- FolderDirectControlsTest.kt
- CommitWorkspaceScreenTest
- Bookmarks
- FolderDirectControlsTest
- CalloutBlockEditor.kt
- FileManager.jvm.kt
- PlatformFile
- SelectionEndpoint
- CommitBackupModels.kt
- WorkspaceTransitionsTest
- GeneralSourceService.kt
- WorkspaceSaveCoordinator
- PersistentProjectBackupQueue
- parseDocumentProperties
- MainViewModelFileCreationRequestTest
- HierarchyOrderDraft.kt
- ProjectIndexer.kt
- FileCommitStore
- What You Must Do When Invoked
- DocumentInformationSectionTest
- CalloutBodyAction
- withManagedTagChanges
- FileManager.android.kt
- AboveAnchorDropdownMenu.kt
- NoteFileTest
- FolderConfig
- .withFolderRenameFixture
- PlatformFile
- PolicyScrollbar.jvm.kt
- EditorTopBar.kt
- CommitDialog.kt
- ProjectFileOrdering.kt
- BlockNavigation
- FileManagerProjectFileTrashTest
- NoteFile.kt
- CommitTree
- ProjectFile
- EditorFocusCoordinator
- FileManagerWorkspaceLifecycleTest
- FolderSettingsService.kt
- DocumentPropertyType
- moveProjectFileToTrash
- WorkspaceMotion
- FileManagerWorkspaceTrashTest
- WorkspaceSelectionDialogTest
- RemoteProjectBackupStore
- MainViewModelFileMoveTest
- MainViewModelRenameTest
- MarkdownStyleConfig.kt
- .workerKeepsFailedWorkAndANewWorkerCanResumeIt
- commonModule.kt
- MarkdownBlockTextFieldContent
- MarkdownStyleConfig
- .withContext
- 10. 외부 변경 감지
- FakeRemoteProjectBackupStore
- directoryNameError
- FileKey
- GeneralSourceProperty
- CommitModels.kt
- 제품 모델·로드맵 이전 안내
- mergeKnownConfig
- workspaceSetup
- .scan
- DebouncedSaveCoordinator
- PersistentBackupWorker.kt
- MaChum 현재 아키텍처
- BlockDecorationDrawer.kt
- WorkspaceSaveCoordinatorTest
- WorkspaceLoadDiagnostics
- 8. Vault 폴더 사용 방식 — 2026-09-07
- readVaultConfigUnlocked
- MarkdownBlockParserTest
- 3. 블록 모델과 직렬화
- 3. 저장·외부 변경 경합
- MainActivity.kt
- 블록 기반 마크다운 에디터 설계
- ClipEntryFactory.jvm.kt
- CommitPlatform.android.kt
- EditorInputTransformation
- LineDiffCounter
- TextBlockEditor
- RawMarkdownOutputTransformation
- 8. 권장 구현 순서
- .menuScene
- architecture.md
- .commit
- CustomColorScheme.kt
- CommitObjectCodec
- MainViewModel.kt
- CommitFileSide
- MaChum P0 수동 테스트
- BlockOperationsTest
- HierarchyOrderDraftTest
- GeneralSourcePropertyTest
- ClipEntryFactory.android.kt
- PolicyScrollbar.android.kt
- graphify reference: extra exports and benchmark
- injectDefaultDocumentProperties
- PolicyScrollbar.kt
- DocumentSelection
- FileKeyTest
- .flush
- RawStyleToggle
- jvmtarget
- MaChum 프로젝트 파일 탐색 수동 테스트
- .calculatePosition
- WorkspaceDialog
- EditorSelectionCoordinatorTest
- 6. 탐색과 파일 생성
- EditorRecompositionDiagnostics.kt
- DocumentPagerTest
- LineDiffEngine
- WorkspaceSelection.kt
- EditorBlock
- gradlew
- MainViewModelLifecycleTest
- 9. 커밋 기능 설계
- MaChum 작업 가이드
- graphify reference: query, path, explain
- CommitWorkspaceMutator
- 프로젝트 커밋 수동 테스트
- 4. frontmatter 정책
- 7. 기능 상태와 우선순위
- LatestNavigationGate
- MainScreen.kt
- MainViewModelFileTrashTest
- MarkdownBlock
- CalloutBodyPolicyTest
- EditorMutationDispatcherTest
- 3. 프로젝트 파일 구조
- WorkspaceTrashKind
- 에이전트 운영 원칙
- graphify reference: add a URL and watch a folder
- graphify reference: commit hook and native CLAUDE.md integration
- graphify reference: incremental update and cluster-only
- RawMarkdownOutputTransformationTest
- isValidProjectFileTitle
- graphify reference: GitHub clone and cross-repo merge
- graphify reference: transcribe video and audio
- CommitPlatform.kt
- extraction-spec.md

## God Nodes (most connected - your core abstractions)
1. `Bookmarks` - 129 edges
2. `FolderConfig` - 111 edges
3. `ProjectCommitService` - 105 edges
4. `ProjectFile` - 81 edges
5. `EditorBlock` - 80 edges
6. `WorkspaceSaveCoordinator` - 77 edges
7. `FileKey` - 62 edges
8. `ProjectCommitServiceTest` - 58 edges
9. `FolderKey` - 48 edges
10. `FileManagerWorkspaceTest` - 47 edges

## Surprising Connections (you probably didn't know these)
- `플랫폼 진입점` --references--> `App()`  [INFERRED]
  docs/architecture.md → composeApp/src/commonMain/kotlin/com/ninetag/machum/App.kt
- `기능 상태: 비활성` --references--> `ProjectCommitService`  [INFERRED]
  docs/google-drive-backup.md → composeApp/src/commonMain/kotlin/com/ninetag/machum/commit/ProjectCommitService.kt
- `SAVE-08 파일 이름 변경 검증` --references--> `FileKey`  [INFERRED]
  docs/p0-manual-test.md → composeApp/src/commonMain/kotlin/com/ninetag/machum/external/ProjectFile.kt
- `16.1 `EditorDocumentState`` --references--> `DocumentSelection`  [INFERRED]
  docs/markdown-editor.md → composeApp/src/commonMain/kotlin/com/ninetag/machum/markdown/state/DocumentSelection.kt
- `현 구조에서 허용하지 않는 구현` --references--> `DocumentSelection`  [INFERRED]
  docs/markdown-editor.md → composeApp/src/commonMain/kotlin/com/ninetag/machum/markdown/state/DocumentSelection.kt

## Import Cycles
- None detected.

## Communities (174 total, 33 thin omitted)

### Community 0 - "EditorTopBarLayoutTest"
Cohesion: 0.14
Nodes (8): EditorTopBar(), Dp, EditorTopBarLayoutTest, ImageComposeScene, IntSize, LayoutDirection, Rect, SemanticsNode

### Community 1 - "hasPendingPropertyDefinitionSync"
Cohesion: 0.13
Nodes (20): CommitHistoryEntry, beginCommitRequest(), confirmCommitRestore(), confirmDeleteDirectory(), confirmMoveFileToTrash(), createCommit(), createDirectory(), hasPendingPropertyDefinitionSync() (+12 more)

### Community 2 - "GeneralSourceService"
Cohesion: 0.07
Nodes (12): GeneralSourceChange, GeneralSourceConfig, GeneralSourcePlan, GeneralSourceRecovery, GeneralSourceResult, GeneralSourceService, GeneralSourceState, PlatformFile (+4 more)

### Community 3 - "SidebarWorkspaceMenus.kt"
Cohesion: 0.08
Nodes (27): arrowdropdown, BringIntoViewRequester, DrawerMenuItem(), CreateFileAction(), Modifier, Dp, Modifier, PlatformFile (+19 more)

### Community 4 - "CommitHistoryScreen.kt"
Cohesion: 0.13
Nodes (26): check, CommitHistoryDetail(), CommitHistoryFileDiff(), CommitHistoryListItem(), CommitHistoryListPane(), CommitRestoreActionAvailability, DisabledReason(), EmptyPaneMessage() (+18 more)

### Community 5 - "ProjectNavigationDrawer.kt"
Cohesion: 0.04
Nodes (74): add, animatedvisibility, awaiteachgesture, awaitfirstdown, clickable, collectisfocusedasstate, collectishoveredasstate, collectispressedasstate (+66 more)

### Community 6 - "HierarchyRowsCompositionTest"
Cohesion: 0.17
Nodes (4): GeneralSourceEntry, GeneralSourceControls(), HierarchyRowsCompositionTest, SemanticsNode

### Community 7 - "ProjectCommitService"
Cohesion: 0.13
Nodes (15): CommitConflictException, CommitStorageException, IllegalStateException, ProjectSnapshot, RestoreResult, ExistingMarkdown, FileManagerCommitWorkspaceMutator, HistoricalFileState (+7 more)

### Community 8 - "test"
Cohesion: 0.10
Nodes (17): assertequals, assertfailswith, assertfalse, assertis, assertnotnull, assertnotsame, assertnull, asserttrue (+9 more)

### Community 9 - "MainViewModelHierarchyTest"
Cohesion: 0.09
Nodes (7): defaultProjectConfig(), Job, MainViewModel, PlatformFile, MainViewModelHierarchyTest, WorkspaceMutationFixture, HierarchyUiState

### Community 10 - "ProjectCommitServiceTest"
Cohesion: 0.13
Nodes (3): FaultInjectingCommitWorkspaceMutator, PausingCommitWorkspaceMutator, ProjectCommitServiceTest

### Community 11 - "FileManagerWorkspaceTest"
Cohesion: 0.07
Nodes (5): FileManagerWorkspaceTest, InitializationPreferencesDataStore, DataStore, Flow, Preferences

### Community 12 - "FolderDirectControls.kt"
Cohesion: 0.07
Nodes (38): accounttree, borderstroke, circleshape, close, Color, WorkspaceBackHandler(), CompactPropertyFieldDecoration(), DocumentInformationSection() (+30 more)

### Community 13 - "EditorHistory"
Cohesion: 0.09
Nodes (27): Callout, captureEditorDocumentSnapshot(), Code, EditorBlockSnapshot, EditorDocumentSnapshot, Embed, HorizontalRule, restore() (+19 more)

### Community 14 - "FileManagerFolderTest.kt"
Cohesion: 0.12
Nodes (18): atomicreference, base_folder_path, cancel, effectiveAutoTags(), FolderType, DEFAULT, GENERAL, withDefaultBaseFolder() (+10 more)

### Community 15 - "FolderKey"
Cohesion: 0.12
Nodes (9): replaceFolderPrefix(), FolderKey, normalizeRelativePath(), FolderFileSelectionMemory, createOrderDraft(), startFileMoveDrag(), togglePlotGroup(), savePlotOrder() (+1 more)

### Community 16 - ".remember"
Cohesion: 0.07
Nodes (32): animatable, clearandsetsemantics, columnscope, AndroidVaultPickerUI, rememberVaultPickerUI(), DpOffset, Modifier, MenuMotion (+24 more)

### Community 17 - "DocumentPropertyProtectionPolicy"
Cohesion: 0.11
Nodes (8): DocumentPropertyProtectionPolicy, NoteFile, display(), hasContent(), NoteFile, PropertyDraft, PropertyForm, scalarText()

### Community 18 - "PlotStage"
Cohesion: 0.10
Nodes (18): PlotStage, CLIMAX, CRISIS, DEVELOPMENT, EPILOGUE, PROLOGUE, RESOLUTION, SETUP (+10 more)

### Community 19 - "MainViewModelWorkspaceSelectionTest"
Cohesion: 0.11
Nodes (10): FileManager, PlatformFile, openWorkspaceChoice(), FailingPreferencesStore, Fixture, DataStore, Flow, Preferences (+2 more)

### Community 20 - "MainViewModelFolderSettingsTest.kt"
Cohesion: 0.20
Nodes (39): aftertest, assertcontentequals, assertnotequals, assertsame, async, atomicboolean, atomicinteger, atomiclong (+31 more)

### Community 21 - "WorkspaceKind"
Cohesion: 0.08
Nodes (20): AbstractApplier, broadcastframeclock, IndexedGeneralSource, PlatformFile, WorkspaceFileMetadata, WorkspaceMetadataIdentity, WorkspaceMetadataIndex, WorkspaceMetadataSnapshot (+12 more)

### Community 22 - "ProjectConfig"
Cohesion: 0.07
Nodes (31): applyDocumentPropertyDefinition(), applyDocumentPropertyDefinitions(), DocumentPropertyDefaults, DocumentPropertyDefinitionChange, validateDocumentPropertyDefinitions(), documentPropertyKeyError(), FolderConfigSerializer, FolderConfigSurrogate (+23 more)

### Community 23 - "FileManager.kt"
Cohesion: 0.08
Nodes (21): bytearraypreferenceskey, awaitProjectHierarchySnapshot(), DefaultOrderUpdate, deleteDirectoryExact(), FolderDeletionUpdate, FolderRenameRollbackException, IllegalStateException, markdownName() (+13 more)

### Community 24 - "DocumentProperties.kt"
Cohesion: 0.13
Nodes (36): BooleanValue, DateTimeValue, DateValue, DocumentProperties, DocumentProperty, DocumentPropertyListItem, documentPropertySource(), DocumentPropertyValue (+28 more)

### Community 25 - "MarkdownBlockEditor.kt"
Cohesion: 0.08
Nodes (34): animatescrollby, CodeBlockEditor(), Brush, FocusRequester, Modifier, TextStyle, Brush, FocusRequester (+26 more)

### Community 26 - "FolderDirectControlsTest.kt"
Cohesion: 0.13
Nodes (41): abs, annotatedstring, boundsinroot, box, commitdiffuistate, handleEditorKeyEvent(), TextFieldState, compositionlocalprovider (+33 more)

### Community 27 - "CommitWorkspaceScreenTest"
Cohesion: 0.21
Nodes (5): CommitPreview, CommitWorkspaceScreenTest, Fixture, MutableState, SemanticsNode

### Community 28 - "Bookmarks"
Cohesion: 0.16
Nodes (5): Bookmarks, ProjectFolder, PlotOrderAssignment, FileManagerFolderTest, FileManagerProjectTagSyncTest

### Community 29 - "FolderDirectControlsTest"
Cohesion: 0.13
Nodes (11): FolderDirectControls(), FolderTagChip(), FolderTagDraft, CoroutineScope, FolderTagDraftTest, DrawerFocusFixture(), FixedInputModeManager, FolderDirectControlsTest (+3 more)

### Community 30 - "CalloutBlockEditor.kt"
Cohesion: 0.07
Nodes (75): arrangement, arrowback, background, basictextfield, border, button, checkbox, checkcircle (+67 more)

### Community 31 - "FileManager.jvm.kt"
Cohesion: 0.16
Nodes (23): incompleteFileCreation(), createFile(), createFileWithContentWriter(), createFolder(), createFolderExclusive(), deleteDirectoryExact(), deleteEmptyFolderExclusive(), PlatformFile (+15 more)

### Community 32 - "PlatformFile"
Cohesion: 0.18
Nodes (23): checkUniqueWorkspaceIdentity(), configureExistingProject(), confirmWorkspaceOpen(), createFile(), createFolder(), createProject(), deleteEmptyFolderExclusive(), folderIdentity() (+15 more)

### Community 33 - "SelectionEndpoint"
Cohesion: 0.14
Nodes (24): commonPrefixLength(), compareEndpoint(), continueTextInputAt(), endpointExists(), extractMarkdown(), isAtomic(), Multi, nextFocusEndpoint() (+16 more)

### Community 34 - "CommitBackupModels.kt"
Cohesion: 0.12
Nodes (15): BackupConcurrencyException, BackupDispatchResult, BackupDispatchStatus, FAILED, NOT_CONFIGURED, QUEUED, BackupHistoryMismatchException, CommitBackupPlan (+7 more)

### Community 35 - "WorkspaceTransitionsTest"
Cohesion: 0.22
Nodes (12): WorkspaceChoice, Modifier, Offset, Rect, transitionLabel(), WorkspaceChoiceList(), workspaceDropDestination(), WorkspaceTransitionDialog() (+4 more)

### Community 36 - "GeneralSourceService.kt"
Cohesion: 0.10
Nodes (28): cancellationexception, BackupKeyedMutexRegistry, T, GeneralSourceOperation, ASSIGN, DELETE, RENAME, createCommitStorageFile() (+20 more)

### Community 37 - "WorkspaceSaveCoordinator"
Cohesion: 0.23
Nodes (3): StateFlow, WorkspaceSaveCoordinator, MainViewModelCommitTest

### Community 38 - "PersistentProjectBackupQueue"
Cohesion: 0.15
Nodes (12): bookmarkdata, clock, BackupNotConfiguredException, BackupQueueStorageException, IllegalStateException, PlatformFile, PendingProjectBackup, PersistentProjectBackupQueue (+4 more)

### Community 39 - "parseDocumentProperties"
Cohesion: 0.16
Nodes (8): deleteDocumentProperty(), DocumentPropertyResult, Failure, parseDocumentProperties(), renameDocumentProperty(), setDocumentProperty(), Success, DocumentPropertiesTest

### Community 40 - "MainViewModelFileCreationRequestTest"
Cohesion: 0.26
Nodes (6): CreateFileRequest, Job, MainViewModel, PlatformFile, MainViewModelFileCreationRequestTest, WorkspaceFixture

### Community 41 - "HierarchyOrderDraft.kt"
Cohesion: 0.24
Nodes (4): HierarchyOrderDraft, HierarchyPlotOrderItem, PlotHierarchyOrderDraft, rebuildPlotItems()

### Community 42 - "ProjectIndexer.kt"
Cohesion: 0.13
Nodes (18): asstateflow, awaitall, Idle, IndexedProjectMetadata, Indexing, PlatformFile, StateFlow, Preparing (+10 more)

### Community 43 - "FileCommitStore"
Cohesion: 0.20
Nodes (7): CommitBackupPlanSource, PlatformFile, ProjectCommit, sha256Utf8(), FileCommitStore, PlatformFile, StoreDirectories

### Community 44 - "What You Must Do When Invoked"
Cohesion: 0.08
Nodes (25): For /graphify add and --watch, For /graphify query, For the commit hook and native CLAUDE.md integration, For --update and --cluster-only, /graphify, Honesty Rules, Interpreter guard for subcommands, Part A - Structural extraction for code files (+17 more)

### Community 45 - "DocumentInformationSectionTest"
Cohesion: 0.34
Nodes (5): DocumentInformationSectionTest, Fixture, MutableState, NoteFile, SemanticsNode

### Community 46 - "CalloutBodyAction"
Cohesion: 0.10
Nodes (20): CalloutBodyAction, CreateBody, FocusBodyStart, FocusTitleEnd, Ignore, MoveNext, MovePrevious, CalloutBodyBoundary (+12 more)

### Community 47 - "withManagedTagChanges"
Cohesion: 0.33
Nodes (4): NoteFile, mergeManagedTags(), withManagedTagChanges(), ManagedTagMergeTest

### Community 48 - "FileManager.android.kt"
Cohesion: 0.23
Nodes (25): androidfile, createFile(), createFolder(), createFolderExclusive(), deleteDirectoryExact(), deleteEmptyFolderExclusive(), getLastModified(), Context (+17 more)

### Community 49 - "AboveAnchorDropdownMenu.kt"
Cohesion: 0.14
Nodes (17): aboveAnchorAvailableHeight(), AboveAnchorDropdownMenu(), AboveAnchorPositionProvider, Dp, IntOffset, IntRect, IntSize, LayoutDirection (+9 more)

### Community 51 - "FolderConfig"
Cohesion: 0.08
Nodes (9): FolderConfig, sortedFor(), FolderPresentation, DEFAULT, GENERAL, PLOT, FolderConfigMigrationTest, ProjectConfigTest (+1 more)

### Community 52 - ".withFolderRenameFixture"
Cohesion: 0.16
Nodes (11): FolderRenameUpdate, FolderRenameFixture, InterruptBehavior, AWAIT_CANCELLATION, THROW_CANCELLATION, THROW_FAILURE, InterruptiblePreferencesDataStore, ByteArray (+3 more)

### Community 53 - "PlatformFile"
Cohesion: 0.20
Nodes (5): PlatformFile, MutationCheckpoint, CREATE_FILE, DELETE, WRITE

### Community 54 - "PolicyScrollbar.jvm.kt"
Cohesion: 0.15
Nodes (17): LazyListState, Modifier, ScrollState, PolicyLazyVerticalScrollbar(), PolicyScrollbarIntrinsicZeroLayout(), PolicyScrollbarIntrinsicZeroMeasurePolicy, PolicyVerticalScrollbar(), Constraints (+9 more)

### Community 55 - "EditorTopBar.kt"
Cohesion: 0.07
Nodes (32): alpha, animatefloatasstate, article, centeralignedtopappbar, cliptobounds, commit, IntOffset, IntRect (+24 more)

### Community 56 - "CommitDialog.kt"
Cohesion: 0.07
Nodes (37): boxwithconstraints, buildannotatedstring, buttondefaults, ProjectFolderDeletionPreview, CommitLineDiffContent(), CommitRestoreConfirmationDialog(), CommitDiffUiState, Dp (+29 more)

### Community 57 - "ProjectFileOrdering.kt"
Cohesion: 0.17
Nodes (13): compareHierarchicalFileNames(), hierarchicalNumberPrefix(), nextDefaultFileName(), nextNumber(), numberedPrefix(), PlotFileEntry, PlotFilePrefix, plotOrder() (+5 more)

### Community 58 - "BlockNavigation"
Cohesion: 0.20
Nodes (21): com, CalloutBlockEditor(), CalloutBodyEditor(), CalloutBodyRuntime, calloutIcon(), DialogueCallout(), androidx, Brush (+13 more)

### Community 60 - "NoteFile.kt"
Cohesion: 0.11
Nodes (25): normalizeTags(), Block, buildBlocks(), detectLineEnding(), documentPropertiesSnapshot(), ensureId(), findClosingFence(), generatedId() (+17 more)

### Community 61 - "CommitTree"
Cohesion: 0.20
Nodes (4): CommitTree, CommitTreeEntry, CommitPlanner, FileCommitStoreIntegrityTest

### Community 62 - "ProjectFile"
Cohesion: 0.18
Nodes (6): FileCreationIncompleteException, ProjectFileMoveAssignment, ProjectFile, DefaultHierarchyOrderDraft, FileManagerProjectFileMoveTest, 5.3 현재 Project 내부 폴더·파일 목록

### Community 63 - "EditorFocusCoordinator"
Cohesion: 0.12
Nodes (14): AtOffset, AtX, CursorHint, EditorFocusCoordinator, EditorFocusIntent, EditorFocusRequest, End, Start (+6 more)

### Community 65 - "FolderSettingsService.kt"
Cohesion: 0.13
Nodes (13): FolderSettingsFile, FolderSettingsPlan, FolderSettingsService, FolderSettingsUpdate, PlatformFile, description(), displayName(), FolderConfigEditor() (+5 more)

### Community 66 - "DocumentPropertyType"
Cohesion: 0.18
Nodes (10): DocumentPropertyType, BOOLEAN, DATE, DATE_TIME, LIST, NUMBER, TAGS, TEXT (+2 more)

### Community 67 - "moveProjectFileToTrash"
Cohesion: 0.26
Nodes (18): completeProjectEntryTrashCleanup(), completeWorkspaceTrashCleanup(), createFolderExclusive(), moveProjectFileToTrash(), moveWorkspaceToTrash(), readWorkspaceTrashConfigRecovery(), readWorkspaceTrashReceipt(), setConfig() (+10 more)

### Community 68 - "WorkspaceMotion"
Cohesion: 0.16
Nodes (11): ImmediateMenuScheme, FiniteAnimationSpec, T, FiniteAnimationSpec, T, WorkspaceMotion, MotionScheme, fastoutslowineasing (+3 more)

### Community 69 - "FileManagerWorkspaceTrashTest"
Cohesion: 0.17
Nodes (3): FileManagerWorkspaceTrashTest, FolderSettingsDialogTest, SemanticsNode

### Community 70 - "WorkspaceSelectionDialogTest"
Cohesion: 0.26
Nodes (5): ImageComposeScene, Result, SemanticsNode, WorkspaceSelectionDialogTest, org

### Community 71 - "RemoteProjectBackupStore"
Cohesion: 0.11
Nodes (9): BackupWorkspaceEntry, ImmutableBackupObject, ImmutablePutResult, ALREADY_PRESENT, CREATED, MutableBackupObject, RemoteProjectBackupStore, WorkerRemoteStore (+1 more)

### Community 72 - "MainViewModelFileMoveTest"
Cohesion: 0.11
Nodes (4): FileManager, MainViewModel, MainViewModelFileMoveTest, MoveFixture

### Community 73 - "MainViewModelRenameTest"
Cohesion: 0.19
Nodes (3): MainViewModel, MainViewModelRenameTest, RenameRaceFixture

### Community 74 - "MarkdownStyleConfig.kt"
Cohesion: 0.16
Nodes (15): CalloutDecorationStyle, defaultCalloutStyles(), defaultMaterialBlockStyleConfig(), customFontFamily(), withFontFamily(), withPlatformUiSizes(), em, font (+7 more)

### Community 75 - ".workerKeepsFailedWorkAndANewWorkerCanResumeIt"
Cohesion: 0.23
Nodes (7): newProjectBackupId(), IllegalStateException, PlatformFile, ProjectRepositoryIdentity, ProjectRepositoryIdentityException, ProjectRepositoryIdentityStore, PersistentProjectBackupQueueTest

### Community 76 - "commonModule.kt"
Cohesion: 0.15
Nodes (12): booleanpreferenceskey, DocumentInfoPreferences, Flow, DocumentInfoPreferencesTest, databasesdir, datastore, map, module (+4 more)

### Community 77 - "MarkdownBlockTextFieldContent"
Cohesion: 0.14
Nodes (13): None, EditorDocumentValueCoordinator, Brush, Modifier, TextStyle, MarkdownBlockTextField(), MarkdownBlockTextFieldContent(), MarkdownBlockTextFieldM3() (+5 more)

### Community 78 - "MarkdownStyleConfig"
Cohesion: 0.35
Nodes (5): SpanStyle, MarkdownStyleConfig, InlineStyleScanner, IntRange, SpanStyle

### Community 80 - "10. 외부 변경 감지"
Cohesion: 0.25
Nodes (8): editorSessionKey(), setActive(), 10. 외부 변경 감지, 검사 순서, 검증 상태, 저장 경계, 허용된 트레이드오프, 활성 조건

### Community 81 - "FakeRemoteProjectBackupStore"
Cohesion: 0.39
Nodes (4): ProjectBackupService, FakeRemoteProjectBackupStore, ImmutableGate, ProjectBackupServiceTest

### Community 83 - "FileKey"
Cohesion: 0.18
Nodes (8): advancetimeby, awaitcancellation, FileKey, coroutinestart, runcurrent, runtest, seconds, standardtestdispatcher

### Community 84 - "GeneralSourceProperty"
Cohesion: 0.21
Nodes (12): renderTextUsingExistingStyle(), GeneralSourceProperty, GeneralSourceValue, Located, parseScalar(), decodeDoubleQuotedYamlTextOrNull(), decodeYamlText(), encodeDoubleQuotedYamlText() (+4 more)

### Community 85 - "CommitModels.kt"
Cohesion: 0.10
Nodes (18): CommitChangeKind, ADDED, DELETED, MODIFIED, RENAMED, RENAMED_AND_MODIFIED, CommitResult, FileLineDiff (+10 more)

### Community 86 - "제품 모델·로드맵 이전 안내"
Cohesion: 0.20
Nodes (10): 10. 확정 사항과 미결 사항, 1. 제품 방향, 2.1 탐색 상태와 일반 폴더 선택, 2. 콘텐츠와 폴더 모델, 5. 메타 인덱스, 미결, 비에디터 안정성 후속 후보 — 이전 다음 작업 초안에서 통합, 제품 모델·로드맵 이전 안내 (+2 more)

### Community 87 - "mergeKnownConfig"
Cohesion: 0.24
Nodes (7): mergeKnownConfig(), mergeObjects(), mergeValues(), moveJsonObjectEntry(), JsonConfigMergeSpec, JsonElement, JsonObject

### Community 88 - "workspaceSetup"
Cohesion: 0.21
Nodes (16): currentVaultAndDirectories(), fromBookmarkDataWithValidate(), getPreferences(), hasValidProjectConfig(), initialize(), ByteArray, openGeneralFolder(), requestOpenWorkspace() (+8 more)

### Community 89 - ".scan"
Cohesion: 0.22
Nodes (8): BlockRange, BlockType, BLOCKQUOTE, HORIZONTAL_RULE, IntRange, SpanStyle, MarkdownPatternScanner, ScanResult

### Community 90 - "DebouncedSaveCoordinator"
Cohesion: 0.27
Nodes (6): DebouncedSaveCoordinator, Job, 8. 편집과 저장 데이터 흐름, K, R, V

### Community 91 - "PersistentBackupWorker.kt"
Cohesion: 0.31
Nodes (6): BackupWorkResult, Completed, Failed, NoPendingWork, PersistentBackupWorker, RemoteProjectBackupStoreProvider

### Community 92 - "MaChum 현재 아키텍처"
Cohesion: 0.14
Nodes (14): 11. 블록 에디터 경계, 12. 은퇴한 workflow 코드 정리, 13. 빌드와 검증, 1. 프로젝트 개요, 2. 모듈 구조, 4. 의존성 주입과 상태, 5.1 `FileManager`, 5.2 expect/actual 경계 (+6 more)

### Community 93 - "BlockDecorationDrawer.kt"
Cohesion: 0.36
Nodes (11): drawBlockDecorations(), drawBlockquoteLines(), drawHorizontalRule(), drawInlineCodeBackgrounds(), getBoundingRect(), androidx, IntRange, Rect (+3 more)

### Community 95 - "WorkspaceLoadDiagnostics"
Cohesion: 0.31
Nodes (4): WorkspaceLoadDiagnostics, WorkspaceLoadTrace, timemark, timesource

### Community 96 - "8. Vault 폴더 사용 방식 — 2026-09-07"
Cohesion: 0.14
Nodes (14): 8.10 폴더 설정의 pending·태그·경로 이동 2차 — 2026-09-08, 8.11 파일 생성 요청의 작업 공간 정체성 — 2026-09-08, 8.12 사이드바 컴포지션·드롭다운 상태 정비 — 2026-09-08, 8.13 디렉터리·Project·Vault 이름 검증 공통화 — 2026-09-08, 8.1 Desktop 사용자 검증 완료, 8.2 자동 검증, 8.3 추가 화면 확인 대상, 8.4 초기화·저장 수명 정비 — 2026-09-08 (+6 more)

### Community 97 - "readVaultConfigUnlocked"
Cohesion: 0.71
Nodes (7): loadVaultConfig(), normalized(), persistVaultConfigUnlocked(), readVaultConfigUnlocked(), updateVaultConfig(), updateWorkspaceVaultConfig(), VaultConfig

### Community 99 - "3. 블록 모델과 직렬화"
Cohesion: 0.07
Nodes (18): Callout, Code, RawOrigin, CALLOUT, CODE, EMBED, TABLE, Table (+10 more)

### Community 100 - "3. 저장·외부 변경 경합"
Cohesion: 0.17
Nodes (12): 3. 저장·외부 변경 경합, LOAD-01 파일 읽기 실패와 재시도, NAV-01 빠른 최신 탐색 우선, SAVE-01 기본 debounce 저장, SAVE-02 다중 파일 pending save, SAVE-03 pending save 중 외부 변경, SAVE-04 자기 쓰기 mtime, SAVE-04A 외부 교체 직후 파일 전환 (+4 more)

### Community 101 - "MainActivity.kt"
Cohesion: 0.16
Nodes (11): AppAndroidPreview(), MainActivity, androidcontext, Bundle, ComponentActivity, enableedgetoedge, globalcontext, init (+3 more)

### Community 102 - "블록 기반 마크다운 에디터 설계"
Cohesion: 0.04
Nodes (45): 11. 구현 상태, 12. 필수 불변조건, 13. 검증 기준, 14. 다음 작업 권장 순서, 15. 완성 목표와 범위, 19. 결정 게이트, 1. 현재 아키텍처, 1차 범위에서 제외 (+37 more)

### Community 103 - "ClipEntryFactory.jvm.kt"
Cohesion: 0.28
Nodes (8): asawttransferable, clipEntryOf(), Clipboard, ClipEntry, readClipboardText(), dataflavor, stringselection, unsupportedflavorexception

### Community 104 - "CommitPlatform.android.kt"
Cohesion: 0.31
Nodes (8): createCommitStorageFile(), KoinComponent, Context, PlatformFile, AndroidFileContext, inject(), inject, toandroiduri

### Community 105 - "EditorInputTransformation"
Cohesion: 0.27
Nodes (5): BlockPrefixResult, EditorInputTransformation, experimentalfoundationapi, InputTransformation, textfieldbuffer

### Community 106 - "LineDiffCounter"
Cohesion: 0.33
Nodes (3): LineChangeCount, LineDiffCounter, LineDiffCounterTest

### Community 107 - "TextBlockEditor"
Cohesion: 0.17
Nodes (12): Brush, FocusRequester, Modifier, TextStyle, TextBlockEditor(), 5. TextBlock 편집, Smart Enter, 블록 승격 (+4 more)

### Community 108 - "RawMarkdownOutputTransformation"
Cohesion: 0.35
Nodes (7): IntRange, SpanStyle, MarkdownSpanApplication, Everywhere, OutsideRawZones, RawMarkdownOutputTransformation, OutputTransformation

### Community 109 - "8. 권장 구현 순서"
Cohesion: 0.18
Nodes (11): 1단계: 데이터 안정화, 2단계: 프로젝트 파일 탐색 최소 흐름, 3단계: frontmatter 자동화, 4단계: 편집 안전성과 생산성, 5단계: 커밋 MVP, 8. 권장 구현 순서, Vault 일반 폴더 탐색 단계 — 2026-09-07 구현, 비에디터 컴포지션·팝업 정비 후보 — 2026-09-08 (+3 more)

### Community 110 - ".menuScene"
Cohesion: 0.47
Nodes (3): DrawerDropdownMenuLayoutTest, ImageComposeScene, MenuFixture

### Community 111 - "architecture.md"
Cohesion: 0.31
Nodes (4): MaChum (맞춤), 문서, 실행과 검증, 현재 개발 단계

### Community 112 - ".commit"
Cohesion: 0.27
Nodes (6): CommitAndBackupResult, PlatformFile, PersistentCommitBackupEnqueuer, CommitBackupEnqueuer, PlatformFile, ProjectCommitBackupCoordinator

### Community 113 - "CustomColorScheme.kt"
Cohesion: 0.33
Nodes (6): ColorScheme, ColorFamily, withCustomFixedColors(), darkcolorscheme, immutable, lightcolorscheme

### Community 114 - "CommitObjectCodec"
Cohesion: 0.25
Nodes (4): CommitHead, CommitObjectCodec, KSerializer, T

### Community 115 - "MainViewModel.kt"
Cohesion: 0.06
Nodes (41): cancelCommitRequests(), closeCommitDiff(), CommittedFolderSettings, completeVaultSelection(), completeWorkspaceSelection(), confirmWorkspaceOpen(), discardPendingWrite(), dismissCommitDialog() (+33 more)

### Community 116 - "CommitFileSide"
Cohesion: 0.12
Nodes (17): CommitChange, CommitFileSide, AFTER, BEFORE, RestoreRollbackFailedException, blobHash(), requestFileContentRestore(), requestFileRestore() (+9 more)

### Community 117 - "MaChum P0 수동 테스트"
Cohesion: 0.20
Nodes (10): 1. 실행 전 준비, 2. 결과 기록 템플릿, 4. Android SAF provider 검증, 5. dissolve 상호작, 6. selection 상호작, 7. Undo/Redo, 8. Table 상호작, 9. 종료 기준 (+2 more)

### Community 121 - "ClipEntryFactory.android.kt"
Cohesion: 0.47
Nodes (5): clipdata, clipEntryOf(), Clipboard, ClipEntry, readClipboardText()

### Community 122 - "PolicyScrollbar.android.kt"
Cohesion: 0.60
Nodes (5): LazyListState, Modifier, ScrollState, PolicyLazyVerticalScrollbar(), PolicyVerticalScrollbar()

### Community 123 - "graphify reference: extra exports and benchmark"
Cohesion: 0.22
Nodes (8): graphify reference: extra exports and benchmark, Step 6b - Wiki (only if --wiki flag), Step 7 - Neo4j export (only if --neo4j or --neo4j-push flag), Step 7a - FalkorDB export (only if --falkordb or --falkordb-push flag), Step 7b - SVG export (only if --svg flag), Step 7c - GraphML export (only if --graphml flag), Step 7d - MCP server (only if --mcp flag), Step 8 - Token reduction benchmark (only if total_words > 5000)

### Community 125 - "PolicyScrollbar.kt"
Cohesion: 0.60
Nodes (5): LazyListState, Modifier, ScrollState, PolicyLazyVerticalScrollbar(), PolicyVerticalScrollbar()

### Community 126 - "DocumentSelection"
Cohesion: 0.13
Nodes (21): clipEntryOf(), Clipboard, ClipEntry, readClipboardText(), DocumentSelection, applySelectionAdjustment(), DocumentSelectionInputCapture(), documentSelectionShortcuts() (+13 more)

### Community 128 - ".flush"
Cohesion: 0.36
Nodes (3): Result, T, Registration

### Community 129 - "RawStyleToggle"
Cohesion: 0.39
Nodes (3): TextFieldState, RawStyleToggle, textrange

### Community 131 - "MaChum 프로젝트 파일 탐색 수동 테스트"
Cohesion: 0.25
Nodes (8): 1. 실행 전 준비, 2. 공통 탐색·저장, 3. Default 정책, 4. Default + Plot 정책, 5. 플랫폼 순서, 6. 최근 Desktop UI 점검 결과, 9. 종료 기준, MaChum 프로젝트 파일 탐색 수동 테스트

### Community 132 - ".calculatePosition"
Cohesion: 0.40
Nodes (4): IntOffset, IntRect, IntSize, LayoutDirection

### Community 133 - "WorkspaceDialog"
Cohesion: 0.40
Nodes (5): Create, Rename, Transition, Trash, WorkspaceDialog

### Community 135 - "6. 탐색과 파일 생성"
Cohesion: 0.29
Nodes (7): 6.1 Vault 폴더 선택과 일반 폴더, 6.2 폴더가 스와이프 스코프, 6.3 폴더 전환, 6.4 파일 생성, 6.5 넘버링, 6.6 하이라키 순서 편집과 파일명 계약, 6. 탐색과 파일 생성

### Community 136 - "EditorRecompositionDiagnostics.kt"
Cohesion: 0.20
Nodes (8): EditorRecompositionCounter, EditorRecompositionDiagnostics, EditorRecompositionSample, TrackEditorSelectionRecomposition(), EditorRecompositionCounterTest, nonskippablecomposable, sideeffect, State

### Community 139 - "WorkspaceSelection.kt"
Cohesion: 0.33
Nodes (5): ensureFolderIdentity(), SuspendedProjectSettings, WorkspaceDirectoryLists, WorkspaceFolderIdentity, WorkspaceOpenRequest

### Community 140 - "EditorBlock"
Cohesion: 0.08
Nodes (22): BlockOperations, DissolveResult, SplitResult, EditorBlock, Embed, HorizontalRule, EditorSelectionCoordinator, EscapeToParent (+14 more)

### Community 141 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 143 - "9. 커밋 기능 설계"
Cohesion: 0.29
Nodes (7): 9.1 확정된 저장 의미, 9.2 추적 범위와 파일 정체성, 9.3 변경 판정, 9.4 저장 위치와 일관성, 9.5 MVP에서 제외하는 Git 기능, 9.6 커밋 UI 역할 분리와 복원 정책(source of truth·확정), 9. 커밋 기능 설계

### Community 150 - "MaChum 작업 가이드"
Cohesion: 0.33
Nodes (5): MaChum 작업 가이드, Source of truth, 검증 명령, 작업 규칙, 주의할 코드 계약

### Community 151 - "graphify reference: query, path, explain"
Cohesion: 0.33
Nodes (5): For /graphify explain, For /graphify path, graphify reference: query, path, explain, Step 0 — Constrained query expansion (REQUIRED before traversal), Step 1 — Traversal

### Community 153 - "프로젝트 커밋 수동 테스트"
Cohesion: 0.33
Nodes (5): 2026-09-07 취소 안전성 자동 검증, 수동 확인 항목, 읽기 전용 검사와 원문 복원 — 2026-09-08, 커밋 이력 전용 화면·단일 파일 복원 검증, 프로젝트 커밋 수동 테스트

### Community 154 - "4. frontmatter 정책"
Cohesion: 0.33
Nodes (6): 4.1 원형 보존 계약, 4.2 `id`, 4.3 `tags`, 4.4 `aliases`, 4.5 `plot`, 4. frontmatter 정책

### Community 155 - "7. 기능 상태와 우선순위"
Cohesion: 0.40
Nodes (5): 7. 기능 상태와 우선순위, 난이도 정의, 로드맵, 상태 정의, 우선순위 정의

### Community 157 - "MainScreen.kt"
Cohesion: 0.05
Nodes (48): alignment, application, backhandler, circularprogressindicator, collectasstate, App(), WorkspaceSetup, GENERAL (+40 more)

### Community 159 - "MarkdownBlock"
Cohesion: 0.40
Nodes (5): Heading, HorizontalRule, MarkdownBlock, TextBlock, Undo/Redo MVP 직후 구조·파일 최적화 게이트

### Community 162 - "3. 프로젝트 파일 구조"
Cohesion: 0.40
Nodes (5): 3.1 원칙, 3.2 폴더 유형, 3.3 프로젝트 설정, 3.4 미설정 폴더의 확인과 프로젝트 설정, 3. 프로젝트 파일 구조

### Community 163 - "WorkspaceTrashKind"
Cohesion: 0.50
Nodes (4): WorkspaceTrashKind, FILE, FOLDER, WORKSPACE

### Community 164 - "에이전트 운영 원칙"
Cohesion: 0.50
Nodes (3): graphify, 서브에이전트의 graphify·ponytail 활용, 에이전트 운영 원칙

### Community 165 - "graphify reference: add a URL and watch a folder"
Cohesion: 0.50
Nodes (3): For /graphify add, For --watch, graphify reference: add a URL and watch a folder

### Community 166 - "graphify reference: commit hook and native CLAUDE.md integration"
Cohesion: 0.50
Nodes (3): For git commit hook, For native CLAUDE.md integration, graphify reference: commit hook and native CLAUDE.md integration

### Community 167 - "graphify reference: incremental update and cluster-only"
Cohesion: 0.50
Nodes (3): For --cluster-only, For --update (incremental re-extraction), graphify reference: incremental update and cluster-only

### Community 169 - "isValidProjectFileTitle"
Cohesion: 0.20
Nodes (6): isValidProjectEntryName(), isValidProjectFileTitle(), isValidProjectFolderName(), projectFileTitleError(), ProjectFileNameValidationTest, ProjectFileTitlePolicyTest

## Knowledge Gaps
- **306 isolated node(s):** `BLOB`, `TREE`, `COMMIT`, `CREATED`, `ALREADY_PRESENT` (+301 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 729 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **33 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Bookmarks` connect `Bookmarks` to `GeneralSourceService`, `test`, `MainViewModelHierarchyTest`, `ProjectCommitServiceTest`, `FileManagerWorkspaceTest`, `MainViewModelLifecycleTest`, `MainViewModelWorkspaceSelectionTest`, `MainViewModelFolderSettingsTest.kt`, `WorkspaceKind`, `ProjectConfig`, `FileManager.kt`, `MainViewModelFileTrashTest`, `GeneralSourceService.kt`, `WorkspaceSaveCoordinator`, `MainViewModelFileCreationRequestTest`, `FolderConfig`, `.withFolderRenameFixture`, `FileManagerProjectFileTrashTest`, `ProjectFile`, `FileManagerWorkspaceLifecycleTest`, `moveProjectFileToTrash`, `FileManagerWorkspaceTrashTest`, `WorkspaceSelectionDialogTest`, `MainViewModelFileMoveTest`, `MainViewModelRenameTest`, `.workerKeepsFailedWorkAndANewWorkerCanResumeIt`, `FakeRemoteProjectBackupStore`, `workspaceSetup`, `MainViewModel.kt`?**
  _High betweenness centrality (0.088) - this node is a cross-community bridge._
- **Why does `FolderConfig` connect `FolderConfig` to `hasPendingPropertyDefinitionSync`, `CommitHistoryScreen.kt`, `ProjectNavigationDrawer.kt`, `HierarchyRowsCompositionTest`, `test`, `MainViewModelHierarchyTest`, `ProjectCommitServiceTest`, `WorkspaceSelection.kt`, `FolderDirectControls.kt`, `FileManagerWorkspaceTest`, `FileManagerFolderTest.kt`, `FolderKey`, `.remember`, `MainViewModelWorkspaceSelectionTest`, `MainViewModelFolderSettingsTest.kt`, `WorkspaceKind`, `ProjectConfig`, `FileManager.kt`, `FolderDirectControlsTest.kt`, `Bookmarks`, `MainScreen.kt`, `FolderDirectControlsTest`, `CalloutBlockEditor.kt`, `PlatformFile`, `GeneralSourceService.kt`, `withManagedTagChanges`, `.withFolderRenameFixture`, `ProjectFileOrdering.kt`, `ProjectFile`, `FolderSettingsService.kt`, `FileManagerWorkspaceTrashTest`, `MainViewModelFileMoveTest`, `workspaceSetup`, `MainViewModel.kt`?**
  _High betweenness centrality (0.065) - this node is a cross-community bridge._
- **Why does `FileKey` connect `FileKey` to `hasPendingPropertyDefinitionSync`, `ProjectNavigationDrawer.kt`, `ProjectCommitService`, `test`, `FolderDirectControls.kt`, `FolderKey`, `.remember`, `MainViewModelFolderSettingsTest.kt`, `WorkspaceKind`, `ProjectConfig`, `FolderDirectControlsTest.kt`, `MainScreen.kt`, `GeneralSourceService.kt`, `HierarchyOrderDraft.kt`, `ProjectIndexer.kt`, `DocumentInformationSectionTest`, `ProjectFile`, `EditorFocusCoordinator`, `FolderSettingsService.kt`, `moveProjectFileToTrash`, `10. 외부 변경 감지`, `DebouncedSaveCoordinator`, `MaChum 현재 아키텍처`, `3. 저장·외부 변경 경합`, `MainViewModel.kt`?**
  _High betweenness centrality (0.062) - this node is a cross-community bridge._
- **Are the 58 inferred relationships involving `Bookmarks` (e.g. with `.withAutoTagViewModel()` and `.applyDefaultOrderRejectsBeforeMutationWhenProjectConfigIsNotLoaded()`) actually correct?**
  _`Bookmarks` has 58 INFERRED edges - model-reasoned connections that need verification._
- **Are the 20 inferred relationships involving `FolderConfig` (e.g. with `applyDocumentPropertyDefinition()` and `DocumentPropertyDefaults`) actually correct?**
  _`FolderConfig` has 20 INFERRED edges - model-reasoned connections that need verification._
- **Are the 40 inferred relationships involving `ProjectCommitService` (e.g. with `.assertMovingFileRestoreCancellation()` and `.assertRepeatedDirtyFileRestore()`) actually correct?**
  _`ProjectCommitService` has 40 INFERRED edges - model-reasoned connections that need verification._
- **Are the 22 inferred relationships involving `ProjectFile` (e.g. with `.projectFileRenameRejectsInvalidAndDuplicateNamesWithoutAutoSuffix()` and `.projectFolderFileBookmarkRestoresByRelativePath()`) actually correct?**
  _`ProjectFile` has 22 INFERRED edges - model-reasoned connections that need verification._