# MaChum 프로젝트 파일 탐색 수동 테스트

> 역할: 폴더 전환 UI, 파일 생명주기, Desktop·Android SAF 상호작용의 P1 검증 절차
>
> 관련 정책: [product-roadmap.md](product-roadmap.md), [architecture.md](architecture.md)
>
> 마지막 갱신: 2026-09-03

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

## 8. Vault 탐색 위치와 Project 전환 계획 — 미구현·검증 대기

> 상태: **PENDING (2026-09-03 기준 미구현)**
>
> 아래 항목은 구현 완료로 간주하거나 기존 PASS 결과에 합산하지 않는다. 구현 후 Desktop과 Android DocumentsUI에서
> 각각 수행하고, 그때 플랫폼 순서와 종료 기준의 필수 범위에 편입한다. 6~7절의 역사적 결과는 이 계획의 검증 근거가 아니다.

다음과 같이 Vault 루트의 직속 디렉터리를 준비한다.

```text
Vault/
├── 기존 작품/                 # 유효한 .machum.json이 있는 Project
├── 레거시 Default 작품/       # 구 schemaVersion, 루트 Default 비-Plot
├── 레거시 General 작품/       # 구 schemaVersion, 루트 General
├── 현재 Plot 해제 작품/       # 현재 schemaVersion, 사용자가 Plot을 끈 Project
├── 소재 정리/                 # .machum.json이 없는 일반 Vault 폴더
│   ├── 첫 문장.md
│   └── 인물 메모.md
├── 필사/                      # .machum.json이 없는 일반 Vault 폴더
│   └── 문장.md
├── 전환 대상/
│   ├── 초안.md
│   └── 1. Concept/기존 메모.md
├── 전환 충돌/                 # `2. Outline`이라는 일반 파일이 존재
└── 설정 충돌/                 # 읽을 수 없는 .machum.json이 존재
```

### 8.1 Vault 일반 폴더와 상단 탐색 위치 선택기

| ID | 절차 | 예정 합격 기준 |
|---|---|---|
| LOCATION-01 | 위 Vault를 선택하고 상단의 기존 `하이라키` 영역에서 탐색 위치 선택기를 연다 | Vault 루트 직속 디렉터리가 `프로젝트`와 `Vault 폴더`로 구분되어 표시되고, 유효한 `.machum.json`이 있는 디렉터리만 Project로 분류됨 |
| LOCATION-02 | 선택기에서 `소재 정리`를 선택한다 | 상단 현재 위치가 `소재 정리`로 바뀌고 해당 디렉터리의 파일·하위 폴더만 하이라키에 표시되며, 마지막으로 선택한 Project 상태와 일반 폴더 위치가 서로 덮어쓰이지 않음 |
| LOCATION-03 | `소재 정리`에서 `필사`, `기존 작품`, 다시 `소재 정리` 순으로 전환한다 | 매번 선택한 탐색 위치로만 하이라키가 전환되고 Project와 일반 Vault 폴더의 파일 목록·선택 상태가 섞이지 않음 |
| LOCATION-04 | `소재 정리`와 `필사`의 파일 목록을 확인하고 새 문서를 만든다 | 일반 Vault 폴더에서는 Plot 단계와 번호 순서 편집을 표시하지 않고 이름순으로 정렬하며, 새 파일명에도 숫자 접두사를 자동 부여하지 않음 |
| LOCATION-05 | Project와 일반 Vault 폴더 각각에서 위치 항목의 메뉴를 연다 | `프로젝트로 전환…`은 일반 Vault 폴더에만 표시되고 이미 Project인 위치에는 표시되지 않음 |

### 8.2 일반 Vault 폴더의 비변경 보장과 Project 전용 기능

