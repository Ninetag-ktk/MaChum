# 프로젝트 커밋 수동 테스트

커밋 생성·이력·복원의 플랫폼 저장소와 UI를 검증한다. 테스트 전 Project를 별도 복사한다.
초기 기준점 항목은 Project 내부의 `.machum/`이 없는 상태에서 시작하고, 나머지 항목은 자동 인덱싱과
초기 기준점 생성이 끝난 fixture에서 각 절차에 필요한 clean/dirty 상태를 만든다.
`초기 기준점`과 아래 후속 복원 항목의 구현 및 JVM 자동 검증은 완료되었다. Desktop·Android 화면 항목은 실제
provider와 UI에서 확인한 뒤에만 수동 검증 완료로 표시한다.

## 2026-09-07 취소 안전성 자동 검증

- `ProjectCommitServiceTest` 31개 통과. 신규 6개는 파일 내용 쓰기, 이동 목적지 생성·쓰기·원본 삭제,
  프로젝트 일부 적용 직후 실제 `Job.cancel()`을 주입하며, 취소 후 rollback 쓰기 실패 분류도 검증한다.
- 원상 복구가 끝난 뒤 취소를 전달하고, HEAD와 `.machum` 객체는 변경하지 않는지 확인했다.
- 코루틴 stacktrace recovery가 예외를 감쌀 수 있으므로 원인 체인과 suppressed 오류까지 확인한다.
- 전체 `:composeApp:jvmTest` 277개 통과(실패·오류·skip 0), `:composeApp:compileAndroidMain` 통과.
- 임시 fixture만 사용했다. 이번 작업에서 실제 Vault 및 Desktop/Android 화면 검증은 수행하지 않았다.

## 수동 확인 항목

### 읽기 전용 검사와 원문 복원 — 2026-09-08

커밋 scan은 디스크 원문으로 blob/hash/diff를 계산하고 ID·태그를 쓰지 않는다. ID 없는 외부 파일은 원본을 유지한 채
인덱싱 안내로 차단한다. Project 재진입 또는 실제 본문 편집·저장으로 ID를 기록한 뒤 다시 시도할 수 있다.
태그가 이미 유효한 전체 복원과 실패 rollback은 YAML 표현·주석·BOM·개행을 불필요하게 재직렬화하지 않는다.

| ID | 절차 | 기대 결과 |
|---|---|---|
| COMMIT-READ-01 | 초기 인덱싱 후 외부에서 기존 ID·Project 태그를 유지하며 YAML 표현이나 구분 개행만 바꾸고 미리보기·diff를 반복 | 수정으로 표시되며 조회 전후 원문·mtime·working-tree hash가 변하지 않음 |
| COMMIT-READ-02 | Project를 열어 둔 상태에서 외부에서 ID 없는 Markdown을 추가하고 커밋 미리보기 | 해당 경로와 인덱싱 안내를 표시하고 ID·태그·mtime을 바꾸지 않음. Project 재진입 후에는 안정적인 `추가`로 표시 |
| COMMIT-READ-03 | 인덱싱된 Project에서 Project 태그가 포함된 비표준 YAML 원문을 커밋하고 본문 수정 후 현재 HEAD 전체 복원 | 커밋의 원문으로 돌아가며 불필요한 형식 변경이 남지 않음 |

자동 실패 주입 대상에는 프로젝트 전체 복원의 빈 파일 생성 직후 실제 취소, 내용을 쓰다 파일이 잘린 실패,
일부 파일을 쓴 뒤 실패를 포함한다. 원래 경로·원문·hash·HEAD 복구 여부를 검증한다. 화면 테스트에서는 실제 사용자
데이터의 강제 삭제나 프로세스 중단으로 이 실패를 흉내 내지 않는다.

이번 자동 검증은 `ProjectCommitServiceTest` 35개를 포함해 전체 JVM 306개(49 suites)를 2회 통과했다.
두 번째는 `--rerun-tasks`, 실패·오류·skip 0이며 Desktop/Android 앱 컴파일도 통과했다.
임시 fixture만 사용했고 실제 Vault 및 화면 검증은 수행하지 않았다.

