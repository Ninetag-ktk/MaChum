# MaChum P0 수동 테스트

> 역할: 자동 테스트로 재현하기 어려운 플랫폼 I/O와 에디터 상호작용의 P0 검증 절차  
> 관련 정책: [product-roadmap.md](product-roadmap.md), [markdown-editor.md](markdown-editor.md)  
> 마지막 갱신: 2026-08-31
>
> 상태: 이전 개발 회차에서 종료 기준 전체 통과(사용자 확인). 이후 기능 변경 시 회귀 절차로 재사용.

---

## 1. 실행 전 준비

1. 작업 트리를 보존한 채 아래 자동 검증을 먼저 실행한다.

   ```bash
   ./gradlew :composeApp:jvmTest
   ./gradlew :composeApp:compileKotlinJvm
   ./gradlew :androidApp:assembleDebug
   ```

2. 실제 원고가 아닌 전용 테스트 vault를 준비한다.
3. Desktop은 텍스트 편집기나 Obsidian을 같이 열고, Android는 적어도 DocumentsUI 로컬 저장소를 사용한다.
4. 사용하는 운영 체제·기기·SAF provider를 결과에 기록한다.

---

## 2. 결과 기록 템플릿

| 항목 | 기록 |
|---|---|
| 일시 | |
| 플랫폼/기기 | |
| OS 버전 | |
| SAF provider | Desktop / DocumentsUI / 클라우드 이름 |
| 앱 commit | |
| 수행자 | |
| 결과 | PASS / FAIL / BLOCKED |
| 증거 | 재현 로그, 스크린샷, 샘플 파일 경로 |

실패한 경우 “기대 결과 / 실제 결과 / 최소 재현 절차”를 함께 기록한다.

---

## 3. 저장·외부 변경 경합

### SAVE-01 기본 debounce 저장

1. 파일을 열고 고유한 문장을 입력한다.
2. 마지막 입력 후 500ms 이상 대기한다.
3. 외부 편집기로 파일을 열어 내용을 확인한다.

합격 기준:

- 마지막 입력이 1초 이내에 저장된다.
- frontmatter와 미관리 YAML은 보존된다.
- 같은 내용의 불필요한 반복 저장이나 cursor 재설정이 없다.

### SAVE-02 다중 파일 pending save

1. A 파일을 편집한 즉시 B 파일로 이동한다.
2. B도 편집한다.
3. 1초 후 두 파일을 외부 편집기에서 확인한다.

합격 기준: A와 B의 변경이 모두 저장된다.

### SAVE-03 pending save 중 외부 변경

1. 앱에서 `LOCAL_PENDING` 문자열을 입력한다.
2. 500ms가 지나기 전 외부 편집기에서 같은 파일을 `EXTERNAL_WINS`로 변경해 저장한다.
3. 앱 창을 활성화하고 2초 이상 대기한다.

합격 기준:

- 앱과 디스크 모두 `EXTERNAL_WINS`를 유지한다.
- 취소된 `LOCAL_PENDING`이 뒤늦게 디스크를 덮어쓰지 않는다.
- 크래시나 커서·포커스 무한 리셋이 없다.

### SAVE-04 자기 쓰기 mtime

1. 긴 문장의 중간에 cursor를 둔다.
2. 한 글자를 입력하고 3초 대기한다.

합격 기준: 앱의 자기 저장이 외부 변경으로 판정되어 블록 ID·cursor·selection이 초기화되지 않는다.

### SAVE-04A 외부 교체 직후 파일 전환

1. A 파일을 편집한 뒤 외부 편집기에서 같은 파일을 `EXTERNAL_REPLACEMENT`로 저장한다.
2. 앱 창을 활성화한 직후 B 파일로 이동하고 다시 A 파일로 돌아온다.
3. 2초 이상 기다린 뒤 앱과 디스크 내용을 확인한다.

합격 기준:

- A 파일은 `EXTERNAL_REPLACEMENT`를 유지한다.
- 전환 전 A 에디터의 늦은 snapshot이 A 또는 B 파일에 저장되지 않는다.
- B 파일의 cursor·selection·본문은 A 파일 상태의 영향을 받지 않는다.