| ID | 절차 | 예정 합격 기준 |
|---|---|---|
| VAULT-FOLDER-01 | `소재 정리`의 전체 항목 목록, 파일 hash·mtime과 frontmatter를 기록한 뒤 폴더를 열고 파일을 읽고 닫는다 | `.machum.json`, `.machum/`, 기본 네 디렉터리가 생성되지 않고 기존 파일의 hash·mtime·이름·frontmatter가 모두 유지됨 |
| VAULT-FOLDER-02 | `id`, `tags`, `plot`이 없는 `소재 정리/첫 문장.md`를 열고 다른 위치로 이동한다 | Project 인덱싱이 실행되지 않으며 `id`, 프로젝트 태그, `plot`을 비롯한 frontmatter가 추가되지 않음 |
| VAULT-FOLDER-03 | 일반 Vault 폴더를 연 상태에서 상단·하단 action과 컨텍스트 메뉴를 확인한다 | Project 설정, Plot 설정, Project 이름 변경과 Commit/Diff/Restore action은 표시되지 않고 일반 문서·폴더 탐색 action만 표시됨 |
| VAULT-FOLDER-04 | 일반 Vault 폴더에서 문서를 편집·저장한 뒤 Project로 전환한다 | 사용자가 편집한 해당 문서만 저장되고, Project의 pending save·bookmark·commit 대상과 일반 폴더의 상태가 섞이지 않음 |
| VAULT-FOLDER-05 | 읽을 수 없는 `.machum.json`이 있는 `설정 충돌`을 선택한다 | 일반 Vault 폴더로 조용히 취급하거나 설정 파일을 덮어쓰지 않고 Project 설정 오류와 복구 가능한 안내를 표시함 |

### 8.3 일반 Vault 폴더를 Project로 전환

| ID | 절차 | 예정 합격 기준 |
|---|---|---|
| CONVERT-01 | `전환 대상`에서 `프로젝트로 전환…`을 선택한다 | 확인창에 디렉터리 역할 변경, `.machum.json` 생성, 기본 네 디렉터리 생성·재사용, Project 인덱싱 예정 항목이 실행 전 표시되고 취소 시 어떤 파일도 변경되지 않음 |
| CONVERT-02 | `전환 대상`의 확인창에서 전환을 확정한다 | `.machum.json`, `2. Outline`, `3. Character`, `4. Scene`이 생성되고 기존 `1. Concept`은 새로 만들거나 교체하지 않고 그대로 재사용됨 |
| CONVERT-03 | 전환 후 `.machum.json`을 확인한다 | 루트와 `4. Scene`은 Default + Plot, `1. Concept`·`2. Outline`은 Default, `3. Character`는 General로 등록되고 현재 schemaVersion이 기록됨 |
| CONVERT-04 | 재사용된 `1. Concept/기존 메모.md`와 새로 만든 세 디렉터리를 확인한다 | 재사용 디렉터리의 기존 내용은 보존되고 새 디렉터리는 비어 있으며 제목 placeholder나 샘플 Markdown은 자동 생성되지 않음 |
| CONVERT-05 | 전환 전후 `초안.md`와 `기존 메모.md`의 본문·파일명을 비교한다 | 기존 Markdown의 본문과 파일명은 유지되고 Project 인덱싱으로 `id`, Project 이름 태그와 해당 폴더의 관리 태그만 중복 없이 보완되며 기존 수동 태그는 보존되고 `plot`은 자동 추가되지 않음 |
| CONVERT-06 | 전환 직후 하이라키와 위치 선택기를 확인한다 | `전환 대상`이 `Vault 폴더`에서 `프로젝트` 그룹으로 이동하고 `초안.md`는 이름이나 `plot`을 자동 변경하지 않은 채 루트 Plot의 `미분류`에 표시됨 |
| CONVERT-07 | 전환 직후 Commit 영역과 저장소를 확인한 뒤 최초 Commit을 실행한다 | Project 전용 Commit action은 전환 성공 뒤에만 표시되고 전환 자체로 자동 Commit을 만들지 않으며, 최초 Commit은 전환된 Project 범위만 추적함 |
| CONVERT-08 | `전환 대상`에서 전환을 다시 시도하거나 앱을 재시작한다 | 다시 전환하거나 기본 디렉터리를 중복 생성하지 않고 일반 Project 열기 흐름으로 진입함 |

### 8.4 전환 충돌과 원자성

