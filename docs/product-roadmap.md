# MaChum 제품 모델과 기능 로드맵

> 역할: 제품 정책, 프로젝트 파일 구조, frontmatter 정책, 기능 우선순위의 source of truth
> 마지막 검토: 2026-09-03
> 현재 코드 구조: [architecture.md](architecture.md)  
> 마크다운 에디터 설계: [markdown-editor.md](markdown-editor.md)

---

## 1. 제품 방향

MaChum의 목표는 Obsidian과 동일한 vault를 공유하면서 **원고 집필과 버전 추적**을 보조하는 것이다.

초기 구현은 workflow 마크다운 템플릿을 헤더 트리로 파싱하고 프로젝트에 step을 배정하는 방식이었다. 실제 작업물에서는 다음 문제가 확인됐다.

- 조직화 기능이 Obsidian과 중복됨
- 작품마다 번호 체계가 달라 재사용 workflow가 맞지 않음
- 캐릭터·장면·아이디어는 선형 step이 아니라 지속적으로 발전하는 엔티티임
- 정작 제품의 차별점인 커밋과 버전 추적은 미구현 상태로 남아 있었음

결정:

1. workflow 템플릿과 배정 네비게이션을 은퇴한다.
2. 조직화는 Obsidian의 폴더·태그·링크에 맡긴다.
3. MaChum은 원고 편집, 폴더별 집필 흐름, 버전 추적에 집중한다.
4. Vault 직속 디렉터리를 탐색 루트로 사용하고, 관리 Project와 일반 Vault 폴더를 역할로 구분한다.
5. Project 전용 자동화는 명시적으로 Project로 인식되거나 전환된 디렉터리에만 적용한다.

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
Vault/
├── 당신을 구하던 삶/           유효한 .machum.json → Project
│   ├── .machum.json
│   ├── 0-1. 프롤로그.md     base 원고 스코프(Default + Plot)
│   ├── 1-1. 첫 장.md
│   ├── 1. Concept/          구상(Default)
│   ├── 2. Outline/          설계(Default)
│   ├── 3. Character/        캐릭터 구체화(General)
│   │   └── 인물.md
│   └── 4. Scene/            장면 구체화(Default + Plot)
│       └── 1-1. 장면.md
├── 소재 정리/                  설정 없음 → 일반 Vault 폴더
│   └── 추가 글감.md
└── 필사/                       설정 없음 → 일반 Vault 폴더
    └── 작품명.md
