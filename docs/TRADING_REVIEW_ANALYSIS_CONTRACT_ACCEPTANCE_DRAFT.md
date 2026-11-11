# Analysis 계약 consumer·수용·전파 리뷰 (DRAFT)

상태: **ADR-078·AP01~06 미채택**. [접수/운영](TRADING_REVIEW_ANALYSIS_CONTRACT_DRAFT.md),
[Review/Metric](TRADING_REVIEW_ANALYSIS_RESPONSE_DRAFT.md), [selection](TRADING_REVIEW_ANALYSIS_SELECTION_DRAFT.md)의 채택 조건이다.
source 비교·정적 검사·미실행 계획·운영 수용을 구분한다. 기존 AUC/AND/LUC와 canonical TR-ACC를 대체하지 않는다.

## 고정 source·consumer

| 경계 | 조사 SHA·관측 | 영향/미확인 |
|---|---|---|
| Context | `f00d277681d18f03ce59e2013fc5bbe877ae3c30`: public 8 operation·Review 전역 count/generic Metric·4role·flat evidence | schema 관측이며 실제 payload/client 부재 증거 아님 |
| Go | `2a4cdad2ba0a37e16b05903e2c86829e0899c54a`: AUC-01~10·AP01~06, durable 준비/once-only binding 보완 | 구현·새 HTTP/SQL·예산/Runtime 활성화는 별도 |
| Core | `d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1`: V2 input builder/reader/artifact·UUID 경로 | API/Run 전체 source를 확인하지 못함. 실제 DB/동적 route·응답 이력 미조사 |
| Compute | `113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963`: full input/typed Metric·7role·disabled Findings·6section hashes | 계산/wire는 유지. 새 read wrapper/selection이 Compute 변경을 자동 요구하지 않음 |
| Web | `9354dfe321ee8543c10e0914249f06d6643ca6db`: tracked src 직접 Analysis path/operation 미발견 | generated/외부 client·배포·UI 수용 미확인 |
| integration | `ec40a12614a5a9f35cffaa57280c6512b93f371a`: docs/*.md→Core snapshot, YAML 명시 분류 | 문서 전파·static evidence와 신규 Go E2E/정책 채택 구분 |

주요 source:

- [Go AUC/API 후보](https://github.com/refinvest-dev/trading-review-api/blob/2a4cdad2ba0a37e16b05903e2c86829e0899c54a/docs/design/analysis-usecases/api-mapping.md),
  [admission/입력 준비](https://github.com/refinvest-dev/trading-review-api/blob/2a4cdad2ba0a37e16b05903e2c86829e0899c54a/docs/design/analysis-usecases/admission.md),
  [public schema](https://github.com/refinvest-dev/context/blob/f00d277681d18f03ce59e2013fc5bbe877ae3c30/openapi/core-api.yaml),
  [API](https://github.com/refinvest-dev/context/blob/f00d277681d18f03ce59e2013fc5bbe877ae3c30/docs/TRADING_REVIEW_API.md).
- [Core builder](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/TradingAnalyticsInputBuilder.kt),
  [reader](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcTradingAnalyticsRevisionReader.kt),
  [artifact store](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/adapter/storage/src/main/kotlin/com/refinvest/core/tradingreview/adapter/storage/LocalTradingAnalyticsArtifactStore.kt).
- [Compute input identity](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/contracts/analytics/identity.py),
  [result projection](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/application/analytics_result.py),
  [integrity](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/contracts/analytics/result_integrity.py),
  [job orchestration](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/application/analytics_jobs.py).
- [Web src](https://github.com/refinvest-dev/web/tree/9354dfe321ee8543c10e0914249f06d6643ca6db/src),
  [snapshot map](https://github.com/refinvest-dev/integration/blob/ec40a12614a5a9f35cffaa57280c6512b93f371a/scripts/contract-sync-map.json).

Core tracked tradingreview와 Web src의 runTradingAnalysis/pollTradingAnalysisStatus/listTradingAnalysisRuns/getTradingReview/
getBehaviorMetric/listReviewUnits/getReviewUnitEvidence·trading-analysis-runs/analyses 직접 검색 관측을 사용한다.
직접 미발견을 전체 배포/다른 저장소·실제 사용자/DB·동적/generated consumer 부재로 일반화하지 않는다.

## 채택·활성화 gate

| Gate | 필요한 산출물/검증 | 현재 상태 |
|---|---|---|
| AR-G01 정책 | AP별 채택/보류·대안/제품 trade-off·대체할 기존 의미·release 묶음 | 모두 미채택 |
| AR-G02 legacy/consumer | 실제 public 응답·key/mapping/ID·Review 통계/hash·classification/role/ref 이력·UI/SDK/decoder | source 관측만, 운영 inventory 미실행 |
| AR-G03 admission/storage | exact ownerScope/VersionSet logical projection·source/publish 비교·failed 재접수·원 receipt·삭제 proof·once-only/예산 | literal hash/root/DB/history·준비 수명 미확정 |
| AR-G04 coherent read schema | AP02/03/04 typed Review/Metric·classification null·7role/default set/reference·LP05/direct pages | 신규 path/version/schema·decoder/cache 미작성 |
| AR-G05 hash/큰 결과 | 원 3 typed Metric/6section·UTF-16/JCS/null·참조 closure·fan-out/byte/heap·full quality | 새 view oracle/부하·현재 fixture 대비 수용 미실행 |
| AR-G06 ingress/cancel | service/delegation/registry·authority/commit·GET 오류/중계·4상태 취소 projection·trace | issuer/운영·unexpected Compute cancel 매핑 미확정 |
| AR-G07 runtime 수용 | 아래 계획·기존 AUC/AND/LUC·실제 HTTP/SQL/동시성/UNKNOWN·Stage/ACK/삭제/부하 | 업무 미구현·새 계획 미실행 |
| AR-G08 전파/인계 | Context→snapshot→Core/Go/Web/SDK·단일 writer·TR만 같은 DB 인계·backup/복구/최신 증거 | 문서 전파 별도, 운영 미실행 |

gate는 체크리스트이며 이미 구축된 CI 승인 장치가 아니다. AP02/03/04는 같은 read version/consumer 전환으로 묶는 것을 우선 권장한다.
부분 채택은 나머지 current 의미와의 호환 증거가 있을 때만 한다. AP01 검토를 새 hash·재접수·모든 권한 정책 승인으로 확대하지 않는다.
AP05의 public 범위 유지는 runtime 취소 매핑이 완료됐다는 뜻이 아니다. ADR-074~077 및 LP05의 별도 DRAFT도 승인하지 않는다.

## 새 미실행 수용 계획

TR-PLAN-ANALYSIS-CONTRACT-001~016은 미실행이다. 구현/fixture를 만들었다는 뜻이 아니며 기존 AUC/AND와 겹치면 같은 증적을 연결한다.

| 계획 ID | Given / When | Then·연결 |
|---|---|---|
| TR-PLAN-ANALYSIS-CONTRACT-001 | current S→T 뒤 same key omitted/null/명시 S/T·publish 기본/다른 값 | 최초 effective pin 기준 replay/conflict·선택 audit·묵시 current 변경 없음; AUC-A02 |
| TR-PLAN-ANALYSIS-CONTRACT-002 | 동일 legacy logical hash/다른 Revision·full version/source/publish·만료 mapping | 승인 binding/conflict/새 key version·권위 receipt 복구, 재라벨/새 SHA root 몰래 추가 없음; AUC-A03 |
| TR-PLAN-ANALYSIS-CONTRACT-003 | same-key FAILED replay·new-key 명시 재접수·active/success 경합 | 채택된 새 Run/Job/lineage·단일 active/success·기존 불변, 자동 attempt와 분리 |
| TR-PLAN-ANALYSIS-CONTRACT-004 | V1/legacy·zero-unit V2·PENDING preparation/부분 두 입력·bind UNKNOWN | pre-Run V2 gate·원 once-only binding/adoption/cleanup·완전 create, 0/default 합성 없음; AUC-A01/05~08 |
| TR-PLAN-ANALYSIS-CONTRACT-005 | Job 이전 준비 지연/재시작·Compute 24h cutoff·expiry/예산 부재 | 별도 preparation 실행 정책/gate·수명 임의 연장/다른 시계 복사 없음 |
| TR-PLAN-ANALYSIS-CONTRACT-006 | Metric별 겹친/다른 모집단·현재 Review global count·legacy client | 승인 versioned per-Metric 의미/compatibility, sum/union/0 복사 없음; AUC-A12 |
| TR-PLAN-ANALYSIS-CONTRACT-007 | full quality·legacy createdAt/lineage 없음/충돌·publication UNKNOWN | 승인 stamp/known-none/unknown 처리·근거 완전, clock/[] 보정·stamp 덮기 없음 |
| TR-PLAN-ANALYSIS-CONTRACT-008 | typed value/distribution/currency/pairs·unavailable/zero·hash wrapper 변경 | 원 typed metricHash/Analysis hash·정확 nullable/단위·V1 findings[]·lossless decoder; AUC-A13 |
| TR-PLAN-ANALYSIS-CONTRACT-009 | default/metric/role/reason filter·multi-role·prior unit classification 없음 | 승인 set/dedup/total·null vs UNAVAILABLE 분리, 가짜 outcome/Record 후필터 없음; AUC-A14~16 |
| TR-PLAN-ANALYSIS-CONTRACT-010 | membership first page/reference 다른 Run/result·role/reason·현재 Guard 변경 | exact 문맥/page/order·접근 재검증·token 권한화/4role cast·stale cache 없음 |
| TR-PLAN-ANALYSIS-CONTRACT-011 | allocation 다중 근거·direct refs·큰 fan-out·두 evidence page | LP05/AP04 승인 단위/완전 chain·독립 total·bounded 소비, 가짜 allocation/truncation 없음; AUC-A17 |
| TR-PLAN-ANALYSIS-CONTRACT-012 | 내부 취소/삭제와 발행·unexpected Compute CANCELLED·browser abort | approved terminal mapping·Guard/fence·실제 purge, 새 public enum/거짓 RUNNING·자동 취소 없음 |
| TR-PLAN-ANALYSIS-CONTRACT-013 | service credential/위임 실패·Member deny/authority unavailable·GET 오류 | 승인 relay/status/safe envelope·trace·권위 선형화 한계, 잘못된 logout/404 은폐 없음 |
| TR-PLAN-ANALYSIS-CONTRACT-014 | Run list 동시 publish/delete·page 경계·유효 큰 DTO/quality | parent/items/total snapshot·정확 순서·checked offset/byte budget, cross-page 과장/summary 축소 없음 |
| TR-PLAN-ANALYSIS-CONTRACT-015 | retained Go result·Compute ACK/TTL expiry·raw-only·Book 삭제 | 현재 Go lifecycle 조회/차단·원 hash·raw 미복원·수명 혼동/재계산 없음 |
| TR-PLAN-ANALYSIS-CONTRACT-016 | source/consumer 혼합 version·R2/A2/ACK/UNKNOWN·Core→Go cutover/복구 | coherent view·같은 Tx/pair·원 receipt·단일 writer/전체 참조 복구 증거; AUC-A19~22 |

## 전파·검증·복구 순서

1. 이 DRAFT를 리뷰하고 AP별 채택/미해결·legacy·consumer 결정을 기록한다. 현재 OpenAPI/fixture/runtime은 유지한다.
2. 새 Markdown은 기존 integration docs/*.md→Core wildcard 대상이다. 이번에는 YAML이 없어 명시 draft 분류를 추가하지 않는다.
   선행 [Core #129](https://github.com/refinvest-dev/core-api/pull/129)는 조사 시 미병합이었다. 새 candidate preview 계획에는
   이번 Analysis 문서뿐 아니라 미전파 Ledger/ADR/API 변경도 포함될 수 있다. 실제 source SHA/목록으로 확인하고 current main sync와 구분한다.
3. Context main 반영 뒤 기존 snapshot 자동화를 통해 Core 문서를 전파한다. 열린 snapshot PR이 갱신될 수 있으므로 최신 diff/CI를 재검토한다.
   snapshot merge는 정책 채택/route 활성화가 아니다. Go는 고정 Context commit/PR 링크로 추적하고 아직 snapshot 체계를 갖춘 것으로 주장하지 않는다.
4. 채택한 scope에만 별도 versioned OpenAPI/예제·hash/view oracle·registry·consumer PR을 만든다. 새 YAML은 integration 분류를 먼저 준비한다.
   Core proxy·Go mapper/저장/Worker·Web/SDK·Compute의 실제 영향을 각각 검토하며 read wrapper만으로 Compute 계산 변경을 요구하지 않는다.
5. 실제 authority/HTTP·SQL/경합/부하·ID/checkpoint/예산·legacy/복구 검증 후 명시 구현/활성화 범위를 확정한다.
   Core 기존 TR writer/Worker를 중단한 단일 writer 전제로 같은 PostgreSQL의 TR 데이터만 Go에 인계한다.
6. E2E 전 contract sync와 Core context-doc sync를 통과하고 당시 전체 SHA·clean·증거를 남긴다.
   이전 Import main smoke를 새 Analysis 업무 성공으로 승계하지 않는다. 실패/미실행을 성공으로 표기하지 않는다.

문서 revert는 runtime/DB를 바꾸지 않는다. 실제 rollback은 새 Run/ID·source/ref/ACK·receipt/조회 schema·새 접수분을 보존할 수 있어야 하며
UUID-only Core 단순 재시작·역 CAST·구 version DTO로 필드 삭제를 복구로 간주하지 않는다. backup/단일 writer·읽기/접수 gate와
forward recovery/compatibility를 실제 runbook으로 검증한다. 이번에 운영 데이터/배포를 변경하거나 복구 리허설을 실행하지 않았다.
