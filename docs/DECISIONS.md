# DECISIONS.md — Architecture & Domain Decision Records

이 문서는 지금까지 논의를 통해 확정한 결정을 ADR(Architecture Decision Record) 형식으로 기록한다. 각 ADR을 뒤집으려면 코드를 먼저 바꾸지 말고 이 문서를 먼저 갱신한다.

---

## ADR-001 — MVP Asset Universe 고정

**결정**: MVP는 5개 자산으로 제한한다(ADR-050에 따라 VIX 보류).

- Assets: `QQQ`, `SPY`, `TQQQ`, `SOXL`, `BTCUSDT`

일봉(Daily Bar)만 지원하며, Intraday 데이터는 다루지 않는다.

**이유**: 자산 수·데이터 빈도를 늘리는 것보다 "가설 → 백테스트 → 재검증" 루프의 정확성과 속도를 검증하는 게 MVP의 목표이기 때문. 데이터 비용/컴퓨트 비용도 이 범위에서 통제 가능하다.

**Alternatives considered**: 개별 미국 주식 전체 지원 — 기각. 진입장벽만 낮추고 검증해야 할 핵심 가치(Cross-Calendar 정합성, Determinism)와 무관하게 스코프만 키운다.

---

## ADR-002 — Primary Signal Asset은 항상 명시적으로 지정

**결정**: 하나의 전략은 정확히 하나의 **Primary Signal Asset**을 가진다. 이 값은 시스템이 자동으로 추론하지 않고 사용자가 Strategy Builder에서 항상 명시적으로 선택한다.

**이유**: `BTC.return(7) > QQQ.return(7)` 같은 Relative 조건에서 "첫 번째 Operand", "Execution Asset과 동일한 자산", "먼저 평가되는 자산" 등 여러 자동 추론 규칙이 가능했지만, 전부 DSL의 의미가 코드 구조나 입력 순서에 암묵적으로 의존하게 만든다는 문제가 있었다. 조건 순서를 바꿨을 뿐인데 백테스트 결과가 달라지는 버그를 원천 차단하기 위해 명시적 지정으로 확정한다.

**단일 자산 조건의 경우**: UI에서는 자동으로 채워주되(UX), 내부 도메인 모델에는 항상 명시적 값을 저장한다(Domain). Fallback 규칙("Signal Asset 미지정 시 첫 번째 Operand")은 안전장치로만 코드에 남기고, 실제로는 UI가 항상 강제하므로 정상 경로에서 발동하지 않는다.

**Condition에 등장하지 않는 자산을 Primary Signal Asset으로 지정하는 것도 허용한다** (예: `SIGNAL ASSET = BTCUSDT`, 조건은 `QQQ.change(5) > 0.20`). 다른 시장의 상태로 특정 자산의 진입 타이밍을 결정하는 것은 핵심 사용 사례이기 때문이다.

---

## ADR-003 — 4대 Temporal Rule 고정 계약

**결정**: 모든 시간 관련 계산은 다음 4가지 규칙으로 환원된다. 이 표는 Backtest Engine 구현의 최상위 계약이다.

| 요소 | 기준 |
|---|---|
| Metric Window (Return/Change/Lookback) | **Referenced Asset 자신의** Trading Session |
| Signal Timestamp | **Primary Signal Asset**의 Calendar |
| Lag / Holding Period(Exit) | **Primary Signal Asset**의 Signal Session |
| Execution (Entry/Exit 공통) | **Execution Asset**의 Next Available Session |

**이유**: Cross-Calendar(24/7 Crypto vs 거래일 기준 US Equity) 조건에서 "N일"이 어느 자산 기준인지 모호하면 재현 불가능한 백테스트가 된다. Metric 계산과 Signal 타이밍을 서로 다른 개념으로 분리한 것이 핵심이다 — `BTC.return(7) > QQQ.return(7)`에서 두 Return은 각자의 Calendar로 계산되지만, 이 조건이 만든 Signal이 "언제 발생한 것"으로 취급되는지는 오직 Primary Signal Asset 하나로 결정된다.

**Related**: ADR-002, ADR-004

---

## ADR-004 — Cross-Calendar Metric Anchor Rule

**결정**: Referenced Asset에 Signal Timestamp 시점의 세션이 존재하지 않으면, **Signal Timestamp 이전에 이미 확정되어 있는 가장 최근 완료 세션**을 기준으로 Metric을 계산한다. 미래 세션을 미리 참조하지 않는다.

예: BTC 토요일 신호 평가 시 QQQ는 토요일 세션이 없으므로, 직전 금요일 종가까지의 데이터로 `QQQ.return(7)`을 계산한다.

**이유**: Point-in-Time Correctness(Look-ahead Bias 방지)를 Cross-Calendar 상황에서도 예외 없이 지키기 위한 규칙. 이 규칙이 없으면 "가장 가까운 세션"을 과거/미래 중 어느 쪽으로 찾을지가 구현자마다 달라질 수 있다.

---

## ADR-005 — Missing Session은 Fail-fast, 임의 보정 금지

**결정**: 예상되는 세션(Expected Session)에 데이터가 없으면 이전 가격 복제, 선형 보간, 임의 가격 추정 등 어떤 형태의 보정도 하지 않고 **백테스트를 중단**하며 오류를 표시한다. 정상적인 Calendar 차이(예: QQQ의 토요일)는 Missing Data가 아니므로 이 규칙의 대상이 아니다.

Primary Signal Asset의 결측은 특히 엄격하게 처리한다 — Signal Calendar 자체를 신뢰할 수 없게 만들기 때문이다.

