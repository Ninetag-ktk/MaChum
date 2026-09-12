# MaChum 프로젝트 파일 탐색 수동 테스트

> 2026-09-09 정정: 과거 NAV-01의 루트 파일 미표시와 FOLDER-11의 초기화 FAB는 현행 요구사항이 아니다.
> 현재는 루트 파일을 표시하며 초기화 FAB는 제거했다. [화면별 기능 요구사항](screen-functional-requirements.md)과 최신 구현 기록을 함께 따른다.

> 이 문서는 현재 구현의 검증 절차·이력이다. [후속 생성 설계](file-folder-creation-design.md)의 `무제` 즉시 생성·General 기본값은
> 미구현이므로 아래 기존 생성 dialog/Default 기대값을 새 기능 통과 기록으로 해석하지 않는다. 새 수용 기준은 설계 문서에 별도로 둔다.

> 역할: 폴더 전환 UI, 파일 생명주기, Desktop·Android SAF 상호작용의 P1 검증 절차
>
> 관련 정책: [product-roadmap.md](product-roadmap.md), [architecture.md](architecture.md)
>
> 마지막 갱신: 2026-09-08

---

## 1. 실행 전 준비

```bash
./gradlew :composeApp:jvmTest
./gradlew :composeApp:compileKotlinJvm
./gradlew :androidApp:assembleDebug
```

전용 vault에 base, `1. Concept`, `3. Character`, `4. Scene`, `4. Scene/Act1`, `.hidden`, 빈 폴더를 만들고,
서로 다른 폴더에 `same.md`를 하나씩 둔다. 기본 Default + Plot인 base에는 `0-1. 프롤로그.md`,
`1-1. 발단.md`, `1-2. 발단 보강.md`, `외부.md`를 준비한다. 일반 Default 정렬 검증용 직속 폴더에는
`0. 영.md`, `2. 둘.md`, `10. 열.md`, `외부.md`를 준비한다.

---

## 2. 공통 탐색·저장

| ID | 절차 | 합격 기준 |
|---|---|---|
| NAV-01 | TopBar 왼쪽 메뉴 열기 | drawer 본문에는 Project 루트 없이 현재 Project의 직속 하위 디렉터리만 표시 |
| NAV-02 | 하단 Project 영역에서 다른 Project 선택 | dropdown이 닫히고 선택 Project의 디렉터리로 전환 |
| NAV-03 | 하단 설정 아이콘에서 Vault 다시 선택 | Vault 선택 화면으로 이동하고 실제 파일은 변경되지 않음 |
| PROJECT-01 | 새 Project 생성 | `1. Concept`, `2. Outline`, `3. Character`, `4. Scene` 디렉터리가 자동 생성됨 |
| PROJECT-02 | 새 Project의 `.machum.json` 확인 | Project 루트와 Scene은 Default + Plot, Concept·Outline은 Default, Character는 General로 기록됨 |
| PROJECT-03 | 기존 Project 선택 | 기본 네 디렉터리가 없더라도 소급 생성하거나 기존 구조를 변경하지 않으며, 명시된 Project 루트 유형·Plot 설정도 덮어쓰지 않음 |
| PROJECT-04 | 기존 Project와 같은 이름으로 생성 시도 | 기존 디렉터리와 설정을 변경하지 않고 생성 오류를 표시 |
| FOLDER-01 | drawer의 Folder 목록 확인 | 프로젝트 직속 비숨김 폴더만 보이고 `.hidden`, `4. Scene/Act1`은 보이지 않음 |
| FOLDER-01A | `디렉터리 추가`에서 이름·유형·자동 태그 입력 | 직속 디렉터리가 생성되고 목록에 즉시 표시되며 `.machum.json`에 같은 설정이 저장됨 |
| FOLDER-01A-1 | `디렉터리 추가` 유형 목록 확인 | Default가 첫 번째이자 기본 선택이고 General이 두 번째이며 Plot은 Default 아래 체크박스로 표시됨 |
| FOLDER-01A-2 | Default에서 Plot 선택 후 General로 전환 | Plot 선택이 즉시 해제되고 General에는 Plot 옵션이 표시되지 않음 |
| FOLDER-01B | 기존 이름, `.hidden`, `../Outside`, `CON`으로 생성 시도 | 추가 버튼이 비활성화되고 디렉터리가 생성되지 않음 |
| FOLDER-01C | 자동 태그가 설정된 디렉터리에서 새 파일 생성 | 새 파일의 frontmatter `tags`에 설정 태그가 중복 없이 기록됨 |
| FOLDER-01D | 디렉터리 설정 아이콘에서 Default/General 또는 Plot 변경 | 설정이 저장되고 기존 파일명은 바뀌지 않으며 이후 생성·정렬부터 적용됨 |
| FOLDER-01D-1 | `3. Character` 이름을 `3. Characters`로 변경 | 실제 디렉터리, `.machum.json` 설정 key, `fileIds`, 현재 선택 파일 경로가 함께 변경되고 화면도 같은 폴더를 유지 |
| FOLDER-01D-2 | 기존 디렉터리명 또는 대소문자만 다른 이름으로 변경 시도 | 저장할 수 없다는 안내가 표시되고 실제 파일과 설정은 변경되지 않음 |
| FOLDER-01D-3 | Project 기본 설정 열기 | Project 루트 이름 입력란은 표시되지 않고 유형·Plot·자동 태그만 변경 가능 |
| FOLDER-01D-4 | Markdown 파일이 든 디렉터리에서 삭제 선택 | 별도 확인 창에 파일 수와 영구 삭제 경고가 표시되고 확인 후 실제 폴더·설정·`fileIds`·선택 경로가 함께 제거됨 |
| FOLDER-01D-5 | 하위 폴더 또는 이미지 파일이 든 디렉터리에서 삭제 선택 | 삭제 차단 안내와 문제 항목이 표시되며 실제 파일과 설정은 변경되지 않음 |
| FOLDER-01D-6 | Project 기본 설정 확인 | Project 루트 삭제 버튼이 표시되지 않음 |
| FOLDER-01E | 기존 파일에 수동 태그와 자동 태그가 있는 상태에서 자동 태그 변경 | 이전 자동 태그만 제거되고 새 자동 태그가 추가되며 수동 태그는 유지됨 |
| FOLDER-01F | 하단 설정 메뉴의 `프로젝트 기본 설정`에서 base 자동 태그 변경 | base와 모든 직속 디렉터리의 Markdown 파일에 변경 사항이 반영됨 |
| FOLDER-01G | 이름이 `폴더 1`인 Project를 열고 루트·직속 폴더 파일 확인 | 모든 파일의 `tags`에 `폴더_1`이 중복 없이 기록되고 기존 태그·본문은 유지됨 |
| FOLDER-01H | `id` 또는 `tags`가 없는 파일이 섞인 Project 선택 | 변경 대상이 있을 때만 인덱싱 로딩이 표시되고 완료 후 MainScreen으로 자동 진입 |
| FOLDER-01I | 이미 인덱싱된 Project를 다시 선택 | 인덱싱 화면 없이 진입하고 파일 mtime과 내용이 불필요하게 변경되지 않음 |
| FOLDER-01J | 설정 메뉴에서 Project 이름 변경 | 실제 Project 디렉터리와 bookmark가 새 경로로 바뀌고 현재 파일 상대 경로는 유지되며, 모든 Markdown의 기존 프로젝트 태그만 새 이름 태그로 교체됨 |
| FOLDER-01J-1 | 기존 Project 이름, 잘못된 이름 또는 대소문자만 다른 이름 입력 | 변경 버튼이 비활성화되거나 실패 안내가 표시되고 실제 디렉터리는 유지됨 |
| FOLDER-02 | `3. Character` 진입 후 TopBar 뒤로가기 | pager에는 `3. Character`의 직속 Markdown 파일만 보이고 뒤로가면 프로젝트 루트 파일로 복귀 |
| FOLDER-03 | 빈 폴더 선택 | 화면이 사라지지 않고 빈 상태, 폴더명, TopBar 뒤로가기가 유지됨 |
| FOLDER-04 | 파일 dropdown 열기 | 현재 폴더의 파일만 표시되고 다른 폴더 항목은 표시되지 않음 |
| FOLDER-05 | 빈 General 폴더에서 `새 파일`, 제목 `도입` 입력 | 미리보기에 `도입.md`가 표시되고 생성 후 즉시 편집 가능 |
| FOLDER-05A | Default 폴더에서 제목 `도입` 입력 | 다음 번호가 반영된 `N. 도입.md`를 미리 보여주고 같은 이름으로 생성 |
| FOLDER-05B | 제목에 `.md`, `/`, 앞뒤 공백 또는 기존 파일명을 입력 | 오류를 표시하고 생성 버튼이 비활성화됨 |
| FOLDER-06 | 서로 다른 폴더의 `same.md`를 각각 편집 | 두 내용과 cache·저장이 섞이지 않음 |
| FOLDER-07 | 입력 후 500ms 전에 다른 폴더로 전환 | 이전 폴더 입력도 정상 저장됨 |
| FOLDER-08 | `3. Character` 파일 선택 후 앱 재시작 | 같은 폴더와 파일로 복원 |
| FOLDER-09 | `3. Character` 파일 rename | 같은 폴더에서 이름만 바뀌고 내용 유지 |
| FOLDER-10 | 현재 폴더를 외부에서 삭제 후 창 활성화 | stale write 없이 base로 복귀 |
| FOLDER-11 | 우측 하단 초기화 FAB를 누르고 확인 | Vault 선택 화면으로 이동하고 실제 프로젝트 파일은 그대로 유지 |

