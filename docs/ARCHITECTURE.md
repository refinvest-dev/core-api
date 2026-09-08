# ARCHITECTURE.md — 시스템 아키텍처

---

## 1. 전체 구조

```text
┌─────────────────┐
│  refinvest-web   │  Next.js
└────────┬─────────┘
         │ REST (항상 Core만 호출)
         ↓
┌─────────────────┐
│  refinvest-core  │  Kotlin + Spring Boot
│  (도메인 상태 소유)     │
└────────┬─────────┘
         │ REST + 비동기 폴링
         ↓
┌──────────────────────┐
│  refinvest-compute   │  Python + FastAPI
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

`refinvest-system`은 코드가 없는 문서/계약 전용 레포로, 이 문서들과 두 API 계약을 보관한다: `openapi/compute-api.yaml`(Core↔Compute), `openapi/core-api.yaml`(Web↔Core).

---

## 2. Core (Kotlin + Spring Boot) 모듈 구조

Hexagonal(Ports & Adapters) + 모듈형 모놀리스로 구성한다.

```text
refinvest-core/
├── strategy/
│   ├── domain/          # Strategy, StrategyVersion, Condition — 순수 도메인 모델
│   ├── application/     # CreateStrategyUseCase, DefineStrategyVersionUseCase 등 (`docs/USECASES.md`와 동일 명명)
│   ├── adapter/in/web/  # StrategyController
│   └── adapter/out/persistence/  # StrategyRepository 구현체 (JPA)
├── backtest/
│   ├── domain/          # BacktestRun, BacktestResult
│   ├── application/     # RunBacktestUseCase, GetBacktestResultUseCase
│   ├── adapter/in/web/  # BacktestController
│   ├── adapter/out/persistence/
│   └── adapter/out/compute/  # ComputeClient — backtest의 Compute 호출은 이 포트 뒤로만 격리
├── asset/
│   └── adapter/out/compute/  # Compute의 Asset/Calendar 참조 데이터를 읽어오는 Read-only 클라이언트
├── user/
├── subscription/
└── common/               # 공통 설정, 인증(Spring Security + JWT), 예외 처리
```

**모듈 간 규칙**:
- 모듈은 서로의 `domain`을 직접 참조하지 않는다. 다른 모듈의 기능이 필요하면 그 모듈의 `application` 레이어(Use Case)를 통해서만 호출한다.
- `backtest`와 `asset` 두 모듈만 `Compute`를 호출할 수 있다 — `backtest`는 백테스트 실행을, `asset`은 Asset/Calendar 참조 데이터 조회(`ListAssets`, `GetAssetAvailability`, `GetSeries`)를 전담하며, 각자 자신의 `adapter/out/compute/`에 클라이언트를 격리한다. 그 외 모듈(`strategy`, `user` 등)이 Compute 데이터가 필요하면 `backtest` 또는 `asset` 모듈의 Use Case를 거친다(직접 호출 금지). (`AI_AGENT.md` §2와 동일)

---

## 3. Compute (Python + FastAPI) 모듈 구조

```text
refinvest-compute/
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
│   └── routes/assets.py       # GET /assets, GET /assets/{symbol}/availability, GET /series (향후 Data Explorer용; MVP Web에는 비공개)
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
refinvest-web/
├── app/
│   ├── strategies/[id]/build/  # Strategy Builder (Level 1~3)
│   └── strategies/[id]/results/[runId]/  # 결과 페이지
├── lib/api/                 # Core API 클라이언트 (Compute를 직접 호출하는 코드는 존재하지 않는다)
└── components/
    ├── chart/                # 향후 Data Explorer/Trade Timeline용 (MVP 사용자 원시 가격 차트에는 미사용)
    └── viz/                  # Recharts/D3 기반 (Equity Curve, Drawdown — 커스텀 시각화)
```

- MVP 사용자는 외부 증권사·거래소 차트에서 시장을 관찰한다(ADR-049). RefInvest는 원시 가격 시계열·오버레이·다운로드를 제공하지 않으며, 백테스트 결과의 Equity Curve/Drawdown 등 파생 결과 시각화에는 Recharts를 사용한다. Data Explorer를 도입하는 Phase 3 이후 시계열 오버레이·줌/팬에는 [lightweight-charts](https://github.com/tradingview/lightweight-charts)를 사용한다.
- API 클라이언트는 `openapi/core-api.yaml`을 기준으로 생성/구현한다(경로, 요청/응답 스키마는 이 스펙이 정본).
- 결과 페이지 필수 구성: Equity Curve(Strategy vs Execution Asset B&H), Drawdown Chart, 거래 이벤트 Timeline(원시 가격 차트 위 마커는 제외하되 `Trade`의 Signal/Entry/Exit 시점과 `signalExecutionMarketRelation`의 Cross-Market 구분은 표시), Trade Table, TQQQ/SOXL은 `docs/DECISIONS.md` ADR-016 경고 고정 표시. Equity/Drawdown은 `BacktestResult`의 파생 portfolio index만 사용하며 원시 가격을 보간하거나 재요청하지 않는다(ADR-052).
- 상태 관리: 서버 상태는 TanStack Query. 별도 전역 상태 관리 라이브러리는 도입하지 않는다.
- 백테스트 결과 대기: `GET /backtests/{id}`를 2~3초 간격으로 폴링. WebSocket 미도입.

---

## 6. Core ↔ Compute 통신

- 프로토콜: REST (JSON). 계약은 `refinvest-system/openapi/compute-api.yaml`에서 관리하며, 두 레포 모두 이 스펙을 기준으로 구현한다. (Web↔Core 계약은 별도 `openapi/core-api.yaml` — §5 참고.)
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

Backtest Engine의 컨테이너 이미지는 **버전 태깅**한다(예: `refinvest-compute:1.3.2`). `BacktestRun.engineVersion`에 이 태그를 기록해, 필요 시 과거 버전의 Engine 이미지로 특정 백테스트를 재현할 수 있게 한다(ADR-010 Determinism Contract).
