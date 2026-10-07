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

---

## ADR-052 — Backtest Result Visualization Contract

**결정**: MVP 결과 화면은 원시 시세를 노출하지 않고, pinned `BacktestResult` 안의 파생
portfolio series와 거래 이벤트만으로 다음을 표시한다.

- Equity Curve는 Strategy와 Primary Benchmark(Execution Asset Buy & Hold)를 함께 표시한다.
- Drawdown은 각 equity curve의 `value / runningPeak - 1`로 Web이 결정론적으로 계산한다.
  별도의 drawdown series를 Core/Compute 계약에 중복 저장하지 않는다.
- Timeline은 각 `Trade`의 `signalTime`, `entryTime`, `exitTime`으로 만든다. 결과에는
  `signalExecutionMarketRelation`을 보존해, Primary Signal Asset과 Execution Asset의
  calendar가 다를 때(`CROSS_MARKET`) 신호와 체결의 시장 구분을 명확히 표시한다.

`equityCurve`와 각 Buy & Hold의 `equityCurve`는 모두 시작값 `1`의 portfolio index다.
Strategy curve는 Execution Asset의 session별 전략 자본가치이고, Buy & Hold curve는 해당
asset의 session close를 첫 available close로 나눈 값이다. date/value는 원시 close나 OHLC가
아니며 백테스트가 고정한 Snapshot의 파생 결과다. 서로 calendar가 다른 series를 차트에 함께
그릴 때 Web은 session을 합성하거나 이전 값을 복제·보간하지 않고, 관측된 date에만 값을 표시한다.

Primary Benchmark에는 asset symbol과 curve를 항상 포함한다. Secondary Reference는
`primarySignalAsset != executionAsset`일 때만 포함하며, Signal Asset Buy & Hold를 나타낸다.
이는 Cross-Market 여부와 별개로 서로 다른 signal/execution asset을 해석할 수 있게 하는 참고값이다.

**이유**: 기존 엔진은 이미 pinned snapshot에서 B&H curve를 계산하지만, public result에는
집계 지표만 전달해 Strategy와 동일 축에서 비교할 수 없었다. 비교 curve와 event의 의미를 결과
계약에 고정하면 Web이 원시 가격을 다시 요청하거나 자체 시장 데이터를 추측하지 않고 결과를
해석할 수 있다. Drawdown은 동일한 immutable portfolio index로부터 유일하게 계산되므로 중복
저장은 version drift와 persistence 용량만 늘린다.

**관련**: ADR-003(Temporal Rule), ADR-007(Benchmark), ADR-010(Determinism Contract),
ADR-048(Signal-to-Execution Delay), ADR-049(원시 데이터 노출 경계). Determinism과
Point-in-Time Correctness에 영향 없음.

---

## ADR-053 — 재현 가능한 성능 Benchmark와 초기 Runtime Observability 분리

**결정**: 성능 최적화의 근거는 고정된 StrategyVersion, DatasetSnapshot, 기간, Fee/Slippage,
Engine version 및 실행 환경을 사용하는 반복 가능한 benchmark로 삼는다. 최적화 전후에는 canonical
`BacktestResult` hash, trade count, terminal status와 deterministic/cross-calendar regression을 먼저
비교한다. 결과가 달라지는 최적화는 성능 개선으로 인정하지 않는다.

Prometheus metric, 소수의 Grafana dashboard, JSON structured log와 `runId` correlation은 개발·검증
환경의 runtime 상태와 병목 후보를 찾는 별도 관측 수단으로 둔다. runtime traffic은 입력과 실행 조건이
통제되지 않으므로 benchmark의 결과 동일성이나 최적화 효과를 대신 증명하지 않는다.

현재는 baseline과 운영 조직·트래픽 특성이 확정되지 않았다. 따라서 완전한 운영 관측 플랫폼을 선제
도입하지 않고 Alertmanager/당직, 자동 SLO와 error budget, 분산 tracing, 중앙 로그 플랫폼, continuous
profiling, frontend RUM, Prometheus HA/장기 storage를 보류한다. 초기 performance CI는 correctness와
functional failure만 차단하고 성능 저하는 경고로 다룬다. 절대 latency SLO와 자동 성능 gate는 실제
baseline과 환경 편차를 확보한 뒤 별도 결정한다.

**이유**: benchmark는 통제된 조건에서 원인과 개선 효과를 재현하기 위한 실험이고 runtime
observability는 다양한 실제 실행에서 이상과 병목 위치를 발견하기 위한 수단이라 표본과 판정 목적이
다르다. 두 데이터를 분리해야 환경·입력 편차를 최적화 효과로 오인하지 않는다. 또한 현 단계에 완전한
운영 플랫폼을 도입하면 baseline을 만드는 데 필요한 최소 계측보다 운영 복잡성이 먼저 증가한다.

세부 scenario, 단계 경계, metric 의미와 cardinality, 저장소 ownership, artifact 보관 및 회귀 정책은
[`docs/PERFORMANCE_OBSERVABILITY.md`](PERFORMANCE_OBSERVABILITY.md)를 따른다.

**관련**: ADR-003~005(Temporal/Data Correctness), ADR-010(Determinism Contract),
ADR-012(Core↔Compute 비동기 구조). Determinism과 Point-in-Time Correctness에 영향 없음.

---

## ADR-054 — RefInvest 제품 정체성과 Trading Review 우선 검증

**결정**: RefInvest를 사용자가 설계한 전략과 실제 거래 행동을 재현 가능한 데이터로 검증하는 개인
투자 분석 서비스로 정의한다. 제품 축은 Strategy Validation과 Behavior Review이며, 향후 두 결과를
Execution Gap에서 연결할 수 있다.

현재 구현 우선순위는 Trading Review TR-0/TR-1이다. Strategy Validation의 기존 계약과 구현은 유지하되
추가 기능 개발은 잠시 중단한다. Trading Review는 실제 체결의 행동과 함께 나타난 결과를 설명하며,
종목/방향/시점 추천, 심리 진단, 인과 단정, 주문 실행을 제공하지 않는다. `행동 교정`은 사용자가 직접
선택한 BehaviorFocus의 후속 준수 측정이 가능해진 이후 범위다.