### SAVE-05 rename·삭제 경합

1. 파일을 편집한 직후 rename한다.
2. 새 이름의 파일에 편집 내용이 저장됐는지 확인한다.
3. 다른 파일을 편집한 직후 외부에서 삭제한다.

합격 기준:

- rename 전 이름으로 stale write가 발생하지 않는다.
- rename 후 파일에 최신 편집이 보존된다.
- 삭제된 파일이 pending save로 재생성되지 않고 앱이 크래시하지 않는다.

### SAVE-06 pending save 직후 작업 공간 전환·종료

1. A Project의 파일에 `FLUSH_BEFORE_TRANSITION`을 입력한다.
2. 500ms가 지나기 전에 다른 Project 선택, Vault 다시 선택, 앱 데이터 초기화를 각각 실행한다.
3. Desktop에서는 같은 조건으로 창을 닫았다가 다시 실행한다.

합격 기준:

- 전환·초기화·종료 전에 마지막 입력이 디스크에 저장된다.
- 저장에 실패한 경우 해당 전환·종료가 중단되고 오류가 표시된다.
- 재시도 전 pending 값이 사라지지 않는다.

### SAVE-07 CRLF·BOM frontmatter 인덱싱

1. UTF-8 BOM과 CRLF를 사용하는 Markdown에 미관리 YAML 키와 본문을 작성한다.
2. 같은 줄바꿈을 사용하지만 frontmatter가 전혀 없는 BOM Markdown도 하나 작성한다.
3. `id` 또는 프로젝트 태그가 없는 상태로 프로젝트에 진입해 자동 인덱싱을 실행한다.

합격 기준:

- frontmatter fence는 한 쌍만 존재한다.
- BOM, CRLF, 미관리 YAML 키와 본문이 보존된다.
- `id`와 프로젝트 태그만 기존 frontmatter 안에 추가된다.
- frontmatter가 없던 파일도 BOM은 문서 절대 맨 앞에 한 번만 있고 새 frontmatter 뒤 본문 앞에는 남지 않는다.

### NAV-01 빠른 최신 탐색 우선

1. Project A를 선택한 직후 Project B를 선택한다.
2. 같은 방식으로 Folder A→B, File A→B를 빠르게 반복한다.
3. 로딩과 bookmark 저장이 끝난 뒤 현재 TopBar·파일 목록·본문과 앱 재시작 후 선택을 확인한다.

합격 기준: 늦게 완료된 A 작업이 B의 폴더·파일·bookmark·본문 상태를 덮어쓰지 않는다.

### LOAD-01 파일 읽기 실패와 재시도

1. 테스트 파일의 읽기 권한을 일시적으로 제거하거나 파일을 외부에서 잠근 뒤 앱에서 연다.
2. 오류 화면이 나타난 뒤 원인을 해소하고 `다시 시도`를 누른다.

합격 기준:

- 로딩 중에는 진행 표시가 보이고 빈 편집기가 먼저 나타나지 않는다.
- 실패 시 오류 문구와 `다시 시도` 버튼이 보인다.
- 재시도 성공 후 기존 Markdown 본문이 편집기로 열린다.

### SAVE-08 파일 이름 변경 검증

1. TopBar 파일명을 편집해 앞뒤 공백, `.md`, 운영체제 예약어와 같은 폴더의 중복 이름을 각각 제출한다.
2. 유효한 새 이름도 제출한다.

합격 기준:

- 잘못된 이름은 파일을 바꾸지 않고 입력한 편집명과 편집 상태를 유지하며 오류를 표시한다.
- 중복 이름에 자동 suffix를 붙여 다른 이름으로 변경하지 않는다.
- 유효한 이름은 한 번만 변경되고 선택·본문·pending save가 새 `FileKey`로 이어진다.

---

## 4. Android SAF provider 검증

각 provider마다 SAVE-01, SAVE-03, SAVE-04를 반복한다.

| provider | 필수 여부 | 결과 |
|---|---:|---|
| DocumentsUI 로컬 저장소 | 필수 | |
| Google Drive 등 실제 사용 클라우드 | 해당 시 | |

