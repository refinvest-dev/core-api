# DOMAIN.md — 도메인 모델

Core와 Compute가 각각 소유하는 Aggregate를 구분한다. 소유하지 않는 서비스는 해당 데이터를 **읽기 전용**으로만 참조한다.

---

## 1. Core가 소유하는 Aggregate

> 아래 각 Aggregate의 `id`는 Snowflake 기반 typed ID를 사용한다(`docs/DECISIONS.md` ADR-018).
> 생성 방식 자체는 도메인이 알지 않으며, feature별 outbound port로 분리한다 — 구현 상세는
> `core-api/AGENTS.md` §4 "Identifiers" 참고.

### 1.1 Strategy (Aggregate Root)

사용자가 정의한 투자 가설의 컨테이너. 여러 개의 불변 `StrategyVersion`을 가진다(ADR-013).

```text
Strategy
├── id
├── ownerId (User)
├── name
└── versions: List<StrategyVersion>
```

### 1.2 StrategyVersion (Entity, Immutable)

```text
StrategyVersion
├── id
├── strategyId
├── createdAt
├── primarySignalAsset: AssetSymbol        # ADR-002: 항상 명시적
├── conditions: List<Condition>            # 하나의 Condition Group (AND/OR만, 중첩 없음)
├── executionAsset: AssetSymbol
├── lag: SignalSessions                    # Primary Signal Asset 기준 (ADR-003)
├── exit: TimeBasedExit(holdingSignalSessions: Int)  # ADR-008: MVP는 이 형태만 허용
└── positionPolicy: { longOnly: true, singlePosition: true, duplicateEntry: IGNORE }  # ADR-009
```

**불변식**:
- `primarySignalAsset`은 정확히 1개, null 불가.
- `primarySignalAsset`은 MVP Asset Universe(ADR-001, ADR-050) 5종(`QQQ`, `SPY`, `TQQQ`, `SOXL`, `BTCUSDT`) 중 하나여야 한다.
- `executionAsset`은 MVP Asset Universe의 5종(`QQQ`, `SPY`, `TQQQ`, `SOXL`, `BTCUSDT`) 중 하나여야 한다.
- `conditions`는 최소 1개 이상.
- `conditions` 내 각 Operand의 Asset이 MVP Asset Universe(ADR-001)에 속해야 한다.
- `lag >= 0`, `exit.holdingSignalSessions > 0`, `MetricReference.window`가 존재하는 경우(`RETURN`/`CHANGE`) `window > 0`.
- 생성된 이후 `conditions`, `primarySignalAsset`, `executionAsset`, `lag`, `exit` 은 수정 불가 — 변경이 필요하면 새 `StrategyVersion`을 만든다.

```text
Condition
├── operator: LT | GT | LTE | GTE
├── logicalCombinator: AND | OR | null      # 두 번째 이후 Condition에만 존재
├── operandA: MetricReference
└── operandB: MetricReference | LiteralValue

MetricReference
├── asset: AssetSymbol
├── metric: SIMPLE | RETURN | CHANGE
└── window: Int | null                      # SIMPLE(Simple Comparison)은 window 없음
```

**예시** — "QQQ가 5일간 7% 이상 하락하고 SPY가 5일간 5% 이상 하락하면, 3 Signal Session 후 TQQQ를 매수해 5 Signal Session 보유":

```text
StrategyVersion {
    primarySignalAsset: QQQ
    conditions: [
        { operandA: {asset: QQQ, metric: RETURN, window: 5}, operator: LT, operandB: -0.07 },
        { logicalCombinator: AND, operandA: {asset: SPY, metric: RETURN, window: 5}, operator: LT, operandB: -0.05 }
    ]
    lag: 3
    executionAsset: TQQQ
    exit: { holdingSignalSessions: 5 }
}
```

Web은 이 구조를 그대로 자연어 문장(Strategy Preview)으로 변환해 사용자에게 보여준다 — DSL과 문장이 항상 1:1 대응해야 한다(`PreviewStrategy` Use Case, `docs/USECASES.md`).

### 1.3 BacktestRun (Aggregate Root)

하나의 백테스트 실행 요청과 상태를 추적한다.

