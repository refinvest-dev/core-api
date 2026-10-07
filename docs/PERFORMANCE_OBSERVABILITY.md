# PERFORMANCE_OBSERVABILITY.md — 성능 측정 및 관측 원칙

이 문서는 RefInvest가 성능을 정량적으로 측정하고 병목 원인을 특정한 뒤, 타깃 최적화를 적용하고
결과 동일성과 성능 개선을 검증하기 위한 정본이다. 세부 구현이나 현재 성능 결과를 기록하는 문서가
아니다. 백테스트의 정확성과 재현성 계약은 `docs/DECISIONS.md` ADR-003~005, ADR-010 및 ADR-053을
우선한다.

---

## 1. 목적과 비목표

### 목적

- 고정된 입력과 환경으로 반복 가능한 benchmark를 정의한다.
- 전체 시간뿐 아니라 계산 단계를 분리해 병목의 원인을 찾고, 원인에 한정된 최적화를 수행한다.
- 동일한 benchmark의 baseline과 candidate를 비교해 성능 회귀와 개선을 확인한다.
- 개발·검증 환경에서 Compute, Core, integration 경로의 상태를 설명할 최소 runtime observability를
  마련한다.
- 최적화 전후에 Data Correctness, Temporal Correctness, Determinism과 기능 결과가 동일한지 먼저
  검증한다.

### 비목표

이 단계에서는 애플리케이션 코드, benchmark runner, Prometheus/Grafana 구성, 부하 테스트 또는
OpenAPI 계약을 구현·변경하지 않는다. 운영 알림과 당직, latency SLO/error budget, 분산 tracing,
중앙 로그 플랫폼도 현재 범위가 아니다. 아직 baseline이 없으므로 절대 latency SLO를 정하지 않으며,
아래의 threshold 후보를 측정 결과처럼 해석하지 않는다.

### Benchmark와 runtime observability의 구분

- **Benchmark**는 고정 fixture와 통제된 실행 환경에서 baseline과 candidate의 계산 비용을 재현·비교하고
  canonical 결과 동일성을 판정한다. 최적화 효과와 회귀 판단의 근거다.
- **Runtime observability**는 개발·검증 환경에서 실제 요청의 처리량, 지연, 상태, 실패와 queue 상태를
  파악해 어디를 조사할지 알려준다. 입력과 머신 조건이 통제되지 않으므로 그 자체로 최적화 전후의
  인과관계나 deterministic 결과 동일성을 증명하지 않는다.

두 목적의 데이터를 섞어 benchmark 결론을 내리지 않는다. Prometheus/Grafana는 runtime 현상과
추세를 찾는 도구이고, 고정 benchmark report는 재현 가능한 성능 비교와 correctness gate의 근거다.

---

## 2. 측정 원칙

1. baseline과 candidate 비교에서는 다음을 모두 고정한다.
   - canonical StrategyVersion payload와 그 식별자
   - DatasetSnapshot ID, fixture manifest 및 artifact hash
   - 요청 기간의 시작일과 종료일
   - commission/slippage를 포함한 Fee/Slippage model
   - Engine version과 결과에 영향을 주는 Engine 설정
   - benchmark scenario와 반복 횟수
2. fixture 생성·다운로드·publication·검증 등 **fixture 준비시간은 Backtest 실행시간에서 제외**한다.
   snapshot load 자체는 이미 준비된 fixture를 실행 입력으로 읽고 검증하는 단계이므로 측정에 포함한다.
3. runtime/JIT/import 초기화 등을 위한 warm-up과 기록 대상 반복을 분리한다. warm-up 결과는 표본과
   percentile에 포함하지 않는다.
4. 평균보다 **median과 p95**를 우선 보고한다. 반복 횟수, 최소/최대와 실패 수를 함께 기록하고,
   필요할 때만 평균을 보조값으로 사용한다.
5. cold-cache와 warm-cache 결과를 별도 series로 측정·보고한다. cache 상태를 섞은 percentile을 만들지
   않는다. cold-cache 준비 방법과 warm-cache 선행 동작을 report에 기록한다.
6. baseline과 candidate는 같은 머신 또는 동일 사양의 전용 환경에서, 같은 OS·CPU/memory 제한·runtime
   및 dependency version·worker 수·database/object storage 조건으로 비교한다. 공유 CI runner 결과는
   참고값이며 정밀한 회귀 판정의 단독 근거로 삼지 않는다.
