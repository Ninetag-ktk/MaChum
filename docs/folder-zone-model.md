# 프로젝트 모델 재설계 — 폴더-존 & 프론트매터 기획

> **상태**: 기획 확정 단계. 코드 미착수. 이 문서는 vault > project > note 구조 재설계의 source of truth.
> **범위**: 파일 구분 구조(폴더-존) + 프론트매터 정책. 커밋 기능은 별도 후속 논의(§9 placeholder).

---

## 1. 배경 및 방향 전환

### 1.1 기존 기획의 문제

MaChum의 초기 핵심은 **"커밋 방식으로 원고/설정의 변경사항을 추적하는 보조도구"** 이며, **Obsidian과 동일 vault를 공유**해 호환되는 것이 목적이었다. 그런데 실제 구현은 `workflow`(마크다운 템플릿을 헤더 트리로 파싱 → 번호 매긴 step → 프로젝트에 배정) 기능에 무게가 쏠렸고, 다음 문제가 드러났다.

- **Obsidian과 중복**: 조직화(태그/링크/폴더/칸반)는 사용자가 이미 Obsidian에서 풍부하게 수행 중. 앱이 자체 numbering을 강요하면 마찰.
- **현실 불일치**: 실제 작업물 두 편(`당신을 구하던 삶`, `모닥별`)의 번호 체계가 서로 다름 → 재사용 템플릿 모델이 성립 안 함.
- **미완성 + 핵심 방치**: `createChildFile()`는 TODO stub, `needUpdateWorkflow` 세팅부는 자기 자신과 비교하는 버그, `MainScreen`은 workflow를 아예 소비 안 함. 정작 핵심인 커밋(`onCommitClick`)도 TODO.

### 1.2 결정된 방향

- **workflow(템플릿 + 배정 플로우)는 네비게이션에서 은퇴.** 조직화는 Obsidian에 위임. (파일 삭제는 하지 않고 플로우에서 제거 + 투자 중단.)
- 앱의 무게중심을 **"글쓰기 준비(Obsidian이 잘하는 것)"에서 "원고 집필 + 버전 추적(Obsidian이 못하는 것)"으로** 이동.
- 파일 구분은 **폴더-존 모델**로: 폴더별 동작(넘버링/프론트매터)을 `.machum.json`이 선언.

---

## 2. 실제 작업물에서 도출한 근거

`당신을 구하던 삶` / `모닥별` 두 프로젝트 분석 결과, 콘텐츠는 사실 3종류이며 성격이 다르다.

| 종류 | 예시 | 성격 | 넘버링 |
|---|---|---|---|
| **아이디어/리소스** | 모티브, 메세지, 캐릭터 초안, 단편 이미지, `Character/`, `Scene/` | 발산적·비선형 | ✗ |
| **원고** | 프롤로그 이후 (3막 8장) | 선형·순차 | ✓ |
| (방법론 문서) | `WorkFlow.md` | 참고용 노트 | — |

핵심 관찰:
- 사용자는 이미 **프론트매터로 조직화**함: `tags: [프로젝트, 캐릭터]`, `aliases: [풀네임]`, `plot: 1) 발단`, `[[링크]]`, `![[임베드]]`.
- **장면→플롯 배치**는 파일 순서가 아니라 `plot:` 필드(다대일 메타데이터)로 이미 해결됨.
- 캐릭터는 "step"이 아니라 초안→고도화로 진화하는 **지속 엔티티**.
- 앱의 현재 `listFile()`은 프로젝트 디렉토리 직속 `.md`만 봄 → **`Character/`, `Scene/` 같은 하위 모듈 폴더가 앱에 안 보임**. **폴더(모듈) 재귀 발견**이 전제조건 — 단, 파일을 하나의 스와이프로 평탄화하는 게 아니라, 각 폴더를 별도 스코프로 인식하는 것(§6).

---

## 3. 폴더-존 모델

### 3.1 원칙

- **자동 생성은 모든 폴더 공통.**
- **자동 넘버링은 원고 폴더에서만** (`numbered=true`). 넘버링은 앱이 관리하는 유일한 순서.
- 아이디어 존은 **임의 중첩 허용** (파일시스템 그대로). "넘버링" 폴더만 특별 취급.

