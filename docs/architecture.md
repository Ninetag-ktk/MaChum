# MaChum 현재 아키텍처

> 역할: 현재 코드가 실제로 어떻게 동작하는지 설명하는 source of truth  
> 마지막 검토: 2026-09-03
> 제품 방향과 우선순위: [product-roadmap.md](product-roadmap.md)  
> 마크다운 에디터 내부 설계: [markdown-editor.md](markdown-editor.md)

---

## 1. 프로젝트 개요

MaChum(맞춤)은 Android와 Desktop(JVM)을 대상으로 하는 Compose Multiplatform 마크다운 집필 앱이다. Obsidian과 같은 vault를 공유하면서 원고 편집과 장기적인 버전 추적을 제공하는 것이 목표다.

현재 제품 흐름은 사용할 vault와 project를 고른 뒤 프로젝트 루트의 마크다운 파일을 좌우로 넘기며 편집하는 구조다. workflow 템플릿 네비게이션은 은퇴했고 관련 소스와 새 vault의 `.workflow/` 자동 생성도 제거했다.
계획된 확장은 Vault 직속 디렉터리를 관리 Project와 일반 Vault 폴더로 분류하고, 상단
하이라키에서 두 탐색 루트를 전환하는 구조다. 이 절의 현재 Project 흐름은 아직 그대로이며,
Workspace 분류와 일반 폴더 mode는 미구현 계획이다.

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

계획된 흐름은 Project 선택 여부 하나로 MainScreen 진입을 결정하지 않는다.

```text
vaultData 없음
  → VaultSelectionScreen

activeWorkspace 없음
  → Vault 직속 탐색 루트 선택

activeWorkspace.kind == PROJECT
  → ProjectConfig 로드 → 버전 마이그레이션 → ProjectIndexer → MainScreen

activeWorkspace.kind == VAULT_FOLDER
  → 인덱싱·설정 쓰기 없이 MainScreen의 일반 폴더 mode
```

이 분기는 아직 구현되지 않았다. 일반 폴더를 선택하는 것만으로 `.machum.json`을 생성하지 않도록
현재의 Project 설정 보완 경로와 분리해야 한다.

`MainScreen`은 다음 역할을 가진다.

- `MainViewModel.fileList`를 `HorizontalPager` 페이지로 표시
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

상단 파일 목록 버튼은 현재 폴더 파일 전환과 새 파일 생성을 제공한다. TopBar 왼쪽 메뉴 버튼은
Obsidian 스타일의 navigation drawer를 열고 커밋 버튼은 오른쪽에 둔다. 우측 하단 초기화 FAB는 확인 후
DataStore 북마크와 `FileManager`의 메모리 상태만 비우며 Vault의 실제 파일은 삭제하지 않는다.
커밋 버튼은 pending save를 flush한 뒤 프로젝트 변경을 스캔하고, 추가·수정·삭제·rename 파일과 줄 증감 요약 및
커밋 메시지 입력 UI를 연다. 같은 dialog의 이력 탭은 `HEAD`에서 parent를 따라 최근 50개 커밋과 각 커밋의
변경 파일을 보여준다. 파일 항목을 선택하면 부모와 해당 tree의 blob으로 줄별 diff를 계산한다. 과거 커밋 복구는
현재 변경이 없을 때만 허용하며, 추적 대상 Markdown을 해당 tree에 맞춘 뒤 `HEAD`는 유지한다. `.machum.json`은
커밋·diff·restore 대상이 아니므로 현재 설정을 그대로 유지한다. 따라서
복구 결과는 현재 변경 사항으로 나타나고 사용자가 새 커밋으로 확정할 수 있다. 변경이 없으면 빈 커밋을 만들지 않는다.