**이유**: 실제 거래 기록은 전략 작성 없이 첫 분석에 도달할 수 있고 기존 Core/Compute/Web 기반을
재사용해 제품 가치를 더 빠르게 검증할 수 있다. 동시에 검증 가능한 근거와 결정론이라는 RefInvest의
기존 신뢰 원칙을 유지한다.

**관련**: ADR-010(Determinism), ADR-015(AI 역할), `docs/TRADING_REVIEW.md`.

---

## ADR-055 — Trading Record 확장 경계와 상품별 Reconstruction

**결정**: 실제 거래 도메인은 `TradingAccount → TradingBook → immutable LedgerRevision` 경계를 사용한다.
TradingBook은 같은 product family, position mode, settlement asset의 독립 복원 단위다. Backtest
`Asset`과 실제 `InstrumentDescriptor`, Backtest `Trade`와 canonical `TradingRecord`/`ReviewUnit`,
`DatasetSnapshot`과 `LedgerRevision`을 별도 타입으로 유지한다.

Canonical record는 Execution/Fee/VenueReportedPnl 등 type별로 표현한다. 상품 확대는 공통 모델에
nullable 필드를 누적하지 않고 상품별 Terms, Adapter, `ReconstructionPolicy`, `ReviewUnit`을 추가한다.
Behavior Metric은 required `AnalysisCapability`를 선언하며 capability가 없을 때 숫자 0을 만들지 않는다.
모든 derived unit과 finding은 `TradingSourceArtifact` row까지 provenance를 보존한다. Import는 원자적으로
accept/reject하고 accepted record/Revision을 수정하지 않는다. Reconstruction은 execution 수량과 통화별
fee/reported PnL의 allocation 보존을 검증하며, 불완전하거나 모순된 ReviewUnit을 추정으로 완성하지 않는다.

**이유**: 선물 flat-to-flat episode, Spot inventory, Options multi-leg는 같은 거래 단위를 공유하지 않는다.
명시적 상품 경계와 capability gating은 현재 Linear Perpetual을 정확히 구현하면서 다른 상품의 의미를
왜곡하지 않고 확장할 수 있게 한다.

**관련**: ADR-054, `docs/TRADING_REVIEW.md` §3~10.

---

## ADR-056 — Trading Analysis 결정론과 증거 계약

**결정**: Trading Analysis 결과는 다음 입력에 대해 결정론적이어야 한다.

```text
LedgerRevision
+ ReconstructionPolicyVersion
+ MetricDefinitionVersion
+ FindingRuleSetVersion
+ AnalysisConfig
= TradingAnalysisResult
```

`TradingAnalysisRun`은 `PENDING → RUNNING → COMPLETED | FAILED` 단방향 상태를 사용한다. COMPLETED에는
reconstruction quality report와 result가 함께 있어야 한다. Core가 사용자 원장과 장기 결과를 소유하고,
Compute는 normalization/reconstruction/analytics와 제한된 runtime 상태만 소유한다.

`BehaviorObservation`은 관찰 가능한 사실만 표현한다. `BehaviorFinding`은 eligible/excluded sample과
제외 사유, comparison definition, supporting/comparison ReviewUnit과 source evidence를 제공한다. capability
부족, 적용 불가, 표본 부족은 실제 값 0과 구분한다. 표본 수만으로 `HIGH CONFIDENCE`를 표시하거나 행동이
손익의 원인이라고 표현하지 않는다. 기간 추세는 같은 definition version/config로 재계산한 결과만 비교한다.

**이유**: Parser·복원·분석 정의는 독립적으로 바뀔 수 있다. 입력과 각 버전을 고정하고 근거 경로를
보존해야 결과 변화의 원인을 설명하고 과거 Review를 재현할 수 있다.

**관련**: ADR-010, ADR-054, ADR-055.

---

## ADR-057 — Binance USDⓈ-M Trading Review MVP 범위

**결정**: 첫 Trading Review는 Binance USDⓈ-M Linear Perpetual, 사용자 선언 One-way Mode, 공식 Trade
History와 Position History CSV의 묶음 수동 업로드만 지원한다. 시작과 종료가 모두 관측되고 두 source가
유일하게 조정된 `COMPLETE` FuturesPositionEpisode만 분석한다. left/right censored, inconsistent,
ambiguous, unsupported episode를 임의 보정하지 않는다.

MVP outcome은 Trade History `Realized Profit` 합계에서 같은 통화의 trading fee를 차감하며 Position
History `Closing PNL`은 gross PnL reconciliation에 사용한다. Funding은 제외 사실을 표시한다. 실제
margin/leverage를 확정할 수 없으므로 ROE를 제공하지 않는다. 초기 분석은 다음 세 가지다.

- `ENTRY_WITHIN_WINDOW_AFTER_LOSS`
- `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS`
- `WIN_LOSS_HOLDING_DURATION_DIFFERENCE`

같은 execution에서 발생한 position reversal은 일반 rapid re-entry에서 분리한다. API sync, Hedge,
COIN-M, Spot, Options, 열린 포지션, 실시간 알림, AI, Strategy mapping과 Execution Gap은 제외한다.

**이유**: 단일 venue/product/mode로 reconstruction 의미를 제한하고 Trade History의 상세 체결과 Position
History의 종료 요약을 함께 검증해야 첫 결과의 정확성과 evidence chain을 확인할 수 있다. 다른 상품이나
확인되지 않은 CSV 변형은 명시적으로 실패시킨다.

**관련**: ADR-054~056, ADR-058, `docs/TRADING_REVIEW.md`, `docs/ROADMAP.md` TR-0/TR-1.

---

## ADR-058 — Binance dual-artifact import와 reconciliation 계약