### 3.2 폴더 유형 (3종, 아이콘 인디케이터)

폴더의 동작은 배타적 **유형** 하나로 결정된다. 하이라키 뷰에서 아이콘으로 즉시 구분되며, 폴더 설정에서 3택으로 변경한다.

| 유형 | 아이콘(예) | numbered | plot | 용도 |
|---|---|---|---|---|
| `general` (일반) | 📁 | ✗ | ✗ | 워크시트·일반 노트 |
| `plot` (플롯) | 🎭 | ✗ | ✓ | 장면 등 서사적 위치를 갖는 리소스 |
| `numbered` (넘버링) | 🔢 | ✓ | ✓ | 원고 시퀀스 |

- 능력이 **중첩**된다: `general {}` ⊂ `plot {plot}` ⊂ `numbered {plot, numbered}`. **넘버링 폴더에도 plot이 자동 적용**된다(원고 각 장에 서사적 위치 부여 가능).
- **`autoTags`는 유형과 직교** — 세 유형 모두 갖는 별도 속성(§4.1). 따라서 폴더 설정 UI = **[유형 3택 아이콘] + [autoTags 편집]** 두 가지.
- 설정 없는/외부(Obsidian)에서 생긴 폴더 = 기본 `general`.
- 불리언 플래그는 두지 않는다(유형 enum이 numbered/plot 조합을 대신). 향후 "사용자 정의 선택 필드"나 "넘버링인데 plot 없음" 같은 조합이 필요해지면 그때 유형을 확장하거나 고급 설정을 연다.

### 3.3 `.machum.json` 스키마 (개정) ✅ 구현 완료

> **상태**: `ProjectConfig`/`FolderConfig`/`FolderType`(`entity/ProjectConfig.kt`) 구현 완료. 구 스키마 하위호환 읽기(`ignoreUnknownKeys`) 포함. `commonTest/.../entity/ProjectConfigTest.kt`, jvmTest 통과.

기존 `workflow` / `workflowLastModified` 필드 제거. `fileIds`는 커밋 정체성용으로 존치. `FolderType` enum 은 JSON 에 소문자(`general`/`plot`/`numbered`)로 직렬화. 읽기는 lenient `configJson`(`ignoreUnknownKeys=true`)이라 구 `.machum.json`의 workflow 필드는 무시된다.

```json
{
  "folders": {
    "":          { "type": "numbered", "autoTags": ["당신을_구하던_삶"] },
    "Character": { "type": "general",  "autoTags": ["캐릭터"] },
    "Scene":     { "type": "plot",     "autoTags": ["장면구상"] }
  },
  "fileIds": {}
}
```

- `type` ∈ `general` / `plot` / `numbered` (§3.2). 미지정 하위 폴더는 `general`로 간주.
- 키 `""` = **프로젝트 디렉토리(base)** — 위계 `vault > project > file` 에서 project 폴더 자체. **원고가 사는 곳이자 랜딩 스코프**(§6). 여기의 `autoTags`가 프로젝트 공통 태그.
- **원고 정책**: 원고는 항상 프로젝트 디렉토리 직속에 둔다 → base(`""`)의 기본 유형은 `numbered`. 단 유형은 3택 중 변경 가능(원고 없는 순수 노트 프로젝트면 general로). 원고가 아닌 최상위 메모는 하위 폴더로 분리 권장(numbered 폴더의 파일은 모두 번호를 받으므로).
- `Character/`, `Scene/` = 프로젝트 디렉토리 **내부**의 하위 모듈 폴더. 각 폴더는 자기 태그만 선언 (additive, §4.1).

---

## 4. 프론트매터 정책

세 종류가 서로 다른 키(`tags` / `aliases` / `plot`)에 쓰이므로 완전히 독립적이고 서로 충돌하지 않는다. 기존 `id`는 커밋 정체성용으로 유지.

YAML 프론트매터는 표준 raw markdown 형태로 작성된다:

```markdown
---
tags:
  - 당신을_구하던_삶
  - 장면구상
plot: 1) 발단
---
```

### 4.1 `tags` — 관리 태그 (autoTags)

