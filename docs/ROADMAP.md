# ROADMAP.md — Phase별 범위

에이전트는 지금 어느 Phase에 있는지 먼저 확인하고, **다음 Phase의 기능을 미리 구현하지 않는다.** 각 Phase는 이전 Phase의 종료 조건을 만족해야 다음으로 넘어간다.

---

## Phase 0 — Feasibility Validation

**성격**: 코드보다 검증이 우선인 스파이크 단계. 이 Phase의 산출물은 "결정"이지 "기능"이 아니다.

**범위**:

*Data*
- 데이터 벤더 확정 (US Equity/ETF, Crypto 각각) — 상업적 이용·재배포·캐싱 권리 확인 (ADR-014 우선 해결)
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
- Strategy Builder Level 1~3 프로토타입으로 TTFB(Time to First Backtest) 예비 측정

*Compliance*
- 유사투자자문업 관련성 등 금융규제 예비 검토 (정식 Legal Review는 `docs/DECISIONS.md` ADR-011 — 유료 플랜 출시 전 필수)

**종료 조건**: 데이터 벤더 확정, DSL 표현 가능 비율 80% 이상 확인, Phase 1 착수 가능 상태.

**DSL Test Cases** (Cross-Calendar/Cross-Market 검증용):

```text
1. 단일 자산 조건 (QQQ.return(5) < -7% → BUY QQQ)
2. Signal Asset ≠ Execution Asset (QQQ 조건 → TQQQ 매수)
3. AND 조건 (QQQ + VIX → TQQQ 매수)
4. Signal Asset이 조건에 직접 등장하지 않는 경우 (BTC Signal, VIX 조건 → 허용)
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

## Phase 1 — Core MVP

**성격**: 실제 서비스 코드 작성이 시작되는 단계. `docs/USECASES.md`에 정의된 Command/Query가 이 Phase의 범위다.

**범위**:

| 영역 | 포함 |
|---|---|
| Data | 6개 Asset(ADR-001) Daily OHLC, Calendar, Corporate Action, Dataset Snapshot |
| Strategy | Primary Signal Asset, Execution Asset, Simple/Lookback/Change/Relative Condition, AND/OR, Lag, Time-based Exit, Long Only, Single Position, Duplicate Entry Ignore |
| Backtest | Next Available Session 체결, Open 기준가, 고정 Fee/Slippage, Point-in-Time Validation, Missing Session Fail-fast, Determinism |
| Result | Equity Curve, Drawdown, Trade Table/Timeline, Return/CAGR/Sharpe/MDD/Win Rate, Benchmark, Low Sample Warning, Zero Trade Empty State, Data Integrity 표시 |
| UX | Data Explorer, Strategy Builder(Progressive Disclosure Level 1~3) |

**명시적 제외**: Condition-based Exit, Short, Multi-position, Strategy 비교, 자연어 입력, AI 전 영역.

**종료 조건**: 사용자 테스트에서 TTFB Simple ≤ 3분 / Advanced ≤ 5분 확인, Backtest 결과가 결정론적으로 재현됨을 자동 테스트로 검증.

---

## Phase 2 — Product Validation

**성격**: 기능 추가보다 계측·실험이 중심. 코드 변경은 대부분 분석/실험을 위한 계측(instrumentation)이다.

**범위**: Second Backtest Rate, Level Transition Rate, Unsupported Hypothesis Rate 등 핵심 지표 계측. 가격 실험(Free/Pro 구분 확정). 리텐션 측정.

**종료 조건**: Second Backtest Rate가 유의미한 수준으로 관찰됨 — 이게 낮으면 Phase 1로 돌아가 UX/DSL을 재검토한다.

---

## Phase 3 — Research Expansion

**범위**: Correlation, Conditional Return, Lead-Lag, Market Regime. 백테스트 이전 단계에서 더 많은 가설을 발견할 수 있도록 Data Explorer를 확장.

---

## Phase 4 — Robust Validation

**범위**: Out-of-Sample Test, Walk-forward Validation, Parameter Sensitivity, Overfitting Detection, Monte Carlo Simulation. "과거에 우연히 잘 맞은 전략인지"를 검증하는 기능군.

---

## Phase 5 — Portfolio & Commercialization

**범위**: Multi-position, Asset Allocation, Rebalancing, Position Sizing, Condition-based Exit(ADR-008 재검토), 구독/결제 정식 도입, AI Research Interface(자연어 → DSL, 검증된 엔진 결과를 AI가 설명 — ADR-015 원칙 유지).

**주의**: 이 Phase에서도 AI는 직접 수치를 계산하지 않는다(ADR-015).
