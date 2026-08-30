# MaChum P0 수동 테스트

> 역할: 자동 테스트로 재현하기 어려운 플랫폼 I/O와 에디터 상호작용의 P0 검증 절차  
> 관련 정책: [product-roadmap.md](product-roadmap.md), [markdown-editor.md](markdown-editor.md)  
> 마지막 갱신: 2026-08-29
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

### SAVE-05 rename·삭제 경합

1. 파일을 편집한 직후 rename한다.
2. 새 이름의 파일에 편집 내용이 저장됐는지 확인한다.
3. 다른 파일을 편집한 직후 외부에서 삭제한다.

합격 기준:

- rename 전 이름으로 stale write가 발생하지 않는다.
- rename 후 파일에 최신 편집이 보존된다.
- 삭제된 파일이 pending save로 재생성되지 않고 앱이 크래시하지 않는다.

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

---

## 6. selection 상호작

| ID | 절차 | 합격 기준 |
|---|---|---|
| SEL-01 | Ctrl/Cmd+A 후 Ctrl/Cmd+C | 전체 raw Markdown이 clipboard에 복사 |
| SEL-02 | Multi 상태에서 Esc·일반 방향키·pointer press | Multi 해제, native cursor 동작 유지 |
| SEL-03 | Text 첫/끝 줄에서 Shift+↑/↓ | 인접 Text까지 selection 확장 |
| SEL-04 | Text에서 atomic Code/Callout/Table 방향으로 Shift 확장 | atomic 블록 전체만 선택 |
| SEL-05 | Callout title/body 경계에서 외부 방향 Shift 확장 | 부모 Callout 전체로 승격 |
| SEL-06 | 같은 container에서 Shift+↑/↓ 반복 | 누적 확장·역방향 축소 정상 |
| SEL-07 | 화면 밖 블록까지 Shift 확장 | endpoint로 스크롤하고 focus를 이동 |

---

## 7. Table 상호작

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

## 8. 종료 기준

P0 플랫폼 검증은 다음을 모두 만족하면 완료한다.

- Desktop의 SAVE-01~05, DIS-01~07, SEL-01~07, TBL-01~06 PASS
- Android DocumentsUI의 SAVE-01, SAVE-03, SAVE-04 PASS
- 자동 `jvmTest`와 Android debug build PASS
- FAIL 항목에 재현 절차와 후속 issue/작업이 기록됨