**결정**: 하나의 `TradingImportSession`에는 `BINANCE_USDS_TRADE_HISTORY_V1`과
`BINANCE_USDS_POSITION_HISTORY_V1` artifact가 각각 정확히 하나씩 필요하다. 두 dialect는 확인된 영문
logical field를 이름과 대소문자까지 정확히 판별하고 순서 변경과 알 수 없는 추가 column만 허용한다. 실제
익명 표본의 두 artifact는 모두 UTF-8 BOM, comma delimiter, CRLF였고 quote character는 없었다. V1 parser는
UTF-8의 optional 단일 leading BOM을 transport marker로 제거해 첫 header를 `Uid`로 읽고 `\uFEFFUid`라는
field로 취급해서는 안 된다. comma CSV, CRLF와 LF record terminator, RFC 4180 double-quote escaping을
지원한다. quote 유무와 BOM 필수 여부, 향후 Binance export의 line ending은 한 표본에서 일반화하지 않는다.
required header 누락은 `REQUIRED_COLUMN_MISSING`, duplicate required header나 이름/대소문자 변경은
`UNSUPPORTED_SOURCE_SCHEMA`, decode/CSV row width 오류는 `FILE_FORMAT_INVALID`다.

Trade `Time`과 Position `Opened`/`Closed`는 모두 exact `yyyy-MM-dd HH:mm:ss` source local timestamp다.
CSV 안에는 offset이 없으므로 사용자가 session에 고정한 IANA `sourceTimezone`을 적용해 UTC instant로
canonicalize한다. export filename의 timezone 표시는 파일 내용의 증거가 아니며 parser가 timezone을
추론하는 데 사용하지 않는다. 각 artifact는 다른 기간을 가질 수 있으므로 Trade는 `Time`의 min/max,
Position은 `Opened`의 min과 `Closed`의 max를 earliest/latest observed instant로 계산하고,
row count, source timezone, `SECOND` precision과 `coverageCompleteness=NOT_PROVEN`을 독립 보존한다. 관측
min/max만으로 거래소 export가 그 사이의 모든 record를 포함한다고 단정하지 않는다.

source `Symbol`은 Unicode를 포함할 수 있는 lossless opaque identifier다. trim, case conversion, Unicode
normalization 또는 ASCII-only validation으로 identity를 바꾸지 않으며 exact symbol을 pinned
`InstrumentDescriptor` registry에 조회해 지원 상품을 판정한다. fee asset의 별도 ASCII grammar는 symbol
규칙이 아니다.

Trade identity는 `HMAC(stableIdentityKey, canonicalTuple(venue, Uid))`로 만든 account fingerprint와 exact
Symbol, raw Trade ID의 length-prefixed canonical tuple이며 ID field는 문자열로 보존한다. opaque symbol을
separator string concatenation으로 조합하지 않는다. identity key는 별도 version을 기록하고 회전 시 기존
fingerprint를 연결하는 migration이 필요하다. 같은 identity와
같은 내용은 provenance를 합쳐 deduplicate하며 내용이 다르면 conflict로 거절한다. Position row는 stable
venue ID가 확인되지 않았으므로 `artifactId + sourceRowNumber`로 식별하고 값 기반 deduplication을 하지 않는다.
원본 UID는 log, public API와 AI context에 노출하지 않는다.

Trade row는 Execution/Fee/VenueReportedPnl record, Position row는 `VenuePositionSummaryRecord`로 정규화한다.
Fee source amount의 부호를 보존해 양수는 cost, 음수는 rebate로 처리한다. CSV로 확인할 수 없는 execution
origin은 `UNKNOWN`이다. 서로 다른 fee asset의 valuation이 없으면 affected episode의 net outcome capability만
누락시키고 import를 거절하지 않는다.

Trade row의 source 순서나 `Order ID`는 의미 순서가 아니다. instrument별 execution을 canonical
`occurredAt`과 숫자 Trade ID로 정렬해 flat-to-flat episode를 복원하며, 같은 Order ID의 여러 행도 각각의
fill로 보존하고 주문이나 execution 하나로 합치지 않는다. 그 뒤 Position summary의 direction, exact
opened/closed instant, max/closed quantity와 realized PnL 제약으로 유일하게 조정한다. 상태는 `EXACT`,
`WITHIN_ROUNDING_TOLERANCE`, `AMBIGUOUS`, `INCOMPLETE_TRADES`, `SUMMARY_MISMATCH`,
`UNSUPPORTED_OVERLAP`이며 앞의 두 상태만 분석 가능하다. tolerance는 source decimal scale과 고정된
instrument terms에서 도출한다. 가장 가까운 후보를 임의 선택하지 않는다. V1 Position status는 정확히
`Closed`만 지원한다. Import compute job은 allocation과 status를 versioned `ReconciliationManifest`로 반환하고
LedgerRevision이 그 hash와 reconstruction version을 고정한다. Analysis는 pinned manifest를 사용한다.

Trade History에만 `Uid`가 있고 Position History에는 account identifier가 없다. Trade UID fingerprint는 Trade
row identity에는 쓰지만 두 artifact가 같은 계정·sub-account에서 왔음을 증명하지 않는다. 사용자 선언,
같은 ImportSession upload, 기간·symbol·episode reconciliation evidence와 향후 authenticated venue evidence를
구분해 보존한다. 현재 CSV만으로 실제 동일 계정을 강하게 증명할 수 없으며 잘못 조합한 파일 탐지는
미확인 경계다. raw UID는 canonical identity 처리에 필요할 수 있어도 log, public API, retained evidence와
AI context에 평문으로 노출하지 않는다.

**이유**: Trade History만으로 계산한 episode를 venue의 Position History와 대조하면 누락·중복·경계 오류를
사용자에게 노출할 수 있다. 두 source의 정밀도와 identity가 다르므로 하나의 row 모델이나 단순 시간 join을
쓰면 모호함이 숨겨진다. 원자적 session과 명시적 reconciliation 상태가 분석 신뢰성과 향후 상품 확장 경계를
동시에 보존한다.

**관련**: ADR-055~057, `docs/TRADING_REVIEW.md` §6~9.

---

## ADR-059 — Binance One-way Futures Reconstruction and Reconciliation

**결정**: Binance USDⓈ-M Linear Perpetual One-way V1은 exact source symbol을 pinned instrument에 매핑한 뒤
instrument별 execution을 canonical UTC `occurredAt`, 그다음 숫자로만 구성된 raw `Trade ID`의
arbitrary-precision integer 값 순으로 처리한다. 전체 canonical serialization의 partition key는 instrument며
source row 순서와 `Order ID`는 tie-breaker가 아니다. ID 원문과 decimal source scale은 보존한다. signed
delta는 BUY `+quantity`, SELL `-quantity`이고, exact Decimal
`nextPosition = previousPosition + signedDelta`의 부호와 절댓값으로 `OPEN/INCREASE/REDUCE/CLOSE`를
결정한다. epsilon으로 flat을 만들거나 서로 다른 symbol의 동시 체결에 전역 순서를 만들지 않는다.

