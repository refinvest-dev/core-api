# Workflow 계약 후보 consumer·수용·전파 (DRAFT)

상태: **ADR-079·WP01~04 미채택**, 2026-11-12. [접수](TRADING_REVIEW_WORKFLOW_CONTRACT_DRAFT.md)·
[identity/child](TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md)의 adoption gate다. 아래 계획은 전부 미실행이다.
고정 source 읽기·Markdown 검사는 실제 HTTP/SQL·계산/장애/삭제 수용 증거가 아니다.

## 고정 source와 확인한 차이

| source | 확인한 범위/한계 |
|---|---|
| [Context Versioning][versioning]·[Persistence][persistence] | ADR-062 type/retry/원자 공개; ownerId 대 ownerScope projection 미확정 |
| [현재 API][api]·[public schema][public]·[Acceptance][acceptance] | output selector 없음; raw 부재 410/즉시 registry 422와 failed Run Then; retry parent는 응답에만 있음 |
| [Compute schema][compute] | Normalization의 Run/Session+두 raw grants·Analytics의 Revision/input/config pin. canonical rebuild/Finding-only를 generic job으로 표현하지 않음 |
| [Go Workflow][go-workflow]·[현재 구현][go-status] | WF-G01~08 설계·WUC-01~12. Workflow 업무 Store/HTTP/Worker 구현 증거 없음 |
| [Core V3][core-v3]·[lifecycle][core-life]·[publication][core-publish] | active-session unique·VALIDATING Session 기반 ordinary import. 재처리 child에 그대로 적용 불가 |
| [Core inventory][core-count] | Analysis/Reprocessing persisted runtime 부재를 source 주석으로 확인. count 0은 새 모듈 absence 증거가 아님 |
| [Compute normalization][compute-normalize]·[jobs][compute-jobs] | raw normalize/reconstruct·원 Run/key/fingerprint 저장. 새 purpose/profile 지원을 확인한 것은 아님 |
| [Analytics DTO][compute-dto]·[input 검증][compute-input] | Finding disabled literal·현재 supported schema 검증. target upgrade/Metric reuse 실행 수용 미확인 |

## consumer와 legacy 영향

| 소비자/소유자 | 검토할 변화 |
|---|---|
| Web→Core public | 산출물 selector/명시 retry UI·동기 오류와 durable 실패 조회 연결·같은 key replay. 실제 사용 이력/decoder를 확인해야 하며 endpoint 선언을 구현 증거로 삼지 않음 |
| Core→Go relay | body/key/오류·원 Run/조회 identity 보존·별도 service/위임/현재 authority. 기존 Core TR writer/Worker 중단, 이중 쓰기 없음 |
| Go Record/Workflow | Run/child intent·logical/full binding·Store Admission/FinishFailure/Publication 원자성. Reader는 조회, 일반 Update 우회 금지 |
| Go Analysis | private prepared R2·input BindOnce·자기 checkpoint/결과·최종 Tx 참여; Record가 Analysis를 import하지 않음 |
| Compute | versioned child profile/지원 type·원 token/grant/result/ACK·정리 책임. 없는 compute surface를 caller에서 계산하지 않음 |
| legacy/운영 | 원 Run/Session/Revision/ID·key/receipt/hash/createdAt 보존, unknown pin은 unknown. 실제 catalog·제약/기존 데이터와 별도 mapping 검증 |

AP01~06/ADR-078·LP/Import·위임 ingress 후보는 별도 미채택이다. WP와 맞는 부분이 있어도 함께 채택되지 않는다.
공개 Analysis 4상태와 내부 private 취소 checkpoint를 섞어 새 public CANCELLED를 만들지 않는다.
DeletionRequest의 request-time count/checklist·Generation/Tombstone·control carrier는 이번 WP로 대체하지 않는다.

## WR-G01~08 — 채택과 활성화 조건