7. report에는 실행 시각, 머신/OS/CPU/memory, runtime 및 dependency version, 각 저장소 Git revision,
   Engine version, fixture/manifest hash, cache mode, warm-up/측정 반복 수를 기록한다.
8. 벽시계 시간의 측정 경계와 clock 종류를 runner에서 하나로 고정한다. 단계 시간은 중복 집계하지 않고,
   병렬 단계가 생기면 total과 단순 합계가 다를 수 있음을 report schema에 표시한다.
9. 한 번의 실행 결과가 아니라 같은 조건에서 반복 측정한 분포로 비교한다.

---

## 3. 정확성 검증 원칙

우선순위는 `Data Correctness > Temporal Correctness > Execution Correctness > Statistical
Interpretation > Performance`다. 속도가 빨라도 결과가 달라지면 성능 개선으로 인정하지 않는다.

- benchmark fixture마다 calculation-bearing `BacktestResult`의 canonical representation과 SHA-256
  expected hash를 버전 관리한다. canonicalization version도 함께 기록한다.
- canonical representation은 key order, Decimal 표현, UTC timestamp 표현, 배열 순서를 고정한다.
  `runId`, 측정 시각, stage duration 같은 실행별 operational metadata는 제외하되, 계산 결과 필드,
  trade 순서, benchmark 결과, data integrity 정보는 포함한다.
- candidate의 canonical result hash가 expected hash와 다르면 **즉시 실패**한다. tolerance로 hash
  mismatch를 숨기지 않는다. 의도된 계산 계약 변경은 성능 PR에서 expected hash만 갱신하는 방식으로
  처리하지 않고 관련 ADR·회귀 근거를 별도로 검토한다.
- hash 외에도 trade count와 terminal status를 명시적으로 확인한다. Zero Trade scenario는
  `COMPLETED`, `tradeCount = 0`, `sampleSizeWarning = ZERO`여야 한다.
- 기존 deterministic 및 cross-calendar regression suite가 모두 통과해야 한다.
- functional error, timeout, 누락된 결과도 성능 표본에서 제외해 성공으로 해석하지 않고 실행 실패로
  판정한다.
- 성능을 위해 Signal Timestamp 이후의 데이터에 접근하거나, cross-calendar anchor·expected session
  fail-fast·next available execution 같은 temporal rule을 우회하지 않는다.
- 같은 benchmark 입력에서 결과가 달라지는 cache, vectorization, indexing 또는 concurrency 최적화는
  허용하지 않는다.

---

## 4. 대표 benchmark 시나리오

모든 시나리오는 아직 측정 전인 정의이며 실제 latency·memory 결과를 포함하지 않는다. 정확한 symbol,
날짜, literal 값과 expected hash는 후속 단계에서 fixture manifest와 함께 확정한다.

| Scenario | 목적 | 고정해야 할 scenario 입력 |
|---|---|---|
| 1년 단일 자산·단일 조건 | 짧은 기본 경로 | 1년 시작/종료일, 하나의 Primary/Condition/Execution Asset, metric/window/operator/literal, lag, holding period |
| 10년 단일 자산 | 기간 증가 비용 | 10년 시작/종료일과 위 단일 자산 전략 필드 전체 |
| 10년 cross-calendar | calendar 정렬·next available session 비용 | 서로 다른 calendar의 Primary/Referenced/Execution Asset, condition, 10년 기간, lag와 holding period |
| 10년 relative 복수 조건 | 복수 operand와 논리 결합 비용 | 각 condition의 좌·우 operand asset/metric/window, AND/OR 및 condition 순서, Primary/Execution Asset, 10년 기간 |
| 신호가 많은 경우 | entry/exit와 trade realization 상한 경로 | 높은 빈도의 신호를 만드는 고정 조건, duplicate-entry policy, lag, 짧은 holding period와 예상 terminal status/trade count |
| Zero Trade인 경우 | 정상 empty-result 경로 | 신호가 없도록 고정한 조건, 기간, `COMPLETED`/trade count 0/sample warning ZERO 기대값 |

각 scenario는 공통으로 다음을 고정한다.

