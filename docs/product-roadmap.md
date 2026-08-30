# MaChum 제품 모델과 기능 로드맵

> 역할: 제품 정책, 프로젝트 파일 구조, frontmatter 정책, 기능 우선순위의 source of truth
> 마지막 검토: 2026-08-29
> 현재 코드 구조: [architecture.md](architecture.md)  
> 마크다운 에디터 설계: [markdown-editor.md](markdown-editor.md)

---

## 1. 제품 방향

MaChum의 목표는 Obsidian과 동일한 vault를 공유하면서 **원고 집필과 버전 추적**을 보조하는 것이다.

초기 구현은 workflow 마크다운 템플릿을 헤더 트리로 파싱하고 프로젝트에 step을 배정하는 방식이었다. 실제 작업물에서는 다음 문제가 확인됐다.

- 조직화 기능이 Obsidian과 중복됨
- 작품마다 번호 체계가 달라 재사용 workflow가 맞지 않음
- 캐릭터·장면·아이디어는 선형 step이 아니라 지속적으로 발전하는 엔티티임
- 정작 제품의 차별점인 커밋과 버전 추적은 미구현 상태로 남음

결정:

1. workflow 템플릿과 배정 네비게이션을 은퇴한다.
2. 조직화는 Obsidian의 폴더·태그·링크에 맡긴다.
3. MaChum은 원고 편집, 폴더별 집필 흐름, 버전 추적에 집중한다.
4. 파일 탐색은 `Vault → Project → File 또는 Folder → File`의 단순한 구조를 사용한다.

---

## 2. 콘텐츠와 폴더 모델

실제 콘텐츠는 크게 다음으로 나뉜다.

| 종류 | 예 | 특성 | 자동 넘버링 |
|---|---|---|---|
| 아이디어·리소스 | 모티브, 캐릭터, 장면 초안 | 발산적·비선형 | 없음 |
| 원고 | 프롤로그, 장·절 | 선형·순차 | 사용 |
| 방법론·참고 | worksheet, 과거 workflow 문서 | 참고용 | 없음 |

### 핵심 위계

```text
vault
└── project
    ├── 0. 프롤로그.md       base 원고 스코프
    ├── 1. 첫 장.md
    ├── Character/           하위 모듈
    │   └── 인물.md
    └── Scene/               하위 모듈
        └── 장면.md
```

프로젝트 디렉터리 자체가 base 폴더이며 설정 경로 `""`로 표현한다.

---

## 3. 프로젝트 파일 구조

### 3.1 원칙

- 파일 자동 생성은 모든 폴더에서 가능하다.
- 자동 넘버링은 `default` 폴더에서 사용한다.
- 프로젝트에는 Markdown 파일과 한 단계의 폴더를 둘 수 있다.
- 폴더에는 Markdown 파일만 두며 중첩 폴더는 앱 탐색 대상에서 제외한다.
- 폴더는 별도 도메인 객체가 아니라 파일 스와이프 범위를 정하는 가벼운 탐색 단위다.
- 현재 폴더의 직속 파일만 표시하고 프로젝트 전체 파일을 평탄화하지 않는다.

### 3.2 폴더 유형

| 유형 | 자동 넘버링 | Plot 옵션 | 용도 |
|---|---:|---:|---|
| `default` | 예 | 선택 가능 | 숫자 접두사를 사용하는 순차 원고. Plot을 켜면 단계별 순번을 함께 사용 |
| `general` | 아니오 | 사용 불가 | 파일명을 그대로 사용하고 이름순으로 정렬하는 자유 형식 |

제품 기본값과 UI 첫 번째 항목은 `Default`다. 두 번째 항목은 `General`이며 Plot은 Default에서만 선택할 수 있다.
Plot을 켜면 단계 코드를 첫 번째 축, 단계 내부의 1부터 시작하는 순번을 두 번째 축으로 사용한다.
General로 변경하면 Plot 옵션은 해제된다.
`autoTags`는 유형과 독립적인 속성이다.

설정되지 않은 외부 폴더와 base `""`는 `default`로 취급하며 사용자가 변경할 수 있다.

### 3.3 프로젝트 설정

```json
{
  "folders": {
    "": {
      "type": "default",
      "plotEnabled": false,
      "autoTags": ["당신을_구하던_삶"]
    },
    "Character": {
      "type": "general",
      "plotEnabled": false,
      "autoTags": ["캐릭터"]
    },
    "Scene": {
      "type": "default",
      "plotEnabled": true,
      "autoTags": ["장면구상"]
    }
  },
  "fileIds": {}
}
```