하단 설정 메뉴의 `프로젝트 이름 변경`은 선택된 Project 디렉터리명을 변경한다. 변경 전에 대기 중인 파일 저장을
flush하고, 성공하면 Project·선택 파일 bookmark를 새 경로로 교체한다. 모든 Markdown의 이전 프로젝트명 태그는
새 프로젝트명 태그로 바꾸되 폴더 관리 태그와 수동 태그는 유지한다. 기존 Project 이름, 잘못된 이름과
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
```

UI와 비즈니스 상태는 주로 `StateFlow`를 사용한다. Compose에서는 `collectAsState()`로 구독한다.

### 4.1 계획된 Workspace 상태

현재 `BOOKMARK_PROJECT`와 `projectData`가 탐색 위치와 Project 정체성을 동시에 나타낸다. 일반 Vault 폴더를
추가할 때는 두 개념을 다음처럼 분리한다.

```kotlin
enum class WorkspaceKind {
    PROJECT,
    VAULT_FOLDER,
}

data class WorkspaceRoot(
    val directory: PlatformFile,
    val kind: WorkspaceKind,
)
```

- `activeWorkspace: StateFlow<WorkspaceRoot?>`: 현재 drawer·pager·TopBar가 보여줄 Vault 직속 탐색 루트
- `selectedProject`: Project 선택기의 현재/마지막 관리 Project. 일반 폴더를 보는 동안에도
  Project로의 빠른 복귀를 위해 유지할 수 있다.
- 새 Project를 선택하면 `selectedProject`와 `activeWorkspace` 모두를 갱신한다.
- 일반 Vault 폴더를 선택하면 `activeWorkspace`만 갱신한다.
- ProjectConfig, `ProjectIndexer`, 관리 frontmatter, commit·diff·restore 진입은 `selectedProject != null`이
  아니라 `activeWorkspace.kind == PROJECT`를 공통 게이트로 사용한다.

상태 타입과 북마크 분리는 아직 구현되지 않았다. 구현 전까지 위의 `BOOKMARK_PROJECT`와
`projectData`가 현재 라이브 흐름의 authority다.

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
`MainViewModel`은 `folderList`와 `currentFolder`를 노출하고 현재 폴더의 파일만 pager 목록으로 유지한다.
drawer용 전체 목록은 별도의 `hierarchyFolderContents: Map<FolderKey, HierarchyFolderContent>`로 유지한다. 이 map은
루트와 모든 직속 폴더의 정렬된 파일 및 Plot 항목만 담으며 pager의 `fileList`, `currentIndex`와 섞지 않는다.
폴더별 선택 기억은 별도 저장소나 `.machum.json` 필드를 만들지 않는다. 앱 재실행 시에는 기존 단일 파일 bookmark만 복원하며,
파일·폴더 rename 및 삭제 시 런타임 key만 함께 정리한다.
navigation drawer의 넓은 본문에는 Project 루트 파일과 모든 직속 하위 디렉터리의 파일을 동시에 표시한다. 루트 파일은
항상 표시하며 루트 자체를 접는 행은 만들지 않는다. 직속 폴더만 프로젝트 진입 시 기본으로 펼치고 단일 disclosure 화살표로
개별 접기·펼치기한다. 폴더 아이콘은 disclosure와 의미가 중복되므로 표시하지 않는다. Markdown 파일은 문서 아이콘을 유지하고
Plot 가상 단계는 낮은 대비의 numbered-list 아이콘으로 구분하며 두 행의 텍스트 시작선을 맞춘다. 자식 파일과 Plot 단계에는 깊이별
들여쓰기와 옅은 연결선을 그린다. 하나의 `LazyColumn`과 folder/stage/file 안정 key만 사용해 전체 트리를 스크롤하며 폴더마다
별도 스크롤 컨테이너를 만들지 않는다. 다른 폴더의 파일을 선택하면 ViewModel이 해당 `currentFolder`와 pager `fileList`를
함께 전환한다. 상단 탐색기 도구막대의 배경·제목은 비상호작용이며 `루트 새 파일`, `새 직속 디렉터리`, `닫기`만 명시적인
action으로 둔다. disclosure는 접기·펼치기만 담당하고 폴더 이름을 클릭하면 해당 폴더를 `currentFolder`로 선택해 pager를
전환한다. 일반 폴더 행의 작은 `+`는 해당 폴더의 새 파일을 만들고 우클릭/롱프레스 메뉴는 새 파일, 이름·설정 변경, 삭제를
연다. Plot 폴더에는 일반 파일 `+`와 컨텍스트 메뉴의 새 파일을 노출하지 않고 각 Plot 단계의 `+`만 제공한다. 삭제는 기존
확인 다이얼로그를 거치며 Project 루트는 삭제할 수 없다. 폴더 행은 36dp, 파일 행은 30dp, Plot 단계는
28dp, 아이콘은 16dp의 tree 전용 치수를 사용하고 기본 48dp `IconButton` 대신 28dp compact action을 쓴다. 앱의 폴더
탐색 범위는 계속 Project 루트와 직속 폴더 한 단계다.
하단 고정 행의 Project 영역은 Project 선택 dropdown을 열고 설정 아이콘은 현재 Vault 정보와 Vault 다시 선택 메뉴를 연다.
Vault 변경은 플랫폼 디렉터리 선택 화면으로 이동한다.
도구막대의 `새 직속 디렉터리` action은 이름, 폴더 유형(`default`/`general`), Default 전용 Plot 옵션과 선택적인 자동 태그를 받는다.
UI 순서는 `Default`, `General`이며 신규 및 미설정 디렉터리의 기본 유형은 `default`다. General은 파일명을
그대로 사용해 이름순으로 정렬하고 General 전환 시 Plot을 해제한다. Default에서 Plot을 켜면 프롤로그부터 에필로그까지 7단계와 단계 내부 순번을 사용한다.
frontmatter는 `plot: 1) 발단`, 첫 파일명은 프롤로그 `0-1. 제목.md`, 발단 `1-1. 제목.md` 형식이다.
단계 코드 `0`~`6`은 그대로 유지하고 frontmatter 단계를 authority로 사용한다.
생성 시 실제 직속 디렉터리와 `.machum.json`의 `FolderConfig`를 함께 만들며, 자동 태그는 이후 생성되는 파일의
frontmatter `tags`에 중복 없이 추가한다. 숨김 이름, 경로 구분자, 운영체제 예약어와 기존 이름은 생성 전에 거부한다.
디렉터리 설정 화면에서는 이름과 설정을 함께 변경할 수 있다. 이름 변경 전 대상 폴더의 pending save를 모두 flush하고,
실제 디렉터리 rename → `.machum.json`의 폴더 key와 `fileIds` 상대 경로 변경 → 선택 파일 bookmark 변경 → 자동 태그
동기화 순으로 처리한다. 이 화면에서 Project 루트는 이름 변경 대상이 아니며, 기존 이름·대소문자만 다른 이름·잘못된 이름은 거부한다.
설정 기록이나 bookmark 갱신이 실패하면 실제 디렉터리명과 이전 설정 복원을 시도한다.
디렉터리 삭제는 폴더 컨텍스트 메뉴 또는 설정 화면의 삭제 버튼에서 별도 확인 다이얼로그를 거치는 영구 작업이다. 삭제 전에 직속 Markdown
파일 수를 표시하며, Project 루트는 삭제할 수 없다. 앱 탐색 범위 밖의 하위 폴더 또는 Markdown 이외 파일이 하나라도
있으면 보이지 않는 데이터 손실을 막기 위해 삭제를 차단한다. 허용된 삭제는 pending save flush → 설정 key와 해당
`fileIds` 제거 → 선택 bookmark 정리 → 실제 Markdown 파일과 디렉터리 삭제 순서로 처리한다. 실제 삭제가 실패하면
이전 설정과 bookmark 복원을 시도한다.
하위 폴더에서는 TopBar에 뒤로가기와 현재 폴더명을 표시하며, 뒤로가기는 프로젝트 루트로 이동한다.
상단 dropdown은 현재 폴더의 파일 전환과 새 파일 생성만 담당한다. 새 파일 다이얼로그는 제목을 필수로 받고 유형별 최종
파일명을 미리 보여주며 금지 문자, `.md` 중복 확장자와 같은 폴더의 중복 이름을 생성 전에 거부한다. Default 폴더는 숫자
접두사로 정렬하고 최대 번호 다음 값을 생성한다. 번호가 하나도 없을 때 일반 Default 디렉터리는 1에서 시작한다.
새 Project 루트는 Default + Plot이며 각 단계 내부 순번을 1에서 시작한다. 단계 코드는 `0`~`6`을 유지하므로 첫 파일은
프롤로그 `0-1. 제목.md`, 발단 `1-1. 제목.md` 형식이다. 기존 번호에는 자동 재번호를 수행하지 않으며 번호 없는
외부 파일은 번호 파일 뒤에 이름순으로 둔다.
Default + Plot 폴더의 새 파일은 같은 다이얼로그에서 단계를 필수로 선택하고 해당 단계 마지막 순번으로 생성한다. drawer는
프롤로그부터 에필로그까지 7개 가상 그룹과 미분류 그룹을 표시하며, 단계별 `+`는 생성 다이얼로그를 해당 단계가 선택된 상태로 연다.
Plot 폴더의 일반 파일 추가와 별도 `플롯 순서 편집` 메뉴는 노출하지 않는다.
알 수 없거나 단계가 없는 기존 파일은 `미분류` 마지막에 두며 해당 파일을 단계로 drag해 drop하기 전에는 변경하지 않는다.

하이라키 직접 순서 편집은 화면 순서만 따로 저장하지 않고 파일명의 숫자 prefix를 authority로 유지한다. Markdown 문서 행의
`Description` 아이콘만 드래그 핸들이며 파일 이름 클릭은 기존처럼 문서를 연다. drag 중에는 메모리 draft만 바꾸고 drop할 때
폴더 단위로 단 한 번 디스크에 적용한다. General은 파일명 이름순이 계약이므로 순서 drag를 제공하지 않는다. 일반 Default는
숫자 접두사가 있는 파일만 같은 폴더 안에서 재정렬하고 1부터 화면 순서대로 `{순번}. {제목}.md`를 다시 계산한다.
Project 루트가 Default 비-Plot이면 현재 구현은 0부터 재번호화한다. 계획된 `schemaVersion` 마이그레이션은
구버전의 기본 비-Plot 루트를 Default + Plot으로 한 번 전환하지만 기존 파일명은 재번호화하지 않는다.
현재 버전에서 사용자가 다시 Plot을 끈 Project는 명시적인 설정으로 보고 0 시작 경로를 유지한다. Default + Plot은 같은 단계 안 또는 인접 단계 사이의 drag 결과에 따라
`{단계코드}-{단계내순번}. {제목}.md`와 frontmatter `plot`을 함께 갱신하며 각 단계의 순번은 1부터 다시 계산한다.
번호 없는 외부 Default 파일은 초기 범위에서 drag 대상이 아니며 별도의 `순번에 편입` 동작 전까지 목록 뒤에 유지한다.
폴더 간 drag는 순서 변경이 아니라 파일 이동이므로 별도 기능으로 둔다.

저장은 대상 폴더 pending save flush → 현재 directory snapshot 재검증 → 충돌 검증 → 고유 임시 이름으로 1차 rename →
Plot frontmatter 갱신 → 최종 이름으로 2차 rename 순서다. 기존 Plot 일괄 rename의 충돌 회피 경로를 공통 batch primitive로
추출해 Default와 공유하고 평행한 rename 구현을 만들지 않는다. 성공 시 `ProjectConfig.fileIds`, bookmark, load state, mtime,
pending save, 폴더별 선택 기억, editor session key와 `hierarchyFolderContents`를 새 `FileKey`로 한 임계 구역에서 이동한다.
파일 선택과 pager 탐색의 bookmark 쓰기도 같은 파일 조정 mutex로 직렬화해 drop 저장과 겹친 오래된 경로가 새 bookmark를
덮어쓰지 않게 한다. 따라서 선택된 문서 편집기는 다시 읽지 않는다. 실패하면 가능한 범위에서 이전 이름과 frontmatter로 rollback하고 화면 draft는
폐기해 원래 하이라키 순서로 되돌린 뒤 해당 폴더 아래에 inline 오류를 표시한다. commit은 frontmatter `id`가 같으므로 이 결과를
삭제+추가가 아닌 rename 또는 rename+수정으로 판정한다.

권장 프로젝트 하이라키는 Project 루트를 장별 원고 영역으로 사용하고, 직속 디렉터리를 다음처럼 구성한다.

| 경로 | 유형 | 시작 번호 | 역할 |
|---|---|---:|---|
| Project 루트 | Default + Plot | 단계별 1 | 실제 장별 원고 |
| `1. Concept` | Default | 1 | 모티브, 글감, 메시지 등 구상 산출물 |
| `2. Outline` | Default | 1 | 캐릭터 초안, 전체 플롯, 발단부 구상 등 설계 산출물 |
| `3. Character` | General | 해당 없음 | 구체화된 캐릭터별 기준 문서 |
| `4. Scene` | Default + Plot | 단계별 1 | 구체화된 장면 구상과 서사 순서 |

영문명은 짧고 문학 작업에서 의미가 분명한 `Concept`와 `Outline`을 사용한다. 이 디렉터리는 앱이
새 프로젝트를 만들 때 번호가 붙은 위 순서로 자동 생성하는 제품 템플릿이다. 실제 디렉터리와 동일한 경로의
`FolderConfig`를 `.machum.json`에 한 번에 기록한다. 기존 프로젝트를 선택할 때는 누락 폴더를 소급 생성하지 않는다.
같은 이름의 Project가 이미 있으면 기존 디렉터리를 재사용하거나 덮어쓰지 않고 생성을 거부한다. 새 디렉터리의
기본 폴더 또는 설정 기록 중 실패하면 생성된 항목을 정리하고 실패로 반환한다.

현재 구현은 새 Project와 base(`""`) 설정이 없는 선택 Project 루트에
`type=default`, `plotEnabled=true`를 기록하지만 `schemaVersion`을 관리하지 않는다. 계획된 마이그레이션은
먼저 Vault 직속 디렉터리를 분류한 뒤, 유효한 `.machum.json`을 가진 Project에만 다음 규칙을 적용한다.

1. `schemaVersion`이 없거나 이전 버전이고 루트가 Default 비-Plot이면 Default + Plot으로 변경한다.
2. General 루트는 사용자 설정으로 보존한다.
3. 결과와 현재 버전을 함께 저장해 한 번만 실행한다. 이후 사용자가 Plot을 끄면 다시 켜지 않는다.
4. 기존 파일명과 `plot` frontmatter는 변경하지 않고 단계가 없는 파일은 `미분류`에 둔다.

이 일회성 마이그레이션은 아직 미구현이다. `.machum.json`이 없는 일반 Vault 폴더는 이 경로에
진입하지 않고, 마이그레이션을 위해 설정 파일을 새로 생성하지 않는다.

폴더 탐색 범위는 계속 Project의 직속 디렉터리와 직속 Markdown 파일로 제한한다.

### 5.4 계획된 Vault 탐색 위치 판별과 Project 전환 경계

Vault 위치 발견은 현재의 `listProject()` 및 설정 생성 경로와 분리한 읽기 전용 probe로 수행한다.
디렉터리 이름이나 기본 네 디렉터리의 존재 여부로 역할을 추측하지 않는다.

| probe 결과 | 분류 | 허용 동작 |
|---|---|---|
| `.machum.json` 없음 | `VAULT_FOLDER` | 설정·frontmatter를 쓰지 않는 일반 폴더 탐색 |
| 정상 또는 지원하는 구 스키마의 `.machum.json` | `PROJECT` | 설정 마이그레이션 후 인덱싱과 Project 기능 사용 |
| `.machum.json` 존재, 파싱 실패 | 손상된 Project | 일반 폴더로 강등하거나 덮어쓰지 않고 복구 안내 |

일반 Vault 폴더의 `프로젝트로 전환…`은 Project 내부 `FolderType` 변경이 아니라 탐색 루트의
역할 전환이다. 실행 전 전체 경로 충돌과 쓰기 가능 여부를 검사하고 다음 변경을 확인창에 표시한다.

1. Project 루트를 `Default + Plot`으로 등록한다.
2. 누락된 `1. Concept`, `2. Outline`, `3. Character`, `4. Scene`을 만들고 동명 디렉터리는 재사용한다.
3. 네 디렉터리에 새 Project와 같은 Default·General·Plot·autoTags 설정을 기록한다.
4. 설정 공개가 끝난 뒤 지원 범위 Markdown에 누락된 `id`와 Project 태그를 인덱싱한다.
5. 기존 파일명과 본문 및 Plot 단계는 추측해서 변경하지 않으며 단계 없는 루트·Scene 파일은 `미분류`에 둔다.
6. 전환 자체로 commit을 만들거나 `.machum/` 저장소를 생성하지 않는다.

필수 디렉터리와 같은 이름의 일반 파일이 있으면 쓰기 전에 전체 전환을 중단한다. 설정 공개 전 실패하면
이번 시도가 만든 항목만 rollback하고 기존 항목은 건드리지 않는다. 설정 공개 후 인덱싱이 부분 실패하면
Project 상태를 유지한 채 실패 파일과 재시도 동작을 제공한다. 이 경계와 전환 transaction은 아직 구현되지 않았다.

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

사이드바의 각 디렉터리 설정 아이콘에서 이름, 유형, Plot, 자동 태그를 수정한다. 유형과 Plot 변경은 기존 파일명을
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
7. 프로젝트 이름 변경 시 기존 프로젝트명 관리 태그를 새 이름으로 교체하고 다른 태그는 보존한다.
8. 앱이 기록하는 태그의 공백은 `_`로 정규화한다. 예: 프로젝트 `폴더 1` → `폴더_1`.
9. UTF-8 BOM과 LF·CRLF frontmatter를 인식하며, 관리 키를 보완해도 원래 줄바꿈 형식과 미관리 YAML·본문을 보존한다.
10. frontmatter가 없는 BOM 문서도 BOM을 본문으로 취급하지 않는다. `id`·`tags`를 새로 만들 때 BOM은 문서의 절대 첫 문자에
    한 번만 남고, 새 frontmatter와 본문 사이에는 삽입되지 않는다.

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
  → MainViewModel.fileLoadStates(Loading / Loaded / Error)
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

Project/Vault 선택, 앱 데이터 초기화와 Desktop 종료 요청은 `WorkspaceSaveCoordinator`를 통해 모든 pending save를
먼저 flush한다. 저장이 실패하면 값은 pending 상태로 남기고 전환·종료를 중단해 재시도할 수 있게 한다. Android는
`onStop`에서 같은 flush를 앱 수명 scope로 시작하는 best-effort 보호를 적용한다. 운영체제의 강제 종료나 프로세스 kill은
lifecycle callback 자체가 보장되지 않으므로 이 경계만으로 완전한 동기 저장을 보장하지 않는다.

`FileKey`는 `/`로 정규화한 프로젝트 상대 경로다. 따라서 프로젝트의 다른 직속 폴더를 탐색해도
동일한 파일명을 가진 파일들이 cache, mtime, pending save를 공유하지 않는다. `ProjectFile`은
이 키와 플랫폼별 `PlatformFile`을 함께 운반한다. 프로젝트가 바뀌면 이전 프로젝트의 cache,
mtime, pending save를 모두 비운다. frontmatter `id`와 `ProjectConfig.fileIds`를 이용한 rename·이동
추적은 폴더 발견 이후 별도 단계에서 연결한다.

---

## 9. 프로젝트 커밋 경계

커밋은 에디터의 `List<EditorBlock>`이나 Undo/Redo snapshot을 저장하지 않는다. pending save를 flush한 뒤
`NoteFile.inject()`로 디스크에 기록된 완전한 Markdown만 대상으로 프로젝트 스냅샷을 만든다. `.machum.json`은
폴더 형식과 앱 인덱스를 관리하는 설정 파일이므로 커밋 대상에서 제외한다.
따라서 블록 에디터와 향후 단일 text surface 여부는 commit 저장 형식에 영향을 주지 않는다.

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

MVP 구현 경계는 `ProjectCommitService`가 commit·diff·restore transaction을 조정하고, `FileCommitStore`가
commit·tree·blob의 영속화를, `CommitPlanner`, `LineDiffCounter`, `LineDiffEngine`이 manifest 비교와 줄 diff 계산을
담당하는 정도로 제한한다. 별도 Git repository 추상화,
staging·branch·merge·remote 계층은 만들지 않는다. 상세 객체 모델, 추적 범위와 미결 정책은
[제품 로드맵의 커밋 기능 설계](product-roadmap.md#9-커밋-기능-설계)를 따른다.

restore는 target blob을 모두 먼저 검증한 뒤 적용한다. 현재와 target에서 경로가 달라지거나 target에 없는 Markdown을
먼저 제거하고, 필요한 직속 디렉터리와 파일을 만든다. 중간 실패 시 시작 시점의 HEAD snapshot으로 Markdown 파일을
되돌리는 best-effort rollback을 수행한다. 설정 파일과 추적하지 않는 바이너리·중첩 폴더 및 빈
추가 디렉터리는 삭제하지 않는다.

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

에디터 완성 작업도 같은 문서의 목표 아키텍처와 단계별 계획을 따른다. `MainViewModel`은 파일 cache와 저장을,
`EditorDocumentState`는 한 문서의 blocks·selection·history를 소유하며 서로의 책임을 합치지 않는다.
독립 `BasicTextField`를 유지하므로 블록 횡단 selection은 운영체제 네이티브 selection이 아니라
`DocumentSelection` 기반 custom 동작으로 제공한다.

Undo/Redo는 mutable `TextFieldState`를 직접 저장하지 않는다. `EditorBlockSnapshot`이 ID·문자열·중첩 구조를
불변 값으로 복사하고 `EditorHistory`가 transaction과 stack만 관리한다. commit의 프로젝트 파일 snapshot과
editor history의 메모리 snapshot은 수명·정체성·저장 위치가 다른 별도 계층이다.

Undo/Redo history는 focus, cursor offset과 native/document selection을 저장하지 않는다. 복원 시 문서 선택은
`None`으로 해제하고 블록 내용만 교체한다. 필드별 focus 경로를 추적하지 않아 Text, Callout, Code, Table 구현을
history와 결합하지 않으며, 복원 직후 커서 위치는 Compose의 일반 focus 처리에 맡긴다.

Cross-block Delete/Cut/Paste는 별도 문서 상태 계층 없이 `replaceSelectedMarkdown` 순수 함수가 담당한다. 정규화된
같은-container selection의 영향 구간만 다시 parse하고 선택 밖 블록과 상위 Callout ID는 보존한다. UI는 clipboard
입출력과 결과 적용만 담당하며, 구조 치환 뒤 이전 cursor를 추적하지 않고 첫 편집 블록 focus만 요청한다.

Multi 상태의 일반 문자·한글 입력은 문서마다 하나인 `DocumentSelectionInputCapture`가 소유한다. IME 조합 중간값은
문서에 쓰지 않고 composition이 확정된 문자열만 `replaceSelectedText` 순수 함수로 치환한다. 치환 결과는 새 Text의
삽입 끝 위치를 일회성 focus 요청으로 전달할 뿐, Undo/Redo history에 focus·cursor·selection을 저장하지 않는다.

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

현재 자동 테스트는 `NoteFile`, `ProjectConfig`, 블록 파서·직렬화, `BlockOperations`,
`DocumentSelection`, `EditorDocumentValueCoordinator`, `EditorFocusCoordinator`, `EditorMutationDispatcher`, `BlockNavigation`,
`CalloutBodyPolicy`,
key별 저장 debounce와 Table 비정형 행 정규화를 검증한다.
실제 키보드·마우스·창 포커스·Android SAF 상호작용은 [P0 수동 테스트](p0-manual-test.md)를 따른다.