**이유**: 어떤 형태든 임의 보정은 Determinism과 정확성 신뢰를 동시에 훼손한다. 사용자에게 "정확하지만 때때로 실패하는" 도구가 "항상 답을 주지만 가끔 틀린" 도구보다 이 서비스의 포지셔닝(연구 도구, 신뢰 기반)에 맞다.

**차트 렌더링과의 관계**: 차트에서 시각적으로 값을 이어 그리는 방식(interpolation)은 순수한 렌더링 정책이며 이 ADR의 대상이 아니다(Data Explorer는 별도 정책 적용 가능). 백테스트 계산에만 이 규칙이 강제된다.

**Fatal Error 종류** (Backtest Engine이 실행을 중단하는 경우, `openapi/compute-api.yaml`의 `FatalErrorCode`와 동일):
```text
MISSING_REQUIRED_DATA        # 이 ADR의 대상 — 예상 세션 결측
DATASET_CORRUPTION
CALENDAR_RESOLUTION_FAILED
PRICE_DATA_MISSING
DSL_INVALID                  # 실행 전 Validation 단계에서 걸러짐 (Job을 큐에 넣지 않고 즉시 실패)
```
이 중 `DSL_INVALID`만 실행 전(pre-flight) 검증이고, 나머지 4종은 실행 중 데이터 접근 시점에 발생한다. 반대로 Signal/Trade가 0건인 것은 오류가 아니라 **정상 완료**다(ADR-006).

---

## ADR-006 — Zero Trades와 Low Sample Warning은 별도로 처리

**결정**:
- Trade Count < 10 → **Low Sample Warning** 표시 (`⚠ Only N trades occurred. Performance statistics may not be statistically meaningful.`)
- Trade Count = 0 → 별도 **Empty State**로 처리. 백테스트는 정상 완료하되 전략 성과 지표는 `null`로 보존하고, Win Rate/Sharpe/Profit Factor 등을 `0`으로 표시하지 않고 "조건을 충족한 신호가 한 번도 발생하지 않았습니다"라는 안내로 대체한다.

**이유**: 0으로 나누는 연산(Win Rate = 승리 거래 / 전체 거래)을 그대로 노출하면 오해를 부르는 숫자(`Win Rate: 0%`처럼 "전략이 항상 실패했다"는 잘못된 인상)가 나갈 수 있다.

---

## ADR-007 — Benchmark 정책: Execution Asset이 Primary

**결정**:
- Primary Benchmark = **Execution Asset Buy & Hold**
- Secondary Reference = **Signal Asset Buy & Hold** (Cross-Market 전략에서만 참고용으로 별도 표시)

Signal Asset을 Primary Benchmark로 사용하지 않는다.

**이유**: "같은 자산을 그냥 들고 있는 것보다 전략이 나았는가"(Execution Asset 기준)와 "신호를 준 시장 자체보다 나았는가"(Signal Asset 기준)는 서로 다른 질문이며, 전자가 실제 투자 의사결정에 더 직접적으로 연결된다.

---

## ADR-008 — Exit은 MVP에서 Time-based만 지원

**결정**: `HOLD N signal_sessions` 형태의 Time-based Exit만 지원한다. Condition-based Exit(`EXIT WHEN RSI > 50` 등)은 지원하지 않으며 **Phase 5**(`docs/ROADMAP.md` — Portfolio & Commercialization)에서 재검토한다.

**이유**: Condition-based Exit을 포함하면 Exit Priority, Multiple Exit Condition 결합, Entry/Exit 동시 발생 처리, Position State 관리 등으로 DSL 복잡도가 급증한다. MVP는 "Entry Condition → 고정 보유 기간 → Outcome"이라는 단순한 형태를 빠르게 검증하는 것을 우선한다.

---

## ADR-009 — Duplicate Entry Signal은 무시(Ignore)

**결정**: 이미 포지션을 보유한 상태에서 새 Entry Signal이 발생하면 무시한다. Queue에 쌓지 않고, 기존 포지션을 강제로 교체하지도 않는다.

**포지션 모델**: Long Only, Single Active Position, 100% Capital Allocation. Short/Multi-position/Portfolio Allocation은 MVP 범위 밖(Phase 5 이후 검토).

**이유**: Determinism을 위해 포지션 상태 전이를 하나의 규칙으로 고정할 필요가 있다. Queue 기반 처리나 조건부 교체는 각각 별도의 정책 결정(우선순위, 타임아웃 등)을 요구해 MVP 범위를 벗어난다.

---

## ADR-010 — Dataset Snapshot & Backtest Determinism Contract

**결정**: 백테스트에 사용한 정규화 데이터는 **Immutable Dataset Snapshot**으로 저장한다. Backtest Result에는 `Dataset Snapshot ID`, `Strategy Version`, `Engine Version`, `Fee/Slippage Model`을 함께 기록한다. 동일한 4가지 입력이면 항상 동일한 결과가 나와야 한다(Determinism Contract).

체결 규칙: `Reference Price = Execution Session Open`, Slippage/Fee는 고정 비율(매수 `Open × (1 + Slippage)`, 매도 `Open × (1 - Slippage)`). Intraday 체결 시뮬레이션(VWAP, Limit Order 등)은 지원하지 않는다.

**이유**: Adjusted Price는 배당/분할 등으로 벤더 측에서 소급 변경(Restatement)될 수 있어, "현재 API 응답"만으로는 재현성을 보장할 수 없다. 스냅샷을 고정해야 "3개월 전에 실행한 백테스트를 지금 다시 열어도 같은 결과가 나온다"를 보장할 수 있다.