- scenario ID와 schema/canonicalization version
- StrategyVersion ID 및 canonical payload
- DatasetSnapshot ID, storage-independent fixture manifest hash와 모든 artifact hash
- 기간(start/end), Primary Signal Asset, condition 목록과 순서, logical combinator
- 각 operand의 asset/metric/window/literal/operator
- Execution Asset, lag, time-based holding period, position/duplicate-entry policy
- commission, slippage, reference price 및 그 밖의 Engine 설정
- Engine version, expected terminal status, expected trade count, canonical BacktestResult hash
- cache mode, warm-up 수, 측정 반복 수와 실행 환경

실제 benchmark ID와 입력값은 runner 도입 단계에서 고정한다. production 또는 mutable latest data를
fixture로 사용하지 않는다.

---

## 5. 측정 단계와 경계

Compute benchmark는 최소한 다음 단계와 전체 실행의 벽시계 시간을 측정한다. 명칭은 의미 계약이며
함수나 package 이름을 강제하지 않는다.

| Stage | 시작과 종료의 의미 |
|---|---|
| `snapshot_load` | pinned snapshot의 manifest/artifact 읽기 시작부터 hash·provenance 검증 및 실행 입력 구성 완료까지 |
| `request_strategy_validation` | 요청/DSL 검증 시작부터 실행 가능한 canonical strategy 확정까지 |
| `calendar_resolution` | 관련 calendar 입력 해석 시작부터 평가·체결에 사용할 session mapping 확정까지 |
| `signal_evaluation` | condition 평가 시작부터 ordered signal set 확정까지 |
| `entry_exit_resolution` | signal에 lag/holding/next available session 규칙 적용 시작부터 entry/exit session 확정까지 |
| `trade_realization` | 체결 가격·fee/slippage·position policy 적용 시작부터 ordered trades와 portfolio path 확정까지 |
| `performance_benchmark_assembly` | 성과 통계 및 execution-asset B&H/secondary reference 계산 시작부터 결과 구성요소 확정까지. 여기서 benchmark는 성능 측정 harness가 아니라 `BacktestResult`의 시장 비교 기준을 뜻함 |
| `result_serialization` | 결과 object의 wire/report representation 직렬화 시작부터 bytes 생성 완료까지 |
| `total_execution` | worker의 benchmark 대상 실행 시작부터 직렬화 완료까지. fixture 준비와 queue wait는 제외하고 위 단계 및 stage 사이 orchestration을 포함 |
| `peak_memory` | `total_execution` 경계 안에서 관측한 process peak memory. 측정 방법과 child/parent 포함 범위를 report에 기록 |

`queue_wait_duration`과 Core HTTP/dispatch/poll 시간은 end-to-end 관측 대상이지만 Compute engine
`total_execution`에는 포함하지 않는다. 이 분리는 queue 병목과 계산 병목을 구별하기 위함이다.

---

## 6. 초기 관측 범위

### 포함

- Prometheus metrics
- 핵심 흐름과 병목을 보여주는 Grafana dashboard 1~2개
- bounded field를 사용하는 JSON structured log
- Core와 Compute에서 상호 연관 가능한 `runId`
- steady 및 burst 부하 테스트
- 사람이 명시적으로 실행하는 performance CI와 JSON/report artifact

### 현재 범위 밖

- Alertmanager 및 Slack/PagerDuty 알림, 당직 체계
- 자동 SLO/error budget과 절대 latency SLO
- Tempo/Jaeger 등 분산 tracing
- Loki/ELK 등 중앙 로그 플랫폼
- continuous profiling
- frontend RUM
- Prometheus HA 및 장기 storage

초기 Grafana는 예를 들어 (1) submission/terminal/failure/active jobs/queue wait와 (2) total/stage/
snapshot/submit-poll duration 및 cache를 분리한 두 화면 이내로 구성한다. 이는 구현 시점의 제안이며
dashboard 수나 panel 배치는 아직 확정된 계약이 아니다.

---

## 7. Metric 의미

아래 이름은 **제안안**이며 구현 전에 각 저장소에서 최종 확정해야 한다. 이름이 바뀌어도 의미와
cardinality 규칙은 유지한다. duration histogram의 단위는 seconds를 우선 제안한다.