한 execution이 position 부호를 바꾸면 기존 episode의 `REVERSAL_CLOSE`와 새 episode의
`REVERSAL_OPEN`으로 수량을 분할한다. reported realized PnL은 closing allocation에 전부, opening에는 0을
배정한다. fee는 closing/opening 수량 비율로 source scale에서 배분하고 결정론적 잔여분을 opening에 두어
수량, asset별 fee와 PnL 합계를 보존한다. 모든 금융 source 값은 arbitrary-precision Decimal이며 평균값은
exact numerator/denominator로 유지하고 표시 직전에만 규정된 rounding을 적용한다.

관측된 flat-to-flat과 보존 검사를 통과한 episode만 `COMPLETE`다. 시작/종료가 관측되지 않거나 모순되거나
지원하지 않는 자료는 각각 `LEFT_CENSORED`, `RIGHT_CENSORED`, `INCONSISTENT`, `UNSUPPORTED`로 보존하고
추정으로 승격하지 않는다. Trade History는 execution과 gross realized PnL의 oracle, Position History는
종료 position aggregate constraint의 oracle이며 어느 한쪽으로 다른 쪽을 덮어쓰지 않는다.

reconciliation은 instrument/exact source symbol, direction, exact opened/closed instant, max quantity, closed volume과 gross PnL로
candidate graph를 만들고 unique one-to-one matching만 채택한다. 평균 entry/close price는 equality 기반
secondary evidence일 뿐 nearest heuristic이 아니다. tolerance는 source decimal scale, pinned step/tick,
settlement precision에서 field별로 계산하며 고정 magic number를 사용하지 않는다. `EXACT`와
`WITHIN_ROUNDING_TOLERANCE`만 분석 대상이다.

CSV에서 manual/API/bot/liquidation/ADL을 알 수 없으므로 execution origin은 `UNKNOWN`이고
`EXECUTION_ORIGIN_KNOWN` capability를 부여하지 않는다. import는 allocation, tolerance, match/status,
exclusion과 provenance를 canonical `ReconciliationManifest`에 기록한다. `LedgerRevision`은 manifest hash와
reconstruction policy version을 고정하며 Analysis는 이를 다시 추정하지 않는다. canonical input, pinned
terms와 version이 같으면 manifest hash가 같아야 한다.

계산식, 상태 전이, tolerance, 실패 결과와 golden scenario의 상세 정본은
[`docs/TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md)다.

**이유**: 부분 체결, reversal, exact-second venue summary와 source별 반올림을 구현자 재량에 맡기면 같은
입력에서도 episode 경계, fee/PnL 귀속과 eligible 모집단이 달라진다. signed state machine, 보존식과
one-to-one constraint matching을 고정하면 모호함을 숨기지 않으면서 결과와 근거를 재현할 수 있다.

**관련**: ADR-054~058, `docs/DOMAIN.md` TR-I03~TR-I12,
`docs/TRADING_RECONSTRUCTION.md`.

---

## ADR-060 — Trading Behavior Metric Definitions

**결정**: Trading Review 초기 세 Metric의 계산 정본을
[`docs/TRADING_ANALYTICS.md`](TRADING_ANALYTICS.md)로 고정한다. V1은 `FuturesPositionEpisode`만 사용하고
`COMPLETE`이면서 reconciliation이 `EXACT` 또는 `WITHIN_ROUNDING_TOLERANCE`인 episode만 eligible로
승격한다.

공통 outcome은 same-settlement-currency `netTradingPnl = realized profit - signed source fee`의 exact
Decimal 부호로 `LOSS | BREAKEVEN | WIN | UNAVAILABLE`을 정한다. 양수 fee는 cost, 음수 fee는 rebate이며
Funding은 제외한다. fee 누락이나 다른 fee asset valuation 누락으로 net PnL을 만들 수 없으면 gross PnL로
대체하지 않고 affected metric을 `MISSING_CAPABILITY`로 둔다.

`ENTRY_WITHIN_WINDOW_AFTER_LOSS`의 V1 window는 `0 < entry.openedAt - loss.closedAt <= PT30M`이다. 정확히
30분은 포함하고 같은 instant는 제외한다. loss는 reversal close로 종료됐어도 subject가 될 수 있지만
`REVERSAL_OPEN`은 일반 re-entry에 넣지 않고 별도 immediate-reversal evidence로 남긴다. same/different
instrument와 direction을 모두 허용한다. window 안 첫 `openedAt`을 찾고 그 instant의 모든 entry를 순서 없는
`relatedEntrySet`으로 연결한다. loss 하나는 entry 수와 관계없이 numerator를 최대 한 번만 증가시킨다.
analysis end까지 전체 window를 볼 수 없는 loss는 제외한다.

`INITIAL_EXPOSURE_CHANGE_AFTER_LOSS`는 위 relation을 그대로 재사용한다. initial exposure는 reconstruction이
고정한 첫 opening allocation 직후 수량에 그 execution price와 pinned contract multiplier를 곱한
initial notional이다. 같은 timestamp의 이후 increase나 다른 episode를 합산하지 않는다. primary는 각
related entry initial notional을 직전 loss episode initial notional로 나눈 ratio들의 중앙값
`NEXT_ENTRY_VS_LOSS_EPISODE_RATIO`다. 다른 settlement currency는 환산하지 않는다. supporting
`AFTER_LOSS_VS_PRIOR_BASELINE_RATIO`는 event 전에 이미 종료된 same-instrument, same-direction 과거 episode의
initial notional 중앙값만 사용하고 subject/loss, subject entry와 미래 episode를 제외한다. 전체 기간 미래
자료를 쓰는 descriptive baseline은 V1에 없으며 도입하면 별도 definition/version으로 만든다.

`WIN_LOSS_HOLDING_DURATION_DIFFERENCE`는 Trade History execution instant의
`holdingDuration = closedAt - openedAt`을 사용한다. breakeven과 unavailable은 win/loss group에 넣지 않는다.
primary는 `lossMedian - winMedian`이고 group별 distribution, mean과 median ratio는 supporting measure다.
duration 0과 reversal episode를 허용하며 outlier를 제거하지 않는다.

Metric 상태는 `AVAILABLE | INSUFFICIENT_SAMPLE | MISSING_CAPABILITY | NOT_APPLICABLE`만 사용하고 우선순위는
`NOT_APPLICABLE → MISSING_CAPABILITY → INSUFFICIENT_SAMPLE → AVAILABLE`이다. 계산 minimum과 Finding 노출
minimum을 분리하며 threshold는 permanent constant가 아니라 canonical `AnalysisConfig`의 versioned default다.
V1 계산 default는 re-entry loss 1, exposure pair 1, prior baseline 3, holding win/loss 각 2이고 Finding 노출
default는 각 subject/group 5다. threshold 변경은 새 AnalysisRun으로 재분석하고 계산 의미 변경은 Metric
Definition Version을 올린다.
표본 수만으로 confidence를 만들지 않는다.

Capability는 episode scope로 판정한다. 세 Metric은 net outcome의 `CLOSED_OUTCOME`/`FEES`, exact
execution-based 경계의 `STABLE_EPISODE_TIME`을 요구하며 exposure Metric은 `INITIAL_EXPOSURE`도 요구한다.
`STABLE_EPISODE_TIME`은 pinned manifest에 summary timestamp로 보정하지 않은 non-null execution
`openedAt`/`closedAt`이 있고 순서가 유효할 때만 부여한다. 다른 capability의 필요 여부와 missing result는
계산 정본의 matrix를 따른다.

모든 결과는 analysis period, definition/version, canonical config, population/eligible/excluded count와 이유,
subject/comparison membership, relation, scalar input, capability snapshot과 다음 evidence chain을 보존한다.

```text
BehaviorMetric → BehaviorObservation → FuturesPositionEpisode
→ PositionAllocation → TradingRecord → source CSV row
```

대용량 membership은 별도 immutable evidence artifact로 page할 수 있지만 logical result와 canonical hash에서
생략하지 않는다. 같은 LedgerRevision/ReconciliationManifest, definition versions와 config는 같은 population,
exclusion, scalar result와 evidence hash를 만든다.

**이유**: 자연어 이름만으로는 loss fee 처리, window 경계, 동시 cross-instrument entry, reversal, exposure
분모, 미래 baseline과 표본 부족을 구현자마다 다르게 해석할 수 있다. outcome, relation, comparison,
capability와 evidence를 함께 고정해야 같은 원장에서 재현 가능한 행동 결과를 만들고 unavailable을 실제
0과 구분할 수 있다.

**관련**: ADR-054~059, `docs/DOMAIN.md` TR-I04, TR-I09~TR-I13,
`docs/TRADING_ANALYTICS.md`, `docs/TRADING_RECONSTRUCTION.md`.

---

## ADR-061 — Trading Data Retention and Deletion

**결정**: Trading Review lifecycle의 상세 정본을
[`docs/TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md)로 고정한다. Core가 private object storage의
raw CSV와 metadata, allowlist `SourceEvidenceSnapshot`, canonical ledger와 장기 결과를 소유하고 Compute는
job에 필요한 최소 payload와 terminal 후 최대 24시간의 encrypted runtime만 보관한다.