---

## 3. Default 정책

| ID | 절차 | 합격 기준 |
|---|---|---|
| NUMBER-01 | Default 폴더 열기 | `0`, `2`, `10`, 번호 없는 파일 순으로 표시 |
| NUMBER-02 | 위 상태에서 `새 파일`, 제목 `제목` 입력 | `11. 제목.md` 생성 |
| NUMBER-03 | 번호 파일이 없는 빈 일반 Default 디렉터리에서 제목 `제목` 입력 | `1. 제목.md` 생성 |
| NUMBER-03A | 현재 schemaVersion에서 사용자가 루트 Plot을 끈 Default Project에서 제목 `제목` 입력 | 명시적인 비-Plot 설정 계약에 따라 `0. 제목.md` 생성 |
| NUMBER-04 | General 폴더에서 제목 `제목` 입력 | 숫자 접두사 없는 `제목.md` 생성 후 이름순 정렬 |
| NUMBER-05 | 직속 Default 폴더에서 문서 아이콘을 위·아래로 드래그 | 드래그 중 1부터 새 순번이 미리 보이고 drop 후 실제 파일명도 같은 순번으로 변경 |
| NUMBER-06 | 현재 schemaVersion에서 사용자가 루트 Plot을 끈 Default Project에서 문서 아이콘을 드래그 | 드래그 중 0부터 새 순번이 미리 보이고 drop 후 실제 파일명도 같은 순번으로 변경 |
| NUMBER-07 | General 또는 번호 없는 Default 파일의 문서 아이콘을 드래그 | 순서 변경 gesture가 시작되지 않고 파일명과 목록 순서가 유지됨 |

---

## 4. Default + Plot 정책

