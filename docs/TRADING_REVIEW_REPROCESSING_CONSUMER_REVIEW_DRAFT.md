# Reprocessing consumer·legacy·receipt/profile 통합 리뷰 (DRAFT)

상태: **ADR-079/WP01~04 미채택**, 2026-11-14. [OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md) 뒤의
소비자/기존 데이터 검토다. 제품 의미·wire·hash·DB를 변경하지 않으며 정책 채택이나 업무 구현 전환을 선언하지 않는다.
[수용 gate](TRADING_REVIEW_WORKFLOW_CONTRACT_ACCEPTANCE_DRAFT.md)의 WR-G01~08·WR-DRAFT-01~14를 그대로 사용한다.
아래 WCR-R01~08은 리뷰 항목이며 새 semantic test ID/상태/enum이 아니다.

## WCR-R01 — 선언과 실제 소비자 구분

| 고정 source | 관측한 사실 | 해석 한계 |
|---|---|---|
| [현재 public 계약](https://github.com/refinvest-dev/context/blob/b3b8a4752219f3462c8528940fd5355fc1dddc0d/openapi/core-api.yaml) | 기존 재처리 4개 operation·optional config/parent·publish default 선언 | 선언은 controller/decoder·실제 호출 이력의 증거가 아님 |
| [Core inventory](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcDeletionScopeInventory.kt)·[exception boundary](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/error/TradingReviewExceptionHandler.kt) | 해당 구현의 Analysis/Reprocessing persisted runtime 부재 주석; 기존 TR controller advice 경계 | source 주석/카운트 0으로 운영 DB·다른 배포/외부 소비자 부재를 증명하지 않음 |
| [Web HTTP client](https://github.com/refinvest-dev/web/blob/9354dfe321ee8543c10e0914249f06d6643ca6db/src/shared/api/http.ts) | 일반 Core client·auth retry·오류 축약·성공 body의 TypeScript cast | 재처리 UI/API caller·runtime schema decoder 구현을 확인한 것이 아님 |
| [Go runtime](https://github.com/refinvest-dev/trading-review-api/blob/e29ad66917443196be3441d7a91d06a28757f082/internal/bootstrap/runtime.go)·[owner queries](https://github.com/refinvest-dev/trading-review-api/blob/e29ad66917443196be3441d7a91d06a28757f082/internal/bootstrap/ownerqueries.go) | probe HTTP와 in-process Account/Book Query 조립. HTTP mount는 미래 identity boundary 대기 | 재처리/거래 조회 ingress·Workflow 업무 구현 증거 아님 |

고정 HEAD의 tracked source에서 `reprocess/requestedOutputs/retryOfReprocessing`을 조사했다.
Core `tradingreview/**/*.kt,*.sql`·`app/src/**/*.kt` 429개에서는 count/checklist 관련 22줄만 발견했다.
Web `src/**/*.ts,*.tsx` 93개·Compute `src/**/*.py` 172개·Go `internal/**/*.go`·`cmd/**/*.go` 41개에서는 일치가 없었다.
이는 조사한 checkout의 텍스트/구조 관측이다. 배포 코드·실제 DB·로그·외부 consumer inventory는 실행하지 않았다.
완전한 프로그램 도달성/미존재 증명으로 보지 않는다.

따라서 기존 계약 v1, 실제 저장 데이터, 새 v2 소비자를 분리한다. 존재를 확인하지 않은 ReprocessingRun history의
대규모 변환을 기정사실화하지 않는다. 반대로 repository에서 호출부가 없다는 이유로 wire 호환성 검토를 생략하지 않는다.

## WCR-R02 — request와 legacy를 분류할 기준

| 입력/기록의 증거 | 권장 처리 경계 | 금지할 추정 |
|---|---|---|
| 명시 outputs·완전 pin·원 profile/receipt가 있는 새 후보 | 동일 profile의 정확 full binding/원 receipt 검증 | 조회 시 최신 config/target·새 source로 재구성 |
| 계약 v1 body만 있고 원 산출물 의도/receipt 불명 | 별도 legacy/consumer 판정 전 새 profile admission 비활성 | config 또는 sourceAnalysis 존재로 pair/Analysis-only 추론 |
| 원 key/receipt가 확인됨 | 현재 권한 뒤 원 mapping/kind/Run을 보존해 replay | 새 key 의미로 해석하거나 retry successor로 원 mapping 이동 |
| key 조회 miss·보관 기간 경과·불완전 claim | authoritative 기록/namespace/복구 이력 조사 | 미접수 확정·새 Compute create/attempt 허가 |
| 현재 소유 source지만 tuple/lineage/hash pin 불명 | 아래 WCR-R03의 미해결 source profile로 분리 | 가짜 hash·0/빈 lineage·source version default로 실패 Run 생성 |

`publishAsLatest` omitted=true와 false·명시 null 거절, sourceAnalysis omitted/null 허용과 retry parent omitted/non-null을 구분한다.
schema/생성 type이 presence를 소실하면 mapper 이전에 처리해야 한다. body/headers를 retry마다 다시 만들면서 key를 바꾸지 않는다.
현행 계약에 endpoint가 있다는 이유로 기존 서버가 요청을 접수했다고 주장하지 않는다.
실제 레거시가 발견되면 원 body/profile·key·receipt/Run·생성 시각·ID/hash를 보존해 mapping 가능 여부부터 판정한다.
신규 Aggregate Snowflake와 기존 Long/UUID·Compute token을 구분하며 서로 변환/재발급하지 않는다.

## WCR-R03 — receipt와 확정 불가 source

[기존 idempotency 저장소](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcTradingIdempotencyStoreAdapter.kt)와
[V1 DDL](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/adapter/persistence/src/main/resources/db/migration/V1__trading_account_book.sql)은
owner/operation/key·payload fingerprint·nullable BIGINT resource_id를 저장한다. 이 source에는 재처리의 원 HTTP 종류/code·
recorded rejection·profile/selector/full binding을 복구할 전체 receipt가 없다. 기존 다른 명령의 구현을 재처리 receipt 구현으로 간주하지 않는다.
JDBC `getLong(resource_id)` 결과만으로 SQL NULL을 실제 ID 0으로 해석하지 않는다. 이것은 source의 표현상 위험이며 실제 불완전 row 관측은 아니다.

WP02 후보는 접근 가능한 source/target identity를 확정한 business failure를 FAILED Run·원 mapping/오류 receipt와 함께 commit한다.
일반 저장 오류는 rollback하며, 기록해야 할 business rejection은 callback의 오류로 반환해 기록을 지우지 않는다.
commit UNKNOWN은 원 key/fence의 effect를 조정하고 조회 miss로 rollback을 단정하지 않는다.
원 202 뒤 현재 FAILED여도 admission 의미는 202다. 원410/422도 원 의미/Run을 보존하며 trace/현재 View까지 고정 bytes는 아니다.
현재 권한/Guard 거절은 replay보다 먼저다. raw-only와 source subtree 접근 차단을 같은 상태로 해석하지 않는다.

**오류 원인부터 구분한다.** 일시 저장소/registry 장애나 저장 데이터의 decoding/무결성 이상은 기존 기술 오류/rollback·복구 경계다.
이런 오류마다 business FAILED Run이나 새 legacy profile을 만들 필요는 없다. 반면 정상으로 보존된 legacy 형식이 새 pin 요구를
표현하지 못하는 경우를 무조건 데이터 손상/일시 장애로 분류해서는 안 된다.

**남은 계약 공백**: schema-valid request가 가리키는 정상 legacy source라도 source tuple/lineage 자체를 확정하지 못할 수 있다.
WP02는 이를 fake pin으로 채우지 말라고 하고, 새 Run schema는 source/version/lineage를 요구하며 접수 422는 recorded Run 참조가 필수다.
따라서 이 경우에 미기록 business 422를 fallback으로 반환할 수 없다. 영구 결손을 일시 장애/UNKNOWN으로 무조건 숨기지도 않는다.
해당 source의 접수를 활성화하기 전에 다음 중 정확한 제품/profile 처리를 별도로 채택해야 한다.

- 실행 전에 지원 source를 명확히 제한하고, pin을 확정할 수 없는 입력의 안전한 거절 code/status·receipt 경계를 정의한다.
- 불명 source를 기록할 별도 명시 모델/응답이 필요하다면 versioned 계약과 consumer를 함께 변경한다.
- 검증된 원 기록으로 pin을 복구할 수 있는 source만 지원하고, 복구 불가 데이터는 활성화 대상에서 제외한다.

이번은 대안/차단 조건 기록이다. 새로운 오류 code/status·Run 상태나 partial receipt schema를 발명하거나 선택하지 않는다.
원 저장/receipt와 source profile을 확정하기 전 전체 재처리 지원/WR-G01·02 완료로 표시하지 않는다.

## WCR-R04 — Web/Core 오류·retry·abort 경계

| 현재 source 관측 | 후보와의 차이 / 후속 소비자 조건 |
|---|---|
| Web ApiError는 status/message/code/violations만 보존 | 현재 traceId/retryable/reprocessingRunId를 소비하지 않는다. 승인된 versioned 오류만 typed 참조로 전달하고 다른 오류에는 ID를 합성하지 않음 |
| Web은 일반 401에서 refresh 후 재요청·필요 시 로그인 이동 | 내부 SERVICE_OR_DELEGATION_REJECTED를 public auth401로 그대로 중계하면 잘못된 session 복구를 유발. Core 자신의 브라우저 인증 실패와 안전한 upstream 오류를 구분 |
| fetch는 init의 signal을 전달하지만 catch는 일반 network ApiError(status0)로 축약 | abort/status0은 서버 미접수·취소 증거 아님. 원 key를 보존한 확인 흐름과 명시 cancel을 분리 |
| auth refresh 후 같은 request closure 재실행 | 새 API 함수는 동일 의도에 동일 key/body를 보존해야 함. 현재 client는 재처리 key 생성/지속성을 구현하지 않음 |
| 성공 JSON은 `as T` cast | 타입 선언/예제 PASS를 runtime decoder·union/nullable/ID round-trip 지원으로 보지 않음 |
| [Core 오류 handler](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/error/TradingReviewExceptionHandler.kt)·[오류 DTO](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/error/TradingErrorResponse.kt)는 REQUEST_INVALID·userAction/occurredAt 등을 사용 | 새 후보 INVALID_REQUEST/닫힌 ErrorResponse와 같지 않음. 기존 auth/Strategy/Backtest/Import 오류를 일괄 변경하지 않고 versioned relay/mapper 경계에서 대조 |

현재 advice는 명시한 기존 controller들에 적용된다. 미래 Reprocessing controller/relay가 자동으로 포함된다고 가정하지 않는다.
public status/key/opaque ID·safe detail·현재 trace·no-store/Vary와 service/위임 오류 번역을 소비자 수용에 포함한다.
same-key의 원 admission replay와 명시 새 FAILED retry, 브라우저 취소와 durable cancel을 UI/오류 처리에서 합치지 않는다.

## WCR-R05 — parent binding과 child protocol은 별개

| 경계 | 고정 source 형식 | 적용 전 확인 |
|---|---|---|
| parent 요청 | [public draft](https://github.com/refinvest-dev/context/blob/b3b8a4752219f3462c8528940fd5355fc1dddc0d/openapi/drafts/trading-review-reprocessing-public.yaml)의 full Target tuple·outputs/config/publish/retry | 부모 full binding·logical identity 보존 |
| Normalization child | [identity Target](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/contracts/normalization/identity.py)는 normalizer/reconstruction/versionSetHash, [create DTO](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/api/schemas/trading_review_jobs.py)는 Session/Run·정확 raw grant 2개 | 부모 객체를 통째로 forward하거나 analytics 필드를 버려 부모 hash를 다시 쓰지 않음. child projection/hash 의미를 별도 고정·검증 |
| Analytics child | [DTO](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/api/schemas/trading_analytics_jobs.py)는 Analytics component·Revision/artifact/config/input pin, strict extra=forbid | full parent tuple을 그대로 넣지 않음. 각 원 identity/버전/hash와 child 요청의 대응을 명시 |
| logical key | [Versioning §10](https://github.com/refinvest-dev/context/blob/b3b8a4752219f3462c8528940fd5355fc1dddc0d/docs/TRADING_VERSIONING.md#10-idempotency)의 ownerId와 [Persistence §5](https://github.com/refinvest-dev/context/blob/b3b8a4752219f3462c8528940fd5355fc1dddc0d/docs/TRADING_REVIEW_PERSISTENCE.md#5-idempotency와-deterministic-uniqueness)의 ownerScope | 이름이 비슷한 hash/owner 표현의 byte 동등성을 추정하지 않음 |

Normalization의 RFC8785 input identity canonicalizer를 재처리 logical hash 구현으로 그대로 재사용하지 않는다.
기존 정본의 hash별 scalar/정렬·nullable/omitted·field projection과 각각의 vector를 따르며, 확정 전 새 Reprocessing hash/vector를 만들지 않는다.
부모 tuple을 보존한 채 child별 승인된 projection을 pin할 수 있는지 검토한다. 기존 child hash field에 부모 hash를 복사하거나
adapter가 임의로 일부 tuple만 골라 재hash하는 것은 해법이 아니다. 의미가 달라지면 필요한 version/이관을 먼저 채택한다.

## WCR-R06 — Compute 지원과 local child intent

| 작업 | 관측된 지원 | 활성화 전에 필요한 증거 |
|---|---|---|
| Raw REPARSE_SOURCE | normalization create/normalize+reconstruct·Run 단위 fence | 별도 local child intent와 기존 raw wire의 동등성/권위/결과·grant/ACK/삭제 binding. 기존 wire 재사용 가능성을 검토하되 성공했다고 가정하지 않음 |
| REBUILD_LEDGER / RERUN_RECONSTRUCTION | 복원 함수와 raw normalize 경로 | canonical/terms 또는 migration input의 durable 실행 계약. 가짜 raw grant/Session으로 대체하지 않음 |
| RERUN_ANALYTICS | retained Revision/artifacts/config/input 기반 Analytics create | source eligibility·exact supported target·bounded export/grant와 Record authority |
| private R2의 A2 | opaque Revision ID가 wire에 있음 | 공개 Reader 우회 없이 stable private ID·V2/manifest·sealed incarnation·최종 공개와 동일 pin |
| REEVALUATE_FINDINGS | 현재 Analytics DTO findingRuleSetVersion은 TRADING_FINDING_RULES_DISABLED_V1 | Metric reuse/Finding-only 실제 계약·지원. full analytics 자동 실행으로 바꾸지 않음 |

local child intent를 만들었다고 Compute에 새 parent/purpose field를 무조건 추가해야 하는 것은 아니다.
기존 wire·key/fingerprint·token·result 의미가 요구 binding을 그대로 표현한다는 증거가 있으면 그 경계를 재사용할 수 있다.
새 필드/입력/계산 의미가 필요하면 strict 기존 DTO에 끼워 넣지 않고 versioned protocol을 정의한다. 이번 리뷰는 어느 실행 surface도 채택/활성화하지 않는다.

[Core lifecycle](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/normalization/NormalizationWorkerLifecycle.kt)의
VALIDATING+activeRun 조건과 [V3 unique](https://github.com/refinvest-dev/core-api/blob/90e4ca8bef9ff18733af5d44db41b5153e46e466/tradingreview/adapter/persistence/src/main/resources/db/migration/V3__trading_normalization_dispatch.sql)를
accepted Session의 child에 그대로 적용하지 않는다. 원 Session 상태/createdRevision·UUID/attempt/token·일반 unique는 보존한다.
child 단계는 Go 소유 intent/checkpoint·현재 Guard로 관리하며 별도 Workflow Aggregate/engine을 추가하지 않는다.

## WCR-R07 — publication/ACK·실제 부재·권위

[공유 terminal delivery](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/application/terminal_delivery.py)에는 Analytics ACK 처리가 있다.
이를 Analytics ACK가 없다는 주장으로 바꾸지 않는다. 다만 ACK/취소/TTL은 실제 부재 proof가 아니다.
[Normalization 삭제 route](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/api/routes/trading_normalization_deletion.py)를 Analytics 삭제 계약으로 대체하지 않는다.
Go ACK Outbox·private R2/A2·최종 pair/lineage/parent/logical success·모듈 disposition의 실제 같은 Tx 수용은 별도다.
prepare에 PERSISTED를 보내거나 일반 실패/취소를 DISCARDED_STALE로 위조하지 않는다.

Core/Gateway service 인증 AND signed delegation·현재 Member·Go owner/Guard·Worker authority는 각각 검증한다.
probe나 in-process owner query 성공은 해당 ingress/Member-commit 선형화 증거가 아니다. 운영 TTL/skew/expiry나
ADR-073 private 수명을 여기서 채택하지 않는다. 기존 R1/A1 보존·단일 TR writer·같은 DB의 TR만 인계 원칙을 유지한다.

## WCR-R08 — 판정과 다음 계약 범위

| 판정 | 현재 상태 / 닫을 조건 |
|---|---|
| 작성/정적 완료 | HTTP/DTO·public/internal schema·152 예제 검증/177 schema 사례/24 거절 변이 자료. 정책/consumer 지원 완료가 아님 |
| source 대조 완료 | 위 고정 소스/선언/소비자 부재 관측·기존 row/오류/child 형식 차이. 운영 inventory/실행 수용 아님 |
| 제품 결정 대기 | owner projection/논리 hash·원 receipt와 불명 source profile·legacy 지원 범위·Ledger-only latest·child/수명/권위 |
| 실행/운영 대기 | 실제 decoder/client·SQL/lock/CAS/UNKNOWN·private/Compute/actual absence·DB/ID/단일 writer/backup·명시 구현 범위 |

다음 한 작업 단위는 **재처리 identity/full binding·원 receipt 및 불명 source 처리 계약 상세화**를 권장한다.
ownerId/ownerScope의 정확 표현·nullable/정렬/field projection·namespace/alias/보관/복구와 원 admission 종류/기록 조건을 먼저 확정할 검토안으로 만든다.
현재 hash를 보존할 수 있는지 대조한 뒤 필요한 vector/호환 profile의 범위를 정한다. 이 리뷰만으로 새 oracle·schema/enum을 만들지 않는다.
WP01~04·WR-G01~08과 연결해 별도 채택 범위를 기록하고 첫 업무 구현/수용 범위는 그 뒤 명시한다.
WR-DRAFT-01~14·기존 TR-ACC/Go WUC/WFP 계획은 계속 semantic 미실행이다. source 검색·문서/정적 CI·snapshot을 완료 증거로 대체하지 않는다.

## 전파·검증 경계

시작 시 [Context #66](https://github.com/refinvest-dev/context/pull/66)·[Go #61](https://github.com/refinvest-dev/trading-review-api/pull/61)
병합과 정확 PR head CI를 재확인했다. [Core snapshot #132](https://github.com/refinvest-dev/core-api/pull/132)의
`0663b746e518e35d664a9a32cad35a0e299366cc`에서 앞선 snapshot 9개와 revision marker가 Context
`b3b8a4752219f3462c8528940fd5355fc1dddc0d`와 text 정책상 일치하고 해당 PR CI가 성공한 것을 확인했다.
검사한 local Core main `90e4ca8bef9ff18733af5d44db41b5153e46e466`의 `verify-contract-sync.ps1 -IncludeWeb`은
Reprocessing snapshot 누락으로 exit1이었다. snapshot 적용·새 E2E는 수행하지 않았다.
이 고정 checkout 검사와 현재 모든 remote main/후속 배포·업무 수용을 구분한다.
이 새 문서도 Context merge 뒤 자동 snapshot PR의 최신 source SHA·변경 범위를 확인한다. 열린 PR이 최신 Context로 갱신될 수 있다.
snapshot apply/merge·E2E를 대신 수행하지 않는다. E2E 전 sync, Web 포함 시 -IncludeWeb을 요구한다.
이번은 Markdown/source 리뷰다. source inventory·본문/링크 검사는 schema consumer round-trip·hash vector·HTTP/JWT·DB/장애·운영 수용 검증이 아니다.