---

## ADR-011 — Regulatory Positioning: 연구·시뮬레이션 도구

**결정**: 서비스는 "과거 데이터에 대한 연구·시뮬레이션 도구"로 포지셔닝하며 투자 추천을 제공하지 않는다. 결과 화면 문구는 항상 과거형/조건형으로 표현한다.

```text
❌ 이 전략은 돈을 벌 수 있습니다 / 좋은 전략입니다
✅ 선택한 과거 기간에서 해당 조건은 다음과 같은 결과를 보였습니다.
```

유료 플랜 출시 전 자본시장법·유사투자자문업 해당 여부에 대한 법률 검토를 진행한다(코드 작업과 별개의 트랙).

**이유**: 백테스트 결과의 상업적 제공이 국내 규제상 어떤 범주에 해당하는지는 별도 법률 검토가 필요한 영역이며, 최소한 제품 문구 수준에서 확정적 수익 예측 표현을 배제해 리스크를 낮춘다.

---

## ADR-012 — 기술 스택 및 레포/컨테이너 구조

**결정**:

| 레포 | 스택 | 역할 |
|---|---|---|
| `core-api` | Kotlin + Spring Boot | Core Server (도메인/API) |
| `compute-api` | Python + FastAPI | Compute (Backtest Engine + Data Ingestion) |
| `web` | Next.js + TypeScript | 프론트엔드 |
| `context` | 문서 전용 | 이 문서들 + Core↔Compute OpenAPI 계약 |

- 4개 레포, 4개 컨테이너로 분리한다.
- **DB는 단일 Postgres를 공유**하되 테이블 소유권을 문서(`docs/ARCHITECTURE.md` §4)로 명확히 구분한다. 서비스별 물리 DB 분리는 하지 않는다.
- Core↔Compute 통신은 **REST + 비동기 폴링**이다. Compute는 백테스트 요청을 즉시 accept(202)하고 내부 큐/워커로 처리하며, Core는 상태를 폴링한다. WebSocket/이벤트 브로커는 지금 도입하지 않는다.
- Web은 Compute를 직접 호출하지 않는다. 항상 Core를 거친다.

**이유**:
- Kotlin은 도메인 로직(유저/전략/구독/사용량 제한)의 타입 안전성과 트랜잭션 관리에 강점이 있다.
- Python은 시계열 벡터 연산(Cross-Calendar 정렬, Metric 계산) 생태계가 압도적으로 유리하다.
- 백테스트는 동기 처리하기엔 무거울 수 있어(10년치 일봉 × Cross-Calendar 조인) 비동기로 분리해야 API 서버가 블로킹되지 않는다.
- DB 물리 분리는 지금 데이터 볼륨(5개 자산 × 일봉)에서 얻는 이득보다 운영 복잡도가 커서 보류한다. 테이블 소유권만 코드 리뷰 규칙으로 강제한다.

**Alternatives considered**: DB도 서비스별로 완전 분리(참고 아키텍처 패턴) — 검토했으나 이번 프로젝트는 데이터 볼륨이 작고 팀 규모도 작아 오버엔지니어링으로 판단, 보류. 향후 트래픽/팀 규모가 커지면 재검토(§ Revisit 참고).

**Revisit 조건**: Compute가 다루는 참조 데이터(Asset/Calendar/Snapshot) 볼륨이 커지거나, Core/Compute를 서로 다른 팀이 전담하게 되면 DB 분리를 다시 검토한다.

---

## ADR-013 — StrategyVersion은 생성 후 불변

**결정**: Strategy는 여러 개의 **StrategyVersion**(불변 엔터티)을 가진다. 사용자가 조건을 수정하면 기존 버전을 덮어쓰지 않고 새 버전을 생성한다. 백테스트는 항상 특정 StrategyVersion을 참조한다.

**이유**: ADR-010의 Determinism Contract가 성립하려면 "Strategy Version"이 시간에 따라 바뀌지 않는 고정된 참조여야 한다. 참고 프로젝트의 자연어 파싱 확인 흐름과 달리 이 서비스는 Form+Block UI로 직접 조건을 구성하므로, 별도의 mutable Draft 상태 없이 "백테스트 실행" 시점에 현재 편집 중인 조건을 새 StrategyVersion으로 확정하는 단순한 흐름을 사용한다.

---

## ADR-014 — BTCUSDT 데이터 공급사: Binance Public Data

**결정**: `BTCUSDT`의 MVP 기준 시장은 **Binance Spot**이며, Compute는
`data.binance.vision`의 공개 archive를 초기 이력 적재와 일일 갱신의 입력으로 사용한다.
이 결정은 RefInvest의 현재 출시 전 개인 사용 단계와 Binance Public Data의 상업적 이용·서버 측
보관·고객 대상 파생 결과 표시 권리가 허용된다는 전제에 한정한다.

**운영 경계**: 이 source는 Compute ingestion 전용이다. Web, Core의 사용자 요청 경로 및 사용자
브라우저는 Binance API나 archive를 직접 호출하지 않는다. RefInvest는 원시 OHLCV·가격 차트·다운로드를
MVP 사용자에게 제공하지 않고, fixed snapshot을 사용한 백테스트의 파생 결과만 제공한다(ADR-049).

**재검토**: 실제 공개 또는 유료 플랜 출시 전에 당시 적용되는 공급사 약관과 허용 범위를 다시 확인한다.
허용 범위가 달라지면 이 ADR을 갱신하고 대체 공급사 또는 asset 범위를 결정한다.