Raw CSV는 artifact별 envelope encryption으로 accepted import의 `acceptedAt + P7D`, rejected import의
`rejectedAt + PT24H`까지만 보관한다. non-terminal upload는 `uploadedAt + PT24H`에 만료한다. 기간은
`TRADING_DATA_POLICY_V1`에 고정하며 server configuration으로 연장하지 않는다. raw는 application backup과
object versioning에서 제외한다. 전송·저장 암호화, KMS 또는 동등한 key-management 경계, key version/rotation,
private object와 최대 5분의 job-bound signed read를 요구한다. key를 source/environment file에 저장하지 않는다.

Raw 만료 뒤에는 full line 대신 `SOURCE_EVIDENCE_MASK_V1` allowlist snapshot을 유지한다. UID는 snapshot에
남기지 않고 Trade/Order ID는 owner+artifact scope HMAC alias로 masking한다. Symbol, 시각, 수량, 가격, fee와
PnL은 owner evidence에 표시할 수 있지만 operator/log/AI에는 노출하지 않는다. UI는 이를 원본 행이 아닌
“원본 삭제 후 보존된 evidence snapshot”으로 표시한다. raw 삭제 뒤 Adapter reparse는 불가능하고 재업로드가
필요하지만 retained canonical record만 필요한 재분석은 가능하다.

사용자는 raw-only, ImportSession, Book, Account와 Member 범위 삭제를 요청할 수 있다. session 삭제는 그
session을 포함한 immutable LedgerRevision 전체와 종속 result를 purge하고, Book/Account/Member는 하위 scope를
cascade한다. 불변 데이터를 수정해 삭제를 흉내 내지 않는다. 같은 hash의 다른 owner object를 공유하거나
삭제하지 않는다. deletion request commit 즉시 정상 접근·신규 job·retry를 차단하고 24시간 안에 primary
DB/live object/Compute runtime을 purge한다.

Artifact는 `ACTIVE → RETENTION_SCHEDULED → DELETION_PENDING → DELETED | DELETION_FAILED`, deletion request는
`REQUESTED → CANCELLING_JOBS → PURGING_PRIMARY → PURGING_OBJECTS → COMPLETED | FAILED`를 사용한다. retry는
미완료 checklist만 idempotently 처리한다. `DELETED`/`COMPLETED`는 live object와 usable key/primary data가
실제로 없을 때만 사용하며 partial failure를 성공으로 표시하지 않는다. 모든 job/callback은
`deletionGeneration`을 확인하고 tombstone보다 오래된 결과를 저장하지 않는다.

Backup은 별도 암호화 경계에서 최대 30일 rolling retention을 사용한다. 정상 접근은 삭제 요청 즉시
차단하지만 backup의 최종 물리 제거는 live purge 완료 후 최대 30일이다. restore는 격리 상태에서 180일
보관하는 payload-free deletion ledger를 replay하고 삭제 scope 0건을 검증한 뒤에만 서비스를 연다.
Operational log는 30일, operator/deletion audit는 180일이며 raw row/UID/ID/fingerprint/Symbol/시각/수량·가격·
fee·PnL·filename/path를 기록하지 않는다. operator 거래 접근은 기본 차단하고 승인·MFA·ticket·최대 30분의
break-glass와 audit를 요구한다.