| 의미 | 제안 metric 이름(미확정) | Type | 정의 |
|---|---|---|---|
| Backtest submission count | `refinvest_backtest_submissions_total` | Counter | Core가 사용자 요청을 정상 접수해 `BacktestRun(PENDING)`과 durable dispatch를 만든 횟수. Compute replay는 새 submission이 아님 |
| Terminal count by status | `refinvest_backtest_terminal_total` | Counter | BacktestRun/job이 처음 terminal 상태로 전이한 횟수. bounded `status=COMPLETED|FAILED`로 구분 |
| Queue wait duration | `refinvest_compute_queue_wait_seconds` | Histogram | Compute durable job 생성/acceptance 시각부터 worker가 해당 execution attempt를 처음 시작한 시각까지 |
| Total execution duration | `refinvest_compute_execution_seconds` | Histogram | §5 `total_execution` 경계의 시간. queue wait와 fixture 준비 제외 |
| Stage duration | `refinvest_compute_stage_seconds` | Histogram | §5의 bounded `stage`별 시간 |
| Snapshot load duration | `refinvest_compute_snapshot_load_seconds` | Histogram | `snapshot_load` 단계 시간. stage metric의 같은 표본과 의미가 일치해야 하며 중복 metric 채택 여부는 구현 시 확정 |
| Snapshot cache hit/miss | `refinvest_compute_snapshot_cache_hits_total`, `refinvest_compute_snapshot_cache_misses_total` | Counter | snapshot 실행 입력 cache lookup의 hit와 miss. cache가 없으면 내보내지 않음 |
| Core→Compute submit/poll duration | `refinvest_core_compute_client_seconds` | Histogram | Core가 Compute submit 또는 poll HTTP 호출을 시작해 응답/transport failure를 얻기까지의 시간. bounded `operation=submit|poll` |
| Retry count | `refinvest_backtest_retries_total` | Counter | 최초 시도 이후 실제로 시작된 retry 횟수. 구분이 필요하면 bounded `operation`만 사용 |
| Failure count by error code | `refinvest_backtest_failures_total` | Counter | 한 실행이 실패로 확정된 횟수. 계약 또는 내부 taxonomy의 bounded `error_code`; 예외 메시지는 label 금지 |
| Dispatch/poll lag | `refinvest_core_backtest_scheduler_lag_seconds` | Histogram | durable dispatch 또는 poll이 실행 가능해진 예정 시각부터 실제 시도 시작까지. bounded `operation=dispatch|poll` |
| PENDING/RUNNING job count | `refinvest_backtest_jobs` | Gauge | 관측 시점의 durable job 수. bounded `status=PENDING|RUNNING` |

Counter의 정확한 증가 지점과 재시작 후 중복 방지 책임은 구현 설계에서 검증한다. Core와 Compute가 같은
의미를 각각 내보내야 하는 경우 metric prefix 또는 scrape target metadata로 출처를 구분하고, 한 시스템
수치처럼 무심코 합산하지 않는다.

---

## 8. Cardinality 및 민감정보 규칙

Metric label은 값 집합이 작고 사전에 제한된 다음 범주만 허용한다.

- bounded `status`
- bounded `stage`
- bounded `operation`
- bounded `error_code`

다음 값은 metric label로 금지한다.

- `runId`
- `memberId`
- `strategyId` 또는 StrategyVersion ID
- `snapshotId`
- 원문 error message, exception message/stack trace
- condition, literal, 기간 등 사용자 입력

`runId`와 `snapshotId`는 접근이 통제된 structured log 또는 benchmark report에서만 사용한다. structured
log에도 `memberId`, 전략 원문, request payload, credential, cookie/token을 기록하지 않는다. error는
bounded code를 우선 기록하며 원문 입력이나 credential이 섞일 수 있는 메시지는 그대로 남기지 않는다.
`runId`를 통해 Core submission/dispatch/poll과 Compute acceptance/claim/terminal event를 연관한다.

---

## 9. 성능 회귀 정책

1. canonical result hash mismatch, trade count/terminal status mismatch, deterministic/cross-calendar regression
   실패는 즉시 실패다.
2. functional error, timeout, 누락된 report도 실패다. 실패 표본을 제거한 성능 개선은 인정하지 않는다.
3. 초기 performance CI에서 성능 저하는 **경고**로만 처리한다. correctness/functional gate만 build를
   실패시킨다.