**관련**: ADR-010(Determinism Contract), ADR-049(MVP 시장 관찰과 원시 데이터 노출 경계), ADR-051(Binance archive ingestion). Determinism과 Point-in-Time Correctness에 영향 없음.

---

## ADR-015 — AI는 MVP 범위 밖 (선제적 가드레일)

**결정**: Phase 5 이전에는 Compute에 어떤 형태로든 LLM/AI 클라이언트 의존성을 추가하지 않는다. 향후 AI가 도입되더라도, AI는 자연어를 Strategy DSL로 변환하거나 이미 계산된 Backtest Result를 설명하는 역할만 하며 **직접 수치를 계산하거나 만들어내지 않는다**.

**이유**: "AI가 투자를 추천하는 서비스가 아니다"라는 포지셔닝(ADR-011)을 아키텍처 레벨에서도 지키기 위해, Compute의 핵심 계산 경로에 AI가 끼어들 여지를 원천적으로 만들지 않는다.

---

## ADR-016 — 레버리지 ETF 결과에는 구조적 경고를 항상 표시

**결정**: `TQQQ`, `SOXL` Execution Asset이 포함된 `BacktestResult`를 보여줄 때, Web은 다음 경고를 조건 없이 고정 표시한다(숨기거나 접을 수 없음).

> ⚠ TQQQ/SOXL은 일일 레버리지 목표를 추구하는 상품으로, 변동성과 일일 복리 효과(volatility decay)에 의해 장기 성과가 기초지수의 단순 배수와 일치하지 않을 수 있습니다.

**이유**: 레버리지 ETF는 횡보장에서 기초지수보다 구조적으로 손실이 누적되는 특성이 있어, 결과 화면의 수익률만 보면 "전략이 좋아서"인지 "레버리지 상품 특성 때문"인지 사용자가 오인하기 쉽다. ADR-011(투자 추천 아님)의 연장선으로, 결과 해석의 한계를 능동적으로 알린다.

---

## ADR-017 — Core↔Compute 내부 인증: API Key + 네트워크 격리

**결정**: `compute-api`는 고정 API Key(요청 헤더, 예: `X-Internal-Api-Key`)를 요구하는 것을 최소 인증으로 삼는다. 여기에 배포 인프라가 표준으로 제공하는 네트워크 격리(VPC/보안 그룹, k8s `NetworkPolicy` 등 — 배포 대상이 정해지는 시점에 확정)를 두 번째 방어선으로 얹는다. 전송 구간 암호화(TLS)는 인증과 별개로 항상 활성화하며, 인프라가 제공하는 방식(로드밸런서 TLS 종료, 사설 인증서 등)을 따른다.

mTLS는 지금 도입하지 않는다.

**이유**: 배포 인프라(Kubernetes/VM/관리형 컨테이너 서비스 등)가 아직 정해지지 않았다(`docs/ARCHITECTURE.md` §7). API Key는 어느 인프라를 선택하든 동일하게 동작해 인프라 결정을 선제적으로 제약하지 않는 유일한 옵션이다. mTLS는 옵션 자체는 더 강력하지만 사설 CA 운영·인증서 로테이션 인프라가 필요해, 서비스 메시(Istio/Linkerd) 같은 게 이미 없다면 이 프로젝트 규모에 비해 운영 부담이 크다 — ADR-012에서 DB 물리 분리를 지금 규모엔 오버엔지니어링으로 보고 보류한 것과 같은 논리다. 네트워크 격리만으로는 방어선이 하나뿐이라 보안 그룹 설정 실수나 내부망의 다른 서비스가 침해당했을 때(lateral movement)에 취약해, API Key를 추가 계층으로 둔다.

**API Key 관리**: 코드에 하드코딩하지 않는다. 배포 인프라가 정해지면 그 인프라의 시크릿 관리 방식(환경변수 주입, 클라우드 시크릿 매니저 등)을 따른다. 로테이션 주기는 운영 단계에서 정한다.

**Revisit 조건**: (1) 서비스 메시 등 mTLS를 자동으로 관리해주는 인프라를 도입하게 되면 mTLS로 전환을 검토한다. (2) `compute-api`를 호출하는 내부 클라이언트가 `core-api` 외에 여러 개로 늘어나 클라이언트별 식별·개별 폐기(revocation)가 필요해지면 단일 고정 Key 대신 클라이언트별 Key 또는 mTLS로 전환을 검토한다.

---

## ADR-018 — core-api: Feature-centric Gradle 멀티모듈 + Hexagonal 아키텍처

**결정**: `core-api`는 단일 Gradle 모듈에 패키지로 경계를 표현하는 대신, **feature(모듈)별로 물리적으로
분리된 Gradle 모듈**을 사용한다. 각 feature는 `domain`(순수 도메인), `port`(inbound/outbound 인터페이스),
`application`(Use Case 구현), `adapter:<technology>`(기술별 구현체, 방향 구분 없이 기술 이름으로 명명)로
나뉜다. 공통 요소는 `shared:kernel`(순수 primitive), `shared:infrastructure`(공통 기술 구현), `app`(실행
진입점/composition root)에 둔다.

세부 컨벤션(디렉터리 구조, Gradle project path, persistence 포트 네이밍 `{Domain}Store`/`{Domain}Reader`
— `Repository`라는 이름은 쓰지 않음, Spring component 규칙, ID 생성은 Snowflake 기반 typed ID를
feature별 outbound port로 정의)은 **`core-api/AGENTS.md` §4가 정본**이다. 이 문서(`ARCHITECTURE.md`)는
그 전체를 재설명하지 않고, feature 목록과 모듈 간 규칙(다른 레포에서도 알아야 하는 부분)만 유지한다.