| ID | 절차 | 예정 합격 기준 |
|---|---|---|
| CONFLICT-01 | `2. Outline` 일반 파일이 있는 `전환 충돌`에서 전환을 확정한다 | 경로 충돌과 문제 항목을 실행 전에 알리고 `.machum.json`이나 나머지 기본 디렉터리를 만들지 않으며 기존 항목을 변경하지 않음 |
| CONFLICT-02 | 기본 디렉터리 생성 또는 `.machum.json` 기록 중 실패하도록 테스트 provider를 구성해 전환한다 | 이번 전환에서 새로 만든 항목만 정리되고 재사용한 디렉터리와 기존 사용자 파일은 보존되며 반쪽짜리 Project로 노출되지 않음 |
| CONFLICT-03 | Project 인덱싱 중 한 Markdown 쓰기를 실패시킨다 | 전환 완료 여부와 인덱싱 실패 범위를 명확히 표시하고 실패 파일을 자동 rename·삭제하지 않으며, 재시도 시 이미 부여한 `id`나 태그가 중복되지 않음 |
| CONFLICT-04 | 전환 대상에 숨김 폴더, 비-Markdown 파일과 임의 이름의 기존 직속 디렉터리를 함께 둔 뒤 전환한다 | 기본 네 디렉터리 외 기존 항목을 삭제·이동·rename하지 않고, 지원 탐색 범위 밖 항목도 전환 과정에서 변경하지 않음 |

### 8.5 기존 Project 루트의 일회성 Plot 마이그레이션

| ID | 절차 | 예정 합격 기준 |
|---|---|---|
| ROOT-MIGRATION-01 | 유효한 `.machum.json`이 있고 schemaVersion이 없거나 구버전이며 루트가 Default 비-Plot인 `레거시 Default 작품`을 처음 연다 | 루트만 Default + Plot으로 한 번 전환되고 현재 schemaVersion이 기록되며, 기존 Markdown의 파일명·본문·`plot`은 자동 변경되지 않고 단계 없는 파일은 `미분류`에 표시됨 |
| ROOT-MIGRATION-02 | 위 Project를 닫았다가 다시 열고, 이후 사용자가 루트 Plot을 끈 뒤 다시 연다 | schemaVersion이 이미 현재이므로 마이그레이션을 반복하지 않고 사용자가 끈 Plot 설정을 그대로 보존함 |
| ROOT-MIGRATION-03 | schemaVersion이 없거나 구버전이며 루트가 General인 `레거시 General 작품`을 연다 | schemaVersion은 갱신할 수 있지만 루트 General 유형과 무번호·이름순 정책은 보존되고 Plot을 강제로 켜지 않음 |
| ROOT-MIGRATION-04 | 현재 schemaVersion이며 루트 Default의 Plot을 사용자가 끈 `현재 Plot 해제 작품`을 연다 | 현재 사용자 설정으로 판단해 Plot을 다시 켜지 않고 `.machum.json`과 Markdown을 불필요하게 쓰지 않음 |
| ROOT-MIGRATION-05 | `.machum.json`이 없는 `소재 정리`와 `필사`를 반복해서 연다 | 파일명 형태나 하위 디렉터리 구성과 무관하게 Plot 마이그레이션을 실행하거나 schemaVersion·`.machum.json`을 생성하지 않음 |
| ROOT-MIGRATION-06 | 기본 네 디렉터리 일부가 없는 레거시 Project를 마이그레이션한다 | 마이그레이션 범위는 Project 루트 설정과 schemaVersion에 한정되고 기본 네 디렉터리를 소급 생성하거나 직속 폴더 설정을 변경하지 않음 |
| ROOT-MIGRATION-07 | 이미 `id`와 필요한 관리 태그가 모두 있는 레거시 Project를 마이그레이션한 뒤 Markdown hash·mtime을 비교한다 | 설정 파일만 갱신되고 Markdown은 인덱싱 때문에 재작성되지 않으며, 별도의 미인덱싱 파일이 있다면 기존 Project 인덱싱 계약대로 `id`와 관리 태그만 보완되고 이름·`plot`은 유지됨 |
| ROOT-MIGRATION-08 | 읽을 수 없거나 유효하지 않은 `.machum.json`이 있는 디렉터리를 연다 | 추측으로 설정을 마이그레이션하거나 덮어쓰지 않고 오류를 표시하며 기존 파일과 디렉터리를 변경하지 않음 |

---

## 9. 종료 기준

- Desktop 필수 항목 전체 PASS
- Android DocumentsUI 필수 항목 전체 PASS
- 자동 `jvmTest`, JVM 컴파일, Android debug build PASS
- 실패 항목의 후속 작업 기록

완료 후 [에디터 컴포지션 정비 게이트](product-roadmap.md#에디터-컴포지션-정비-게이트-2단계-완료-직후)로 진행한다.