- `folders`: 프로젝트 상대 폴더 경로에서 `FolderConfig`로의 map
- `fileIds`: 향후 rename·이동·커밋 추적에 사용할 파일 ID map
- 구 `workflow`, `workflowLastModified` 필드는 읽을 때 무시
- 구 유형 `numbered`는 `default`, `plot`은 `default + plotEnabled`로 읽고 다음 저장부터 새 형식만 기록

현재 상태:

| 항목 | 상태 |
|---|---|
| `ProjectConfig`, `FolderConfig`, `FolderType` | 검증 완료 |
| 구 스키마 무시와 JSON round-trip | 검증 완료 |
| `.machum.json` 파일 생성 | 구현됨 |
| 설정을 현재 프로젝트 상태로 노출 | 검증 완료 |
| 기본 base 설정 자동 기록 | 검증 완료 |
| 폴더 동작에 설정 적용 | 미구현 |

---

## 4. frontmatter 정책

관리 키는 `id`, `tags`, `aliases`, `plot`이다. 서로 다른 키에 쓰므로 정책도 독립적으로 적용한다.
모든 파일의 `tags`에는 프로젝트명을 필수 관리 태그로 포함하며, 공백은 `_`로 정규화한다.
프로젝트 선택 시 모든 지원 범위의 Markdown 파일을 먼저 점검하고 누락된 `id`와 프로젝트명 태그만
보완한다. 변경 대상이 있을 때만 로딩 화면을 표시하고 완료 후 자동 진입한다. 파일별 실패는 내부 결과에
집계하며 다른 파일 처리는 계속한다.

```markdown
---
id: a1b2c3d4
tags:
  - 당신을_구하던_삶
  - 장면구상
aliases:
  - 풀네임
plot: 1) 발단
---
```

### 4.1 원형 보존 계약

`NoteFile`은 관리 키만 구조적으로 처리한다.

- 미관리 키, 주석, 키 순서, 원래 포맷을 그대로 보존
- 관리 키도 수정 전에는 verbatim 유지
- 수정한 관리 키만 표준 형태로 정규화
- 리스트는 block, flow, 인라인 CSV를 읽음
- 순수 parse → inject는 본문 앞 빈 줄 정규화를 제외하면 안정적

현재 `NoteFile` 구현과 테스트로 검증됐다.

### 4.2 `id`

- 향후 커밋 정체성과 rename·이동 추적에 사용
- 파일을 처음 열 때 없으면 8자 ID 생성
- `FileManager.readMarkdown()`이 생성한 ID를 즉시 파일에 기록

### 4.3 `tags`

폴더의 관리 태그는 다음처럼 합성한다.

```text
base "" autoTags + 현재 폴더 autoTags
```

설정 변경 시:

1. 이전 관리 태그 제거
2. 새 관리 태그 추가
3. 사용자 수동 태그 보존

base 태그 변경은 프로젝트 전체 파일에, 폴더 태그 변경은 해당 폴더 직속 파일에 적용한다. 관리 태그는 설정이 authority이므로 사용자가 파일에서 직접 지워도 다음 동기화에서 복원될 수 있다.

### 4.4 `aliases`

- 모든 폴더 유형 공통
- 파일 생성 시 파일명을 aliases에 자동 입력
- 이후 전용 UI에서 수정·추가

### 4.5 `plot`

현재 고정 값은 `0) 프롤로그`, `1) 발단`, `2) 전개`, `3) 위기`, `4) 절정`, `5) 결말`,
`6) 에필로그`다. frontmatter `plot`에는 이 문자열을 그대로 기록한다.

- Plot 옵션을 켠 `default` 폴더에 적용
- 생성 시 단계 선택 필수
- 파일명은 `{단계코드}-{단계내순번}. {제목}.md`
- frontmatter 단계가 authority이며 파일명 단계 코드는 순서 저장 시 동기화

---

## 5. 메타 인덱스

폴더 목록과 plot 그룹핑은 열지 않은 파일의 frontmatter도 필요하다. Android SAF와 클라우드 vault에서 매번 전체 파일을 읽지 않도록 세션 인메모리 인덱스를 둔다.

