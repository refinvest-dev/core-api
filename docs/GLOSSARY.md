# GLOSSARY.md — 도메인 용어 ↔ 코드 네이밍

에이전트는 변수명/클래스명/필드명을 지을 때 이 표를 그대로 따른다. 임의로 축약하거나 동의어로 바꾸지 않는다(`AI_AGENT.md` §2).

| 용어 (한글) | 용어 (영문/코드) | 정의 | Related ADR |
|---|---|---|---|
| 가설 | Hypothesis | 사용자가 검증하고 싶은 투자 아이디어. 코드 상 별도 Aggregate는 아니며 `Strategy`로 구현됨 | — |
| 전략 | `Strategy` | 가설을 조건으로 표현한 컨테이너. 여러 `StrategyVersion`을 가짐 | ADR-013 |
| 전략 버전 | `StrategyVersion` | 불변 엔터티. 생성 후 수정 불가 | ADR-013 |
| 신호 자산 | `signalAsset` | 조건 계산에 사용되는 자산 (Primary와 구분됨) | ADR-002 |
| 기준 신호 자산 | `primarySignalAsset` | Signal Timestamp/Lag/Exit의 시간 기준이 되는 단 하나의 자산. 항상 명시적으로 지정 | ADR-002, ADR-003 |
| 조건 참조 자산 | `conditionReferenceAsset` | 조건 계산에 쓰이지만 시간 기준은 아닌 자산 (예: SPY) | ADR-002 |
| 실행 자산 | `executionAsset` | 실제로 매수/매도하는 자산 | ADR-003 |
| 신호 캘린더 | `signalCalendar` | Primary Signal Asset이 속한 Calendar(`US_EQUITY` \| `CRYPTO_UTC`) | ADR-003 |
| 거래 세션 | Trading Session | 특정 자산이 거래되는 하루 단위. Metric Window(Return/Change/Lookback) 계산은 **Referenced Asset 자신의** Trading Session을 기준으로 한다 | ADR-003 |
| 신호 세션 | Signal Session | **Primary Signal Asset 캘린더 기준**으로 셈하는 세션 단위. Trading Session과 달리 항상 Primary Signal Asset 하나의 캘린더로 고정되며, `lag`(`SignalSessions`)와 `exit.holdingSignalSessions`가 이 단위로 계산된다 | ADR-003 |
| 지표 윈도우 | `metricWindow` | Return/Change 등 계산 시 사용하는 세션 개수. Referenced Asset 자신의 Calendar 기준 | ADR-003 |
| 지표 앵커 규칙 | Cross-Calendar Metric Anchor Rule | Referenced Asset에 해당 세션이 없을 때 가장 최근 완료 세션을 기준으로 계산하는 규칙 | ADR-004 |
| 지연 | `lag` | Signal 확정과 실제 매수 사이의 지연(Primary Signal Asset 세션 수) | ADR-003 |
| 체결 | `execution` | 실제 주문이 이뤄지는 것. Execution Asset의 Next Available Session에서 발생 | ADR-003 |
| 다음 가능 세션 | Next Available Session | Execution Asset이 거래 가능한 가장 이른 세션 | ADR-003 |
| 결측 세션 | Missing Session | 데이터가 없는 세션. 예상된 결측(정상 Calendar 차이)과 예상치 못한 결측(Fail-fast 대상)으로 구분 | ADR-005 |
| 신호-체결 지연 | Signal-to-Execution Delay | 각 거래의 Signal Timestamp와 Execution Timestamp의 차이. 결과에서는 시간(hours) 단위로 기록 | ADR-048 |
| 중복 진입 | Duplicate Entry | 포지션 보유 중 새 Entry Signal 발생. MVP는 무시(Ignore) | ADR-009 |
| 청산 | `exit` | 포지션 종료. MVP는 Time-based Exit만 지원 | ADR-008 |
| 데이터셋 스냅샷 | `DatasetSnapshot` | 특정 시점에 정규화되어 저장된 불변 데이터셋. 재현성의 기준 | ADR-010 |
| source artifact | `sourceArtifact` | Snapshot을 만들 때 검증한 공급사 archive의 URI, SHA-256, 수집 시각. BTCUSDT는 Binance Public Data archive를 사용 | ADR-051 |
| 결정론성 | Determinism | 동일 입력(Strategy Version + Dataset Snapshot + Engine Version + Fee/Slippage) → 동일 결과 | ADR-010 |
| 시점 정확성 | Point-in-Time Correctness | 계산 시점 이후의 데이터를 절대 참조하지 않는 원칙 | ADR-004 |
| 미래참조편향 | Look-ahead Bias | Point-in-Time Correctness를 위반해 미래 정보를 사용하는 오류. 방지 대상 | ADR-004 |
| 기준가 | Reference Price | 체결 시 사용하는 기준 가격. `Execution Session Open`으로 고정 | ADR-010 |
| 낮은 표본 경고 | Low Sample Warning | Trade Count < 10일 때 표시하는 경고 | ADR-006 |
| 무거래 결과 | Zero Trades / Empty State | Trade Count = 0일 때의 별도 처리 | ADR-006 |
| 기본 벤치마크 | Primary Benchmark | Execution Asset Buy & Hold | ADR-007 |
| 참고 벤치마크 | Secondary Reference | Primary Signal Asset과 Execution Asset이 다를 때의 Signal Asset Buy & Hold | ADR-007, ADR-052 |
| 신호-체결 시장 관계 | `signalExecutionMarketRelation` | Primary Signal Asset과 Execution Asset의 calendar가 같으면 `SAME_MARKET`, 다르면 `CROSS_MARKET`. 결과 Timeline의 시장 구분에 사용 | ADR-052 |
| 포트폴리오 지수 | portfolio index | Strategy 또는 Buy & Hold의 equity curve 값. 시작값은 1이며 원시 가격이 아니다 | ADR-052 |
| 레버리지 ETF 경고 | Leveraged ETF Warning | TQQQ/SOXL 결과에 고정 표시하는 구조적 특성 안내 | ADR-016 |
| 백테스트 실행 | `BacktestRun` | 하나의 백테스트 요청과 상태(PENDING/RUNNING/COMPLETED/FAILED) | — |
| 백테스트 결과 | `BacktestResult` | 계산이 끝난 뒤 Core가 영속화하는 지표/차트/거래 내역 | — |
| 재검증율 | Second Backtest Rate | 첫 백테스트 후 조건을 수정해 재실행하는 비율. MVP의 핵심 성공 지표 | — |
| 단계적 노출 | Progressive Disclosure | Strategy Builder UI를 Level 1(Simple) → 2(AND/OR) → 3(Relative/Lag/Cross-Market)로 점진 노출 | — |