추가 확인:

- 앱 재시작 후 persisted URI permission이 유지된다.
- 외부 저장 후 provider의 mtime이 갱신되는다.
- mtime 갱신이 지연되더라도 중복 reload 루프나 데이터 손실이 없다.

---

## 5. dissolve 상호작

| ID | 절차 | 합격 기준 |
|---|---|---|
| DIS-01 | Code/Callout/Table 다음 Text 시작에서 Backspace | 직전 특수 블록만 raw Text로 변환 |
| DIS-02 | raw 블록의 marker를 유지한 채 계속 편집 | 편집 중 박스로 복귀하지 않음 |
| DIS-03 | marker 유지 후 focus-out | 200ms 후 해당 특수 블록으로 복귀 |
| DIS-04 | marker 파괴 후 focus-out | 일반 Text로 자동 해제 |
| DIS-05 | focus-out 후 200ms 안에 다시 focus | raw Text 유지 |
| DIS-06 | Callout title offset 0에서 Backspace | Callout 자체만 raw Text로 변환 |
| DIS-07 | Callout body 첫 블록 offset 0에서 Backspace | title로 커서 이동, Callout 유지 |

### Callout body 이동

| ID | 절차 | 합격 기준 |
|---|---|---|
| CAL-01 | body 없는 Standard title에서 Enter 또는 Tab | 빈 body를 만들고 첫 위치에 focus |
| CAL-02 | body 있는 Standard title에서 ↓, body 첫 위치에서 ↑ | body 시작으로 진입한 뒤 title 끝으로 복귀 |
| CAL-03 | body 있는 DL title 끝에서 →, body 시작에서 ← | title과 body 사이를 양방향으로 이동 |
| CAL-04 | DL title에서 ↓, 다음 외부 Text 첫 줄에서 ↑ | 외부 Text 시작으로 이동한 뒤 DL body의 가장 깊은 끝으로 역진입 |
| CAL-05 | body 마지막 빈 줄에서 Enter | trailing newline을 제거하고 다음 외부 블록으로 탈출 |

---

## 6. selection 상호작

| ID | 절차 | 합격 기준 |
|---|---|---|
| SEL-01 | Ctrl/Cmd+A 후 Ctrl/Cmd+C | 전체 raw Markdown이 clipboard에 복사 |
| SEL-02 | Multi 상태에서 Esc·일반 방향키·pointer press | Multi 해제. Esc는 focus endpoint, 앞/뒤 방향키는 선택 시작/끝 경계로 접고 pointer는 누른 native 위치 사용 |
| SEL-03 | Text 첫/끝 줄에서 Shift+↑/↓ | 인접 Text까지 selection 확장 |
| SEL-04 | Text에서 atomic Code/Callout/Table 방향으로 Shift 확장 | atomic 블록 전체만 선택 |
| SEL-05 | Callout title/body 경계에서 외부 방향 Shift 확장 | 부모 Callout 전체로 승격 |
| SEL-06 | 같은 container에서 Shift+↑/↓ 반복 | 누적 확장·역방향 축소 정상 |
| SEL-07 | 화면 밖 블록까지 Shift 확장 | endpoint로 스크롤하고 focus를 이동 |
| SEL-08 | Code·Table이 포함된 문서에서 Ctrl/Cmd+A → Delete → Undo | 빈 입력 문서가 된 뒤 원문 블록이 복원됨. Desktop 검증 완료 |
| SEL-09 | Ctrl/Cmd+A → Ctrl/Cmd+X | clipboard 기록 성공 후 선택 문서가 제거되고 빈 Text 하나가 남음. Desktop 검증 완료 |
| SEL-10 | SEL-09 직후 빈 Text에서 Ctrl/Cmd+A → Ctrl/Cmd+V | clipboard Markdown이 Code·Table 블록으로 복원됨. Desktop 검증 완료 |
| SEL-11 | SEL-10 직후 Undo 두 번 | Paste와 Cut이 각각 한 단계로 취소되고 최종 원문이 저장됨. Desktop 검증 완료 |
| SEL-12 | Text+Callout 문서 Ctrl/Cmd+A → 영문 또는 한글 입력 → 연속 입력 → Undo | 선택 범위가 Text 하나로 치환되고 삽입 끝에서 입력이 이어지며 Undo로 원문 구조가 복원됨. Desktop 검증 완료 |
| SEL-13 | Multi 상태에서 Esc 또는 왼쪽 방향키 → 문자 입력 | 선택 강조가 해제되고 Esc는 focus endpoint, 왼쪽 방향키는 선택 시작점에서 입력이 이어짐. Desktop 검증 완료 |
| SEL-14 | Windows 한글 IME를 켠 뒤 Multi 상태에서 자모를 조합하고 확정 | 조합 중에는 문서를 치환하지 않고 확정 문자열로 한 번만 치환되며 입력이 이어짐. 물리 한/영 키 자동화 미지원으로 수동 확인 필요 |