```kotlin
FileMeta(
    id: String?,
    name: String,
    plot: String?,
    tags: List<String>,
    aliases: List<String>,
    mtime: Long,
)
```

수집 정책:

1. 폴더 진입 시 직속 `.md` 순회
2. mtime이 같으면 캐시 재사용
3. mtime이 다르면 frontmatter를 다시 읽음
4. 삭제된 파일은 인덱스에서 제거
5. 첫 구현은 세션 인메모리 map

에디터의 열린 파일 cache와는 별개다. 인덱스는 열지 않은 파일까지 포함한다.

그룹핑 정책:

- Plot 옵션을 켠 Default는 프롤로그부터 에필로그까지 고정 단계순, 각 단계 안에서는 숫자순
- `plot=null` 또는 알 수 없는 값은 “미분류”로 마지막
- General 폴더는 파일명순
- Default 폴더는 숫자 prefix

---

## 6. 탐색과 파일 생성

### 6.1 폴더가 스와이프 스코프

- 앱은 항상 하나의 현재 폴더 안에 있다.
- `listFile(folder)`는 그 폴더 직속 `.md`만 반환한다.
- 다른 폴더의 파일은 현재 pager에 섞지 않는다.
- `listFolders(project)`는 dot 폴더를 제외한 프로젝트 직속 폴더만 반환한다.
- 프로젝트를 열면 base `""` 원고 스코프에 진입한다.

### 6.2 폴더 전환

navigation drawer 본문에는 현재 Project의 직속 Folder만 표시한다. 하단 고정 행의 Project 영역에서
Project dropdown을 열고 설정 아이콘에서 Vault를 다시 선택한다. 폴더에 진입하면 TopBar에 프로젝트
루트로 돌아가는 뒤로가기와 현재 폴더명을 표시한다. 파일 dropdown에는 폴더를 넣지 않는다.

각 폴더 항목은 다음 정보를 표시한다.

- 상대 경로와 이름
- 폴더 유형 아이콘
- 필요할 경우 파일 수

### 6.3 파일 생성

- `Default`: 마지막에서 다음으로 넘기거나 `+`를 사용해 다음 번호 생성
- `General`: 이름을 입력해 번호 없이 생성
- `Default + Plot`: 단계를 필수 선택하고 해당 단계의 최대 순번+1로 생성
- 생성 전 제목을 필수로 입력하고 유형별 최종 `.md` 파일명을 미리 표시
- 금지 문자, 확장자 중복 입력과 같은 폴더의 중복 파일명을 생성 전에 거부
- 모든 유형: aliases와 autoTags 적용
- Plot 파일의 frontmatter는 `{숫자}) {단계}` 형식으로 생성

### 6.4 넘버링

- 0부터 시작
- 파일명: `"{n}. {제목}"`
- 숫자 prefix로 정렬
- 다음 번호는 현재 최댓값 + 1
- 번호가 없는 외부 파일의 배치 정책은 구현 전에 결정 필요

PLOT 순서 편집은 7단계 그룹에서 드래그로 수행한다. 같은 그룹 안의 이동은 순번 변경, 다른 그룹으로의
이동은 단계 변경이며 저장 시 각 단계 순번을 1부터 다시 계산한다. 파일명 충돌을 피하기 위해 임시 이름을
거치는 일괄 rename을 사용한다.

현재 `setFile()`의 `"0. 제목"` 생성은 빈 프로젝트를 열기 위한 임시 fallback이다.

---

## 7. 기능 상태와 우선순위

### 상태 정의

| 상태 | 의미 |
|---|---|
| 미구현 | 코드 없음 |
| 부분 구현 | 일부 코드가 있으나 라이브 흐름 또는 필수 동작 누락 |
| 구현·검증 필요 | 코드가 있으나 자동·수동 검증 부족 |
| 검증 완료 | 정의된 테스트나 검증 시나리오 통과 |

### 우선순위 정의

- `P0`: 데이터 손상·크래시·핵심 회귀 방지
- `P1`: 제품의 핵심 사용 흐름
- `P2`: 편집 생산성과 완성도
- `P3`: 고급 기능과 시각 개선

### 난이도 정의

- `S`: 국소 변경
- `M`: 여러 파일·상태 흐름 변경
- `L`: 화면·상태·파일 계층 동시 변경
- `XL`: 아키텍처와 복합 UI 상호작용 변경