| gate | 닫을 증거 | 연결된 Go gate |
|---|---|---|
| WR-G01 요청/응답 version | outputs/config/type·동기 실패와 Run 연결·retry parent·오류/receipt의 정확 schema/consumer 호환성 | WF-G01/07 |
| WR-G02 identity/legacy | owner projection·hash nullable/canonical bytes/vector·full binding 충돌·alias/retention·원 receipt 복구 | WF-G01 |
| WR-G03 child protocol | ordinary Import 불변·namespace/uniqueness·지원 type/target/input·create/grant/result/cancel/ACK/delete 계약 | WF-G02 |
| WR-G04 prepared source/Tx | private/public stable ID/FK·V2/input/export/grant·stage sealing·bounded publication·일반 Update 차단 | WF-G03 |
| WR-G05 pointer/경합 | Ledger-only latest 정책·모든 writer 잠금/CAS·두 child/lineage/logical success/outbox·UNKNOWN | WF-G04 |
| WR-G06 수명/실제 부재 | ADR-073 별도 채택·현재 ACK/expiry·Analytics remote absence·late create·삭제 closure/control/재시작 | WF-G05/06 |
| WR-G07 ingress/보안 | Core/Gateway·Worker authority·Member/쓰기 commit 선형화·operation 오류/현재 Guard | WF-G07 |
| WR-G08 운영/범위 전환 | 명시 업무 구현 범위·단일 writer 인계·실제 SQL/HTTP/장애/부하·DB history/backup restore·수용 증거 | WF-G08 |

WP01~04의 후보를 작성한 것만으로 WF-G01/02가 닫히지 않는다. 채택된 의미·정확 schema·consumer 전파와
구현/수용 증거를 각각 기록한다. 독립적인 Go 저장/Tx 상세 검토는 계속할 수 있다.

## 후보 수용 차이 — 전부 미실행

WR-DRAFT는 이 후보를 검토할 체크 ID이며 새 canonical AcceptanceOutcome/HTTP status가 아니다.
기존 TR-ACC·Go 계획을 재사용하고 아래는 새 계약 구분에 필요한 추가 assertion만 적는다.

| ID | Given/When → 확인할 Then | 기존 연결 |
|---|---|---|
| WR-DRAFT-01 | 같은 source/target의 Ledger-only와 pair 요청 → 각각 명시 outputs pin; 같은 legacy key 충돌, partial 공개 없음 | REPROCESS-001/003/010 |
| WR-DRAFT-02 | Analysis config 누락/null·Ledger-only config·불법 type/output → schema/semantic 거절, default/자동 승격 없음 | REPROCESS-004~006 |
| WR-DRAFT-03 | retained source/raw 부재 → 410 receipt+FAILED만 원자 저장, Compute/child 없음·old 보존 | DELETE-005·REPROCESS-002 |
| WR-DRAFT-04 | target unavailable → 422 receipt+FAILED; 일시 registry 장애는 unavailable로 확정하지 않음 | REPROCESS-008 |
| WR-DRAFT-05 | foreign/삭제된 parent·source → 현행 접근 오류, 신규 audit Run/dispatch·존재 누출 없음 | DELETE-006~008·REPROCESS-011 |
| WR-DRAFT-06 | 원 410/422 또는 202 뒤 terminal 실패·같은 key → 원 receipt/Run, 원 key를 후속 retry로 재결속하지 않음 | REPROCESS-002/007~009 |
| WR-DRAFT-07 | 같은 key·다른 source pointer/outputs/publish 또는 full tuple → conflict, hash root 변경 없음 | REPROCESS-007 |
| WR-DRAFT-08 | retryable FAILED·새 key+명시 parent 동시 요청 → 단일 successor·새 Run/attempt/retryOf; 원 Run/Session 불변 | REPROCESS-009 |
| WR-DRAFT-09 | 영구 source 부재·UNKNOWN·CANCELLED 또는 late child → 자동 retry/새 key/create·stale ACK 위조 없음 | DELETE-005·REPROCESS-011 |
| WR-DRAFT-10 | ACCEPTED Session의 child 준비/재시작 → original state/createdRevision 보존·ordinary unique 유지·원 child binding 복구 | REPROCESS-001/003·Go WUC-09 |
| WR-DRAFT-11 | canonical rebuild/Finding-only 미지원 target → unsupported/호환 실패 의미 채택 후 검증, raw/full analytics fallback 없음 | REPROCESS-003/006/008 |
| WR-DRAFT-12 | private R2→A2 → export/input/result/public ID 동일·외부 비노출·검증 incarnation 불변 | REPROCESS-010·Go APC-A11 |
| WR-DRAFT-13 | final Tx 중 실패/UNKNOWN·ACK 유실 → 양 child/Run/pair/logical success/outbox 전부 또는 무효; 원 receipt 조정 | REPROCESS-009/010·Go AST-A13~15/APC-A12 |
| WR-DRAFT-14 | 삭제/취소·수명 경계 → 공개 차단/기존 pair 보존·private cleanup 추적; ACK/TTL/404로 Analytics 부재 단정 없음 | REPROCESS-011·DELETE-010/013·RACK-DRAFT |

