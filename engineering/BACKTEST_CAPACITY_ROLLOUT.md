# Backtest capacity 전환 절차

Core 로컬 운영 안내다. 의미는 ADR-020, 비동기 접수는 ADR-047과 `RunBacktest` Use Case를 따른다.
실제 배포는 운영 담당자가 수행한다. 이 변경의 로컬 검증은 분리된 integration DB에서만 수행한다.

## 저장 경계

- `backtest_quotas`의 `used_count`는 UTC 월간 정상 접수 횟수이며 실패·완료에도 환불하지 않는다.
- `backtest_capacities`는 사용자별 예약·해제를 직렬화하는 잠금 row다.
- `backtest_capacity_reservations`에는 실행 ID별 활성 예약만 저장한다. 종료 시 해당 ID의 예약만
  삭제하므로 같은 실행의 중복 종료가 다른 실행의 capacity를 해제하지 않는다.
- 기존 `active_count` 컬럼은 schema 호환을 위해 보존한다. 새 코드의 동시 한도 판정에는 사용하지 않는다.
- 접수의 월은 Run의 `createdAt`에서 한 번 정한다. quota·예약·Run·durable dispatch는 같은
  application transaction으로 commit/rollback한다. 종료 상태와 예약 해제도 같은 transaction이다.

## 배포 순서

1. 기존 schema/data의 복구 가능한 백업과 현재 월별 사용량·실행 상태를 확인한다.
2. 백테스트 신규 접수를 운영 수단으로 중지하고 기존 Core의 dispatch/polling을 중지한다.
   모든 이전 Core 인스턴스의 처리를 종료한 뒤 V11을 적용한다. 이전·신규 예약 방식을 혼용하지 않는다.
3. 정상 Flyway 절차로 `V11__backtest_capacity_reservations.sql`을 적용한다. 기존 월간 사용량과
   Run/Result/dispatch는 보존한다. 새 DB는 예약용 테이블을 만들고, 기존 DB는 모든 UTC 월의
   `PENDING`/`RUNNING` Run과 Strategy 소유자를 연결해 활성 예약을 복원한다.
4. 활성 Run의 소유자를 해석할 수 없으면 migration이 실패하고 transaction을 rollback한다.
   원인을 조사·복구한 뒤 다시 적용하며, 임의 owner나 0건으로 대체하지 않는다.
5. 새 Core로 기동한 뒤 활성 예약의 실행 ID/owner 집합과 기존 활성 Run 집합, 월별 사용량 보존을
   확인한다. 단일 실행 접수·종료, 동시 한도와 새 월의 월간 한도 검증 후 접수·dispatch/polling을 재개한다.

## 복구와 검증 범위

- migration 자체 실패는 Flyway/PostgreSQL transaction으로 복구한다. 성공한 V11을 편집하지 않는다.
- 전환 뒤에는 legacy `active_count`가 갱신되지 않는다. 이전 바이너리 재기동은 트래픽·worker 중지와
  현재 활성 Run에 맞춘 legacy counter 복구를 포함한 별도 검증이 필요하다. 기본 복구는 새 코드의
  전진 수정 또는 같은 시점의 schema/data 복구 계획에 따른다.
- 이 변경은 capacity의 실행별 해제를 멱등화한다. 만료된 polling lease의 Run 변경 차단은 R04/1c에서
  별도로 수행한다.
- 테스트는 월 경계, 동시 최초 접수, 사용자별 한도, 월간 거절 시 예약 미소비, 중복 해제, transaction
  rollback, 새 DB migration, 기존 활성 Run backfill 및 미해석 owner의 migration 실패를 검증한다.
- 실제 운영 credential·fixture·compatibility evidence는 이 문서나 저장소에 기록하지 않는다.