| ID | 절차 | 기대 결과 |
|---|---|---|
| COMMIT-01 | `.machum/`이 없는 관리 Project를 선택하고 인덱싱 완료까지 대기 | 지원 범위 Markdown을 담은 `초기 기준점`이 자동 생성되고, 다시 진입해도 중복 생성되지 않음 |
| COMMIT-02 | 초기 기준점 뒤 Markdown을 수정하고 메시지를 입력해 첫 사용자 커밋 | dialog가 닫히고 새 commit의 parent가 `초기 기준점`이며 `.machum/HEAD.json`, `blobs`, `trees`, `commits`가 일관되게 갱신됨 |
| COMMIT-03 | 아무 파일도 바꾸지 않고 다시 커밋 버튼 선택 | `커밋할 변경 사항이 없습니다`가 표시되고 새 커밋 버튼은 나타나지 않음 |
| COMMIT-04 | 편집 직후 500ms 전에 커밋 버튼 선택 | pending save가 먼저 기록되고 해당 파일이 `수정`으로 표시됨 |
| COMMIT-05 | 파일 추가·수정·삭제 후 미리보기 | 변경된 파일만 각각 `추가`, `수정`, `삭제`로 표시되고 `+/-` 줄 수가 보임 |
| COMMIT-06 | frontmatter `id`를 유지한 채 외부에서 파일명 변경 | `이름 변경`으로 표시되고 이전 경로와 새 경로가 함께 보임 |
| COMMIT-07 | 이름과 내용을 함께 변경 | `이름 변경·수정`으로 표시되고 줄 증감이 함께 보임 |
| COMMIT-08 | 같은 frontmatter `id`를 가진 파일 두 개 생성 후 미리보기 | 커밋을 만들지 않고 중복 ID 오류를 표시함 |
| COMMIT-09 | 삭제 또는 내용 없는 rename만 커밋한 뒤 `blobs` 개수 확인 | 새 content blob이 추가되지 않음 |
| COMMIT-10 | Android DocumentsUI에서 `.machum/`이 없는 관리 Project의 인덱싱 완료까지 대기 | provider가 `.machum/`과 `초기 기준점` 객체를 정확한 이름으로 만들고 후속 미리보기가 기존 HEAD를 읽음 |
| COMMIT-11 | 커밋 메시지를 공백으로 둠 | 커밋 버튼이 비활성화됨 |
| COMMIT-12 | 두 번 이상 커밋한 뒤 빠른 dialog의 `커밋 이력` 선택 | 별도 이력 화면에 최신 커밋부터 메시지, 짧은 ID와 변경 파일 수가 표시됨 |
| COMMIT-13 | 이력의 커밋 항목 선택 | 해당 커밋의 추가·수정·삭제·rename 파일과 줄 증감이 펼쳐짐 |
| COMMIT-14 | 현재 변경 또는 이력의 변경 파일 선택 | 이전/현재 줄 번호와 `+`, `-`, 문맥 줄이 표시되고 긴 동일 구간은 접힘 |
| COMMIT-15 | 내용이 바뀌지 않은 rename 파일의 diff 선택 | 파일 내용은 변경되지 않았다는 안내가 표시됨 |
| COMMIT-16 | 일반 편집으로 미커밋 변경이 있는 상태에서 과거 커밋 펼침 | `이 커밋 시점으로 복원`이 비활성화되고 먼저 커밋하라는 안내와 `변경사항 커밋` 진입 동작이 표시됨. 유효한 복원 세션의 예외는 COMMIT-NEXT-21~23에서 별도 검증 |
| COMMIT-17 | clean 상태에서 과거 커밋의 `이 커밋 시점으로 복원` 선택·확인 | Markdown 파일 추가·삭제·내용·경로가 해당 시점으로 복원되고 `.machum.json`은 현재 상태를 유지함 |
| COMMIT-18 | `이 커밋 시점으로 복원` 직후 현재 변경 확인 | HEAD와 기존 이력은 이전 최신 상태를 유지하고 복원 결과가 새 미커밋 변경으로 표시됨 |
| COMMIT-19 | 프로젝트 전체 복원으로 과거 폴더 경로가 필요해지는 커밋 선택 | 필요한 직속 디렉터리를 생성하고 파일을 과거 경로에 복원함 |
| COMMIT-20 | `이 커밋 시점으로 복원`의 파일 쓰기 단계에 실패를 주입 | 오류를 표시하고 복원 직전 canonical working-tree hash와 파일 상태로 롤백하며 HEAD와 기존 이력을 변경하지 않음 |
| COMMIT-21 | `.machum.json`만 수정한 뒤 커밋 버튼 선택 | 설정 파일은 변경 목록에 나타나지 않고 `커밋할 변경 사항이 없습니다`가 표시됨 |

