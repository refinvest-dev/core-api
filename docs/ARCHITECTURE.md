# ARCHITECTURE.md — 시스템 아키텍처

---

## 1. 전체 구조

```text
┌─────────────────┐
│  web   │  Next.js
└────────┬─────────┘
         │ REST (항상 Core만 호출)
         ↓
┌─────────────────┐
│  core-api  │  Kotlin + Spring Boot
│  (도메인 상태 소유)     │
└────────┬─────────┘
         │ REST + 비동기 폴링
         ↓
┌──────────────────────┐
│  compute-api  │  Python + FastAPI
│  (Backtest Engine +       │
│   Data Ingestion)          │
└──────────┬───────────┘
           ↓
   ┌───────┴────────┐
   ↓                ↓
[Postgres]     [Object Storage]
(공유 DB,       (Dataset Snapshot,
테이블 소유권    Parquet)
문서로 구분)
```

`context`은 코드가 없는 문서/계약 전용 레포로, 이 문서들과 두 API 계약을 보관한다: `openapi/compute-api.yaml`(Core↔Compute), `openapi/core-api.yaml`(Web↔Core).

---

## 2. Core (Kotlin + Spring Boot) 모듈 구조

Feature-centric Gradle 멀티모듈 + Hexagonal(Ports & Adapters)로 구성한다. **실제 모듈/패키지 구조,
네이밍 컨벤션(예: persistence 포트는 `{Domain}Store`/`{Domain}Reader`, `Repository` 명칭 금지)은
`core-api/AGENTS.md` §4가 정본이다** — 이 문서에서 구조를 재설명하지 않는다. Gradle 멀티모듈을 택한
이유와 트레이드오프는 `docs/DECISIONS.md` ADR-018 참고.

모듈(feature) 목록: `strategy`, `backtest`(오케스트레이션), `asset`, `user`, `subscription`.
`shared`/`app`은 공통 kernel·infrastructure·composition root다.

**모듈 간 규칙**(레포 구조와 무관하게 항상 성립):
- 모듈은 서로의 domain을 직접 참조하지 않는다. 다른 모듈의 기능이 필요하면 그 모듈이 노출하는
  Use Case(application 레이어)를 통해서만 호출한다.
- `backtest`와 `asset` 두 모듈만 `Compute`를 호출할 수 있다 — `backtest`는 백테스트 실행을, `asset`은
  Asset/Calendar 참조 데이터 조회(`ListAssets`, `GetAssetAvailability`, `GetSeries`)를 전담하며, 각자
  자신의 기술 어댑터 계층 뒤로 Compute 클라이언트를 격리한다(구체적 모듈 경로는 `core-api/AGENTS.md`
  §4 컨벤션을 따른다). 그 외 모듈(`strategy`, `user` 등)이 Compute 데이터가 필요하면 `backtest` 또는
  `asset` 모듈의 Use Case를 거친다(직접 호출 금지). Compute의 URL·응답 스키마를 이 어댑터 밖으로
  노출하지 않는다. (`AI_AGENT.md` §2와 동일)

---

## 3. Compute (Python + FastAPI) 모듈 구조

```text
compute-api/
├── engine/                  # Backtest Engine — 계산 로직
│   ├── temporal/            # 4대 Temporal Rule 구현 (docs/DECISIONS.md ADR-003, ADR-004)
│   ├── conditions/           # Simple, Lookback, Change, Relative 평가
│   ├── execution/            # Signal → Lag → Execution Resolution → Price Calculation
│   ├── metrics/               # CAGR, Sharpe, MDD 등
│   └── pipeline.py           # Strategy DSL → Validation → ... → Result (전체 파이프라인 엔트리)
├── ingestion/                # Data Ingestion — engine과 완전히 분리된 패키지
│   ├── vendors/               # 벤더별 어댑터 (Tiingo, 크립토 벤더 등)
│   ├── corporate_actions/     # Split/Reverse Split/Dividend 정규화
│   └── snapshot.py            # Immutable Dataset Snapshot 생성
├── api/
│   ├── routes/backtests.py    # POST /backtests (202 Accepted), GET /backtests/{id}
│   └── routes/assets.py       # GET /assets, GET /assets/{symbol}/availability, GET /series (Data Explorer용 다중 자산 시계열 — `docs/USECASES.md`의 `GetSeries`)
└── worker/                    # 비동기 Job 처리 (Job Queue Consumer)
```

**계산 파이프라인** (`engine/pipeline.py`):

```text
Strategy DSL → Validation → Dataset Snapshot Resolution → Calendar Resolution
→ Signal Evaluation → Lag Resolution → Execution Session Resolution
→ Execution Price Calculation → Position Management → Exit
→ Fee/Slippage → Portfolio Calculation → Metrics → Result
```

`engine`과 `ingestion`은 서로를 import하지 않는다. `ingestion`이 실패해도 이미 존재하는 Snapshot으로 `engine`은 계속 동작해야 한다.

---

## 4. 데이터베이스 — 단일 Postgres, 테이블 소유권 분리

