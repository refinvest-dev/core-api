# GLOSSARY.md — 도메인 용어 ↔ 코드 네이밍

에이전트는 변수명/클래스명/필드명을 지을 때 이 표를 그대로 따른다. 임의로 축약하거나 동의어로 바꾸지 않는다(`AI_AGENT.md` §2).

| 용어 (한글) | 용어 (영문/코드) | 정의 | Related ADR |
|---|---|---|---|
| 가설 | Hypothesis | 사용자가 검증하고 싶은 투자 아이디어. 코드 상 별도 Aggregate는 아니며 `Strategy`로 구현됨 | — |
| 전략 | `Strategy` | 가설을 조건으로 표현한 컨테이너. 여러 `StrategyVersion`을 가짐 | ADR-013 |
| 전략 버전 | `StrategyVersion` | 불변 엔터티. 생성 후 수정 불가 | ADR-013 |
| 신호 자산 | `signalAsset` | 조건 계산에 사용되는 자산 (Primary와 구분됨) | ADR-002 |
| 기준 신호 자산 | `primarySignalAsset` | Signal Timestamp/Lag/Exit의 시간 기준이 되는 단 하나의 자산. 항상 명시적으로 지정 | ADR-002, ADR-003 |
| 조건 참조 자산 | `conditionReferenceAsset` | 조건 계산에 쓰이지만 시간 기준은 아닌 자산 (예: VIX) | ADR-002 |
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
| 신호-체결 지연 | Signal-to-Execution Delay | Cross-Market 전략에서 Signal Timestamp와 실제 Execution Timestamp의 차이 | — |
| 중복 진입 | Duplicate Entry | 포지션 보유 중 새 Entry Signal 발생. MVP는 무시(Ignore) | ADR-009 |
| 청산 | `exit` | 포지션 종료. MVP는 Time-based Exit만 지원 | ADR-008 |
| 데이터셋 스냅샷 | `DatasetSnapshot` | 특정 시점에 정규화되어 저장된 불변 데이터셋. 재현성의 기준 | ADR-010 |
| 결정론성 | Determinism | 동일 입력(Strategy Version + Dataset Snapshot + Engine Version + Fee/Slippage) → 동일 결과 | ADR-010 |
| 시점 정확성 | Point-in-Time Correctness | 계산 시점 이후의 데이터를 절대 참조하지 않는 원칙 | ADR-004 |
| 미래참조편향 | Look-ahead Bias | Point-in-Time Correctness를 위반해 미래 정보를 사용하는 오류. 방지 대상 | ADR-004 |
| 기준가 | Reference Price | 체결 시 사용하는 기준 가격. `Execution Session Open`으로 고정 | ADR-010 |
| 낮은 표본 경고 | Low Sample Warning | Trade Count < 10일 때 표시하는 경고 | ADR-006 |
| 무거래 결과 | Zero Trades / Empty State | Trade Count = 0일 때의 별도 처리 | ADR-006 |
| 기본 벤치마크 | Primary Benchmark | Execution Asset Buy & Hold | ADR-007 |
| 참고 벤치마크 | Secondary Reference | Signal Asset Buy & Hold (Cross-Market 전략에서만) | ADR-007 |
| 레버리지 ETF 경고 | Leveraged ETF Warning | TQQQ/SOXL 결과에 고정 표시하는 구조적 특성 안내 | ADR-016 |
| 백테스트 실행 | `BacktestRun` | 하나의 백테스트 요청과 상태(PENDING/RUNNING/COMPLETED/FAILED) | — |
| 백테스트 결과 | `BacktestResult` | 계산이 끝난 뒤 Core가 영속화하는 지표/차트/거래 내역 | — |
| 재검증율 | Second Backtest Rate | 첫 백테스트 후 조건을 수정해 재실행하는 비율. MVP의 핵심 성공 지표 | — |
| 단계적 노출 | Progressive Disclosure | Strategy Builder UI를 Level 1(Simple) → 2(AND/OR) → 3(Relative/Lag/Cross-Market)로 점진 노출 | — |

---

## Condition Metric 타입

| 타입 | 코드 상 값 | 설명 |
|---|---|---|
| Simple Comparison | `SIMPLE` | `VIX > 25` 형태, window 없음 |
| Lookback | `RETURN` | `QQQ.return(20) > 0.10` |
| Change | `CHANGE` | `VIX.change(5) > 0.20` |
| Relative | 위 두 타입의 조합으로 표현 (별도 타입 아님) | `QQQ.return(20) > SPY.return(20)` — operandB도 MetricReference인 경우 |

## Market Calendar

| 값 | 대상 자산 | 특징 |
|---|---|---|
| `US_EQUITY` | QQQ, SPY, TQQQ, SOXL, VIX | 거래일 기준, 주말/미국 공휴일 제외 |
| `CRYPTO_UTC` | BTCUSDT | 24/7, UTC 기준 Daily Session |
