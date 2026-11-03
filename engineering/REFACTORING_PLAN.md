# Core API 아키텍처 리팩토링 실행 계획

이 문서는 Core가 소유하는 로컬 실행 목록이다. 제품 의미·Aggregate 경계·wire 계약은
[문서 정본 안내](../docs/README.md)의 context snapshot을 따른다. 구현 규칙은
[AGENTS.md](../AGENTS.md), 커밋·PR·머지 규칙은 [GIT_WORKFLOW.md](../docs/GIT_WORKFLOW.md)를 따른다.
이 문서의 작업 상태와 PR 링크는 작업을 완료할 때 갱신한다.

0~6c-3 코드 변경과 Integration #49/#50은 main에 반영됐다. 최종 main 조합 검증과 실제 운영 적용의
미확인 항목은 [Refactoring Handoff](REFACTORING_HANDOFF.md)에서 구분한다.

## 1. 목표와 변경 기준

기준은 ADR-018의 feature-centric Hexagonal 구조이며, 관련 업무 계약은 ADR-013,
ADR-019~020, ADR-047, ADR-061, ADR-063~070과 적용되는 Use Case다.

```text
Command -> Application -> Aggregate 생성/행위 -> AggregateStore
        -> PersistenceMapper -> JPA/JDBC 저장 모델

command-side load -> 저장 모델 조회 -> Mapper -> 검증된 Aggregate 복원
read-only query -> Reader -> read model -> Result -> web DTO
```

- Aggregate Root는 공통 AggregateRoot와 Identifier 기반 typed ID를 사용한다.
- 생성·복원·상태 전이가 불변식을 보장하고, 소속 Entity는 Root를 통해 업무 변경한다.
- Mapper는 persistence 모듈에 두며 I/O 없이 변환한다. 복원 시 새 업무 이벤트를 발생시키지 않는다.
- 조회용 projection과 command Aggregate 복원은 각각의 목적에 맞게 구현한다.
- CAS/잠금, idempotency, immutable membership, version pin, hash와 삭제 generation을 보존한다.
- NormalizationRun·TradingSourceArtifact는 정본상 ImportSession 소속이다. 저장 코드 분리를 위해
  독립 Aggregate로 승격하지 않는다. Account·Book·Session·LedgerRevision·DeletionRequest의
  Root 여부는 [Persistence 계약](../docs/TRADING_REVIEW_PERSISTENCE.md)의 matrix를 따른다.
- Trading Review의 JDBC를 우선 유지한다. JPA 전환은 별도 근거와 검증을 갖춘 작업으로 제안한다.
- 대용량 Revision/Result는 stream/batch로 처리한다. 저장 API를 단순화하려고 모든 row를 적재하지 않는다.
- schema/wire/hash 의미를 바꿔야 하면 context 정본을 먼저 변경·동기화한다.

## 2. 리뷰 발견 사항과 검증 상태

아래 항목은 코드·문서 대조 결과다. 동시성·장애 시나리오는 후속 PR에서 실제 회귀 테스트로 재현한다.
`코드 확인`은 운영 장애 재현이나 수정 완료를 뜻하지 않는다. 각 PR은 테스트 결과와 관련 계약을
기록하고, 재현 결과에 따라 수정 범위를 조정한다.