전체 canonical ID는 [Acceptance](TRADING_REVIEW_ACCEPTANCE.md)의 TR-ACC-*이며 Go 상세 계획은
[Workflow 수용 연결][go-workflow]을 따른다. source table·링크 검사는 이 14개 실행 결과가 아니다.

## 적용 순서와 중단

1. WP별 의미/대안·미해결 WR gate를 리뷰해 별도 adoption 범위를 기록한다. ADR-079는 그 전까지 DRAFT다.
2. versioned public/내부/Compute schema·예제/vector·consumer/legacy mapping·지원 type을 먼저 확정한다.
   현재 wire snapshot·fixture를 candidate Markdown만으로 덮어쓰지 않는다.
3. Context 정본 반영 뒤 Core/Compute/Web snapshot·decoder/relay·Go 고정 source를 정확 revision으로 전파한다.
   새 문서 분류/sync 대상도 실제 script에서 확인한다. Go에 snapshot 체계가 이미 있다고 가정하지 않는다.
4. 명시한 Go 구현 전환 뒤 Store/Tx·Worker/Compute 연결과 실제 SQL/HTTP 수용을 실행한다.
   E2E 전 integration verify-contract-sync.ps1, Web 포함 시 -IncludeWeb; Core doc snapshot은 verify-context-doc-sync.ps1로 검증한다.
5. 실패 시 해당 profile/type의 신규 admission/publication을 중단하고 기존 R1/A1·원 request/child/UNKNOWN/ACK/정리 기록을 유지한다.
   원 writer를 동시에 재활성화하거나 completed 결과를 rewrite·pointer rewind하지 않는다.

이번 문서 작업은 active OpenAPI/vector·계산·업무 코드/DDL·DB/환경 설정을 변경하지 않는다.
ADR-071/073~078·삭제 control·Analytics 실제 부재 정책·private 수명 채택은 별도다.

[versioning]: https://github.com/refinvest-dev/context/blob/d6eb79cb7b4308b21e2787c3387955476f29a78c/docs/TRADING_VERSIONING.md
[persistence]: https://github.com/refinvest-dev/context/blob/d6eb79cb7b4308b21e2787c3387955476f29a78c/docs/TRADING_REVIEW_PERSISTENCE.md
[api]: https://github.com/refinvest-dev/context/blob/d6eb79cb7b4308b21e2787c3387955476f29a78c/docs/TRADING_REVIEW_API.md
[public]: https://github.com/refinvest-dev/context/blob/d6eb79cb7b4308b21e2787c3387955476f29a78c/openapi/core-api.yaml
[acceptance]: https://github.com/refinvest-dev/context/blob/d6eb79cb7b4308b21e2787c3387955476f29a78c/docs/TRADING_REVIEW_ACCEPTANCE.md
[compute]: https://github.com/refinvest-dev/context/blob/d6eb79cb7b4308b21e2787c3387955476f29a78c/openapi/compute-api.yaml
[go-workflow]: https://github.com/refinvest-dev/trading-review-api/blob/e00d0991abc72a9ebc446411d00cd4679b0671b8/docs/design/workflow/review-and-acceptance.md
[go-status]: https://github.com/refinvest-dev/trading-review-api/blob/e00d0991abc72a9ebc446411d00cd4679b0671b8/engineering/IMPLEMENTATION_STATUS.md
[core-v3]: https://github.com/refinvest-dev/core-api/blob/3eb596eda23c94ec883ade2b34f694988d364b6a/tradingreview/adapter/persistence/src/main/resources/db/migration/V3__trading_normalization_dispatch.sql
[core-life]: https://github.com/refinvest-dev/core-api/blob/3eb596eda23c94ec883ade2b34f694988d364b6a/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/normalization/NormalizationWorkerLifecycle.kt
[core-publish]: https://github.com/refinvest-dev/core-api/blob/3eb596eda23c94ec883ade2b34f694988d364b6a/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcLedgerRevisionPersistenceAdapter.kt
[core-count]: https://github.com/refinvest-dev/core-api/blob/3eb596eda23c94ec883ade2b34f694988d364b6a/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcDeletionScopeInventory.kt
[compute-normalize]: https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/application/normalization_execution.py
[compute-jobs]: https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/persistence/postgres/trading_review_jobs.py
[compute-dto]: https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/api/schemas/trading_analytics_jobs.py
[compute-input]: https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/application/analytics_input.py


