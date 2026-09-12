# MaChum 현재 아키텍처

> 역할: 현재 코드 구조·API·저장 동시성·플랫폼 기술 계약의 정본. 제품 규칙과 화면 명령은 연결된 설계 정본을 따른다
> 마지막 검토: 2026-09-12 (UI 상태 구독·공통 컴포지션 점검)
> 제품 규칙: [제품 도메인](design/product-domain.md); 우선순위: [작업 목록](planning/backlog.md)
> 목표 화면·명령 구조(부분 구현): [information-architecture.md](information-architecture.md)
> 마크다운 에디터 내부 설계: [markdown-editor.md](markdown-editor.md)

> 후속 생성 UX: [파일·폴더 생성 설계](file-folder-creation-design.md). Vault 일반 폴더 생성은 1차 구현했고,
> 내부 파일/폴더 즉시 생성·General 기본값·선택적 인라인 이름 변경을 반영했다. aliases 비기록은 유지한다.

> [작업 공간 수명 설계](design/workspace-lifecycle.md)의 모드 전환·루트 설정 UI 제거를 소스에 반영했다. 검증 상태는 [단순화 기록](records/implementation-2026-09-10-workspace-simplification.md)을 따른다.
> [태그·폴더 제어 설계](design/tags-and-folder-controls.md)의 직접 편집 UI와 문서 정보 구현 상태는 [태그 UX 구현 기록](records/implementation-2026-09-11-tag-ux.md)을 따른다.

## UI 상태 구독·공통 컴포지션

화면 파일은 다음 책임으로 배치한다. 테스트도 같은 패키지 구조를 따른다.

```text
screen/
├── vaultScreen/               Vault 선택
├── projectScreen/             작업 공간 선택·프로젝트 이름 변경
├── mainScreen/                MainScreen·편집 상단·문서 정보·ViewModel·저장 조율
│   └── leftSideMenu/          사이드바·하이라키·폴더 제어·source 구분
├── commitScreen/              변경사항·이력·비교·복원 확인 UI
└── common/                    여러 화면에서 사용하는 팝업·메뉴·플랫폼 UI
```

기존 mainComposition 패키지는 mainScreen으로 대체했다. 커밋 도메인/저장소는 기존 commit 패키지에, 커밋 상태 DTO는 MainViewModel과 함께 둔다. 파일 위치 정비를 상태·저장 책임 분리 완료로 해석하지 않는다. DrawerDropdownMenu/DrawerMenuItem은 다른 화면에서도 사용하므로 common에 둔다. 이동 목록과 검증은 [화면 파일 정리 기록](records/screen-layout-2026-09-12.md)을 따른다.

UI는 필요한 영역에서 상태를 수집하고 읽는다. App의 선택 화면 전용 오류 구독은 해당 화면 분기에, MainScreen의 작업 공간 목록 구독은 drawerContent에 둔다. StateFlow를 단순히 하나로 합쳐 호출 개수를 줄이는 것을 성능 최적화로 보지 않는다. 화면 구독과 저장·mutation coordinator 수명은 분리한다.

현재 중복과 다음 정비의 우선순위·검증 경계는 [2026-09-12 점검 기록](records/architecture-review-2026-09-12.md)을 따른다.

## 작업 공간 선택·상위 이동 정리

App은 같은 MainViewModel을 쓰되 이전 MainScreen을 SaveableStateHolder로 별도 보관하지 않는다.
`openWorkspaceSelection()`은 저장 fence 안에서 flush 성공 후 편집 캐시·세션을 정리하고 작업 공간 선택을 표시한다.
flush 실패는 화면·본문·세션을 보존한다. 일반 bookmark·마지막 Project 기록은 삭제하지 않는다.
화면 간 이동·명령 배치는 [IA](information-architecture.md), 전환의 사용자 계약은 [작업 공간 수명](design/workspace-lifecycle.md)을 따른다.

`completeVaultSelection()`은 유효한 Vault 선택 성공 후 해당 작업 공간 목록을 표시한다. 네이티브 선택 취소는 Vault 화면에 머문다.

ProjectSelectionScreen은 유형별 목록·생성·명시 대상 이름/분류 관리를 담당한다. 같은 대상을 고르더라도 `openWorkspaceChoice()`의 존재·분류 검증과 일반 열기를 거친다.
`completeWorkspaceSelection()`은 해당 대상의 목록을 다시 읽고 선택 화면을 닫는다. 선택 화면에 있는 동안 bookmark collector가 편집 캐시를 다시 만들지 않는다.
`runWorkspaceSelectionAction()`은 저장·mutation fence 및 Vault/요청 세대 검사를 유지한다.

Android Back 처리와 저장·생성·복원 중 보호는 `WorkspaceBackHandler` 및 각 화면의 처리 상태에 연결된다.

최신 검증과 미실행 화면 항목은 [IA 기능 제거 기록](records/implementation-2026-09-10-ia-trim.md)을 따른다.
모바일 치수는 [1차 기록](records/implementation-2026-09-09-workspace-mobile.md)을 따른다. 후속 내부 즉시 생성의 검증은 [생성 구현 기록](records/implementation-2026-09-10-instant-creation.md)을 따른다.

---

## 작업 공간 분류 전환의 현재 계약

- `ProjectSelectionScreen`은 `runWorkspaceSelectionAction()`의 저장·mutation fence 안에서 `FileManager.transitionWorkspace(vault, directory, expectedSetup, targetKind)`를 호출한다.
- 일반 재열기용 bookmark는 활성 편집 여부가 아니다. 전환은 명시 대상·현재 Vault·기대 분류를 검증하고 성공 후 선택 화면에서 목록만 갱신한다.
- VaultConfig.suspendedProjects에 원 설정 텍스트·프로젝트명·루트 identity·폴더별 identity와 설정을 보관한다. 보관 기록이 있는 General은 유효한 `.machum.json`이 남아 있어도 관리 Project로 열지 않는다.
- 루트와 직속 폴더의 `.machum-folder-id.json`으로 rename/경로 재사용을 구분한다. ID 없는 새 폴더는 복귀 시 General, 중복/손상 ID는 오류다. 삭제된 항목은 복원하지 않는다.
- 복귀는 존재하는 폴더 설정과 frontmatter ID 기반 fileIds 경로를 재구성한다. 프로젝트명 태그/누락 ID 보완과 사용자 태그 보존은 기존 Project 규칙을 따른다. 커밋 이력을 문서에 덮어쓰지 않는다.
- 쓰기 전 원문 비교·부분 실패 보상·재시도 검증을 적용한다. 최초 General 대상의 동일 경로 외부 교체 및 SAF의 비교 직후 외부 쓰기는 완전히 원자적으로 차단하지 못한다.
- 기존 식별 파일의 이름 충돌·손상은 덮어쓰지 않고 실패로 처리한다. General 전환 준비가 실패하면 관리 모드는 바꾸지 않는다. 준비 중 만든 유효 식별 파일은 남을 수 있으며 재시도에 재사용한다.
- 보관된 원 Project 설정이 없거나 손상되면 복귀를 실패 처리하며 조용히 초기화하지 않는다. General에서 이름이 바뀐 경우 이전 관리 프로젝트명 태그를 현재 이름으로 갱신하고 사용자 태그를 보존한다. 실패한 쓰기는 보상 대상이다.
- 분류 조회는 보통 설정을 생성하지 않는다. 예외는 과거 DataStore의 General 분류를 현재 Vault로 옮기는 일회성 마이그레이션이다.
- `WorkspaceChoiceList`가 최초 down의 대상을 고정하고 목록 수명으로 드래그·가장자리 스크롤을 관리한다. 드롭과 메뉴는 같은 확인창을 연다.

## 1. 프로젝트 개요

MaChum(맞춤)은 Android와 Desktop(JVM)을 대상으로 하는 Compose Multiplatform 마크다운 집필 앱이다. Obsidian과 같은 vault를 공유하면서 원고 편집과 장기적인 버전 추적을 제공하는 것이 목표다.