### 로드맵

| 기능 | 상태 | 난이도 | 우선순위 |
|---|---|---:|---:|
| 저장·외부 변경 충돌 검증 | 검증 완료 | M | P0 |
| 에디터 파서·직렬화 테스트 | 검증 완료 | M | P0 |
| Table 비정형 행 정규화 | 검증 완료 | M | P0 |
| ProjectConfig 상태 연결 | 검증 완료 | M | P1 |
| 상대 경로 기반 파일 cache | 구현·자동 검증 완료 | M | P1 |
| `listFolders`와 현재 폴더 | 구현·수동 검증 필요 | L | P1 |
| Default 정렬·생성 | 구현·수동 검증 필요 | M | P1 |
| Vault·Project·Folder 전환 UI | Obsidian형 drawer 구현·수동 검증 필요 | L | P1 |
| autoTags | 설정 편집·기존 파일 동기화 자동 검증 완료 | L | P1 |
| aliases UI·자동 입력 | 부분 구현 | M | P1 |
| plot UI·그룹핑 | 구현·수동 검증 필요 | L | P1 |
| Undo/Redo | 미구현 | L | P1 |
| 커밋 MVP | 미구현 | XL | P1~P2 |
| Cross-block Cut/Paste | 부분 구현 | L~XL | P2 |
| 마우스 드래그 selection | 미구현 | XL | P2 |
| Table 셀 selection | 정책 확정·미구현 | XL | P2~P3 |
| Embed 미리보기 | 비활성 | L | P3 |
| M3 컬러 정리 | 부분 구현 | S~M | P3 |

---

## 8. 권장 구현 순서

### 1단계: 데이터 안정화

1. 저장 debounce와 외부 변경 경합: 자동·플랫폼 수동 검증 완료
2. 파서·직렬화·BlockOperations 테스트: 완료
3. Table 비정형 행 정규화: 완료

자동화하기 어려운 Desktop 외부 편집·Android SAF·포커스·실제 키보드/마우스 상호작용은
[P0 수동 테스트](p0-manual-test.md)를 따른다.

### 2단계: 프로젝트 파일 탐색 최소 흐름

1. ProjectConfig 상태 연결: 완료
2. 상대 경로 기반 cache: 완료
3. 폴더 발견: 완료
4. 현재 폴더 상태: 구현 완료, 수동 통합 검증 필요
5. 탐색 UI: 디렉터리 drawer, 생성 설정 dialog, 하단 Project·설정 메뉴, TopBar 뒤로가기 구현, 수동 검증 필요
6. Default 정렬과 생성: 구현·자동 검증 완료, 플랫폼 수동 검증 필요

플랫폼별 합격 기준은 [프로젝트 파일 탐색 수동 테스트](folder-zone-manual-test.md)에서 관리한다.

### 에디터 컴포지션 정비 게이트: 2단계 완료 직후

폴더 전환 UI와 파일 탐색 회귀 검증을 마친 직후, frontmatter UI를 추가하기 전에 마크다운 에디터의
컴포지션을 정비한다. 폴더 탐색 중에는 상위 화면 상태가 계속 바뀌므로 동시에 진행하지 않고,
Undo/Redo 전에 끝내서 편집 이력과 selection이 불안정한 상태 소유권에 결합되지 않게 한다.

범위:

1. `MarkdownBlockTextField`는 문서 상태와 외부 value 동기화의 소유자로 한정
2. `MarkdownBlockEditor`의 selection·focus·keyboard·dissolve 조정을 작은 coordinator와 Composable로 분리
3. 블록별 callback이 최신 list/index를 참조하는 경계를 명시하고 stale capture 제거
4. `EditorPage`에서 파일 navigation 상태와 에디터 문서 상태의 생명주기 분리
5. 안정 key·parameter stability·불필요한 recomposition을 측정하고 회귀 테스트 추가

완료 조건:

- 기존 parse·dissolve·selection·Table 동작 계약 변경 없음
- 폴더·파일 전환 후 이전 문서의 focus, selection, raw reparse 작업이 새 문서에 개입하지 않음
- 핵심 coordinator를 UI 없이 자동 테스트할 수 있음
- Desktop 에디터 수동 회귀 시나리오 통과

이 작업은 에디터 내부의 목표 지향적 구조 정비다. 프로젝트 전체 책임 분리와 성능 최적화는
아래의 3단계 후 최적화 게이트에서 별도로 수행한다.