## HTTP/DTO 문서 후보 연결

[HTTP registry](TRADING_REVIEW_WORKFLOW_HTTP_DRAFT.md)·[DTO 후보](TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md)에
기존 삭제 4개/shared status 1개의 소유 profile·새 재처리 4개 후보와 versioned field/receipt를 구체화했다.
기존 WR-DRAFT ID를 유지하고 다음 assertion을 해당 계획의 HTTP/consumer 검증으로 연결한다. 전부 미실행이다.

| 기존 ID/gate | 추가로 확인할 경계 |
|---|---|
| WR-DRAFT-01/02/07·WR-G01/02 | 필수 requestedOutputs/type/config·publish/null·기존 namespace·legacy full binding/정확 hash projection |
| WR-DRAFT-03~06·WR-G01/07 | durable failure commit과 동기 오류·승인된 opaque Run 연결·UNKNOWN/foreign 미노출·원 admission 의미와 현재 trace 구분 |
| WR-DRAFT-08/09·WR-G01~03 | 새 key+명시 FAILED parent·누락/null/다른 binding·UNKNOWN/취소 자동 retry 거절 |
| WR-DRAFT-10~14·WR-G03~06 | private child 비노출·지원 Compute profile·최종 공개/취소·고정 ACK·실제 부재·수명 gate 유지 |
| WR-G07/08 | 기존 삭제 registry/profile 중복 없음·별도 status 위임·service AND 사용자/current authority·Core relay/CSRF/timeout/trace/consumer |

다음은 public/internal OpenAPI draft·정적 예제/위험 변이 검사다. integration의 exact 신규 파일 분류/gate를
먼저 준비하고 Context preview·Core snapshot·Go 고정 연결 의존을 맞춘다. 이번은 새 YAML을 추가하지 않아
기존 draft 분류를 변경하지 않으며 schema/JWT/authority/SQL/E2E 검증 성공을 주장하지 않는다.


## OpenAPI 정적 후보 연결

[Reprocessing OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)에 public/internal 8개 operation·완전 synthetic 예제와
정적 schema/위험 변이를 연결했다. 검사 수·명령은 해당 문서에 기록한다. 이 검사는 아래 기존 semantic 계획의 구조 일부만 대조한다.
WR-DRAFT-01~14는 모두 미실행이며 ID·기존 TR-ACC/WUC/WFP 계획을 중복 생성하거나 완료로 변경하지 않는다.

| 기존 gate | 정적으로 대조한 부분 | 계속 필요한 증거 |
|---|---|---|
| WR-G01/02 | type/outputs/config/target component·nullable·원 namespace | 정확 full binding/hash·legacy migration·WP02 commit/replay/UNKNOWN |
| WR-G03~06 | private child 비노출 shape·완료 산출물 조합 | 실제 child profile·final Tx/CAS·수명/ACK·remote fence/actual absence |
| WR-G07 | credential AND·audience/scope/caller 선언·CSRF·trace/cache·오류 Run 참조 제한 | JWT/issuer/current Member·Go owner/Guard·Member/commit 선형화·중계/consumer |
| WR-G08 | integration 분류 선행·Context 정적 CI/impact 계획 | 새 Core snapshot/최신 sync·실제 수용·DB/ID/단일 writer/backup 인계 |

admit 422는 접근 가능한 source의 확정 business rejection을 기록한 후보이며 Run 참조를 생략하는 fallback이 없다.
삭제/현재 접근 차단의 일반 410과 확정 source 실패를 기록한 410을 구분한다. schema가 기록/권한의 사실을 증명하지는 않는다.
canonical rebuild/Finding-only·private source·참조 VO 내부 확장 필드와 실제 decoder·생성 client의 required/nullable 차이는 consumer gate다.
정적 PASS를 정책 채택으로 보지 않으며 다음은 이 소비자/legacy·receipt/profile·잔여 gate를 통합 검토한다.


## Consumer/legacy 통합 리뷰 연결

