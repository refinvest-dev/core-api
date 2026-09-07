# USECASES.md — Command / Query 목록

Phase 1(Core MVP) 범위의 Use Case만 다룬다. 신규 Use Case를 추가할 때는 어느 Aggregate에 속하는지, Core/Compute 중 어디서 처리되는지 명시한다. 아래 Core 쪽 Use Case의 정확한 REST 경로/스키마는 `openapi/core-api.yaml`이 정본이다 — 이 표는 Use Case 단위 요약이고, 실제 구현은 그 스펙을 따른다.

---

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
| `GetBacktestResult` | Query | `COMPLETED` 상태의 `BacktestRun`에 대한 `BacktestResult` 전체(지표, Equity Curve, Trade Table 등) 조회 |
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
| `ExecuteBacktest` | Command (내부) | Strategy DSL을 받아 `docs/ARCHITECTURE.md` §3 파이프라인을 실행하고 `BacktestResult` payload 반환. DSL Validation(Asset 존재, Calendar 호환성, Window/Lag Validity 등)은 **이 파이프라인의 첫 단계**로 내장되어 있으며, 별도로 독립 호출 가능한 엔드포인트는 아니다 — 실패 시 Job을 큐에 넣지 않고 즉시 `400`으로 반환한다(`openapi/compute-api.yaml`의 `POST /backtests` 400 응답) |
| `IngestDailyData` | Command (스케줄) | 벤더 archive/API → 정규화 → Corporate Action 처리 → 새 `DatasetSnapshot` 생성. BTCUSDT는 Binance Public Data의 전일 일봉 archive와 checksum을 검증한다(ADR-051). 사용자 요청과 무관하게 매일 1회 실행하며, 사용자 브라우저 요청으로 벤더 API를 호출하지 않는다. |

## Member / Auth / Subscription (Core)

| Use Case | 타입 | 설명 |
|---|---|---|
| `SocialLogin` | Command | Kakao/Naver/Google provider identity를 정규화해 기존 `SocialIdentity`의 Member를 찾거나, 최초 로그인이라면 Member와 SocialIdentity를 원자적으로 생성한 뒤 RefInvest Access/Refresh JWT Cookie를 발급 |
| `RefreshSession` | Command | Refresh JWT Rotation. 사용된 Refresh Token을 무효화하고 새 Access/Refresh JWT를 발급하며, 재사용은 해당 token family를 폐기 |
| `Logout` | Command | 현재 Refresh Token 또는 token family를 무효화하고 인증 Cookie 제거 |
| `GetUsage` | Query | UTC 기준 이번 달 Backtest 정상 접수 횟수와 현재 Free/Pro tier의 월간 횟수·기간·Asset 한도 조회 |
| `UpgradeSubscription` | Command | Free → Pro 전환 (Phase 5에서 실제 결제 연동, MVP는 상태값만) |

---

## Use Case 작성 규칙

- 하나의 Use Case는 하나의 Aggregate만 수정한다. 여러 Aggregate를 동시에 바꿔야 한다면 별도 Use Case로 쪼개고, 필요하면 Application 레이어에서 순차 호출한다.
- Command는 항상 결과로 최소한의 식별자(`id`)를 반환하고, 상세 조회는 별도 Query로 분리한다(CQRS 원칙 경량 적용).
- Compute를 호출하는 Use Case는 `backtest` 또는 `asset` 모듈의 `ComputeClient` 포트만 사용한다 — `RunBacktest`는 `backtest` 모듈의 포트를, `ListAssets`/`GetAssetAvailability`/보류된 `GetSeries`는 `asset` 모듈의 포트를 사용한다. 그 외 모듈은 Compute를 직접 호출하지 않는다(`docs/ARCHITECTURE.md` §2).