---

## 7. Undo/Redo

| ID | 절차 | 합격 기준 |
|---|---|---|
| UND-01 | Text 끝에 문자열 입력 → Ctrl/Cmd+Z → Ctrl/Cmd+Shift+Z 또는 Ctrl/Cmd+Y → Ctrl/Cmd+Z | 입력 제거 → 복원 → 제거가 순서대로 반영되고 마지막 원문이 저장됨. Desktop 검증 완료 |
| UND-02 | 같은 입력 위치에서 750ms 이내 여러 글자 입력 후 Ctrl/Cmd+Z | 연속 입력이 한 번에 취소됨 |
| UND-03 | Enter split, 블록 reparse·dissolve, Table 행/열 추가 뒤 Undo/Redo | 각 구조 동작이 한 단계로 취소·복원되고 Markdown 직렬화가 보존됨 |
| UND-04 | 편집 이력이 있는 파일을 외부에서 교체한 뒤 Ctrl/Cmd+Z | 외부 값을 새 baseline으로 사용하며 교체 전 앱 내용을 되살리지 않음 |
| UND-05 | A 파일 편집 후 B 파일로 전환해 편집하고 각각 Undo | 각 문서의 이력이 다른 파일에 적용되지 않음 |
| UND-06 | 중첩 Callout body 또는 Code 일부를 선택해 교체 → Undo → Redo → Undo | 내용이 순서대로 복원됨. focus·cursor·선택 강조 복원은 요구하지 않음. Desktop 검증 완료 |

UND-01과 UND-06은 2026-08-31 Desktop 화면에서 최종 원문 저장까지 확인했다. coalescing과 외부 baseline reset은
단위 테스트로 검증했다. UND-03·05의 전체 화면 행렬과 Table cell 수동 검증은 정식 P0 회귀에서
계속 수행한다.

---

## 8. Table 상호작

아래 비정형 파일을 외부에서 생성한다.

```markdown
| A | B |
| --- | --- | --- |
| 1 |
| 2 | 3 | 4 |
```

| ID | 절차 | 합격 기준 |
|---|---|---|
| TBL-01 | 샘플 파일 열기 | 3열로 정규화되고 빈 셀이 패딩됨 |
| TBL-02 | 셀에서 방향키 이동 | 경계 내부는 인접 셀, 외부는 인접 블록으로 이동 |
| TBL-03 | 마지막 열에서 Tab | 새 열 추가 후 편집 가능 |
| TBL-04 | 마지막 행에서 Enter | 새 행 추가 후 편집 가능 |
| TBL-05 | 오른쪽/아래 `+` 버튼 클릭 | 열/행 추가 후 기존 셀 내용 보존 |
| TBL-06 | 테이블 아래 블록에서 ↑ | 마지막 행 첫 셀로 진입 |

---

## 9. 종료 기준

P0 플랫폼 검증은 다음을 모두 만족하면 완료한다.

- Desktop의 SAVE-01~05, DIS-01~07, SEL-01~13, UND-01~06, TBL-01~06 PASS. SEL-14는 수동 확인
- Android DocumentsUI의 SAVE-01, SAVE-03, SAVE-04 PASS
- 자동 `jvmTest`와 Android debug build PASS
- FAIL 항목에 재현 절차와 후속 issue/작업이 기록됨
