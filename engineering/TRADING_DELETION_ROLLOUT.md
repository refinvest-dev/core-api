# Trading deletion rollout

ADR-061/063과 [runtime 삭제 계약](../docs/TRADING_RUNTIME_DELETION.md)을 적용하는 Core 로컬 전환 절차다.
외부 계약은 Context 정본에서 변경한다.

## Preconditions

- 기존 Core의 upload/read/publication과 Compute worker를 drain하고, child 종료뿐 아니라 부모가
  전달받은 결과 payload를 해제했는지 확인한다. 이전 writer와 새 writer를 혼합 실행하지 않는다.
- 여러 Core 인스턴스는 같은 DB와 보호된 raw/analytics volume을 사용해야 한다.
  `refinvest.trading-review.raw.root` 아래 `workspaces`도 같은 volume이어야 한다. JVM 임시 디렉터리를
  인스턴스별로 사용하는 구성은 여러 인스턴스의 부재 확인을 보장하지 않는다.
- 해당 volume에서 JVM 간 shared/exclusive file lock이 동작하는지 확인한다. scope 삭제 표식은 새
  reader/writer를 거절하고, 기존 handle을 drain한 뒤 실제 파일 부재를 확인한다.
- 전환 전 global raw staging과 legacy result spool을 조사한다. 귀속을 확인할 수 없는 사본은
  완료로 backfill하지 않는다. orphan sweeper의 최대 보존 시간은 24시간이며 활성 handle은 건드리지 않는다.

## Deployment order

1. Context의 additive Compute 계약과 Core/Compute/Web snapshot을 먼저 동기화한다.
2. 기존 Compute 실행을 drain한 뒤 `0018_trading_normalization_runtime_deletion.sql`과 새 Compute를 적용한다.
   migration은 기존 `RUNNING` 자원을 알 수 없는 legacy 자원으로 보존한다. 이 경우 lease 만료나
   `CANCELLED` 상태만으로 부재를 확정하지 않는다.
3. 기존 Core writer와 삭제 worker를 중지한 뒤 V13/V14와 새 Core 전체 인스턴스를 적용한다.
   V14는 기존 요청·실패·완료 기록을 보존하고 lease, 실행 정책, 다음 시도 시각, 복구 관계를 추가한다.
4. 분리된 integration DB와 합성 fixture로 cutover 및 삭제 검증을 수행한 뒤 admission을 연다.
   기동·환경 설정·검증 증적의 정본은 integration의 `README.md`와 `AGENTS.md`다.

삭제 대상의 Core Run ID는 job 생성 응답 유실 때도 보존된다. Compute는 이 Run ID를 먼저 차단하고,
원래 Session/source generation과 현재 삭제 요청/scope generation을 각각 검증한다. 두 generation을
수치로 비교하거나 원래 입력 generation을 바꿔 쓰지 않는다.

## Execution policy

V14가 고정하는 `DELETION_EXECUTION_V1`의 실행 규칙은 다음과 같다.

- 한 번에 요청 하나를 5분 lease로 확보하고 외부 작업을 최대 10개 처리한다. HTTP와 파일 작업은
  DB transaction 밖에서 수행한다. 결과는 현재 lease·owner·scope·generation·version을 잠근 뒤 반영한다.
- Compute `PENDING`이나 열린 파일 handle은 정상 대기다. 1~60초의 응답 간격 또는 5초 뒤 다시
  확인하며 오류 횟수를 올리지 않는다. 미완료 항목만 다시 처리한다.
- 실제 오류에는 5, 10, 20, 40초 backoff를 적용한다. 다섯 번째 오류는 `FAILED`로 남긴다.
  stale lease 응답은 체크포인트·상태·실패 횟수·원본 키를 바꾸지 않는다.
- 요청 시점부터 24시간을 넘긴 미완료 요청도 `FAILED`로 남기고 SLA 오류를 기록한다.
  단순 대기 때문에 실패한 경우 오류 횟수는 0일 수 있다.
- 완료는 필수 component 전부의 실제 부재 확인을 요구한다. 취소 접수, TTL, DB primary row 제거,
  파일 삭제 호출 성공이나 과거 완료 상태는 이 확인을 대신하지 않는다.

## Recovery

`FAILED` 원 요청을 되돌리지 않는다. 운영 composition은
`RemediateTradingDeletionUseCase.remediate(DeletionRequestId(failedId))`를 호출해 같은 owner·scope·generation의
새 요청을 만든다. 원 요청의 task locator와 이미 확인한 item 증거를 보존하고 나머지만 처리한다.
같은 실패 요청의 복구 생성은 idempotent하며, 복구도 실패하면 그 실패 요청에 다음 복구를 연결한다.

원 요청은 `FAILED`로 audit에 남는다. 복구 요청이 필수 부재 확인을 마치면 `COMPLETED`가 되며, 상위
삭제는 같은 scope/generation의 이 완료 증거를 확인하고 진행한다. 원 요청의 생성 replay는 원래 요청을 반환한다.

원격 host의 실행 자원, 소실된 legacy Run/Session 관계 또는 불완전한 과거 workset의 범위를 확인할 수
없다면 복구 요청도 완료하지 않는다. 운영자는 실제 자원 종료와 보존된 locator를 먼저 조사해야 하며,
체크포인트나 coverage flag를 임의로 바꿔 완료 상태를 만들지 않는다.

## Rollback

새 tombstone을 무시하는 구 Compute나 lease 없이 진행하는 구 Core로 바로 되돌리지 않는다.
먼저 admission과 writer를 중지하고 실행 자원을 drain한다. DDL을 되돌려 기존 요청·locator·복구 audit를
삭제하지 않는다. backup restore는 ADR-061에 따라 deletion ledger replay와 부재 확인 뒤에 연다.

## Evidence

로컬 credential, 원본 fixture 산출물과 실행 로그는 커밋하지 않는다. PR에는 실행한 시나리오·테스트 수와
검증 범위만 기록한다. 재시작·HTTP 전송 오류로 실패한 실행은 성공한 실행과 구분한다.