제품 목적과 탐색 범위는 [제품 도메인](design/product-domain.md), 화면 책임은 [IA](information-architecture.md)를 따른다.

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
├── entity/                ProjectConfig 등 앱 데이터 모델
├── external/              파일 시스템, NoteFile, 커밋 저장소
├── markdown/              블록 기반 마크다운 에디터
├── screen/                vault/project/main/editor 화면
└── theme/                 Material 테마와 색상·타이포그래피
```

### 플랫폼 진입점

- Android: `androidApp/.../MainActivity.kt`
  - Koin은 프로세스에서 한 번, FileKit은 Activity에 연결해 초기화
  - `App()`을 Android Activity에 표시
- Desktop: `desktopApp/.../main.kt`
  - Koin과 FileKit 초기화
  - 하나의 Window에서 `App()`을 유지하고 vault 미선택 시 picker 내용을 표시
  - vault 선택 여부에 따라 창 기본 크기·resize 허용 여부만 변경

---

## 3. 앱 화면 흐름

`App.kt`는 navigation library 없이 `FileManager.bookmarks`를 기준으로 화면을 선택한다.

2026-09-08부터 FileManager 생성자는 비동기 작업을 시작하지 않는다. App의 `LaunchedEffect`가
`initialize()` 완료를 기다린 뒤 선택·편집 화면을 표시한다. 초기화는 기존 `workspaceOpenMutex`로 직렬화하고
FileManager 인스턴스당 성공한 복원은 반복하지 않는다. 실패 시 오류·재시도 화면을 표시하며, 취소는 호출자에게
전달한다. 실패·취소 시 중간 메모리 상태만 비우고 저장된 선택 정보는 재시도를 위해 유지한다.
초기화 도중 인덱싱의 취소도 파일 오류로 소비하지 않는다. Desktop Window를 교체하지 않으므로 Vault 복원 직후
초기화 coroutine이 자기 화면 교체로 취소되는 문제를 피한다. 이 복원과 사용자가 요청한 `activateProject`의
기존 완료 보장 경계는 별개다.
같은 Window에 남은 ViewModel이 화면 밖에서도 외부 파일을 폴링하지 않도록, MainScreen의 포커스 수집이
취소되면 활성 상태를 false로 되돌린다.

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

Vault 선택은 Android와 Desktop이 같은 Material 3 콘텐츠를 사용한다. 현재 라이브 흐름에는 WorkflowScreen이나 WorkflowSelectionScreen이 없다.

현재 흐름은 `Bookmarks.projectData`를 탐색 루트로 재사용하고 `workspaceKind`로 관리 Project와 일반 폴더를 구분한다.
별도 activeWorkspace 모델은 도입하지 않았다.

```text
vaultData 없음
  → VaultSelectionScreen

projectData 없음
  → ProjectSelectionScreen에서 Vault 직속 탐색 루트 선택

workspaceKind == PROJECT
  → ProjectConfig·인덱싱 상태에 따른 처리 → MainScreen → 초기 기준점 준비 gate

workspaceKind == GENERAL
  → 인덱싱·설정 쓰기 없이 MainScreen의 일반 폴더 mode
```

이 분기는 구현됐다. 미설정 폴더는 선택 요청/사용 방식 확인 경로를 거치며, 일반 폴더 선택만으로
`.machum.json`을 생성하지 않는다. Vault 직속 일반 폴더 생성은 작업 공간 선택 화면에 후속 구현했다.

`MainScreen`은 다음 역할을 가진다.

- `MainViewModel.hierarchyState`에서 파생한 `fileList`를 `HorizontalPager` 페이지로 표시
- 페이지 정지 시 선택 파일을 bookmark에 반영
- top bar에서 파일명 변경
- `LocalWindowInfo.isWindowFocused`를 ViewModel에 전달해 외부 변경 폴링 활성화

파일 목록·저장·외부 변경 추적은 `FileKey`를 authority로 사용한다. Pager와 편집기 composition은 별도의 런타임
`editorSessionKey`를 사용하며, 앱 내부 파일명 변경 때 기존 `FileKey`에서 새 `FileKey`로 이 session key를 이동한다.
따라서 경로가 바뀌어도 같은 편집기 문서 상태를 유지하고, 실제 외부 교체·삭제 때만 session을 폐기한다. 최초 방출을 제외한
`settledPage` 변화만 사용자 선택으로 전달한다.
`currentIndex`에 의한 파일 생성·dropdown 전환·bookmark 복원은 pager를 목표 페이지로 이동시키고, 이동 전 임시 페이지가
선택과 bookmark를 덮어쓰지 않게 한다. Project·Folder·File 선택은 `LatestNavigationGate`가 generation과 mutex로
직렬화한다. 새 요청이 들어오면 이전 요청의 늦은 결과는 화면에 적용하지 않고, 이미 시작된 bookmark 쓰기는 최신 요청이
마지막에 덮어쓴다. `MainScreen`은 파일별 `Loading`·`Loaded`·`Error` 상태 맵을 한 번만 구독하며 `EditorPage`는 ViewModel을 직접
조회하지 않는다. 읽기 실패 시 빈 편집기로 오인하지 않고 오류와 재시도 버튼을 표시한다. 따라서 Pager 위치가 바뀌거나 파일 목록이 재정렬되어도
문서 상태와 지연 effect의 수명은 명시적인 앱 내부 rename을 제외하면 `FileKey` 경계를 넘지 않는다.

커밋·복원 명령과 적용 범위는 [제품 도메인 §9.6](design/product-domain.md#96-커밋-ui-역할-분리와-복원-정책source-of-truth확정)을 따른다.
이력 화면은 기존 Editor를 composition에서 제거하지 않고 불투명한 자체 `Scaffold`로 덮어 editor session과 Undo 수명을 유지한다.
커밋 진입은 pending save flush 후 `preview()`만 읽고 `history()`를 함께 계산하지 않는다. 이력의 diff는 parent tree와 선택 tree의 blob으로 계산한다. 서랍 트리는 안정 key를 가진 단일 `LazyColumn`을 사용한다.

상태도 화면 역할에 맞춰 `CommitCreateUiState`와 `CommitHistoryUiState` 두 개로만 나눈다. 전자는 preview·loading·
committing·error, 후자는 history·선택 commit/file·diff·restore·loading·error를 소유한다. 기존
`CommitChangeRow`, 줄 diff 본문, 복원 확인 dialog와 저장소 구성요소는 재사용하고 `ProjectCommitService`에
파일 복원과 HEAD snapshot·parent 적용 동작을 제공한다. UI 파일은 `CommitDialog.kt`와
`CommitHistoryScreen.kt`를 기본 경계로 하고 별도 navigation/repository 계층은 추가하지 않는다.

작업 공간 선택의 행 메뉴에서 `이름 변경`은 명시한 작업 공간 디렉터리명을 변경한다. 변경 전에 대기 중인 파일 저장을
flush하고, 성공하면 Project·선택 파일 bookmark를 새 경로로 교체한다. 태그 변경 의미는 [frontmatter 정책](design/product-domain.md#4-frontmatter-정책)을 따른다. 기존 Project 이름, 잘못된 이름과
대소문자만 다른 이름은 플랫폼 간 일관성을 위해 거부한다.

TopBar의 파일 이름 변경은 새 파일 생성과 같은 제목 검증을 재사용한다. 잘못된 문자·예약어·`.md` 직접 입력·앞뒤 공백과
현재 폴더의 중복 이름은 실제 파일 작업 전에 거부한다. 플랫폼 rename도 자동 suffix 대신 정확한 대상 이름만 사용하며,
실패하면 사용자가 입력한 제목과 편집 상태를 유지한 채 오류를 표시한다. 편집 시작 시 대상 `ProjectFile`을 고정해
focus out 도중 현재 선택이 잠시 비어도 다른 파일을 rename하지 않는다. 제목이 바뀌지 않은 focus out은 파일 작업 없이
편집 모드만 끝낸다. 성공 시 파일 목록, 선택 index, 로드된 `NoteFile`, pending save, mtime, bookmark와 editor session key를
같은 임계 구역에서 즉시 새 key로 옮기므로 외부 변경 polling이나 파일 재읽기를 기다리지 않는다.

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
WORKSPACE_KIND    PROJECT | GENERAL
```

