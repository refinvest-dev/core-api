# ARCHITECTURE.md — 시스템 아키텍처

> 이 문서는 ADR-072의 목표 서비스 구성을 정의한다. 현재 구현의 이전 완료 여부는
> [서비스 경계의 gate](TRADING_REVIEW_SERVICE_BOUNDARY.md#이관과-완료-gate)와 실제 증적으로 확인한다.

---

## 1. 전체 구조

```text
Web → Core (사용자 인증/CSRF)
        ├→ Strategy/Backtest → Compute
        └→ HTTP/JSON → Trading Review Go API
                         ├→ TradingRecord / Analysis / Workflow
                         ├→ PostgreSQL (기존 DB, Trading Review 소유 테이블)
                         └→ durable dispatch → Go Worker → HTTP → Compute
                                                           └→ Object Storage
미래 Trading Review 진입: Web → Gateway → Go (Core 중계 교체)
```

Core는 Member/Strategy/Backtest를 소유한다. Go는 Trading Review 상태·권한·발행·삭제와
장기 결과를 소유한다. Compute는 계산과 제한 수명의 runtime 및 시장 참조 데이터를 소유한다.
Object Storage는 공유 인프라라도 namespace/권한/key/lifecycle을 소유 서비스별로 분리한다.
Trading Review raw/manifest/result Artifact는 Go, DatasetSnapshot과 Compute runtime은 Compute 소유다.
서로의 object를 임의 열거·장기 복사하지 않는다.

context는 도메인/ADR/API 정본, integration은 조합/E2E 저장소다.
`openapi/core-api.yaml`은 초기 Web→Core, `openapi/compute-api.yaml`은 Core/Go→Compute의
현재 wire 계약이다. 새 Core→Go 계약은 후속 확정 대상이며 기존 공개 endpoint와 동일하다고 추정하지 않는다.

## 2. Core (Kotlin + Spring Boot) 모듈 구조

아래 Kotlin 구조는 기존 Core 구현의 참조 구조다. `tradingreview`는 이관 대상이며 초기
Core 중계 연결 시 업무/Worker를 비활성화한다. Go의 신규 업무 구현은 이 구조에 추가하지 않는다.
Go 패키지 상세는 Go 저장소 ARCHITECTURE.md가 소유한다.

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
├── tradingreview/
│   ├── domain/          # TradingAccount/Book, TradingImportSession, LedgerRevision, TradingAnalysisRun
│   ├── application/     # import/analysis/review Use Case
│   ├── adapter/in/web/  # upload, run polling, review query
│   ├── adapter/out/persistence/
│   ├── adapter/out/artifact/ # 민감 원본 저장/삭제 port
│   └── adapter/out/compute/  # normalization/analysis 호출 격리
├── asset/
│   └── adapter/out/compute/  # Compute의 Asset/Calendar 참조 데이터를 읽어오는 Read-only 클라이언트
├── user/
├── subscription/
└── common/               # 공통 설정, 인증(Spring Security + JWT), 예외 처리
```

**모듈 간 규칙**:
- 모듈은 서로의 `domain`을 직접 참조하지 않는다. 다른 모듈의 기능이 필요하면 그 모듈의 `application` 레이어(Use Case)를 통해서만 호출한다.
- `backtest`, `asset`, `tradingreview`만 Compute를 호출할 수 있다. `backtest`는 백테스트 실행을, `asset`은 Asset/Calendar 참조 데이터 조회(`ListAssets`, `GetAssetAvailability`, `GetSeries`)를 전담하며, 각자 자신의 `adapter/out/compute/`에 클라이언트를 격리한다. `tradingreview`는 거래 정규화와 행동 분석만 자신의 포트로 호출한다. 그 외 모듈(`strategy`, `user` 등)은 Compute를 직접 호출하지 않는다. (`AI_AGENT.md` §2와 동일)

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
├── trading_review/          # 사용자 거래 기록 분석, backtest engine과 분리
│   ├── adapters/binance/    # source schema detection/normalization
│   ├── reconstruction/      # product-specific ReconstructionPolicy
│   ├── analytics/           # Observation/Metric/Finding
│   └── pipeline.py          # pinned ledger input → Review result
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
| `trading_accounts`, `trading_books`, `trading_import_sessions`, `trading_source_artifacts`, `source_evidence_snapshots`, `trading_records`, `ledger_revisions`, revision-record membership, reconciliation manifests | Go | Go만 |
| `trading_analysis_runs`, `trading_analysis_results`, `trading_reprocessing_runs`, `trading_deletion_requests`, artifact retention state, durable dispatch | Go | Go만 (Compute runtime/terminal payload는 제한 기간만 보관) |
| Trading Review compute jobs, attempts, terminal payloads, artifact leases | Compute | Compute만; Go는 internal API로만 조회/ack |
| `assets`, `market_calendars`, `corporate_actions` | Compute (Ingestion) | Core는 Compute API를 통해서만 조회, 직접 쿼리 금지 |
| `dataset_snapshots` | Compute (Ingestion) | Compute 내부, Core는 스냅샷 ID만 참조값으로 저장 |

**Core가 Asset/Calendar 정보를 직접 DB에서 join하지 않는 이유**: 스키마가 같은 물리 DB에 있어도, Compute가 소유한 테이블은 반드시 Compute의 API(`GET /assets`)를 거쳐 조회한다. 이렇게 해야 나중에 실제로 DB를 분리해야 할 때(ADR-012 Revisit 조건) 애플리케이션 코드 변경 없이 인프라만 바꿀 수 있다.

---

## 5. Web (Next.js) 구조

```text
refinvest-web/
├── app/
│   ├── strategies/[id]/build/  # Strategy Builder (Level 1~3)
│   ├── strategies/[id]/results/[runId]/  # Backtest 결과 페이지
│   ├── trading-review/imports/ # CSV import/verification
│   └── trading-review/runs/[runId]/ # quality report, findings, evidence
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

- Compute는 인터넷에 직접 노출하지 않는다. Core와 Trading Review Go만 각 업무 계약에 따라 호출하는 내부망 서비스로 배포한다.
- Job Queue: Compute 소유 PostgreSQL 테이블의 durable job queue를 사용한다. Worker는 transaction으로
  eligible job을 claim하고 lease/heartbeat로 장애 복구와 bounded retry를 관리한다. 이 큐는 Compute
  내부 구현 상세이며 Core는 직접 읽거나 쓸 수 없다. Core는 자신의 durable dispatch record와 Compute
  REST API만 사용한다(ADR-040~044, ADR-047).

---

## 7. 배포 단위

실행 단위는 Core, Web, Trading Review Go API/Worker, Compute API/Worker다. context는 문서 저장소이고 integration은 harness이므로 저장소 수를 컨테이너 수와 동일시하지 않는다. Ingestion 배포는 Compute가 결정한다. Postgres와 Object Storage는 공용 인프라로 별도 관리한다. 현재 Compute의 durable job queue를 위한 별도 Redis는 사용하지 않는다.

Backtest Engine의 컨테이너 이미지는 **버전 태깅**한다(예: `refinvest-compute:1.3.2`). `BacktestRun.engineVersion`에 이 태그를 기록해, 보관된 과거 Engine 이미지가 있으면 특정 백테스트 재현에 사용할 수 있게 한다(ADR-010 Determinism Contract). 태그가 과거 binary의 영구 보관이나 실제 가용성을 보장하지는 않는다.

---

## 8. Trading Review 실행 경계

Trading Review는 독립 Go API/Worker로 배포한다. API와 Worker는 같은 업무 규칙과 DB를 사용한다.

```text
Web → Core 인증/CSRF/streaming 중계 → Go session/artifact 접수
Go durable dispatch → Compute normalization/reconciliation → 검증된 비공개 staging
Go atomic publication → immutable LedgerRevision + Book pointer
Go → Compute analytics (pinned Revision) → 검증/발행 → Core 경유 Web 조회
Workflow: R2 staging → A2 staging → 동일 DB Tx로 R2/A2 + pointers + lineage 공개
```

- CSV는 기존 multipart·artifact당 50 MiB 계약을 유지한다. Core는 인증/중계하고 Go가 streaming
  checksum·암호화 object write 검증·Artifact 등록을 소유한다. 중계 buffering/limit은 계약 구현에서 검증한다.
- 두 role이 준비되어도 명시 validation 전에는 실행하지 않는다. Compute에 브라우저가 직접 업로드하지 않는다.
- Go TradingRecord는 소유권·Guard/Generation/Tombstone·Ledger·Book latest pointers를 소유한다.
- Go Analysis는 분석 실행·결과·ACK를 소유하고 Record의 공개 Revision/Guard 계약을 사용한다.
- Workflow는 삭제/재처리를 조율하며 별도 Aggregate/상태 엔진을 만들지 않는다.

Workflow의 [접수·산출물 후보](TRADING_REVIEW_WORKFLOW_CONTRACT_DRAFT.md),
[identity·child binding](TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md),
[consumer·수용 gate](TRADING_REVIEW_WORKFLOW_CONTRACT_ACCEPTANCE_DRAFT.md)는 ADR-079 DRAFT다.
[Workflow HTTP registry](TRADING_REVIEW_WORKFLOW_HTTP_DRAFT.md)·[DTO 후보](TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md)는
기존 삭제 소유 profile 재사용과 versioned 재처리/원 receipt/위임 경계를 문서화한다.
후속 [Reprocessing OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)는 public/internal 8개 operation과 정적 검사를
별도 DRAFT로 표현하며 기존 active OpenAPI/route를 활성화하지 않는다.
현재 API/Compute/hash를 바꾸거나 private 수명·Analytics 실제 부재 정책을 채택하지 않는다.
- 외부 I/O는 DB Tx 밖, 두 모듈의 최종 공개는 동일 실제 Tx다. WithinTx 방식은 미확정이다.
- 기존 grant/retention/version/hash/ACK 의미를 유지한다. Go가 결과를 독립 검증하고 오래된 generation의
  결과를 공개하지 않는다. Raw-only는 기존 Ledger/Analysis를 보존한다.
- 상세 제품 의미는 Trading Review 정본, Go 폴더/계층은 Go 저장소 문서를 따른다.
- Core 중단 기간 직접 Go 검증과 Core 중계 검증을 구분한다. 이관은 서비스 경계 gate로 확인한다.


## Workflow consumer 검토 연결

[재처리 consumer/legacy 통합 리뷰](TRADING_REVIEW_REPROCESSING_CONSUMER_REVIEW_DRAFT.md)는 현재 계약 선언과 실제
Core/Web/Go 소스 범위, 기존 generic idempotency·receipt/불명 source pin·오류/abort·child별 wire/hash 차이를 구분한다.
별도 Workflow Aggregate나 Compute 임의 확장을 도입하지 않으며 identity/full binding·원 receipt/profile이 다음 상세화 범위다.
source 부재 관측·snapshot/정적 CI를 실제 운영 데이터 부재·정책 채택·업무 수용으로 해석하지 않는다.