### 3단계: frontmatter 자동화

1. aliases
2. autoTags: 설정 편집·기존 파일 동기화 구현 및 자동 검증 완료
3. plot UI: 7단계 생성·그룹 정렬·드래그 순서 편집 구현, 플랫폼 수동 검증 필요
4. 메타 인덱스와 그룹핑

### 최적화 게이트: 3단계 완료 직후

프로젝트 파일 탐색 최소 흐름과 frontmatter·메타 인덱스까지 완료되면 첫 구조 최적화를 진행한다.
이 시점이면 파일 정체성, 탐색, 저장, 메타데이터의 핵심 데이터 흐름이 안정되고,
아직 Undo/Redo와 커밋처럼 구조 의존도가 큰 기능을 쌓기 전이라 재작업 비용도 낮다.

이 게이트에서 말하는 최적화는 동작을 바꾸지 않는 안정화 작업이다.

1. 기준 시나리오 측정: 프로젝트 열기, 폴더 전환, 파일 스와이프, 대형 문서 편집, 외부 변경 반영
2. `FileManager`의 파일 I/O·프로젝트 인덱스·bookmark 책임 분리
3. `MainViewModel`의 cache·선택·외부 감지·저장 orchestration 책임 분리
4. dormant workflow 코드와 사용하지 않는 상태 제거
5. 불필요한 전체 목록 조회·전체 map 복사·Compose 재구성 확인
6. 측정으로 확인된 병목만 최적화하고 P0 및 파일 탐색 회귀 테스트 재실행

완료 조건:

- 대표 규모의 테스트 vault와 측정 기준이 문서화됨
- 주요 계층의 책임 경계가 테스트 가능한 형태로 정리됨
- P0와 파일 탐색 자동·수동 회귀가 모두 통과함
- 측정 결과가 없는 추측성 micro-optimization은 수행하지 않음

이후 기능 개발 중에는 국소적인 정리만 수행한다. 두 번째 성능 최적화는 커밋 MVP가 완성된 뒤
실제 스냅샷·diff 비용까지 포함해 릴리스 후보를 측정할 때 진행한다.

### 4단계: 편집 안전성과 생산성

1. Undo/Redo
2. Cross-block 잘라내기·붙여넣기
3. selection 입력 대체
4. 마우스 드래그
5. Table 셀 selection

### 5단계: 커밋 MVP

파일 정체성과 폴더 이동 정책이 안정된 뒤 시작한다.

---

## 9. 커밋 기능의 결정 항목

구현 전 다음을 확정해야 한다.

- 추적 단위: 파일별 커밋 또는 프로젝트 스냅샷
- 저장 위치: vault를 오염시키지 않는 `.machum/` 숨김 디렉터리
- rename·이동 추적: frontmatter `id`와 `fileIds`의 책임 분배
- diff 단위: 산문 문단 또는 줄
- 복구 정책과 외부 편집 충돌
- Android SAF에서 스냅샷 비용

커밋 기능은 제품의 핵심 차별점이지만 프로젝트 파일 탐색보다 먼저 구현하면 파일 경로와 정체성 모델을 재작업할 가능성이 높다.

---

## 10. 확정 사항과 미결 사항

### 확정

- workflow 네비게이션 은퇴
- `Vault → Project → File 또는 Folder → File` 구조
- 폴더 유형 `default`, `general`과 Default 전용 `plotEnabled` 옵션
- `autoTags`는 유형과 독립
- base 프로젝트 폴더가 원고 랜딩 스코프
- 폴더가 파일 스와이프 스코프
- 기본 폴더 유형은 Default이며 UI 순서는 Default, General
- Default는 0부터 시작
- 관리 frontmatter 키와 원형 보존 계약
- Table 셀 selection은 장기적으로 사각형 방식

### 미결

- 번호 없는 외부 파일의 Default 폴더 내 최종 정렬 위치(현재 임시 정책: 번호 파일 뒤 이름순)
- navigation drawer의 Desktop 대화면 확장 방식
- 디렉터리 생성 UI 컴팩트화: Plot 체크박스를 별도 하위 행 대신 Default 항목 오른쪽에 배치
- 사용자 정의 선택 필드
- 커밋 저장 단위와 스냅샷 형식
- 외부 변경이 미저장 입력을 덮을 때 사용자 알림 여부