**이유**: `docs/ARCHITECTURE.md` §2의 "모듈은 서로의 domain을 직접 참조하지 않는다"는 규칙을 **컴파일
타임에 강제**하기 위해서다. 단일 모듈 + 패키지 분리는 설정이 간단하지만 경계 위반이 테스트(ArchUnit)를
돌려야만 잡힌다 — 컴파일 자체는 통과해버린다. 물리적으로 Gradle 모듈을 분리하면 애초에 다른 모듈의
`domain`을 import하는 게 컴파일 에러가 되어, 사람이든 에이전트든 경계를 실수로 넘기 어렵다. 이 프로젝트가
에이전트에게 상당 부분의 구현을 맡기는 만큼, "규칙을 지켜라"보다 "애초에 못 하게 만든다"는 방향이
`docs/GIT_WORKFLOW.md`에서 이미 취한 것과 같은 원칙이다.

**트레이드오프**: 설정 복잡도가 단일 모듈보다 높다(모듈별 `build.gradle`, 모듈 간 의존성 그래프 관리,
`build-logic`의 convention plugin 유지 등). 이 비용은 `gradle/libs.versions.toml`과 convention plugin
(`kotlin-common-conventions`, `domain-conventions` 등)으로 반복을 줄여 완화한다.

**Revisit 조건**: 이 구조가 실제로 빌드 시간이나 개발 속도에 부담을 줄 정도로 무거워지면(feature 수가
많아지며 Gradle 설정 관리 자체가 병목이 되는 경우), 덜 자주 바뀌는 feature들을 다시 묶는 것을 검토한다.

---

## ADR-019 — 소셜 로그인 + RefInvest JWT Cookie 세션

**결정**: MVP 인증은 Kakao, Naver, Google OAuth2/OIDC 소셜 로그인만 지원한다. Provider의 access token,
refresh token, subject는 RefInvest의 인증 credential이 아니며, 내부 `MemberId`는 Snowflake 기반으로 별도
발급한다. `SocialIdentity(provider, providerSubject)`는 유일하고, provider가 다른 identity를 email만으로
자동 병합하지 않는다.

로그인 성공 시 Core는 짧은 수명의 Access JWT와 긴 수명의 Refresh JWT를 각각 HttpOnly Cookie로 발급한다.
Access JWT는 내부 MemberId(`sub`), role, issuer, audience, issued-at, expiry, jti만 포함한다. Refresh는
Rotation 상태를 서버에 저장하고, 한 번 사용된 Refresh credential을 재사용하면 replay로 처리해 해당 token
family의 활성 credential을 폐기한다. 로그아웃은 Cookie 제거와 Refresh 상태 폐기를 함께 수행한다.

Cookie 인증은 CSRF 보호 대상이다. Web과 Core의 허용 origin은 환경별 명시 설정으로 관리하며, 개발 기본값은
`http://localhost:3001`과 `http://localhost:8080`이다. 운영 Cookie는 `Secure`와 명시적 `SameSite`를
필수로 한다. 로그인 후 복귀 위치는 검증된 Web 내부 상대 경로만 OAuth state에 보존한다.

**이유**: provider token을 애플리케이션 세션으로 재사용하면 provider별 만료·권한·탈퇴 정책이 RefInvest의
인가 경계에 스며든다. 별도 JWT와 server-side rotation 상태는 내부 role의 정본을 RefInvest DB에 유지하면서
탈취된 refresh credential의 재사용을 탐지·폐기할 수 있게 한다. Cookie 기반 세션을 쓰면서 CSRF를 비활성화하면
cross-site 요청에 취약하므로, JWT라는 표현 방식과 무관하게 browser cookie 보안 모델을 따른다.

---

## ADR-020 — Backtest Quota & Entitlement Policy (MVP)

**결정**: Backtest 실행 정책은 Subscription tier별 `BacktestPolicy` 경계에서 관리한다. FREE는 월간 30회·동시 1회·최대 365일·`QQQ`/`SPY`/`BTCUSDT`만, PRO는 월간 500회·동시 3회·최대 3,650일·ADR-001의 MVP Asset Universe 전체를 허용한다. PRO도 MVP에서는 유한 한도를 둔다.

실행 시 primarySignalAsset, 모든 Condition이 참조하는 Asset, executionAsset이 현재 tier에 모두 허용되어야 한다. 이 entitlement는 `StrategyVersion` 정의·저장에는 적용하지 않고 `RunBacktest`에만 적용한다. 기간은 API `Period.start`와 `end`를 포함한 UTC calendar day 수로 계산한다.

quota month는 UTC calendar month다. entitlement와 기간 검사를 통과하여 `BacktestRun(PENDING)`이 정상 접수될 때 월간 1회를 소비하며, 이후 Compute 실패에도 자동 환불하지 않는다. 월간 quota와 `PENDING`/`RUNNING` 동시 실행 capacity는 PostgreSQL transaction/locking 또는 동등한 atomic conditional update로 예약한다. 비원자적 check-then-write나 quota만을 위한 Redis는 사용하지 않는다. terminal 상태 전이는 동시 실행 reservation만 해제한다.

월간 한도와 동시 실행 한도 초과는 각각 HTTP 429 (`BACKTEST_MONTHLY_LIMIT_EXCEEDED`, `BACKTEST_CONCURRENCY_LIMIT_EXCEEDED`)로, 기간과 Asset entitlement 위반은 각각 HTTP 403 (`BACKTEST_PERIOD_NOT_ALLOWED`, `ASSET_NOT_ALLOWED_FOR_PLAN`)으로 반환한다. 공통 `ErrorResponse`는 항상 machine-readable `code`와 `message`를 포함한다.

