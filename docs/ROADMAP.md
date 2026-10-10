# ROADMAP.md — Phase별 범위

에이전트는 지금 어느 Phase에 있는지 먼저 확인하고, **다음 Phase의 기능을 미리 구현하지 않는다.** 각 Phase는 이전 Phase의 종료 조건을 만족해야 다음으로 넘어간다.

**현재 우선순위(2026-09-29)**: Strategy Validation의 추가 개발은 안정된 상태에서 잠시 중단하고,
Trading Review `TR-0 → TR-1`을 먼저 검증한다. 기존 Backtest 계약과 회귀는 유지하며 두 도메인의 타입과
저장 모델을 합치지 않는다. 이 우선순위는 Strategy Track의 폐기를 의미하지 않는다.

---

## Trading Review TR-0 — Record & Reconstruction Feasibility

**성격**: 실제 Binance sample을 사용하는 기술/제품 discovery. production API보다 입력 의미론과
복원 정확성을 먼저 확정한다.

**범위**:

- 실제 Binance USDⓈ-M Trade/Position export는 read-only 비식별 aggregate 검증에만 사용하고 저장소에는
  민감 원본을 두지 않으며, 확정 계약을 재현하는 합성 Integration fixture package 확보
- 두 V1 logical/physical schema, value enum, exact-second timestamp, stable trade identity, timezone, One-way evidence 검증
- 동일 파일 재업로드, 겹치는 기간과 identity conflict의 deduplication 규칙 검증
- Linear Perpetual One-way canonical normalization PoC
- flat-to-flat FuturesPositionEpisode와 reversal allocation 복원
- Trade realized profit/fee, Position closing PNL과 자체 reconstruction의 constraint reconciliation
- `COMPLETE/LEFT_CENSORED/RIGHT_CENSORED/INCONSISTENT/UNSUPPORTED` 분류
- 초기 3개 Metric을 실제 sample로 계산
- raw artifact retention/deletion과 민감정보 logging 정책 적용
- `TR-I01`~`TR-I38` 중 실행 가능한 불변식의 정상/위반 fixture와 expected result 정의
- Import/Analysis OpenAPI 및 persistence schema 초안

**초기 Metric**:

1. `ENTRY_WITHIN_WINDOW_AFTER_LOSS`
2. `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS`
3. `WIN_LOSS_HOLDING_DURATION_DIFFERENCE`

### TR-0A — Trade Reconstruction Specification

ADR-059와 [`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md)에서 signed quantity state machine,
partial/reversal allocation, Decimal/zero, fee/PnL 보존, censored status, Position History constraint matching,
oracle/tolerance, failure taxonomy, capability와 25개 golden scenario expected result를 확정한다. 후속 harness와
Golden Dataset은 이 문서를 해석해서 규칙을 새로 만들지 않고 표의 필드와 status를 기계 판독 fixture로
옮긴다.

**TR-0A 종료 조건**:

- 같은 canonical input, pinned instrument terms와 version이 같은 manifest hash를 만든다.
- quantity, asset별 fee와 reported PnL 보존식이 partial close와 양방향 reversal에서 정의된다.
- `COMPLETE`/eligible 판정과 import rejection/episode exclusion/analysis failure 경계가 열거된다.
- 25개 합성 scenario가 input, allocation, status, capability, inclusion, exclusion과 provenance expected result를 가진다.

### TR-0C — Analytics Definition & Capability Matrix

ADR-060과 [`TRADING_ANALYTICS.md`](TRADING_ANALYTICS.md)에서 초기 세 Metric의 net-PnL outcome,
population/eligibility/exclusion, 30분 relation, exposure comparison/baseline, holding-duration aggregation,
capability/status/minimum과 evidence 계약을 확정한다. 28개 합성 boundary scenario는 후속 Integration Golden
Fixture가 별도 해석 없이 expected result로 옮길 수 있는 입력이 된다.

**TR-0C 종료 조건**:

- 같은 LedgerRevision, ReconciliationManifest, Metric Definition Version과 AnalysisConfig가 같은
  population, exclusion, scalar result와 evidence hash를 만든다.
- net outcome capability 부족, 표본 부족과 실제 값 0을 서로 다른 status/result로 표현한다.
- 동시 cross-instrument entry에 인위적 event order를 만들지 않고 reversal을 일반 re-entry와 분리한다.
- exposure의 primary direct comparison과 strict-prior baseline, holding-duration primary aggregation이
  구현자 재량 없이 고정된다.
- 모든 Metric membership과 scalar input이 allocation/canonical record/source row까지 추적된다.

### TR-0E — Privacy & Data Lifecycle Contract

ADR-061과 [`TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md)에서 데이터 등급/inventory, accepted raw
7일·rejected raw 24시간, allowlist `SourceEvidenceSnapshot`, owner 삭제 scope, 단방향 deletion state,
동시 job의 generation/tombstone, live purge와 backup 최종 제거, log/operator/AI 경계와 policy version을
확정한다. 이 단계는 storage adapter, schema, OpenAPI, worker 또는 UI를 구현하지 않는다.