AI Summary/Query는 MVP 밖이다. 별도 ADR, 사용자 동의와 provider retention/no-training 확인 전에는 Trading
Review 데이터를 외부 AI provider에 보내지 않는다. 이후에도 raw/UID/fingerprint/full Trade·Order ID/source
row는 금지하고 allowlist Metric/evidence summary와 Symbol·금액 masking만 후보로 삼는다.

각 import는 `dataPolicyVersion`, `retentionPolicy`, `maskingPolicyVersion`, `rawExpiresAt`,
`evidenceRetention`, `deletionPolicyVersion`을 고정해야 accept할 수 있다. 정책 변경은 신규 import부터
적용하며 기존 raw TTL을 소급 연장하지 않는다. redaction/access/deletion 강화와 explicit user deletion은
모든 version에 즉시 적용한다.

**이유**: full raw를 영구 보관하지 않으면서 사용자에게 계산 근거를 제공하고, immutable ledger·비동기
job·backup이 삭제 권리를 무력화하지 않게 하려면 retention clock, evidence 대체물, tombstone과 restore
순서를 하나의 계약으로 고정해야 한다. 7일/24시간은 초기 Adapter 결함 복구 창과 raw 노출 부담을 구분한
MVP 절충이고 30일 backup 상한은 재해 복구와 최종 삭제 시점을 동시에 명시한다.

**확정된 MVP 기본값**: accepted raw 7일, rejected/non-terminal raw 24시간, live purge 24시간, backup 30일,
operational log 30일, audit/deletion ledger 180일, Option B evidence snapshot과 위 상태/접근 경계.

**운영 configuration**: bounded retry 횟수·backoff·alert threshold와 provider adapter 식별자는 설정할 수
있지만 확정 기간, 상태 의미, masking allowlist와 SLA를 바꿀 수 없다.

**법률 검토 필요**: 출시 지역의 필수 보관/삭제 의무, audit 기간, subprocessor/backup 고지와 legal hold.
검토 전 임의의 장기 보관이나 legal hold를 추가하지 않는다.

**후속 결정 대상**: ADR-062로 확정한 versioning/reprocessing의 persistence/OpenAPI/worker,
deletion worker와 cloud KMS/backup 제품 선택, 향후 AI data boundary.

**관련**: ADR-054~060, `docs/DOMAIN.md` TR-I01~TR-I24,
`docs/TRADING_DATA_LIFECYCLE.md`.

---

## ADR-062 — Trading Analysis Versioning and Reprocessing

**결정**: Trading Review version compatibility, 재처리와 result lineage의 상세 정본을
[`docs/TRADING_VERSIONING.md`](TRADING_VERSIONING.md)로 고정한다. 하나의 `analyticsVersion` 대신
`sourceDialectVersion`, `adapterVersion`, `normalizerVersion`, `canonicalSchemaVersion`,
`instrumentTermsVersion`, `reconstructionPolicyVersion`, `reconciliationVersion`,
`reviewUnitSchemaVersion`, `metricDefinitionVersion`, `findingRuleSetVersion`, `analysisConfigVersion`,
`evidenceSchemaVersion`, `dataPolicyVersion`을 분리한다. 사용자에게 의미 있는 definition/schema/policy는
명시적 immutable version, 실행 artifact는 immutable `implementationDigest`, config와 합성 VersionSet은
canonical serialization hash로 식별한다. source commit이나 mutable image tag만으로 runtime version을
식별하지 않는다.

version registry는 `ACTIVE | DEPRECATED | READ_ONLY | UNAVAILABLE`을 사용한다. 구현 artifact를 영구 보관한다고
가정하지 않으며, old implementation이 없어 동일 version replay가 불가능해도 lifecycle상 남아 있는 result
payload와 version metadata는 조회할 수 있다. 각 producer/consumer는 exact compatibility를 선언하고 unknown
field/forward compatibility는 schema가 명시한 경우만 허용한다. 호환되지 않는 version을 가장 가까운 version으로
대체하거나 암묵 변환하지 않는다.

accepted record, `LedgerRevision`과 완료된 `TradingAnalysisResult`는 수정하지 않는다. Adapter/normalizer 변경은
active raw에서 다시 파싱해 새 Revision을 만들고, canonical schema 변경은 등록된 lossless migration 또는 raw
reparse로 새 Revision을 만든다. instrument terms, reconstruction 또는 reconciliation 변경은 retained compatible
canonical record로 새 manifest와 Revision을 만든다. Metric, Finding 또는 `AnalysisConfig` 변경은 compatible
기존 Revision으로 새 `TradingAnalysisRun`을 만든다. Finding-only 재평가도 immutable result/evidence 경계를
위해 새 Run이다. data policy 변경은 계산 재실행이 아니라 ADR-061 lifecycle로 처리한다.

재처리는 기존 ImportSession이나 AnalysisRun을 다시 열지 않고 Core 소유 `TradingReprocessingRun`으로 추적한다.
type은 `REPARSE_SOURCE | REBUILD_LEDGER | RERUN_RECONSTRUCTION | RERUN_ANALYTICS |
REEVALUATE_FINDINGS`, status는 `PENDING → VALIDATING_INPUT → RUNNING → COMPLETED | FAILED | CANCELLED`다.
새 Revision과 Analysis를 함께 요청해도 완전한 결과, hash, lineage와 deletion generation 검증 전에는 어느
산출물도 current 결과로 publish하지 않는다. 실패·취소는 기존 정상 Review와 latest pointer를 바꾸지 않는다.

입력 가용성은 `RAW_AND_CANONICAL | CANONICAL_ONLY | RESULT_ONLY | DELETED`로 구분한다. raw 삭제 뒤
`SourceEvidenceSnapshot`은 Adapter 입력을 대체하지 않으며 reparse는 실패한다. compatible canonical이 남으면
reconstruction/analytics는 가능하고 result만 남으면 기존 조회만 가능하다. 재업로드는 새 ImportSession이며
owner/Book/source identity와 conflict 검증 뒤에만 기존 lineage에 연결한다. 삭제된 artifact를 재처리하려고
backup에서 복원하지 않는다.