**이유**: quota(얼마나 실행할 수 있는가)와 entitlement(무엇을 실행할 수 있는가)를 분리하면, Controller나 Application 곳곳에 tier 조건을 하드코딩하지 않고 향후 실제 비용·사용량에 따라 정책만 조정할 수 있다. 원자적 reservation은 동시 요청이 한도를 우회하는 것을 막고, PENDING 접수 시점 차감은 Compute 실패·재시도 정책과 billing 정책을 MVP 범위에서 분리한다.

**관련**: ADR-001(MVP Asset Universe), ADR-018(feature-centric Core architecture).

---

## ADR-040 — Backtest Submission Idempotency

**결정**: Core는 논리적 Backtest 제출마다 하나의 불투명한 UUID v4 `Idempotency-Key`를 생성하고, 24시간 이내 transport 재시도에서는 정확히 같은 키를 사용한다. 새 사용자의 제출은 새 키를 사용한다.

`POST /backtests`는 이 헤더를 필수로 하며, Compute는 키·canonical request fingerprint·생성한 `runId`·초기 acceptance를 durable job과 원자적으로 저장한다. 같은 키와 같은 요청은 같은 `runId`가 담긴 최초 `202 Accepted`를 다시 반환하며 실행을 재시작하지 않는다. 같은 키로 다른 payload를 보내면 `409 Conflict`를 반환한다.

**이유**: Core가 timeout 또는 응답 유실을 겪으면 Compute가 job을 접수했는지 알 수 없다. 명시적 idempotency key 없이 재시도하면 중복 계산과 중복 polling state가 생긴다. HTTP header는 계산 입력인 StrategyVersion/Engine payload와 retry identity를 분리한다.

**운영 조건**: Core는 key를 영속화하고 24시간보다 짧은 retry horizon에서만 재사용한다. Compute의 terminal job·request·idempotency 기록은 같은 24시간 retention을 갖는다. 이 결정은 DatasetSnapshot, EngineVersion, 계산 입력을 바꾸지 않으므로 Determinism과 Point-in-Time Correctness에 영향이 없다.

---

## ADR-041 — Backtest Job Retention and Cleanup

**결정**: Compute는 terminal(`COMPLETED`, `FAILED`) job과 runtime result/failure payload, request payload, idempotency 정보를 terminal 전이 후 24시간 보관한다. terminal 전이에는 `terminal_at`을 원자적으로 기록하며, cleanup timer는 매시간 PostgreSQL 현재 시각 기준으로 24시간 이상 지난 terminal row만 최대 1,000건 삭제한다. `PENDING`과 `RUNNING` job은 삭제하지 않는다.

Core는 retention 만료 전에 terminal 결과를 자신의 장기 저장소에 복사한다. 이후 `GET /backtests/{runId}`의 `404`는 Compute가 모르는 run이거나 runtime retention이 만료됐음을 뜻하며, Core는 이를 근거로 같은 논리 요청을 재제출하지 않는다. Compute acceptance를 기록한 dispatch의 `runId`가 `404`이면 Core는 runtime 상태 유실로 간주하고 `BacktestRun`을 `FAILED`로 종료한다. 이때 failure reason은 `COMPUTE_RUNTIME_STATUS_UNAVAILABLE`이며, Core가 실행 시작을 관측하지 못했다면 `actualPeriod`, `datasetSnapshotId`, `engineVersion`은 `null`일 수 있다. terminal 전이로 동시 실행 capacity만 해제하고 월간 quota는 환불하지 않는다.

**이유**: Compute는 실행 runtime만 소유하고 장기 product result는 Core가 소유한다. terminal job과 idempotency record의 retention을 동일하게 두면 replay key가 원 acceptance 없이 남지 않는다. 이 결정은 dataset artifact나 계산 의미론을 변경하지 않는다.

---

## ADR-042 — Backtest Execution Deadline and Process Isolation

**결정**: snapshot-pinned execution attempt 하나의 deadline은 isolated child process 시작부터 결과 생산까지 10분이다. lease를 소유한 worker의 parent process만 PostgreSQL `COMPLETED`/`FAILED` 전이를 기록하고, child process는 local IPC로 outcome만 반환한다.

deadline 초과 시 parent는 child를 종료하고 최대 30초 후 force-kill하며 `EXECUTION_TIMEOUT` terminal failure를 기록한다. 이 경우 일반 transient retry는 하지 않는다. 기존 60초 lease와 15초 heartbeat는 parent worker 장애 복구용이며 execution deadline이 아니다.

**이유**: 비협조적인 engine/data-loader 호출이 worker를 무한정 점유하지 않게 하며, 종료된 child가 terminal state를 다시 덮어쓰지 못하게 한다. `EXECUTION_TIMEOUT`은 Compute error-code 계약에 포함한다.

---

## ADR-044 — Backtest Admission Capacity

**결정**: Compute는 전역적으로 최대 20개의 active job만 허용한다. active는 `PENDING` 또는 `RUNNING`이며 bounded transient retry 대기 중인 `RUNNING`도 포함한다. terminal job은 capacity를 소비하지 않는다.

capacity가 가득 차면 새 제출은 `503 Service Unavailable`, `BACKTEST_CAPACITY_EXCEEDED`, `Retry-After: 30`으로 거절한다. 같은 `Idempotency-Key`와 canonical request의 replay는 capacity 검사보다 먼저 기존 `202/runId`를 반환한다. capacity 거절은 job과 idempotency record를 만들지 않으므로 Core는 안내된 시간 뒤 같은 key로 재시도할 수 있다.

