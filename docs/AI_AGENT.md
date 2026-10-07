# AI_AGENT.md — 에이전트 작업 워크플로우

`AGENTS.md`를 먼저 읽었다는 전제로 작성한다. 이 문서는 "무엇을" 만들지가 아니라 "어떻게" 작업을 진행할지를 다룬다.

---

## 1. 작업 시작 전 체크리스트

새 작업(이슈/티켓)을 받으면 다음을 순서대로 확인한다.

1. **어느 레포의 작업인가?** — `core`(도메인 로직, API), `compute`(백테스트 계산, 데이터 적재), `web`(UI), `system`(문서/계약)인지 먼저 구분한다. 하나의 기능이 여러 레포에 걸치는 경우(예: 새 Condition Type 추가)가 흔하므로, 영향받는 레포를 전부 나열한 뒤 시작한다.
2. **`docs/DECISIONS.md`에 관련 ADR이 있는가?** — 있다면 그 결정을 따른다. 결정과 다르게 구현해야 할 이유가 있다면, 코드를 먼저 바꾸지 말고 ADR을 수정하는 PR을 먼저 제안한다.
3. **`docs/DOMAIN.md`의 관련 Aggregate 불변식을 확인한다.** — 특히 Strategy/BacktestRun의 상태 전이 규칙을 위반하지 않는지 확인한다.
4. **Trading Review 작업인가?** — `docs/TRADING_REVIEW.md`의 product scope, capability, provenance와 version 경계를 확인한다. Binance One-way reconstruction 구현/fixture라면 `docs/TRADING_RECONSTRUCTION.md`의 상태 전이, 보존식, tolerance와 expected result도 적용한다. 초기 Behavior Metric analytics 구현/fixture라면 `docs/TRADING_ANALYTICS.md`의 population, relation, comparison, status와 boundary expected result를 적용한다.
   artifact 저장·조회·log·삭제·backup·재처리 경계를 다루면 `docs/TRADING_DATA_LIFECYCLE.md`의 pinned
   policy, deletion generation/state와 `TR-I14`~`TR-I24`도 적용한다. Adapter/schema/reconstruction/Metric/Finding
   version 변경, 과거 데이터 재처리, lineage 또는 trend compatibility를 다루면
   `docs/TRADING_VERSIONING.md`와 `TR-I25`~`TR-I38`을 적용한다.
   Trading Review 구현, fixture 또는 E2E라면 `docs/TRADING_REVIEW_ACCEPTANCE.md`의 Scenario ID와 상태 전이,
   idempotency, outcome, evidence 및 service ownership assertion도 적용한다.
   Trading Review public/internal API, persistence, orchestration 구현이면 `docs/TRADING_REVIEW_API.md`,
   `docs/TRADING_REVIEW_PERSISTENCE.md`와 ADR-063의 upload, polling, ownership, durable dispatch, large payload와
   transaction 경계를 적용한다. 정확한 wire schema는 두 OpenAPI를 추측 없이 사용한다.
5. **Core↔Compute 경계를 넘는 변경인가?** — API 계약(`system` 레포의 OpenAPI 스펙)이 바뀌어야 한다면, 계약을 먼저 수정하고 두 레포에 각각 반영한다. 한쪽 레포만 보고 임의로 응답 필드를 추측해 구현하지 않는다.

## 2. Core (Kotlin + Spring Boot) 작업 규칙