- **합성**: 프로젝트 base 태그(`""`의 autoTags) + 폴더 autoTags를 **additive 병합**.
- **일괄 동기화**: 폴더 설정에서 autoTags를 변경하면 그 폴더 파일들에 일괄 반영.
  - 계약: 폴더 설정이 **관리 태그 집합의 authority**. 변경 시 파일에서 **옛 관리 태그 제거 + 새 관리 태그 추가**, 그 외(사용자 수동) 태그는 보존.
  - cascade: base 태그 변경 → 프로젝트 전체 파일 / 폴더 태그 변경 → 그 폴더 직속 파일. (하위 폴더는 자기 설정으로 각자.)
  - 트레이드오프: 사용자가 관리 태그를 파일에서 손으로 지워도 다음 동기화에 부활(= "관리" 태그의 정의). 수용.

### 4.2 `aliases` — 파일명 자동 입력

- **모든 파일 공통** (폴더 플래그 없음).
- 파일 **생성 시 파일명이 `aliases`에 자동 입력**.
- 이후 **전용 UI**로 수정/추가 가능.

### 4.3 `plot` — 선택 필드 (열거형)

- **앱 전역 정의**의 선택 필드. 현재 `plot` 하나, 값 5개 고정:
  `1) 발단`, `2) 전개`, `3) 위기`, `4) 절정`, `5) 결말`
- **`plot` / `numbered` 유형 폴더에 적용** (일반 유형은 미적용).
- 파일 편집 중 **드롭다운**으로 선택.
- **생성 시 기본값 = 미설정(빈 값).** 사용자가 드롭다운에서 고름 (서사적 위치는 의도적 선택).
- 향후 authoring-mode에서 "plot 단계별 장면 자동 수집"의 토대.

### 4.4 메타 인덱스 — 목록/그룹핑용 프론트매터 수집

plot 단계별 그룹핑(§8)이나 폴더 목록 표시를 위해서는, 폴더 안 각 파일의 **본문을 열지 않고도** frontmatter(`plot`, `tags`, `aliases`, `id`)를 알아야 한다. 매 목록마다 전 파일을 통독하는 것은 비싸므로(특히 Android SAF·클라우드 동기 vault) **mtime 기반 메타 인덱스**를 둔다.

**FileMeta (프론트매터 digest):**
```
FileMeta(
  id: String?,          // frontmatter id (커밋 정체성과 공유)
  name: String,         // 파일명
  plot: String?,        // "1) 발단" … / 없으면 null
  tags: List<String>,
  aliases: List<String>,
  mtime: Long,          // 마지막 수정 시각
)
```

**수집 방식 (mtime 무효화):**
- 폴더 진입/새로고침 시 그 폴더 **직속 `.md`**를 순회.
- 파일별 `mtime`이 인덱스의 것과 같으면 캐시된 `FileMeta` 재사용, 다르면 **frontmatter만 읽어** 갱신.
- `mtime` 비교·읽기는 기존 외부 변경 감지(mtime 폴링, `FileManager.lastModified`)와 **동일 메커니즘 재사용** → 일관성.
- 인덱스는 **세션 인메모리**(`Map<파일명, FileMeta>`)로 시작. 지속 캐시(`.machum/index.json` 등)는 향후 최적화. Obsidian 외부 편집이 있으므로 어차피 mtime 검증이 authority.

**frontmatter-only 읽기:** 구조화된 `NoteFile`(§5)이 frontmatter를 파싱하므로 digest는 거기서 파생. 큰 원고 파일을 위해 "닫는 `---`까지만 읽는" 헤드 읽기를 최적화로 둘 수 있음(정확성은 전체 읽기로도 충분).

**그룹핑 (뷰 레벨 연산):**
- `files.groupBy { it.plot }` → **정규 순서**(발단→전개→위기→절정→결말)로 그룹 정렬, `plot=null`은 "미설정" 그룹으로 맨 끝.
- 그룹 내 정렬: 일반/플롯 = 파일명, 넘버링 = 번호.
- plot·넘버링 유형 폴더에서만 그룹핑 뷰 제공(일반 유형은 평면 목록).

**비용:** 폴더 첫 진입 시 N개 파일 frontmatter 1회 읽기, 이후 새로고침은 변경 파일만. 수용 가능.

**에디터 캐시와 별개:** 이 메타 인덱스는 그룹핑/목록용으로 **열지 않은 파일까지** 포함한다. `MainViewModel`의 NoteFile 편집 캐시(열린 파일만)와 구분된다.

