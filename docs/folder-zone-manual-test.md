# MaChum 프로젝트 파일 탐색 수동 테스트

> 역할: 폴더 전환 UI, 파일 생명주기, Desktop·Android SAF 상호작용의 P1 검증 절차
>
> 관련 정책: [product-roadmap.md](product-roadmap.md), [architecture.md](architecture.md)
>
> 마지막 갱신: 2026-08-29

---

## 1. 실행 전 준비

```bash
./gradlew :composeApp:jvmTest
./gradlew :composeApp:compileKotlinJvm
./gradlew :androidApp:assembleDebug
```

전용 vault에 base, `Character`, `Scene`, `Scene/Act1`, `.hidden`, 빈 폴더를 만들고,
서로 다른 폴더에 `same.md`를 하나씩 둔다. 기본 Default 폴더인 base에는 `0. 영.md`,
`2. 둘.md`, `10. 열.md`, `외부.md`를 준비한다.

---

## 2. 공통 탐색·저장

| ID | 절차 | 합격 기준 |
|---|---|---|
| NAV-01 | TopBar 왼쪽 메뉴 열기 | drawer 본문에는 Project 루트 없이 현재 Project의 직속 하위 디렉터리만 표시 |
| NAV-02 | 하단 Project 영역에서 다른 Project 선택 | dropdown이 닫히고 선택 Project의 디렉터리로 전환 |
| NAV-03 | 하단 설정 아이콘에서 Vault 다시 선택 | Vault 선택 화면으로 이동하고 실제 파일은 변경되지 않음 |
| FOLDER-01 | drawer의 Folder 목록 확인 | 프로젝트 직속 비숨김 폴더만 보이고 `.hidden`, `Scene/Act1`은 보이지 않음 |
| FOLDER-01A | `디렉터리 추가`에서 이름·유형·자동 태그 입력 | 직속 디렉터리가 생성되고 목록에 즉시 표시되며 `.machum.json`에 같은 설정이 저장됨 |
| FOLDER-01A-1 | `디렉터리 추가` 유형 목록 확인 | Default가 첫 번째이자 기본 선택이고 General이 두 번째이며 Plot은 Default 아래 체크박스로 표시됨 |
| FOLDER-01A-2 | Default에서 Plot 선택 후 General로 전환 | Plot 선택이 즉시 해제되고 General에는 Plot 옵션이 표시되지 않음 |
| FOLDER-01B | 기존 이름, `.hidden`, `../Outside`, `CON`으로 생성 시도 | 추가 버튼이 비활성화되고 디렉터리가 생성되지 않음 |
| FOLDER-01C | 자동 태그가 설정된 디렉터리에서 새 파일 생성 | 새 파일의 frontmatter `tags`에 설정 태그가 중복 없이 기록됨 |
| FOLDER-01D | 디렉터리 설정 아이콘에서 Default/General 또는 Plot 변경 | 설정이 저장되고 기존 파일명은 바뀌지 않으며 이후 생성·정렬부터 적용됨 |
| FOLDER-01E | 기존 파일에 수동 태그와 자동 태그가 있는 상태에서 자동 태그 변경 | 이전 자동 태그만 제거되고 새 자동 태그가 추가되며 수동 태그는 유지됨 |
| FOLDER-01F | 하단 설정 메뉴의 `프로젝트 기본 설정`에서 base 자동 태그 변경 | base와 모든 직속 디렉터리의 Markdown 파일에 변경 사항이 반영됨 |
| FOLDER-01G | 이름이 `폴더 1`인 Project를 열고 루트·직속 폴더 파일 확인 | 모든 파일의 `tags`에 `폴더_1`이 중복 없이 기록되고 기존 태그·본문은 유지됨 |
| FOLDER-01H | `id` 또는 `tags`가 없는 파일이 섞인 Project 선택 | 변경 대상이 있을 때만 인덱싱 로딩이 표시되고 완료 후 MainScreen으로 자동 진입 |
| FOLDER-01I | 이미 인덱싱된 Project를 다시 선택 | 인덱싱 화면 없이 진입하고 파일 mtime과 내용이 불필요하게 변경되지 않음 |
| FOLDER-02 | `Character` 진입 후 TopBar 뒤로가기 | pager에는 `Character`의 직속 Markdown 파일만 보이고 뒤로가면 프로젝트 루트 파일로 복귀 |
| FOLDER-03 | 빈 폴더 선택 | 화면이 사라지지 않고 빈 상태, 폴더명, TopBar 뒤로가기가 유지됨 |
| FOLDER-04 | 파일 dropdown 열기 | 현재 폴더의 파일만 표시되고 다른 폴더 항목은 표시되지 않음 |
| FOLDER-05 | 빈 General 폴더에서 `새 파일`, 제목 `도입` 입력 | 미리보기에 `도입.md`가 표시되고 생성 후 즉시 편집 가능 |
| FOLDER-05A | Default 폴더에서 제목 `도입` 입력 | 다음 번호가 반영된 `N. 도입.md`를 미리 보여주고 같은 이름으로 생성 |
| FOLDER-05B | 제목에 `.md`, `/`, 앞뒤 공백 또는 기존 파일명을 입력 | 오류를 표시하고 생성 버튼이 비활성화됨 |
| FOLDER-06 | 서로 다른 폴더의 `same.md`를 각각 편집 | 두 내용과 cache·저장이 섞이지 않음 |
| FOLDER-07 | 입력 후 500ms 전에 다른 폴더로 전환 | 이전 폴더 입력도 정상 저장됨 |
| FOLDER-08 | `Character` 파일 선택 후 앱 재시작 | 같은 폴더와 파일로 복원 |
| FOLDER-09 | `Character` 파일 rename | 같은 폴더에서 이름만 바뀌고 내용 유지 |
| FOLDER-10 | 현재 폴더를 외부에서 삭제 후 창 활성화 | stale write 없이 base로 복귀 |
| FOLDER-11 | 우측 하단 초기화 FAB를 누르고 확인 | Vault 선택 화면으로 이동하고 실제 프로젝트 파일은 그대로 유지 |