| ID | 대상 | 코드 확인 내용 | 후속 검증/목표 |
|---|---|---|---|
| R01 | Trading Review 접근 | grant redemption이 상위 Account/Book 삭제 상태를 검사하지 않음 | 상위 삭제 commit 직후 기존 토큰 거절, 다른 owner/scope 영향 없음 |
| R02 | Backtest capacity | monthly quota row에 active_count도 저장 | 이전 달 실행을 포함해 동시 한도 유지, terminal에서 정확히 한 번 해제 |
| R03 | Strategy 저장 | 전체 version collection 교체, optimistic version/command lock 부재 | 동시 version 추가 결과 모두 보존, 기존 immutable version 유지 |
| R04 | Backtest polling | Run 변경 뒤 dispatch claim token 검사 | 만료·교체된 lease의 응답이 Run/capacity/dispatch를 변경하지 않음 |
| R05 | Strategy 조회 | 정렬 없는 association의 lastOrNull로 최신 버전 선택 | 명시적 최신 기준과 동률 tie-break, 저장/조회 순서 독립 |
| R06 | Compute 호출 | Backtest/Asset 클라이언트에 명시적 connect/read timeout 부재 | 응답 지연/연결 실패의 bounded 종료와 retry, lease와 정합 |
| R07 | Trading Review domain | Root 공통 기반 누락, public Session 생성/copy로 상태 조합 가능 | 생성·복원·전이 불변식과 잘못된 상태 거절 |
| R08 | Persistence 매핑 | 변환 코드 분산·중복, Backtest 변환 내부의 결과 조회 | Aggregate별 Mapper, 완전한 복원, Mapper I/O 제거 |
| R09 | Trading Review 저장 | 여러 Store를 구현한 통합 adapter, SQL 중심 발행/삭제 상태 변경 | Aggregate별 adapter, 명시적 발행/삭제 모델과 원자적 저장 |
| R10 | Port/Application/Web | 포트의 파일 복호화·삭제 구현, 내부 HTTP status, scheduler의 구현체 호출 | 기술 구현 격리, 실패 의미와 HTTP 매핑 분리, inbound port 진입 |
| R11 | 삭제/보관 | persistence worker가 업무 절차 수행, object 작업이 DB transaction 안에 있음 | durable 단계별 처리, 짧은 DB transaction, 부분 실패 복구 |
| R12 | Feature 경계 | Backtest->Strategy/Subscription, Auth->Member domain 직접 참조 | feature port 계약으로 연동, 변경 모듈의 경계 검사 |
| R13 | Web/read model | domain failure 직접 응답, DTO에서 purge 상태 추정 | web DTO 변환, 실제 component 상태 기반 삭제 read model |
| R14 | Schema | Flyway와 Hibernate ddl-auto:update 병행 | 기존 모듈 migration 편입, 새 DB/기존 DB 검증 후 운영 validate 전환 |
| R15 | 운영 설정 | public health 상세 공개, 기본 Snowflake node-id=0 | health 공개 범위 제한, 다중 인스턴스 ID 설정·검증 |
| R16 | 검증 체계 | import 일부만 검사하는 경계 테스트, DB 조건부 테스트/캐시 | 변경 경계 검사 확대, 실제 PostgreSQL 및 integration 결과를 구분해 기록 |
| R17 | Raw 정책 시계 | upload의 24시간 expiry가 accepted/rejected 전이에서 갱신되지 않으며 non-terminal TTL의 runtime 취소 연결이 없음 | 5b-2에서 terminal 시계 고정·실제 expiry 요청·generation fence와 늦은 발행 거절 구현, 아래 검증 기록 참조 |
| R18 | 삭제 영향 범위 count | Book 삭제는 Book 1개만, Account 삭제는 Account/Book 수만 요청에 고정하며 하위 Session/artifact와 Revision/Run 수를 충분히 저장하지 않음 | 5c-2에서 요청 시점 scope snapshot·영속 count와 replay/조회·legacy 복원을 검증; 현재 count를 부재 증거로 쓰지 않음 |
| R19 | Auth HTTP 진입점 | Controller가 outbound token parser와 concrete security adapter를 직접 의존 | 6c-2/[Core #109](https://github.com/refinvest-dev/core-api/pull/109)에서 raw credential 업무 진입점·HTTP 기술 소유권 분리, 검증된 요청만 transaction 진입 |
| R20 | Trading Review port 구성 | Account/Book/Import inbound 계약과 storage/idempotency outbound capability·모델이 파일에 혼재 | 6c-3/[Core #110](https://github.com/refinvest-dev/core-api/pull/110)에서 기능별 Command/Query/Result와 계약 파일 분리, Aggregate 저장·기존 wire 의미 보존 |

주요 근거 파일:

- R01: `tradingreview/adapter/persistence/.../JdbcArtifactAccessGrants.kt`,
  `tradingreview/application/.../TradingReviewService.kt`
- R02/R04: `backtest/adapter/persistence/.../JpaBacktestQuotaStoreAdapter.kt`,
  `backtest/application/.../PollSubmittedBacktestsService.kt`,
  `backtest/adapter/persistence/.../JpaBacktestComputeDispatchStoreAdapter.kt`
- R03/R05: `strategy/adapter/persistence/.../JpaStrategyStoreAdapter.kt`,
  `StrategyJpaEntity.kt`, `JpaStrategyReaderAdapter.kt`
- R07~R13: `tradingreview/{domain,port,application,adapter}`, 각 feature의 persistence adapter
- R14~R16: `app/src/main/resources/application.yaml`, `build-logic`,
  `app/src/test/kotlin/com/refinvest/TradingReviewModuleBoundaryTest.kt`

## 3. 단계와 PR 단위

각 단계는 아래 순서로 진행한다. PR은 한 가지 목적과 독립 검증 범위를 갖고, 구현과 회귀 테스트를
함께 포함한다. 같은 feature에서 여러 계층이 함께 바뀌어도 동작 가능한 세로 단위로 묶는다.
큰 PR을 파일 종류만으로 기계적으로 나누지 않는다.

| 단계 | 권장 PR 단위/순서 | 완료 기준 |
|---|---|---|
| 0 | 로컬 규칙과 이 실행 목록 | 기준·소유권·검증 상태·후속 PR 순서 명문화 |
| 1a | R01 삭제 후 artifact 접근 차단 | 실제 DB에서 Account/Book 삭제 후 기존 grant 거절 |
| 1b | R02 월간 quota와 동시 capacity | 월 경계·동시 요청·rollback·중복 terminal 검증 |
| 1c | R04 polling lease와 상태 반영 | 느린 이전 worker와 새 claim 경합 검증 |
| 1d | R03 Strategy 동시 저장 | 동시 version 추가·rollback·기존 version 보존 검증 |
| 1e | R05 최신 버전 정렬 | 조회 순서와 시각 동률에 대한 deterministic 선택 |
| 1f | R06 HTTP timeout | 지연/실패 테스트, retry 및 lease 정합 |
| 2a | Member/Subscription 기준 구현 | Aggregate Store/Reader/Mapper와 복원 패턴 확립 |
| 2b | Auth 저장·행위/매핑 | refresh 회전·폐기·replay·경합 원자성 보존 |
| 3a | Strategy Aggregate 저장/조회 정리 | R03 수정 유지, immutable child와 Mapper/read model 정리 |
| 3b | Backtest Aggregate 저장/복원 정리 | Run/Result 완전한 복원, R02/R04 수정 유지 |
| 3c | R12 feature port 경계 | 직접 domain 참조 제거, 실제 의존성 검사 |
| 4a | TradingAccount/TradingBook | Root/typed ID/Mapper/Store 분리, ownership 보존 |
| 4b-1 | TradingImportSession 및 소속 Artifact | Root·typed ID·Mapper·Aggregate 저장, 업로드·삭제·retention CAS 보존 |
| 4b-2 | Session 소속 NormalizationRun | 소속 모델·Mapper·입력 pin·재시도·원자적 admission 보존 |
| 4c | LedgerRevision 및 publication | immutable 발행 모델, membership/hash/generation gate, bounded 저장 |
| 4d-1 | DeletionRequest 모델·저장 경계 | Root/typed ID·scope/checklist 정의·순수 Mapper·Aggregate 단위 요청 저장/복원 |
| 4d-2 | DeletionRequest 상태 전이·진행 증명 | worker 전이 CAS·component별 durable checkpoint·정상 완료·실패·복구 연결 |
| 5a | Dispatch/polling/result/ACK 진입점 | inbound use case, port의 기술 구현 이동, ACK commit 순서 보존 |
| 5b-1 | 남은 retention 실행 경계 | application orchestration, reader/copy drain, lease CAS·backoff와 orphan 보호 |
| 5b-2 | Raw 정책 시계와 non-terminal 만료 | accepted/rejected 보관 시각, 만료 시 runtime 종료·늦은 발행 차단, 정책 기간 불연장 |
| 5c-1 | 실패/조회/web 경계 | 의미 기반 실패, web DTO, checkpoint 기반 purge read model |
| 5c-2 | 삭제 영향 범위 count | 요청 시점 count 저장·replay·조회, 삭제 경합과 legacy 복원 검증 |
| 6a | 기존 모듈 schema migration | 새 DB·기존 DB 업그레이드, 데이터 보존, validate 기동 |
| 6b | 운영 설정 | health/node ID 등 설정 검증, 개발·운영 동작 확인 |
| 6c | 전체 경계/회귀/integration | 미해결 목록 점검, PostgreSQL/E2E·계약 검증 결과 정리 |
| 6c-1 | 전체 검증 누락 보강 | Gradle 46개 모듈 coverage·classpath, Asset/공통/Mapper source 경계, Core 자체 PostgreSQL CI |
| 6c-2 | Auth inbound 진입점 | R19 제거, concrete adapter 결합과 outbound 호출을 HTTP Controller에서 분리 |
| 6c-3 | Trading Review port 구성 | R20 정리 후 전체 검증 결과와 잔여 제약 재확인 |

새롭게 확인한 긴급 동작 문제는 근거와 의존성을 기록해 우선 처리한다. 구조 작업 때문에 필요한
schema 변경은 해당 단계에 포함하며, 6a까지 미뤄서 중간 commit의 기동을 깨뜨리지 않는다.
각 구조 단계에서 해당 모듈의 경계 검사를 함께 추가한다. 6c는 전체 누락 검증이다.

## 4. 검증과 리뷰 기준

- 동작 수정은 수정 전 실패와 수정 후 성공을 확인하는 회귀 테스트를 포함한다.
- Aggregate 테스트는 불법 생성·복원·전이, 소속 Entity 불변식과 immutable pin을 검증한다.
- Mapper 테스트는 전체 Aggregate round-trip과 nullable 상태, child 관계/order, version 보존을 확인한다.
  구현 대입문을 그대로 반복하는 테스트는 만들지 않는다.
- PostgreSQL locking/CAS/constraint/migration은 실제 분리된 테스트 DB에서 검증한다.
  H2나 mock 결과를 PostgreSQL 검증 완료로 기록하지 않는다.
- 테스트의 UP-TO-DATE/cache, skip과 실제 실행을 PR에 구분한다. 필요한 DB 환경이 없으면
  미검증 시나리오를 명시하고 후속 검증 없이 해당 완료 조건을 충족했다고 표시하지 않는다.
- Core↔Compute 변경에는 AGENTS.md §7의 계약 검사와 integration smoke를 적용한다.
  실행 방법·환경·증적 위치는 integration 정본을 참조한다.
- 새 snapshot은 context에서 동기화하고 byte-level 검사한다. 고정 vector의 기대값을 Core에서 수정하지 않는다.
- API/schema/hash/version 의미가 바뀌지 않는 리팩토링도 기존 wire와 저장 데이터 호환성을 확인한다.
- 각 PR에 관련 ADR/Use Case, 변경 이유, 검증 결과, Determinism 영향과 material limitation을 기록한다.
- GitHub PR 머지는 사람이 수행한다. 선행 PR 미병합 상태에서 다음 작업을 이어갈 경우
  stacked PR의 base/dependency를 명시하고, 머지된 변경을 중복 구현하지 않는다.

## 5. 진행 기록

| 단계 | 상태 | 커밋/PR 및 검증 |
|---|---|---|
| 0 | 완료·머지 | [PR #82](https://github.com/refinvest-dev/core-api/pull/82), 규칙과 실행 계획 |
| 1a | 완료·머지 | [PR #83](https://github.com/refinvest-dev/core-api/pull/83), R01 발급/사용의 상위 상태·소유권 검증 |
| 1b | 완료·머지 | [PR #84](https://github.com/refinvest-dev/core-api/pull/84), 월간 사용량과 실행 ID별 활성 예약 분리 |
| 1c | 완료·머지 | [PR #85](https://github.com/refinvest-dev/core-api/pull/85), R04 polling 응답 반영 전 lease 잠금·검증 |
| 1d | 완료·머지 | [PR #86](https://github.com/refinvest-dev/core-api/pull/86), R03 Strategy 잠금과 immutable version append-only 저장 |
| 1e | 완료·머지 | [PR #87](https://github.com/refinvest-dev/core-api/pull/87), R05 생성 시각·숫자 ID 기준 version 정렬과 최신 선택 |
| 1f | 완료·머지 | [PR #88](https://github.com/refinvest-dev/core-api/pull/88), R06 Backtest/Asset HTTP timeout과 retry·lease 정합 |
| 2a | 완료·머지 | [PR #89](https://github.com/refinvest-dev/core-api/pull/89), Member/Subscription Mapper와 구독 command 잠금 |
| 2b | 완료·머지 | [PR #90](https://github.com/refinvest-dev/core-api/pull/90), Auth Aggregate·Mapper와 refresh family 경합 수정 |
| 3a | 완료·머지 | [PR #91](https://github.com/refinvest-dev/core-api/pull/91), `a824c26`·`270146d`, Strategy 복원·불변성과 저장/조회 Mapper 정리 |
| 3b | 완료·머지 | [PR #92](https://github.com/refinvest-dev/core-api/pull/92), `7483529`·`c5d363b`, Backtest Run/Result 복원·불변성과 저장/조회 Mapper 정리, R02/R04 회귀 검증 |
| 3c | 완료·머지 | [PR #93](https://github.com/refinvest-dev/core-api/pull/93), `ad3af75`·`36db90d`, R12 feature port 계약과 실제 classpath에서 타 feature domain 참조 차단 |
| 4a | 완료·머지 | [PR #94](https://github.com/refinvest-dev/core-api/pull/94), `96a1a6c`·`47d85c5`·`12aba18`, Account/Book Root·Mapper·Store/Reader와 삭제 cascade fence |
| 4b-1 | 완료·머지 | [PR #95](https://github.com/refinvest-dev/core-api/pull/95), `5771f96`·`5f66b63`·`f5ac6df`, Session Root·Artifact 슬롯 저장·순수 Mapper·retention CAS와 삭제 replay·grant retry 이력 보강 |
| 4b-2 | 완료·머지 | [PR #97](https://github.com/refinvest-dev/core-api/pull/97), `ce42b14`·`e4e4b38`, Session 소속 NormalizationRun·순수 Mapper·명시적 admission과 재시도 입력 고정 |
| 4c | 완료·머지 | [PR #98](https://github.com/refinvest-dev/core-api/pull/98), `d6df0c0`·`fd03cf3`, immutable LedgerRevision·순수 Mapper·전용 publication capability와 bounded 저장 |
| 4d-1 | 완료·머지 | [PR #99](https://github.com/refinvest-dev/core-api/pull/99), `e02feae`·`7107bf6`, DeletionRequest Root·typed scope/checklist 정의·Mapper·Aggregate 요청 저장/복원 |
| 4d-2 | 완료·머지 | [PR #100](https://github.com/refinvest-dev/core-api/pull/100), application 실행 조율·lease·실제 부재 확인·복구와 ORCH-024~028 검증 |
| 5a | 완료·머지 | [PR #101](https://github.com/refinvest-dev/core-api/pull/101), `eb6ec6a`·`0ca702a`, 정규화 inbound port·독립 ACK 실행과 commit/rollback·재전송 검증 |
| 5b-1 | 완료·머지 | [PR #102](https://github.com/refinvest-dev/core-api/pull/102), `a53a1c4`, 원본 purge·orphan 실행 조율, reader drain·lease·backoff와 V15 |
| 5b-2 | 완료·머지 | [PR #103](https://github.com/refinvest-dev/core-api/pull/103), `260d620`, R17 정책 시계·non-terminal 만료와 runtime 취소·늦은 publication 처리 |
| 5c-1 | 완료·머지 | [PR #104](https://github.com/refinvest-dev/core-api/pull/104), `33c2171`, 실패 의미·web DTO·전용 삭제 Reader와 checkpoint 기반 public component 상태 |
| 5c-2 | 완료·머지 | [PR #105](https://github.com/refinvest-dev/core-api/pull/105), `0d589cd`, R18 요청 시점 일곱 count·provenance 저장과 V17, 실제 purge/replay E2E 통과 |
| 6a | 완료·머지 | [Core #106](https://github.com/refinvest-dev/core-api/pull/106)과 [Integration #49](https://github.com/refinvest-dev/integration/pull/49) main 반영, V18~V21·Boot Flyway 자동 기동·Hibernate validate, 당시 app 104개·실제 JAR·Linux 전체 fixture/API/Web CI 통과 |
| 6b | 완료·머지 | [Core #107](https://github.com/refinvest-dev/core-api/pull/107) 및 [Integration #50](https://github.com/refinvest-dev/integration/pull/50) 반영; #50도 #49를 통해 main에 포함, 당시 app 109개·공통 ID 4개·실제 JAR·Linux 전체 fixture/API/Web 통과 |
| 6c-1 | 완료·머지 | [Core #108](https://github.com/refinvest-dev/core-api/pull/108), 전체 46개 모듈 경계·로컬/Linux 499개 테스트(실제 PG 144개)·report gate, [재점검](ARCHITECTURE_AUDIT.md)에서 R19/R20 후속 범위 식별 |
| 6c-2 | 완료·머지 | [Core #109](https://github.com/refinvest-dev/core-api/pull/109), raw credential inbound 처리와 transaction 경계, HTTP 구성요소 web 소유·adapter 직접 의존 제거, 전체 Core 510개·실제 PG 144개 및 API/Web 후보 CI 통과 |
| 6c-3 | 완료·머지 | [Core #110](https://github.com/refinvest-dev/core-api/pull/110), 기능별 inbound 16개·Entity 없는 결과·capability 분리, Core 전체 514개·실제 PG 144개 및 계약·API/Web·전체 Trading 후보 CI 통과 |

마지막 코드 반영은 Core `9f9046d`, Integration `801241f`다. 후보 조합의 과거 검증 기록은 아래에
보존하며, 머지된 다섯 저장소 main 조합의 고정 ref·최종 CI와 운영 인계는
[완료·인계 기록](REFACTORING_HANDOFF.md)을 따른다. 코드 리팩토링 완료를 운영 DB 전환·배포 완료로 해석하지 않는다.

### 5c-2 삭제 영향 범위 count snapshot

- 구현은 [Core #105](https://github.com/refinvest-dev/core-api/pull/105), 계약은
  [Context #45](https://github.com/refinvest-dev/context/pull/45), Web snapshot은
  [Web #47](https://github.com/refinvest-dev/web/pull/47), 실행 검증은
  [Integration #48](https://github.com/refinvest-dev/integration/pull/48)로 분리한다.
  Context → Core/Web → Integration 순서로 반영한다.
- `DeletionScopeInventory`가 기존 부모 command 잠금과 동일 transaction에서 owner/scope를 한 SQL snapshot으로
  집계한다. 요청·tombstone·count·idempotency 결과는 함께 commit/rollback된다. 대량 row를 메모리에 적재하지 않는다.
- DeletionRequest의 값 객체·Store·순수 Mapper·Reader·web DTO가 일곱 count와 provenance를 보존한다.
  GET·purge 후 replay·remediation에서 다시 집계하지 않는다. 현재 저장 모델이 없는 Analysis/Reprocessing은
  0이며 NormalizationRun을 analysisRuns로 세지 않는다. Count는 purge 완료 증거가 아니다.
- [Context #45](https://github.com/refinvest-dev/context/pull/45)는 선택형 snapshotStatus를 정의한다.
  누락/LEGACY_PARTIAL은 과거 부분 집계이며 0을 정확한 부재로 해석하지 않는다. V17은 기존 count·state·
  version·attempt를 변경하지 않고 legacy로 보존한다. 현재 live row에서 과거 count를 복원하지 않는다.
  배포·rollback은 [Count Snapshot Rollout](DELETION_COUNT_SNAPSHOT_ROLLOUT.md)을 따른다.
- 수정 전 PostgreSQL에서 Book 하위 Session/artifact count가 0인 회귀를 재현했다. 수정 후 Trading Review
  178개와 app 99개, 총 277개를 실제 실행·필요 범위 재검증으로 통과했다. 실제 PostgreSQL 70개에는 scope/owner
  격리·빈 Account·실제 publication Revision·purge 후 replay·rollback 후 재집계·legacy migration·nonzero count
  remediation과 실제 부모 잠금에 대기한 생성 요청의 거절을 포함한다. 최종 실패·오류·skip은 0개다.
- 새 DB Flyway V1~V17·모듈 경계·실행 JAR와 Context/Core/Compute/Web snapshot·revision provenance·문서 26개,
  선택 필드의 세 호환 사례·두 거절 사례를 검증했다. 중간 constructor 중복과 경합 fixture Member 중복 seed를
  수정했고 Windows 소켓 오류가 난 범위를 재검증했다. 실패한 실행을 성공으로 세지 않는다.
- 실제 E2E는 Core `0d589cd`·Integration `b9471b5`에서 종료 코드 0으로 통과했다. Book의 Session 1·artifact 2·
  Revision 1과 나머지 Account의 Book 2·Session 2·artifact 4를 고정하고 실제 purge·primary 부재·동일 DELETE
  replay를 확인했다. Session/raw-only count·정책 시계·ORCH-024~028·migration/replay 회귀도 통과했다.
- 초기 psql 10014·canary DB 연결 실패·HTTP ConnectError 실행은 성공으로 세지 않았다. Revision이 있는
  scope에서 발견한 E2E analytics 저장소 설정 누락은 Integration에서 보완했다. 최종 실행의 worker 소켓 오류
  복구 2회는 원 로그와 함께 로컬 증적에 남겼다. 업무 상태·lease·삭제 증거를 강제로 변경하지 않았다.
- 후보 커밋 조합의 [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41405900708)를 통과했다.
  계산 입력·hash·version pin·generation·lease와 결정론은 바꾸지 않았다.

### 6a schema migration과 validate 기동

- V18 Subscription·V19 SocialIdentity/RefreshSession·V20 Strategy/Version/Condition·V21 BacktestRun/Result/Dispatch를
  feature persistence 모듈에 추가했다. Member는 V1, quota/capacity는 V11, refresh family fence는 V12를 유지한다.
- 기존 row·index·constraint·identity sequence·불변 version/result·OID payload·quota/예약·dispatch lease를
  다시 만들지 않는다. 새 DB와 기존 DB upgrade를 별도 경로로 검증한 뒤 기본 `ddl-auto`를 `validate`로 바꿨다.
- 실제 JAR 검증에서 Boot 4 Flyway 자동 설정 모듈 누락을 발견했다. Starter를 연결하고 Boot 자동 migration
  뒤 전체 JPA 매핑 검증을 수행하는 회귀 테스트를 추가했다. 수동 Flyway 테스트만으로 기동 성공을 추정하지 않는다.
- app 전체 104개(실제 PostgreSQL 28개)와 모듈 경계·실행 JAR가 통과했다. PostgreSQL에서는 새 DB·V17 + Hibernate
  schema·Flyway 도입 전 baseline 0·잘못된 schema 거절·Boot 자동 기동의 다섯 경로를 검사했다. 한글 OID payload와
  생성 sequence, 활성 Run만의 예약 복원도 확인했다. 초기 Windows listen 실패 실행은 성공으로 세지 않는다.
- 실제 JAR의 빈 격리 DB 기동·V1~V21 적용·전략/버전 HTTP 생성·재기동 후 동일 조회·기존 버전 보존과 새 버전
  추가가 통과했다. 배포와 rollback은 [Schema Migration Rollout](SCHEMA_MIGRATION_ROLLOUT.md)을 따른다.
- [Integration #49](https://github.com/refinvest-dev/integration/pull/49)는 수동 fixture에서 모든 feature SQL을
  적용하고 해당 경로의 Flyway 중복 실행만 끈다. Core 기본 validate는 유지하고 일반 API/Web smoke는 자동
  Flyway 기동을 검사한다. [최종 후보 CI](https://github.com/refinvest-dev/integration/actions/runs/41422406898)는
  Core `d5c9b02`·Integration `17e459f`의 API/Web과 전체 Trading fixture를 모두 통과했다. Fixture runtime은
  473.776초이며 ORCH-024~028·raw 정책 시계·Book/Account count·canary·migration replay가 통과했다.
  Worker 복구와 실패한 process-control exit는 0개다.
- Windows의 listen/DB/HTTP 연결 오류 실행과 한 실행의 통제된 중단은 실패 증적으로 보존했다. Linux에서
  발견한 Windows 전용 프로세스 종료 호출과 재사용 workflow를 가정하지 않은 검사도 보완했다. Migration
  탐색 2개·process-control 4개·계약 전파 32개·발행 9개와 프로세스 식별 23 assertion을 관련 범위에서 검증했다.
  최종 증적과 credential은 로컬/CI artifact에만 두고 커밋하지 않는다. Core → Integration 순서로 반영한다.

### 6b health와 명시적 node 설정

- 공개 health는 `show-details: never`, `show-components: never`로 DB·디스크·예외 진단을 숨긴다.
  표준 probe group 이름과 aggregate UP/200·DOWN/503 신호는 유지한다. 익명·access cookie·개발 scrape
  credential 요청과 장애 contributor를 검증하고 기존 Prometheus 인가·loopback·API/CSRF 경계를 유지했다.
- Snowflake node ID의 전역 기본값 0을 제거했다. 누락·빈 값·정수가 아닌 값·0~1023 밖의 값은 기동을
  실패시킨다. 실제 실행 YAML을 읽는 회귀 테스트로 수정 전 누락 설정의 잘못된 기동을 재현했다.
  동일 millisecond에 node 0과 1023이 생성한 512개 ID의 비중복을 검증했다.
- app 109개(실제 PostgreSQL 28개 포함)·공통 ID 생성기 4개와 모듈 경계가 통과했다. Windows 소켓 오류의
  실패 실행은 보존하고 영향받은 HTTP/health 테스트만 다시 실행해 최종 실패·오류·skip 0개를 확인했다.
- 실제 JAR로 빈 격리 schema의 V1~V21 자동 migration·비공개 health·Strategy/version HTTP 생성을 검증했다.
  같은 DB에서 node 0→1 재기동 후 기존 version 보존·새 version 추가와 생성 ID의 node bit를 확인했고,
  node 설정을 제거한 별도 JAR의 비정상 종료도 확인했다. 전용 프로세스는 종료했다.
- Integration은 primary 0을 명시하고 secondary 1·canary 2를 유지한다. 실제 Spring JSON override를 포함한
  node ID를 프로세스 identity에 반영해 변경된 설정의 프로세스 재사용을 거절한다. 관련 30 assertion이 통과했다.
- 후보 [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41471542806)와
  [API/Web·전체 Trading CI](https://github.com/refinvest-dev/integration/actions/runs/41471547547)를 통과했다.
  검증 대상은 Core `7189137`·Integration `b19fcf9`이며 이후 변경은 문서뿐이다. Fixture runtime은 475.712초다.
  ORCH-024~028·두 Core node 0/1·별도 canary·raw 정책 시계 네 경로·Book/Account count·migration replay가
  통과했다. Worker 복구는 없고 process-control 13개 종료 코드는 모두 0이다. 기존 Compute 0017 단일 transaction
  진단의 SQLSTATE `55006` rollback과 실제 `psql -f` migration/replay 통과는 구분한다.
- 운영자는 동시에 쓰는 writer마다 다른 node ID를 배정해야 한다. JVM 설정 검증은 분산 중복 할당을 탐지하지
  않으며 registry/allocator를 추가하지 않았다. [운영 설정 절차](OPERATIONS_CONFIGURATION.md)를 따른다.
  ID 형식·기존 ID·업무 API/schema·계산 hash와 결정론은 바꾸지 않았다. Credential·fixture·실행 증적은 커밋하지 않는다.
- [Core #107](https://github.com/refinvest-dev/core-api/pull/107)과
  [Integration #50](https://github.com/refinvest-dev/integration/pull/50)은 Ready PR이다. Integration #50은 열린
  #49의 후속 브랜치로 분리했다. #49 → Core #107 → Integration #50 순서로 반영하며 실제 머지는 사람이 수행한다.

### 6c 전체 점검과 후속 순서

전체 feature의 Aggregate·Store/Reader·Mapper·application/adapter 의존 경계를 다시 점검했다.
[재점검 기록](ARCHITECTURE_AUDIT.md)에 46개 모듈·550개 운영 Kotlin 파일, Root 11개·Mapper 13개와
검증 누락 및 잔여 항목을 정리한다. 6c-1에서 R16 검증 누락을 보강하고, 새로 확인한 R19 Auth HTTP
진입점은 6c-2, R20 Trading Review 계약 구성은 6c-3으로 분리한다. R19/R20을 남긴 채 리팩토링 전체를
완료로 표시하지 않는다. 실제 DB·CI 실행 결과와 운영상 제약은 해당 PR의 검증 범위와 구분해 기록한다.

6c-1의 기존 검사 누락은 임시 init script로 금지된 Asset→Strategy application 의존성을 추가했을 때
성공하는 것으로 재현했다. 수정 후 같은 의존성과 미등록 모듈 추가를 거절한다. 로컬 전체 테스트의
소켓 오류 두 범위는 별도 재실행해 최종 499개(실제 PostgreSQL 144개)·실패/오류/skip 0개를 확인했다.
[Linux CI](https://github.com/refinvest-dev/core-api/actions/runs/41482472152)도 같은 499개와 report gate를
통과했다. 최초 CI의 DB 이름 안전 조건 불일치는 전용 컨테이너 설정을 맞춰 해결했고 테스트 조건은
완화하지 않았다. 최신 head 검사는 PR에서 확인한다.

### 6c-2 Auth inbound 진입점과 HTTP 소유권

- [Core #109](https://github.com/refinvest-dev/core-api/pull/109)는 Refresh/Logout Command로 raw credential을
  받고 application이 token parser port를 호출하도록 바꾼다. 진단 문자열에는 credential을 넣지 않는다.
  검증 실패·누락은 DB transaction 전에 종료한다. 검증된 회전/폐기는 기존 REQUIRED transaction에서
  실행해 family fence·role 정본·replay·현재 시각과 commit/rollback 의미를 유지한다.
- Cookie 작성·OAuth callback/returnTo·HTTP 오류/필터·metrics filter 구성을 web adapter로 이동했다.
  Controller의 업무 호출은 inbound port이며 같은 web adapter의 쿠키 보조 객체는 전송만 담당한다.
  JWT 서명·검증·token port는 security adapter에 남긴다. HTTP는 Spring의 access 전용 `JwtDecoder`
  인터페이스를 주입받는다. 일반 decoder는 refresh를 검증하며 두 adapter 사이 Gradle 의존성은 없다.
- 설정 prefix/키·기본값과 JWT claim·cookie path/속성·CORS·CSRF·공개 endpoint를 유지한다.
  새 source 검사로 Controller/outbound·adapter 상호 참조와 security 내부 HTTP 구현을 금지한다.
- Auth application 단위 23개와 web cookie/provider 검증 5개를 통과했다. 실제 HTTP에는 누락·서명·
  issuer/audience/만료/type 오류의 family 불변, CSRF 없는 refresh/logout 거절과 잘못된 credential의
  logout 쿠키/세션 정리를 추가했다. 로컬 Windows listen 오류와 새 쿠키 테스트의 잘못된 기본값 가정은
  구분하며, 쿠키 테스트는 Secure=true·SameSite=Lax를 명시해 검증한다. 최신 전체 Core/PostgreSQL
  CI와 실제 API/Web smoke 결과·후보 ref는 PR에 기록한다.
- 업무 API/schema/hash와 계산 결정론 영향은 없다. #109는 머지됐으며 R20/6c-3을 아래 PR로 이어간다.

### 6c-3 Trading Review 기능별 계약과 결과 모델

- [Core #110](https://github.com/refinvest-dev/core-api/pull/110)은 혼합 파일의 capability·모델 선언 44개를
  기능별 package와 독립 파일로 옮긴다. 첫 커밋에서 선언 내용의 보존과 전체 호출부 컴파일을 확인했다.
  다음 커밋은 업무 인터페이스 4개를 단일 Command/Query 입력을 받는 inbound port 16개로 바꾼다.
- Application은 기존 Aggregate 생성·행위와 Store/Reader를 유지하며 port 결과 모델을 반환한다.
  결과에는 Aggregate·소속 Entity·object key·wrapped DEK·key version을 담지 않는다. Controller는
  업무별 inbound port와 web DTO 변환을 소유하며 원본 정책 시계는 application의 조회 결과에 반영한다.
- 업로드의 metadata callback은 file stream을 소비한 뒤 실행한다. 외부 파일 작업과 짧은 DB transaction,
  idempotency fingerprint·owner 잠금·CAS/generation·publication·삭제 의미는 유지한다. 저장 상태와
  불변식을 검사하는 PostgreSQL 테스트는 결과 DTO 대신 Store에서 Aggregate를 다시 조회한다.
- 로컬 port 2개·application 26개·web 12개·persistence 85개·app 경계 12개, 합계 137개가 통과했다.
  persistence에는 실제 PostgreSQL Trading Review 70개가 포함된다. 실패·오류·skip은 0개다.
  경계 검사에서 정상적인 `port.normalization.compute`를 구현 의존성으로 오인한 실패는 보존하고,
  해당 port만 허용하도록 고친 뒤 경계 검사와 마지막 서비스 빌드를 다시 통과했다.
- 결과 그래프의 Entity 비노출·기능별 단일 CQR 진입점과 raw 만료 시각 직전/정각·artifact role projection을
  검사한다. Context snapshot 26개와 `git diff --check`도 통과했다. `537d25e`의 [Core Linux CI](https://github.com/refinvest-dev/core-api/actions/runs/41605083219)는
  30개 테스트 모듈·514개 테스트와 실행 JAR·결과 gate를 통과했다. 실제 PostgreSQL 10개 suite·144개를
  포함하며 실패·오류·skip은 0개다. 문서 후속 커밋을 포함한 최신 head 검사는 PR에서 확인한다.
- [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41605084847)와 [API/Web 및 전체 Trading CI](https://github.com/refinvest-dev/integration/actions/runs/41605088749)도
  같은 Core `537d25e`에서 통과했다. API smoke·브라우저 11개와 전체 Trading raw-policy/schema fixture를
  실행했다. 고정 ref는 Context `b970e1b`, Compute `d75ff4d`, Web `c2e09d6`, Integration `13006c9`다.
- 통합 artifact에서 raw policy clock·삭제 count snapshot·version pin 시나리오의 PASS, process-control
  13개 종료 코드 0과 worker 자동 복구 없이 통과한 사실을 확인했다. Integration 사전 process-control의
  Windows 전용 1개 skip은 Core 전체 회귀의 skip 0개와 구분한다.
- 업무 HTTP/OpenAPI·schema·Compute payload·계산 hash와 ID 형식 변경은 없으며 계산 결정론 영향은 없다.
  당시 Integration #49 후보 검증과 이후 `801241f`의 main 반영을 구분한다. 기존 Snowflake node 배정·재시작
  high-water mark, Compute 0017 단일 transaction 진단과 schema 운영 절차는 그대로 적용한다.

### 5c-1 실패 의미와 public 조회 경계

- Port의 `TradingFailure`에서 HTTP 숫자를 제거하고 `TradingFailureKind`로 실패 의미를 전달한다.
  Application·capability·composition은 이 의미를 사용하고 web exception handler가 HTTP로 매핑한다.
  기존 400/422·404/410 구분, concurrent modification·dependency failure의 retryable과 재업로드 안내를 유지한다.
- Import failure는 web DTO의 명시적 변환으로 공개한다. code/retryable/userAction/occurredAt 네 필드와 기존
  nullable·terminal 응답 의미를 보존하고 domain failure 객체를 응답 필드로 직접 노출하지 않는다.
- 삭제 GET은 `GetTradingDeletionRequestService`와 `DeletionRequestReader`로 분리한다. Reader는 Store와
  같은 한 statement의 Root/checkpoint snapshot과 순수 Mapper를 공유하며 조회에서 상태를 변경하지 않는다.
  Application command의 생성·replay 응답도 같은 projection factory를 사용한다.
- Public primary는 PRIMARY_DATA+DERIVED_RESULTS, object는 RAW_OBJECTS+RAW_KEYS+TEMPORARY_COPIES,
  runtime은 COMPUTE_RUNTIME의 scope별 필수 항목을 기준으로 한다. 필수 항목이 없을 때만 NOT_APPLICABLE,
  필수 부재 checkpoint가 모두 있을 때만 COMPLETED다. 전체 요청이 FAILED여도 확인된 group은 COMPLETED를
  유지하고 미완료 group은 FAILED다. count 0이나 전체 요청 상태만으로 group 완료·적용 불가를 추정하지 않는다.
- Raw-only backup은 NOT_APPLICABLE과 null due date다. Subtree backup은 별도 부재 증거가 없어 IN_PROGRESS를
  유지하며 기한 도달만으로 완료를 만들지 않는다. V13이 보존한 legacy COMPLETED label에 checkpoint가 없으면
  historical label은 보존하고 확인되지 않은 component는 보수적으로 IN_PROGRESS로 표시한다. 이 조회가
  과거 부재 증거를 만들거나 삭제를 재실행하지 않는다.
- R18은 이번 대조에서 확인한 별도 저장 문제다. 현재 admission-time count는 보존하며, 정확한 scope count를
  고정하는 migration·경합 검증은 5c-2에 분리한다. 5c 전체가 완료됐다는 의미가 아니다.
- 수정 전 raw-only runtime이 NOT_APPLICABLE로 잘못 표시되는 회귀 실패를 확인했다. 수정 후 Trading Review
  171개와 app 99개, 총 270개 테스트의 실제 실행·필요 범위 재검증을 취합해 통과했다. 실제 PostgreSQL은
  65개이며 owner isolation·조회 무변경·partial checkpoint·legacy 상태 복원을 포함한다. 테스트 실패·오류·
  skip은 최종 취합에서 0개다. 새 DB Flyway V1~V16·모듈 경계·실행 JAR와 Context 문서 26개 검사를 통과했다.
- 중간 검증의 새 service transaction proxy 설정·중복 scope fixture·이동한 조회 진입점과 composition의
  호출부를 수정한 뒤 재검증했다. Windows socket listen 오류로 시작하지 못한 application 26개는 별도
  실제 실행으로 확인했다. 로컬 실행기의 bare wildcard filter가 파일 목록으로 확장된 실패는 패키지
  한정 filter로 재검증했으며, 이들 중간 실행을 성공으로 세지 않는다.
- 첫 실제 E2E에서는 raw-only와 Session 삭제의 queued/unknown-create 공개 상태 검사가 통과했지만,
  기존 cutover fixture가 전송 중 ACK 재예약과 경합해 retry를 소진했다. terminal ACK 실패를 확인한 뒤
  해당 시나리오와 소유 서비스만 정리했다. fixture 대기는 실제 실패 전송의 lease 해제 뒤 조건부 UPDATE로
  예약하도록 보완했다. 상태·attempt·lease·retry 예산을 강제로 바꾸지 않고 같은 Core 코드로 재검증했다.
- 최종 실제 Core↔Compute E2E는 raw-only/Session 삭제의 queued·unknown-create 공개 상태 네 경로,
  정책 시계 네 assertion·primary·무결성·publication·ACK 재시도·V1 pin·ORCH-024~028·두 Core cutover
  10개·migration replay가 모두 통과했고 실제 프로세스 종료 코드는 0이다. Windows 10014로 실제 worker를
  한 번 복구했고 원래 로그를 보존했다. 실제 checkpoint·runtime receipt·파일/key 부재와 HTTP component
  상태를 함께 검증했다. V2 실제 HTTP publication은 범위 밖이며 PostgreSQL V2 검증과 구분한다.
- 증적은 integration의 `.integration-data/trading-normalization/757aa2a950884641bfea387f022beee8/evidence.json`이다.
  후보는 Core `33c2171`, Integration `1510b75`, Compute `d75ff4d`, Context `0099527`이다. 최종 실행과 중지한
  첫 실행의 소유 registry 프로세스 0개와 포트 해제를 확인했다. 기존 Compute 0017의 단일 transaction
  진단은 SQLSTATE `55006`으로 rollback됐고 기존 `psql -f` runner의 migration/replay는 통과했다.
  검증 변경은 [Integration PR #47](https://github.com/refinvest-dev/integration/pull/47)로 분리했다.
- 계산 입력·hash·version pin·generation·lease와 결정론은 바꾸지 않았다. 이 단계에는 DB schema와
  OpenAPI snapshot 변경이 없으며 로컬 credential·fixture·증적은 커밋하지 않는다. 다음 단계는 R18/5c-2다.

### 5b-2 Raw 정책 시계와 non-terminal 만료

- Session Root가 새 accepted publication의 `accepted_at + P7D`, 새 rejection의 `rejected_at + PT24H`를
  결정한다. replay·조회·raw-only 삭제는 고정된 시각과 기한을 연장하지 않는다. publication은 artifact까지
  잠근 뒤 읽은 시각으로 raw 가용성과 lease를 검사해 이미 만료되거나 purge가 시작된 원본을 발행하지 않는다.
- `TradingImportExpiryService`는 후보를 bounded 조회하고 Account→Book→Session 잠금 뒤 만료를 다시 검사한다.
  Root의 `PROCESSING_FAILED / SOURCE_ARTIFACT_EXPIRED / REUPLOAD_REQUIRED` 전이, generation 증가,
  `raw_expired_at`과 실제 raw-only `DeletionRequest`를 같은 transaction에 저장한다. replay는 요청이나
  generation 증가를 반복하지 않는다. Scheduler는 inbound port만 호출하며 외부 I/O는 이 transaction 밖이다.
- 기존 삭제 실행의 Run identity fence와 raw-only 네 component 부재 확인을 재사용한다. 생성 응답을 잃어
  job ID가 없는 경우도 실제 runtime 정리를 거친다. Session metadata와 발행된 Revision·record·evidence·
  분석 입력·Book latest는 보존한다. 결과 수신이 expiry worker보다 먼저 만료를 발견하면 발행하지 않고
  durable expiry 전이를 기다리며 이전 generation의 PERSISTED ACK나 가짜 stale 증거를 만들지 않는다.
- V16은 nullable 정책 시각과 후보 인덱스를 추가한다. legacy accepted는 연결된 Revision의 `published_at`,
  rejected는 failure 시각에서 유효한 증거가 있을 때만 복원하고 기존 raw 기한은 변경하지 않는다.
  삭제된 raw를 되살리거나 현재 시각으로 추정하지 않는다. 전환·rollback은
  [Raw Policy Clocks Rollout](TRADING_RAW_POLICY_CLOCKS_ROLLOUT.md)을 따른다.
- 수정 전 실제 PostgreSQL에서 accepted 이후에도 upload 기한이 남는 회귀 실패를 확인했다. 수정 후 실제
  PostgreSQL 64개를 포함한 Trading Review 167개와 app 98개, 총 265개 테스트를 캐시 재사용 없이 실행해
  통과했다. 실패·오류·skip은 없다.
  기한 경계·terminal clock 보존·late publication 차단·expiry/request 동시 rollback·멱등 처리·legacy 기한
  보존을 포함한다. 새 DB Flyway V1~V16·모듈 경계·실행 JAR 빌드도 통과했다. 중간 검증의 fixture ID 타입과
  migration publication 컬럼명 오류를 수정한 뒤 전체 검증을 다시 실행했다.
- 실제 Core↔Compute에서 accepted clock/replay·queued expiry·생성 응답 유실 expiry·accepted raw purge 뒤
  Review 보존, 네 assertion이 모두 통과했다. 단일 실제 요청·runtime absence receipt·파일/키 부재·capacity
  반환·늦은 create 거절과 Session metadata 보존을 확인했다. 기본 publication·무결성·ACK 재시도·V1 pin·
  ORCH-024~028·두 Core cutover assertion 10개·migration replay도 통과했다. V2 실제 HTTP publication은
  이 E2E 범위 밖이며 PostgreSQL V2 검증과 구분한다. Context 문서 26개와 계약 snapshot 검사도 통과했다.
- 증적은 integration의 `.integration-data/trading-normalization/b5f674ede190433da351dd3aa094ba71/evidence.json`이다.
  후보는 Core `260d620`, Integration `359531d`, Compute `d75ff4d`, Context `0099527`이며 약 552초에
  실제 프로세스 종료 코드 0으로 통과했다. worker 복구는 0건이며 기본·두 번째 Core·canary registry 프로세스 0개와
  포트 해제를 확인했다. 로컬 credential·
  fixture·증적은 커밋하지 않는다. Compute 0017의 단일 transaction 진단은 기존 SQLSTATE `55006` 한계로
  rollback됐고 기존 `psql -f` runner의 replay는 통과했다. Core V16의 실제 새 DB migration 통과와 구분한다.
- 중간 E2E의 Windows 10014 기동 오류·호스트 실행 공백 뒤 시간 초과·새 조회 도우미 인자 오류를 통과로
  세지 않았다. 별도 재실행에서는 소켓 복구로 지연되는 동안 합성 ACK의 20분 대기가 소진됐다. 선택형 TTL
  E2E에만 충분한 ACK 대기를 적용하고 기존 단계에서 즉시 해제하도록 수정한 뒤 모든 assertion을 재검증했다.
  기본 runner의 20분 대기와 제품 retry 예산은 유지한다. PowerShell stderr 리다이렉션이 native 종료 0에도
  외부 실행 도구에 1을 보고하는 현상은 독립 재현했고, 최종 실행의 프로세스 종료 코드를 직접 수집해 0을 확인했다.
  검증 runner는 [Integration PR #46](https://github.com/refinvest-dev/integration/pull/46)로 분리했다.
  API·hash·version pin·계산 입력 의미와 결정론은 바꾸지 않았다. 다음 단계는 5c의 실패 의미·조회
  projection·web DTO 정리다.

### 5b-1 원본 purge와 orphan 실행 경계

- `TradingRawRetentionService`와 `TradingRawOrphanCleanupService`가 기능별 inbound port를 구현한다.
  Scheduler의 SQL·파일 삭제·Reader 호출과 업무 절차를 제거했다. 기존 5초/1분 실행 주기, 삭제 batch 20개,
  orphan grace 기본 3600초와 최소 600초 미만 실행 생략은 유지한다. 파일 I/O는 DB transaction 밖이다.
- 원본은 RawSlot·LegacyArtifact를 fence하고 reader·임시 사본을 drain한 뒤 object 부재를 확인한다.
  이 확인 뒤에만 DB의 filename·locator·wrapped DEK·key version을 제거한다. 대기는 오류로 세지 않고
  5초 뒤 재확인하며 실제 오류는 5/10/20/40초 backoff 후 다섯 번째에 `DELETION_FAILED`로 남긴다.
- claim은 artifact snapshot·version·lease·오류 횟수를 묶는다. 결과 반영은 row lock을 획득한 뒤 DB 시각으로
  만료를 확인한다. 수정 전 실제 PostgreSQL에서 만료 뒤 재claim 전의 늦은 성공 응답이 상태·키를 바꾸는
  회귀 실패를 재현했다. 수정 후 만료·교체·잠금 대기 중 만료된 응답은 DB의 상태·키를 바꾸지 않는다.
- V15는 다음 시도 시각을 lease와 분리하고 처리 대상 인덱스를 추가한다. 기존 descriptor·expiry·claim·
  attempt count·terminal 상태를 변경하지 않는다. `overdue` gauge는 만료된 pending/failed도 포함하고,
  failure counter는 현재 claim에 반영된 실제 오류만 센다. 전환·rollback은
  [Raw Retention Rollout](TRADING_RAW_RETENTION_ROLLOUT.md)을 따른다. 이전 TTL writer와의 혼합 실행은 보장하지 않는다.
- Trading Review 162개와 app 98개, 총 260개 테스트의 실제 실행·재검증 결과를 취합해 통과했다.
  실제 PostgreSQL 61개에는 현재/legacy reader drain·재시작·사본 부재·backoff/소진·stale claim·잠금 대기·
  migration 보존·참조된 object와 활성 업로드의 orphan 보호를 포함한다. 원본 purge가 기존 Revision·record·
  evidence·V2 분석 입력·Book·ACK와 unrelated role을 바꾸지 않는 것도 검증했다. 새 DB Flyway V1~V15,
  Gradle/소스 경계와 실행 JAR 빌드를 통과했다. Windows 소켓 오류와 새 legacy fixture의 잘못된 key로
  실패한 중간 실행은 성공으로 세지 않았으며, fixture를 실제 opaque key 형식으로 수정한 뒤 전체 검증과
  오류 대상 재실행을 수행했다. 로컬 credential·fixture·증적은 커밋하지 않는다.
- 계약 snapshot·Context 문서 26개와 V1/V2 fixed vector 검사를 통과했다. V15를 적용한 새 JAR의 실제
  Core↔Compute E2E는 `primary`·`result_integrity`·`ledger_publication` 모두 PASS이고 ACK는
  `ACKNOWLEDGED_AFTER_RETRY`다. ACK 전송 때 별도 DB 연결의 publication commit 가시성, V1 publication·
  ORCH-024~028·두 Core cutover assertion 10개·migration replay가 통과했다. queued/unknown-create 삭제
  회귀도 각각 실제 부재·체크포인트 6개·늦은 create 거절을 확인했다. V2의 실제 HTTP publication은 이
  E2E 범위 밖이며 앞의 PostgreSQL V2 검증과 구분한다.
- 중간 E2E 세 건은 Windows 10014 DB 연결 오류, 기존 15분 결과 수신 lease보다 짧은 4분 관찰 한도,
  canary Compute의 소켓 기동 오류로 실패했다. 성공으로 세지 않았으며 같은 코드에서 관찰 한도를
  20분으로 조정했다. claim 강제 해제·업무 상태 수동 수정·assertion 완화 없이 자연 lease 만료와
  reclaim·재개를 확인한 최종 실행은 종료 코드 0이고 worker 복구 이벤트는 0건이다. 증적은 integration의
  `.integration-data/trading-normalization/7ed4867478c442cbabd0c4736400aae8/evidence.json`이다. 후보는 Core
  `a53a1c4`, Compute `d75ff4d`, Context `0099527`, Integration `0ae4d0a`이며 이후 Core 변경은 문서뿐이다.
  기본·두 번째 Core·canary registry의 프로세스 0개와 검증용 포트 해제를 확인했다.
- 이번 단계는 저장된 `raw_expires_at`을 기준으로 실행한다. 코드 대조에서 R17을 확인해 기존 5b를
  두 PR 단위로 나눴다. accepted/rejected 전이의 정책 시계와 non-terminal TTL의 취소·late publication
  연결은 5b-2에 남아 있으며, 이 결과를 전체 TTL 정책의 완료로 보지 않는다. API·hash·계산 입력 의미와
  결정론은 바꾸지 않았다.

### 5a 진입점과 ACK 실행 분리

- dispatch·polling·결과 수신·ACK·staging 정리를 기능별 inbound port로 정의했다. Scheduler는
  이 계약만 주입받고 회차당 20개 처리 상한과 기존 실행 주기를 유지한다. Application은 기존
  lease·입력 identity·generation·result version pin과 publication 검증을 유지한다.
- compute adapter의 application 의존성과 소스 경계 검사의 두 구현체 예외를 제거했다. 수정 전
  새 Gradle 검사에서 기존 직접 의존을 거절하는 실패를 확인했고, 수정 후 compile/test classpath의
  전이 의존에도 application 구현체가 없는지 검사한다. 기술 구현 금지 검사는 port 전체로 확대했다.
  파일·암호화 구현 분리는 선행 #100의 내용을 유지하며 HTTP client는 compute adapter에 둔다.
- `TradingNormalizationAcknowledgementService`는 commit된 ACK outbox의 claim→transaction 밖 전송→
  결과 CAS만 담당한다. 결과 검증과 Revision 발행을 반복하지 않으며 저장된 재시도 정책·payload
  만료·lease·멱등 키를 보존한다. 결과 수신 서비스는 발행과 ACK outbox를 같은 transaction에 저장한다.
- 실제 PostgreSQL 테스트는 발행 row와 ACK outbox를 쓴 뒤 commit 전에 실패시켜 둘 다 rollback되고
  staging은 남는지 확인한다. 복구 후 한 번만 발행하며, ACK 전송 때 활성 DB transaction이 없고 별도
  물리 연결에서도 발행·Session ACCEPTED·ACK identity가 보이는지 검증했다. Compute가 ACK를 받은 뒤
  Core가 결과를 기록하지 못한 경우도 lease 만료 후 같은 ACK를 재전송한다. V1/V2 publication을 포함한다.
- Trading Review 155개와 app 97개, 총 252개 테스트의 실제 실행·재검증 결과를 취합해 통과했다.
  실제 PostgreSQL 54개를 포함하며 최종 실패·오류·skip은 0개다. 최초 전체 실행과 일부 재실행의
  Windows `Bad address: listen/connect`·10014 오류는 통과로 세지 않았다. 소스 변경 없이 해당 모듈과
  남은 실패 테스트를 재실행했고 app 97개는 전체 재실행에서 통과했다. Gradle 모듈 경계 검사와
  실행 JAR 빌드도 통과했다. 로컬 실행 보고서·credential·fixture·증적은 커밋하지 않는다.
- 수정 전후 계약 snapshot과 Context 문서 26개·V1/V2 fixed vector 검사를 통과했다. 새 JAR의 실제
  Core↔Compute E2E는 `primary`·`result_integrity`·`ledger_publication` 모두 PASS이며 ACK는
  `ACKNOWLEDGED_AFTER_RETRY`다. ACK 요청 시 별도 DB 연결에서 publication commit 가시성을 확인했다.
  V1 publication·ORCH-024~028과 두 Core의 cutover assertion 10개, migration 후 replay를 통과했다.
  잘못된 descriptor/payload의 Revision·PERSISTED ACK는 0건이고 기존 latest를 유지했다. queued Job과
  unknown-create 삭제 회귀도 각각 component 체크포인트 6개·실제 부재·늦은 create 거절을 확인했다.
  실제 HTTP의 V2 publication은 이번 E2E 범위에 포함하지 않으며 PostgreSQL의 V2 publication 검증과 구분한다.
- 첫 E2E는 서비스 기동 전 Windows 10014 DB 연결 오류로 중단됐고 성공으로 세지 않았다. 새 격리
  실행은 종료 코드 0이며 실행 중 worker 소켓 오류 한 건의 harness 복구를 별도로 기록했다. 최종
  증적은 integration의 `.integration-data/trading-normalization/655a38bdd73f4122b12d7f88812f0977/evidence.json`이다.
  후보는 Core `0ca702a`, Compute `d75ff4d`, Context `0099527`, Integration `0ae4d0a`이며 이후 Core 변경은
  이 문서뿐이다. 기본·두 번째 Core·canary registry의 프로세스 0개와 검증용 포트 해제를 확인했다.
- API·DB schema·migration·retry 정책·version pin·hash와 계산 입력 의미는 바꾸지 않았으며 결정론에
  영향이 없다. 다음 5b는 원본 TTL 만료와 orphan 정리의 application 조율·짧은 transaction·실제 부재
  확인을 다룬다. #100에서 선반영한 삭제 실행과 복구를 남은 retention 전체의 완료로 보지 않는다.

### 4d-2 범위와 후속 경계

- `DeletionRequest`의 상태 전이와 component 증거를 CAS로 저장한다. 과거 `COMPLETED`의 빈 증거는
  보존하지만 새 완료에는 모든 필수 component의 실제 부재 확인이 필요하다. terminal 요청을 되돌리지 않는다.
- 정상 삭제의 완료를 위해 5b의 필수 외부 실행을 먼저 연결했다. Scheduler는 inbound port를 호출하고
  application이 5분 lease·최대 10개 작업·transaction 밖 HTTP/파일 I/O·짧은 결과 반영을 조율한다.
  owner/scope/generation/version과 현재 lease를 잠근 뒤 검사하며 stale 응답은 진행·실패·키를 바꾸지 않는다.
- V13 workset은 primary purge 전 opaque locator와 관계를 보존한다. V14는 실행 정책·다음 시도·lease,
  Run의 Session/source identity, artifact role, 사본 task와 remediation 관계를 추가한다. Mapper는 Root와
  checkpoint를 순수 복원한다. 기존 terminal 기록이나 불명확한 legacy 범위에서 부재를 추정하지 않는다.
- Compute는 Core Run 단위로 새 생성을 먼저 차단하고 실제 자원 부재를 확인한다. job ID가 없는 생성
  응답 유실도 처리하며 source generation과 삭제 scope generation을 비교하거나 바꿔 쓰지 않는다.
  child 종료뿐 아니라 부모의 결과 payload와 실패 traceback 해제도 확인한다.
- raw upload/read, Run spool과 Revision 분석 입력을 scope별로 추적한다. 삭제 표식 뒤 새 쓰기를 막고
  활성 handle을 drain한 뒤 사본을 정리한다. 파일·암호화 구현은 adapter로 옮겼다. raw-only는 retained
  ledger/evidence/분석 입력을 유지한다. 기존 stale ACK와 unrelated latest pointer도 보존한다.
- `PENDING`은 오류 횟수 0으로 기다린다. 실제 오류는 지수 backoff 뒤 5회 한도에서 실패하고, 24시간
  SLA 초과도 실패로 남긴다. remediation은 원래 `FAILED` audit·scope/generation·item 증거를 보존한
  새 요청이다. 상위 삭제는 같은 범위의 복구 완료 증거를 확인한다.
- 최종 Core 고유 테스트 251건을 검증했다. Trading Review 155건과 app 96건이며 PostgreSQL Store
  54건을 포함한다. 전체 실행의 파일 adapter JVM 시작 소켓 오류는 동일 코드의 15건 재실행으로
  보완했다. 최종 실패·skip은 0이고 모듈 경계·bootJar도 통과했다.
- Compute는 최신 main 병합 뒤 전체 단위 실행 936건 통과·선택 환경 87건 skip·event-loop 시작 오류
  1건을 확인했다. 동일 코드의 대상 파일 17건 재실행과 부모 예외 자원 보존을 포함한 29건 회귀,
  실제 PostgreSQL 15건, Ruff·strict Pyright 검증이 통과했다.
- 실제 Core↔Compute HTTP에서 기본 정규화·무결성·LedgerRevision 발행과 ORCH-024~028이 모두
  통과했다. 두 Core cutover의 assertion 10개, pre-pin migration 후 HTTP replay·Revision/ACK 보존,
  queued Job 및 응답 유실 Run의 삭제 `COMPLETED`·체크포인트 6개·파일/primary/Job 부재·capacity
  반환·늦은 create 거절을 검증했다. 구현 조합은 Core `fdbaaca`, Compute `7574c05`, integration
  `0ae4d0a`다. 성공 실행의 worker 복구 event는 0개이며 main·second·canary registry는 모두 비어 있다.
- 중단한 실행은 성공 기록에 포함하지 않는다. background JVM이 진단 PIPE를 상속해 startup 제어가
  대기하는 문제는 임시 파일 수집으로 수정하고 실제 Windows child를 포함한 진단 테스트 4건을 검증했다.
  Compute 0017의 단일 transaction 진단은 기존 `55006` 제약을 보존하고 rollback했다. 기존 `psql -f`
  runner로 migration·HTTP replay가 통과했으며, 임의의 단일 transaction 배포로 일반화하지 않는다.
- Context 문서 26개·Core/Compute/Web 계약·provenance·고정 hash fixture가 동기화됐다. 계산 입력·결과
  hash·버전과 계산 결정론 영향은 없다. credential·fixture 산출물·상세 증적은 로컬에만 보관한다.
- 전환은 [삭제 전환 절차](TRADING_DELETION_ROLLOUT.md)를 따른다. 후속 5a는 dispatch/polling/result/ACK
  진입점, 남은 5b는 retention 실행 흐름, 5c는 public component projection과 HTTP 의미 정리를 다룬다.


### 4d-1 범위와 후속 경계

- 기존 삭제 모델은 port의 문자열 기반 결과 DTO였으며 worker가 SQL로 상태를 전이했다. 4d를
  모델/저장 경계와 실행 상태/CAS 연결로 나눴다. 4d 전체 완료는 4d-2까지 포함한다.
- `DeletionRequest`는 `AggregateRoot<DeletionRequestId>`다. `DeletionScope`가 target type과 typed ID,
  requested scope를 묶고, 생성 시 scope별 영향 집계·owner·generation·초기 상태/시각을 검증한다.
  복원은 terminal metadata·failure code·attempt·시각의 일관성을 검증하며 업무 행위를 재실행하지 않는다.
- `DeletionPurgeChecklist`는 해당 scope에서 필요한 live component의 정의다. raw-only와 subtree의
  primary/derived 삭제 범위를 구분하고 object·key·temporary copy·Compute runtime을 포함한다.
  전체 request 상태나 resource count를 component 완료의 증거로 사용하지 않는다. backup deadline은
  별도 저장값을 유지하며, checklist가 실제 purge 진행이나 부재 검증을 수행했다는 뜻은 아니다.
- `DeletionRequestPersistenceMapper`는 로드된 row와 Domain을 순수 변환한다. Store의 ID/target 조회가
  같은 복원을 공유하며 이전 mapper가 결과 DTO에서 누락하던 attempt count도 Root에 보존한다.
  과거 `IMPORT_SUBTREE` label과 미집계 0 count를 현재 값으로 추정하거나 수정하지 않는다.
- Application은 검증된 Root를 생성해 `DeletionRequestStore`에 저장하고 기존 `TradingDeletion` inbound
  결과 DTO로 변환한다. Snowflake adapter와 generator port도 typed deletion ID를 사용한다. 새 Store는
  초기 REQUESTED 저장과 복원만 제공하고, 일반적인 상태 overwrite API를 추가하지 않는다.
- Worker의 기존 SQL 전이, component 진행 정보 부재와 public DTO의 전체 상태 기반 추정은 이번 단계에서
  해결됐다고 표시하지 않는다. 4d-2에서 전이/CAS·체크포인트·완료 증명을 연결하고, 5b에서 외부 I/O의
  transaction 분리와 durable 복구를 다룬다. read model/HTTP 실패 의미 정리는 5c 범위도 따른다.
- 관련 테스트 227건을 전체 실행으로 통과했다(실패·skip 0). Domain 24, Application 26,
  Persistence 57, Web·Compute·Storage 각 8, App 96건이며 실제 PostgreSQL은 Persistence 43건과
  App 23건이다. Domain 4건·Mapper 3건·실제 DB 1건·source 경계 1건을 추가했다.
- checklist에서 불필요한 backup 추정 보조 속성을 제거한 뒤 Domain 24건과 Persistence 57건을
  재검증했다. 이 추가 실행 중 기존 `bookCreationReplayAfterDeletionIsGone` 한 건이 test DB 연결
  단계의 Windows `Bad address: connect`로 실패했으며, 같은 최종 코드로 해당 항목을 별도 재실행해
  통과했다. 실패한 실행을 성공으로 계산하지 않으며, 최종 227개 고유 항목은 실패·skip 0이다.
  `verifyAggregateModuleBoundaries`·`verifyMemberSubscriptionBoundaries`와 `bootJar`도 통과했다.
- API·schema·계산 hash 의미와 계산 결정론 영향은 없다. 계약·hash vector·context 문서 25개
  동기화 검사를 통과했다. Worker와 Compute I/O 변경이 없어 실제 Core↔Compute runtime E2E는
  재실행하지 않았다. 기존 삭제·scope/owner 격리·idempotency·rollback·부분 실패/재시도·stale ACK
  DB 회귀는 포함한다. 원격 CI 결과는 별도 확인 대상이며 credential·fixture·로그·runner는 커밋하지 않는다.
- 도메인·순수 매핑(`e02feae`), Aggregate Store·typed generator·서비스/결과 경계와 DB 회귀
  (`7107bf6`), 로컬 검증 기록을 별도 커밋으로 나눴다. 다음 단계는 4d-2다.

### 4c 검증 기록과 후속 범위

- `LedgerRevision`을 `AggregateRoot<LedgerRevisionId>` 기반의 불변 모델로 정의했다. Application이
  검증된 입력 identity·결과 쌍·hash·count·section digest와 bounded metadata로 준비하고, 발행 capability가
  Book 잠금 안에서 순번과 발행 시각만 부여한다. 발행 후 재발행·내용 변경을 허용하지 않는다.
- `LedgerRevisionPersistenceMapper`는 전달받은 row와 Aggregate를 순수하게 변환하고 양방향 불변식을
  검증한다. VersionSet과 count/digest JSON의 중첩 key 순서를 정규화한다. command 복원 SQL은
  manifest의 reviewUnits·matches·issues와 quality의 validationIssues를 DB에서 제외한 뒤 JDBC로 읽는다.
  전체 membership을 Aggregate 복원에 적재하지 않는다. 독립적인 공개 summary projection은 전용 Reader다.
- 기존 staging Store의 개별 발행 인자를 제거하고 `LedgerPublication`에 Aggregate, 실행 snapshot,
  descriptor와 lease 조건을 묶었다. Revision Store/발행 adapter와 summary Reader를 분리했으며,
  수신·staging·ACK runtime capability는 유지한다. ACK row 작성만 공통 persistence helper로 공유한다.
- 발행은 transition gate와 Account→Book→Session→Run/dispatch/stage 잠금 순서를 따른다. 저장된 staging
  descriptor와 메타데이터, expected Run 버전·attempt·hash·결과 쌍, live lease·삭제 generation을 확인한다.
  잠금을 모두 얻은 후 Clock을 다시 읽으며, stale 요청은 Revision·membership·latest·ACK를 바꾸지 않는다.
- 대용량 membership·allocation·manifest 배열과 V2 supplement는 기존 INSERT SELECT/DB 집계를 유지한다.
  Session ACCEPTED·Revision/membership·Book latest·Run/dispatch 완료·ACK outbox·감사·stage 제거는 같은
  transaction이며 HTTP ACK는 commit 후 별도 runtime에서 처리한다. 중복 성공 조회도 기존 내용과
  ACK identity를 확인하고, 삭제가 시작된 Session은 성공 replay로 간주하지 않는다.
- 최종 코드의 관련 테스트 218개 고유 항목을 검증했다. Domain 20, Application 26, Persistence 53,
  Web·Compute·Storage 각 8, App 95건이며, 실제 PostgreSQL 검증은 Persistence 42건과 App 23건이다.
  새 도메인/매핑 6건과 source 경계 1건, 실제 잠금 대기 중 lease 만료 및 별도 Session membership
  격리 2건을 추가했다. 기존 DB 발행 시나리오에도 descriptor·메타데이터·Run 버전·lease 변조,
  pause·rollback·삭제 후 replay의 무변경 검증과 bounded 복원을 추가했다.
- Mapper 왕복 테스트에서 중첩 VersionSet의 JSON key 순서가 달라지는 것을 확인해 정규화했다.
  수정 후 Domain·Application·Persistence·Web·Compute·Storage 전체와 App 94건이 통과했고,
  기존 `ComputeHttpTimeoutTest.submitTimesOutBeforeHeadersAndDuringBodyDelivery` 한 건의 Windows
  `Bad address: listen` 오류는 같은 코드로 항목을 재실행해 통과했다. 위 218건은 한 번의 전체
  Gradle 실행 성공을 뜻하지 않으며 실패한 실행은 성공으로 계산하지 않는다. 최종 고유 항목은
  실패·skip 0이고 source/실제 Gradle 모듈 경계 검사와 `bootJar`도 통과했다.
- 도메인·순수 매핑(`d6df0c0`)과 발행 capability·실행 fence·DB 회귀(`fd03cf3`), 로컬 검증 기록을
  별도 커밋으로 나눈다. API·schema·계산 hash 의미 변경과 계산 결정론 영향은 없다. 구현 전후
  OpenAPI·hash vector·context 문서 25개 동기화 검사를 통과했다. 원격 CI 결과는 별도 확인 대상이다.
- `fd03cf3`의 검증 JAR로 실제 Core↔Compute runtime을 통과했다. `primary`·`result_integrity`·
  `ledger_publication`은 PASS, ACK는 `ACKNOWLEDGED_AFTER_RETRY`, publication identity·evidence references·
  idempotency는 PASS다. 증적의 source commit과 artifact SHA-256이 테스트한 JAR와 일치한다.
  로컬 증적은 integration의
  `.integration-data/trading-normalization/7fd12d4fc2e64071ab62bb5027b615de/evidence.json`이다.
- 통합 검증은 분리된 `refinvest_integration` DB와 합성 fixture를 사용했다. 로컬 wrapper에서 ACK
  간격을 20초→60초, 후속 주기 대기를 22초→62초로 조정하고, publication의 Session 대기 잠금을
  풀기 전에 Compute API를 중지해 ACK 장애를 확실히 주입했다. Windows `WinError 10014`의 API
  기동·재기동만 제한 재시도했으며, 진단용 파이프 대기는 동일 API의 추가 재기동으로 해소했다.
  기존 assertion 93개가 AST 기준 동일함을 확인했고 추가 API 재기동 뒤에도 ACK·Revision·count·latest
  불변성 검사를 통과했다. 준비 단계 오류·장애 주입 시점 경합·프로세스 제어 오류로 실패한 실행은
  통과 기록에서 제외했다. 이번 실행은 합성 기능 검증이며 대형 payload의 heap 성능 측정은 아니다.
  검증 프로세스와 임시 환경 파일은 정리했고 credential·fixture·로그·wrapper는 커밋하지 않는다.
- 후속 4d에서는 DeletionRequest를 명시적 상태 모델로 정리한다. scheduler 진입점과 파일·암호화·HTTP
  기술 구현 이동은 5a, 삭제/retention 실행 절차 분리는 5b로 남긴다.

### 4b-2 검증 기록과 후속 범위

- NormalizationRun은 Session 소속 `DomainEntity<NormalizationRunId>`다. Root의
  `admitNormalization`이 최초 생성·명시적 재시도와 입력/결과 pin을 검증한다. 공개 생성·복사와
  일반 Run 삽입 포트를 제거했고, Session 복원은 현재 Run 하나만 적재한다.
- `NormalizationRunPersistenceMapper`는 로드한 row와 소속 입력을 양방향 변환한다. Store/Reader는
  같은 매핑을 공유한다. 과거 identity·delivery policy·완료 시각 누락을 현재 값으로 추정하지 않으며,
  복원 중 업무 행위를 재실행하지 않는다. 새 실행의 상태·identity·descriptor 불변식은 검증한다.
- 명시적 admission capability는 Account→Book→Session 잠금과 transition gate, expected Session·이전
  Run 버전, raw 보관 만료와 삭제 generation을 확인한다. Session 포인터·Run·attempt·dispatch·감사
  이력을 같은 transaction에 저장하며 stale 요청과 rollback은 어느 row도 남기지 않는다.
- worker의 lease·attempt·receipt 전이는 별도 runtime capability로 유지하고 조건을 모델로 묶었다.
  UUID 생성은 순수 feature port와 `adapter:uuid`로 분리했으며 실제 Gradle 의존성 검사에 포함했다.
- 명시적 재시도가 저장된 instrument terms 대신 현재 설정을 사용하던 문제를 PostgreSQL 회귀
  테스트로 재현했다. 기존 설정과 결과 V1/V2 pin을 유지하도록 수정하고 Root에서도 변경을 거절한다.
- 최종 코드에서 cleanTest 후 관련 테스트 209건을 한 실행으로 통과했다(실패·skip 0).
  Domain 17, Application 26, Persistence 48, Web·Compute·Storage 각 8, App 94건이다.
  실제 PostgreSQL 검증은 Persistence 40건과 App 23건이며, admission의 실제 잠금 대기·commit/rollback
  경합, stale 이전 Run, 만료·삭제·pause gate와 V2 재시도를 포함한다. source 경계 검사와
  `verifyAggregateModuleBoundaries`, `verifyMemberSubscriptionBoundaries`, `bootJar`도 통과했다.
- 수정 전 검증 중 Windows 소켓의 `Bad address`로 실패한 실행은 통과 기록에 포함하지 않는다.
  최종 위 실행은 재시도 결과를 합산한 수치가 아니다. 원격 CI 통과를 뜻하지 않는다.
- 최종 코드 `e4e4b38`의 검증 JAR로 Core↔Compute 실제 runtime 검증을 통과했다.
  `primary`·`result_integrity`·`ledger_publication`은 PASS, publication의 identity·evidence references·
  idempotency는 PASS, ACK는 `ACKNOWLEDGED_AFTER_RETRY`다. 검증 증적의 source commit과 artifact
  SHA-256이 테스트한 JAR와 일치한다. 로컬 증적은 integration의
  `.integration-data/trading-normalization/cfa135385a684f8183951b59d073c37a/evidence.json`이다.
- runtime 첫 실행은 Compute 준비 단계의 Windows `WinError 10014`로 실패했으며 통과로 계산하지
  않는다. 같은 제품 코드·JAR·assertion으로 재실행해 통과했다. 검증 wrapper에서 ACK 간격을
  20초→60초, 후속 전체 주기 대기를 22초→62초로 조정했으며 검증 assertion을 제거하지 않았다.
  실행 프로세스와 임시 환경 파일은 정리했다. credential·fixture·로그·runner는 커밋하지 않는다.
- 구현 전후 계약/OpenAPI·V1/V2 hash vector·context 문서 25개 동기화 검사를 통과했다.
  API·schema·계산 hash 의미 변경은 없으며 계산 결정론 영향은 없다.
- 구현(`ce42b14`)과 재현 후 입력 고정 수정(`e4e4b38`), 로컬 검증 기록을 나눠 커밋한다.
  다음 4c에서는 immutable LedgerRevision과 publication capability·Mapper를 정리하고 membership,
  hash, generation, latest pointer 원자성과 stream/batch 저장을 유지한다. scheduler 진입점과
  파일·암호화·HTTP 기술 구현 이동은 5a 범위로 남긴다.

### 4b-1 검증 기록과 후속 범위

- 4b를 Session·Artifact의 업로드/삭제 저장 경계와 NormalizationRun의 admission/재시도 경계로
  나눴다. 두 소속 모델을 독립 Aggregate로 승격하지 않는다. 4b 전체 완료는 4b-2까지 포함한다.
- ADR-018/061/063/064와 `TR-I03`~`TR-I06`·`TR-I14`~`TR-I24`를 기준으로 Session을
  `AggregateRoot<TradingImportSessionId>`, Artifact를 소속 `DomainEntity`로 정리했다. 공개 생성자와
  `copy`를 제거하고 생성·복원의 상태/시간/policy·version pin, role·ID 중복과 소속을 검증한다.
  raw descriptor의 키와 Artifact collection은 외부 변경을 차단한다. 복원은 업무 행위·이벤트를 재실행하지 않는다.
- Application은 Root의 `recordUpload`·`requestArtifactDeletion`·`requestDeletion` 뒤 Session Store를
  호출한다. Root와 최대 두 Artifact를 같은 transaction에서 저장하며 하위 Entity를 직접 저장하는
  command port를 제거했다. Store/Reader는 같은 순수 `TradingImportSessionPersistenceMapper`를
  사용하고 JDBC row 추출과 SQL 컬럼 분해는 adapter가 소유한다. 기존 JDBC와 대용량 result 저장을 유지한다.
- 새 Session 저장은 Account→Book 순서로 잠그고 활성 parent·owner·mode를 검사한다. 변경 저장은
  Session 잠금·expected Root/Artifact version, immutable metadata·입력 pin·created Revision pointer와
  허용된 상태/generation을 검사한다. stale 저장은 Root·Artifact 모두 변경하지 않는다. 빈 collection을
  저장해 기존 Artifact를 지우거나 일반 저장으로 발행 pointer를 바꿀 수 없다.
- Raw purge는 별도 capability/adapter로 분리했다. 갱신된 claim의 Artifact version과 다른 늦은
  완료·실패 응답은 상태나 실패 횟수를 바꾸지 않는다. 기존 idempotency·원자적 admission/publication,
  삭제 generation과 DB transaction 밖의 object I/O를 보존한다.
- 실제 PostgreSQL에서 삭제 요청 직후 생성 key를 재전송하면 삭제 대기 Root를 반환하는 회귀를
  재현했다. 수정 후 요청 직후와 purge 이후 모두 `410 RESOURCE_DELETED`를 검증했다.
- 실제 HTTP smoke에서 ADR-064의 grant retry가 새 attempt에 기존 Compute job ID를 보존하지 않는
  `CORE_ATTEMPT_DRIFT`도 발견했다. PostgreSQL 회귀는 `[same-job, same-job]` 대신
  `[same-job, null]`로 실패했다. 같은 job의 grant 갱신에서만 현재 job ID를 새 attempt에 저장하도록
  수정했다. 새 Compute job을 만드는 재시도는 ID를 상속하지 않으며, 잘못된/만료 lease·stale token
  거절과 Run/attempt/audit rollback을 함께 검증한다. 과거 attempt 이력의 자동 보정은 포함하지 않는다.
- 전체 관련 테스트는 domain 12개·application 26개·persistence 41개·web 8개·compute 8개·storage
  8개·app 93개로 196개 통과, 실패·오류·skip 0개다. security/snowflake는 테스트 source가 없다.
  재전송 수정 후 application·persistence·app 160개를 다시 실행했고 나머지 36개는 앞선 성공 결과를
  재사용했다. 실제 PostgreSQL 59개(Trading Review 36개·app 23개)를 포함한다. 소스 경계와 실제
  Gradle 의존성 검사, bootJar도 통과했다.
- grant retry 이력 수정 뒤 persistence 41개·Gradle 경계·bootJar를 추가 실행해 통과했다. 나머지
  155개는 앞선 성공 결과를 재사용했다. 관련 테스트 합계는 중복 실행을 합산하지 않은 196개다.
- 새 회귀는 immutable pin·Artifact identity 변경/누락 거절, stale snapshot 슬롯 보존, parent 삭제,
  Root/Artifact 전체 rollback과 purge claim 교체를 검증한다. 기존 업로드 경합·replay, admission·retry,
  삭제 후 grant 차단, V1/V2 publication·ACK 회귀도 통과했다. 기존 만료 fixture의 시간 역전과
  service/worker Clock 불일치는 유효한 시간 흐름으로 수정했다. Windows Gradle 소켓 bind 오류로
  테스트가 시작되지 않은 실행과 수정 전 실패는 성공으로 세지 않았다.
- 실제 Core↔Compute `test-trading-normalization-runtime-contract.ps1 -Publication`의 업로드·정규화,
  result 무결성, Ledger 발행, Compute 중단/재기동 뒤 ACK 재전송과 한 scheduler 주기 뒤 재조회가
  통과했다. `primary`·`result_integrity`·`ledger_publication` 모두 PASS이며 ACK는
  `ACKNOWLEDGED_AFTER_RETRY`, idempotency는 PASS다. membership/hash·evidence reference·건수,
  latest pointer와 staging 정리를 확인했다. 검증한 bootJar의 SHA-256은 실행 증적과 일치한다.
- 검증 runner는 이미 빌드한 JAR를 사용하고 ACK fault를 설치할 수 있도록 scheduler 주기를
  로컬에서 60초로, 후속 확인 대기를 62초로 맞췄다. 원래 assertion은 유지했다. 장시간 실행 중단 뒤
  인증 만료로 마지막 조회가 401이 된 실행, grant retry 이력 수정 전 실패와 Windows 소켓 오류로
  기동되지 못한 실행은 통과로 세지 않았다. 전용 프로세스·임시 환경 파일을 정리했다.
- PR #96의 최신 정본 문서 동기화는 main 병합으로 반영했다. 실행 코드는 HTTP 검증 커밋
  `f5ac6df`와 같으며 이후 변경은 문서뿐이다. 병합 후 계약·정본 문서 25개 동기화와
  `git diff --check`도 통과했다. API·schema·Compute 요청과 계산 hash 의미 변경은
  없으며 계산 결정론 영향은 없다. 새 저장/CAS 검증이 유지되도록 쓰기·retention 워커 인스턴스를 함께
  갱신한다. credential·fixture·로그·검증 runner는 커밋하지 않는다.
- 다음 4b-2에서는 NormalizationRun을 Session 소속 모델로 정리하고 Root를 통한 생성/재시도,
  pure Mapper와 명시적 admission capability를 구현한다. exact input·result/version pin,
  active Run uniqueness와 attempt/lease/deletion generation fence를 유지한다. 4c 발행 모델과
  5a scheduler·파일/암호화 port 구현 이동은 후속 단계다.

### 4a 검증 기록과 적용 기준

- ADR-018/061/063, `Create/Get/List/DeleteTradingAccount`·`Create/Get/List/DeleteTradingBook`과
  `TR-I01`·`TR-I02`·`TR-I14`~`TR-I16`·`TR-I19`·`TR-I24`를 기준으로 정리했다. Account와 Book은
  각각 독립 AggregateRoot이며 소속 Book 전체를 Account의 메모리 collection으로 적재하지 않는다.
  ID는 Identifier 기반 value object로 분리하고 Root 생성자를 private으로 유지한다.
- 생성·복원 모두 이름, timezone, version, deletion generation·상태 조합과 timestamp 순서를
  검증한다. 수정 전 공통 기반 누락과 잘못된 복원 허용의 회귀 실패 3개를 확인했다. 복원은
  새 업무 행위나 이벤트를 실행하지 않는다. rename·삭제는 기존처럼 새로운 불변 snapshot을 반환한다.
- JDBC는 유지했다. Account/Book별 Store·Reader와 PersistenceMapper/Record를 분리했으며
  삭제 journal과 idempotency adapter도 각각 독립시켰다. Mapper는 준비된 record만 변환하고
  조회·시각 결정·ID 생성·상태 전이는 수행하지 않는다. Book의 기존 latest Ledger reference도
  typed ID로 복원하며 Ledger body나 전체 하위 row를 조회하지 않는다.
- 일반 저장은 기존 version CAS에 immutable identity·현재 lifecycle·generation·timestamp와
  Book pointer 조건을 더했다. 과거 snapshot이나 복원된 다른 parent/timezone·terminal 상태로
  저장할 수 없다. Book 생성은 현재 Account를 잠가 ACTIVE·owner·venue를 검증한다. 일반 저장은
  publication pointer를 갱신하지 않으며 기존 streaming publication/ACK/purge 경로를 보존한다.
- Account 삭제의 일괄 Book 차단은 owner·generation·저장 후 version·요청 시각을 묶은 원자적
  capability로 분리했다. 같은 transaction의 Account fence를 잠가 검증하고 stale 요청은 아무것도
  바꾸지 않는다. 이미 삭제 중인 Book의 generation은 재증가시키지 않으며 affected count는 archived/
  deleting Book도 포함한 요청 scope를 센다. 시계 차이로 자식 updatedAt이 역전되는 실패도 재현했고
  기존 시각과 요청 시각 중 늦은 값을 유지해 복원 가능한 상태를 보존했다.
- 실제 PostgreSQL에서 삭제 중인 Account/Book이 같은 생성 키 재전송으로 반환되는 실패 2개를
  재현하고 `410 RESOURCE_DELETED`로 차단했다. 외부 owner의 `404`와 기존 정상 replay는 유지한다.
  `TR-ACC-DELETE-006/007/011`의 접근 차단·scope·중복 generation 부분을 검증했다. 전체 purge
  workflow와 DeletionRequest 모델 분리는 4d/5b에 남아 있으며 이 단계가 모두 완료했다는 뜻은 아니다.
- Domain 9개·application 26개·persistence 36개·web 8개·compute 8개·storage 8개와 app 92개,
  총 187개의 통과 결과를 확인했다. PostgreSQL은 Trading Review 33개와 기존 app DB/migration
  23개, 총 56개다. 초기 전체 실행의 DB 연결/worker 종료 `Bad address: connect/listen` 실패는
  성공으로 세지 않았다. 기존 통과 결과를 유지하고 해당 항목을 선택 재실행했으며 최종 변경 후
  persistence 36개와 실행 JAR를 다시 검증했다. 테스트 skip은 0개이고 컴파일 UP-TO-DATE는
  테스트 실행으로 세지 않는다. Security/Snowflake는 테스트 source가 없어 컴파일·경계만 확인했다.
- PostgreSQL 회귀는 실제 row 잠금 대기, owner 격리, immutable parent/timezone, stale 저장·cascade,
  rollback 시 Root/child/journal/idempotency 동시 복구와 삭제 generation 1회 증가를 확인했다.
  기존 실제 publication/purge 테스트에 latest pointer 복원·유지와 stale 저장 거절을 추가했다.
- Trading Review 9개 모듈의 소스 및 실제 Gradle 의존성 검사를 추가했다. security의 Member port
  전이 의존성을 제한하며 BOM metadata와 framework 구현 JAR를 구분한다. 기존 Compute scheduler의
  application 구현체 두 참조는 정확한 타입으로 추적하고 5a에서 제거한다. 새 Account/Book port와
  Mapper는 I/O 구현을 허용하지 않는다. 기존 port의 파일/암호화 구현과 inbound/HTTP 경계는 5a/5c다.
- schema·API 필드·hash·DSL·계산 결정론 변경은 없다. 정상 저장 데이터·idempotency·publication을
  유지한다. 최종 코드 `12aba18`의 실행 JAR로 합성 CSV 업로드부터 실제 Compute normalization,
  결과 무결성·Ledger 발행·latest pointer·ACK 장애/복구·재조회 idempotency의 원본 runtime assertion을
  통과했다. evidence의 Core commit과 JAR SHA-256도 현재 구현과 일치했다. 계약·문서 snapshot
  24개·Web snapshot과 context vector 검사도 통과했다.
- 최초 ACK 장애 주입 창을 놓친 실행과 DB/Compute 기동의 Windows 소켓 오류 실행은 성공으로
  세지 않았다. 로컬 harness의 ACK 주기를 20초에서 60초로 늘려 장애/복구와 원본 assertion을
  확인했다. ACK 이후 관찰도 62초로 맞춘 추가 실행은 Compute의 `WinError 10014` 기동·재기동
  실패로 끝났으며 통과로 기록하지 않는다. 이 추가 관찰은 환경이 안정된 곳에서 재검증할 수 있다.
  제품 코드는 검증을 위해 변경하지 않았고 assertion을 제거하지 않았다. 검증용 프로세스·임시
  환경 파일은 각 실행 후 정리했으며 credential·fixture·로그는 커밋하지 않는다. 다음 단계는
  4b ImportSession과 소속 Artifact/NormalizationRun이다.

### 3c 검증 기록과 적용 기준

- ADR-018/019/020과 로그인·refresh·현재 회원 조회, `RunBacktest`·`ListBacktestRuns`를 기준으로
  R12의 직접 domain 참조를 제거했다. 회원 역할과 구독 tier는 각 feature의 inbound port가
  소유하는 enum으로 전달하며 application에서 domain 값을 변환한다. MEMBER/ADMIN과
  FREE/PRO의 외부 값은 유지한다. Domain Aggregate와 command Store/Reader 경계는 유지한다.
- Backtest 실행은 Subscription의 `GetBacktestPolicyUseCase`를 통해 한도·허용 Asset을 받는다.
  tier 선택과 구독 부재 시 FREE 기본값, 실제 정책은 Subscription이 소유한다. Backtest는
  정책 결과로 entitlement·포함 일수·quota/capacity 예약을 검증하고 tier별 수치를 직접 결정하지
  않는다. 결과 Set은 복사해 소비자의 변경이 이후 정책 결정을 바꾸지 않도록 했다.
- 실행 이력의 소유권은 `LookupStrategyOwnerUseCase`로 확인한다. JPA의 소유자 컬럼 조회는
  Strategy 전체 버전·조건을 로딩하지 않는다. 존재하지 않는 전략과 타인 전략 모두 이력을
  읽기 전에 기존과 같은 not found로 처리한다. 큰 ID와 빈 전략의 소유자도 보존한다.
- 다른 feature port 의존성은 전이 의존성을 제한해 그 feature의 domain을 compile/test classpath에
  노출하지 않는다. 기존 Gradle 의존성 검사에 application과 Auth security의 실제 classpath 검사도
  추가했다. 수정 전 `member:application`에 `subscription:domain`이 유입되는 경계 실패를 확인했고
  수정 후 통과했다. Backtest/Auth 소스 검사에 있던 domain 타입 예외도 제거했다.
- feature 간 사용하는 port 계약 7개의 method signature·중첩 모델·generic·sealed 구현 타입을
  검사한다. 단순히 import를 없애고 domain 모델을 결과 안에 숨기는 변경도 거절한다.
- Member application 3개·Subscription application 9개·Auth application 11개·Auth security 3개,
  Strategy application 14개·persistence 24개, Backtest application 30개·persistence 43개와 app
  90개, 총 227개를 한 번의 실행에서 실제 검증했다. 실패·오류·테스트 skip은 0개다. 선택한
  모듈의 cleanTest 후 실행한 결과이며 컴파일 UP-TO-DATE를 테스트 실행으로 세지 않는다.
- 실제 PostgreSQL 검증은 Strategy 16개, Backtest quota/lease/Run 저장 30개와 기존 app DB/
  migration 23개, 총 69개다. 소유권 조회와 함께 immutable 저장, rollback, 월 경계 capacity,
  stale polling, refresh 회전·폐기·경합과 회원 생성/구독 업그레이드의 원자성을 재검증했다.
  소스·실제 Gradle 의존성·계약 모델 검사를 통과했고 실행 JAR도 빌드했다.
- 역할·구독 값의 JSON 표현, public/Compute API, schema, hash·DSL·계산 결정론에는 영향이 없다.
  현재 커밋의 실행 JAR와 합성 `integration-e2e-v1` fixture, local integration DB로 실제
  Core↔Compute HTTP smoke를 통과했다. 정상·재실행·실패 결과·타인 접근 거절과 terminal
  persistence를 확인했다. JAR artifact identity를 포함한 기존 topology preflight와 smoke
  assertion을 유지했다. 별도 포트/registry의 프로세스와 임시 환경 파일은 정리했다.
- 계약 snapshot·문서 snapshot 24개와 context vector 검사도 통과했다. 로컬 IPv4 옵션은 검증
  프로세스에만 적용하며 credential·fixture·로그는 커밋하지 않는다. 다음 단계는 4a
  TradingAccount/TradingBook Root·typed ID·Mapper·Store 분리와 ownership 보존이다.

### 3b 검증 기록과 적용 기준

- ADR-010/018/020과 `RunBacktest`·`GetBacktestResult`·`PollBacktestStatus`·`ListBacktestRuns`를
  기준으로 BacktestRun의 저장·복원을 정리했다. Root와 typed ID 및 소속 BacktestResult 경계를
  유지한다. `restore`도 완료 행위와 동일하게 결과의 Run ID·DatasetSnapshot ID를 검증하며,
  잘못된 errorCode는 상태를 바꾸기 전에 거절한다. 이 불변식과 중첩 목록 변경의 회귀 실패
  3개를 수정 전에 확인했다. 결과·benchmark curve·delay 분포는 입력 복사와 변경 불가 목록으로
  보호하며, JSON에 새 Aggregate/Entity ID 필드를 추가하지 않는다.
- `BacktestRunPersistenceMapper`와 `BacktestResultPersistenceMapper`는 adapter가 조회한
  Root/Result row를 받아 전체 Aggregate를 복원한다. Repository 조회·시각 결정·업무 전이는
  Mapper에서 수행하지 않는다. 독립적인 상태·목록 조회는 `BacktestRunReadModelMapper`를
  사용하고, 결과 Reader는 Store와 같은 결과 매핑을 공유한다. nullable 지표, ZERO sample,
  optional benchmark, 큰 ID, 소수 정밀도와 기존 legacy 결과 재계산 안내를 보존한다.
- 이전 adapter로 stale RUNNING 저장이 이미 commit된 COMPLETED를 덮어쓰는 PostgreSQL
  회귀 실패를 재현했다. command 조회와 저장은 Root row를 잠그고 기존 JPA cache도 새로
  로딩한다. 요청 입력·execution pin·terminal 상태/결과 변경은 거절하며, 동일 terminal 저장은
  기존 Result row/OID와 완료 지표를 변경하지 않는다. Result는 append-only로 저장한다.
  DB timestamp와 fee 컬럼의 기존 정밀도를 고려해 원래 입력의 정상 재저장을 허용한다.
- Store의 변경용 조회→행위→저장은 같은 command transaction 안에서 수행한다. Dispatch의
  요청 정보 조회는 짧은 transaction으로 끝내고 Compute HTTP는 transaction 밖에서 호출한다.
  영구 거절 반영은 현재 Run을 다시 잠가 복원한 후 상태·capacity를 함께 저장한다. 결과 조회는
  read-only transaction에서 PostgreSQL LOB를 읽고 Run의 snapshot과 결과의 일치를 확인한다.
- Backtest domain·port·application·persistence·compute·web·snowflake를 소스 및 실제 Gradle
  project 의존성 경계 검사에 추가했다. R12의 기존 직접 domain 참조 3개 타입만 명시적으로
  추적하며 3c에서 제거한다. API/schema/hash 및 계산 결정론에는 영향이 없다.
- domain 10개·application 30개·persistence 43개·compute 10개와 app 89개, 총 182개 테스트의
  통과를 확인했다. 테스트 skip은 0개다. 한 번의 전체 실행 성공을 뜻하지 않는다. domain/
  application/compute 통과 결과를 유지하고, 수정 후 persistence 및 app을 재실행했다. Windows
  `Bad address: listen` 및 DB 연결 소켓 오류로 실패한 실행은 성공으로 세지 않았다. 마지막으로
  실패했던 HTTP timeout/중복 예약 회귀 1개를 선택 재실행해 Gradle 정상 종료를 확인했다.
- 실제 PostgreSQL 검증은 quota 9개·polling lease/영구 거절 12개·Aggregate 저장 9개와 기존
  app DB/migration 23개, 총 53개다. stale 요청의 Run·capacity·dispatch 보존, 실제 row 잠금
  대기와 cache 갱신, 다른 Root의 독립 저장, rollback과 terminal Result/OID 보존을 확인했다.
  Mapper 6개는 전체 상태/결과와 불법 저장 상태, JSON shape 및 정밀한 소수 비교를 검증했다.
- 앱의 Spring 프록시·HTTP 회귀 89개와 실제 Gradle project 의존성 검사를 통과했고 실행 JAR도
  빌드했다. 컴파일 UP-TO-DATE는 테스트 실제 실행과 구분한다. IPv4 옵션과 loopback bind는
  로컬 검증 프로세스에만 적용했다. 로컬 credential·fixture·로그는 커밋하지 않는다.
- domain 보강(`7483529`)과 Mapper·저장 보호·회귀/경계 검증(`c5d363b`)을 나눠 커밋했다.
  잠금 보장은 모든 writer가 새 저장 경로를 사용할 때 적용된다. 다음 단계는 3c feature port
  경계 정리다.
- 계약 snapshot과 문서 snapshot 24개, context vector 검사를 통과했다. 현재 구현의 실행 JAR로
  local integration DB와 `integration-e2e-v1` 합성 fixture에서 실제 Core↔Compute HTTP smoke를
  통과했다. 정상 실행·재실행·실패 결과·타인 접근 거절 및 terminal persistence를 확인했다.
  JAR 실행의 artifact identity를 topology preflight에 전달하고 기존 smoke assertion을 유지했다.
  별도 포트/registry로 기동한 세 프로세스와 임시 환경 파일은 정리했으며 harness 산출물은
  커밋하지 않았다. API/schema/hash·계산 입력 및 결정론 영향은 없다.

### 3a 검증 기록과 적용 기준

- ADR-013/018과 `CreateStrategy`·`DefineStrategyVersion`·`GetStrategy`·`ListStrategies`를 기준으로
  Strategy의 저장·복원과 조회 매핑을 정리했다. 기존 AggregateRoot·typed ID를 유지하며
  `Strategy.restore`·`StrategyVersion.restore`가 생성/추가 행위를 재실행하지 않고 같은 불변식을
  검증한다. Root는 이름·child 소유권·버전 ID 중복을 검사하고 입력 버전 목록을 복사한다.
- 수정 전 Version의 조건 목록을 MutableList로 변환해 변경할 수 있는 회귀 실패를 재현했다.
  조건은 입력을 복사한 변경 불가 목록으로 보관해 생성·복원된 immutable 정의를 보호한다.
- `StrategyPersistenceMapper`는 Aggregate·Version·Condition의 양방향 변환과 child 관계를 만든다.
  새 Aggregate 저장만 전체 그래프를 생성하며 기존 Root는 잠근 뒤 새 버전만 append한다. R03의
  기존 metadata/definition 변경 거절, 다른 Root의 버전 ID 충돌 거절과 DB timestamp 정밀도 처리를
  adapter에 보존했다. 기존 버전·조건 collection을 교체하거나 row를 재생성하지 않는다.
- `StrategyReadModelMapper`는 Root를 복원하지 않고 상세·목록과 Backtest 소비 projection으로
  직접 변환한다. 독립적인 버전 조회의 owner·조건 row는 adapter가 먼저 준비한다. 조건 순서와
  생성 시각·숫자 ID 기준 버전 정렬을 명시해 R05의 최신 선택·동률 기준을 유지한다.
- domain 17개·application 14개·persistence 23개·snowflake 1개와 app 87개, 총 142개가 통과했다.
  실패·오류·테스트 skip은 0개다. Strategy 대상 55개와 앱의 나머지 40개 통과 결과를 유지하고,
  최종 실행에서 기존 앱 회귀 47개를 실제 재실행한 합계다. 컴파일 UP-TO-DATE와 테스트 결과
  재사용 여부를 구분하며, 전체 142개가 한 번의 task 실행에서 통과했다고 표시하지 않는다.
- Mapper 테스트 5개는 전체 정의·큰 ID·timestamp round-trip, nullable window/combinator,
  Literal/Metric operand, child 관계·순서와 잘못된 저장 상태의 거절을 검증했다. PostgreSQL
  Strategy 테스트 15개로 동시 추가·rollback·기존 row/metadata 보존·최신 선택을 재검증했다.
  이미 로딩된 그래프를 세션 종료 후 변환하고 복원한 Aggregate를 다시 저장해도 row가 바뀌지
  않는 것도 확인했다. App의 기존 PostgreSQL 검증 23개도 실제 실행했으므로 DB 검증은 총 38개다.
- PostgreSQL fixture는 작은 연결 풀을 사용하며 경쟁 작업을 시작하기 전에 별도 물리 연결을
  확보한다. 기존 실제 잠금 대기 assertion을 유지했고 전용 schema와 연결 풀을 정리했다.
- Windows에서 `Bad address: listen`이 Tomcat 및 Gradle test worker 기동 중 반복됐다. 해당
  실패·미기동 실행은 성공으로 세지 않았다. 최종 앱 47개는 로컬 프로세스의
  `SERVER_ADDRESS=127.0.0.1`, `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`로 검증했다.
  이 옵션은 로컬 실행 환경에만 적용했다.
- Strategy domain·port·application·persistence·web·snowflake를 소스 및 실제 Gradle 의존성 경계
  검사에 추가했다. 실행 진입점은 기존 `verifyAggregateModuleBoundaries`를 사용한다.
- 계약 snapshot, 문서 snapshot 24개와 git diff 검사를 통과했다. API/schema/hash·계산 입력 및
  계산 결정론 영향은 없다. Core↔Compute 호출 변경이 없어 해당 E2E는 재실행하지 않았으며
  Backtest 소비 projection은 회귀 검증에 포함했다. 로컬 credential·fixture·로그는 커밋하지 않는다.
- domain 복원·불변성 보강(`a824c26`)과 Mapper·조회·회귀/경계 검증(`270146d`)을 나눠 커밋했다.
  다음 단계는 3b이며, Backtest Run/Result의 저장·복원을 정리하면서 R02 quota/capacity 예약과
  R04 polling lease의 stale 응답 차단을 유지한다.

### 2b 검증 기록과 적용 순서

- ADR-018/019와 `SocialLogin`·`RefreshSession`·`Logout`을 기준으로 Auth Aggregate를 정리했다.
  SocialIdentity는 provider/subject 자연키의 typed ID, RefreshSession은 기존 UUID JTI의 typed ID를
  사용하며 공통 AggregateRoot를 상속한다. 공개 생성자·copy 대신 생성·복원·행위를 구분하고
  잘못된 수명·회전 상태, 다른 member/family 또는 같은 JTI의 교체를 거절한다. Domain에서
  ID나 현재 시각을 생성하지 않으며 저장된 provider/subject·JTI·family를 재발급하지 않는다.
- 명시적인 두 PersistenceMapper가 전체 상태를 변환한다. SocialIdentity의 DB surrogate ID는
  persistence 내부에 남기며 typed 자연키로 Aggregate를 조회한다. Application은 Aggregate가 만든
  검증된 회전 모델을 Store로 전달하고 family 폐기도 요청 모델로 묶는다. Mapper는 I/O·시계·ID
  생성과 업무 행위를 수행하지 않는다. 현재 Member role의 정본 조회와 provider별 identity
  유일성을 유지하며, Auth의 기존 Member role 직접 타입 참조 정리는 3c에 남긴다.
- 수정 전 실제 PostgreSQL에서 회전 transaction이 교체 credential을 저장하는 동안 로그아웃하면
  family 폐기가 교체 credential을 놓쳐 활성 row 1개가 남는 회귀 실패를 재현했다.
- V12의 `refresh_session_family_locks`는 별도 domain Aggregate가 아닌 원자적 family capability의
  기술 잠금·폐기 표식이다. family 사용 시 row를 생성해 최초 저장·회전·폐기를 같은 잠금으로
  직렬화하고, 폐기된 빈 family에도 새 credential을 추가하지 않는다. 회전은 잠금 대기 후 JPA의
  관리 중인 세션도 refresh해 expected 상태를 검사하며 JTI 충돌은 overwrite 대신 실패시킨다.
  family 폐기는 전체 이력을 적재하지 않고 bulk 갱신한다.
- PostgreSQL Auth 테스트 13개로 회전/로그아웃 양방향 경합, 동일 credential의 중복 refresh와
  replay에 따른 winner family 폐기, 최초 저장/폐기 경합, 다른 family 격리, 충돌·rollback과
  stale snapshot을 검증했다. 별도 물리 연결과 실제 DB 잠금 대기를 사용한다. 동시 최초 소셜
  로그인은 identity 하나만 commit하며 패한 요청의 Member/Subscription이 함께 rollback된다.
- 기존 Auth schema에 V12를 적용해 모든 기존 session/identity row 보존, Flyway 재실행 0건과
  Auth entity의 Hibernate validate를 확인했다. 기존 전체 Flyway 경로의 migration 12개 적용도
  실제 PostgreSQL에서 통과했다. Core 전체 schema 편입·운영 validate 전환은 6a에 남아 있다.
- 검증 합계는 domain 8개·Mapper 8개·application 18개·security 3개·app 85개로 총 122개 통과다.
  실패·오류·테스트 skip은 0개다. 앞선 단위 테스트 37개 결과를 유지하고, H2의 native UUID
  반환형 문제를 JPA entity 잠금 조회로 수정한 뒤 app 85개를 실제 재실행했다. app에는 PostgreSQL
  Auth Store 13개·Auth migration 1개·Member/Subscription 8개·기존 Flyway 1개, 총 23개의 실제 DB
  검증과 로그인·쿠키·CSRF·refresh·로그아웃 HTTP 회귀가 포함된다. 실패한 중간 실행을 통과로
  세지 않으며 컴파일의 UP-TO-DATE와 테스트 실행을 구분한다.
- 소스 경계와 실제 Gradle project/기술 의존성 검사에 Auth를 추가했다. 실행 진입점은
  `verifyAggregateModuleBoundaries`이며 기존 `verifyMemberSubscriptionBoundaries`도 alias로 유지한다.
  security adapter의 사용하지 않는 application 의존성을 제거했다.
- 적용 순서는 Auth 쓰기(로그인·refresh·로그아웃) 중지 → 기존 transaction 종료 → V12 적용 →
  모든 Auth writer의 수정 코드 교체 → 쓰기 재개다. 이전 writer와의 혼합 실행은 보호 범위에
  포함하지 않는다. V12는 기존 Auth 데이터를 변경하지 않으며 코드 rollback 시에도 새 테이블과
  Flyway 이력을 삭제하지 않는다. 이전 코드는 이번 경합 수정을 포함하지 않으므로 쓰기를 차단한
  상태에서 복구 코드를 검증하고 재개한다.
- API/hash·계산 입력 변경과 계산 결정론 영향은 없다. Core↔Compute 호출 변경이 없어 해당 E2E는
  재실행하지 않았다. 임시 schema·연결 풀을 정리했으며 로컬 credential·fixture·로그는 커밋하지
  않는다. Aggregate/Mapper 정리(`67798c2`)와 family 경합·migration·회귀 검증(`7ab5d1e`)을 나눠
  커밋했다. 다음 단계는 기존 R03/R05 수정을 보존하는 3a Strategy 매핑·조회 정리다.

### 2a 검증 기록과 적용 기준

- 기존 Member/Subscription은 이미 `AggregateRoot<MemberId>`와 private 생성자·`create/restore`를
  사용하고 있었다. 이번 단계는 이를 유지하면서 순수 `MemberPersistenceMapper`와
  `SubscriptionPersistenceMapper`에 domain ↔ JPA 변환을 모았다. Store는 Aggregate를 받고,
  단순 Aggregate 조회인 Reader와 command Store는 같은 복원 Mapper를 사용한다.
- `MemberRole`은 성격별 `valueobject` package로 이동했다. 신규 회원의 MEMBER 기본값과 DB에
  저장된 ADMIN 역할 복원을 구분하며, Auth의 직접 domain 타입 참조 정리는 예정된 3c에서 다룬다.
- 실제 PostgreSQL에서 PRO 업그레이드 후 기본 구독을 재생성하면 FREE로 바뀌는 회귀 실패를
  재현했다. 수정 후 생성 서비스는 Store로 조회한 기존 Aggregate를 그대로 반환한다.
- 구독 command는 회원 row를 잠근 뒤 조회·변경·저장한다. 구독 row가 없는 최초 생성도 같은
  회원 기준으로 직렬화하며 독립 Reader는 잠그지 않는다. command 조회는 활성 transaction이
  필요하고 조회→행위→저장은 같은 transaction에서 실행한다. 모든 구독 writer에 수정 코드를
  적용해야 이 규칙을 보장하며, 이전 writer와의 혼합 실행은 보호 범위에 포함하지 않는다.
- `cleanTest` 후 Mapper 4개·Member application 1개·Subscription application 6개·Auth application
  11개·app 69개, 총 91개를 실제 실행해 통과했다. 실패·오류·skip은 0개다. 컴파일 일부는
  UP-TO-DATE였으나 테스트 결과는 재사용하지 않았다.
- app의 PostgreSQL 테스트 8개로 회원/기본 구독 저장과 ADMIN 복원, PRO 재생성, 누락 구독
  업그레이드, 최초 생성↔업그레이드 양방향 경합, Reader/다른 회원 격리, command transaction
  경계와 회원·구독/업그레이드 rollback을 검증했다. 경합은 별도 물리 연결과 실제 잠금 대기로
  확인하며 전용 임시 schema를 정리했다. 기존 PostgreSQL Flyway 테스트도 실제 실행했다.
  Member/Subscription의 기존 schema를 Flyway로 편입하는 작업은 6a에 남아 있다.
- 소스 경계 검사 2개와 실제 Gradle project/기술 의존성 검사
  `verifyMemberSubscriptionBoundaries`를 추가하고 `:app:test`에 연결했다.
- 이번 변경은 API/schema/hash·계산 입력과 계산 결정론에 영향을 주지 않는다. Core↔Compute 호출
  변경이 없어 해당 E2E는 재실행하지 않았다. Windows Gradle 소켓 오류로 시작되지 않은 실행과
  fixture 준비 중 실패한 실행은 위 통과 결과에 포함하지 않았다. 로컬 credential·fixture·로그는
  커밋하지 않는다.
- 구현은 Member 기준 정리(`2acf644`)와 Subscription 매핑·동시성/회귀 검증(`3c2c4be`)으로
  나누었다. 다음 단계는 2b이며, refresh 회전·폐기·replay 의미와 경합 원자성을 보존하면서 Auth
  Aggregate 행위와 persistence 매핑을 정리한다.

### 1f 검증 기록과 운영 설정

- 수정 전 production HTTP constructor를 실제 지연 서버에 연결해, 250ms 설정에도 요청이 3초 검증
  경계까지 반환하지 못하는 회귀 실패를 재현했다. 수정 후 헤더·본문·계속 전달되는 본문과 TLS
  handshake 지연, 연결 거절을 실제 소켓으로 검증했다.
- 공통 기술 구현 `HttpClientTimeouts`는 `shared:infrastructure`에 두고 Backtest 제출·polling 및 Asset
  조회 adapter에 적용했다. 설정은 `refinvest.compute.connect-timeout` / `read-timeout`이며 환경 변수
  `COMPUTE_CONNECT_TIMEOUT=PT2S`, `COMPUTE_READ_TIMEOUT=PT20S`가 기본값이다.
- 두 timeout은 양수여야 하며 Backtest는 합이 dispatch/poll lease 60초 미만이어야 기동한다.
  application과 HTTP adapter가 같은 port의 lease 값을 참조한다. HTTP I/O는 DB transaction 밖에서 수행한다.
- 실제 PostgreSQL에서 응답 본문 timeout 뒤 동일한 저장 key·요청으로 제출을 재시도해 acceptance를
  기록하고, quota 재소비 없이 capacity를 유지함을 확인했다. polling timeout도 Run·Result·capacity를
  변경하지 않고 10초 뒤 재조회하며, 정상 terminal 완료 뒤 capacity를 해제한다.
- 관련 결과는 공통 3개·Backtest HTTP 10개·Asset HTTP 5개·Backtest application 29개·persistence 27개·
  app 59개로 133개 통과, 실패·오류·skip 0개다. 앞선 실행에서 통과한 47개는 재사용됐고, 최종 실행에서
  persistence 27개와 app 59개를 실제 실행했다. 실제 PG lease/timeout 11개·quota 9개와 app의 실제 PG
  migration을 포함한다. app의 실제 지연 HTTP 테스트 7개에는 JUnit 실행 목록에서 확인한 본문 진행 기한도 포함한다.
- 로컬 Gradle 컴파일 정체와 DB `Bad address: connect` 실패 실행은 성공으로 세지 않았다.
  검증용 compiler 프로세스를 격리하고 heap을 조정했다. PG fixture에는 작은 연결 풀을 적용했으며,
  quota 경합은 작업자 수만큼 물리 연결을 미리 확보해 동시 transaction 검증을 유지한다.
- 실제 Core↔Compute `run-api-smoke-ci.ps1`가 정상 Backtest·재실행·계산 실패·다른 사용자 조회 차단을
  통과했다. 추가 Asset 검증은 처음 쿠키 미전달로 401을 받았고, 쿠키 세션으로 고친 별도 재실행에서
  `/assets`와 QQQ availability·snapshot metadata를 검증해 통과했다. 전용 서비스·임시 환경 파일을 정리했다.
- 수정 전후 계약 snapshot, 문서 snapshot 24개와 `git diff --check`가 통과했다. credential·fixture·
  로그·heap dump는 커밋하지 않는다. wire/schema/hash·계산 입력 변경은 없으며 계산 결정론 영향은 없다.

### 1e 검증 기록

- 정본은 version이 없는 경우의 `latestVersionId=null`을 명시하며, 상세 version 배열의 정렬과
  생성 시각 동률의 선택 기준은 별도로 지정하지 않는다. Core read model에 `(createdAt, 숫자 ID)`
  오름차순과 마지막 version을 최신으로 선택하는 기준을 명시했다.
- 수정 전 회귀 테스트에서 같은 시각의 version이 `[20, 300, 9, 10]` 대신 `[20, 300, 10, 9]`로
  반환되는 실패를 재현했다. 기존 `lastOrNull` 선택은 이 collection 순서에 의존했다.
- 상세·목록 Reader의 공통 변환에서 정렬하며, 입력 persistence collection은 변경하지 않는다.
  ID가 더 커도 생성 시각이 오래된 version은 최신으로 선택하지 않는다.
- 조회 테스트 3개는 네 version의 24가지 순열, 숫자 `9`/`10`의 시각 동률, 빈 Strategy와 부재를 검증한다.
  실제 PostgreSQL 테스트 14개는 기존 동시 저장 회귀 11개와 재조회·사용자 격리·새 append 관측 3개를
  포함하며, version/condition row와 DSL 조건 순서 보존도 확인한다. 임시 schema는 테스트가 정리한다.
- application 14개와 app 52개를 포함해 관련 테스트 83개가 실제 실행돼 통과했고 실패·오류·skip은 0개다.
  HTTP 검증은 app의 H2·실제 서버에서 상세·목록의 최신 ID 일치를 확인하며, PostgreSQL 조회 검증과
  구분한다. app에는 기존 HTTP/모듈 경계 및 실제 PostgreSQL migration 검증도 포함된다.
- 새 HTTP fixture의 기존 ID 재사용으로 생긴 간섭을 전용 ID와 자체 정리로 해소한 뒤 app 52개를
  다시 실행해 통과했다. 중간 실패 실행을 성공으로 세지 않는다.
- 계약 snapshot, 문서 snapshot 24개와 `git diff --check`가 통과했다. 로컬 환경·fixture·실행 로그는
  커밋하지 않는다. wire schema·저장 데이터·Compute 요청·계산 hash 변경은 없으며 계산 결정론 영향은 없다.

### 1d 검증 기록

- 수정 전 실제 PostgreSQL·JPA 테스트에서 두 Aggregate snapshot이 각각 version을 추가하면
  `[11, 12, 13]` 대신 `[11, 13]`만 남는 유실을 재현했다.
- command-side 조회는 기존 transaction을 필수로 요구하고, version collection을 읽기 전에 Strategy
  row를 잠근다. 저장도 같은 Root를 잠근 뒤 새 version/condition만 `persist`하며, collection 교체와
  cascade merge/delete·orphan removal 경로를 제거했다. 누락된 과거 version도 삭제하지 않는다.
- 기존 version의 정의와 Root metadata 교체, 다른 Strategy의 version ID 재사용을 거절한다.
  Aggregate도 중복 version ID 추가를 거절한다. 같은 Aggregate 재저장은 기존 row를 다시 쓰지 않는다.
- Clock의 nanosecond와 PostgreSQL의 microsecond 저장 정밀도 차이로 같은 Aggregate 재저장이 거절되는
  추가 회귀를 확인하고 비교 정밀도를 보완했다. 저장된 과거 시각이나 API 시각을 다시 쓰지 않는다.
- 관련 테스트 결과 합계는 domain 13개·application 14개·PostgreSQL Store 11개·app 51개로
  89개 통과, 실패·오류·skip 0개다. domain/application은 최초 실행 후 후속 실행에서 재사용됐고,
  PostgreSQL 11개와 app 51개는 최종 수정 후 실제 실행했다. app에는 실제 PostgreSQL migration과
  기존 HTTP/모듈 경계 검증이 포함된다.
- PostgreSQL 테스트는 실제 잠금 대기를 `pg_stat_activity`로 확인한다. 동시 정의 둘 다 보존,
  rollback 뒤 대기 writer 진행, 기존 version/condition row와 참조 보존, 다른 Root의 독립 진행,
  definition 충돌·소유권·transaction 경계 및 정밀도를 검증하고 자체 임시 schema를 정리한다.
- Windows Gradle/Kotlin daemon 소켓 오류와 metrics HTTP 테스트 3개의 `Bad address: listen` 실패는
  성공으로 세지 않았다. 기본 JVM 설정으로 app을 다시 실행해 51개 모두 통과했다.
- 첫 HTTP smoke는 Strategy 생성·version 저장 뒤 Backtest POST의 로컬 연결 오류로 중단됐다.
  해당 시도는 성공으로 세지 않으며 전용 프로세스·임시 환경 파일을 정리했다.
- 새 프로세스의 `run-api-smoke-ci.ps1` 재실행은 실제 PostgreSQL·Core↔Compute HTTP에서 Strategy 생성·
  version 정의, 정상 Backtest 완료·재실행·계산 실패·다른 사용자 조회 차단을 검증하고 통과했다.
  기동한 전용 프로세스·임시 환경 파일은 정리했고 로컬 credential·fixture·실행 로그는 커밋하지 않는다.
- 수정 전후 계약 snapshot 검사, 문서 snapshot 24개 검사와 `git diff --check`도 통과했다.
- API/schema/hash·계산 의미 변경은 없으며 결정론 영향은 없다. 배포 시 모든 Strategy 쓰기 인스턴스에
  새 저장 경로를 적용해야 동시 저장 보장을 유지한다. Mapper/Reader 전반 정리는 3a에서 이어간다.

### 1c 검증 기록

- 수정 전 실제 PostgreSQL·JPA 경로에서 lease를 새 워커가 가져간 뒤 이전 404 응답이 Run을
  `FAILED`로 바꾸는 회귀 테스트 실패를 확인했다.
- 응답 반영 transaction의 첫 단계에서 dispatch row를 잠그고 현재 상태·token·Compute job과 lease
  만료를 검증한다. 잠금을 기다리는 동안 만료될 수 있으므로 시각은 잠금 획득 뒤에 읽는다.
- 검증은 기존 application transaction을 필수로 요구하며, Run·Result·capacity·dispatch commit까지
  잠금을 유지한다. 보호된 처리 중 deadline을 지나도 다른 워커는 commit 뒤 상태를 다시 확인한다.
  Compute HTTP는 transaction 밖에서 수행한다.
- 관련 persistence/application/app 검증 105개가 통과했다. PostgreSQL lease 테스트 9개는 만료/교체된
  7가지 응답, 오래된 워커와 정상 완료의 경합, commit까지의 잠금, 잠금 대기 중 만료, 전체 rollback과
  만료/교체 및 rollback 응답의 실패 observation 미발행을 확인했다. 잠금 대기는 `pg_stat_activity`로 확인한다.
- PostgreSQL 테스트의 임시 schema와 `@Lob` 대형 객체 정리를 보완하고 lease 테스트 9개를 다시
  실행해 통과했다. 로컬 환경·fixture·실행 로그는 커밋하지 않는다.
- HTTP smoke의 첫 실행은 PowerShell 전체 스트림 기록이 Python 경고를 오류로 취급해 사전검사에서
  중단됐다. 해당 실행은 성공으로 표시하지 않으며, 오류 스트림을 보존해 재실행한다.
- 재실행한 `run-api-smoke-ci.ps1`는 실제 Core↔Compute HTTP에서 정상 실행·재실행·계산 실패·다른
  사용자 조회 차단을 검증하고 통과했다. 수정 전후 계약 snapshot과 문서 snapshot 검사도 통과했다.
- API/schema/hash 의미 변경은 없으며 결정론 영향은 없다. 배포 시 모든 polling 인스턴스에 수정 코드가
  적용돼야 이 검증 경계를 보장할 수 있다.

### 1b 검증 기록과 배포

- 수정 전 실제 PostgreSQL에서 월이 바뀌면 동시 한도가 풀리는 회귀 테스트가 실패했다.
- 수정 후 persistence/application/app 테스트 96개가 통과했다. PostgreSQL quota 테스트 9개와
  Flyway migration 테스트도 실제 실행했으며 실패·오류·skip은 0개다.
- UTC 자정에서 바뀌는 Clock으로 접수 시각과 quota month의 일치를 추가 검증하고 application
  테스트를 재실행해 통과했다. 월간 거절, 최초 동시 접수, 월을 넘는 동시 예약, 중복 해제,
  사용자 격리, 접수/종료 rollback과 기존 데이터 backfill·불명 owner 실패를 검증했다.
- Windows Gradle 소켓 오류로 시작되지 않은 실행은 성공으로 세지 않았다. 숨김 프로세스 실행으로
  테스트를 완료했다.
- 계약 snapshot 검사와 실제 Core↔Compute `run-api-smoke-ci.ps1`를 통과했다. 정상 실행·재실행·계산
  실패·다른 사용자 조회 거절을 확인했다. 로컬 credential/fixture/증적은 커밋하지 않는다.
- `run-durable-dispatch-reliability.ps1`는 기존 contract 검사의 strict-mode
  `analyticsInputSupplement` 속성 오류로 서비스 기동 전 중단됐다. 이 장애 복구 harness는 검증 완료로
  표시하지 않으며, 통과한 일반 API smoke와 구분한다.
- V11은 기존 월간 사용량과 Run/Result를 보존하며 활성 Run을 새 예약으로 복원한다. 적용 시 이전 Core의
  접수·dispatch/polling을 중지하고 혼합 버전 기동을 피해야 한다. 상세 순서와 복구 제약은
  [Backtest capacity 전환 절차](BACKTEST_CAPACITY_ROLLOUT.md)를 따른다.
- API/Compute 계약과 계산 hash 의미는 변경하지 않았으며 결정론 영향은 없다.

### 1a 검증 기록

- `TR-ACC-DELETE-006/007`의 즉시 접근 차단·삭제 scope 격리 assertion을 회귀 테스트에 적용했다.
  삭제 워커가 실행되기 전 `REQUESTED` 상태에서 기존 토큰 사용과 새 발급을 거절한다.
- 수정 전 실제 PostgreSQL에서 새 회귀 테스트 2개가 모두 실패했다. 삭제 후 토큰 사용이
  원본 스트림을 반환하는 문제를 재현했다.
- 수정 후 `:tradingreview:adapter:persistence:test --tests '*PostgresTradingReviewStoreTest'`를
  실행해 26개 통과, 실패/오류/skip 0개를 확인했다. 대상 외 Book/Account/owner의 토큰과
  정상 토큰의 single-read 동작도 검증했다.
- 수정 전후 integration 계약 snapshot 검사를 통과했다. 합성 fixture와 분리된 integration DB의
  `test-trading-normalization-runtime-contract.ps1`도 통과했다. 로컬 증적은 integration에만 보관한다.
- API/schema/hash 의미 변경은 없으며 결정론 영향은 없다. 전체 삭제 workflow의 나머지 assertion은
  4d/5b에서 검증한다.

초기 리뷰에서 전체 `gradlew test` task는 성공했다. 당시 결과는 대부분 UP-TO-DATE였으며
리포트 합계 248개, 실패 0개, skipped 1개였다. PostgreSQL Store의 이전 실행 결과도 재사용됐고
Flyway migration 테스트는 건너뛰었다. 이 기록은 후속 변경의 실제 DB 검증을 대신하지 않는다.