JVM 테스트는 snapshot 생성, blob 재사용, 변경 판정, 줄 diff, 이력 parent 연결, 프로젝트·파일 복원,
설정 제외와 dirty 차단, 초기 기준점의 단일 생성, HEAD snapshot·parent 적용, canonical hash 기반 세션 수명·범위,
저장 객체 무결성 및 단계별 실패 주입 rollback을 검증한다. Android의 COMMIT-10, COMMIT-17~20과 신규 복원 항목은
실제 DocumentsUI provider에서도 별도 수행한다.

## 커밋 이력 전용 화면·단일 파일 복원 검증

상태: **구현 및 JVM 자동 검증 완료 / Desktop·Android 수동 화면 검증 대기**

복원 동작의 의미와 세션 규칙은 [제품 로드맵](product-roadmap.md)의 9.6을 source of truth로 삼고,
이 절에는 구현 후 합격 여부를 판단할 검증 절차만 둔다.

자동 검증에는 `id`·`plot` 보존, 선택 버전의 Markdown 전체와 현재 Project 태그 정책, 파일 이동·삭제·재생성,
충돌·dirty 차단과 무관한 dirty 파일 보존을 포함한다. ViewModel 검증에는 이력 진입·이탈의 editor session 보존,
복원 대상 session 교체, 복원 세션의 hash·범위·무효화 규칙을 포함한다. 저장소 검증에는 초기 기준점의 단일 생성과
실패 시 부분 HEAD 미노출, 복원 쓰기 단계별 실패 주입 후 rollback을 포함한다. 이 문서의 상태는 해당 자동 테스트와
아래 화면 인수 조건을 통과한 뒤에만 완료로 바꾼다. 각 수동 항목은 독립된 Project 복사본에서
시작하거나 앞선 결과를 커밋·폐기해 필요한 clean 조건을 다시 만든 뒤 수행한다.