| ID | 절차 | 합격 기준 |
|---|---|---|
| PLOT-01 | 새 Project 루트 또는 Scene의 Plot 단계 행에서 `+` 선택 | 해당 단계가 미리 선택되고 제목을 입력하기 전에는 생성되지 않음. Plot 폴더 행과 상단 파일 메뉴에는 일반 `새 파일`이 없음 |
| PLOT-02 | 제목 `제목`, 발단 파일이 없는 상태에서 발단 선택 | `1-1. 제목.md` 미리보기 후 파일과 frontmatter `plot: 1) 발단` 생성 |
| PLOT-02A | 새 Project 루트에서 프롤로그 첫 파일 생성 | `0-1. 제목.md` 미리보기 후 파일과 frontmatter `plot: 0) 프롤로그` 생성 |
| PLOT-03 | 기존 `1-1`이 있는 상태에서 제목 `제목`, 발단 선택 | `1-2. 제목.md` 미리보기 후 생성 |
| PLOT-04 | 문서 아이콘을 같은 단계 안에서 드래그 후 drop | 별도 저장 버튼 없이 표시 순서대로 단계 내부 순번이 1부터 다시 기록됨 |
| PLOT-05 | 발단 파일의 문서 아이콘을 전개 방향으로 드래그 후 drop | frontmatter가 `2) 전개`로 바뀌고 파일명이 `2-N. 제목.md`로 변경됨 |
| PLOT-06 | 한 행 임계값보다 짧게 드래그 후 놓기 | 순서·frontmatter·실제 파일명이 변경되지 않음 |
| PLOT-07 | 단계가 없거나 알 수 없는 외부 파일 열기 | 미분류 마지막에 표시되고 자동 수정되지 않음 |
| PLOT-08 | frontmatter와 파일명 단계 코드가 불일치 | frontmatter 단계로 표시되고 순서 저장 시 파일명이 정규화됨 |

---

## 5. 플랫폼 순서

1. Desktop에서 NAV-01~03, FOLDER-01~11과 FOLDER-01A-1~2, NUMBER-01~07, PLOT-01~08을 수행한다.
2. Android DocumentsUI에서 NAV-01~03, FOLDER-01~11과 FOLDER-01A-1~2, NUMBER-01~07, PLOT-01~08을 수행한다.
3. Android에서는 앱 재시작 후 persisted URI permission과 폴더 파일 bookmark 복원을 함께 확인한다.

실패 시 운영체제, provider, 현재 폴더 상대 경로, 기대 결과, 실제 결과와 재현 절차를 기록한다.

---

## 6. 최근 Desktop UI 점검 결과

2026-08-30 Desktop 앱에서 실제 파일을 변경하지 않는 smoke test를 수행했다.

- 사이드바의 디렉터리 목록, 선택 강조, 설정 아이콘과 추가 버튼 표시: 통과
- 일반 디렉터리 설정의 이름 입력, Default/Plot/General, 자동 태그, 삭제·취소·저장 배치: 통과
- Markdown 파일 1개가 든 디렉터리의 삭제 확인창에서 파일 수와 영구 삭제 경고 표시: 통과 후 취소
- Project 기본 설정에서 이름 입력과 삭제 버튼을 숨기고 유형·Plot·자동 태그만 표시: 통과
- 디렉터리 추가에서 Default가 기본·첫 항목이고 Plot이 Default 아래, General이 다음 항목으로 표시: 통과 후 취소

이 점검에서는 이름·설정 저장과 영구 삭제를 실행하지 않았으므로 실제 Project 데이터 변경은 없다.

---

## 7. 2026-08-30 Desktop 통합 UI 테스트

테스트 전용 Vault `build/ui-test-vault-20260830`과 Project `UI 통합 테스트`에서 실제 생성·이름 변경·재시작을 수행했다.
기존 사용자 Vault의 파일은 변경하지 않았다.

통과:

- `PROJECT-01`, `PROJECT-02`: 기본 `1. Concept`, `2. Outline`, `3. Character`, `4. Scene`과 유형·태그 설정 생성. 당시 Project 루트는 Default 비-Plot 정책이었으며 현재의 Default + Plot 기본값은 재검증 대상
- `NUMBER-03`, `NUMBER-03A`, `NUMBER-04`: Concept은 `1`, `2`, Project 루트는 `0`, `1`, General은 번호 없이 생성(당시 Project 루트 Default 비-Plot 정책에서 수행한 역사적 결과)
- `PLOT-02`: `1-0. Opening.md`, `plot: 1) 발단`, Project·Scene 관리 태그 생성(당시 0 시작 정책에서 수행한 역사적 테스트 결과. 현재의 1 시작 `PLOT-02`로 대체되었으며 재검증 대상)
- 실행 중 폴더별 마지막 파일 복원: Concept의 `2. 두 번째 아이디어`, Character의 `Villain`을 각각 복원
- `FOLDER-01D-1`: `3. Character`를 `3. Cast`로 변경하고 실제 폴더·설정 key·파일·마지막 선택을 유지
- `FOLDER-01D-4`: Markdown 2개와 영구 삭제 경고를 표시하는 확인창까지 통과 후 취소
- `FOLDER-01D-5`: 테스트용 `keep.txt`를 감지해 삭제를 차단하고 문제 항목명을 표시
- 생성된 모든 Markdown에 `id`, `UI_통합_테스트` 태그와 폴더별 관리 태그가 반영됨
- `FOLDER-01H`: frontmatter 없는 `2. Outline/raw external note.md`를 추가한 뒤 재진입하면 로딩 전환 후 `id`와 `UI_통합_테스트` 태그를 보완하고 결과 popup 없이 MainScreen으로 이동
- 재시작 시 별도 결과 popup 없이 MainScreen으로 직접 진입

발견 후 수정·재검증 완료:

- 두 번째 이후 파일 생성 직후 새 파일 대신 목록의 첫 파일로 되돌아감. 실제 파일·번호·frontmatter 생성은 정상이다.
- `FOLDER-08`: 종료 전 `3. Cast / Villain`이었으나 재시작 후 `3. Cast / Hero`가 선택됐다. Project와 폴더는 정상 복원됐다.

두 현상은 `fileList/currentIndex`가 갱신될 때 pager가 목표 페이지로 이동하기 전에 임시 0번 페이지의
`onPageChanged`가 실행되어 bookmark를 덮어쓰는 선택 경쟁이었다. 2026-08-31 최초 pager 방출을 제외한
`settledPage`만 선택 이벤트로 처리하도록 수정했다.

- 세 번째 General 파일 `Mentor.md` 생성 직후 `3. Cast / Mentor` 유지: 통과
- 앱 재시작 후 `3. Cast / Mentor` 복원: 통과
- 수평 스크롤로 `Villain` 선택 후 앱 재시작 시 `3. Cast / Villain` 복원: 통과