---

## 3. Default 정책

| ID | 절차 | 합격 기준 |
|---|---|---|
| NUMBER-01 | Default 폴더 열기 | `0`, `2`, `10`, 번호 없는 파일 순으로 표시 |
| NUMBER-02 | 위 상태에서 `새 파일`, 제목 `제목` 입력 | `11. 제목.md` 생성 |
| NUMBER-03 | 번호 파일이 없는 빈 Default 폴더에서 제목 `제목` 입력 | `0. 제목.md` 생성 |
| NUMBER-04 | General 폴더에서 제목 `제목` 입력 | 숫자 접두사 없는 `제목.md` 생성 후 이름순 정렬 |

---

## 4. Default + Plot 정책

| ID | 절차 | 합격 기준 |
|---|---|---|
| PLOT-01 | Plot을 켠 Default 폴더에서 `새 파일` | 제목과 7단계 선택이 모두 완료되기 전에는 생성되지 않음 |
| PLOT-02 | 제목 `제목`, 발단 파일이 없는 상태에서 발단 선택 | `1-1. 제목.md` 미리보기 후 파일과 frontmatter `plot: 1) 발단` 생성 |
| PLOT-03 | 기존 `1-1`이 있는 상태에서 제목 `제목`, 발단 선택 | `1-2. 제목.md` 미리보기 후 생성 |
| PLOT-04 | 순서 편집에서 같은 단계 안으로 드래그 후 저장 | 표시 순서대로 단계 내부 순번이 1부터 다시 기록됨 |
| PLOT-05 | 발단 파일을 전개 그룹으로 이동 후 저장 | frontmatter가 `2) 전개`로 바뀌고 파일명이 `2-N. 제목.md`로 변경됨 |
| PLOT-06 | 저장하지 않고 취소 | frontmatter와 실제 파일명이 변경되지 않음 |
| PLOT-07 | 단계가 없거나 알 수 없는 외부 파일 열기 | 미분류 마지막에 표시되고 자동 수정되지 않음 |
| PLOT-08 | frontmatter와 파일명 단계 코드가 불일치 | frontmatter 단계로 표시되고 순서 저장 시 파일명이 정규화됨 |

---

## 5. 플랫폼 순서

1. Desktop에서 NAV-01~03, FOLDER-01~11과 FOLDER-01A-1~2, NUMBER-01~04, PLOT-01~08을 수행한다.
2. Android DocumentsUI에서 NAV-01~03, FOLDER-01~11과 FOLDER-01A-1~2, NUMBER-01~04, PLOT-01~08을 수행한다.
3. Android에서는 앱 재시작 후 persisted URI permission과 폴더 파일 bookmark 복원을 함께 확인한다.

실패 시 운영체제, provider, 현재 폴더 상대 경로, 기대 결과, 실제 결과와 재현 절차를 기록한다.

---

## 6. 종료 기준

- Desktop 필수 항목 전체 PASS
- Android DocumentsUI 필수 항목 전체 PASS
- 자동 `jvmTest`, JVM 컴파일, Android debug build PASS
- 실패 항목의 후속 작업 기록

완료 후 [에디터 컴포지션 정비 게이트](product-roadmap.md#에디터-컴포지션-정비-게이트-2단계-완료-직후)로 진행한다.