```

Vault 직속 디렉터리는 물리적으로 동등하다. 유효한 `.machum.json`이 있는 디렉터리만
관리 `Project`로 인식하고, 설정이 없는 디렉터리는 `VAULT_FOLDER`로 연다. 일반 Vault 폴더를
여는 행위는 `.machum.json`을 생성하거나 Project로 암묵적 전환하지 않는다. 설정 파일이 있지만
파싱할 수 없는 경우는 일반 폴더로 조용히 다루거나 덮어쓰지 않고, 손상된 Project 설정으로
표시해 복구 또는 명시적 전환을 요구한다.

Project 디렉터리 자체가 base 폴더이며 설정 경로 `""`로 표현한다. Project 내부의
`General`은 해당 Project가 관리하는 `FolderConfig`의 하나이며, 일반 Vault 폴더의 유형을 뜻하지 않는다.

### 2.1 탐색 루트와 상태 분리(계획)

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

- `activeWorkspace`: 현재 하이라키에 표시하고 편집하는 Vault 직속 디렉터리
- `selectedProject`: 하단 Project 선택기가 가리키는 현재 또는 마지막 관리 Project
- 일반 Vault 폴더로 이동해도 `selectedProject`는 Project로 돌아갈 경로를 위해 유지하되,
  Project 전용 동작은 반드시 `activeWorkspace.kind == PROJECT`인지로 판별한다.
- Project 선택은 두 상태를 모두 갱신하고, 일반 폴더 선택은 `activeWorkspace`만 바꾼다.

이 상태 모델과 Vault 폴더 탐색은 아직 구현되지 않은 계획 범위다.

---

## 3. 프로젝트 파일 구조

### 3.1 원칙

- 이 절의 `FolderConfig`·넘버링·Plot 규칙은 관리 Project와 그 직속 폴더에만 적용한다.
- 파일 자동 생성은 Project 루트와 직속 폴더에서 가능하다.
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
새 Project의 루트는 장별 원고 영역이므로 `Default + Plot`을 기본으로 사용한다. 일반 Default 디렉터리는 1부터 시작한다.
Plot을 켜면 단계 코드를 첫 번째 축, 단계 내부의 1부터 시작하는 순번을 두 번째 축으로 사용한다.
단계 코드 `0`~`6`은 바꾸지 않으며 첫 파일은 프롤로그 `0-1`, 발단 `1-1` 형식이다.
General로 변경하면 Plot 옵션은 해제된다.
`autoTags`는 유형과 독립적인 속성이다.

Project 내부의 설정되지 않은 직속 폴더는 `default`로 취급한다. 반면 `.machum.json`이 없는
Vault 직속 디렉터리는 `VAULT_FOLDER`이며, `default`로 간주하지 않는다.

기존 Project의 루트 Plot 적용은 `schemaVersion`을 사용한 일회성 마이그레이션으로 계획한다.

1. 유효한 `.machum.json`을 읽어 `PROJECT`로 인식한 디렉터리에만 실행한다.
2. `schemaVersion`이 없거나 이전 버전이고 루트가 Default 비-Plot이면 `Default + Plot`으로 보완한다.
3. 기존 루트가 `General`이면 사용자 설정으로 보고 그대로 보존한다.
4. 결과와 현재 `schemaVersion`을 함께 저장해 다시 실행하지 않는다. 이후 사용자가 Plot을 끄면
   버전은 유지되므로 다시 강제로 켜지지 않는다.
5. 마이그레이션은 기존 파일명·`plot` frontmatter를 바꾸지 않는다. 단계가 확인되지 않은 루트 파일은
   Plot의 `미분류`에 남고, 사용자가 단계로 드래그할 때만 이름과 frontmatter를 변경한다.

스키마 버전 필드와 일회성 마이그레이션은 아직 구현되지 않았다.

### 3.3 프로젝트 설정

```json
{
  "schemaVersion": 1,
  "folders": {
    "": {
      "type": "default",
      "plotEnabled": true,
      "autoTags": []
    },
    "1. Concept": {
      "type": "default",
      "plotEnabled": false,
      "autoTags": ["구상"]
    },
    "2. Outline": {
      "type": "default",
      "plotEnabled": false,
      "autoTags": ["설계"]
    },
    "3. Character": {
      "type": "general",
      "plotEnabled": false,
      "autoTags": ["캐릭터"]
    },
    "4. Scene": {
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
- `schemaVersion`: 한 번 적용한 설정 마이그레이션을 다시 강제하지 않기 위한 버전(계획)
- 구 `workflow`, `workflowLastModified` 필드는 읽을 때 무시
- 구 유형 `numbered`는 `default`, `plot`은 `default + plotEnabled`로 읽고 다음 저장부터 새 형식만 기록

새 프로젝트를 만들면 `1. Concept`, `2. Outline`, `3. Character`, `4. Scene`을 순서대로 자동 생성하고,
같은 경로의 설정을 `.machum.json`에 함께 기록한다. 기존 프로젝트를 선택할 때는 이 템플릿을 소급 생성하지 않는다.
`1. Concept`는 모티브·글감·메시지를 순서대로 축적하는 구상 영역이고,
`2. Outline`은 캐릭터 초안·전체 플롯·발단부 구상 등 설계 산출물을 순서대로 축적하는 영역이다.
둘 다 Default이며 첫 파일은 `1. 제목.md`다. 구체화 결과는 `3. Character`(General)와
`4. Scene`(Default + Plot)에 두고, 실제 장별 원고는 Project 루트(Default + Plot)에 둔다.

### 3.4 일반 Vault 폴더의 `프로젝트로 전환…`(계획)

일반 Vault 폴더의 탐색 위치 메뉴와 우클릭/롱프레스 메뉴에 `프로젝트로 전환…`을 제공한다.
즉시 실행하지 않고 확인 다이얼로그에 생성·재사용할 경로, frontmatter 인덱싱, 충돌을 먼저 보여준다.

전환 결과는 다음과 같다.

| 경로 | 설정 | 충돌 처리 |
|---|---|---|
| Project 루트 `""` | `Default + Plot` | 루트는 기존 디렉터리 자체를 사용 |
| `1. Concept` | `Default` | 동명 디렉터리는 재사용하고 설정만 등록 |
| `2. Outline` | `Default` | 동명 디렉터리는 재사용하고 설정만 등록 |
| `3. Character` | `General` | 동명 디렉터리는 재사용하고 설정만 등록 |
| `4. Scene` | `Default + Plot` | 동명 디렉터리는 재사용하고 설정만 등록 |

- 없는 기본 디렉터리만 생성하고, 이미 있는 디렉터리와 그 내용은 그대로 재사용한다.
- 같은 이름의 파일이 있어 필수 디렉터리를 만들 수 없거나, `.machum.json`이 이미 있고 유효한 Project
  설정으로 읽히지 않으면 전환 전 검사에서 중단하고 정확한 충돌 경로를 알린다.
- 기존 Markdown 파일은 이름과 본문을 변경하지 않으며 Plot 단계를 임의 할당하지 않는다. 루트와
  `4. Scene`의 기존 파일은 단계가 명시되지 않았다면 `미분류`에 남는다.
- 설정이 성공적으로 공개된 뒤에만 `ProjectIndexer`를 실행해 누락 `id`와 Project 태그를 보완한다.
  이 후속 인덱싱 범위와 변경 건수도 확인 다이얼로그에 표시한다.

전환은 전체 경로와 쓰기 가능 여부를 먼저 검사한 뒤, 누락 디렉터리 생성 → 임시 설정 파일 쓰기 →
`.machum.json` 공개 순서로 수행한다. 설정 공개 전 실패하면 이 시도가 생성한 파일과 디렉터리만
rollback하고, 전에부터 있던 디렉터리와 파일은 삭제·덮어쓰지 않는다. 설정이 공개되면
해당 폴더를 `PROJECT`로 재분류한다. 후속 인덱싱이 파일별로 부분 실패한 경우는 이미 저장된
frontmatter를 다시 되돌리지 않고 Project 상태를 유지하며, 실패 파일과 재시도 동작을 제공한다.

이 전환 흐름은 아직 구현되지 않았다.

현재 상태:

| 항목 | 상태 |
|---|---|
| `ProjectConfig`, `FolderConfig`, `FolderType` | 검증 완료 |
| 구 스키마 무시와 JSON round-trip | 검증 완료 |
| `.machum.json` 파일 생성 | 구현됨 |
| 설정을 현재 프로젝트 상태로 노출 | 검증 완료 |
| 기본 base 설정 자동 기록 | 검증 완료 |
| 새 프로젝트 기본 4개 디렉터리·설정 자동 생성 | JVM 통합 검증 완료 |
| 폴더 동작에 설정 적용 | 구현·자동 검증 완료 |
| 디렉터리 이름 변경과 설정·ID·bookmark 동기화 | JVM 통합 검증 완료 |
| 안전 확인을 포함한 디렉터리 삭제 | JVM 통합 검증 완료 |
| 실행 중 디렉터리별 마지막 선택 파일 복원 | 구현·자동 검증 완료 |
| `WorkspaceKind`·`activeWorkspace` 상태 분리 | 계획·미구현 |
| 일반 Vault 폴더 비변경 탐색 | 계획·미구현 |
| `프로젝트로 전환…` transaction | 계획·미구현 |
| `schemaVersion` 기반 루트 Plot 마이그레이션 | 계획·미구현 |

---

## 4. frontmatter 정책

관리 키는 `id`, `tags`, `aliases`, `plot`이다. 서로 다른 키에 쓰므로 정책도 독립적으로 적용한다.
관리 Project 범위의 모든 파일 `tags`에는 프로젝트명을 필수 관리 태그로 포함하며, 공백은 `_`로 정규화한다.
Project 선택 시 모든 지원 범위의 Markdown 파일을 먼저 점검하고 누락된 `id`와 프로젝트명 태그만
보완한다. 변경 대상이 있을 때만 로딩 화면을 표시하고 완료 후 자동 진입한다. 파일별 실패는 내부 결과에
집계하며 다른 파일 처리는 계속한다.

일반 Vault 폴더에서는 이 관리 frontmatter 정책을 적용하지 않는다. 파일을 목록에 표시하거나
열어보는 것만으로 `id`, Project 태그, `aliases`, `plot`을 생성·보완하지 않는다.

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
- Project 파일을 처음 열 때 없으면 8자 ID 생성
- Project mode의 `FileManager.readMarkdown()`이 생성한 ID를 즉시 파일에 기록
- 일반 Vault 폴더의 읽기 경로는 ID 보장 로직을 호출하지 않음(계획)

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
이 메타 인덱스와 frontmatter 스캔은 Project 전용이다. 일반 Vault 폴더는 파일 이름·경로·mtime처럼
목록과 편집에 필요한 정보만 읽고, `FileMeta` 인덱싱 대상에 포함하지 않는다(계획).

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

### 6.1 Vault 탐색 위치 선택과 일반 폴더(계획)

현재 navigation drawer 상단의 비상호작 `하이라키` 제목을 탐색 위치 선택기로 바꾼다.
선택기는 위치 이름과 chevron 영역만 클릭할 수 있고, 나머지 빈 도구막대는 선택 영역으로
확장하지 않는다.

```text
Project 표시:    [ 프로젝트 파일 ▾ ]       [루트 새 파일] [새 직속 디렉터리] [닫기]
일반 폴더 표시: [ 소재 정리 ▾ ]          [새 문서] [새 폴더] [닫기]

탐색 위치
  프로젝트
    프로젝트 파일 · 당신을 구하던 삶
  Vault 폴더
    소재 정리
    필사
```

- Project를 보는 동안 상단은 `프로젝트 파일`, 메뉴의 보조 정보에는 실제 Project 이름을 표시한다.
- 일반 Vault 폴더는 실제 디렉터리 이름을 상단에 표시한다.
- 상단은 `activeWorkspace` 전환, 하단 Project 영역은 `selectedProject` 변경과 Project로의 빠른 복귀를 담당한다.
- 일반 폴더에서는 Project 설정·Plot·커밋 action을 숨기거나 비활성화하고,
  `프로젝트로 전환…`을 명시적인 관리 action으로 제공한다.

일반 Vault 폴더의 파일 규칙은 General과 유사하지만 `FolderConfig(type=general)`을 만들지 않는다.

- 사용자가 입력한 파일명을 그대로 사용하고 이름순으로 정렬
- 자동 넘버링, Plot 그룹, 순서 drag, `순번에 편입` 미제공
- 열람·목록 조회 시 `.machum.json`, `id`, Project 태그, aliases, Plot frontmatter 미생성
- `ProjectIndexer`, Project `FileMeta`, `.machum/` commit·diff·restore 대상에서 제외
- 새 문서·이름 변경·본문 편집처럼 사용자가 명시적으로 요청한 파일 변경만 수행

일반 Vault 폴더 탐색은 아직 구현되지 않았다. 첫 범위는 Project 탐색과 같이 현재 루트와
직속 폴더 한 단계로 제한하고, 재귀 lazy tree는 별도 확장으로 다룬다.

### 6.2 폴더가 스와이프 스코프

- 앱은 항상 하나의 현재 폴더 안에 있다.
- `listFile(folder)`는 그 폴더 직속 `.md`만 반환한다.
- 다른 폴더의 파일은 현재 pager에 섞지 않는다.
- `listFolders(project)`는 dot 폴더를 제외한 프로젝트 직속 폴더만 반환한다.
- 프로젝트를 열면 base `""` 원고 스코프에 진입한다.

### 6.3 폴더 전환

navigation drawer 본문에는 현재 Project의 직속 Folder와 현재 탐색 범위의 Markdown 파일을 함께 표시한다.
Project 루트 파일은 폴더 목록 위에 항상 표시하고 모든 직속 폴더의 파일을 동시에 펼친다. 루트는 접는 대상이 아니며,
각 직속 폴더만 기본 펼침 상태에서 disclosure 화살표로 개별 접기·펼치기한다. 폴더 아이콘은 제거하되 Markdown 문서 아이콘은
유지하고, Plot 단계에는 낮은 대비의 numbered-list 아이콘을 사용한다. 파일·Plot 단계에 깊이별 들여쓰기와 옅은 연결선을
표시하며 두 종류 행의 텍스트 시작선을 맞춘다. 전체 트리는 안정 key를 가진 단일 `LazyColumn`으로 스크롤한다.
하단 고정 행의 Project 영역에서 Project dropdown을 열고 설정 아이콘에서 Vault를 다시 선택한다. 폴더에
진입하면 TopBar에 프로젝트 루트로 돌아가는 뒤로가기와 현재 폴더명을 표시한다. TopBar 파일 dropdown에는
폴더를 넣지 않으며 기존 빠른 파일 전환 경로로 유지한다.

각 폴더 항목은 다음 정보를 표시한다.

- 상대 경로와 이름
- 폴더의 접힘 상태를 나타내는 단일 disclosure
- 모든 직속 폴더의 파일과 현재 선택 상태
- 폴더 이름 클릭으로 해당 폴더를 `currentFolder`로 선택하고 pager 전환
- 일반 폴더의 작은 새 파일 `+`
- 일반 폴더 우클릭/롱프레스의 새 파일, 이름·설정 변경, 삭제 메뉴
- Plot 폴더 우클릭/롱프레스의 이름·설정 변경, 삭제 메뉴

현재 구현의 상단 도구막대 제목은 비상호작이다. 탐색 위치 선택기를 구현한 후에도 빈 영역 전체를
클릭 가능한 루트 행으로 사용하지 않는다. `루트 새 파일`, `새 직속 디렉터리`, `닫기`만
명시적인 action으로 제공하고, 삭제는 폴더 컨텍스트 메뉴에서 확인 다이얼로그를 거친다. Tree 전용 치수는 폴더 36dp,
파일 30dp, Plot 단계 28dp, 아이콘 16dp, action 28dp로 둔다. pager용 현재 폴더 목록과
drawer용 전체 폴더 map을 분리해 한눈에 보기를 위해 편집 상태 모델을 평탄화하지 않는다. 지원 범위는 루트와
직속 폴더까지이며 재귀 중첩 폴더는 포함하지 않는다.

### 6.4 파일 생성

- `Default`: 마지막에서 다음으로 넘기거나 `+`를 사용해 다음 번호 생성
- `General`: 이름을 입력해 번호 없이 생성
- `Default + Plot`: 단계를 필수 선택하고 해당 단계의 최대 순번+1로 생성
- 하이라키의 Plot 단계별 `+`: 해당 단계가 미리 선택된 같은 생성 dialog 사용
- Plot 폴더 행의 일반 파일 `+`와 컨텍스트 메뉴의 새 파일은 노출하지 않음
- 생성 전 제목을 필수로 입력하고 유형별 최종 `.md` 파일명을 미리 표시
- 금지 문자, 확장자 중복 입력과 같은 폴더의 중복 파일명을 생성 전에 거부
- 모든 유형: aliases와 autoTags 적용
- Plot 파일의 frontmatter는 `{숫자}) {단계}` 형식으로 생성

### 6.5 넘버링

- 일반 Default 디렉터리는 1부터 시작
- 새 Project 루트의 기본값은 Default + Plot이며 각 Plot 단계 내부에서 1부터 시작
- 일회성 마이그레이션을 통해 Default + Plot이 된 기존 루트의 기존 파일명은 자동 재번호화하지 않음
- 파일명: `"{n}. {제목}"`
- 숫자 prefix로 정렬
- 다음 번호는 현재 최댓값 + 1
- 번호가 없는 외부 파일은 번호 파일 뒤에 이름순으로 배치

Plot 폴더의 하이라키는 7개 단계 가상 그룹과 미분류 그룹을 직접 보여준다. 별도 `플롯 순서 편집` 메뉴는 노출하지 않고
하이라키의 문서 드래그로 같은 단계 안의 순서 또는 인접 단계 배정을 직접 바꾼다. drop 시 각 단계 순번을 1부터 다시 계산하고,
파일명 충돌을 피하기 위해 임시 이름을 거치는 일괄 rename을 사용한다.

### 6.6 하이라키 순서 편집과 파일명 계약

하이라키의 drag & drop은 별도 표시 순서를 저장하는 기능이 아니라 파일명 순번을 편집하는 기능으로 설계한다.

- Markdown 문서 행의 `Description` 아이콘만 드래그 핸들로 사용하며 이름 클릭은 문서 열기를 유지한다.
- 별도 순서 편집 모드나 저장 버튼을 두지 않는다. drag는 메모리 draft만 바꾸고 drop 시 폴더 단위로 단 한 번 디스크에 적용한다.
- `General`은 파일명 이름순 자유 형식이므로 순서 drag를 제공하지 않는다.
- 일반 `Default`는 숫자 접두사가 있는 파일만 같은 폴더 안에서 재정렬하고 1부터 다시 매겨 `{순번}. {제목}.md`로 rename한다.
- `Default + Plot`은 같은 단계 안 또는 인접 단계 사이의 drag로 `plot` frontmatter를, 단계 내부 순서로 1부터 시작하는 파일명 순번을 함께 바꾼다.
- 번호 없는 외부 파일은 초기 구현에서 drag 대상에서 제외하고 목록 뒤에 유지한다. 사용자가 명시적으로 `순번에 편입`할 때만 번호 파일로 바꾼다.
- 폴더 사이 drag는 순서가 아니라 이동이므로 이 기능에 포함하지 않는다.

저장 transaction은 대상 pending save flush, directory snapshot 재검증, 이름 충돌 검증, 고유 임시 이름, 최종 이름의
2단계 rename을 사용한다. Plot은 중간에 frontmatter 단계도 갱신한다. 기존 Plot 일괄 rename 코드를 공통 batch
primitive로 추출해 Default와 공유한다. 완료 후 `fileIds`, bookmark, cache/load state, mtime, pending save,
선택 기억, editor session과 전체 하이라키 map을 새 `FileKey`로 이동해 열린 편집기를 재생성하지 않는다.
실패 시 이전 이름/frontmatter rollback을 시도하고 draft를 폐기해 원래 순서로 되돌린 뒤 해당 폴더 아래에 inline 오류를
표시한다. 커밋에서는 동일한 frontmatter `id`로 rename 또는 rename+수정으로 추적한다.

현재 `setFile()`은 빈 Default + Plot 루트에 `0-1. 제목.md`와 프롤로그 frontmatter를 만들고,
명시적인 Default 비-Plot 루트에는 `0. 제목.md`를 만드는 진입 fallback이다. 계획된 일반 Vault 폴더 mode는
빈 위치를 열기 위해 임의의 Markdown 파일을 자동 생성하지 않는다.

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
| `listFolders`와 현재 폴더 | 핵심 탐색 검증 완료로 간주 | L | P1 |
| Default 정렬·생성 | 자동·플랫폼 핵심 생성 검증 완료로 간주 | M | P1 |
| Project 내부 Folder 전환 UI | Obsidian형 drawer 핵심 검증 완료로 간주 | L | P1 |
| Vault 직속 탐색 루트 분류·상태 분리 | 계획·미구현 | L | P1 |
| 상단 탐색 위치 선택기·일반 Vault 폴더 탐색 | 계획·미구현 | L | P1 |
| 일반 폴더 `프로젝트로 전환…` | 계획·미구현 | L | P1 |
| 기존 Project 루트 Plot 일회성 마이그레이션 | 계획·미구현 | M | P1 |
| Project 이름 변경 | 구현·JVM 자동 검증 완료 | M | P1 |
| autoTags | 설정 편집·기존 파일 동기화 자동 검증 완료 | L | P1 |
| aliases UI·자동 입력 | 부분 구현 | M | P1 |
| plot UI·그룹핑 | 플랫폼 기본 생성 검증 완료로 간주·순서 편집 수동 검증 필요 | L | P1 |
| Undo/Redo | 내용 복원 구현, 자동/Desktop 검증 완료 | L | P1 |
| 커밋 MVP | 생성·이력·줄별 diff·clean restore 구현 및 JVM 검증 완료, Android 수동 검증 필요 | XL | P1 |
| Cross-block Delete/Cut/Paste | 구현·자동/Desktop 검증 완료 | L | P2 |
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
4. 현재 폴더 상태: 구현·핵심 검증 완료로 간주
5. 탐색 UI: 디렉터리 drawer, 생성 설정 dialog, 하단 Project·설정 메뉴, TopBar 뒤로가기 구현·핵심 검증 완료로 간주
6. Default 정렬과 생성: 구현·자동 검증 완료, 플랫폼 핵심 검증 완료로 간주
7. Project 이름 변경: 디렉터리·bookmark·프로젝트명 태그 동기화 구현 및 JVM 자동 검증 완료

플랫폼별 합격 기준은 [프로젝트 파일 탐색 수동 테스트](folder-zone-manual-test.md)에서 관리한다.

### 다음 탐색 단계: Vault workspace 분리(계획)

기존 Project 탐색이 완료된 상태에서 일반 Vault 폴더를 추가할 때는 다음 순서를 고정한다.
순서를 바꾸어 루트 Plot 보완을 먼저 실행하면 설정이 없던 `소재 정리`·`필사`에
`.machum.json`과 관리 frontmatter를 생성할 수 있다.

1. Vault 직속 디렉터리를 쓰기 없이 `PROJECT` / `VAULT_FOLDER` / 손상된 Project로 분류
2. Project I/O와 일반 폴더 I/O를 분리해 일반 폴더 열람의 설정·frontmatter 변경 차단
3. `activeWorkspace`와 `selectedProject` 상태 분리
4. 상단 탐색 위치 선택기와 일반 폴더의 이름순 하이라키 연결
5. `프로젝트로 전환…` 전 검사·transaction·rollback 구현
6. 위 분류를 통과한 기존 Project에만 `schemaVersion` 기반 루트 Default + Plot 마이그레이션 실행
7. 일반 폴더 비변경, 전환 충돌·rollback, 일회성 마이그레이션 재실행 방지를 JVM/SAF에서 검증

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

2026-08-31 1차 정비 완료:

- Pager와 에디터 전체 수명을 `FileKey` 안정 key로 연결
- `EditorPage`의 ViewModel 직접 조회와 페이지별 전체 cache 구독 제거
- 외부 값 교체 revision을 관리하는 UI 비의존 `EditorDocumentValueCoordinator` 추가
- 최초 동일 값·부모 echo·이전 문서 collector의 stale 방출 차단 자동 테스트 추가
- 블록 간 예약 포커스를 불변 `EditorFocusRequest` 하나로 묶고 UI 비의존 `EditorFocusCoordinator`로 이동
- 새 요청의 이전 요청 무효화, stale 완료 무시와 사라진 대상 취소를 자동 테스트
- 외부 문서 revision을 `focusEpoch`로 연결해 이전 문서의 대기 중 포커스 effect 폐기
- Desktop에서 빈 본문 한글 입력, Text→Callout 재구성, 블록 간 방향키 이동과 재진입 저장을 수동 검증
- 기존 `BlockOperations`를 감싸는 UI 비의존 `EditorMutationDispatcher` 추가
- split·merge·reparse·dissolve·raw mode 해제 결과를 `EditorMutation(blocks, focusIntent)`으로 통일
- merge cursor offset, fallback dissolve, silent reparse와 block identity 보존 자동 테스트 추가
- Desktop에서 Callout dissolve → silent reparse와 기존 Text focus 보존을 수동 검증
- `BlockNavigation`의 15개 callback을 `focus`, `mutation`, `selection` action 그룹으로 분리
- 기본 no-op과 그룹별 callback 독립성을 자동 테스트하고 Desktop에서 세 역할 경로를 수동 검증
- Standard·DL Callout의 body 생성·진입·탈출 결정을 UI 비의존 `CalloutBodyPolicy`로 통합
- 공통 body runtime·재귀 renderer를 적용하고 정책 행렬 자동 테스트와 두 레이아웃 Desktop 회귀 검증
- cross-block selection 결정을 UI 비의존 `EditorSelectionCoordinator`로 이동하고 전체 선택·Text 확장·atomic
  이웃·중첩 경계 escape·focus 해제 정책을 common test로 고정
- Desktop 명시적 개발 실행에서만 활성화되는 recomposition counter와 `document`, `container`,
  `selection-surface`, `block-row`, `block` 계측 지점 추가
- 단일 Text 최초 표시 기준 `document=1`, `container(root)=1`, `selection-surface(root)=1` 기록 및
  Desktop `Ctrl+A` 강조·`Esc` 해제 회귀 확인

2026-08-31 에디터 컴포지션 정비 게이트의 계획 범위는 완료했다.
Table 셀 내부 이동과 Embed 편집 중 포커스 복구는
각 컴포넌트의 로컬 동작이므로 중앙 coordinator로 끌어올리지 않는다.

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

### 전체 구조·파일 최적화 게이트: Undo/Redo MVP 완료 직후

기존에는 frontmatter 단계 직후를 첫 전체 최적화 시점으로 잡았으나 커밋 MVP와 에디터 정비가 우선 진행되었다.
2026-08-31 Undo/Redo 내용 이력 MVP의 UI 연결과 Desktop Text 회귀 검증 직후, cross-block Delete/Cut/Paste 전에
첫 전체 구조·파일 최적화를 수행했다.

이 게이트에서 말하는 최적화는 동작을 바꾸지 않는 안정화 작업이다.

1. 기준 시나리오와 소스 분포 측정 — 완료
2. dormant workflow 코드와 사용하지 않는 상태 제거 — 완료
3. 작은 coordinator·policy·helper의 단일 소비자 여부와 독립 테스트 가치를 조사해 통합 또는 유지 결정 — 완료
4. 불필요한 파일 본문 읽기 제거 — 완료
5. commit snapshot과 editor history snapshot의 책임 구분 유지 — 완료
6. JVM 전체 테스트, Desktop/Android 컴파일과 Desktop 핵심 화면 회귀 재실행 — 완료
7. `FileManager`·`MainViewModel` 추가 분리는 새 계층 수가 늘어나는 비용이 더 커 현재 구조 유지
8. 대형 문서 성능 계측과 recomposition 병목 최적화는 cross-block 편집 이후 릴리스 후보 게이트로 이관

완료 조건:

- 대표 규모의 테스트 vault와 측정 기준이 문서화됨
- 주요 계층의 책임 경계가 테스트 가능한 형태로 정리됨
- P0와 파일 탐색 자동·수동 회귀가 모두 통과함
- 측정 결과가 없는 추측성 micro-optimization은 수행하지 않음

정리 전 → 후 기준값:

- `composeApp/src` Kotlin 파일 136개 → 117개
- `commonMain` Kotlin 파일 91개 → 74개, 이 중 80줄 이하 33개 → 23개
- Markdown commonMain Kotlin 파일 29개 → 28개

주요 변경은 workflow parser·entity·화면과 `FileManager`의 dormant API 제거, 사용하지 않던 샘플 Platform/Greeting
경계 제거, scanner 전용 `MarkdownBlock` 병합이다. 기존 Vault의 `.workflow/`와 사용자 파일은 삭제하지 않는다.

파일 개수를 목표 숫자에 맞춰 줄이지 않는다. 플랫폼 expect/actual, 영속성 경계, 독립 정책 테스트가 있는 파일은
유지하고, 단일 소비자·중복 책임·탐색 비용이 확인된 파일만 합친다.

이후 기능 개발 중에는 국소적인 정리만 수행한다. 다음 성능 최적화는 cross-block 편집까지 완료한 릴리스
후보에서 editor history와 commit snapshot·diff 비용을 함께 측정할 때 진행한다.

### 4단계: 편집 안전성과 생산성

상세 아키텍처, 구현 순서와 난이도 판단은 [MarkdownEditor 설계](markdown-editor.md#17-단계별-구현-계획)를 따른다.

1. 현행 계약 고정과 대표 문서 측정
2. focus coordinator와 mutation dispatcher 분리 완료
3. immutable document snapshot과 순수 history, 내용 Undo/Redo UI 연결 완료
4. focus·cursor·selection 비추적 정책 적용, 중첩 Dialogue Callout·Code 내용 Desktop 검증 완료
5. Cross-block Delete/Cut/Paste 완료
6. selection 일반 문자·IME 확정 입력 대체 완료, Text+Callout Desktop 확정 문자열 회귀 검증 완료
   - 실제 Windows 한글 IME composition 과정은 물리 한/영 키로 수동 확인 필요
7. 조건부: Scope B와 custom 마우스 drag
8. 별도 milestone: Table 셀 selection과 Embed

마우스 drag, Android native selection handle 수준의 블록 횡단 UX, Table 사각형 selection은
기술적으로 비용이 큰 결정 게이트다. 합의 전에는 키보드 기반 selection과 atomic Table 정책을 유지한다.

### 5단계: 커밋 MVP

파일 정체성과 폴더 이동 정책이 안정된 뒤 시작한다.

1. 현재 편집 파일을 포함한 모든 pending save를 먼저 flush한다.
2. 프로젝트의 추적 대상 파일을 스캔하고 직전 커밋 manifest와 비교한다.
3. 추가·수정된 파일의 새 content blob만 기록한다.
4. 삭제 파일은 새 blob을 만들지 않고 새 manifest에서 제외한다.
5. 동일한 frontmatter `id`의 경로가 바뀌면 rename·이동으로 기록한다.
6. 전체 프로젝트 상태를 가리키는 tree와 commit을 기록하고 마지막에 현재 commit 참조를 교체한다.
7. 변경 사항이 없으면 빈 commit을 만들지 않는다.

2026-08-31 1차 구현 완료:

- SHA-256 content-addressed blob, 전체 tree manifest, 단일 parent commit과 `HEAD.json` 저장
- frontmatter `id` 기반 추가·수정·삭제·rename·rename+수정 판정
- 같은 내용의 blob 재사용과 삭제·rename 시 불필요한 blob 미생성
- pending save flush 후 변경 목록, 줄 추가·삭제 수, 커밋 메시지를 표시하는 UI
- `HEAD`의 parent 연결을 따라 최근 50개 커밋과 당시 변경 파일을 다시 계산하는 이력 UI
- 파일별 실제 줄 diff, 문맥 줄 생략, 대형 문서 근사·표시 상한 처리
- clean working tree에서 추적 대상 Markdown을 과거 tree로 복구하고 HEAD 이력을 유지하는 restore
- restore 전 target blob 전체 검증과 실패 시 HEAD snapshot best-effort rollback
- 중복 파일 ID와 손상된 저장 객체 감지
- JVM 순수 로직·파일 저장 통합 테스트 및 Android main 컴파일 검증

기능상 남은 범위는 Android SAF provider별 `.machum/` 생성·restore 수동 검증과 외부 편집 경합 강화다.

---

## 9. 커밋 기능 설계

### 9.1 확정된 저장 의미

커밋은 **논리적으로 프로젝트 전체 스냅샷**이다. 다만 매번 모든 파일 본문을 복사하지 않는다.
Git과 비슷하게 각 커밋이 전체 파일 manifest를 가리키고, 내용은 해시 기반 content blob으로 저장한다.
직전 커밋과 내용이 같은 파일은 기존 blob을 재사용하므로 새로 기록되는 본문은 추가·변경된 파일뿐이다.

patch 연쇄는 저장의 원본으로 사용하지 않는다. patch만 누적하면 오래된 커밋 복원에 모든 중간 이력이 필요하고
손상 복구가 복잡해진다. diff는 부모와 현재 manifest 및 blob을 비교해 언제든 다시 계산한다.

```text
Commit
├── id
├── parentCommitId
├── createdAt
├── message
└── treeHash
    └── Tree manifest
        ├── fileId → relativePath + blobHash
        └── fileId → relativePath + blobHash

Object store
└── blobHash → 해당 시점의 파일 내용
```

- `Commit`: 부모와 해당 시점의 전체 tree를 가리키는 불변 객체
- `Tree manifest`: 커밋 시점에 존재한 추적 대상 전체 목록. 파일 본문이 아니라 기존 blob 참조를 포함한다.
- `Content blob`: 파일 전체 내용. 동일 해시는 한 번만 저장한다.
- `ChangeSet`: 부모 tree와 현재 tree의 비교 결과이며 commit의 원본 데이터가 아니라 표시·검증용 파생 값이다.

### 9.2 추적 범위와 파일 정체성

MVP 추적 대상은 현재 제품이 관리하는 Project 루트 및 직속 디렉터리의 Markdown 파일이다. Project 설정 파일
`.machum.json`, 커밋 저장소 `.machum/`, 임시 파일, 숨김 파일, 중첩 디렉터리와 바이너리 첨부 파일은 제외한다.
빈 디렉터리는 파일이 아니므로 별도로 커밋하지 않는다. 첨부 파일 추적은 파일 탐색 범위를 확장할 때 별도 결정한다.

Markdown 파일의 정체성은 경로가 아니라 frontmatter `id`다. `ProjectConfig.fileIds`는 경로와 ID를 빠르게
연결하는 인덱스이며 정체성의 최종 authority는 파일 자체의 `id`다. 커밋 전에 인덱싱을 완료하고, 동일 프로젝트에
중복 `id`가 있으면 rename·삭제를 잘못 판정하지 않도록 커밋을 중단하고 충돌 파일을 알려준다. 1차 구현에서 이미
`.machum.json` entry를 포함해 만든 커밋은 읽을 수 있지만, 새 diff와 restore에서는 해당 legacy entry를 무시한다.

### 9.3 변경 판정

부모 tree와 현재 스캔 결과를 다음 순서로 비교한다.

| 판정 | 조건 | 새 blob 기록 |
|---|---|---|
| 추가 | 이전에 없던 `fileId`가 존재 | 예 |
| 수정 | 같은 `fileId`, 같은 경로, 다른 `blobHash` | 예 |
| 삭제 | 이전 `fileId`가 현재 없음 | 아니오 |
| 이름 변경·이동 | 같은 `fileId`, 다른 경로, 같은 `blobHash` | 아니오 |
| 이름 변경·이동 + 수정 | 같은 `fileId`, 다른 경로, 다른 `blobHash` | 예 |

커밋 UI에는 이 ChangeSet에 포함된 파일만 보여준다. 줄 단위 LCS에서 추가·삭제 줄 수와 실제 diff hunk를 계산하며,
변경 주변 3줄만 남기고 긴 동일 구간을 접는다. 계산량이 큰 문서는 공통 prefix/suffix 밖을 전체 교체로 표시하고
화면 출력은 2,000줄로 제한하지만 원본 blob은 생략하지 않는다. 산문 가독성을 위한 문단 단위 표시는 후속 view다. 에디터의 런타임
`EditorBlock.id`는 재파싱 때 달라질 수 있으므로 커밋 정체성이나 diff 기준으로 사용하지 않는다.

### 9.4 저장 위치와 일관성

커밋 객체·tree·blob은 Project 내부의 숨김 `.machum/` 저장소에 둔다. Project의 지원 범위 Markdown만 추적하고,
`.machum.json`과 `.machum/` 자체는 제외해 설정 변경 및 재귀 저장이 커밋에 섞이지 않게 한다. JVM 파일 저장은 자동 검증했고 Android 구현은 컴파일을
통과했다. DocumentsUI/provider별 숨김 디렉터리와 정확한 파일명 생성은 연결 기기에서 수동 검증해야 한다. 지원하지 않는
provider의 앱 전용 저장소 fallback은 MVP 이후 범위이며, 현재는 사용자에게 저장 실패를 알린다.

```text
.machum/
├── HEAD.json
├── blobs/<sha256>.blob
├── trees/<sha256>.json
└── commits/<commit-id>.json
```

커밋 시작 전에 `DebouncedSaveCoordinator`의 pending save를 모두 flush한다. 커밋 스캔과 객체 기록 중에는 프로젝트
단위 mutex로 앱 내부 rename·삭제·저장을 직렬화한다. blob과 tree를 먼저 기록하고 commit 및 현재 참조를 마지막에
기록해 중간 실패가 기존 이력을 가리키는 참조를 손상시키지 않게 한다. 외부 편집과의 경합은 커밋 직전·직후 mtime
재검사 정책을 구현 단계에서 확정한다.

### 9.5 MVP에서 제외하는 Git 기능

Git 호환 저장소나 Git 명령 실행을 목표로 하지 않는다. 1차 범위에서는 staging area, branch, merge, rebase,
remote push·pull, 파일별 선택 커밋을 제외한다. 사용자가 commit 버튼을 누르면 감지된 프로젝트 변경 전체가 하나의
commit이 된다. 이 제한은 프로젝트 스냅샷 UX를 단순하게 유지하면서 변경·추가·삭제·rename diff와 복구 기반은 보존한다.

커밋 기능은 제품의 핵심 차별점이지만 프로젝트 파일 탐색보다 먼저 구현하면 파일 경로와 정체성 모델을 재작업할 가능성이 높다.

---

## 10. 확정 사항과 미결 사항

### 확정

- workflow 네비게이션 은퇴
- Vault 직속 디렉터리를 `PROJECT` / `VAULT_FOLDER`로 구분하는 탐색 루트 구조
- 유효한 `.machum.json`이 관리 Project의 판별 기준이며, 일반 폴더 열람은 Project 설정을 생성하지 않음
- `activeWorkspace`와 `selectedProject`를 분리하고 Project 전용 동작은 active kind로 게이트
- 일반 Vault 폴더는 무번호·이름순이며 ID·Project 태그·메타 인덱스·커밋을 자동 적용하지 않음
- 일반 폴더의 `프로젝트로 전환…`은 `.machum.json`과 누락된 기본 4개 디렉터리를 transaction으로 생성
- 폴더 유형 `default`, `general`과 Default 전용 `plotEnabled` 옵션
- `autoTags`는 유형과 독립
- base 프로젝트 폴더가 원고 랜딩 스코프
- 폴더가 파일 스와이프 스코프
- 기본 폴더 유형은 Default이며 UI 순서는 Default, General
- 일반 Default 디렉터리와 Default + Plot의 단계 내부 순번은 1부터 시작
- 기존 관리 Project의 Default 비-Plot 루트는 `schemaVersion` 기반으로 한 번만 Default + Plot으로 전환하고,
  General 루트와 기존 파일명·frontmatter는 보존
- 관리 frontmatter 키와 원형 보존 계약
- Table 셀 selection은 장기적으로 사각형 방식
- 커밋은 프로젝트 전체 스냅샷이며 해시 기반 blob 재사용으로 추가·변경된 내용만 새로 저장
- Markdown rename·이동 추적의 정체성은 frontmatter `id`
- 커밋 UI는 부모 스냅샷과 비교한 추가·수정·삭제·rename 파일만 표시

### 미결

- 과거 버전에서 일반 Vault 폴더를 Project처럼 열어 정상 `.machum.json`이 이미 생성된 경우의 최초 분류 확인 또는 `Project 관리 해제…` 정책
- 일반 Vault 폴더를 Project로 전환할 때 기본 네 디렉터리 이외의 기존 직속 디렉터리를 General로 등록할지, Project의 미설정 기본값인 Default로 둘지
- Project에서 일반 Vault 폴더로 되돌리는 역전환과 기존 `id`·Project 태그 정리 범위
- 번호 없는 외부 파일의 Default 폴더 내 최종 정렬 위치(현재 임시 정책: 번호 파일 뒤 이름순)
- navigation drawer의 Desktop 대화면 확장 방식
- 디렉터리 생성 UI 컴팩트화: Plot 체크박스를 별도 하위 행 대신 Default 항목 오른쪽에 배치
- 사용자 정의 선택 필드
- 커밋·복구 중 외부 편집 경합의 mtime 재검사 강화
- Android SAF provider별 `.machum/` 지원 및 대체 저장 위치
- Markdown 이외 첨부 파일과 중첩 디렉터리의 향후 추적 범위
- 외부 변경이 미저장 입력을 덮을 때 사용자 알림 여부
- `DEC-EDITOR-01`: 1차 에디터 완성에 마우스·터치 drag를 포함할지 여부
- `DEC-EDITOR-02`: custom block selection을 유지할지 단일 text surface 재설계를 요구할지 여부
- `DEC-EDITOR-03`: Table 사각형 selection을 1차 범위에 포함할지 여부
- `DEC-EDITOR-04`: Embed 미리보기를 1차 범위에 포함할지 여부