영구 삭제는 실행하지 않았으며 테스트 Vault와 `keep.txt`는 재현용으로 유지한다.

Android DocumentsUI 검증은 2026-08-31 사용자 결정에 따라 완료로 간주하고 다음 구현 단계로 진행했다.
새로 추가된 Project 이름 변경은 공통 로직 JVM 자동 테스트와 Android 컴파일을 우선 합격 기준으로 삼고,
실기기 회귀 시 `FOLDER-01J~01J-1`을 함께 확인한다.

---

## 8. Vault 폴더 사용 방식 — 2026-09-07

### 8.1 Desktop 사용자 검증 완료

사용자가 실제 화면에서 다음 흐름을 검증했다.

- 미설정 Vault 폴더 선택 시 `프로젝트로 설정`, `일반 폴더로 전환`, `취소` 확인창 표시
- 취소 시 현재 선택과 실제 폴더 내용 유지
- 일반 폴더 진입 후 이름순 파일 탐색·본문 편집·파일/직속 디렉터리 생성과 이름 변경
- 일반 폴더에서 자동 번호·Plot·Project 설정·커밋과 ID/Project 태그 자동 생성 미노출
- 일반 폴더와 마지막 파일의 앱 재시작 복원, 빈 폴더의 샘플 Markdown 미생성
- 일반 폴더의 `프로젝트로 설정…` 실행 후 기본 네 디렉터리 생성·재사용과 Project 기능 활성화
- Project의 pending 본문 저장 후 일반 폴더 전환

사용자가 이미 검증한 Default·Plot 순서 변경 화면도 PASS로 유지한다.

### 8.2 자동 검증

`FileManagerWorkspaceTest`는 다음을 JVM 임시 디렉터리에서 검증한다.

| 범위 | 합격 기준 |
|---|---|
| 미설정 폴더 취소 | bookmark·설정·Markdown과 디렉터리 목록이 변하지 않음 |
| 일반 폴더 | 읽기·편집·생성·이름 변경에도 `.machum.json`, `.machum/`, ID·Project 태그가 생기지 않음 |
| 분류와 선택 위치 | 일반 폴더는 상단 목록에만, Project와 미분류 후보는 하단 Project 목록에만 포함 |
| Vault별 설정 | `.machum-vault.json`에 직속 상대 이름만 기록하고 다른 Vault의 분류와 섞이지 않음 |
| 이동 가능성 | Vault를 다른 경로로 옮기고 다시 선택해도 일반 폴더와 마지막 Project를 복원 |
| Project 복귀 | 일반 폴더에서 상단 `프로젝트`를 선택하면 마지막 Project를 즉시 열고, 삭제됐다면 Project 선택 화면으로 이동 |
| 재시작 | 일반 폴더와 마지막 파일을 확인창·인덱싱 없이 복원 |
| 프로젝트 설정 | 누락된 기본 네 디렉터리만 생성하고 기존 동명 폴더·본문·비템플릿 폴더를 유지 |
| 경로 충돌 | 쓰기 전에 중단하고 사용자가 일반 폴더 선택으로 전환 가능 |
| 저장 전환 | Project pending 저장을 flush한 뒤 일반 폴더 상태로 교체 |
| 외부 삭제 | 삭제된 현재 탐색 루트의 pending 저장을 취소하고 bookmark·cache를 비워 경로 재생성을 방지 |
| legacy workflow | 기존 Vault 선택과 새 Vault 생성 모두 `.workflow/`를 만들지 않음 |

### 8.3 추가 화면 확인 대상

| ID | 절차 | 합격 기준 |
|---|---|---|
| LOCATION-01 | 일반 폴더에서 사이드바 상단의 폴더 이름/chevron을 누른다 | 첫 행 `프로젝트`와 일반 폴더 목록이 열리고 현재 위치에 check가 표시됨 |
| LOCATION-02 | 상단 목록에서 다른 일반 폴더를 선택한다 | 해당 폴더의 하이라키와 파일로 전환되고 하단 Project 표시가 일반 폴더 이름으로 바뀌지 않음 |
| LOCATION-03 | 하단 Project 선택기를 연다 | 지정한 일반 폴더는 제외되고 Project와 아직 분류하지 않은 후보만 표시됨 |
| LOCATION-04 | 일반 폴더에서 상단 `프로젝트`를 선택한다 | 마지막 작업 Project로 바로 전환되고 상단은 `프로젝트`, 하단은 현재 Project 이름을 표시함 |
| LOCATION-05 | Project에서 상단 `프로젝트`/chevron을 누른다 | 일반 폴더 목록이 열리고 원하는 일반 폴더로 다시 전환 가능 |
| LOCATION-06 | 마지막 Project를 앱 밖에서 삭제한 뒤 일반 폴더의 상단 `프로젝트`를 선택한다 | 오류 없이 Project 선택 화면으로 이동하고 stale 기록을 제거함 |
| VAULT-CONFIG-01 | 새 빈 Vault를 선택만 한다 | `.machum-vault.json`이 생성되지 않음 |
| VAULT-CONFIG-02 | 일반 폴더를 확정하거나 Project를 연다 | Vault 루트에 `.machum-vault.json`이 생성되고 상대 폴더 이름만 기록됨 |
| WORKFLOW-01 | 새 빈 Vault를 만들거나 기존 빈 Vault를 선택한다 | 앱이 `.workflow/`를 생성하지 않음. 기존 폴더가 이미 있다면 삭제하거나 변경하지 않음 |
| EXTERNAL-ROOT-01 | 저장 대기 중인 현재 Vault 직속 폴더를 앱 밖에서 삭제한다 | 앱이 크래시하거나 폴더를 재생성하지 않고 해당 선택을 해제해 선택 화면으로 이동 |

### 8.4 초기화·저장 수명 정비 — 2026-09-08

자동 검증은 실제 사용자 Vault 대신 임시 디렉터리와 제어 가능한 DataStore를 사용했다.