UI와 비즈니스 상태는 주로 `StateFlow`를 사용한다. Compose에서는 `collectAsState()`로 구독한다.

### 4.1 Vault 폴더 사용 방식

`Bookmarks.projectData`는 현재 Vault 직속 탐색 루트로 재사용하고 `workspaceKind`가
`PROJECT` 또는 `GENERAL`을 나타낸다. 별도의 이중 선택 상태나 파일 저장 계층은 도입하지 않았다.

- `workspace_kind`: 마지막 탐색 루트의 사용 방식
- `.machum-vault.json`: Vault 직속 상대 이름으로 된 `generalFolders`와 `lastProject`
  파일이 없으면 일반 폴더 확정 또는 마지막 Project 최초 기록 때 생성한다. General 폴더 내부에는 Project 설정 파일을 새로 만들지 않는다. 기존 Project에서 전환한 General의 보관 설정은 이와 구별한다.
- 과거 DataStore `general_folders` 절대 경로는 선택한 Vault의 실제 직속 폴더와 정확히 일치할 때만
  `.machum-vault.json`으로 한 번 이전한 뒤 제거한다.
- `workspaceOpenRequest`: 미설정 위치의 확인창 상태(대상, 처리 중 여부, 오류)
- `requestOpenWorkspace`: 프로젝트 선택 화면·사이드바의 공통 진입점. 설정 유무/일반 폴더 선택 기억을 읽고 판단한다.
- `confirmWorkspaceOpen`: 프로젝트 설정 또는 일반 폴더 사용을 확정한다. mutex로 중복 요청을 직렬화한다.
- `listWorkspaceDirectories`: Vault 설정을 한 번 읽어 관리 Project·미분류 후보는 하단 목록으로,
  지정한 일반 폴더는 상단 작업 위치 목록으로 나눈다.
- 상단 `프로젝트` 선택은 `lastProject`가 유효하면 바로 열고, 없거나 삭제됐으면 현재 Vault만 남겨
  Project 선택 화면으로 이동한다.
- 일반 폴더에서는 기존 ProjectConfig 모양의 General 설정을 메모리에만 둬 기존 파일 탐색을 재사용한다.
- Project→일반 폴더 전환 전에는 `WorkspaceSaveCoordinator`가 pending save를 flush한다.
- ViewModel은 경로 또는 kind가 바뀌면 기존 파일 cache·selection·commit 상태를 정리한다.
- 일반 폴더 선택은 재시작 시 복원되고, 빈 폴더 진입은 샘플 문서를 생성하지 않는다.

```json
{
  "generalFolders": ["소재 정리", "필사"],
  "lastProject": "당신을 구하던 삶"
}
```

설정 파일이 손상된 경우 자동으로 빈 설정을 덮어쓰지 않는다. `loadVaultConfig()`의 일부 조회에는 fallback이 있지만,
`workspaceSetup()`은 Vault 설정을 엄격하게 다시 읽으므로 손상 시 분류 확인·열기가 실패할 수 있다.
자체 `.machum.json`이 있어도 Project 접근을 보장하는 계약은 아니다. 설정 문제를 해결한 뒤 재시도해야 한다.

---

## 5. 파일 시스템 계층

### 5.1 `FileManager`

`external/FileManager.kt`가 파일 작업의 공통 진입점이다.

주요 책임:

- vault/project/file bookmark 저장과 복원
- project와 `.md` 파일 목록 조회
- 파일 생성·읽기·쓰기·삭제·rename
- `.machum.json` 생성·직렬화
- `NoteFile` 순수 읽기와 생성·인덱싱 시 명시적인 ID/Project 태그 보정
- 파일 mtime 조회

### 5.2 expect/actual 경계

공통 선언과 플랫폼 actual을 사용한다.

| 기능 | Android | Desktop |
|---|---|---|
| 파일·폴더 생성 | SAF `DocumentFile` | FileKit/JVM File |
| rename | `DocumentFile.renameTo` | `java.io.File.renameTo` |
| 권한 확인 | persisted URI permission | 항상 true |
| mtime | `COLUMN_LAST_MODIFIED` | `File.lastModified()` |
| clipboard entry | Android `ClipData` | AWT `StringSelection` |
| vault picker UI | FileKit 네이티브 디렉터리 picker | FileKit 네이티브 디렉터리 picker |

Android SAF의 mtime은 provider나 클라우드 동기화 앱에 따라 갱신 시점이 달라질 수 있다.

### 5.3 현재 Project 내부 폴더·파일 목록

현재 라이브 흐름이 지원하는 저장 구조는 `Vault → Project → File 또는 Folder → File`로 고정되어 있다.

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
- 같은 앱 실행 중에는 폴더별 마지막 선택 `FileKey`를 `MainViewModel` 메모리에서만 기억하고, 폴더로 돌아올 때 유효한 파일이면 복원

책임 흐름은 `FileManager` 직접 조회 → `MainViewModel`의 현재 폴더·파일 상태 → `MainScreen` 표시로 제한한다.
`MainViewModel`은 하나의 `hierarchyState`를 노출한다. `folderList`와
`folderContents: Map<FolderKey, HierarchyFolderContent>`가 루트·모든 직속 폴더의 정렬된 파일 및 Plot 항목을 담고,
현재 폴더 key와 선택 FileKey로 `currentFolder`, pager의 `fileList`와 `currentIndex`를 파생한다.
Drawer와 편집 영역은 이 snapshot을 함께 사용하며 현재 폴더의 파일 목록을 중복 저장하지 않는다.
폴더별 선택 기억은 별도 저장소나 `.machum.json` 필드를 만들지 않는다. 앱 재실행 시에는 기존 단일 파일 bookmark만 복원하며,
파일·폴더 rename 및 삭제 시 런타임 key만 함께 정리한다.
트리의 명령·행 구성과 생성 진입점은 [IA](information-architecture.md)를 따른다.
`LazyColumn` 하나와 folder/stage/file 안정 key를 사용하며 폴더별 스크롤 컨테이너를 만들지 않는다. 다른 폴더 파일 선택은 `currentFolder`와 pager 목록을 같은 snapshot에서 전환한다.
내부 생성 이름 폼은 제거했다. `CreateFileAction`은 즉시 요청 또는 Plot 단계 선택을 제공하고 `FolderInlineRename`은 생성 후 선택적 이름 변경을 담당한다. 생성 UX·기본값은 [생성 설계](file-folder-creation-design.md), 파일명 검증은 `ProjectFileTitlePolicy`를 따른다.