새 version 배포는 기존 결과를 자동 reparse/rebuild/rerun하지 않고 결과 조회도 lazy reprocessing을 유발하지
않는다. 기본 trigger는 사용자 요청 또는 승인된 migration plan이다. bug/security/correctness incident의
대량 재처리는 별도 plan, 대상, notification, capacity와 audit가 필요하고 항상 새 Revision/Run을 만든다.
우선순위는 `Deletion > Security hold > Reprocessing > New analysis`이며 법률상 hold는 별도 결정 전 만들지
않는다. 여기서 security hold는 incident 동안 계산/publication을 멈추는 운영 gate이고 retention을 늘리는
legal hold가 아니다.

idempotency key는 owner/Book, source Revision content hash, optional source result hash, target VersionSet hash,
optional AnalysisConfig hash와 reprocessing type의 canonical hash다. 생성 시각, random ID, worker와 attempt는
제외한다. 같은 key의 진행 중 요청은 기존 run을, 성공 요청은 기존 immutable result를 반환한다. 실패 retry는
같은 key와 새 attempt/run을 `retryOf` lineage로 연결한다.

lineage는 여러 ImportSession/Revision을 표현할 수 있는 DAG이며 parent Revision IDs, optional parent Run,
source ImportSession IDs, reprocessing run/type/reason, source/target VersionSet, initiator, input availability,
result hash와 created time을 보존한다. cycle과 cross-owner/Book parent는 금지한다. latest는 mutable pointer일 수
있지만 fully validated `COMPLETED` Revision/Result만 가리킨다. 과거/현재 결과는 source Revision, definition
versions, config, reason, created time과 difference summary를 구분해 조회한다.

기간 trend는 Metric definition, period를 제외한 canonical config, outcome basis, comparison definition,
review timezone/timezone database, required capability set과 ReviewUnit schema가 모두 같을 때만 비교한다. 다르면
`NOT_COMPARABLE_VERSION`이며 최신 VersionSet으로 과거 기간 전체를 다시 완료해야 새 comparable series를 만든다.
재처리 diff는 version이 다른 old/new 결과를 병렬 설명할 수 있지만 기간 trend가 아니며 숫자 변화의 원인을
자동 단정하지 않는다.

failure taxonomy는 `VERSION_UNAVAILABLE`, `VERSION_INCOMPATIBLE`,
`REPROCESSING_SOURCE_UNAVAILABLE`, `REPROCESSING_NOT_SUPPORTED`,
`CANONICAL_SCHEMA_INCOMPATIBLE`, `INSTRUMENT_TERMS_UNAVAILABLE`, `TARGET_VERSION_RETIRED`,
`REPROCESSING_CONFLICT`, `REPROCESSING_CANCELLED`, `RESULT_HASH_MISMATCH`,
`STALE_RESULT_AFTER_DELETION`, `LINEAGE_INCONSISTENT`를 사용하고 trend 불일치는
`NOT_COMPARABLE_VERSION`으로 표현한다. 같은 canonical source/terms/target
VersionSet/config는 ADR-059/060의 canonical JSON, Decimal, ordering, timezone 규칙으로 같은 result hash를
만들어야 하며 불일치는 publish하지 않는다.

**이유**: parser, schema, terms, 복원, Metric과 Finding은 서로 다른 속도로 바뀌고 raw는 짧게 보관된다. 변경을
한 version에 숨기거나 기존 결과를 덮어쓰면 어떤 정의로 계산됐는지, raw 없이 무엇을 재실행할 수 있는지,
숫자가 왜 달라졌는지 설명할 수 없다. immutable 결과와 명시적 compatibility/lineage/publication 경계가
재현 가능성과 최신 정의 재계산을 동시에 보장한다.

**확정된 도메인 계약**: 위 version taxonomy/identifier, registry 상태, compatibility fail-fast, 변경별 새
Revision/Run, ReprocessingRun/type/status, input availability, 자동 재처리 금지, idempotency, DAG lineage,
publication, historical view, trend comparability, lifecycle 우선순위, failure와 deterministic hash.

**운영 configuration**: bounded retry/backoff, rate limit, batch size, 동시 실행 capacity, notification channel과
alert threshold. 이 값은 상태 의미, 삭제 우선순위, compatibility와 immutable publication을 바꿀 수 없다.

**migration plan 필요**: system-wide bug/security/correctness fix, canonical schema migration, deprecated version
retirement와 대규모 재계산.

**후속 구현 결정**: persistence/OpenAPI, queue/worker, registry 배포 방식, full payload diff 보관, Web UI와 과금.

**관련**: ADR-054~061, `docs/DOMAIN.md` TR-I25~TR-I38,
`docs/TRADING_VERSIONING.md`.

---

## ADR-063 — Trading Review API and Persistence Boundaries

**결정**: Trading Review의 정확한 public/internal HTTP schema는 `openapi/core-api.yaml`과
`openapi/compute-api.yaml`, API 의미와 흐름은 `docs/TRADING_REVIEW_API.md`, 논리 persistence 경계는
`docs/TRADING_REVIEW_PERSISTENCE.md`로 고정한다.

Web은 JWT Cookie/CSRF가 적용된 Core public API만 호출하고 Compute를 직접 호출하지 않는다. Compute는 기존
`X-Internal-Api-Key` 하나와 network isolation/TLS를 사용하는 내부 API만 제공한다. Import, Analysis,
Reprocessing과 Deletion은 callback 없이 polling하며 Core의 기존 durable dispatch pattern을 재사용한다.
Core Aggregate 생성/상태 전이와 dispatch를 같은 DB transaction에서 만들고 원격 HTTP는 transaction 밖에서
idempotently 수행한다. Compute는 Core table을 직접 수정하지 않는다.