- FileManager 생성자의 무작업, 최초 복원의 단일 실행·동시 요청 직렬화, 취소·읽기 실패 후 재시도
- Project와 마지막 파일 복원·ID/태그 인덱싱, General의 비인덱싱 복원
- 손상된 Project 설정의 오류 전달, 중간 메모리 정리, 저장된 선택 보존과 설정 복구 후 재시도
- 이전 저장 등록 해제와 새 등록 보존, launch/잠금 대기 중에도 요청 시점의 저장 callback 유지
- debounce 소유자 취소 후 이미 요청된 flush 완료, 실제 ViewModelStore.clear()의 콜백 해제

전체 JVM 290개를 2회 통과했다(두 번째는 `--rerun-tasks`, 모두 실패·오류·skip 0).
Desktop `compileKotlin`, Android 앱 `compileDebugKotlin`도 통과했다.
Android Activity 재생성 및 아래 화면 동작은 이번 자동 검증에 포함되지 않았으며 사용자의 화면 확인 대상으로 남긴다.

- 앱 재실행 시 로딩 후 마지막 Project/General·선택 파일로 복귀하고, Vault 선택용 중복 창이 생기지 않는지
- 테스트 편집 창을 최대화한 뒤 Vault 선택으로 나가면 작은 일반 창으로 복귀하는지
- 본문 입력 직후 정상 종료·재실행하면 마지막 입력이 보존되는지(Android는 onStop 후 복귀)

플랫폼 flush 없이 ViewModelStore만 직접 비우는 동작과 OS의 강제 프로세스 종료는 저장 보장 조건이 아니다.

### 8.5 하이라키 단일 snapshot — 2026-09-08

`MainViewModelHierarchyTest`는 eager collector로 관찰한 snapshot마다 폴더 목록과 content map의 key,
파일 소속 폴더, Plot 항목과 파일 순서, 현재 폴더와 선택 key/index/currentFile의 일치를 확인한다.
rename, 현재 Default 순서 변경, 비현재 Plot 순서 변경, Project 전환 경합과 빈 폴더 선택에 이 검증을 적용했다.
빈 폴더의 선택 key·currentFile은 null이고 index는 0이어야 한다.

`MainViewModelCommitTest`의 연속 Project 복원은 선택 파일 삭제 후 첫 파일 fallback,
다음 복원으로 파일 재등장 후 기존 선택 유지, 완료 marker 이후 하이라키와 bookmark 경로 일치를 추가 확인했다.
기존 파일명 변경의 editor session·본문 유지 테스트도 단일 snapshot API로 검증한다.

전체 JVM 291개를 2회 통과했다(두 번째는 `--rerun-tasks`, 모두 실패·오류·skip 0).
Desktop `compileKotlin`, Android 앱 `compileDebugKotlin`도 통과했다.
실제 Vault나 UI는 조작하지 않았으며 다음 화면 확인은 사용자에게 남긴다.

- 빈 폴더와 파일이 있는 폴더를 번갈아 선택할 때 이전 파일명이 남거나 다른 파일이 잠시 선택되지 않는지
- 파일명·Default/Plot 순서를 바꾸면 사이드바와 편집 영역의 선택이 함께 유지되는지
- 파일이 추가·삭제되는 Project 복원 뒤 목록과 선택 파일이 함께 갱신되는지

### 8.6 읽기·저장 책임 분리 — 2026-09-08

회귀 범위:

- Project/General 및 bookmark 전환과 무관하게 `readMarkdown`이 bytes·mtime·누락 ID를 그대로 유지
- 명시적 ID/Project 태그 보정의 캡처된 Project 이름 사용, 기존 ID·수동 태그·Plot·미관리 metadata 보존과 반복 무변경
- ID 없는 외부 파일의 Plot 하이라키·본문 로드 무변형, 실제 편집은 cache에 반영하고 flush 때에만 디스크 저장
- Project/General에서 읽기만 한 파일의 이름 변경 성공·실패가 원문을 다시 쓰지 않으며 실제 pending 편집은 보존
- 관리 태그 변경의 ID-only 누락 보정과 General 비보정
- Plot 순서 변경 취소 시 원래 파일명·원문 bytes·설정·선택 위치 복구

전체 JVM 306개(49 suites)를 2회 통과했다. 두 번째는 `--rerun-tasks`이며 실패·오류·skip은 모두 0이다.
Desktop `compileKotlin`, Android 앱 `compileDebugKotlin`도 통과했다.

화면 확인은 다음으로 한정한다. 실제 Vault와 UI는 이 자동 검증에서 조작하지 않는다.

- Project를 열어 둔 채 외부 Markdown을 추가해도 탐색·열기만으로 ID/태그가 생기지 않는지
- 해당 문서를 편집·저장하거나 Project를 다시 열면 ID와 프로젝트 태그가 추가되는지
- 일반 폴더에서는 여전히 ID/Project 태그가 자동으로 붙지 않는지
- 읽기만 한 문서 이름을 바꾸어도 본문이 재저장되지 않고, 편집 직후 이름을 바꾸면 마지막 입력은 보존되는지