```text
BacktestRun
├── id
├── strategyVersionId
├── status: PENDING | RUNNING | COMPLETED | FAILED
├── requestedPeriod: { start, end }
├── actualPeriod: { start, end }            # Asset Availability 교집합 반영 후 (ADR-001 관련)
├── feeModel: { commission: Percent, slippage: Percent }
├── datasetSnapshotId                       # Compute가 실행 시점에 사용한 스냅샷 참조
├── engineVersion
├── createdAt
├── failureReason: String | null            # Fail-fast(ADR-005)로 중단된 경우
└── errorCode: FatalErrorCode | null        # Compute가 구조화한 terminal failure code
```

**불변식**:
- `status`가 `COMPLETED`가 되려면 `BacktestResult`가 반드시 함께 존재해야 한다.
- `status`가 `FAILED`이면 `failureReason`이 필수. Compute가 구조화된 fatal failure를 반환한 경우
  `errorCode`도 함께 보존한다. Compute 접수 전 영구 거절 또는 runtime 상태 유실처럼 Compute
  error code가 없는 실패에서는 `errorCode`가 `null`일 수 있다.
- 상태 전이: `PENDING → RUNNING → (COMPLETED | FAILED)` 또는 Compute가 job을 접수하기 전에 영구적으로 거절했거나 acceptance 뒤 실행 시작을 관측하기 전에 runtime 상태를 잃은 경우의 `PENDING → FAILED`. 역방향 전이 없음. transport 재시도는 같은 `BacktestRun`의 durable dispatch를 사용하며, 사용자가 새로 실행하면 새 `BacktestRun`을 생성한다.
- `datasetSnapshotId`, `engineVersion`은 **Compute가 실행을 시작한 이후에만 채워진다** — `PENDING` 상태에서는 `null`이다. Core가 요청 시점에 스냅샷을 미리 지정하지 않고, Compute가 실행 시점에 선택한 값을 응답으로 돌려받아 기록한다.
- Compute job 접수 전 영구 거절 또는 acceptance 뒤 runtime 상태 유실로 인한 `FAILED`는 `failureReason`만 필수이며 `actualPeriod`, `datasetSnapshotId`, `engineVersion`은 `null`일 수 있다. Compute가 `RUNNING` 이후 실패한 `FAILED`는 실행 metadata를 모두 가진다.

### 1.4 BacktestResult (Entity, BacktestRun에 종속)

Compute가 계산한 결과를 Core가 영속화한 읽기 모델. Compute는 이 데이터를 직접 저장하지 않는다 — 계산해서 Core에 반환하면 Core가 소유권을 갖는다.

```text
BacktestResult
├── backtestRunId
├── metrics: { totalReturn?, cagr?, mdd?, sharpe?, winRate?, tradeCount, avgTradeReturn?, avgHoldingPeriod?, profitFactor? }
├── equityCurve: List<{ date, value }>          # Execution Asset session별 Strategy portfolio index, 시작값 1
├── trades: List<Trade>
├── benchmark: { primary: BuyAndHoldResult, secondaryReference: BuyAndHoldResult | null }  # ADR-007, ADR-052
├── signalExecutionMarketRelation: SAME_MARKET | CROSS_MARKET
├── signalExecutionDelay: { median, max, distribution }  # 각 값은 시간(hours) 단위, ADR-048
├── sampleSizeWarning: NONE | LOW | ZERO    # ADR-006
└── dataIntegrityStatus: { datasetSnapshotId, corporateActionsApplied, pointInTimeValidationPassed }  # datasetSnapshotId는 BacktestRun.datasetSnapshotId와 동일 값 (필드명 통일)

BuyAndHoldResult
├── asset
├── equityCurve: List<{ date, value }>          # 해당 Asset session별 Close-to-Close portfolio index, 시작값 1
└── totalReturn, cagr?, mdd

Trade
├── signalTime, entryTime, entryPrice
├── exitTime, exitPrice
├── returnPct
└── holdingPeriod
```

