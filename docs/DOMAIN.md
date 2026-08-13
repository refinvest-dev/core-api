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
- `primarySignalAsset`은 MVP Asset Universe(ADR-001) 6종(`QQQ`, `SPY`, `TQQQ`, `SOXL`, `BTCUSDT`, `VIX`) 중 하나여야 한다 — `VIX`도 Signal Asset으로는 허용된다(ADR-002).
- `executionAsset`은 MVP Asset Universe 중 **Execution Asset 하위 집합**(`QQQ`, `SPY`, `TQQQ`, `SOXL`, `BTCUSDT`)에만 속해야 한다 — `VIX`는 Execution Asset으로 지정할 수 없다(ADR-001).
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

**예시** — "QQQ가 5일간 7% 이상 하락하고 VIX가 5일간 20% 이상 상승하면, 3 Signal Session 후 TQQQ를 매수해 5 Signal Session 보유":

```text
StrategyVersion {
    primarySignalAsset: QQQ
    conditions: [
        { operandA: {asset: QQQ, metric: RETURN, window: 5}, operator: LT, operandB: -0.07 },
        { logicalCombinator: AND, operandA: {asset: VIX, metric: CHANGE, window: 5}, operator: GT, operandB: 0.20 }
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
└── failureReason: String | null            # Fail-fast(ADR-005)로 중단된 경우
```

**불변식**:
- `status`가 `COMPLETED`가 되려면 `BacktestResult`가 반드시 함께 존재해야 한다.
- `status`가 `FAILED`이면 `failureReason`이 필수.
- 상태 전이: `PENDING → RUNNING → (COMPLETED | FAILED)`. 역방향 전이 없음. 재시도는 새 `BacktestRun`을 생성한다.
- `datasetSnapshotId`, `engineVersion`은 **Compute가 실행을 시작한 이후에만 채워진다** — `PENDING` 상태에서는 `null`이다. Core가 요청 시점에 스냅샷을 미리 지정하지 않고, Compute가 실행 시점에 선택한 값을 응답으로 돌려받아 기록한다.

### 1.4 BacktestResult (Entity, BacktestRun에 종속)

Compute가 계산한 결과를 Core가 영속화한 읽기 모델. Compute는 이 데이터를 직접 저장하지 않는다 — 계산해서 Core에 반환하면 Core가 소유권을 갖는다.

```text
BacktestResult
├── backtestRunId
├── metrics: { totalReturn, cagr, mdd, sharpe, winRate, tradeCount, avgTradeReturn, avgHoldingPeriod, profitFactor }
├── equityCurve: List<{ date, value }>
├── trades: List<Trade>
├── benchmark: { primary: BuyAndHoldResult, secondaryReference: BuyAndHoldResult | null }  # ADR-007
├── signalExecutionDelay: { median, max, distribution }
├── sampleSizeWarning: NONE | LOW | ZERO    # ADR-006
└── dataIntegrityStatus: { datasetSnapshotId, corporateActionsApplied, pointInTimeValidationPassed }  # datasetSnapshotId는 BacktestRun.datasetSnapshotId와 동일 값 (필드명 통일)

Trade
├── signalTime, entryTime, entryPrice
├── exitTime, exitPrice
├── returnPct
└── holdingPeriod
```

### 1.5 User / Subscription

`Subscription.tier: FREE | PRO`. Free/Pro 차이는 사용량·범위 제한이며 별도 도메인 로직(가격, 결제)은 없다 — 결제 연동은 Phase 5.

| | Free | Pro |
|---|---|---|
| Asset | 제한된 Asset | 전체 Asset |
| 백테스트 기간 | 제한된 기간 | 확장된 기간 |
| 월 백테스트 횟수 | 제한 | 높은 한도 |
| 전략 저장/비교, Export | 미제공 | 제공 |

정확한 제한 수치는 데이터/컴퓨트 비용 검증 후 결정(Phase 0/2). `GetUsage` Use Case(`docs/USECASES.md`)로 남은 한도를 조회한다.

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
├── source: VendorName
├── coverage: { assets: List<AssetSymbol>, start, end }
├── adjustmentPolicy
└── storagePath                             # Object Storage 상의 Parquet 경로
```

**불변식**: 한번 생성된 Snapshot은 절대 수정하지 않는다. 새 데이터가 들어오면 새 Snapshot을 생성한다(ADR-010).

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

Compute는 `Strategy`, `User`, `Subscription`을 전혀 모른다 — 요청받은 StrategyVersion 내용과 파라미터만으로 순수 계산을 수행하는 상태 없는(stateless) 서비스로 취급한다.
