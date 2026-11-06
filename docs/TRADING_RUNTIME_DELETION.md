# Trading Normalization runtime deletion

ADR-061/063의 실제 삭제 완료를 위한 내부 실행 계약이다. 계산 입력·hash·result version은 변경하지 않는다.

## Identity and fencing

- Core는 `PUT /trading-review/normalization-runs/{coreNormalizationRunId}/deletion`을 호출한다.
  job ID를 모르는 생성 응답 유실도 같은 Core Run ID로 처리한다.
- `coreImportSessionId`와 `sourceDeletionGeneration`은 삭제할 Run의 불변 입력 identity다.
  `deletionRequestId`와 `deletionGeneration`은 Core 삭제 요청의 identity다. 서로 다른 scope의
  generation counter를 비교하거나 원래 입력 generation을 바꿔 쓰지 않는다.
- Compute는 create와 같은 Run 잠금 아래 tombstone을 먼저 저장한다. 이후 해당 Run의 create/replay,
  재시도·claim·grant 변경·publication은 데이터를 재생성하지 않는다. 이미 생성된 다른 Run이나
  다른 Session은 변경하지 않는다. 같은 Run의 다른 source identity 또는 같은 request의 다른
  generation은 409이며 상태와 capacity를 변경하지 않는다.
- Run tombstone과 삭제 receipt에는 opaque identity·시각·상태만 남긴다. 원본 payload, grant URI,
  거래값과 암호화 키는 남기지 않는다. 완료/복구 후 P180D 보관하고 backup restore 전에 replay한다.
  확인되지 않은 실행 자원이 남은 요청은 복구 전 만료하지 않는다.

## Actual absence

- `PENDING`은 정상적인 비동기 처리 대기다. Core는 지정된 간격 뒤 같은 요청을 다시 보내며 이 결과만으로
  오류 횟수를 증가시키지 않는다. 실제 dependency 오류에는 versioned bounded backoff와 기존 live purge
  24시간 SLA를 적용한다. terminal 실패는 원 요청을 되돌리지 않고 새 remediation 요청으로 복구한다.
- `ABSENT`는 해당 Run의 모든 실행 자원이 종료되고 job/request, grant/attempt, terminal payload와
  runtime 사본의 부재를 확인한 경우에만 반환한다. 취소 접수·CANCELLED·lease 만료·TTL 경과·404는
  이 확인을 대신하지 않는다. 실행 중인 소유자의 자원 종료 또는 같은 host의 실제 process identity
  부재를 확인한다. 원격 host 또는 legacy 자원의 부재를 확인할 수 없으면 PENDING을 유지한다.
- Compute Normalization은 원본을 child process 메모리에서만 처리한다. child가 원본을 읽기 전에
  실행 자원을 등록한다. child 종료·join과 부모가 전달받은 결과 payload 해제를 모두 확인한 뒤
  자원을 해제한다. 살아 있는 부모가 아직 자원을 해제하지 않았다면 child 종료만으로 부재를
  확정하지 않는다. 예외 경로도 child를 종료한다.
  단순 DB lease 만료로 살아 있는 child를 없다고 판단하지 않는다.
- Core는 response의 Run/Session/source generation/request ID/scope generation을 모두 검증한 뒤,
  현재 lease·version과 같은 삭제 generation일 때만 component 체크포인트를 저장한다.
  stale 응답은 요청·체크포인트·capacity·latest pointer를 변경하지 않는다.

## Core component execution

- 원본 staging·수신 spool·분석 입력 파일은 opaque Session/Run/Revision identity에 귀속시킨다.
  scope 삭제는 진행 중인 쓰기를 차단하고 사본을 정리한 뒤 실제 부재를 확인한다. 삭제 표식 이후의
  늦은 쓰기·publication은 실패하고 자신이 만든 사본을 제거한다. 재시작 뒤 orphan도 식별할 수 있어야 한다.
- DB transaction에서 실행 작업을 확보하고, 외부 HTTP/object/filesystem I/O는 transaction 밖에서
  수행한 뒤 짧은 CAS transaction으로 결과를 반영한다. primary row 삭제 전 opaque locator와 관계를
  보존하며 완료한 item은 다시 만들지 않는다. raw-only는 ledger·evidence·분석 입력을 지우지 않는다.
- 완료 판정은 고정된 필수 component 체크포인트의 집합으로 한다. 상위 요청은 원래 FAILED인 하위
  요청의 remediation 완료 증거도 같은 scope/generation에서 해석한다. 과거 전체 상태나 row 개수만으로
  부재를 추정하지 않는다.

## Rollout and acceptance

- 새 계약은 additive다. context → Compute migration/구현 → Core 소비 순서로 배포한다.
  구 worker의 실행 자원을 drain하고 transaction·child 종료를 확인한 뒤 새 자원 추적을 활성화한다.
  혼합 writer는 보장 범위에 포함하지 않는다. 확인할 수 없는 legacy 실행을 완료로 backfill하지 않는다.
- `TR-ACC-DELETE-003/006/007/009/011/012/013`에 대해 정상 완료, 활성 작업 취소, 생성 응답 유실,
  늦은 create·결과·파일 쓰기, 부분 실패·재시작·remediation, owner/scope 격리를 검증한다.