`sampleSizeWarning = ZERO`이면 `tradeCount`를 제외한 전략 성과 지표는 계산 불가능한 값으로 `null`이다. Core와 Web은 이를 숫자 `0`으로 대체하지 않고 Empty State로 표시한다(ADR-006).
`signalExecutionDelay.distribution`은 각 Trade의 `entryTime - signalTime`을 시간(hours) 단위로 기록한다. `median`과 `max`는 이 분포에서 계산하며, 무거래 결과는 빈 분포와 `median = max = 0`을 사용한다(ADR-048).
`signalExecutionMarketRelation`은 Primary Signal Asset과 Execution Asset의 calendar가 같으면 `SAME_MARKET`, 다르면 `CROSS_MARKET`이다. Timeline은 `trades`의 signal/entry/exit 시각과 이 값을 사용해 신호와 체결의 시장 관계를 표시한다. Drawdown은 Strategy와 Benchmark 각각의 `equityCurve`에서 `value / 해당 시점까지의 최고 value - 1`로 파생하며, 원시 가격 series나 별도 drawdown persistence를 만들지 않는다(ADR-052).

### 1.5 Member / Auth / Subscription

```text
Member
├── id (MemberId, RefInvest Snowflake ID)
├── role: MEMBER | ADMIN
└── createdAt

SocialIdentity
├── provider: KAKAO | NAVER | GOOGLE
├── providerSubject
└── memberId

RefreshSession
├── jti
├── memberId
├── familyId
├── tokenFingerprint
├── issuedAt, expiresAt
├── revokedAt
└── replacedByJti
```

- `MemberId`는 RefInvest 내부 식별자이며 provider subject/email과 분리한다. Strategy의 `ownerId`는
  `MemberId`를 사용한다.
- `(provider, providerSubject)`는 유일하다. email만으로 Member를 식별하거나 provider가 다른 identity를
  자동 병합하지 않는다. 하나의 Member는 장래에 여러 SocialIdentity를 가질 수 있다.
- provider access/refresh token은 RefInvest 인증 수단이 아니며 현재 제품 요구상 영속화하지 않는다.
- RefreshSession은 raw refresh credential을 저장하지 않는다. 회전되었거나 폐기된 credential의 재사용은
  replay로 간주해 해당 family의 활성 credential을 폐기한다.
- role의 정본은 RefInvest DB다. provider payload, email/domain, frontend 입력으로 ADMIN을 부여하지 않는다.

`Subscription.tier: FREE | PRO`. Free/Pro 차이는 사용량·실행 범위 제한이며 별도 가격·결제 도메인 로직은 없다 — 결제 연동은 Phase 5다.

| 정책 | FREE | PRO |
|---|---:|---:|
| 월간 Backtest 정상 접수 횟수 | 30회 | 500회 |
| 최대 동시 실행 수 | 1회 | 3회 |
| 최대 요청 기간 | 365일 | 3,650일 |
| Backtest 허용 Asset | `QQQ`, `SPY`, `BTCUSDT` | MVP Asset Universe 전체 |
| Strategy/StrategyVersion 저장 | 제공 | 제공 |
| Strategy 비교, 결과 Export | MVP 미제공 (향후 PRO 전용) | MVP 미제공 (향후 제공) |

- MVP Asset Universe는 ADR-001과 ADR-050의 `QQQ`, `SPY`, `TQQQ`, `SOXL`, `BTCUSDT`를 유지한다. 이 정책을 위해 Asset을 추가하지 않는다.
- Strategy/StrategyVersion 저장은 FREE와 PRO 모두 허용한다. Backtest 실행은 저장된 StrategyVersion을 참조하므로, 저장 자체를 FREE entitlement로 제한하지 않는다.
- Strategy 비교와 결과 Export는 Phase 1 범위 밖이다. 해당 Use Case를 도입할 때 FREE에는 허용하지 않고 PRO entitlement로 별도 적용한다. 현재 `GetUsage`는 이 미구현 기능의 enablement를 반환하지 않는다.
- Asset entitlement는 **Backtest 실행**에만 적용한다. `StrategyVersion` 정의·저장 시점에는 Plan entitlement를 검사하지 않는다. 실행 시 primarySignalAsset, 모든 Condition이 참조하는 Asset, executionAsset이 현재 tier에 모두 허용되어야 한다.
- 요청 기간은 `Period.start`와 `Period.end`를 모두 포함한 UTC calendar day 수(`end - start + 1`)로 판정한다.
- quota month는 client timezone과 무관한 UTC calendar month다. `BacktestRun(PENDING)`이 정상 접수될 때 월간 1회를 소비하며, 이후 Compute 실패에도 자동 환불하지 않는다. 거절된 요청은 quota를 소비하지 않는다.
- 월간 quota와 동시 실행 capacity는 concurrent request에서도 한도를 넘지 않도록 원자적으로 예약한다. `PENDING`과 `RUNNING`이 동시 실행 수를 점유하며, terminal 상태로 전이할 때만 동시 실행 reservation을 해제한다.