---

## 5. 선행 작업 (step 0) — `NoteFile` 구조화 ✅ 구현 완료

> **상태**: 구현 완료 (접근법 A). `external/NoteFile.kt` + `commonTest/.../external/NoteFileTest.kt`. jvmTest 통과.

기존 `NoteFile`은 프론트매터를 **불투명 blob + `id` 정규식**으로만 다뤄 `tags` 일괄 동기화도, `aliases`/`plot` 병합도 불가능했다. 다음과 같이 구조화했다.

- YAML 프론트매터를 **읽기 / 병합 / 쓰기** 가능하게 구조화 — top-level 키 단위 블록 파싱.
- **관리 키만 구조적 처리**(`id`/`tags`/`aliases`/`plot`): `val id/plot/tags/aliases` 접근자 + `withId/withPlot/withTags/withAliases` 세터.
- **미지 키·주석·키 순서·포맷 원형 보존** (Obsidian 호환 핵심). YAML 라이브러리는 round-trip 시 재포맷하므로 미사용.
- **접근법 A 채택**: 관리 키는 *실제 수정 시에만* 표준 형태로 정규화(스칼라 `key: value`, 리스트 block `- `). 미수정 시 verbatim 유지 → 순수 `parse→inject` 왕복 무손실(본문 앞 공백 정규화 제외). `inject()` diff 안정.
- 리스트 읽기는 block / flow(`[a, b]`) / 인라인 CSV 형태 모두 인식.
- **범위 경계**: `NoteFile` 은 저장/직렬화만. autoTags 일괄 동기화 알고리즘(옛 관리 태그 제거 + 신규 추가 + 수동 태그 보존, §4.1)과 plot 값 강제(§4.3)는 상위 계층(FileManager/폴더-존 로직)이 담당.
- **기존 API 변경**: `getId()` → `val id` 로 대체(호출부 `.id`). `parse/ensureId/withBody/inject/body` 는 유지.

---

## 6. 네비게이션 모델

현재의 "프로젝트 디렉토리 직속 `.md` 평면 리스트 + 전체 스와이프"를 **폴더=스코프** 구조로 교체.

**핵심 원칙 — 폴더 = 모듈 = 스와이프 스코프.**
- 앱은 항상 하나의 폴더("현재 모듈") 안에 있고, 그 폴더의 **직속 `.md`만** 좌우 스와이프로 넘긴다.
- 재귀는 **파일 평탄화가 아니라 폴더(모듈) 발견**에만 쓴다. 하위 폴더 파일은 스와이프에 섞이지 않고 별도 스코프로 남는다.
- **랜딩 = 프로젝트 디렉토리(base) 직속 `.md`** = 원고. 프로젝트를 열면 바로 원고를 스와이프.

**API 형태:**
- `listFile(folder)` — 그 폴더 직속 `.md`만 반환(재귀 X). 정렬: `numbered`=번호, 그 외=이름. 스와이프 페이지 목록.
- `listFolders(projectDir)` — 프로젝트 **내부** 하위 모듈 폴더를 재귀 발견(임의 중첩 §3.1). 각 노드 = 경로 + 유형 + autoTags + 하위 有無. dot-폴더는 제외.

**전환/탐색:**
- **폴더(모듈) 전환**: 프로젝트 내부 폴더 트리를 탐색하는 **드롭다운 / 바텀시트** 메뉴.
- **파일 탐색**: 현재 폴더의 직속 `.md`만 좌우 스와이프.
  - Obsidian 모바일의 "리스트 열고→탭→닫고" 왕복 불편을 없애는 것이 목적. 폴더 안에서는 순수 스와이프.
- **파일 생성** (폴더 유형별로 성격이 다름):
  - 원고(`numbered`, = 프로젝트 디렉토리): 마지막에서 더 스와이프 → 다음 번호 파일 자동 생성(선형 집필 흐름).
  - 리소스 모듈(`general`/`plot`, = Character/Scene 등): `+` 버튼 (이름 입력, 번호 없음, autoTags 자동).

### 6.1 원고 넘버링 스킴

- **0부터 정수 +1.** 새 파일명 = `"{n}. {제목}"` (기존 `markdownName()`의 `". "` 분리 규칙과 일치).

