# Raw Policy Clocks Rollout

5b-2의 Core V16 전환과 운영 확인 절차다. 정책 정본은 ADR-061과 `docs/TRADING_DATA_LIFECYCLE.md`다.
구현은 [Core PR #103](https://github.com/refinvest-dev/core-api/pull/103), 실제 서비스 검증 runner는
[Integration PR #46](https://github.com/refinvest-dev/integration/pull/46)에 있다.

## Behavior

- 새 accepted publication은 `accepted_at`과 artifact의 `accepted_at + P7D`를 같은 transaction에서 저장한다.
  새 rejection은 `rejected_at + PT24H`를 고정한다. replay·조회·raw-only 삭제가 이 시각을 연장하지 않는다.
- 발행은 부모·Session·Run/dispatch/staging·artifact를 잠근 뒤 읽은 시각으로 raw 가용성과 lease를 검증한다.
  이미 만료됐거나 purge가 시작된 source를 accepted로 바꾸거나 기한을 연장하지 않는다.
- non-terminal source 만료는 Session을 `PROCESSING_FAILED / SOURCE_ARTIFACT_EXPIRED / REUPLOAD_REQUIRED`로
  전이하고 generation을 증가시킨다. `raw_expired_at`과 실제 raw-only `DeletionRequest`를 같은 transaction에
  기록한다. 중복 trigger는 새 요청이나 generation 증가를 만들지 않는다.
- 기존 삭제 실행이 Run 생성 fence·실제 runtime 부재 확인·scoped copy/object/key purge를 수행한다.
  job ID를 받지 못한 생성 응답 유실도 원래 Run identity로 정리한다. 취소 접수나 job 404만으로 완료하지 않는다.
  Session metadata와 기존 Revision·evidence·Book latest는 raw-only 경계에 따라 보존한다.
- publication이 worker보다 먼저 expiry를 관측하면 발행하지 않고 durable expiry 전이를 기다린다.
  이전 generation으로 PERSISTED ACK나 가짜 DISCARDED_STALE 증거를 만들지 않는다.

## Legacy Data

V16은 nullable `accepted_at`, `rejected_at`, `raw_expired_at`과 후보 검색 인덱스를 추가한다.
기존 accepted 시각은 연결된 Revision의 `published_at`, rejected 시각은 기존 failure의 `occurred_at`에서
유효한 범위의 증거가 있을 때만 복원한다. 증거가 없으면 null을 유지하고 현재 시각으로 추정하지 않는다.

기존 artifact의 `raw_expires_at`은 변경하지 않는다. 과거에 너무 짧게 저장된 보관 기한을 소급 연장하거나,
이미 만료·삭제된 raw를 되살리지 않는다. 신규 전이부터 정책 시계가 정확하게 적용된다.

## Deployment

1. 기존 transition control로 fleet의 normalization admission/create/publication을 중지한다. 기존 writer의
   transaction과 파일 실행을 drain하고, `refinvest.trading-review.retention-enabled=false`로 TTL/expiry worker도
   중지한다. 이전 publisher와 새 정책 writer를 혼합해 실행하지 않는다.
2. V16과 새 Core 코드를 모든 관련 writer에 적용하고 Flyway 검증을 확인한다. V16 전 코드로 신규 terminal
   전이를 계속 만들면 정책 시각을 고정하지 못하므로 migration 적용만으로 전환을 끝내지 않는다.
3. 새 코드만 남은 상태에서 retention을 활성화한다. expiry scheduler의 기본 주기는 5초이며
   `refinvest.trading-review.expiry-delay`로 조정한다. 보관 기간은 이 설정으로 바꾸지 않는다.
4. 분리된 DB와 합성 파일에서 accepted clock/replay, queued·응답 유실 Run의 expiry, 원본만 제거한 뒤의
   Review 보존을 확인한다. 검증된 transition control 절차로 normalization을 재개한다.

## Rollback

TTL/expiry와 normalization 쓰기를 중지한 상태에서 복구한다. V16 column·index와 Flyway 이력, 기록된 시각,
generation·삭제 요청·runtime fence는 유지한다. 이전 코드는 만료 전이와 늦은 발행 차단을 제공하지 않으므로
이전 writer를 다시 활성화하는 것을 정상 복구로 간주하지 않는다. 검증한 수정 코드로 재개한다.