`GetUsage` Use Case(`docs/USECASES.md`)는 UTC 기준 이번 달 사용량과 현재 tier의 한도를 조회한다.

---

## 2. Compute가 소유하는 참조 데이터 (Reference Data)

Core는 이 데이터를 **읽기 전용**으로만 참조한다. 쓰기는 Compute의 Ingestion 파이프라인만 수행한다.

### 2.1 Asset

```text
Asset
├── symbol
├── calendar: US_EQUITY | CRYPTO_UTC        # ADR-003. 시장 구분은 이 값으로 충분 (US_EQUITY↔주식/ETF, CRYPTO_UTC↔크립토), 별도 market 필드 없음
├── listingDate
├── dataAvailability: { firstDate, lastDate }
└── corporateActions: List<CorporateAction>

CorporateAction
├── type: SPLIT | REVERSE_SPLIT | DIVIDEND
├── effectiveDate
└── ratio | amount
```

### 2.2 DatasetSnapshot (Immutable)

```text
DatasetSnapshot
├── id (version)
├── createdAt
├── source: VendorName                         # BTCUSDT: BINANCE_PUBLIC_DATA
├── sourceArtifacts: List<{uri, sha256, retrievedAt}>
├── coverage: { assets: List<AssetSymbol>, start, end }
├── adjustmentPolicy
└── storagePath                             # immutable artifact prefix(S3 또는 로컬 filesystem)
```

**불변식**: 한번 생성된 Snapshot은 절대 수정하지 않는다. 새 데이터가 들어오거나 source archive의
SHA-256이 바뀌면 새 Snapshot을 생성한다. `sourceArtifacts`는 해당 Snapshot을 만들 때 실제로 검증한
source archive의 provenance이며, Snapshot 생성 후 변경하지 않는다(ADR-010, ADR-051). 현재 artifact
contract는 `manifest.json`, calendar JSON, Daily Bar NDJSON과 선택적 reference-close NDJSON이며,
production storage는 S3를 사용한다. 로컬 개발에서는 같은 contract를 filesystem에 저장할 수 있다.

---

## 3. Core ↔ Compute 협업 흐름

```text
1. Core: StrategyVersion 확정
2. Core: BacktestRun(PENDING) 생성 → Compute에 계산 요청 (strategyVersion 전체 payload 전달)
3. Compute: 최신 DatasetSnapshot 선택 (또는 요청에 명시된 snapshot 사용)
4. Compute: Signal Evaluation → Lag → Execution → Position → Metrics 계산 (docs/ARCHITECTURE.md §3 파이프라인)
5. Compute: BacktestResult payload를 Core에 반환 (자체 저장 안 함)
6. Core: BacktestRun.status = COMPLETED, BacktestResult 영속화
```

Compute는 `Strategy`, `User`, `Subscription`을 전혀 모른다. 요청받은 StrategyVersion 내용과 파라미터만으로
계산하며, 실행 중에는 PostgreSQL durable job/lease와 terminal payload를 제한된 기간 보관한다. 장기 제품
결과와 사용자 도메인 상태는 계속 Core가 소유한다(ADR-041).

---

## 4. Trading Review 도메인

제품 의미론과 확장 경계는 [`TRADING_REVIEW.md`](TRADING_REVIEW.md), Binance One-way 복원 계산의 상세
정본은 [`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md), 초기 Behavior Metric 계산 정본은
[`TRADING_ANALYTICS.md`](TRADING_ANALYTICS.md), 데이터 보관·삭제 정본은
[`TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md), version compatibility·재처리·lineage 정본은
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md), 사용자 관점 상태 전이와 완료 조건은
[`TRADING_REVIEW_ACCEPTANCE.md`](TRADING_REVIEW_ACCEPTANCE.md)다. Backtest의
`AssetSymbol`, `Trade`, `DatasetSnapshot`과 아래 타입을 공유하지 않는다.

