# Google Drive 커밋 백업

## 기능 상태: 비활성

Google Drive 백업 코어는 향후 연동을 위해 소스와 테스트만 보존하며, 현재 애플리케이션 실행 경로에는
연결하지 않는다.

- `MainViewModel`의 커밋은 로컬 `ProjectCommitService`만 호출한다.
- 백업 queue, worker, 원격 store는 production DI에 등록하지 않는다.
- Google 로그인, OAuth client ID, Drive REST adapter와 설정 UI를 제공하지 않는다.
- 따라서 커밋해도 백업 queue 생성, 원격 요청 또는 Drive 업로드가 발생하지 않는다.

Android와 Desktop 설치형 OAuth public client를 사용하면 별도 애플리케이션 서버 없이 직접 연동할 수 있다.
향후 상시 서버 작업이나 중앙 집중식 다중 기기 조정 때문에 별도 서버가 필수가 되는 확장은 현재 구현
범위에서 제외한다. 해당 요구가 생겨도 기존 코어를 삭제하지 않고 비활성 상태로 유지한다.

기능 활성화는 다음 조건을 모두 갖춘 뒤 별도 작업으로 진행한다.

1. Android와 Desktop OAuth client ID 준비
2. `drive.file` 범위와 플랫폼별 안전한 token 저장소 적용
3. 실제 Drive adapter 및 다중 기기 staging/publish 계약 검증
4. 사용자가 명시적으로 켤 수 있는 설정 UI와 production DI 등록

## 현재 구현 범위

Google Drive 연동의 첫 단계는 **로컬 커밋 이후 실행되는 선택적 upload-only 백업**이다.
로컬 `ProjectCommitService`가 유일한 commit authority이고, 원격 장애는 이미 만들어진 로컬 커밋을
취소하거나 되돌리지 않는다.

현재 공통 코드에는 다음 경계까지만 구현한다.

- 로컬 commit parent 연결을 따라 원격 HEAD 이후의 백업 계획 생성
- content-addressed blob, tree, commit을 불변 원격 객체로 게시
- 최신 tree의 추적 대상 Markdown을 `fileId`, 상대 경로, `blobHash` 작업본 manifest로 제공
- 불변 객체 → 작업본 mirror → 원격 HEAD 순서 강제
- 같은 경로·같은 content hash를 성공한 no-op으로 처리하는 재시도 계약
- 원격 HEAD가 로컬 이력의 조상이 아니거나 준비 중 변경되면 중단
- 선택적 백업 queue 등록 실패를 로컬 커밋 결과와 분리
- `.machum/repository.json`에 프로젝트 UUID를 생성·보존
- 계정·프로젝트별 Drive folder ID와 마지막 성공 commit을 DataStore에 저장
- pending 작업은 같은 계정·프로젝트의 가장 최신 commit 하나로 합치고 실패 횟수·메시지를 보존
- 동일 앱 process에서는 project UUID별 공유 mutex로 여러 service/worker를 직렬화

실제 Google OAuth, Drive REST 구현, access/refresh token 보안 저장, 설정 UI, OS 백그라운드 작업은 아직 포함하지 않는다.

## 원격 저장 계약

```text
history/
├── blobs/<sha256>.blob
├── trees/<tree-hash>.json
├── commits/<commit-id>.json
└── HEAD.json                 # 항상 마지막 갱신

metadata/
├── repository.json          # 안정적인 project UUID
└── project-config.json      # commit 밖의 최신 복구 설정

workspace/
└── <프로젝트 상대 Markdown 경로>
```

`RemoteProjectBackupStore` 구현은 다음 조건을 지켜야 한다.

1. `putImmutable()`은 같은 경로의 같은 hash가 이미 있으면 네트워크 본문 업로드 없이 성공한다.
2. 같은 불변 경로에 다른 hash가 있으면 덮어쓰지 않고 저장소 손상으로 처리한다.
3. `upsertMetadata()`는 commit 이력과 분리된 최신 복구 설정을 교체한다.
4. `mirrorWorkspace()`는 `fileId` 정체성을 사용해 추가·수정·삭제·rename을 반영하고 재호출에 안전해야 한다.
5. `updateHead()`는 전달받은 기존 HEAD가 아직 현재일 때만 실행한다.
6. HEAD 게시 전 실패하면 다음 실행이 같은 로컬 commit을 안전하게 다시 시도할 수 있어야 한다.