| ID | 절차 | 기대 결과 |
|---|---|---|
| COMMIT-NEXT-01 | 빠른 커밋 dialog에서 `커밋 이력` 선택 | dialog가 닫히고 MainScreen의 별도 이력 화면으로 전환되며 기존 편집 위치는 유지됨 |
| COMMIT-NEXT-02 | 이력 상세·파일 diff까지 진입한 뒤 뒤로가기 | `파일 diff → 커밋 상세 → 이력 목록 → 기존 편집 위치` 순서로 돌아감 |
| COMMIT-NEXT-03 | 같은 `fileId`지만 현재 이름·직속 폴더·순번·`plot`·태그가 선택 버전과 다르게 준비된 clean 파일에서 `파일 내용 복원` | 선택 버전의 본문·일반 frontmatter가 복원되지만 현재 파일명·직속 폴더 경로·순번·`id`·`plot`은 유지됨. 현재 태그와 과거 사용자 태그는 합치되 다른 폴더의 과거 `autoTags`는 다시 추가하지 않음 |
| COMMIT-NEXT-04 | 현재 또는 선택한 `변경 전/변경 후` 한쪽에 파일이 없는 diff 확인 | `파일 내용 복원`은 비활성화되고 `단일 파일 전체 복원`이 안내됨 |
| COMMIT-NEXT-05 | 이름·직속 폴더·내용이 달랐던 clean 파일에서 `단일 파일 전체 복원` | 해당 한 파일만 선택 시점의 내용·frontmatter·이름·경로로 복원됨 |
| COMMIT-NEXT-06 | 파일 삭제가 이미 커밋된 clean fixture에서 삭제 전 버전으로 단일 파일 전체 복원 | 삭제된 파일을 정확한 이름과 직속 폴더 경로에 다시 만듦 |
| COMMIT-NEXT-07 | 파일 추가가 이미 커밋된 별도 clean fixture에서 추가 전 `파일 없음` 버전으로 단일 파일 전체 복원 | 해당 파일만 삭제하고 무관한 파일과 빈 폴더는 유지함 |
| COMMIT-NEXT-08 | 복원 목적지에 다른 `fileId` 파일을 둔 뒤 단일 파일 전체 복원 | 충돌을 먼저 표시하고 두 파일 모두 변경하지 않음 |
| COMMIT-NEXT-09 | 복원 대상 파일을 편집한 직후 두 단일 파일 복원을 시도하고 autosave 대기 시간을 넘겨 확인 | pending save를 flush한 뒤 두 동작을 차단하고 대상의 미커밋 내용을 보존함 |
| COMMIT-NEXT-10 | 대상이 아닌 파일을 편집한 직후 clean 대상 파일을 단일 파일 복원하고 autosave 대기 시간을 넘겨 확인 | 복원을 허용하고 대상 복원 결과와 무관한 파일의 미커밋 변경을 모두 유지함 |
| COMMIT-NEXT-11 | 각 단일 파일 복원을 독립적으로 수행한 뒤 HEAD, 이력, `.machum.json`, `.machum/`, 비-Markdown 파일과 빈 폴더를 비교 | HEAD와 이력·설정·저장소 객체·비추적 항목은 유지되고 해당 파일 복원 결과만 새 미커밋 변경으로 나타남 |
| COMMIT-NEXT-12 | 과거 커밋 상세, 현재 HEAD 상세와 파일 diff의 복원 메뉴 확인 | 파일 diff에는 `파일 내용 복원`·`단일 파일 전체 복원`, 과거 커밋에는 `이 커밋 시점으로 복원`, 현재 HEAD에는 `현재 커밋 시점으로 복원`과 parent가 있을 때 `최근 커밋의 변경 되돌리기`가 구분되어 보이며 `프로젝트 전체 내용 복원`은 없음 |
| COMMIT-NEXT-13 | 파일 내용 복원 쓰기 실패를 독립 fixture에서 유도 | 대상 파일과 무관한 dirty 파일을 복원 직전 상태로 유지하고 오류를 표시함 |
| COMMIT-NEXT-14 | 단일 파일 전체 복원의 생성·이동·삭제 실패를 각각 독립 fixture에서 유도 | 대상 파일을 가능한 범위에서 복원 직전 내용·이름·경로·존재 상태로 rollback하고 무관한 파일은 변경하지 않음 |
| COMMIT-NEXT-15 | 세 복원 동작의 확인 dialog를 각각 열고 취소 | 선택 범위와 예상 생성·삭제·이동·덮어쓰기를 구분해 안내하고, 취소하면 어떤 파일이나 HEAD도 변경하지 않음 |
| COMMIT-NEXT-16 | 편집 후 이력 화면을 열었다 닫고 Undo 실행 | 이력 진입 전 입력이 정상 취소되어 editor session과 Undo 이력이 유지됨 |
| COMMIT-NEXT-17 | A 파일을 복원한 뒤 A와 B 파일에서 각각 Undo 확인 | A에서는 복원 전 내용이 Undo로 되살아나지 않고, 복원하지 않은 B의 기존 Undo 이력은 유지됨 |
| COMMIT-NEXT-18 | A Project 이력 또는 diff 로딩 중 B Project로 전환 | A의 늦은 결과나 오류가 B 화면에 나타나지 않고 이력 화면이 닫힘 |
| COMMIT-NEXT-19 | parent가 있는 현재 HEAD에서 `최근 커밋의 변경 되돌리기` 선택·확인 | parent snapshot의 추적 Markdown tree가 working tree에 적용되지만 HEAD와 commit 이력은 그대로이며, 적용 결과는 새 미커밋 변경으로 표시됨 |
| COMMIT-NEXT-20 | parent가 없는 현재 HEAD를 clean 상태에서 확인하고, 별도로 기존 이력의 parent 없는 첫 commit도 확인 | `현재 커밋 시점으로 복원`은 이미 같은 상태라 비활성화되고 `이 커밋보다 이전 기준점이 없습니다.`가 표시됨. 기존 첫 commit 이전 상태는 소급 생성되거나 복구되지 않음 |
| COMMIT-NEXT-21 | clean 상태에서 `이 커밋 시점으로 복원`한 뒤 이력 화면을 떠나거나 파일을 편집하지 않고 다른 과거 시점 복원 또는 HEAD 되돌리기를 연속 수행 | 직전 복원 결과의 canonical working-tree hash가 그대로인 Project 범위 복원 세션에서는 Project 범위 동작이 계속 활성화되고 매번 HEAD·이력은 유지됨. 파일 복원에는 이 세션의 dirty 예외가 전이되지 않음 |
| COMMIT-NEXT-22 | COMMIT-NEXT-21의 세션에서 각각 사용자 편집, 외부 편집, 이력 화면 닫기·재진입, 추적 파일의 hash 불일치를 만든 뒤 다시 Project 범위 복원 시도 | 각 경우 세션이 폐기되고 일반 dirty 차단과 `변경사항 커밋` 안내로 돌아감 |
| COMMIT-NEXT-23 | 처음부터 B만 dirty인 상태에서 clean A 파일을 복원한 뒤, 같은 이력 화면에서 A의 다른 버전 재복원과 B의 복원·Project 전체 복원을 각각 시도 | 직전 성공 hash가 유지되면 복원 세션에 기록된 A만 dirty 예외로 다시 복원할 수 있다. B에는 예외가 적용되지 않고 Project 범위 복원으로 확대할 수도 없으며 B의 기존 변경은 보존됨 |
| COMMIT-NEXT-24 | Markdown과 이력이 모두 없는 빈 관리 Project를 선택해 인덱싱 완료 후 재진입 | 빈 tree도 유효한 `초기 기준점`으로 정확히 한 번 생성되며 재진입해도 중복 commit이 생기지 않음 |
| COMMIT-NEXT-25 | 초기 기준점 저장 실패를 주입한 두 fixture에서 각각 `다시 시도`, `기준점 없이 계속` 선택 | 실패 중 부분 HEAD나 불완전 commit이 노출되지 않음. 재시도 fixture는 기준점을 한 번 생성하고, 계속 fixture는 이력 없는 상태로 MainScreen에 진입함 |
| COMMIT-NEXT-26 | HEAD 되돌리기와 유효한 Project/파일 복원 세션의 후속 복원에서 생성·쓰기·이동·삭제 실패를 단계별 독립 fixture로 주입 | 각 시도는 직전 canonical working-tree hash와 파일 존재·내용·경로로 rollback하고 HEAD·이력·무관한 dirty 파일을 유지함. rollback hash가 직전 세션 hash와 같을 때만 세션이 유지되고, 다르면 오류와 함께 폐기되어 일반 dirty 차단으로 돌아감 |
| COMMIT-NEXT-27 | `초기 기준점`만 있는 Project에서 첫 편집을 커밋하지 않고 현재 HEAD의 `현재 커밋 시점으로 복원` 선택·확인 | 미커밋 변경 전체가 기준점 snapshot으로 돌아가고 HEAD와 단일 이력은 유지됨 |
| COMMIT-NEXT-28 | 과거 시점으로 프로젝트 전체 복원한 직후 현재 HEAD의 `현재 커밋 시점으로 복원` 선택·확인 | 최신 HEAD snapshot으로 다시 진행할 수 있고 별도 commit 없이도 복원 전후 시점을 왕복하며 HEAD·이력은 유지됨 |
| COMMIT-NEXT-29 | 복원 확인 dialog를 연 뒤 외부에서 추적 Markdown을 수정하고 확인 | preview 시점 canonical hash와 달라 실행을 차단하고 외부 변경을 보존하며, 기존 복원 세션이 있었다면 폐기함 |