---

## 7. 앱 네비게이션 플로우 변경 ✅ workflow 은퇴 완료

기존 `App.kt` 상태 머신에서 workflow 관련 단계 제거:

- ~~`WorkflowScreen` (템플릿 편집)~~ → **은퇴 완료** (App.kt 게이트 제거)
- ~~`WorkflowSelectionScreen` (배정)~~ → **은퇴 완료** (App.kt 게이트 제거)
- **현재 흐름**: `VaultSelection → ProjectSelection → MainScreen`
- **향후**: `MainScreen`을 폴더=스와이프 스코프 구조로 확장 + 폴더(모듈) 전환 UI 추가 (§6)

**은퇴 방식 (삭제 아님, dormant 유지)**: workflow 화면 파일과 `FileManager`의 workflow 함수·StateFlow는 컴파일만 유지하고 라이브 흐름에서 호출을 제거했다. 구체적으로 App.kt 게이트 2개 제거, `validateVault()`의 `getWorkflowList()`+prefs-wipe 제거, `validProject/setProject/pickProject`의 `setWorkflow()` 제거, `setFile()` 빈-프로젝트 폴백을 `_workflow.first()` → 임시 기본명 `"0. 제목"`(§6.1 넘버링으로 추후 정교화)으로 대체. `needUpdateWorkflow` 자기비교 버그와 "워크플로우 없으면 프로젝트 선택 wipe" 지뢰도 함께 제거됨. jvmTest 통과.

> **다음**: `.machum.json` 스키마 개정(`ProjectConfig`의 `workflow`/`workflowLastModified` 제거 + `folders: {type, autoTags}` 도입) → 폴더 발견(`listFolders`) → 폴더-존 네비게이션.

---

## 8. 확정 / 미결

### 확정
- workflow 은퇴, 폴더-존 모델, `.machum.json` 개정 스키마.
- 폴더 유형 3종(일반/플롯/넘버링, 능력 중첩 — 넘버링은 plot 포함) + `autoTags`(유형과 직교). 아이콘 인디케이터.
- 프론트매터 3종(tags 일괄동기화 / aliases 파일명자동+편집 / plot 선택필드) 독립.
- plot: 앱 전역 5값 고정, 생성 시 미설정. 플롯·넘버링 유형에 적용.
- 원고 넘버링 0부터 +1.
- `NoteFile` 구조화(step 0) ✅ **완료** (§5).
- 위계 `vault > project > file`. **프로젝트 디렉토리(base) = 원고 스코프 = 랜딩**, 기본 유형 `numbered`(변경 가능). Character/Scene 등은 프로젝트 내부 하위 모듈 폴더.
- 폴더 = 모듈 = 스와이프 스코프. 스와이프 = 현재 폴더 직속 .md(재귀 X), 재귀는 폴더 발견용. 폴더 전환 = 드롭다운/바텀시트.

### 미결 / 후속
- **plot 단계별 그룹핑 뷰**: plot·넘버링 유형 폴더에서 파일을 plot 값 단위로 묶어 보기. **수집 구조는 §4.4(메타 인덱스) 설계 완료**, UI 구현은 향후. authoring-mode의 "plot 단계별 장면 수집"과 연결.
- 사용자 정의 선택 필드(옵션 B) / "넘버링인데 plot 없음" 조합: 필요 시 유형 확장 또는 고급 설정.
- 칸반(Obsidian `kanban-plugin`) 파일: 일단 평문 마크다운으로 렌더링만 안 깨지게. 필요성 재검토 보류.
- authoring-mode(장×인물 구조표 + plot 단계별 장면 수집): 향후 확장.

---

## 9. 다음 논의 — 커밋 기능 (placeholder)

파일 구분 구조 확정 후 진행. 논의 시작점:
- 추적 단위: 파일별 커밋 vs 프로젝트 스냅샷 커밋.
- 저장 방식: vault 오염 방지를 위한 숨김 디렉토리(`.machum/` 등) 스냅샷.
- 정체성: 프론트매터 `id` + `fileIds`로 rename/이동 추적.
- diff 표시: 산문 줄/문단 단위 + 블록 에디터 연동.
- (칸반형 커밋 뷰 아이디어 — §8 미결과 연계 검토)
```