- 패키지 구조는 `docs/ARCHITECTURE.md` §2의 모듈 경계를 따른다. `strategy`, `backtest`(오케스트레이션), `tradingreview`, `asset`, `user`, `subscription` 모듈 간 직접 참조 대신 각 모듈이 노출하는 Application Service를 통해서만 상호작용한다.
- Compute를 호출하는 코드는 **오직 `backtest`, `asset`, `tradingreview` 모듈**에만 존재한다. 각 모듈은 자신의 `adapter/out/compute/`에 클라이언트를 격리한다. `tradingreview`는 거래 정규화와 행동 분석만 위임하며, 다른 모듈(`strategy`, `user` 등)은 Compute를 직접 호출하지 않는다. Compute URL과 응답 스키마를 이 세 어댑터 밖으로 노출하지 않는다.
- Core의 백테스트 실행 엔드포인트(Web이 호출하는 public API, `openapi/core-api.yaml`의 `POST /strategy-versions/{versionId}/backtests`)는 **비동기**다. 요청 즉시 `BacktestRun(status=PENDING)`을 반환하고, 실제 계산은 `backtest` 모듈이 Compute의 내부 API(`docs/ARCHITECTURE.md` §6, `POST /backtests`)에 위임한 뒤 상태를 폴링해 갱신한다. 절대 이 엔드포인트를 동기로 만들지 않는다 (Compute 응답을 기다리며 커넥션을 오래 잡고 있지 않는다).
- DSL 관련 필드(Condition, Signal Asset 등)는 `docs/GLOSSARY.md`의 네이밍을 그대로 코드 식별자로 사용한다. 임의로 축약하거나 다른 용어로 바꾸지 않는다.
- 테스트: Aggregate 불변식(예: "Primary Signal Asset은 정확히 1개") 위반 시나리오를 반드시 실패 테스트로 커버한다.

## 3. Compute (Python + FastAPI) 작업 규칙

- **Backtest Engine**, **Market Data Ingestion**, **Trading Review**는 같은 레포 안이라도 명확히 분리된 모듈/패키지로 유지한다 (`engine/`, `ingestion/`, `trading_review/`). Ingestion 실패가 Engine에 영향을 주면 안 된다.
- 시계열 계산(Return, Change, Relative, Lag)은 `docs/DECISIONS.md` **ADR-003(4대 Temporal Rule)**을 그대로 구현한다 — 이 규칙은 이 서비스의 핵심 계약이므로 임의로 최적화하며 규칙을 변형하지 않는다. 특히 Cross-Calendar Metric Anchor Rule(**ADR-004**)을 건드리는 변경은 반드시 해당 ADR을 함께 갱신한다.
- Dataset은 항상 **Immutable Snapshot**을 참조해서 계산한다. Ingestion이 만든 최신 스냅샷을 실행 중인 백테스트가 참조하다가 중간에 갱신되는 일이 없도록 스냅샷 ID를 파라미터로 명시적으로 받는다.
- 벡터화 연산(pandas/polars)을 사용하되, Point-in-Time Correctness를 해치는 방식(예: 전체 시계열을 미리 로드해두고 미래 인덱스에 실수로 접근하는 패턴)을 피하기 위해 계산 함수는 항상 "이 시점까지 확정된 데이터"만 인자로 받도록 설계한다.
- Compute는 외부에 인증 없이 노출하지 않는다. Core만 호출하는 내부망 서비스로 취급한다.

## 4. Web (Next.js) 작업 규칙

- Core API만 호출한다(`openapi/core-api.yaml`). Compute의 존재를 프론트가 알 필요가 없다.
- 백테스트 결과 대기는 **폴링**(2~3초 간격)으로 구현한다. WebSocket 등 실시간 채널은 지금 도입하지 않는다.
- Strategy Builder는 Progressive Disclosure(Level 1 → 2 → 3) 구조를 그대로 UI 상태로 반영한다. Level 3(Relative/Lag/Cross-Market)를 처음부터 노출하지 않는다.
- 차트는 `docs/ARCHITECTURE.md` §5에 명시된 라이브러리 선택을 따른다.

## 5. PR 작성 시 확인할 것

- 변경이 `docs/DECISIONS.md`의 기존 ADR과 충돌하지 않는지 명시한다.
- Core↔Compute API 계약을 바꿨다면 `system` 레포의 OpenAPI 스펙도 같은 PR 세트에 포함한다(레포가 다르더라도 PR 설명에 서로 링크).
- Determinism에 영향을 주는 변경(§ AGENTS.md 4항)은 PR 설명에 "Determinism 영향 없음" 또는 "영향 있음 + 마이그레이션 계획"을 명시한다.

## 6. 모호할 때

스펙에 없는 엣지 케이스를 만나면, 영향 범위에 따라 다르게 대응한다.