Raw upload는 MVP에서 Option A를 선택한다. Web은 artifact당 최대 50 MiB의 `text/csv` 또는
`application/vnd.ms-excel` file을 multipart로 Core에 보내며 file upload도 CSRF 예외가 아니다. Core는 raw
SHA-256/size를 streaming 검증하고 artifact별 envelope encryption으로 private object storage에 저장한다.
encrypted write/verification이 끝나야 role slot을 채우며 partial upload는 resource를 만들지 않는다. 두 role이
준비돼도 validation은 별도 command 전에는 시작하지 않는다. filename은 path/identity가 아니고 raw lifecycle과
함께 삭제한다. source cell은 실행하지 않으며 향후 CSV export는 formula injection을 escape한다.

Compute input은 public URL이나 영구 download URL이 아니라 최대 5분의 single-read, job-bound signed artifact
grant다. 만료는 retryable attempt failure이며 Core가 raw TTL/deletion generation을 다시 확인해 새 grant로
재시도한다. terminal 대용량 output은 response에 records를 inline하지 않고 opaque payload ID, media type,
size/hash/expiry로 전달한다. Core가 internal endpoint에서 stream-download해 장기 저장하고 ack한 뒤 Compute는
cleanup할 수 있으며 terminal runtime/payload는 어떤 경우에도 24시간을 넘기지 않는다.

Core는 Account/Book/Import/Artifact metadata, evidence snapshot, canonical record, immutable Revision/manifest,
Analysis/Reprocessing/Deletion, durable dispatch, 장기 result와 latest pointer를 소유한다. Compute는 Job/Attempt,
input lease와 terminal payload만 소유한다. 하나의 `TradingRecord`는 한 owner/Book에 속하고 같은 owner/Book의
여러 Revision이 같은 immutable record를 참조할 수 있다. Revision membership은 별도
`LedgerRevisionRecord` relation이다. artifact/source row snapshot은 여러 canonical record provenance가
공유한다. cross-user logical/physical deduplication은 금지한다.

accepted record, Revision membership, manifest와 completed result는 immutable이다. correction/reprocessing은
새 Revision/Run을 만든다. mutable `latestRevisionId`/`latestAnalysisRunId`는 immutable payload와 분리하고,
fully validated `COMPLETED` publication transaction에서만 갱신한다. 새 Revision+Analysis 재처리는 child를
staging으로 만들고 hash, DAG lineage, attempt와 deletion generation을 검증한 뒤 pointer 쌍과 함께 원자적으로
publish한다. 실패/취소는 기존 result/latest를 바꾸지 않는다.

Public command는 1~128자의 opaque `Idempotency-Key`를 사용한다. same key/same canonical payload는 최초
resource를 반환하고 different payload는 `409`다. transport replay는 최소 24시간 보관하며 Aggregate가 더
오래 남는 validation/analysis/reprocessing/deletion은 logical key와 deterministic-success uniqueness도
유지한다. Analysis logical key는 Revision content/manifest hash + Analytics VersionSet hash + config hash +
owner/generation, reprocessing key는 ADR-062 canonical identity다. Compute dispatch는 별도 persistent key와
request fingerprint를 사용한다.

Evidence API는 Metric membership, ReviewUnit과 paged allocation→record→snapshot chain을 단계별로 제공한다.
raw bytes/full row/download URL은 반환하지 않는다. raw 삭제 뒤에는 versioned allowlist snapshot,
`rawAvailability=DELETED`, masked identifier와 reparse 불가를 정직하게 표시한다. 다른 owner의 resource는
존재하지 않는 resource와 같은 `404`다.

Deletion은 target별 async public command와 request status로 제공한다. tombstone/deletion generation을 먼저
commit해 access/admission/retry를 막고 DB/object/DEK/Compute runtime은 fixed checklist와 retry로 purge한다.
분산 transaction은 가정하지 않는다. 일부 purge를 완료로 표시하지 않으며 late result는 discard한다.
Reprocessing은 source availability, exact target VersionSet/type/config와 lineage를 고정한 별도 async Run이고
raw가 없는 reparse나 unavailable/incompatible target을 대체 실행하지 않는다.

금융 Decimal은 public/internal Trading Review schema에서 JSON string으로 전달하고 source scale을 별도
보존한다. ratio/percentage point는 exact rational, duration은 integer microseconds, canonical timestamp는
UTC RFC 3339, source local time은 timezone과 분리한다. Position History의 second-precision local timestamp도
pinned source timezone을 적용한 exact instant로 표현한다. Error는 `code`, safe `message`, `traceId`, `retryable`, optional allowlisted
`details`를 사용하고 raw value/identifier/storage locator를 포함하지 않는다. malformed/auth/hidden/conflict/
size/media/semantic/temporary failure는 각각 400/401·403/404/409/413/415/422/503으로 구분하며 비동기 domain
failure는 최초 `202`와 이후 terminal status를 분리한다.

**이유**: 선행 도메인 계약만으로는 구현자가 upload termination, record membership, long-running transaction,
large payload, duplicate/stale terminal 결과와 public error를 각자 다르게 선택할 수 있다. Core 장기 소유와
Compute 제한 runtime, 명시적 polling/idempotency/publication을 schema까지 고정하면 source fidelity,
evidence와 immutable reprocessing/deletion을 서비스 경계에서 잃지 않고 Core/Compute를 독립 구현할 수 있다.

**확정된 계약**: public/internal API 분리, Option A upload와 50 MiB/media type, explicit validation, polling,
5분 artifact grant, opaque large terminal payload와 acknowledgement, Core/Compute ownership, logical transaction,
idempotency, immutable result/latest pointer, evidence/deletion/reprocessing API, Decimal/time/error semantics.

**server configuration**: polling interval, bounded retry/backoff, worker lease/heartbeat, alert threshold와 cleanup
실행 주기. 상태, hash, retention hard limit과 access 의미를 바꿀 수 없다.

**infrastructure limit**: proxy streaming/body overhead, KMS/object storage implementation, database/index type,
queue capacity. public 50 MiB 상한보다 낮은 body limit은 허용하지 않는다.

**후속 성능 검증**: 최대 upload의 Core memory/latency, terminal payload streaming, evidence pagination과 purge
latency. 변경이 필요하면 OpenAPI/ADR version을 함께 갱신한다.

**관련**: ADR-017, ADR-040~041, ADR-047, ADR-054~062,
`docs/TRADING_REVIEW_API.md`, `docs/TRADING_REVIEW_PERSISTENCE.md`.