### 4.1 Core Aggregate Root

```text
TradingAccount
└── 사용자가 선언한 venue account container

TradingBook
└── productFamily + positionMode + settlementAsset가 같은 독립 복원 경계

TradingImportSession
├── TRADE_HISTORY + POSITION_HISTORY source artifacts
└── RECEIVED → VALIDATING → ACCEPTED | REJECTED

LedgerRevision (Immutable)
└── accepted canonical TradingRecord, versioned ReconciliationManifest, contentHash, capability, provenance

TradingAnalysisRun
└── PENDING → RUNNING → COMPLETED | FAILED

TradingReprocessingRun
└── PENDING → VALIDATING_INPUT → RUNNING → COMPLETED | FAILED | CANCELLED

DeletionRequest
└── REQUESTED → CANCELLING_JOBS → PURGING_PRIMARY → PURGING_OBJECTS → COMPLETED | FAILED
```

각 상태의 command/event precondition, terminal callback, retry, deletion 경쟁과 artifact retention 전이는
[`TRADING_REVIEW_ACCEPTANCE.md` §3](TRADING_REVIEW_ACCEPTANCE.md#3-state-transition-contract)를 따른다.
`AcceptanceOutcome`은 이 Aggregate status들과 별도인 scenario 판정이다.

현재 MVP가 허용하는 조합은 `BINANCE + LINEAR_PERPETUAL + ONE_WAY + USDT`다. 이는 제품 범위이며
아래의 장기 도메인 불변식과 구분한다.

**Trading Review 불변식**:

- `TR-I01 Ownership`: Account에서 source evidence까지 직접 또는 상위 Aggregate로 해석되는 소유자는
  일치하며 다른 사용자의 Aggregate나 evidence와 결합하지 않는다.
- `TR-I02 Homogeneous Book`: 하나의 Book에는 같은 venue/product family/position mode/settlement와
  ReconstructionPolicy를 적용할 수 있는 record만 포함한다.
- `TR-I03 Atomic Import`: TradingImportSession은 두 필수 artifact를 함께 검증해 전체가 `ACCEPTED` 또는
  `REJECTED`된다. 분석 의미가 있는 미해석
  행을 버리고 성공시키지 않으며, 알려진 비거래 행을 무시했다면 종류와 개수를 기록한다. 두 artifact의
  coverage는 독립 계산하며 min/max만으로 source completeness를 단정하지 않는다.
- `TR-I04 Source Provenance`: 모든 canonical record와 derived result는 artifact/source row/source record
  key로 이어지는 provenance를 보존한다.
- `TR-I05 Immutable Accepted Data`: accepted `TradingRecord`, `LedgerRevision`, pinned instrument terms와
  완료된 AnalysisResult를 수정하지 않는다. 보정과 재처리는 새 Revision/Run을 만든다.
- `TR-I06 Pinned Ledger Input`: LedgerRevision은 record 집합, content hash, import, instrument terms,
  normalization/reconstruction version, role별 artifact coverage, ReconciliationManifest, capability와 quality를
  완전히 고정한다.
- `TR-I07 Quantity Conservation`: 각 Execution 수량은 PositionAllocation에 누락·중복 없이 보존된다.
  Reversal은 하나의 execution을 서로 겹치지 않는 close/open quantity로 분할할 수 있다.
- `TR-I08 Cash-flow Conservation`: 배분된 fee와 venue-reported PnL의 합은 연결된 canonical record와
  asset별로 일치한다. 통화가 다르면 고정된 valuation 근거 없이 합산하지 않는다.
- `TR-I09 No Completion by Assumption`: 불완전하거나 모순되거나 지원하지 않는 ReviewUnit을 추정으로
  `COMPLETE`로 승격하지 않으며 결과와 제외 사유를 보존한다. summary가 없는 complete Trade episode도
  정상 reconciliation이나 0건으로 바꾸지 않는다.
- `TR-I10 Unavailable Is Not Zero`: capability 부족, 적용 불가, 표본 부족을 실제 값 `0`과 구분한다.
- `TR-I11 Deterministic Analysis`: AnalysisRun은 정확히 하나의 LedgerRevision과 version/config 묶음을
  고정하며 같은 입력은 같은 canonical result를 만든다.
- `TR-I12 Complete Result and Evidence`: `COMPLETED` Run은 quality report, 전체 eligible/excluded 모집단과
  사유, Metric 상태 및 evidence chain을 함께 가진다.
- `TR-I13 Descriptive Semantics`: Observation/Finding과 제품 문구는 관찰된 행동과 결과만 표현하고 심리,
  성격, 인과관계나 투자 지시를 단정하지 않는다.
- `TR-I14 Deletion Scope Isolation`: 삭제는 요청 owner/scope에 속한 데이터만 대상으로 하며 같은 hash나
  다른 owner의 artifact/evidence를 결합하거나 삭제하지 않는다.
- `TR-I15 Immediate Revocation`: 삭제 요청 commit 뒤 해당 scope는 정상 API, signed access, 신규 job과
  retry에서 즉시 접근 불가다.
- `TR-I16 Stale Result Rejection`: tombstone의 deletion generation보다 오래된 Compute 결과는 저장하지 않는다.
- `TR-I17 Honest Retained Evidence`: raw TTL 뒤 evidence는 versioned allowlist snapshot임을 표시하고 원본
  row라고 표현하지 않는다.
- `TR-I18 Sensitive Observability Boundary`: raw UID, source row, Symbol과 거래값을 operational log/metric
  label에 기록하지 않는다.
- `TR-I19 Truthful Deletion Status`: 일부 purge나 terminal failure를 `DELETED`/`COMPLETED`로 표시하지 않는다.
- `TR-I20 Restore Non-Resurrection`: backup restore는 deletion ledger replay와 검증 전 서비스를 열지 않으며
  삭제된 데이터를 재활성화하지 않는다.
- `TR-I21 Pinned Data Policy`: `dataPolicyVersion`과 retention/masking/deletion 필드 없이 import를
  `ACCEPTED`로 만들지 않는다.
- `TR-I22 Immutable Purge Semantics`: accepted Ledger 내용을 수정해 source를 빼지 않고 종속 Revision과
  결과 전체의 접근 제거·purge로 삭제한다.
- `TR-I23 Storage-State Consistency`: `DELETED` artifact에는 readable live object나 usable key가 없으며
  남아 있으면 pending 또는 failed다.
- `TR-I24 Requested-Scope Equivalence`: 사용자에게 약속한 deletion scope와 실제 purge checklist가 정확히
  일치한다.
- `TR-I25 Immutable Reprocessing`: 재처리는 accepted `TradingImportSession`이나 기존 Revision/Run/Result를
  다시 열거나 수정하지 않고 새 `LedgerRevision` 또는 `TradingAnalysisRun`을 만든다.
- `TR-I26 Idempotent Reprocessing`: 같은 reprocessing idempotency key의 성공 결과를 중복 생성하지 않으며
  진행 중·완료 요청은 기존 run/result를 반환한다.
- `TR-I27 Failure Isolation`: 실패하거나 취소된 재처리는 기존 정상 Review와 latest pointer를 변경하지 않는다.
- `TR-I28 Source Availability Honesty`: raw가 없으면 Adapter reparse를 성공시키지 않고 evidence snapshot을
  raw 대체물로 사용하지 않는다.
- `TR-I29 Exact Version Compatibility`: 호환되지 않거나 unavailable한 version을 가장 가까운 version으로
  대체하거나 암묵 변환하지 않는다.
- `TR-I30 Deletion Precedence`: 삭제 요청 commit 뒤 재처리를 admit/retry하지 않고 실행 중 output과 늦은
  callback을 저장하지 않는다.
- `TR-I31 Complete Version Lineage`: 새 Revision/Result는 parent, source import, reprocessing run/type,
  source/target VersionSet, initiator, input availability와 result hash를 추적할 수 있다.
- `TR-I32 Trend Definition Equality`: Metric definition, period 외 config, outcome/comparison, timezone,
  capability와 ReviewUnit schema가 다른 결과를 같은 trend로 연결하지 않는다.
- `TR-I33 Deterministic Reprocessing Result`: 같은 canonical source, instrument terms, target VersionSet과
  AnalysisConfig는 같은 canonical result hash를 만든다. 불일치는 publish하지 않는다.
- `TR-I34 Readability Separate From Replayability`: old implementation availability와 retained result 조회
  가능성을 별도 상태로 표현한다.
- `TR-I35 Verified Reupload Lineage`: 재업로드를 기존 lineage에 연결하기 전에 owner, Book, source identity와
  overlap/conflict를 검증한다.
- `TR-I36 Completed-only Publication`: latest pointer는 lineage, generation과 hash 검증을 마친 `COMPLETED`
  Revision/Result만 가리키며 부분 결과를 노출하지 않는다.
- `TR-I37 Lineage DAG Integrity`: lineage parent는 같은 owner/Book에 속하고 cycle이 없으며 여러 source를
  단일 parent로 축소하지 않는다.
- `TR-I38 Version Metadata Retention`: 실행 implementation이 unavailable이어도 lifecycle상 보존되는 결과에는
  당시 source/target VersionSet, config와 lineage metadata가 남는다.

### 4.2 Canonical Record와 Analysis Result

```text
TradingRecord
├── ExecutionRecord
├── FeeRecord
├── VenueReportedPnlRecord
├── VenuePositionSummaryRecord
└── Funding/Liquidation/Transfer/PositionSnapshot Record (향후)

ReviewUnit
└── FuturesPositionEpisode (MVP), 상품별 구현은 ReconstructionPolicy로 분리

BehaviorObservation
BehaviorMetric
BehaviorFinding
```

MVP는 `COMPLETE`이면서 Position History와 `EXACT` 또는 `WITHIN_ROUNDING_TOLERANCE`로 유일하게
조정된 FuturesPositionEpisode만 분석한다. 불완전·모순·모호·unsupported 결과를 임의로 보정하지 않는다.
Binance source symbol은 Unicode를 포함할 수 있는 lossless opaque identifier로 보존하고 exact pinned
`InstrumentDescriptor`로 지원 여부를 판정한다. Trade와 Position source timestamp는 모두 second precision
local value를 pinned source timezone으로 canonicalize한 exact UTC instant다.
Funding은 제외하고, 같은 정산 통화에서 `netTradingPnl = sum(realizedProfit) - sum(sourceFeeAmount)`로
표시한다. 세부 Binance source 계약은 ADR-058, 복원/조정 규칙은 ADR-059와
[`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md)를 따른다.

초기 Metric은 `ENTRY_WITHIN_WINDOW_AFTER_LOSS`, `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS`,
`WIN_LOSS_HOLDING_DURATION_DIFFERENCE`다. 각 Metric은 population/eligible/excluded와 이유,
subject/comparison membership, capability snapshot, scalar input과 source row evidence를 가진다. 상태는
`AVAILABLE | INSUFFICIENT_SAMPLE | MISSING_CAPABILITY | NOT_APPLICABLE`이며 표본 부족이나 capability 누락을
실제 값 0으로 바꾸지 않는다. 상세 formula와 minimum은 `TRADING_ANALYTICS.md`를 따른다.

### 4.3 Trading Review Determinism

```text
LedgerRevision
+ ReconstructionPolicyVersion
+ ReconciliationManifestHash
+ MetricDefinitionVersion
+ FindingRuleSetVersion
+ AnalysisConfig
= TradingAnalysisResult
```

Metric이 요구하는 data capability가 없으면 `0`을 반환하지 않고 `MISSING_CAPABILITY` 또는
`NOT_APPLICABLE`로 표현한다. 기간별 추세는 같은 definition/version과 config로 재계산한 결과만 비교한다.
재처리의 전체 VersionSet, compatibility, idempotency와 단계별 canonical result hash는
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)를 따른다.
공개/내부 API와 논리 저장 관계(별도 `LedgerRevisionRecord`, durable dispatch, latest pointer와 deletion
transaction)는 [`TRADING_REVIEW_API.md`](TRADING_REVIEW_API.md)와
[`TRADING_REVIEW_PERSISTENCE.md`](TRADING_REVIEW_PERSISTENCE.md)를 따른다.