물리적으로 하나의 Postgres 인스턴스를 공유한다(ADR-012). 소유권은 아래 표로 강제하며, **소유하지 않는 서비스는 해당 테이블에 쓰기 쿼리를 절대 작성하지 않는다** — 코드 리뷰에서 우선 확인 대상.

| 테이블 | 소유(쓰기 권한) | 읽기 |
|---|---|---|
| `users`, `strategies`, `strategy_versions`, `subscriptions` | Core | Core만 |
| `backtest_runs`, `backtest_results`, `trades` | Core | Core만 (Compute는 응답을 반환할 뿐 직접 쓰지 않음) |
| `assets`, `market_calendars`, `corporate_actions` | Compute (Ingestion) | Core는 Compute API를 통해서만 조회, 직접 쿼리 금지 |
| `dataset_snapshots` | Compute (Ingestion) | Compute 내부, Core는 스냅샷 ID만 참조값으로 저장 |

**Core가 Asset/Calendar 정보를 직접 DB에서 join하지 않는 이유**: 스키마가 같은 물리 DB에 있어도, Compute가 소유한 테이블은 반드시 Compute의 API(`GET /assets`)를 거쳐 조회한다. 이렇게 해야 나중에 실제로 DB를 분리해야 할 때(ADR-012 Revisit 조건) 애플리케이션 코드 변경 없이 인프라만 바꿀 수 있다.

---

## 5. Web (Next.js) 구조

```text
web/
├── app/
│   ├── explorer/            # Data Explorer
│   ├── strategies/[id]/build/  # Strategy Builder (Level 1~3)
│   └── strategies/[id]/results/[runId]/  # 결과 페이지
├── lib/api/                 # Core API 클라이언트 (Compute를 직접 호출하는 코드는 존재하지 않는다)
└── components/
    ├── chart/                # lightweight-charts 기반 (Data Explorer, Trade Timeline)
    └── viz/                  # Recharts/D3 기반 (Equity Curve, Drawdown — 커스텀 시각화)
```

- 차트: 시계열 오버레이·줌/팬이 필요한 Data Explorer/Trade Timeline은 [lightweight-charts](https://github.com/tradingview/lightweight-charts)를, Equity Curve/Drawdown처럼 커스텀 시각화가 필요한 부분은 Recharts를 사용한다.
- API 클라이언트는 `openapi/core-api.yaml`을 기준으로 생성/구현한다(경로, 요청/응답 스키마는 이 스펙이 정본).
- 결과 페이지 필수 구성: Equity Curve(Strategy vs Execution Asset B&H), Drawdown Chart, Trade Timeline(가격 차트 위 Signal/Entry/Exit 마커, Cross-Market은 Signal↔Execution 구분), Trade Table, TQQQ/SOXL은 `docs/DECISIONS.md` ADR-016 경고 고정 표시.
- 상태 관리: 서버 상태는 TanStack Query. 별도 전역 상태 관리 라이브러리는 도입하지 않는다.
- 백테스트 결과 대기: `GET /backtests/{id}`를 2~3초 간격으로 폴링. WebSocket 미도입.

---

## 6. Core ↔ Compute 통신

- 프로토콜: REST (JSON). 계약은 `context/openapi/compute-api.yaml`에서 관리하며, 두 레포 모두 이 스펙을 기준으로 구현한다. (Web↔Core 계약은 별도 `openapi/core-api.yaml` — §5 참고.)
- 백테스트 실행은 비동기다.

```text
Core                                  Compute
  │  POST /backtests {strategyVersion, feeModel, period}
  ├─────────────────────────────────────>│
  │  202 Accepted {runId, status: PENDING}
  │<─────────────────────────────────────┤
  │                                       │ (내부 Job Queue에서 처리)
  │  GET /backtests/{runId}  (폴링, 2~3초 간격)
  ├─────────────────────────────────────>│
  │  200 {status: RUNNING}               │
  │<─────────────────────────────────────┤
  │  ... (반복) ...                       │
  │  GET /backtests/{runId}
  ├─────────────────────────────────────>│
  │  200 {status: COMPLETED, result: {...}}
  │<─────────────────────────────────────┤
  │  BacktestResult 영속화                │
```

- Compute는 인터넷에 직접 노출하지 않는다. Core만 호출 가능한 내부망 서비스로 배포한다.
- Job Queue: Compute 내부에서 Redis + RQ(또는 Celery)를 사용한다. 이 큐는 Compute 레포 내부 구현 상세이며 Core는 알 필요가 없다.

---

## 7. 배포 단위

4개 레포 = 4개 컨테이너(Core, Compute, Web, 그리고 Ingestion을 별도 스케줄 워커로 분리할지는 Compute 레포 내에서 결정). Postgres, Redis, Object Storage는 공용 인프라로 별도 관리한다.

Backtest Engine의 컨테이너 이미지는 **버전 태깅**한다(예: `compute-api:1.3.2`). `BacktestRun.engineVersion`에 이 태그를 기록해, 필요 시 과거 버전의 Engine 이미지로 특정 백테스트를 재현할 수 있게 한다(ADR-010 Determinism Contract).