디렉터리 설정 화면에서는 이름과 설정을 함께 변경할 수 있다. 이름 변경 전 대상 폴더의 pending save를 모두 flush하고,
실제 디렉터리 rename → `.machum.json`의 폴더 key와 `fileIds` 상대 경로 변경 → 선택 파일 bookmark 변경 → 자동 태그
동기화 순으로 처리한다. 이 화면에서 Project 루트는 이름 변경 대상이 아니며, 기존 이름·대소문자만 다른 이름·잘못된 이름은 거부한다.
설정 기록이나 bookmark 갱신이 실패하면 실제 디렉터리명과 이전 설정 복원을 시도한다.
디렉터리 삭제는 폴더 컨텍스트 메뉴 또는 설정 화면의 삭제 버튼에서 별도 확인 다이얼로그를 거치는 영구 작업이다. 삭제 전에 직속 Markdown
파일 수를 표시하며, Project 루트는 삭제할 수 없다. 앱 탐색 범위 밖의 하위 폴더 또는 Markdown 이외 파일이 하나라도
있으면 보이지 않는 데이터 손실을 막기 위해 삭제를 차단한다. 허용된 삭제는 pending save flush → 설정 key와 해당
`fileIds` 제거 → 선택 bookmark 정리 → 실제 Markdown 파일과 디렉터리 삭제 순서로 처리한다. 실제 삭제가 실패하면
이전 설정과 bookmark 복원을 시도한다.
번호·Plot·미분류 파일의 생성과 정렬 규칙은 [제품 도메인 §6](design/product-domain.md#6-탐색과-파일-생성), 생성 UX 목표는 [생성 설계](file-folder-creation-design.md)를 따른다.

`CreateFileRequest`는 workspace 위치·종류, `FolderKey`, 초기 `PlotStage`를 고정하며 VM은 제출 즉시 생성 gate를 닫고 기존 mutation/flush fence 뒤 대상을 다시 검증한다. 단계 목록의 composition 수명은 workspace·폴더·Plot 설정에 묶인다.
`createProjectFile(..., initialNote)`는 필수 메타데이터를 먼저 준비해 저장하며 JVM은 `CREATE_NEW`로 기존 경로를 덮어쓰지 않는다. `FileCreationIncompleteException`은 실제 생성 파일과 내용 snapshot을 보존하고 `retryProjectFileCreation`은 외부 변경 여부를 확인해 같은 파일만 완료한다. SAF의 마지막 비교와 쓰기 사이 외부 변경은 원자적으로 막지 못한다.
`createProjectFolder`는 충돌 회피·exclusive 생성 후 Project에서만 설정을 기록한다. 설정 실패 시 이전 원문 복구와 새 빈 폴더 정리를 시도하며, General은 보존된 Project 설정에 의존하지 않는다.
`updateDirectoryAndAwait`는 기존 문서 쓰기까지 완료해야 성공한다. 설정 창은 완료를 기다리며 입력을 잠그고 실패를 표시한다. 물리 rename/config는 성공했으나 본문 쓰기가 실패한 동일 요청은 기존 pending을 flush한 뒤 완료하며 설정을 다시 적용하지 않는다. 이 임시 완료 기록은 다른 요청·작업 공간 전환 시 폐기한다.
자동 검사와 실제 화면 검증 경계는 [생성 구현 기록](records/implementation-2026-09-10-instant-creation.md)을 따른다.

`SidebarWorkspaceMenus.kt`의 두 선택기는 기존 Row 자식 Box에 trigger와 DropdownMenu를 함께 두어 팝업 앵커를 유지한다.
메뉴와 폴더 컨텍스트 상태는 Drawer가 소유하며 workspace 전환 시 해제한다. 별도 Actions 객체나 상태 계층은 두지 않는다.
실제 팝업 위치·키보드 확인은 [사이드바 수동 검증](folder-zone-manual-test.md#812-사이드바-컴포지션드롭다운-상태-정비--2026-09-08)을 따른다.

비에디터 후보 4는 디렉터리 생성·설정, Project 생성·이름 변경, Vault 생성의 입력 오류 계산을
`screen/common/DirectoryNameValidation.kt`의 작은 순수 함수 `directoryNameError`로 공유한다.
빈 입력의 오류 숨김, 앞뒤 공백 거부, 기존 폴더명 문법 검사, 현재 이름 제외와 lowercase 기준 중복 검사를
한곳에서 처리한다. 파일 제목과 달리 `.md`로 끝나는 디렉터리 이름도 허용한다.
화면별 문구, 빈 값 제출 차단, 변경 없는 디렉터리 설정 저장과 Project rename 차단, `allowRename=false`,
busy·Enter 중복 제출 gate와 callback은 호출자가 유지한다. Vault에는 기존 이름 조회나 중복 정책을 추가하지 않는다.
Unicode 변환으로 case-only와 중복 조건이 겹치는 경우 case-only 오류를 우선한다. 이때 Project rename의
표시 문구 우선순위만 통일되며 입력을 거부하는 결과는 동일하다.
파일시스템 계층은 기존처럼 입력을 trim한 뒤 모든 직속 항목의 충돌을 `ignoreCase`로 재검사한다.
UI 검사는 이 최종 검사를 대신하지 않으며 FileManager·ViewModel의 쓰기 경계와 정책은 변경하지 않는다.
화면 확인은 [이름 검증 수동 절차](folder-zone-manual-test.md#813-디렉터리projectvault-이름-검증-공통화--2026-09-08)를 따른다.

순서 변경의 허용 대상·번호 계약은 [제품 도메인 §6.6](design/product-domain.md#66-하이라키-순서-편집과-파일명-계약)을 따른다. drag 중에는 메모리 draft만 바꾸고 drop 때 폴더 단위로 한 번 적용한다.

저장은 대상 폴더 pending save flush → 현재 directory snapshot 재검증 → 충돌 검증 → 고유 임시 이름으로 1차 rename →
Plot frontmatter 갱신 → 최종 이름으로 2차 rename 순서다. 기존 Plot 일괄 rename의 충돌 회피 경로를 공통 batch primitive로
추출해 Default와 공유하고 평행한 rename 구현을 만들지 않는다. 성공 시 `ProjectConfig.fileIds`, bookmark, load state, mtime,
pending save, 폴더별 선택 기억, editor session key와 `hierarchyState.folderContents`를 새 `FileKey`로 한 임계 구역에서 이동한다.
파일 선택과 pager 탐색의 bookmark 쓰기도 같은 파일 조정 mutex로 직렬화해 drop 저장과 겹친 오래된 경로가 새 bookmark를
덮어쓰지 않게 한다. 따라서 선택된 문서 편집기는 다시 읽지 않는다. 실패하면 가능한 범위에서 이전 이름과 frontmatter로 rollback하고 화면 draft는
폐기해 원래 하이라키 순서로 되돌린 뒤 해당 폴더 아래에 inline 오류를 표시한다. commit은 frontmatter `id`가 같으므로 이 결과를
삭제+추가가 아닌 rename 또는 rename+수정으로 판정한다.

프로젝트 기본 폴더·루트 설정은 [제품 도메인 §3](design/product-domain.md#3-프로젝트-파일-구조)을 따른다.
새 Project 생성은 실제 기본 폴더와 `FolderConfig`를 함께 기록하고, 실패하면 이번 시도가 만든 항목만 정리한다.
기존 설정의 자동 마이그레이션은 하지 않는다.

### 5.4 미설정 폴더 선택과 프로젝트 설정

`listProject`는 Vault의 모든 비숨김 직속 폴더를 반환한다.
`workspaceSetup`은 대상 내부 디렉터리를 수정하지 않고 현재 설정을 읽는다. Vault 설정의 일회성 이전 쓰기 예외는 [전환 계약](#작업-공간-분류-전환의-현재-계약)을 따른다.

설정 유무·보관 상태에 따른 전환 의미는 [작업 공간 수명](design/workspace-lifecycle.md)을 따른다.
General 분류는 Vault 루트 `.machum-vault.json`에 기록한다. 일반 조회와 레거시 분류 이전의 쓰기 경계는 같은 전환 계약을 따른다.
`readMarkdown`은 양 모드에서 읽기 전용 parse다. General 본문 변경은 `withBody(ensureId = false)`로 관리 ID 생성을 차단한다.
General 설정은 메모리에서만 처리하고 autoTags 동기화·Plot/Default 재번호 API를 차단한다.

`configureExistingProject`는 다음 순서로 동작한다.

1. 기본 네 폴더 경로의 파일/대소문자 충돌을 전체 검사한다.
2. 누락된 `1. Concept`, `2. Outline`, `3. Character`, `4. Scene`만 만들고 기존 동명 폴더는 재사용한다.
3. 새 Project와 동일한 루트·네 폴더 설정을 기록한다.
4. 실패 시 이번 시도가 만든 설정과 아직 빈 새 폴더를 정리한다. 기존 사용자 파일과 재사용 폴더는 유지한다.
5. 설정 후 기존 ProjectIndexer로 ID·프로젝트 태그를 보완한다. 기존 파일명·본문·Plot 단계는 바꾸지 않는다.

최초 설정과 보관 Project 복귀는 다른 경로다. 복귀 계약은 [작업 공간 분류 전환의 현재 계약](#작업-공간-분류-전환의-현재-계약)을 따른다.

설정 생성 도중 프로세스 강제 종료를 복구하는 journal은 이번 범위에 포함하지 않는다.

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
- 빈 설정 및 구 설정의 base(`""`) 기본값(`default + plotEnabled`) 보완·저장: 검증 완료
- 설정 전체 변경과 폴더별 설정 변경 API: 구현됨
- 폴더 탐색과 현재 폴더 상태에 적용: 구현·통합 검증 필요
- Default 정렬과 디렉터리 이름·설정 편집 UI: 구현·자동 검증 완료, 수동 검증 필요
- autoTags 변경 시 기존 파일 동기화: 구현·자동 검증 완료

설정 필드의 제품 의미와 태그 보존은 [제품 도메인](design/product-domain.md), 루트 설정 UI 제거는 [작업 공간 수명 §4](design/workspace-lifecycle.md#4-루트와-태그)를 따른다.
base `autoTags` 지원은 호환 데이터 처리 계약이며 루트 편집 UI의 존재를 뜻하지 않는다.

생성·편집 다이얼로그는 `FolderConfigEditor`와 동일한 편집 상태를 공유한다. 설정 변경은 기존 저장 coordinator의
flush/쓰기 fence 아래에서 진행하며 `FolderSettingsService`는 사전 조회와 메타데이터 확정을 담당한다.
`MainViewModel`은 최신 pending과 조회 결과를 새 경로의 cache/session/하이라키에 반영하고, 관리 태그 변경도
동일한 pending 저장 경로에 예약한다. 태그 저장 실패와 재시도 경계는 아래 폴더 rename 2차 정비를 따른다.

설정 상태의 `null`은 프로젝트가 선택되지 않았거나 설정을 아직 로드하지 못한 상태를 뜻한다.
프로젝트가 바뀌면 이전 설정 상태를 먼저 비우며, 새 프로젝트의 설정을 읽은 뒤에만 새 상태를 공개한다.
현재는 라이브 상태까지 연결됐고, 다음 단계에서 상대 경로 기반 파일·폴더 상태가 이 설정을 소비한다.

---

## 7. 마크다운 파일과 frontmatter

`NoteFile`은 YAML frontmatter와 본문을 분리한다.

관리 키·원형 보존·보정 시점의 정본은 [제품 도메인 §4](design/product-domain.md#4-frontmatter-정책)다.
`NoteFile`은 YAML 전체를 재직렬화하지 않고 관리 키만 선택적으로 갱신한다.

프로젝트 선택 직후 `ProjectIndexer`가 MainScreen 진입 전에 루트와 직속 디렉터리의 Markdown 파일을
점검한다. 파일별로 `id`와 프로젝트명 태그를 독립적으로 검사하고 누락된 값이 있을 때만 디스크를
갱신한다. 변경 대상이 없으면 별도 화면 없이 바로 진입하고, 변경 대상이 있을 때만 처리 수와 전체 수를
표시하는 로딩 화면을 보여준다. 완료되면 결과 확인 없이 MainScreen으로 자동 진입한다. 한 파일의
읽기·쓰기 실패는 다른 파일의 인덱싱을 중단하지 않는다.

이 초기 인덱싱은 frontmatter 필수값 보완 절차다. 열지 않은 파일의 메타데이터를 세션 동안 검색·그룹핑하는
인메모리 메타 인덱스는 별도 단계로 유지한다.

`NoteFileTest`는 관리 키 읽기·쓰기, 미지 키 보존, 안정적인 round-trip을 검증한다.

`NoteFile.withProjectMetadata(projectName)`는 ID와 정규화된 Project 태그를 합성하는 순수 변환이다.
`FileManager.inspectProjectMetadata`는 보정 필요 여부만 반환하고, `ensureProjectMetadata`만 그 결과를 디스크에
기록한다. 인덱싱 적용 시에는 원본을 다시 읽어 검사 때의 버퍼를 그대로 덮어쓰지 않는다. 이 재검사는 외부 프로그램과의
완전한 동시 쓰기 원자성까지 보장하지 않는다. 일반 `write`/`writeMarkdown`에는 메타데이터 보정을 숨기지 않는다.
General 편집과 이름·순서 변경 및 실패 rollback이 원치 않는 ID/태그 변경을 받지 않도록 하기 위해서다.

---

## 8. 편집과 저장 데이터 흐름

```text
PlatformFile
  → ProjectFile(FileKey = 프로젝트 상대 경로)
  → FileManager.readMarkdown()
  → NoteFile(frontmatter + body)
  → MainViewModel.fileLoadStates(Loading / Loaded / Error)
  → EditorPage
  → MarkdownBlockTextFieldM3(value = noteFile.body)
  → List<EditorBlock>
  → 사용자 편집
  → blocks.toMarkdown()
  → MainViewModel.updateBody()
  → NoteFile.withBody(ensureId = false)
  → Project만 withProjectMetadata(편집 시점 프로젝트명)
  → DebouncedSaveCoordinator (FileKey별 500ms)
  → FileManager.writeMarkdown()
```

저장 debounce의 소유자는 ViewModel 계층의 `DebouncedSaveCoordinator`다. 실효 저장 지연은 500ms이며,
같은 파일의 새 요청만 이전 요청을 취소한다. 다른 파일의 pending save는 서로 취소하지 않는다.
외부 mtime 변경·삭제·rename 시에는 해당 파일의 stale pending save를 취소한다.

2026-09-08 자동 저장 오류·취소 정비에서는 자동 저장의 일반 예외를 `onSaveFailure`로 전달하고 pending 내용을
유지한다. MainViewModel은 기존 `WorkspaceSaveCoordinator.lastErrorMessage`와 오류창을 재사용해 실패 파일을 알린다.
알림 닫기는 pending 폐기가 아니며, 다음 실제 편집이나 전환·종료의 flush에서 재시도한다. 자동 무한 재시도나 별도 오류
state/scope는 추가하지 않는다. 정상 취소와 이미 다른 요청으로 대체된 작업의 늦은 오류는 사용자 오류로 보고하지 않는다.

flush도 자동 저장처럼 성공이 확인된 값만 pending에서 제거한다. 잠금 대기나 실제 쓰기 중 취소·실패해도 미저장값은
남으며, 최신 입력이나 명시적으로 폐기된 값을 과거 값으로 복구하지 않는다. 별도의 취소 key 집합과 실패 시 pending
재삽입 로직은 두지 않는다. 저장에 실패해 Job만 종료된 파일도 `cancelMissing`의 대상이다.

2026-09-08 쓰기 순서 정비에서는 기존 flush Mutex를 자동 저장·명시적 flush·단일 파일 rename이 함께 사용하는
`writeMutex` 하나로 통합했다. 500ms 예약은 key별로 독립적이지만 실제 쓰기는 앞선 쓰기의 완료 후 실행한다.
flush는 잠금 안에서 아직 시작하지 않은 예약만 취소하고 현재 pending을 저장한다. 같은 잠금을 기다리는 Job을
잠금 안에서 `cancelAndJoin`하지 않으므로 교착을 만들지 않는다. 먼저 완료한 자동 저장은 flush에서 중복 저장하지 않는다.
`flushAll`은 pending이 비어 보여도 최초 한 번 쓰기 잠금을 통과한다. rename이 old key를 꺼내고 new key에 다시 예약하는
중간에도 전환·종료의 flush가 먼저 끝나지 않으며, 이후 새 key의 pending까지 처리한다.

상태 접근은 기존 Main dispatcher에 한정하고 디스크 쓰기는 FileManager의 IO 경계를 사용한다. 일반 `cancel()`은
취소 요청과 pending 폐기이며, 이미 시작한 OS/provider 쓰기의 즉시 중단을 뜻하지 않는다. 이 완료 순서 보장은 같은
코디네이터가 소유한 문서 쓰기와 아래 단일 파일 rename에 적용하며, 외부 프로그램의 변경이나 프로세스 종료 후
복구 journal까지 보장하지 않는다. 새 서비스·scope·파일별 잠금 map은 추가하지 않았다.

후속 우선순위는 폴더 rename/delete의 취소 rollback이다. 2026-09-08 병렬 코드 조사에서 다음 미해결 사항을 확인했다.

- 폴더 rename의 디스크·설정·bookmark 취소 복구와 선행 flush 이후 새 pending의 경로·관리 태그 이동은 아래
  1·2차 정비로 연결했다. 태그 저장 실패 시 구조 변경을 되돌리지는 않고 새 경로의 pending을 보관해 재시도한다.
- Desktop/Android 폴더 삭제는 직속 Markdown을 순차 삭제한다. 중간 삭제 실패 시 설정·bookmark 복구만으로 앞서
  삭제한 파일을 되살릴 수 없다. 영구 삭제 유지 또는 복구 가능한 보관 방식을 사용자와 확정하기 전에는 삭제 구현을
  확대하지 않는다. 임시 보관 방식은 검토 후보이며 확정된 정책이 아니다.

production 호출이 없던 `pickProject` 우회 API는 제거했다. 설정 테스트도 `requestOpenWorkspace`와 필요한 경우
`confirmWorkspaceOpen(PROJECT)`를 거치는 실제 진입 경로를 검증한다. Project 활성화 전체의 NonCancellable 축소는
별도 수명 설계 전까지 보류한다. 현재 App scope와 이 경계는 인덱싱 중 화면 교체 및 bookmark/config 완료를 보호하므로
단순 삭제하지 않는다.

Project/Vault 선택과 Desktop 종료 요청은 `WorkspaceSaveCoordinator`를 통해 모든 pending save를
먼저 flush한다. 저장이 실패하면 값은 pending 상태로 남기고 전환·종료를 중단해 재시도할 수 있게 한다. Android는
`onStop`에서 같은 flush를 앱 수명 scope로 시작하는 best-effort 보호를 적용한다. 운영체제의 강제 종료나 프로세스 kill은
lifecycle callback 자체가 보장되지 않으므로 이 경계만으로 완전한 동기 저장을 보장하지 않는다.

저장 콜백 등록은 `AutoCloseable`을 반환하고 MainViewModel의 `addCloseable`에 연결한다. ViewModel 정리 시
자신의 등록만 해제하므로, 이전 ViewModel의 늦은 정리가 새 등록을 지우지 않는다. foreground flush는 잠금을
기다리기 전, `onStop` background flush는 coroutine을 시작하기 전에 등록 snapshot을 확보한다. 이미 요청된
저장은 등록 해제 후에도 해당 callback으로 완료하고 요청이 끝나면 참조를 놓는다.
등록 해제 자체는 새 저장을 예약하지 않는다. 새 ViewModel이 편집한 뒤 이전 pending 내용이 늦게 덮어쓰는
수명 간 경쟁을 추가하지 않기 위해서다. 플랫폼 flush 없이 `ViewModelStore.clear()`만 호출하는 임의 경로는
저장 보장 대상이 아니며, 새 종료 경로를 추가한다면 먼저 flush 완료 경계를 연결해야 한다.

파일 생성과 디렉터리 생성·설정 변경·삭제, Project 이름 변경은 `MainViewModel.launchWorkspaceMutation`을
사용한다. 호출 시 Project와 대상 폴더를 고정하고 `fileReconciliationMutex → WorkspaceSaveCoordinator` 순서로
기존 잠금을 획득한 뒤 pending save를 flush한다. 잠금을 기다리는 사이 작업 공간이 바뀐 요청은 실행하지 않는다.
저장이 실패하면 구조 변경도 실행하지 않으며, 코루틴 취소는 저장 실패 Result로 변환하지 않고 전달한다.
삭제 확인은 실행 시 대상 폴더를 다시 점검한다. Project 선택·생성 UI 작업은 화면 교체에도 유지되는 App scope를
사용하고, FileManager의 Project 활성화 진입점은 같은 `workspaceOpenMutex`와 내부 `activateProject`로 모인다.
현재 활성화 중 인덱싱 완료를 보장하는 `NonCancellable` 경계는 유지한다. 활성화 작업의 전체 수명 재설계는 별도 범위다.

2026-09-07 안정화에서는 기존 계층을 유지하고 일반/Plot 파일 생성의 공통 처리를 묶었다. 폴더 조회 함수는
조회 결과만 반환하며, 현재 폴더·Plot 목록·파일 목록·하이라키 반영은 `applyFolderContent`로 적용 순서를 공유한다.
복원 정책별 공개 API는 유지하되 Project snapshot 적용과 ViewModel의 Project/파일 복원 후처리는 공통화했다.
미사용 `listFile`, 구 `renameMarkdown` expect/actual, `getDescription`, `CustomListItem`, `dissolveCallout`을 제거하고
저장 전체 취소는 `cancelAll` 하나로 통일했다. 파일 생성에 쓰는 충돌 회피 함수와 현행 `renameMarkdownExact`는 유지한다.

FileManager 초기화 Scope 소유권과 ViewModel 저장 콜백 등록 해제는 위의 작은 수명 경계로 정비했다.
하이라키의 단일 상태 snapshot과 읽기 API의 메타데이터 보정 책임도 아래와 같이 정비했다.
이번 변경은 전체 수명·상태 모델 재설계나 외부 프로그램과의 동시 쓰기 원자성까지 해결한 것으로 보지 않는다.

2026-09-08 하이라키 상태 정비에서는 6개의 독립 StateFlow를 `HierarchyUiState` 하나로 바꿨다.
폴더 목록·폴더별 content map·현재 폴더 key·선택 FileKey만 저장하고 현재 파일 목록·Plot 목록·index는
snapshot을 만들 때 파생한다. 폴더별 map과 현재 파일 목록을 각각 수정하지 않는다. 빈 폴더는 선택 key와
현재 파일이 null이고 Pager용 index만 0이다. rename·번호 재정렬의 선택 기준도 숫자 index가 아니라 FileKey다.

`publishHierarchy`는 조회가 모두 끝난 결과에 선택 fallback과 삭제된 파일의 runtime 정리를 적용하고 한 번 게시한다.
전체 하이라키를 기준으로 실제 사라진 key만 정리하므로 다른 폴더로 이동했다는 이유로 cache·pending을 지우지 않는다.
현재 폴더의 탐색·생성은 `applyFolderContent`, 전체 갱신·설정 변경·rename·재정렬은 같은 게시 함수로 모인다.
복원 중에는 content map만 먼저 비우지 않고 새 목록이 준비되면 교체하며, Project 정리는 빈 snapshot 한 번으로 수행한다.

bookmark/config 수집의 조회·게시도 기존 `fileReconciliationMutex`를 사용해 탐색·구조 변경과 직렬화한다.
새 잠금이나 보조 scope, map/stateIn으로 만든 호환용 개별 flow는 추가하지 않았다. MainScreen은 snapshot을 한 번
구독해 편집 영역과 Drawer에 전달하므로 Drawer의 현재 폴더 재합성 및 Pager index fallback 보정은 제거했다.
bookmark·Project 설정·문서 cache는 기존 소유자를 유지한다. 이번 원자성 보장은 하이라키 내부의 목록과 선택에 대한
것이며, 앱의 모든 상태와 디스크 변경을 하나의 transaction으로 바꾼 것은 아니다.

2026-09-08 읽기·저장 책임 정비에서는 workspace에 따라 동작이 달랐던 Markdown 읽기 overload를 순수 읽기 하나로
통합했다. 생성과 편집은 요청 시점의 Project 정보를 사용해 보정하며, 지연 저장에서 바뀐 bookmark를 다시 읽지 않는다.
파일 rename은 읽기 cache를 저장 대상으로 취급하지 않고 `DebouncedSaveCoordinator.cancel`이 돌려준 실제 pending만
새 key로 옮긴다. 실패 시에도 실제 pending만 기존 key로 재예약한다. Plot 순서 변경 실패는 파싱된 문서를 재직렬화하지
않고 보관한 원문 문자열을 복구한다. 별도 서비스·scope·저장소를 추가하지 않고 기존 책임 경계를 명시했다.

단일 파일 rename은 `fileReconciliationMutex → writeMutex` 순서로 잠금을 얻고, 앞선 문서 쓰기가 실제로 끝난 뒤에만
pending을 꺼내고 물리 이름 변경을 시작한다. 각 잠금 대기 후 작업 공간과 대상 파일을 재검증한다. 잠금 대기는 취소
가능하며, 진입 후 물리 rename·최신 pending 이동·cache/session/하이라키 key·bookmark 반영은 하나의 제한된
`NonCancellable` 구간으로 완료한 뒤 호출자의 취소를 다시 전달한다. 이름 변경 I/O 중의 새 입력도 기존 key에서 회수해
새 경로로 옮기므로 늦은 저장이 이전 파일명을 다시 만들지 않는다. 읽기만 한 문서는 이름 변경을 이유로 본문을
다시 저장하지 않는다. `withWritesPaused` 내부에서는 같은 잠금을 다시 얻는 flush나 중첩 rename을 호출하지 않는다.

2026-09-08 폴더 rename 1차 정비의 경계는 `FileManager.renameProjectFolder`가 완료될 때까지의 물리 폴더명·Project
설정(fileIds 포함)·선택 bookmark다. 기존 `projectConfigMutex`를 사용하며 새 transaction 서비스나 저장소는 만들지 않는다.
실제 이름 변경만 `NonCancellable`에서 결과를 확보하고, 이후 설정·bookmark 저장은 취소 가능하게 둔다. IO context
복귀 시점의 취소도 바깥 catch에서 처리하므로, 물리 변경 결과를 잃고 원상복구를 건너뛰지 않는다.

취소나 실패가 발생하면 별도의 취소 불가 복구 구간에서 원래 폴더명·설정 원문·메모리 설정·변경을 시도한 bookmark를
각각 복구한다. 설정을 다시 직렬화하지 않으며, General에는 디스크 설정을 만들지 않는다. 원래 없던 Project 설정이
이번 시도로 생성되었다면 그 설정 파일만 제거한다. 이 절차는 문서 본문이나 태그를 다시 쓰지 않는다.
완전한 복구 뒤 일반 실패는 기존 nullable 결과와 오류 UI를 사용하고, 정상 취소는 다시 전달한다. 하나라도 복구에
실패하면 `FolderRenameRollbackException`에 원래 원인과 복구 오류들을 보존해 기존 오류창에 전달한다. 불완전한 복구를
성공이나 단순 취소로 숨기지 않고 추가 편집 중단·실제 폴더 확인·작업 공간 재진입을 안내한다.

1차 경계만으로는 후속 태그 동기화·runtime 반영을 보호하지 못하므로, 2차 정비에서 다음 순서를 연결한다.

1. 기존 `launchWorkspaceMutation`의 flush 뒤 `withWritesPaused`로 자동 저장과 경합하지 않도록 한다.
2. FolderSettingsService의 `prepare`는 대상 Markdown과 mtime을 먼저 조회한다. 이 단계의 취소·읽기 실패는
   물리 폴더나 설정을 바꾸지 않는다. 별도 내부 flush는 없으므로 비재진입 mutex의 중첩 획득도 없다.
3. `commit`으로 폴더명/설정을 바꾸고, FileManager는 새 폴더에서 실제 파일 handle을 다시 조회해 old key → 새
   ProjectFile 매핑을 돌려준다. SAF URI를 문자열 경로로 추측하지 않으며 조회 실패도 기존 rename rollback에 포함한다.
4. 메타데이터 확정부터 `applyFolderSettings`의 pending/cache/session/하이라키 반영까지 제한된 NonCancellable로
   완료한다. 마지막 runtime 반영에는 suspend가 없다. IO 도중 입력한 최신 pending을 우선 사용하고 id/plot/사용자
   메타데이터/본문을 유지한 채 관리 태그 차이만 적용한다. 태그 차이가 없으면 원본 NoteFile 객체를 그대로 사용한다.
5. 쓰기 fence를 해제한 뒤 같은 DebouncedSaveCoordinator로 새 경로에 저장한다. 일부 실패나 취소에도 미저장 값은
   pending에 남아 다음 편집·작업 전환 시 재시도한다. 오류는 ‘설정 변경 완료, 일부 문서 저장 실패’로 구분한다.

본문 저장을 별도 직접 쓰기 경로로 처리하지 않으므로, N번째 태그 저장 실패 때문에 앞선 성공 결과나 이전 태그 정책을
잃어버리는 문제를 피한다. 새 journal/작업 queue/scope/잠금은 추가하지 않는다. pending은 기존과 동일한 메모리 기반으로,
프로세스 강제 종료까지의 내구성이나 외부 프로그램의 동시 경로 변경을 보장하지 않는다.
이 경로로 대체되어 production 사용처가 없어진 `synchronizeAutoTags`/`AutoTagSyncUpdate`는 제거하고, 기존 회귀를
MainViewModel 설정 변경 경로로 이관했다. pending이 없으면 사전 조회한 disk를 우선해 stale cache의 외부 본문 덮어쓰기를
막으며, 같은 원문일 때만 cache 객체를 재사용한다. 사전 조회 이후 또 발생한 외부 쓰기의 충돌 해결과 대형 Project의
선택적 snapshot 최적화는 후속 범위다.

`FileKey`는 `/`로 정규화한 프로젝트 상대 경로다. 따라서 프로젝트의 다른 직속 폴더를 탐색해도
동일한 파일명을 가진 파일들이 cache, mtime, pending save를 공유하지 않는다. `ProjectFile`은
이 키와 플랫폼별 `PlatformFile`을 함께 운반한다. 프로젝트가 바뀌면 이전 프로젝트의 cache,
mtime, pending save를 모두 비운다. frontmatter `id`와 `ProjectConfig.fileIds`를 이용한 rename·이동
추적은 폴더 발견 이후 별도 단계에서 연결한다.

---

## 9. 프로젝트 커밋 경계

커밋은 에디터의 `List<EditorBlock>`이나 Undo/Redo snapshot을 저장하지 않는다. pending save를 flush한 뒤
디스크의 Markdown 원문 문자열을 그대로 읽어 프로젝트 스냅샷을 만든다. 비교를 위해 `NoteFile.inject()`로
재직렬화하지 않으므로 frontmatter 표현이나 구분 개행만 달라진 변경도 추적한다. `.machum.json`은
폴더 형식과 앱 인덱스를 관리하는 설정 파일이므로 커밋 대상에서 제외한다.
따라서 블록 에디터와 향후 단일 text surface 여부는 commit 저장 형식에 영향을 주지 않는다.

미리보기·working diff·복원 사전검사도 같은 읽기 전용 scan을 사용한다. 실행 중 외부에서 추가된 ID 없는 파일은
임시 ID를 발급하거나 검사 중 파일을 고치지 않고 대상 경로와 함께 인덱싱 안내 오류를 반환한다. Project를 다시 열어
인덱싱하거나 해당 파일을 실제 편집·저장하면 커밋할 수 있다. 이 구현은 조회 중 원본 보존을 우선하는 보수적 동작이며,
커밋 진입 시 자동 보정을 원한다면 읽기 API 대신 별도의 명시적 준비 단계에서 처리해야 한다.

전체 snapshot 적용은 검증된 대상 상대 경로 목록을 기준으로 지원 범위 파일을 삭제·덮어쓴다. 복원 중 생성됐으나
아직 ID를 쓰지 못한 빈 파일이나 부분 쓰기 파일이 있어도 rollback 자체가 ID 검사에서 막히지 않는다. 작업 전후의
정체성·충돌·hash 검사는 여전히 엄격하게 수행한다. 전체 복원의 raw backup은 실패 시 그대로 복구하고,
필수 Project 태그가 이미 유효한 역사 버전은 전체 복원 시에도 불필요하게 재직렬화하지 않는다.

```text
Editor runtime state
  → pending save flush
  → tracked project files scan
  → content hash 계산
  → parent tree와 비교
  → 새 blob(추가·수정 파일만)
  → 전체 manifest를 가리키는 tree
  → parent + tree를 가리키는 commit
```

논리 모델은 전체 Project snapshot이고 물리 저장은 content-addressed 방식이다. 새 tree는 모든 추적 파일의
`fileId`, 상대 경로, `blobHash`를 가지지만 변경되지 않은 본문은 이전 blob을 참조한다. 삭제는 새 tree에서 빠진
항목으로, rename·이동은 동일한 frontmatter `id`의 상대 경로 변화로 판정한다. diff는 저장된 patch가 아니라 부모와
현재 tree의 비교 결과로 계산한다.

현재 MVP 구현 경계는 `ProjectCommitService`가 commit·diff·복원 transaction을 조정하고, `FileCommitStore`가
commit·tree·blob의 영속화를, `CommitPlanner`, `LineDiffCounter`, `LineDiffEngine`이 manifest 비교와 줄 diff 계산을
담당하는 정도로 제한한다. 별도 Git repository 추상화,
staging·branch·merge·remote 계층은 만들지 않는다. 상세 객체 모델, 추적 범위와 미결 정책은
[커밋 기능 설계](design/product-domain.md#9-커밋-기능-설계)를 따른다.

초기 기준점과 HEAD/parent 복원 의미는 [제품 도메인 §9.6](design/product-domain.md#96-커밋-ui-역할-분리와-복원-정책source-of-truth확정)을 따른다.

프로젝트 전체 복원은 target blob·경로·충돌을 먼저 검증한 뒤 mutation 직전에 canonical working-tree hash를
재확인한다. 적용 후 목표 hash를 read-back 검증하고, 실패하면 복원 직전 working snapshot을 재적용해 원래 hash까지
확인한다. 파일 복원도 같은 transaction 경계를 사용한다. tree와 commit 객체는 로드시 파일명에 사용된 hash/ID와
canonical 내용을 함께 검증하며, blob 검증만으로 손상 객체를 신뢰하지 않는다.

복원 중 코루틴 취소도 실패와 같은 transaction 경계를 거친다. 파일/프로젝트 복원은 공통 실행 함수에서
원상 복구와 원래 hash 검증만 `NonCancellable`로 보호하고, 복구에 성공하면 원래 `CancellationException`을
그대로 전달한다. 복구 자체가 실패하면 `RestoreRollbackFailedException`에 원인과 복구 오류를 함께 보존한다.
새 목적지 파일은 생성과 rollback용 참조 등록만 짧게 보호해 취소 시 임시 파일을 놓치지 않게 한다.
HEAD와 이력은 변경하지 않으며, 프로세스 강제 종료나 외부 프로그램과의 원자적 동시 쓰기까지 보장하지 않는다.

세 복원 동작의 데이터 보존·dirty 예외·세션 범위는 [제품 도메인 §9.6](design/product-domain.md#96-커밋-ui-역할-분리와-복원-정책source-of-truth확정)에만 정의한다.
서비스는 파일 단위 대상을 기존 `CommitChange`의 `BEFORE | AFTER` side로 표현하며 새 영속 모델을 만들지 않는다.
`NoteFile` 변환으로 historical 본문에 현재 정체성·분류를 적용한다. 런타임 세션은 canonical hash를 재검사하며 일반 dirty 차단과 stale 세션 오류를 구분한다.

---

## 10. 외부 변경 감지

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

본문·Plot 목록을 다시 읽는 단계에서는 ID·태그 보완이나 디스크 쓰기를 하지 않는다. 실행 중 외부에서 추가한
ID 없는 파일도 열기만 하면 원문을 유지하고, Project 재진입 인덱싱이나 실제 본문 편집·저장 때 보완한다.

### 허용된 트레이드오프

진짜 외부 변경을 반영하면 에디터 블록 ID, focus, cursor, selection이 초기화될 수 있다. 외부 우선 정책상 현재는 이를 수용한다.

### 검증 상태

- Desktop 저장·외부 변경·rename·삭제 경합 수동 검증 완료
- Android DocumentsUI 저장·mtime 수동 검증 완료
- provider별 mtime 동작은 새 provider 지원 시 회귀 검증

---

## 11. 블록 에디터 경계

마크다운 본문은 `List<EditorBlock>`으로 관리한다.

```text
MarkdownBlockTextField
  ├── 외부 value 동기화
  ├── DocumentSelection 소유
  └── MarkdownBlockEditor
       ├── EditorFocusCoordinator
       ├── EditorMutationDispatcher
       ├── EditorSelectionCoordinator
       ├── EditorBlockSnapshot
       ├── EditorHistory
       ├── BlockNavigation
       │    ├── focus actions
       │    ├── mutation actions
       │    └── selection actions
       ├── TextBlockEditor
       ├── CalloutBlockEditor
       │    └── CalloutBodyPolicy
       ├── CodeBlockEditor
       └── TableBlockEditor
```

각 블록은 독립 `TextFieldState`와 안정 ID를 가진다. `LazyColumn` key는 블록 ID를 사용한다. 블록 에디터가
상위 문서 에디터에 보내는 요청은 `BlockNavigation`의 `focus`, `mutation`, `selection` 세 역할 그룹으로
구분한다. 자세한 파싱·포커스·selection·dissolve 계약은 [markdown-editor.md](markdown-editor.md)에만 기록한다.
selection 상태는 `MarkdownBlockTextField` 하나가 소유하고 `EditorSelectionCoordinator`는 다음 endpoint와
컨테이너 escape 여부만 순수 계산하므로 상태 저장소를 추가하지 않는다.

`MainViewModel`은 파일 cache와 저장, `EditorDocumentState`는 한 문서의 blocks·selection·history를 소유한다.
commit의 디스크 파일 snapshot과 editor history의 메모리 snapshot은 수명·정체성·저장 위치가 다른 별도 계층이다.
Undo/Redo·cross-block 입력·focus 복원 계약과 구현 상태는 [에디터 설계](markdown-editor.md)에만 기록한다.

---

## 12. 은퇴한 workflow 코드 정리

workflow 기반 템플릿과 배정 플로우는 제품 네비게이션에서 은퇴했고 2026-08-31 구조 최적화에서 다음 항목을 제거했다.

- `WorkflowParser`와 workflow entity
- `workflowSceen` 패키지와 `WorkflowSelectionScreen`
- `FileManager`의 workflow 상태·파일 선택·생성 API
- 새 vault 선택 시 `.workflow/`를 만드는 동작

기존 Vault에 이미 존재하는 `.workflow/`와 사용자 파일은 마이그레이션이나 삭제 대상으로 취급하지 않는다. 앱이 더 이상
생성·조회·수정하지 않을 뿐이므로 필요하면 Obsidian이나 파일 관리자를 통해 그대로 사용할 수 있다.

---

## 13. 빌드와 검증

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

검증 결과의 정본은 [문서 안내의 검증 문서](README.md)와 각 구현 기록이다. 과거 테스트 개수를 현재 아키텍처에 중복 기록하지 않는다.
최신 실행 결과·남은 항목은 [세션 인계](session-handoff.md)에서 확인한다.

실제 키보드·마우스·창 포커스·Android SAF 상호작용은 [P0 수동 테스트](p0-manual-test.md)를 따른다.