**TR-0E 종료 조건**:

- 각 데이터의 owner/storage/purpose/retention clock/delete/backup/access 경계가 구현값으로 고정된다.
- raw 삭제 뒤 evidence snapshot과 재파싱 불가가 사용자에게 숨겨지지 않는다.
- raw/session/Book/Account/Member 삭제 scope와 dependent immutable Revision purge가 구분된다.
- 삭제 중 job/callback, partial failure와 restore가 tombstone/deletion generation으로 결정론적으로 처리된다.
- `TR-I14`~`TR-I24`, 9개 lifecycle failure와 20개 scenario expected result가 정의된다.

### TR-0F — Versioning & Reprocessing Policy

ADR-062와 [`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)에서 Adapter, canonical schema, instrument terms,
reconstruction/reconciliation, Metric/Finding/config version taxonomy와 compatibility, input availability,
`TradingReprocessingRun`, 변경별 새 Revision/Run, idempotency, DAG lineage, publication, historical result,
trend comparability, lifecycle 우선순위, failure와 deterministic result hash를 확정한다. 이 단계는 worker,
persistence, OpenAPI, queue, migration 실행 또는 UI를 구현하지 않는다.

**TR-0F 종료 조건**:

- raw/canonical/result availability마다 허용되는 reparse/reconstruction/analytics가 명시된다.
- version 변경별 새 `LedgerRevision`/`TradingAnalysisRun` 규칙과 24개 scenario expected result가 정의된다.
- 중복·실패·취소·삭제 경쟁이 기존 정상 결과와 latest pointer를 손상시키지 않는다.
- old implementation의 replay 불가와 retained result 조회 가능 상태를 구분한다.
- 같은 canonical source/terms/target VersionSet/config가 같은 result hash를 만들고 mismatch publication을 막는다.
- `TR-I25`~`TR-I38`과 12개 reprocessing failure 및 `NOT_COMPARABLE_VERSION` trend 상태가 정의된다.

### TR-0G — Acceptance Scenarios & State Transition Contract

[`TRADING_REVIEW_ACCEPTANCE.md`](TRADING_REVIEW_ACCEPTANCE.md)에서 Import, Reconstruction, Analysis, Evidence,
Data Lifecycle, Reprocessing, Authorization과 Normalization Job protocol의 완료 조건을 110개 stable Scenario ID로 연결한다. 다섯 상태
모델의 command/event 전이와 terminal/duplicate/retry/deletion 경쟁을 정의하되 OpenAPI와 persistence는
확정하지 않는다.

**TR-0G 종료 조건**:

- 모든 scenario가 precondition, pinned version/config, fixture 상태, state/artifact 변화, outcome,
  failure/exclusion, idempotency, evidence, invariant, service ownership와 deferred transport assertion을 가진다.
- `TR-I01`~`TR-I38`이 최소 하나의 Acceptance Scenario에 연결된다.
- 실제 0과 unavailable, import rejection과 episode exclusion, retry와 duplicate, raw deletion과 evidence,
  reprocessing failure와 기존 Review 보호가 별도 assertion이다.
- Integration harness가 Scenario ID와 Then 조건을 별도 의미 해석 없이 fixture/E2E assertion으로 옮길 수 있다.

### TR-0H — API & Persistence Boundary Contract

ADR-063, [`TRADING_REVIEW_API.md`](TRADING_REVIEW_API.md),
[`TRADING_REVIEW_PERSISTENCE.md`](TRADING_REVIEW_PERSISTENCE.md)와 두 OpenAPI에서 Web→Core/Core→Compute
operation, multipart raw upload, async polling/durable dispatch, logical Aggregate storage, idempotency,
large terminal payload, evidence, reprocessing/deletion과 error/status를 확정한다. 실제 migration, worker,
controller와 Integration E2E는 각 구현 저장소의 후속 작업이다.

**TR-0H 종료 조건**:

- 110개 Acceptance Scenario가 public/internal operation 또는 명시적 운영 control-plane assertion에 연결된다.
- Core/Compute가 같은 logical record를 동시에 소유하지 않고 terminal payload handoff/ack/purge가 정의된다.
- accepted immutable data와 mutable latest pointer, Revision membership, deterministic uniqueness가 구분된다.
- import/analysis/reprocessing/deletion의 local transaction과 distributed retry 경계가 구현 가능한 수준으로 닫힌다.
- OpenAPI parsing/ref/operation/security/error/Decimal/time/pagination 검증을 통과한다.

**TR-0 종료 조건**:

- 지원 sample의 모든 canonical row가 source row까지 추적된다.
- 복원 불일치는 원인과 제외 상태가 설명되며 조용한 보정이 없다.
- 같은 input/version/config 반복 실행이 같은 canonical result hash를 만든다.
- 수량과 통화별 fee/reported PnL allocation 보존 위반이 harness에서 탐지된다.
- 불완전 ReviewUnit, capability 부족과 실제 값 0이 서로 다른 expected result로 검증된다.
- 3개 Metric의 eligible/excluded population, 제외 사유와 비교군이 문서화된다.
- raw artifact lifecycle을 결정하고 확정된 초기 Metric 계약으로 OpenAPI-first 구현이 가능하다.
- version compatibility, 재처리 lineage와 publication 계약을 해석 없이 구현할 수 있다.
- Acceptance Scenario와 상태 전이 계약으로 위 조건, authorization, duplicate/retry와 deletion 경쟁을
  Integration assertion에 연결할 수 있다.

## Trading Review TR-1 — Review MVP

**범위**:

| 영역 | 포함 |
|---|---|
| Input | Binance USDⓈ-M Linear Perpetual, One-way, 공식 Trade History + Position History CSV 묶음 업로드 |
| Import | two-artifact session, V1 schema validation, reconciliation, immutable LedgerRevision, quality/capability report |
| Reconstruction | 유일하게 조정된 COMPLETE FuturesPositionEpisode, reversal 분리, evidence allocation |
| Outcome | Trade realized profit - same-currency fee, Position closing PNL 대조, funding 제외 명시 |
| Analytics | TR-0의 3개 Metric, sample/comparison/exclusion 공개, descriptive Finding |
| UX | Upload → Verify → Finding → ReviewUnit/Execution drill-down |
| Runtime | Core 소유 상태/장기 결과, Compute 비동기 normalization/analysis, deterministic version metadata |

**명시적 제외**: Binance API sync, Hedge, COIN-M, Spot, Options, 열린 포지션, 펀딩 포함 손익,
margin ROE, 실시간 알림, AI, BehaviorFocus, Strategy mapping, Execution Gap.

**종료 조건**:

- 사용자가 import count와 reconstruction exclusion을 확인할 수 있다.
- 모든 Finding이 supporting/comparison ReviewUnit과 source row로 drill-down된다.
- 사용성 테스트에서 사용자가 최소 한 Finding의 의미와 근거를 올바르게 설명할 수 있다.
- 사용자에게 이전에 몰랐던 행동 발견 여부와 다음 기간 재수입 의사를 직접 확인한다.
- 두 번째 Import와 변화 추적을 구현하기 전 중복/overlap 정책이 검증된다.

## Trading Review TR-2 — 반복 Review와 사용자 Focus

**범위 후보**: 반복 Import, 같은 definition으로 기간 재계산, comparable trend, 사용자가 선택하는
`BehaviorFocus`, 후속 기간 준수 측정. API read-only sync는 CSV 반복 사용성이 검증된 뒤 별도 결정한다.

## Trading Review TR-3 — 상품 확대

새 상품은 수요와 데이터 완전성이 검증된 순서로 추가한다. 후보는 Hedge Mode, COIN-M, Spot이며,
각 상품은 새 Terms/Adapter/ReconstructionPolicy/ReviewUnit과 Metric applicability를 명시해야 한다.
Options와 복합 전략은 별도 discovery 없이는 범위에 넣지 않는다.

---

## Strategy Validation Track — 현재 추가 개발 중단

### Phase 0 — Feasibility Validation

**성격**: 코드보다 검증이 우선인 스파이크 단계. 이 Phase의 산출물은 "결정"이지 "기능"이 아니다.

**범위**:

*Data*
- 데이터 벤더 확정 (US Equity/ETF, Crypto 각각) — BTCUSDT는 Binance Public Data archive를 사용하며, ETF 공급사의 상업적 이용·재배포·캐싱 권리를 확인 (ADR-014, ADR-051)
- API Rate Limit, 비용 확인
- 데이터 품질 샘플 검증

*Asset Availability & Corporate Action*
- Listing Date, First Available Date, Data Coverage 확인
- TQQQ/SOXL Reverse Split 처리 검증
- Adjusted Price Restatement 정책 확인
- Dataset Snapshot 저장 가능 여부(라이선스) 확인

*Calendar & Temporal Semantics*
- Market Calendar(CRYPTO_UTC / US_EQUITY) 정의, Holiday Calendar 확보
- Cross-Calendar Metric Anchor Rule(ADR-004) 실제 데이터로 검증
- Primary Signal Asset 결측 시나리오 검증
- Missing Session Fail-fast 정책 검증

*Strategy DSL*
- 실제 투자 가설 30~50개 수집 → DSL로 표현, **80% 이상 표현 가능**이 목표
- 표현 불가능한 가설 분류 (Lookback/Lag/Relative/Multi-asset/Portfolio 등)

*Competition*
- 경쟁 제품(TradingView, QuantConnect, Composer, Portfolio123, 국내 퀀트 서비스) 직접 사용, 동일 가설 구현 비교

*UX*
- 사용자는 자신이 사용하는 증권사·거래소 등 외부 차트에서 시장을 관찰하고, RefInvest에서는
  전략 템플릿·조건 빌더로 가설을 정의해 백테스트한다(ADR-049).
- Strategy Builder Level 1~3 프로토타입으로 TTFB(Time to First Backtest) 예비 측정

*Compliance*
- 유사투자자문업 관련성 등 금융규제 예비 검토 (정식 Legal Review는 `docs/DECISIONS.md` ADR-011 — 유료 플랜 출시 전 필수)

**종료 조건**: 데이터 벤더 확정, DSL 표현 가능 비율 80% 이상 확인, Phase 1 착수 가능 상태.

**DSL Test Cases** (Cross-Calendar/Cross-Market 검증용):

```text
1. 단일 자산 조건 (QQQ.return(5) < -7% → BUY QQQ)
2. Signal Asset ≠ Execution Asset (QQQ 조건 → TQQQ 매수)
3. AND 조건 (QQQ + SPY → TQQQ 매수)
4. Signal Asset이 조건에 직접 등장하지 않는 경우 (BTC Signal, QQQ 조건 → 허용)
5. Cross-Market 진입 (BTC Signal → TQQQ Execution)
6. BTC 주말 신호 → 월요일 TQQQ 체결
7. QQQ 예상 세션 결측 → Fail-fast
8. BTC 예상 세션 결측 → Fail-fast
9. BTC 토요일 Relative 신호 (BTC.return(7) > QQQ.return(7)) → QQQ는 가장 최근 완료 세션(금) 기준
10. Relative + Lag → Primary Signal Asset(BTC) 기준으로 세션 카운트
11. Multiple Signal References + Lag → Primary Signal Asset Calendar만 기준
12. Duplicate Entry → Ignore
13. Zero Trades → 정상 완료 + Empty State
14. Low Sample → Warning
15. Reverse Split → 과거 가격 정확히 조정
16. Dataset Version 변경 후에도 기존 Backtest 결과 재현 가능
```

---

### Phase 1 — Core MVP

**성격**: 실제 서비스 코드 작성이 시작되는 단계. `docs/USECASES.md`에 정의된 Command/Query가 이 Phase의 범위다.

**범위**:

| 영역 | 포함 |
|---|---|
| Data | 5개 Asset(ADR-001, ADR-050) Daily OHLC, Calendar, Corporate Action, Dataset Snapshot |
| Strategy | Primary Signal Asset, Execution Asset, Simple/Lookback/Change/Relative Condition, AND/OR, Lag, Time-based Exit, Long Only, Single Position, Duplicate Entry Ignore |
| Backtest | Next Available Session 체결, Open 기준가, 고정 Fee/Slippage, Point-in-Time Validation, Missing Session Fail-fast, Determinism |
| Result | Equity Curve, Drawdown, Trade Table/Timeline, Return/CAGR/Sharpe/MDD/Win Rate, Benchmark, Low Sample Warning, Zero Trade Empty State, Data Integrity 표시 |
| UX | Strategy Builder(Progressive Disclosure Level 1~3), 전략 템플릿, 백테스트 결과 해석. 사용자용 원시 가격 차트·Data Explorer·원시 데이터 다운로드는 제외(ADR-049) |

**명시적 제외**: Condition-based Exit, Short, Multi-position, Strategy 비교, 자연어 입력, AI 전 영역.

**종료 조건**: 사용자 테스트에서 TTFB Simple ≤ 3분 / Advanced ≤ 5분 확인, Backtest 결과가 결정론적으로 재현됨을 자동 테스트로 검증. 측정 절차와 기록 양식은 [`TTFB_MEASUREMENT.md`](TTFB_MEASUREMENT.md)를 따른다.

---

### Phase 2 — Product Validation

**성격**: 기능 추가보다 계측·실험이 중심. 코드 변경은 대부분 분석/실험을 위한 계측(instrumentation)이다.

**범위**: Second Backtest Rate, Level Transition Rate, Unsupported Hypothesis Rate 등 핵심 지표 계측. 가격 실험(Free/Pro 구분 확정). 리텐션 측정.

**종료 조건**: Second Backtest Rate가 유의미한 수준으로 관찰됨 — 이게 낮으면 Phase 1로 돌아가 UX/DSL을 재검토한다.

---

### Phase 3 — Research Expansion

**범위**: Correlation, Conditional Return, Lead-Lag, Market Regime. Data Explorer의 공개 여부와
데이터 벤더 라이선스를 재검토한 뒤, 백테스트 이전 단계에서 더 많은 가설을 발견할 수 있도록
Data Explorer를 도입·확장한다.

---

### Phase 4 — Robust Validation

**범위**: Out-of-Sample Test, Walk-forward Validation, Parameter Sensitivity, Overfitting Detection, Monte Carlo Simulation. "과거에 우연히 잘 맞은 전략인지"를 검증하는 기능군.

---

### Phase 5 — Portfolio & Commercialization

**범위**: Multi-position, Asset Allocation, Rebalancing, Position Sizing, Condition-based Exit(ADR-008 재검토), 구독/결제 정식 도입, AI Research Interface(자연어 → DSL, 검증된 엔진 결과를 AI가 설명 — ADR-015 원칙 유지).

**주의**: 이 Phase에서도 AI는 직접 수치를 계산하지 않는다(ADR-015).