PostgreSQL transaction-scoped advisory lock이 idempotency lookup, active-job count, insert를 직렬화한다. Core는 member별 quota·rate limit·priority를 소유하고 Compute는 user-specific policy를 적용하지 않는다.

**이유**: Compute overload를 durable runtime state 생성 전에 명시적으로 차단하면서도, Core의 product policy와 Compute의 global execution capacity를 분리한다. admission은 snapshot 선택이나 engine semantics를 변경하지 않는다.

---

## ADR-046 — Immutable Snapshot Series API

**결정**: `GET /series`는 요청 시작 시 정확히 하나의 immutable `DatasetSnapshot`을 해석한다. caller가 `datasetSnapshotId`를 주면 그것을 사용하고, 없으면 Compute가 latest published snapshot 하나를 선택한다. 모든 200 응답은 snapshot ID, creation time, adjustment policy를 반환한다.

endpoint는 1~5개의 서로 다른 지원 symbol, 최대 10년·20,000 point의 date range를 받으며 `PRICE`(session close), `RETURN`(직전 available session close 대비 수익률), `NORMALIZED`(범위 안 첫 available close를 100으로 정규화)를 제공한다. Asset별 trading session은 독립적으로 반환하며 common date를 만들거나 이전 값 복제·interpolation을 하지 않는다. expected session의 close 결측 또는 손상된 snapshot artifact는 `503 DATASET_CORRUPTION`, latest snapshot 부재는 `503 DATASET_UNAVAILABLE`, 명시한 snapshot 미존재는 `404`다.

**이유**: Data Explorer가 vendor의 mutable latest data에 의존하지 않고 동일 snapshot을 반복 조회해 재현 가능해야 한다. Cross-calendar 시각 정렬은 Core/Web의 presentation concern이며 Compute의 관측값을 바꾸지 않는다.

---

## ADR-047 — Core Backtest Durable Dispatch and Pre-acceptance Failure

**결정**: Core는 `RunBacktest`가 entitlement·기간·quota 검사를 통과하면 `BacktestRun(PENDING)`과 Compute dispatch record를 같은 database transaction에서 만든다. dispatch record는 Core의 `BacktestRunId`, Compute가 요구하는 UUID v4 `Idempotency-Key`, Compute acceptance 뒤의 `runId`, retry/lease 상태를 Core 소유 데이터로 보관한다.

dispatcher는 database transaction 밖에서 Compute에 요청한다. timeout, connection failure, `503`은 같은 idempotency key로 재시도한다. `400` 또는 `409`처럼 Compute가 job을 영구적으로 접수하지 않은 경우 Core는 해당 run을 `PENDING → FAILED`로 전이하고, failure reason을 기록하며 동시 실행 capacity만 해제한다. 이 pre-acceptance failure에는 actual period, dataset snapshot, engine version이 없다. acceptance가 기록된 뒤 Compute runtime 상태를 더 이상 조회할 수 없는 경우의 종료 규칙은 ADR-041을 따른다. 월간 quota는 Core의 PENDING 정상 접수 시 이미 소비됐으므로 환불하지 않는다.

**이유**: 외부 HTTP 호출을 Core database transaction 안에 넣으면 lock과 rollback 경계가 원격 호출에 묶이고, 반대로 단순 post-commit 호출은 request 유실을 만든다. durable dispatch와 idempotency key를 함께 영속화하면 응답 유실에도 중복 job 없이 재시도할 수 있다. Compute가 job을 만들기 전에 거절한 경우도 public API가 이미 반환한 PENDING run의 terminal outcome으로 표현해야 polling이 무한 대기하지 않는다.

**관련**: ADR-020(quota/entitlement), ADR-040(idempotent Compute submission), ADR-041(runtime retention), ADR-044(admission capacity). Determinism과 Point-in-Time Correctness에 영향 없음.

---

## ADR-048 — Signal-to-Execution Delay Result Contract

**결정**: `BacktestResult.signalExecutionDelay`는 각 체결 거래의 immutable calendar 기준 `entryTime - signalTime`을 시간(hours) 단위 `Decimal`로 기록한다. `distribution`은 거래 입력 순서를 보존한 각 거래의 delay이며, `median`은 오름차순 정렬한 값의 중앙값(짝수 개면 두 중앙값의 산술 평균), `max`는 최댓값이다. 거래가 없으면 `distribution`은 빈 배열이고 `median`, `max`는 `0`이다. 모든 값은 0 이상이며 Core와 Compute는 동일한 `median`, `max`, `distribution` 필드로 이 단위를 사용한다.

`signalTime`과 `entryTime`은 이미 결과의 Trade payload가 사용하는 immutable session completion timestamp다. Reference Price가 execution session open이라는 ADR-010의 체결 가격 규칙은 이 결과 metadata로 변경하지 않는다.

**이유**: Cross-Market 전략에서는 Primary Signal Asset의 신호 확정과 Execution Asset의 다음 가능 세션 사이에 실제 시간 차이가 생긴다. 이를 명시적으로 보존하면 Web이 단순 lag 설정과 calendar 차이로 생긴 실제 체결 지연을 구분해 설명할 수 있다. 단위와 zero-trade 표현을 계약으로 고정해 Core가 임의의 기본값을 만들지 않게 한다.

**관련**: ADR-003(Temporal Rule), ADR-010(Determinism Contract). Determinism과 Point-in-Time Correctness에 영향 없음.

---

## ADR-049 — MVP 시장 관찰과 원시 데이터 노출 경계