커밋 조회·복원 관련 절차는 [커밋 수동 테스트](commit-manual-test.md#읽기-전용-검사와-원문-복원--2026-09-08)에 기록한다.

### 8.7 자동 저장 오류·취소 안전성 — 2026-09-08

회귀 범위:

- flush가 기존 저장 Job의 종료를 기다리거나 실제 저장하는 도중 취소되어도 미저장값을 다음 flush로 재시도
- 취소·실패 중 들어온 최신 입력을 과거 값으로 덮지 않고, 명시적으로 폐기된 pending을 되살리지 않음
- 자동 저장 실패를 기존 오류 상태에 전달하고 다른 파일의 저장·후속 재시도를 유지
- 정상 취소와 대체된 작업의 늦은 오류는 실패 알림으로 처리하지 않음
- 실패 뒤 Job이 없는 pending도 파일 삭제 정리에서 폐기
- 임시 파일 경로를 디렉터리로 막아 실제 FileKit 저장 실패를 유도한 후, editor cache·오류 알림·경로 복구 후 재저장 검증

전체 JVM 314개(49 suites)를 2회 통과했다. 두 번째는 `--rerun-tasks`이며 실패·오류·skip은 모두 0이다.
Desktop `compileKotlin`, Android 앱 `compileDebugKotlin`도 통과했다. 첫 전체 실행에서 취소 write를 정상 완료로
모델링한 테스트 1건의 오류를 수정한 뒤 위 두 성공 실행을 확인했다.

UI는 기존 오류창을 재사용한다. 알림을 닫아도 입력은 메모리에 남고, 저장을 계속 실패하는 동안 전환·정상 종료는
중단된다. 프로세스 강제 종료 후 메모리 내용 복구와 이미 시작한 provider I/O의 즉시 중단은 이번 보장에 포함하지 않는다.
실제 Vault와 화면은 조작하지 않는다. 사용자 화면 확인 시 쓰기 실패를 실제 문서에서 강제로 만들 필요는 없다.

### 8.8 저장·단일 파일 이름 변경 순서 보장 — 2026-09-08

자동 검증은 제어 가능한 지연 쓰기와 임시 파일만 사용한다. 별도 UI·저장 서비스·파일별 잠금을 추가하지 않고,
자동 저장과 flush가 공유하는 쓰기 잠금을 단일 파일 rename에서도 사용한다.

회귀 범위:

- 취소돼도 늦게 완료하는 이전 쓰기가 최신 쓰기보다 먼저 완료되어 과거 본문으로 덮어쓰지 않음
- 쓰기를 기다리는 flush의 취소는 이미 시작한 자동 저장을 취소하지 않고, 성공한 값을 중복 저장하지 않음
- flush 자체의 쓰기 도중 취소하면 미저장값은 다음 flush에서 재시도
- rename 대기 뒤 old key의 예약을 제거하고 new key로 옮겨 이전 경로에 후속 쓰기가 실행되지 않음
- rename이 pending을 잠시 꺼낸 동안에도 전환·종료의 flush가 대기하고 새 key의 pending을 처리
- 실제 FileKit 쓰기를 지연한 상태에서 rename 대기·추가 편집·명시적 flush·대기 취소를 검증
- 기존 읽기 전용 rename의 원문 bytes·mtime 보존 및 편집 cache·editor session 유지 회귀를 계속 수행

전체 JVM 321개(49 suites)를 2회 통과했다. 두 번째는 `--rerun-tasks`이며 실패·오류·skip은 모두 0이다.
저장 코디네이터 21개와 파일명 변경 통합 8개가 포함되며, 이번에 추가한 회귀는 7개다.
Desktop `compileKotlin`, Android 앱 `compileDebugKotlin`도 강제 재실행을 포함해 통과했다.

실제 Vault와 화면은 조작하지 않는다. 화면 확인 시에는 테스트 문서에 입력한 직후 이름을 바꾸고 정상 종료·재실행하여
새 이름의 파일에 마지막 입력이 남고 이전 이름의 파일이 다시 생기지 않는지만 확인하면 된다.

잠금 대기는 취소할 수 있지만 물리 이름 변경에 진입한 후에는 runtime 경로 반영까지 마친 뒤 취소를 전달한다.
외부 프로그램의 동시 수정, OS/provider 쓰기의 즉시 중단, 프로세스 kill과 폴더 rename/delete의 중간 실패 복구는
이 단일 파일 쓰기 순서 보장 범위가 아니다.

### 8.9 폴더 이름 변경 취소·실패 복구 1차 — 2026-09-08

이 단계는 FileManager가 폴더명·설정·선택 bookmark를 변경하다 실패하거나 취소되는 경우의 원상복구를 다룬다.
실제 폴더 변경 결과를 확보한 뒤 취소를 처리하고, 복구는 취소된 caller와 독립적으로 완료한다. 일반 실패와 복구
실패는 구분해 기존 오류 UI로 전달한다. 새 저장 서비스나 별도의 복구 화면은 추가하지 않는다.

복구 기준:

- 원래 폴더와 그 안의 Markdown 원문은 유지되고 대상 이름의 폴더는 남지 않음
- Project 설정의 원문과 메모리 설정, fileIds 경로 및 저장된/메모리 선택 bookmark가 원래 상태와 일치
- 실제 취소와 주입된 CancellationException은 복구 후 다시 전달되며 이후 이름 변경 재시도가 가능
- General에는 rollback을 이유로 `.machum.json`이 생기지 않음
- 복구 자체가 실패하면 성공/null/단순 취소로 숨기지 않고 원래 실패 및 복구 오류를 별도 예외에 보존
- 일반 실패는 기존 하이라키·편집 cache를 유지하면서 오류 UI에 전달

회귀 주입 지점은 설정 변경 후 선택 bookmark 저장이다. 이 지점의 일반 실패·실제 caller 취소·주입된 취소 예외,
역 rename을 막는 원래 경로 충돌, General 실패와 다른 폴더의 선택 유지까지 검증한다. 설정 파일 쓰기 자체의
부분 실패와 provider rename 직후의 정확한 취소 시점은 별도 I/O hook 없이 결정적으로 주입하지 않았으며,
해당 경계는 코드 검토 범위다. 프로세스 종료나 디스크 고장 후 자동 복구 journal을 제공하는 것은 아니다.

전체 JVM 328개(49 suites)를 2회 통과했다. 두 번째는 `--rerun-tasks`이며 실패·오류·skip은 모두 0이다.
이번에 추가한 회귀는 7개이며 FileManagerFolderTest 19개, MainViewModelHierarchyTest 10개가 포함된다.
Desktop `compileKotlin`, Android 앱 `compileDebugKotlin`도 강제 재실행을 포함해 통과했다.

실제 Vault와 화면은 조작하지 않는다. 사용자는 테스트 폴더를 선택한 상태에서 기존 폴더와 같은 이름으로 변경을
시도하면 변경이 거절되고, 원래 이름·선택·본문이 유지되는지만 확인하면 된다. 실제 문서로 복구 실패를 강제로 만들
필요는 없다.

선행 flush 이후 새 pending 입력의 폴더 경로/관리 태그 이동, 후속 태그 동기화 중 취소, Project 전체 이름 변경과
폴더 부분 삭제 복구는 이 1차 단계에서 완료한 범위가 아니다.

### 8.10 폴더 설정의 pending·태그·경로 이동 2차 — 2026-09-08

이 단계는 8.9의 FileManager 물리 rename 복구 위에 MainViewModel의 저장 fence와 runtime 반영을 연결한다.
태그 쓰기는 별도 FileManager 일괄 직접 쓰기 API가 아니라 기존 DebouncedSaveCoordinator를 사용한다.

새 통합 회귀 6개:

- 실제 bookmark 저장을 멈춘 채 물리 폴더 rename 도중 추가 입력 → 새 경로에 최신 본문·새 관리 태그 저장,
  id/plot/사용자 메타데이터/editor session/선택 유지, 이전 경로 미재생성
- 태그 차이 없는 읽기 전용 rename → 본문 쓰기 0회, raw bytes/mtime/cache instance/session 보존
- 설정-only 태그 저장 실패 → 새 설정과 원하는 cache/pending 유지, 다음 flush에서 본문과 태그 재저장
- base 태그 변경 → 루트와 여러 하위 폴더에 적용하되 각 폴더의 관리/수동 태그 유지
- cache 로드 이후 외부 앱이 변경한 본문 → 사전 조회한 disk를 기준으로 태그만 변경해 외부 본문/수동 태그 유지
- 물리 rename 뒤 작업 coroutine 취소 → runtime과 pending의 새 경로 반영 완료, 취소 전달 후 명시적 flush 가능

기존 태그 동기화 3개 회귀도 MainViewModel 실제 흐름으로 이관했다. id-only 보정, 재적용 raw/mtime 무변경,
General의 id/tags/디스크 설정 무변경, base와 하위 폴더 태그 적용 범위를 유지한다.
ProjectConfig 테스트 10곳은 제거한 `pickProject` 대신 workspace 선택/필요 시 확인 흐름을 통과한다.

테스트 fixture는 구조 변경 Job 완료를 기다린 뒤 flush한다. 구조 변경이 reconciliation mutex를 기다릴 때 flush만
앞서 끝나는 것을 피하며, 이미 취소 완료한 Job의 남은 가상시간 debounce는 join하지 않고 직접 flush한다.

한계: 사전 조회 이후 외부 앱이 다시 쓰는 동시 수정, 프로세스 kill 후 메모리 pending 복구, 임의 ViewModel 폐기,
Project 전체 rename과 폴더 부분 삭제 복구까지 보장하지 않는다. metadata 확정 후 문서 저장이 실패하면 구조 변경은
완료된 상태이며 오류 UI는 부분 저장 실패와 재시도 가능함을 명시한다.

전체 JVM **334개(50 suites)를 2회 통과**했다. 두 번째는 `--rerun-tasks --warning-mode all --no-configuration-cache`이며
실패·오류·skip 모두 0이다. Desktop `compileKotlin`, Android `compileDebugKotlin`도 강제 재실행을 포함해 통과했다.

실제 Vault와 화면은 조작하지 않았다. 사용자 화면 확인 시 테스트 문서에 입력 직후 폴더명/자동 태그를 변경하고,
재실행 후 새 경로에 최신 본문·태그가 남으며 이전 폴더가 재생성되지 않는지 확인하면 된다.

### 8.11 파일 생성 요청의 작업 공간 정체성 — 2026-09-08

MainScreen의 파일 생성 요청을 workspace 위치·종류, 대상 폴더와 초기 Plot 단계를 담은 하나의 nullable 상태로
통합했다. 작업 공간 전환/해제 시 이전 요청을 폐기하고, 생성 확인도 고정된 요청을 ViewModel에 전달한다.
자동 검증: Compose 상태 수명 3개와 임시 Vault의 ViewModel 통합 5개를 추가했고, 최종 전체 JVM
**342개 / 52 suites 통과**(실패·오류·skip 0). Desktop·Android 컴파일도 통과했다.
실제 Vault와 앱 화면은 자동 조작하지 않는다. 아래는 사용자가 확인할 항목이며 아직 화면 검증 완료를 뜻하지 않는다.

| ID | 절차 | 합격 기준 |
|---|---|---|
| CREATE-REQUEST-01 | 다른 폴더를 보고 있는 상태에서 drawer의 `3. Character` 새 파일 선택 | Character로 이동하고 drawer가 닫힌 뒤 팝업이 한 번 열림. 입력한 파일은 Character에만 생성 |
| CREATE-REQUEST-02 | `4. Scene`의 위기 단계 `+`로 팝업을 열고 취소한 뒤 빈 루트의 `새 파일` 선택 | 첫 팝업에는 위기가 미리 선택되며, 두 번째 팝업에는 이전 제목·단계가 남지 않음 |
| CREATE-REQUEST-03 | 폴더 이동·drawer 닫기 중 다른 Project/일반 폴더 전환이 가능한 경우 전환 | 같은 상대 폴더명이 있어도 이전 생성 팝업이 새 공간에 열리지 않고 파일도 생성되지 않음 |
| CREATE-REQUEST-04 | 일반 폴더에서 파일 생성 후 프로젝트로 설정하고 다시 생성 | 일반 폴더는 무번호·관리 메타데이터 없이 생성하고, 전환 후 새 요청에는 Project 정책 적용 |
| CREATE-REQUEST-05 | 파일 팝업에서 Enter 확인, 취소 후 재열기, 작은 창에서 긴 제목 입력 | 중복 생성 없이 확인되며 새 요청의 입력·미리보기·포커스와 버튼 접근이 정상 |

### 8.12 사이드바 컴포지션·드롭다운 상태 정비 — 2026-09-08

후보 2·3의 확인 범위는 상단 workspace 선택기, 하단 Project 선택기와 설정 메뉴다. 메뉴는 한 번에 하나만
열리며 workspace 위치·종류가 바뀌면 닫혀야 한다. 폴더 context menu, 펼침 상태, 순서 드래그와 각 dialog는
기존 동작을 유지한다. 아래는 사용자 수동 검증 항목이며, 실제 앱 화면 검증 완료를 뜻하지 않는다.

사전 설계·테스트 계획 검토와 완료 diff 독립 검토를 거쳤고 지적사항은 없었다. 관련 회귀 **28개 / 6 suites**,
최종 전체 JVM **342개 / 52 suites**가 모두 통과했다(실패·오류·skip 0). Desktop·Android 컴파일과
`git diff --check`도 통과했다. 새 구현을 그대로 재현하는 enum 테스트나 테스트용 상태 계층은 추가하지 않았다.

| ID | 절차 | 합격 기준 |
|---|---|---|
| DRAWER-MENU-01 | workspace·Project·설정 메뉴를 차례로 열기 | 한 번에 하나만 열리고 이전 메뉴의 늦은 닫기 처리가 새 메뉴를 닫지 않음 |
| DRAWER-MENU-02 | 메뉴를 연 상태에서 workspace 전환이 발생하는 경우 확인 | 새 위치 또는 일반→Project 전환 후 이전 메뉴가 닫히고 새 위치의 항목으로 다시 열림 |
| DRAWER-MENU-03 | 메뉴에서 workspace 전환·프로젝트 기본 설정·이름 변경 실행 | 메뉴가 닫힌 뒤 기존 화면 전환 또는 dialog가 시작되며 이전 팝업이 남지 않음 |
| DRAWER-MENU-04 | 작은 창에서 각 선택기 열기, 키보드로 선택·닫기 | 기존 라벨·아이콘·버튼 너비·팝업 앵커 위치와 키보드 동작 유지 |

선택 목록/체크 표시와 설정 항목은 기존 `NAV-02/03`, `LOCATION-01~05`, `FOLDER-01F/J` 절차를 재사용한다.
폴더 context menu와 Default/Plot 순서 드래그도 기존 절차를 유지한다. 자동 회귀와 컴파일 통과만으로
드롭다운의 실제 앵커·키보드 동작까지 검증했다고 판단하지 않는다.

### 8.13 디렉터리·Project·Vault 이름 검증 공통화 — 2026-09-08

후보 4는 5개 화면의 입력 오류 계산을 공유한다. 아래는 사용자 수동 검증 항목이며 실제 Vault와 앱 화면은
자동 조작하지 않는다. 생성·설정의 내용 배치와 버튼 동작은 기존 상태를 유지한다.

새 정책 테스트 10개와 완료 diff의 독립 검토 후 관련 회귀 **52개 / 5 suites**, 최종 전체 JVM
**352개 / 53 suites**가 통과했다(실패·오류·skip 0). Desktop·Android 컴파일과 `git diff --check`도 통과했다.

| ID | 절차 | 합격 기준 |
|---|---|---|
| DIRECTORY-NAME-01 | 디렉터리·Project·Vault 생성에서 입력을 비우거나 공백만 입력한 뒤 ` Draft`, `CON`, `.hidden` 입력 | 빈 입력은 오류 없이 확인 비활성화. 나머지는 공백 또는 화면에 맞는 이름 오류가 표시되며 제출 불가 |
| DIRECTORY-NAME-02 | 디렉터리/Project 생성에서 기존 이름의 대소문자 변형 입력, 별도 이름 `Draft.md` 입력 | 기존 이름은 중복 오류. `.md` 이름은 오류 없음(다른 충돌이 없는 경우) |
| DIRECTORY-NAME-03 | 디렉터리 설정에서 이름을 그대로 두고 태그 등 설정만 변경, Project 이름 변경에서 현재 이름 그대로 두기 | 디렉터리 설정 저장은 가능하고 Project 변경 버튼은 비활성화. 기본 설정처럼 이름 변경을 허용하지 않는 화면도 저장 가능 |
| DIRECTORY-NAME-04 | 디렉터리/Project 이름 변경에서 현재 이름의 대소문자만 변경하거나 다른 기존 이름 입력 | 각각 대소문자 변경 오류와 중복 오류가 표시되며 기존 이름 유지 |
| DIRECTORY-NAME-05 | Project·Vault 생성에서 Enter와 확인 버튼 사용, Vault 상위 폴더 미선택 상태 확인 | 중복 제출 차단 유지. Vault는 상위 폴더 선택 전 생성 불가. 처리 실패 뒤 기존 재시도 흐름 유지 |

Unicode 대소문자 변경과 다른 항목의 lowercase 중복이 겹치면 대소문자 변경 문구를 먼저 표시한다.
예를 들어 현재 이름 `I\u0307`, 입력 `i\u0307`, 다른 이름 `\u0130` 조합이며, 여기서 `\u` 표기는 Unicode
코드 포인트를 뜻한다. 거부 결과는 유지하고 Project 이름 변경의 표시 우선순위만 통일했다. 이 경계는 자동 테스트로 확인한다.

## 9. 종료 기준

2026-09-07 자동 검증: 전환 flush를 막은 상태에서 이전 Project의 파일/폴더 생성, 폴더 설정 변경,
Project 이름 변경과 삭제 확인을 큐에 넣고 전환 완료 후 모두 폐기되는지 확인했다. 저장 실패 시 구조 변경 차단,
저장/전환 도중 실제 취소의 전파와 잠금 해제, 동시 같은 이름 Project 생성의 단일 성공도 검증했다.
전체 JVM 테스트 277개와 Android 소스 컴파일은 통과했으며, 아래 Desktop/Android 화면 항목의 완료 여부와는 별개다.

- Desktop 필수 항목 전체 PASS
- Android DocumentsUI 필수 항목 전체 PASS
- 자동 `jvmTest`, JVM 컴파일, Android debug build PASS
- 실패 항목의 후속 작업 기록

2026-09-08 우선순위 조정에 따라 위 검증 뒤에도 에디터 내부 작업을 자동 재개하지 않는다.
먼저 [비에디터 정비 후보](product-roadmap.md#비에디터-컴포지션팝업-정비-후보--2026-09-08)를 검토하고,
앱 핵심 흐름 완성도와 사용자 화면 확인이 충분해지면 에디터 후속 범위를 다시 논의한다.
