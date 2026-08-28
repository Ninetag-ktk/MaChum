# MaChum 제품 모델과 기능 로드맵

> 역할: 제품 정책, 폴더-존 모델, frontmatter 정책, 기능 우선순위의 source of truth  
> 마지막 검토: 2026-08-28  
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
4. 파일 구분은 `.machum.json`이 선언하는 **폴더-존 모델**을 사용한다.

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

## 3. 폴더-존 정책

### 3.1 원칙

- 파일 자동 생성은 모든 폴더에서 가능하다.
- 자동 넘버링은 `numbered` 폴더에서만 사용한다.
- 아이디어 폴더는 임의 중첩을 허용한다.
- 폴더는 모듈이자 파일 스와이프의 스코프다.
- 재귀 탐색은 폴더 발견에만 사용하고 파일을 하나의 목록으로 평탄화하지 않는다.

### 3.2 폴더 유형

| 유형 | numbered | plot | 용도 |
|---|---:|---:|---|
| `general` | 아니오 | 아니오 | 일반 노트, worksheet, 캐릭터 |
| `plot` | 아니오 | 예 | 장면처럼 서사 위치를 갖는 리소스 |
| `numbered` | 예 | 예 | 순차 원고 |

능력 관계는 `general ⊂ plot ⊂ numbered`다. `autoTags`는 유형과 독립적인 속성이다.

설정되지 않은 외부 폴더는 `general`로 취급한다. base `""`의 제품 기본값은 `numbered`지만 사용자가 변경할 수 있다.

### 3.3 프로젝트 설정

```json
{
  "folders": {
    "": {
      "type": "numbered",
      "autoTags": ["당신을_구하던_삶"]
    },
    "Character": {
      "type": "general",
      "autoTags": ["캐릭터"]
    },
    "Scene": {
      "type": "plot",
      "autoTags": ["장면구상"]
    }
  },
  "fileIds": {}
}
```

- `folders`: 프로젝트 상대 폴더 경로에서 `FolderConfig`로의 map
- `fileIds`: 향후 rename·이동·커밋 추적에 사용할 파일 ID map
- 구 `workflow`, `workflowLastModified` 필드는 읽을 때 무시

현재 상태:

| 항목 | 상태 |
|---|---|
| `ProjectConfig`, `FolderConfig`, `FolderType` | 검증 완료 |
| 구 스키마 무시와 JSON round-trip | 검증 완료 |
| `.machum.json` 파일 생성 | 구현됨 |
| 설정을 현재 프로젝트 상태로 노출 | 미구현 |
| 기본 base 설정 자동 기록 | 미구현 |
| 폴더 동작에 설정 적용 | 미구현 |

---

## 4. frontmatter 정책

관리 키는 `id`, `tags`, `aliases`, `plot`이다. 서로 다른 키에 쓰므로 정책도 독립적으로 적용한다.

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

현재 고정 값:

1. `1) 발단`
2. `2) 전개`
3. `3) 위기`
4. `4) 절정`
5. `5) 결말`

- `plot`, `numbered` 폴더에 적용
- 생성 시 기본값은 미설정
- 편집 화면에서 dropdown으로 선택

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

- plot 단계의 정규 순서 사용
- `plot=null`은 “미설정”으로 마지막
- general/plot 폴더의 그룹 내 정렬은 파일명
- numbered 폴더는 숫자 prefix

---

## 6. 탐색과 파일 생성

### 6.1 폴더가 스와이프 스코프

- 앱은 항상 하나의 현재 폴더 안에 있다.
- `listFile(folder)`는 그 폴더 직속 `.md`만 반환한다.
- 하위 폴더의 파일은 현재 pager에 섞지 않는다.
- `listFolders(project)`는 dot 폴더를 제외하고 하위 폴더를 재귀 발견한다.
- 프로젝트를 열면 base `""` 원고 스코프에 진입한다.

### 6.2 폴더 전환

프로젝트 내부 폴더 트리를 dropdown 또는 bottom sheet로 제공한다.

각 폴더 항목은 다음 정보를 표시한다.

- 상대 경로와 이름
- 폴더 유형 아이콘
- 하위 폴더 존재 여부
- 필요할 경우 파일 수

### 6.3 파일 생성

- `numbered`: 마지막에서 다음으로 넘기거나 `+`를 사용해 다음 번호 생성
- `general`, `plot`: 이름을 입력해 번호 없이 생성
- 모든 유형: aliases와 autoTags 적용
- plot 값은 생성 시 비워 둠

### 6.4 넘버링

- 0부터 시작
- 파일명: `"{n}. {제목}"`
- 숫자 prefix로 정렬
- 다음 번호는 현재 최댓값 + 1
- 번호가 없는 외부 파일의 배치 정책은 구현 전에 결정 필요

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
| 저장·외부 변경 충돌 검증 | 구현·검증 필요 | M | P0 |
| 에디터 파서·직렬화 테스트 | 검증 완료 | M | P0 |
| Table 비정형 행 정규화 | 검증 완료 | M | P0 |
| ProjectConfig 상태 연결 | 부분 구현 | M | P1 |
| 상대 경로/ID 기반 파일 cache | 미구현 | M | P1 |
| `listFolders`와 현재 폴더 | 미구현 | L | P1 |
| numbered 정렬·생성 | 부분 구현 | M | P1 |
| 파일·폴더 전환 UI | 미구현 | L | P1 |
| autoTags | 부분 구현 | L | P1 |
| aliases UI·자동 입력 | 부분 구현 | M | P1 |
| plot UI·그룹핑 | 부분 구현 | L | P1 |
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

1. 저장 debounce와 외부 변경 경합: key별 debounce·취소 자동 검증 완료, 플랫폼 수동 검증 남음
2. 파서·직렬화·BlockOperations 테스트: 완료
3. Table 비정형 행 정규화: 완료

자동화하기 어려운 Desktop 외부 편집·Android SAF·포커스·실제 키보드/마우스 상호작용은
[P0 수동 테스트](p0-manual-test.md)를 따른다.

### 2단계: 폴더-존 최소 수직 흐름

1. ProjectConfig 상태 연결
2. 상대 경로 또는 ID 기반 cache
3. 폴더 발견
4. 현재 폴더 상태
5. 폴더 전환 UI
6. numbered 정렬과 생성

### 3단계: frontmatter 자동화

1. aliases
2. autoTags
3. plot UI
4. 메타 인덱스와 그룹핑

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

커밋 기능은 제품의 핵심 차별점이지만 폴더-존보다 먼저 구현하면 파일 경로와 정체성 모델을 재작업할 가능성이 높다.

---

## 10. 확정 사항과 미결 사항

### 확정

- workflow 네비게이션 은퇴
- 폴더-존 모델
- 폴더 유형 `general`, `plot`, `numbered`
- `autoTags`는 유형과 독립
- base 프로젝트 폴더가 원고 랜딩 스코프
- 폴더가 파일 스와이프 스코프
- numbered는 0부터 시작
- plot 5단계 고정값
- 관리 frontmatter 키와 원형 보존 계약
- Table 셀 selection은 장기적으로 사각형 방식

### 미결

- 번호 없는 외부 파일의 numbered 폴더 내 정렬 위치
- 폴더 전환 UI의 최종 형태
- base autoTags 변경 시 대규모 동기화 UX
- 사용자 정의 선택 필드
- 커밋 저장 단위와 스냅샷 형식
- 외부 변경이 미저장 입력을 덮을 때 사용자 알림 여부