**결정**: MVP의 시장 관찰은 사용자가 자신의 증권사·거래소 또는 기타 외부 도구에서 수행한다.
RefInvest는 전략 템플릿, 조건 빌더와 백테스트 결과 해석을 제공한다. 사용자용
Data Explorer, 원시 가격/수익률/정규화 시계열 차트, OHLC 또는 원시 데이터 다운로드는 MVP에서
제공하지 않는다. 증권사·거래소 계정 연동, 주문 실행, 사용자 요청에 따른 실시간 또는 벤더 API
조회도 MVP 범위 밖이다.

Compute는 백테스트를 위해 상업적 이용·캐싱 권리가 확인된 공급사 데이터만 정기 ingestion으로
immutable `DatasetSnapshot`에 적재한다. Web의 어떤 사용자 흐름도 벤더 API를 직접 또는 Core/Compute
경유로 호출하지 않는다. `GET /series`와 Core의 `/assets/series`는 향후 Data Explorer를 위한 계약 및
Compute capability로 유지하되 MVP Web은 호출하거나 노출하지 않는다. 해당 공개 기능을 다시 도입하려면
원시 데이터 표시·재배포·보관 권리를 공급사와 서면으로 확인하고 이 ADR 및 공개 API 정책을 재검토한다.

백테스트 결과의 Equity Curve, Drawdown, 지표, 거래 이벤트와 같이 원시 시세를 재게시하지 않는 파생
결과도 공급사 계약상 고객 대상 표시가 허용되는지 별도로 확인한다. 이 정책은 데이터 라이선스 검토를
대체하지 않는다.

**이유**: 초기 제품의 핵심은 외부에서 관찰한 가설을 조건으로 정의하고, 고정된 스냅샷으로 재현 가능한
과거 시뮬레이션을 수행하는 데 있다. 원시 데이터 탐색·차트·다운로드를 제외하면 벤더 호출량과 고객 대상
데이터 재배포 범위를 줄이고, 계정 연동·실시간 시세·주문 실행에 따른 보안 및 규제 범위 확대를 피할 수
있다. 전략 정의와 백테스트 결과 해석에는 영향을 주지 않는다.

**관련**: ADR-010(Determinism Contract), ADR-014(BTCUSDT 데이터 공급사), ADR-046(Immutable Snapshot Series API). Determinism과 Point-in-Time Correctness에 영향 없음.

---

## ADR-050 — VIX MVP 보류

**결정**: VIX를 MVP Asset Universe에서 제외한다. 따라서 MVP StrategyVersion의
`primarySignalAsset`, `executionAsset`, Condition operand는 VIX를 참조할 수 없다.
MVP Asset Universe는 `QQQ`, `SPY`, `TQQQ`, `SOXL`, `BTCUSDT` 다섯 자산이다.

VIX 데이터 공급·보관·고객 대상 파생 결과 표시 권리가 서면으로 확인되고, 이를 사용하는
가설의 제품 가치를 재검토한 뒤에만 별도 ADR과 계약 변경으로 다시 도입한다.

**이유**: Cboe VIX 데이터의 상업적 사용 조건이 확정되지 않은 상태에서 signal/reference
자산으로 남기면, 사용자에게 생성 가능한 전략이 실제 published DatasetSnapshot에서 실행되지
않는 경로가 생긴다. 자산을 contract와 DSL에서 함께 제외하면 snapshot ingestion, entitlement,
availability, Web 조건 선택지가 일관되게 유지된다.

**관련**: ADR-001(MVP Asset Universe), ADR-014(BTCUSDT 데이터 공급사), ADR-049(MVP 시장 관찰과 원시 데이터 노출 경계). Determinism과 Point-in-Time Correctness에 영향 없음.

---

## ADR-051 — Binance Public Data Archive Ingestion

**결정**: `BTCUSDT` 일봉 데이터의 초기 backfill은 Binance Spot 월별 Kline archive로, 이후 갱신은
전일의 일별 Kline archive로 수행한다. Compute는 source archive의 `.CHECKSUM`을 검증한 뒤에만
정규화 데이터를 적재하고, source URI·SHA-256·수집 시각을 생성하는 `DatasetSnapshot`의 provenance로
보관한다. BTCUSDT의 Trading Session은 `CRYPTO_UTC` 기준 UTC 일봉이며 corporate action adjustment를
적용하지 않는다.

동일 URI의 archive SHA-256이 기존 provenance와 다르면 Compute는 기존 snapshot이나 정규화 행을
수정하지 않고 새 revision과 immutable `DatasetSnapshot`을 생성한다. checksum 검증 실패·archive 누락·
정규화 실패가 발생하면 새 snapshot을 publish하지 않으며, 이미 publish된 snapshot은 계속 백테스트에
사용할 수 있다.

**이유**: 월별 archive는 초기 이력 적재의 요청 수를 줄이고, 일별 archive는 운영 갱신 범위를 고정한다.
공개 archive도 사후 수정될 수 있으므로, URI만 기록하면 과거 입력을 식별할 수 없다. source hash와
수집 시각을 snapshot에 고정하면 공급사 수정이 기존 결과를 조용히 바꾸지 않으며 ADR-010의 재현성
계약을 유지한다.

**관련**: ADR-003(Temporal Rule), ADR-010(Determinism Contract), ADR-014(BTCUSDT 데이터 공급사), ADR-049(MVP 시장 관찰과 원시 데이터 노출 경계). Determinism에 영향 있음: 동일 snapshot에는 동일한 source artifact와 정규화 데이터가 고정된다. Point-in-Time Correctness에 영향 없음.