## Trading Review 용어

Backtest 용어와 실제 거래 용어를 타입 수준에서 구분한다. 특히 Backtest의 `execution`/`Trade`를 아래
`ExecutionRecord`/`ReviewUnit`에 재사용하지 않는다.

| 용어 (한글) | 용어 (영문/코드) | 정의 | Related ADR |
|---|---|---|---|
| 거래 계정 | `TradingAccount` | 사용자가 선언한 외부 venue account container. CSV만으로 검증된 연결을 뜻하지 않음 | ADR-054 |
| 거래 장부 | `TradingBook` | product family, position mode, settlement asset가 같은 독립 복원 경계 | ADR-055 |
| 거래 상품 | `InstrumentDescriptor` | venue의 실제 거래 계약과 pinned product terms. Backtest `Asset`과 별도 | ADR-055 |
| 원본 파일 | `TradingSourceArtifact` | import session 안에서 역할, dialect, hash, metadata와 retention 상태를 가진 업로드 원본 | ADR-055, ADR-058 |
| 원본 보관 상태 | `ArtifactRetentionStatus` | `ACTIVE`, `RETENTION_SCHEDULED`, `DELETION_PENDING`, `DELETED`, `DELETION_FAILED`로 raw object lifecycle을 표현 | ADR-061 |
| 근거 스냅샷 | `SourceEvidenceSnapshot` | raw 삭제 뒤에도 남는 allowlist source field, row/hash와 masking version. 원본 행 자체가 아님 | ADR-061 |
| 데이터 정책 버전 | `dataPolicyVersion` | import에 retention, masking, evidence와 deletion 의미를 함께 고정하는 정책 식별자 | ADR-061 |
| 삭제 요청 | `DeletionRequest` | owner scope, deletion generation, purge checklist와 상태를 가진 idempotent 삭제 작업 | ADR-061 |
| 삭제 세대 | `deletionGeneration` | 삭제 이전 job/result를 stale로 판정하는 scope별 단조 증가 값 | ADR-061 |
| 거래 수입 세션 | `TradingImportSession` | Trade History와 Position History를 함께 검증·조정해 하나의 LedgerRevision을 만드는 원자적 작업 | ADR-058 |
| 거래 기록 | `TradingRecord` | canonical execution, fee, reported PnL 등 발생 사실의 상위 개념 | ADR-055 |
| 실제 체결 | `ExecutionRecord` | 거래소에서 실제 발생한 fill. Backtest의 모의 `execution`과 구분 | ADR-055 |
| 거래소 포지션 요약 | `VenuePositionSummaryRecord` | Position History가 제공하는 종료 포지션 요약. 시점별 PositionSnapshot과 구분 | ADR-058 |
| 포지션 모드 증거 | `PositionModeEvidence` | 사용자 선언, 관측 호환성, hedge overlap 등 mode 판단 근거 | ADR-058 |
| 조정 상태 | `ReconciliationStatus` | reconstructed episode와 venue position summary의 일치·모호·불완전 상태 | ADR-058 |
| 조정 명세 | `ReconciliationManifest` | import 때 계산해 LedgerRevision에 version/hash로 고정하는 episode allocation, tolerance, 조정 상태와 exclusion | ADR-058, ADR-059 |
| 부호 포지션 수량 | Signed Position Quantity | One-way symbol별 position. Long은 양수, Short는 음수이며 exact Decimal 0만 flat | ADR-059 |
| 반전 | `Reversal` | 한 execution이 기존 episode를 닫고 잔여 수량으로 반대 방향 episode를 같은 시각에 여는 상태 전이 | ADR-059 |
| 검열 에피소드 | `CensoredEpisode` | source 범위 밖 시작 또는 범위 끝 미종료로 경계를 완전히 관측하지 못한 episode | ADR-059 |
| 분석 적격 에피소드 | `EligibleEpisode` | `COMPLETE`이며 reconciliation이 `EXACT` 또는 `WITHIN_ROUNDING_TOLERANCE`이고 required capability를 가진 episode | ADR-059 |
| 장부 리비전 | `LedgerRevision` | 분석에 입력되는 불변 canonical record 집합 | ADR-055, ADR-056 |
| 복원 정책 | `ReconstructionPolicy` | 상품/position mode별 record → ReviewUnit 결정 규칙 | ADR-055 |
| 분석 단위 | `ReviewUnit` | Behavior Analytics가 받는 공통 경계. MVP 구현은 `FuturesPositionEpisode` | ADR-055 |
| 선물 포지션 에피소드 | `FuturesPositionEpisode` | instrument position이 0에서 열려 다시 0이 될 때까지의 선물 분석 단위 | ADR-057 |
| 체결 배분 | `PositionAllocation` | canonical record 수량/비용/PnL이 ReviewUnit의 open/increase/reduce/close에 배분된 근거 | ADR-055 |
| 행동 관찰 | `BehaviorObservation` | 심리·의도를 추정하지 않고 ReviewUnit 사이에서 계산한 사실 | ADR-056 |
| 행동 지표 | `BehaviorMetric` | 기간, 표본, 비교군과 definition version을 가진 집계값 | ADR-056 |
| 행동 발견 | `BehaviorFinding` | 노출 기준을 충족해 review 가치가 있다고 선택된 Metric과 근거 | ADR-056 |
| 데이터 능력 | `AnalysisCapability` | 특정 Metric 계산에 필요한 입력 정보의 존재 여부 | ADR-055 |
| 안정 에피소드 시각 | `STABLE_EPISODE_TIME` | summary 분 단위 보정 없이 pinned execution에서 exact `openedAt`/`closedAt`을 확인할 수 있는 episode capability | ADR-060 |
| 지표 모집단 | `Population` | Metric 시간 anchor에 들어와 eligibility/exclusion 판정 대상이 되는 전체 episode 후보 집합 | ADR-060 |
| 관찰 대상 | `Subject` | Metric이 직접 관찰하는 episode 또는 outcome group member | ADR-060 |
| 비교 대상 | `Comparison` | Subject와 연결하거나 집계상 비교하는 entry, 과거 baseline 또는 반대 outcome group | ADR-060 |
| 결과 분류 | `OutcomeClassification` | exact net trading PnL에 따른 `LOSS`, `BREAKEVEN`, `WIN`, capability가 없을 때 `UNAVAILABLE` | ADR-060 |
| 지표 상태 | `MetricStatus` | `AVAILABLE`, `INSUFFICIENT_SAMPLE`, `MISSING_CAPABILITY`, `NOT_APPLICABLE` 중 하나. import/reconstruction 상태와 별도 | ADR-060 |
| 지표 정의 버전 | `MetricDefinitionVersion` | population, relation, comparison과 formula 의미를 고정하는 버전 | ADR-056, ADR-060 |
| 분석 설정 | `AnalysisConfig` | analysis period, window, baseline/sample policy 등 한 AnalysisRun의 결정론적 계산 입력 | ADR-056, ADR-060 |
| 거래 분석 실행 | `TradingAnalysisRun` | pinned revision/version/config를 사용한 비동기 분석 상태 | ADR-056 |
| 거래 재처리 실행 | `TradingReprocessingRun` | 기존 Import/Run을 다시 열지 않고 source/target version, input availability, idempotency와 새 Revision/Run 생성을 추적하는 Aggregate | ADR-062 |
| Revision record membership | `LedgerRevisionRecord` | immutable TradingRecord와 immutable LedgerRevision의 다대다 membership 및 canonical semantic order를 고정하는 relation | ADR-063 |
| 내구성 dispatch | `DurableDispatch` | Core Aggregate와 같은 transaction에서 생성되고 Compute idempotency/job/attempt/ack를 추적하는 발송 record | ADR-047, ADR-063 |
| Compute 종결 payload | `ComputeTerminalPayload` | Compute가 최대 24시간 보관하며 Core가 hash 검증·장기 저장 후 acknowledge하는 opaque runtime payload | ADR-063 |
| artifact lease | `ArtifactLease` | normalization job 하나에 묶인 최대 5분 single-read raw access grant와 제한된 input reference | ADR-061, ADR-063 |
| 최신 거래 장부 포인터 | `latestRevisionId` | 완전히 publish된 immutable LedgerRevision만 가리키는 TradingBook의 mutable pointer | ADR-062, ADR-063 |
| 최신 거래 분석 포인터 | `latestAnalysisRunId` | 완전히 완료된 immutable AnalysisResult의 Run만 가리키는 TradingBook의 mutable pointer | ADR-062, ADR-063 |
| 인수 결과 | `AcceptanceOutcome` | Acceptance Scenario의 사용자 관점 판정. 8개 outcome을 사용하며 Aggregate/Metric/HTTP status와 별도 | TR-0G |
| 인수 시나리오 ID | `TradingReviewAcceptanceScenarioId` | Integration fixture/test와 PR evidence에서 재사용하는 `TR-ACC-{GROUP}-{NUMBER}` stable ID | TR-0G |
| 버전 집합 | `VersionSet` | normalization, reconstruction, analytics 단계에 필요한 의미 version과 implementation digest의 immutable 묶음 | ADR-062 |
| 버전 가용성 | `VersionAvailability` | `ACTIVE`, `DEPRECATED`, `READ_ONLY`, `UNAVAILABLE`로 신규 실행·replay 가능성을 나타내는 상태. 기존 result 조회 가능성과 별도 | ADR-062 |
| 재처리 입력 가용성 | `ReprocessingInputAvailability` | `RAW_AND_CANONICAL`, `CANONICAL_ONLY`, `RESULT_ONLY`, `DELETED`로 가능한 재처리 범위를 나타내는 상태 | ADR-062 |
| 재처리 차이 요약 | `ReprocessingDifferenceSummary` | source/target Revision·Run의 record, episode, eligibility, Metric과 evidence 차이를 version과 함께 기술한 요약. 기간 trend가 아님 | ADR-062 |
| 추세 비교 키 | `TrendComparisonKey` | 같은 기간 trend로 연결하기 위해 같아야 하는 Metric/config/outcome/comparison/timezone/capability/ReviewUnit schema 묶음 | ADR-062 |
| 재처리 결과 해시 | `CanonicalResultHash` | canonical source, instrument terms, target VersionSet과 AnalysisConfig로 결정되는 immutable 결과 hash | ADR-062 |
| 행동 초점 | `BehaviorFocus` | 사용자가 향후 추적하기로 선택한 행동 기준. MVP 이후 | ADR-054 |

---

## Condition Metric 타입

| 타입 | 코드 상 값 | 설명 |
|---|---|---|
| Simple Comparison | `SIMPLE` | `QQQ > 500` 형태, window 없음 |
| Lookback | `RETURN` | `QQQ.return(20) > 0.10` |
| Change | `CHANGE` | `QQQ.change(5) > 0.20` |
| Relative | 위 두 타입의 조합으로 표현 (별도 타입 아님) | `QQQ.return(20) > SPY.return(20)` — operandB도 MetricReference인 경우 |

## Market Calendar

| 값 | 대상 자산 | 특징 |
|---|---|---|
| `US_EQUITY` | QQQ, SPY, TQQQ, SOXL | 거래일 기준, 주말/미국 공휴일 제외 |
| `CRYPTO_UTC` | BTCUSDT | 24/7, UTC 기준 Daily Session |
