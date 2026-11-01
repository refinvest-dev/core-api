# Raw Retention Rollout

이 문서는 5b-1의 Core 실행 코드와 V15 전환 절차를 다룬다. 보관 기간·삭제 상태의 정본은
`docs/TRADING_DATA_LIFECYCLE.md`와 ADR-061이며, 이 문서로 정책을 변경하지 않는다.

## Scope

- TTL worker와 raw orphan scheduler는 inbound port를 호출한다. Application이 작업 claim,
  transaction 밖 파일 I/O, 결과 반영을 조율한다. 기존 실행 주기 5초/1분과 삭제 batch 20개를 유지한다.
- 원본 purge는 Session의 artifact role scope와 legacy artifact scope를 먼저 fence하고 reader·임시 사본의
  해제를 확인한다. 이후 object 부재를 확인하고 DB의 filename·object locator·wrapped DEK·key version을
  제거한다. reader가 남아 있으면 `DELETION_PENDING`이며 실패 횟수를 소비하지 않고 5초 뒤 확인한다.
- 실제 I/O 오류는 5/10/20/40초 뒤 재시도하고 다섯 번째 오류에서 `DELETION_FAILED`로 남긴다. 실패를
  성공으로 표시하거나 자동으로 `ACTIVE`로 되돌리지 않는다. 원본 조회와 reparse 차단을 유지한다.
- 결과 반영은 artifact version과 DB가 발급한 5분 lease를 검증한다. row lock 대기를 마친 시각에도
  유효해야 하며, 만료·교체된 claim의 성공·실패 응답은 상태와 키를 바꾸지 않는다.
- orphan 정리는 참조된 object, 활성 업로드·reader와 grace 기간 안의 파일을 보존한다. 기존
  `refinvest.trading-review.raw.orphan-grace-seconds` 기본값 3600과 최소 600초 미만 실행 생략을 유지한다.

## Migration

V15는 `trading_source_artifacts.deletion_next_attempt_at`과 처리 대상 인덱스를 추가한다. 기존 raw
descriptor·expiry·version·attempt count·claim deadline·terminal 상태를 변경하지 않는다. 새 코드의
retention capability를 호출하기 전에 V15가 적용돼 있어야 한다.

이번 변경은 기존 정책 시계를 backfill하지 않는다. accepted/rejected 상태 전이의 보관 시각과
non-terminal TTL의 runtime 취소 연결은 5b-2의 별도 검증·수정 대상이다. 5b-1은 저장된
`raw_expires_at`을 기준으로 실행하며, 전체 보관 정책 구현의 완료를 뜻하지 않는다.

## Deployment

1. 모든 기존 Core 인스턴스에서 `refinvest.trading-review.retention-enabled=false`로 TTL worker를
   중지한다. 설정을 반영한 재시작으로 이전 worker 프로세스와 진행 중 실행이 종료됐는지 확인한다.
   새 구현과 기존의 직접 파일 삭제 worker를 동시에 실행하지 않는다.
2. TTL worker를 중지한 상태로 V15와 새 Core 코드를 적용한다. Flyway 검증과 기존 기능 기동을
   확인한다. 앞 단계에서 도입한 원본 저장소와 공유 workspace 설정을 유지한다.
3. 모든 TTL writer가 새 버전임을 확인한 뒤 retention을 활성화한다. 이전 claim은 보존되므로 아직
   유효한 lease가 있으면 최대 5분 뒤 reclaim할 수 있다. 재시작을 이유로 claim을 수동 해제하지 않는다.
4. 분리된 검증 DB와 합성 원본에서 reader drain, retry 시각 준수, 파일·키 부재 후 완료를 확인한다.
   immutable Revision·evidence·분석 입력과 unrelated artifact가 보존되는지도 확인한다.

## Observation

- `trading.review.raw.overdue`는 expiry가 지난 미삭제 artifact를 센다. `DELETION_PENDING`과
  `DELETION_FAILED`도 포함해 완료되지 않은 작업이 지표에서 사라지지 않게 한다.
- `trading.review.raw.deletion.failures`는 유효한 claim에 반영된 실제 오류만 센다. reader drain 대기와
  stale 응답은 실패로 세지 않는다. 지표에 owner·artifact ID·filename·object path 같은 label을 추가하지 않는다.
- 실패나 계속되는 drain 대기는 원본 파일과 key를 확인하는 운영 복구 대상이다. DB 상태만 `DELETED`로
  바꾸거나 실패 기록을 지워 해결하지 않는다. 원본 재업로드는 새 Session 경로를 사용한다.

## Rollback

1. TTL worker를 다시 중지하고 진행 중 새 worker가 종료됐는지 확인한다.
2. V15 column·index와 Flyway 이력은 유지한다. 기존 데이터와 원본 descriptor를 되돌려 쓰지 않는다.
3. 이전 코드는 새 claim 검증·drain·backoff를 제공하지 않는다. 이전 TTL worker를 활성화하는 rollback을
   정상 복구로 간주하지 않으며, 수정된 writer를 검증하고 재배포해 만료 purge를 재개한다.