- **계산 엔진 핵심 로직**(Temporal Rule, Signal/Lag/Execution 판정, Determinism, Missing Session 처리 등 `docs/DECISIONS.md` ADR-002~010이 다루는 영역)에 영향을 준다면 — 임의로 구현을 정하지 않는다. 우선순위 기준(아래)으로 가장 타당한 안을 스스로 도출하되, **코드를 병합하기 전에** `docs/DECISIONS.md`에 그 판단과 근거를 담은 ADR 초안을 추가하고 사람의 리뷰를 요청한다. 여기서는 잘못된 기본값이 조용히 틀린 백테스트 결과로 이어질 수 있기 때문에 신중함이 우선한다.
- **그 외 영역**(API 필드 명명 세부사항, 에러 메시지 문구, 내부 함수 분리 방식 등 정정 비용이 낮은 결정)은 아래 우선순위 기준으로 스스로 판단해 진행하고, PR 설명에 어떤 근거로 그렇게 정했는지만 남긴다. 매번 멈추고 확인을 구하지 않는다.

이 서비스의 우선순위는 **Data Correctness > Temporal Correctness > Execution Correctness > Statistical Interpretation > Research UX > Feature Count** 순이다. 트레이드오프가 생기면 이 순서를 기준으로 판단한다.

## 7. Trading Review 현재 작업 규칙

- 현재 우선순위는 `docs/ROADMAP.md`의 TR-0이며 실제 Binance Trade/Position History fixture package로 ADR-058의 입력 계약과 reconciliation을 먼저 검증한다.
- 구현과 harness fixture는 `docs/DOMAIN.md`의 `TR-I01`~`TR-I38` 중 적용되는 불변식과 expected result를 명시한다.
- provider row를 조용히 버리거나 불완전한 position episode를 완성했다고 추정하지 않는다.
- 같은 `LedgerRevision + Reconstruction/Metric/Finding versions + AnalysisConfig`는 같은 결과를 내야 한다.
- Finding은 source row까지 provenance를 제공하고, 행동과 결과 사이의 인과관계나 심리 상태를 단정하지 않는다.
- Backtest `Asset`/`Trade`와 Trading Review `Instrument`/`TradingRecord`/`ReviewUnit`은 별도 타입이다.
- ADR-058로 확정한 identity/timezone/mode/reconciliation 의미와 ADR-061의 retention/deletion 경계는
  구현에 사용하되, TR-0에 남은 fixture 검증이 끝나기 전에는 persistence/OpenAPI를 추측해 고정하지 않는다.
- ADR-059와 `docs/TRADING_RECONSTRUCTION.md`의 signed state machine, allocation, failure/status, manifest hash 규칙을 구현자 재량으로 바꾸지 않는다. Golden fixture는 scenario expected field와 TR-I07~TR-I10 보존 결과를 함께 명시한다.
- ADR-060과 `docs/TRADING_ANALYTICS.md`의 outcome, window, comparison group, capability/status 우선순위,
  sample threshold와 evidence 규칙을 구현자 재량으로 바꾸지 않는다. Analytics fixture는 28개 boundary
  scenario의 population/membership/intermediate/status/result를 별도 해석 없이 옮긴다.
- ADR-061과 `docs/TRADING_DATA_LIFECYCLE.md`의 raw TTL, evidence masking, 삭제 scope/state/SLA,
  deletion generation, log/AI/backup 경계를 설정으로 완화하거나 immutable ledger를 영구 보관 근거로 쓰지 않는다.
- ADR-062와 `docs/TRADING_VERSIONING.md`의 exact compatibility, 새 Revision/Run, idempotency, lineage,
  completed-only publication과 deterministic hash를 적용한다. 새 version 배포·결과 조회를 기존 결과의 자동
  재처리 trigger로 사용하거나 unavailable version을 가까운 version으로 대체하지 않는다.
- `docs/TRADING_REVIEW_ACCEPTANCE.md`의 Scenario ID는 Integration/PR evidence에서 그대로 사용하고,
  `FIXTURE_PLANNED`를 실제 fixture 없이 검증 완료로 표시하지 않는다. AcceptanceOutcome을 Aggregate status나
  HTTP status로 구현하지 않는다.
- ADR-063의 public Core multipart upload, 명시적 validation, Core durable dispatch→Compute polling,
  opaque terminal payload/ack와 generation/hash 검증을 callback/direct Web→Compute 흐름으로 바꾸지 않는다.
  persistence 구현은 `TRADING_REVIEW_PERSISTENCE.md`의 논리 constraint를 실제 schema/transaction으로 강제한다.