Drive 구현에서는 원격 파일의 `appProperties`에 MaChum project/file/object ID와 content hash를 기록해
파일명 검색이나 프로젝트명 중복에 의존하지 않는 방식이 적합하다.

## `.machum.json` 정책

`.machum.json`은 기존 정책대로 새 CommitTree, diff, restore 대상이 아니다. 따라서 `workspace`에도
포함되지 않는다. 과거 legacy tree가 설정 entry를 이미 포함한다면 그 tree 자체의 무결성을 보존하기 위해
참조 blob을 history 객체로만 백업할 수 있다.

프로젝트 폴더 유형·Plot·자동 태그 설정은 `metadata/project-config.json` 최신본으로 별도 백업한다.
이 파일은 mutable recovery metadata이며 commit 이력에는 포함하지 않는다.

## 확정된 로컬 identity와 queue 정책

- project UUID만 commit 추적 밖의 `.machum/repository.json`에 둔다.
- Google 계정의 안정 ID, Drive folder ID와 마지막 성공 commit cursor는 앱 DataStore에 둔다.
- OAuth token은 DataStore나 프로젝트 파일에 넣지 않고 플랫폼 보안 저장소 구현 시 별도로 관리한다.
- 첫 연결은 원격 HEAD가 없으므로 현재 HEAD까지 도달 가능한 전체 history를 bootstrap한다.
- 이후 pending은 최신 target commit 하나로 합쳐도 parent 연결을 따라 중간 history를 모두 계획한다.
- 실패 작업은 앱 실행 중 또는 다음 실행에서 다시 처리한다. Android WorkManager와 Desktop background
  lifecycle은 실제 Drive adapter 이후 별도 단계다.
- workspace는 읽기 가능한 upload-only mirror다. Drive에서 직접 수정한 내용은 1차에서 가져오지 않는다.

## 동시성 보장 범위

동일 앱 process 안에서는 UUID별 공유 mutex가 service instance와 worker instance를 가로질러 백업 한 건만
실행한다. 각 실행은 계획 전, 불변 객체 게시 전후에 원격 HEAD를 다시 읽고 base와 다르면 metadata/workspace를
게시하지 않는다. `updateHead()`도 expected base 검증을 요구한다.

이 보장은 다른 기기나 다른 process가 HEAD 확인 직후 workspace 게시 사이에 끼어드는 경우까지 원자적으로
막지는 못한다. 실제 Drive adapter의 다중 기기 단계에서는 다음 staging/publish 계약이 필요하다.

1. target commit ID 아래 staging workspace와 metadata를 준비한다.
2. 원격 HEAD와 generation/version을 다시 확인한다.
3. 조건부 publish marker 또는 새 current pointer를 한 번에 교체한다.
4. publish에 실패한 staging은 현재 workspace로 노출하지 않고 후속 정리 대상으로 남긴다.

Drive API에서 사용할 수 있는 revision/etag 조건과 folder 전환 방식이 확정되기 전에는 다중 기기 merge나
원격 삭제를 구현하지 않는다.

## 검증 기준

- 최초 연결 시 현재 HEAD까지 도달 가능한 commit/tree/blob 전체가 준비된다.
- 증분 백업은 동일 hash 객체를 다시 만들지 않는다.
- 추가·수정·삭제·rename 뒤 workspace manifest가 최신 tree와 일치한다.
- blob/tree/commit 또는 workspace 단계 실패 시 원격 HEAD는 갱신되지 않는다.
- 실패 후 재시도로 동일 객체 충돌 없이 완료된다.
- 두 service/worker가 같은 project UUID를 처리해도 두 번째 실행은 첫 HEAD 게시 이후 새 base를 읽는다.
- 불변 객체 업로드 중 외부 HEAD 변경을 발견하면 metadata와 workspace는 게시되지 않는다.
- 백업 queue 등록 실패 후에도 로컬 HEAD와 history는 유지된다.
- queue wrapper를 다시 만들어도 binding, pending target, 실패 횟수와 cursor가 유지된다.
- `.machum.json` 변경만으로 새 commit 또는 workspace entry가 만들어지지 않는다.