4. 공유 CI runner의 noise를 고려해 그 결과로 자동 성능 차단 기준을 두지 않는다.
5. 회귀 조사는 같은 로컬 머신 또는 전용 환경에서 baseline과 candidate를 교대 또는 충분히 가까운
   시간에 반복 실행해 확인한다.
6. provisional 기준으로, median 또는 p95가 동일 scenario/cache mode에서 **20% 이상 반복적으로 저하**되면
   조사 대상으로 제안할 수 있다. 20%는 SLO나 확정 gate가 아니며 최초 baseline과 환경 편차를 확보한
   뒤 조정·확정한다. 단일 outlier나 한 번의 CI 실행만으로 회귀를 확정하지 않는다.
7. 개선 판정은 correctness gate 통과 후 stage/total/peak-memory 변화를 함께 검토한다. 한 지표의 개선이
   다른 단계의 큰 악화나 메모리 고갈 위험을 숨기지 않게 한다.

---

## 10. 저장소별 책임

| 저장소 | 책임 |
|---|---|
| `context` | 측정·정확성·cardinality 원칙, 관련 아키텍처 결정, 검증된 전후 비교 case study의 정본 |
| `compute-api` | 고정 fixture를 소비하는 benchmark runner, 계산 단계 timing, Compute API/worker/job/snapshot metric과 structured event |
| `core-api` | public HTTP, durable dispatch, Compute submit/poll, terminal result persistence, Core database connection pool metric |
| `integration` | 외부 vendor/production data에 의존하지 않는 non-production fixture 조립, Prometheus/Grafana composition, steady/burst 부하 테스트, baseline-candidate 비교 report와 수동 CI orchestration/compatibility evidence |
| `web` | 초기 범위에서는 기존 browser acceptance로 결과 조회 흐름을 검증. custom RUM 및 Compute 직접 관측은 도입하지 않음 |

integration은 제품 규칙, OpenAPI, 서비스 business code를 소유하지 않는다. benchmark 계산과 stage timing은
Compute에, public 요청 및 orchestration timing은 Core에 둔다. integration의 fixture 산출물과 실행 증적은
각 서비스 저장소의 production/runtime dependency가 아니다.

---

## 11. 결과물 및 보관 정책

- benchmark scenario 정의, canonicalization version과 expected result hash는 버전 관리한다.
- 대용량 DatasetSnapshot artifact는 Git에 직접 커밋하지 않는다.
- fixture manifest, source/artifact hash와 재생성 또는 획득에 필요한 비밀정보 없는 metadata는 버전
  관리한다.
- 실행 결과 JSON, 비교 report와 Grafana 캡처는 CI artifact 또는 integration의 `.integration-data`에
  저장한다. `.integration-data`는 로컬 전용이며 커밋하지 않는다.
- 로컬 benchmark 결과, 환경 파일, credential, token, 개인식별정보는 커밋하지 않는다.
- 보관할 가치가 검증된 주요 전후 결과만 실행 환경, 각 저장소 Git revision, Engine version, fixture
  hash, 반복 수와 correctness 결과를 포함한 context case study 문서로 정리한다.
- case study는 선택된 성공 결과만 제시하지 않고 비교 방법과 실패/편차를 함께 설명하며, 과거 데이터
  기반 시뮬레이션의 성능 사례임을 명시한다.

---

## 12. 향후 실행 순서

1. non-production 고정 fixture와 manifest/hash 확정
2. Compute benchmark runner 및 canonical result hash gate 구현
3. 통제된 환경에서 최초 baseline 수집
4. Compute stage/worker metric과 Core HTTP/dispatch/poll/persistence metric 추가
5. integration에 Prometheus/Grafana 개발·검증 composition 구성
6. steady/burst 부하 테스트와 병목 관측
7. 수동 performance CI 및 결과 artifact 구성
8. 측정 근거에 따라 Compute calendar indexing 개선
9. 측정 근거에 따라 Core 조회/index 개선
10. 동일 fixture·환경의 전후 비교를 context case study로 정리

순서는 병목이 측정으로 확인될 때만 8~9단계 최적화를 수행한다는 뜻이다. 실제 baseline이 생기기 전에
절대 latency SLO나 자동 performance gate를 확정하지 않는다.
