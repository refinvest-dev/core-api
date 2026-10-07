# USECASES.md — Command / Query 목록

Strategy Validation Phase 1과 현재 우선순위인 Trading Review TR-0/TR-1 Use Case를 다룬다. 신규 Use Case를 추가할 때는 어느 Aggregate에 속하는지, Core/Compute 중 어디서 처리되는지 명시한다. 아래 Core 쪽 Use Case의 정확한 REST 경로/스키마는 `openapi/core-api.yaml`이 정본이다 — 이 표는 Use Case 단위 요약이고, 실제 구현은 그 스펙을 따른다.

Trading Review Use Case의 precondition, 상태 변화, idempotency, evidence와 사용자 관점 outcome은
[`TRADING_REVIEW_ACCEPTANCE.md`](TRADING_REVIEW_ACCEPTANCE.md)의 stable Scenario ID를 acceptance 정본으로
사용한다. `AcceptanceOutcome`은 Aggregate status나 후속 HTTP status가 아니다.

---

## Trading Record 모듈 (Core, Compute normalization 오케스트레이션)

아래 Use Case의 정확한 REST 경로와 payload는 `openapi/core-api.yaml`에 확정되어 있다. 보관·삭제 scope와 상태는
[`TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md), version compatibility·재처리·lineage는
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)가 정본이다.

| Use Case | 타입 | 설명 |
|---|---|---|
| `CreateTradingAccount` | Command | 사용자가 선언한 Binance account container 생성 |
| `GetTradingAccount` / `ListTradingAccounts` | Query | owner Account 단건/목록 조회 |
| `CreateTradingBook` | Command | `BINANCE + LINEAR_PERPETUAL + ONE_WAY + USDT` 복원 경계 생성 |
| `GetTradingBook` / `ListTradingBooks` | Query | owner Book 단건/목록 조회 |
| `CreateTradingImportSession` | Command | source/review timezone과 One-way 선언을 고정한 수입 세션 생성 |
| `UploadTradingImportArtifact` | Command | 역할이 다른 Trade History/Position History artifact를 session에 각각 등록 |
| `ValidateTradingImportSession` | Command | 두 artifact의 비동기 normalization/reconciliation과 원자적 accept/reject 시작 |
| `GetTradingImportSession` | Query | 상태, row count, validation/reconciliation issue, 생성된 LedgerRevision 조회 |
| `ListTradingImports` / `GetTradingImportQualityReport` | Query | Book import 이력과 별도 quality report 조회 |
| `GetLedgerRevision` | Query | immutable revision의 coverage, capability, quality summary 조회 |
| `ListLedgerRevisions` | Query | Book의 immutable Revision 이력 조회 |
| `DeleteTradingSourceArtifact` | Command | raw object만 즉시 접근 차단·삭제하고 metadata/snapshot/ledger/result는 유지 |
| `DeleteTradingImportSession` | Command | session source/evidence/record와 이를 포함한 immutable Revision 및 종속 result 전체 삭제 |
| `DeleteTradingBook` | Command | Book 아래 모든 import, ledger, evidence와 analysis data 삭제 |
| `DeleteTradingAccount` | Command | Account와 모든 Book subtree 삭제 |
| `GetTradingDeletionRequest` | Query | deletion state, safe failure와 backup 최종 제거 예정 시점 조회 |

## Trading Review 모듈 (Core, Compute analysis 오케스트레이션)

| Use Case | 타입 | 설명 |
|---|---|---|
| `RunTradingAnalysis` | Command | 하나의 LedgerRevision과 명시적 AnalysisConfig로 `TradingAnalysisRun(PENDING)` 생성 |
| `PollTradingAnalysisStatus` | Query | analysis run 상태와 structured failure 조회 |
| `GetTradingReview` | Query | reconstruction quality, Metrics/Findings와 supporting ReviewUnit 조회 |
| `GetBehaviorMetric` | Query | Metric status/value, population과 paged membership reference 조회 |
| `ListReviewUnits` / `GetReviewUnit` | Query | 분석 결과의 ReviewUnit 목록/단건 조회 |
| `GetReviewUnitEvidence` | Query | ReviewUnit allocation과 canonical source row provenance 조회 |
| `ListTradingAnalysisRuns` | Query | 같은 Book의 분석 이력 조회. 버전이 다른 결과를 자동 trend로 결합하지 않음 |
| `RequestTradingReprocessing` | Command | source Revision/optional Run, exact target VersionSet, type과 config를 고정한 `TradingReprocessingRun(PENDING)` 생성. 같은 성공 idempotency key는 기존 결과 반환 |
| `CancelTradingReprocessing` | Command | terminal 전 재처리 취소를 요청. 기존 정상 결과와 latest pointer는 변경하지 않음 |
| `PollTradingReprocessingStatus` | Query | input availability, 상태, 새 Revision/Run, safe failure와 difference summary 조회 |
| `GetTradingResultHistory` | Query | historical/current result의 source/target version, config, lineage, 생성 시점과 replay availability 조회 |
| `CompareTradingReprocessingResults` | Query | source/target immutable result의 version-aware difference summary 조회. 기간 trend로 해석하지 않음 |
| `GetComparableTradingTrend` | Query (TR-2) | `TrendComparisonKey`가 같은 completed result만 연결하고 불일치하면 `NOT_COMPARABLE_VERSION` 반환 |

raw가 삭제된 `GetReviewUnitEvidence`는 원본 row가 아니라 masking version이 고정된
`SourceEvidenceSnapshot`과 canonical record를 반환하고 raw 부재를 명시한다.

Trading Review 수입과 분석은 비동기다. Import acceptance와 Analysis completion은 별도 상태 전이다.
재처리는 기존 ImportSession/AnalysisRun의 상태를 되돌리지 않으며 삭제 요청 commit 뒤에는 admission/retry하지
않는다. API 의미는 `TRADING_REVIEW_API.md`, persistence는 `TRADING_REVIEW_PERSISTENCE.md`를 따른다.

## Strategy 모듈 (Core)

| Use Case | 타입 | 설명 |
|---|---|---|
| `CreateStrategy` | Command | 빈 Strategy 생성 (이름만 지정) |
| `DefineStrategyVersion` | Command | Primary Signal Asset, Condition, Execution Asset, Lag, Exit을 지정해 새 `StrategyVersion` 생성. 불변식(`docs/DOMAIN.md` §1.2) 검증 포함 |
| `PreviewStrategy` | Query | 현재 편집 중인 조건을 자연어 문장으로 미리 보여줌 (DSL과 1:1 대응) |
| `GetStrategy` | Query | Strategy와 그 버전 목록 조회 |
| `ListStrategies` | Query | 사용자의 전략 목록 |

## Backtest 모듈 (Core, Compute 오케스트레이션)

| Use Case | 타입 | 설명 |
|---|---|---|
| `RunBacktest` | Command | 현재 Subscription tier의 모든 Asset entitlement·기간 제한을 검사하고, 월간 quota/동시 실행 capacity를 원자적으로 예약한 뒤 `BacktestRun(PENDING)` 생성, Compute에 비동기 요청 |
| `PollBacktestStatus` | Query | `BacktestRun.status` 조회 (Web이 폴링) |
| `GetBacktestResult` | Query | `COMPLETED` 상태의 `BacktestRun`에 대한 `BacktestResult` 전체(지표, Strategy/Execution Asset B&H Equity Curve, Trade Table/Timeline source, 시장 관계 등) 조회 |
| `ListBacktestRuns` | Query | 특정 Strategy의 백테스트 실행 이력 (Second Backtest Rate 계측의 데이터 소스) |

**주의**: `RunBacktest`는 항상 비동기다. 동기로 결과를 바로 반환하는 Use Case를 만들지 않는다(`AI_AGENT.md` §2).

## Asset / Strategy Builder 지원 (Core는 프록시, 실제 데이터는 Compute 소유)

MVP에서 사용자는 외부 증권사·거래소 차트 등으로 시장을 관찰하고 RefInvest에서 가설을
정의·검증한다(ADR-049). 따라서 Asset 메타데이터와 가용 기간은 Strategy Builder가
조건과 기간 제약을 안내하는 데만 사용한다. 사용자용 원시 가격/수익률/정규화 시계열,
Data Explorer, 원시 데이터 다운로드는 MVP 공개 기능이 아니다.

| Use Case | 타입 | 설명 |
|---|---|---|
| `ListAssets` | Query | MVP Asset Universe(5종) 목록과 메타데이터 |
| `GetAssetAvailability` | Query | 특정 Asset의 `listingDate`, `dataAvailability` — Strategy Builder에서 기간 제약 안내에 사용 |
| `GetSeries` | Query (보류) | immutable snapshot 시계열 조회. 계약과 Compute 내부 capability는 유지하지만 MVP Web은 호출·표시하지 않는다. 공개 Data Explorer 도입 시 라이선스 검토와 함께 재활성화한다. |

## Compute 내부 Use Case (Compute 소유, Core는 호출만)

| Use Case | 타입 | 설명 |
|---|---|---|
| `NormalizeTradingRecords` | Command (내부) | 두 Binance V1 artifact schema를 검증하고 canonical record/provenance, reconciliation, capability와 quality report를 반환. 알 수 없는 의미 행을 조용히 제외하지 않음 |
| `ExecuteTradingAnalysis` | Command (내부) | pinned LedgerRevision과 version/config로 product reconstruction, ReviewUnit, Behavior Metric/Finding을 결정론적으로 계산 |
| `PollTradingComputeJob` | Query (내부) | normalization/analytics Compute runtime 상태를 Core durable dispatch가 polling |
| `GetTradingComputeTerminalPayload` / `AcknowledgeTradingComputeResult` | Query/Command (내부) | opaque terminal payload를 hash 검증해 Core에 장기 저장한 뒤 acknowledgement |
| `ExecuteBacktest` | Command (내부) | Strategy DSL을 받아 `docs/ARCHITECTURE.md` §3 파이프라인을 실행하고 `BacktestResult` payload 반환. DSL Validation(Asset 존재, Calendar 호환성, Window/Lag Validity 등)은 **이 파이프라인의 첫 단계**로 내장되어 있으며, 별도로 독립 호출 가능한 엔드포인트는 아니다 — 실패 시 Job을 큐에 넣지 않고 즉시 `400`으로 반환한다(`openapi/compute-api.yaml`의 `POST /backtests` 400 응답) |
| `IngestDailyData` | Command (스케줄) | 벤더 archive/API → 정규화 → Corporate Action 처리 → 새 `DatasetSnapshot` 생성. BTCUSDT는 Binance Public Data의 전일 일봉 archive와 checksum을 검증한다(ADR-051). 사용자 요청과 무관하게 매일 1회 실행하며, 사용자 브라우저 요청으로 벤더 API를 호출하지 않는다. |

## Member / Auth / Subscription (Core)

| Use Case | 타입 | 설명 |
|---|---|---|
| `SocialLogin` | Command | Kakao/Naver/Google provider identity를 정규화해 기존 `SocialIdentity`의 Member를 찾거나, 최초 로그인이라면 Member와 SocialIdentity를 원자적으로 생성한 뒤 RefInvest Access/Refresh JWT Cookie를 발급 |
| `RefreshSession` | Command | Refresh JWT Rotation. 사용된 Refresh Token을 무효화하고 새 Access/Refresh JWT를 발급하며, 재사용은 해당 token family를 폐기 |
| `Logout` | Command | 현재 Refresh Token 또는 token family를 무효화하고 인증 Cookie 제거 |
| `DeleteMember` | Command | Member 삭제 orchestration을 시작하며 모든 TradingAccount 하위 Trading Review 삭제 완료를 추적 |
| `GetUsage` | Query | UTC 기준 이번 달 Backtest 정상 접수 횟수와 현재 Free/Pro tier의 월간 횟수·기간·Asset 한도 조회 |
| `UpgradeSubscription` | Command | Free → Pro 전환 (Phase 5에서 실제 결제 연동, MVP는 상태값만) |

---

## Use Case 작성 규칙

- 하나의 Use Case는 하나의 Aggregate만 수정한다. 여러 Aggregate를 동시에 바꿔야 한다면 별도 Use Case로 쪼개고, 필요하면 Application 레이어에서 순차 호출한다.
- Command는 항상 결과로 최소한의 식별자(`id`)를 반환하고, 상세 조회는 별도 Query로 분리한다(CQRS 원칙 경량 적용).
- Compute를 호출하는 Use Case는 `backtest`, `asset`, `tradingreview` 모듈의 각 `ComputeClient` 포트만 사용한다 — `RunBacktest`는 `backtest` 모듈의 포트를, `ListAssets`/`GetAssetAvailability`/보류된 `GetSeries`는 `asset` 모듈의 포트를 사용한다. Trading Review import/analysis는 `tradingreview` 포트를 사용하며, 그 외 모듈은 Compute를 직접 호출하지 않는다(`docs/ARCHITECTURE.md` §2).