[WCR-R01~08](TRADING_REVIEW_REPROCESSING_CONSUMER_REVIEW_DRAFT.md)에 실제 소스 범위와 관측 한계·기존 row/receipt·
불명 source pin·Web 오류/abort·parent/child projection·Compute 지원/actual absence·다음 검토 범위를 기록했다.
리뷰 ID는 새 semantic 테스트/상태가 아니다. WR-DRAFT-01~14는 여전히 전부 미실행이다.

| 기존 gate | 추가로 구체화한 판단 |
|---|---|
| WR-G01/02 | 공개 계약 선언과 실제 consumer를 분리. 없는 Reprocessing history 이관을 가정하지 않되 운영 inventory는 별도 확인. source pin 불명에는 미기록 business422/가짜 Run 금지 |
| WR-G02/03 | parent full tuple/logical hash와 child Target/input/create key의 projection을 구분. ownerId/ownerScope·각 canonicalization·nullable/원 receipt는 정확 byte/이력으로 확인 |
| WR-G03~06 | raw wire 재사용은 동등성 검증 뒤 판단. canonical/Finding/private source 지원·shared Analytics ACK와 실제 부재 gap을 혼동하지 않음 |
| WR-G07 | 현재 Web의 오류 축약/401 refresh/abort·Core advice/오류 DTO와 후보 차이. issuer/current Member/Go Guard/commit 선형화는 실제 수용 대기 |
| WR-G08 | Core 후보 snapshot/marker 일치와 main sync 성공을 구분. TR만 같은 DB 인계·단일 writer·명시 구현 전환/실제 운영 증거 필요 |

다음은 identity/full binding·원 admission receipt·불명 source 처리 profile 상세화다.
정확 owner/nullable/hash projection·namespace/alias/보관/복구·지원 legacy 범위를 먼저 검토하고 필요한 vector/채택 범위를 정한다.
이번은 새 JSON schema·vector·DDL/SQL·HTTP/DB 수용이나 구현 전환이 아니다.


## 순차 identity/hash 논의 연결

[① 논의안](TRADING_REVIEW_REPROCESSING_IDENTITY_POLICY_DRAFT.md)에 ownerId/ownerScope·신규 profile 표현과 기존 byte/key 보존 조건을 적었다.
WR-G02의 구체적인 후보이지 gate를 닫는 증거가 아니다. 일곱 필드의 문자열/null/정렬 recipe를 서술했으며 실제 hash/vector는 만들지 않았다.
원 key/profile·namespace/alias·성공 uniqueness와 supported source의 실제 이력을 확인하고 정확 채택 범위를 기록한 뒤
필요한 vector/consumer byte 일치·충돌/복구 수용을 기존 계획에 연결한다. 새 semantic test ID를 추가하지 않는다.
② binding에서 retry parent/요청 의도와 계산 동일성을 구분하고 ③ receipt·④ source pin 불명은 이후 논의한다.
기존 OpenAPI의 schema/예제 CI·Markdown 검사는 이 새 recipe의 byte 호환성·정책 채택·업무 실행 증거가 아니다.


## 순차 binding 수용 연결

[② 후보](TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md)의 검토 사례를 기존 WR-G02·WR-DRAFT-01~14에 연결한다.
same-key의 publish omitted/true·source Analysis omitted/null과 forbidden null·reason/parent 변경을 구분하고,
cross-key의 exact source/outputs/publish·reason audit·FAILED parent chain/단일 successor·profile/Guard·UNKNOWN을 검증해야 한다.
새 semantic ID/실행 결과는 추가하지 않는다. ① 채택 권고 표도 여전히 미채택이며 byte/profile·실제 이력과 활성화는 별도다.


## 순차 receipt 수용 연결

[③ 후보](TRADING_REVIEW_REPROCESSING_RECEIPT_POLICY_DRAFT.md)의 원202/현재FAILED·기록410/422 replay·key별 수렴/audit·
명시 retry 부모 mapping 보존·commit/응답 유실/UNKNOWN·현재 Guard/삭제·TTL/원 profile 불명 사례를 기존 WR-G01/02/07/08·
WR-DRAFT-01~14에 연결한다. 모두 semantic 미실행이며 새 test ID/실행 결과는 추가하지 않는다.
기존 Core resource_id는 원 receipt 전체의 증거가 아니다. ④ 불명 pin·receipt retention/legacy·actual authority/SQL/운영 인계는 열린다.
