# Analysis 접수·운영 계약 리뷰 (DRAFT)

상태: **ADR-078 검토 초안·미채택·runtime 미구현**. 현재 public/Compute OpenAPI·hash/vector·상태/ACK를 유지한다.
Go AUC-01~10의 AP01~06을 같은 ID로 정리한다. 문서 merge/CI 성공은 정책 채택·구현·route 활성화가 아니다.

## 읽는 순서와 책임

1. 이 문서: AP01 admission/replay와 AP05 취소·AP06 ingress/오류/페이지.
2. [응답 리뷰](TRADING_REVIEW_ANALYSIS_RESPONSE_DRAFT.md): AP02 Review·AP03 typed Metric/ID/hash.
3. [selection 리뷰](TRADING_REVIEW_ANALYSIS_SELECTION_DRAFT.md): AP04 Unit/membership/근거·LP05 연결.
4. [consumer·수용/전파](TRADING_REVIEW_ANALYSIS_CONTRACT_ACCEPTANCE_DRAFT.md): 고정 source·AR-G01~08·16개 미실행 계획.

현재 정본은 [API](TRADING_REVIEW_API.md)·[public schema](../openapi/core-api.yaml), [Domain](DOMAIN.md),
[Persistence](TRADING_REVIEW_PERSISTENCE.md)·[Lifecycle](TRADING_DATA_LIFECYCLE.md)·[Versioning](TRADING_VERSIONING.md),
[Analytics protocol](TRADING_ANALYTICS_JOB_PROTOCOL.md)·[input identity](TRADING_ANALYTICS_INPUT_IDENTITY.md)·
[integrity](TRADING_ANALYTICS_RESULT_INTEGRITY.md)·[delivery](TRADING_ANALYTICS_RESULT_DELIVERY.md)다.
Go의 package/소비자 Port·실제 Tx/Store·입력 준비 구현은 Go 소유다. Module Facade를 RPC로 바꾸지 않는다.

| ID | 우선 후보 | 대안/비용 | 채택 전 |
|---|---|---|---|
| AP01 | 최초 resolved pin으로 same-key 의미 비교·logical 충돌의 exact source/publish binding 검증 | source를 포함한 versioned 새 logical key는 별도 mapping/인계 필요 | key projection·legacy receipt·failed 재접수·삭제 scope |
| AP02 | versioned Review의 per-Metric count 권위·완전 source quality·Go publication stamp | 새 공통 모집단 통계는 별도 계산 정의/version 필요 | 기존 응답 이력·legacy·decoder/크기 |
| AP03 | 원 typed Metric을 보존하는 versioned read wrapper·Metric/Analysis hash 분리 | generic primary만 유지하려면 모든 세부 근거 소비 경로 필요 | typed null/단위·hash oracle·public legacy 의미 |
| AP04 | result-bound selection·7종 role·classification absent 구분·독립 direct evidence | source manifest 전체 기본 목록·legacy 4role는 별도 의미/호환 설계 | page/total/order·LP05·decoder/팬아웃 |
| AP05 | 현재 public 8개 유지·내부 삭제/재처리 취소 차단 우선 | 사용자 Run cancel은 새 상태/오류/멱등/수명 설계 필요 | 공개 Run 4상태와 terminal mapping |
| AP06 | 별도 Analysis ingress·operation별 오류·명시 페이지/결과 수명 | 기존 profile 확대·오류 일괄 404는 거부 | authority/commit·proxy/SQL/운영 증거 |

## AP01 — same-key request 의미

우선 후보는 다음 비교 규칙이다. 아래는 **semantic equality 표**이며 신규 hash bytes/wire schema가 아니다.

| 입력/경합 | 후보 판정 |
|---|---|
| analysisConfig | 현재 required 전체 canonical 값·정확한 hash 비교. 생략된 threshold/default를 Restore에서 채우지 않음 |
| analyticsVersionSet omitted/null | 새 접수만 exact current를 resolve/pin. known key면 최초 resolved tuple 사용 |
| same key의 명시 VersionSet | 최초 tuple과 exact 같으면 omitted/null과 같은 실행 의미, 다르면 conflict |
| 원 명시 tuple 뒤 same key의 omitted/null | 최초 tuple에 bind. 오늘 current로 바꾸지 않음. 최초 선택 방식은 audit로 보존 |
| publishAsLatest omitted/true | 동일 의도. 명시 false는 다른 의도이며 같은 key 재사용이면 conflict |
| 같은 key의 원 Run이 terminal FAILED | 원 Run/status replay. 자동 새 Run·Job·attempt 생성 없음 |
| 현재 접근/삭제/authority 변경 | replay도 현재 접근 규칙 확인. 원 pin은 접근 허용 근거가 아님 |

owner+operation+key namespace와 exact Revision/config/version/publish 의도를 결합한다. 입력 ID는 opaque string이고
신규 Aggregate Snowflake·legacy UUID/참조는 원대로 유지한다. 새 key라고 현재 다른 Revision의 Run을 재라벨하지 않는다.
legacy key retention 뒤 mapping 부재는 미접수 증거가 아니다. logical/영속 receipt 복구 없이 중복 실행하지 않는다.

### logical uniqueness와 source binding

현재 API의 analyticsVersionSetHash와 Persistence 예시의 metricDefinitionVersionSetHash는 표기가 다르다.
우선 후보는 현재 등록된 logical hash 규칙/원 mapping을 보존하면서 **별도의 full binding equality**를 요구하는 것이다.
full binding은 owner/Account/Book·정확 RevisionID·ledger/manifest hash·전체 exact AnalyticsVersionSet·Config/hash·
승인된 삭제 scope proof·최초 publication 의도를 포함한다. 부분 Metric version hash만 같다고 같은 계산/실행이라고 하지 않는다.
registry가 승인한 versionSetHash와 전체 tuple을 확인하되 versionSetHash를 포함한 객체를 다시 hash해 새 의미로 사용하지 않는다.
Compute analysisInputHash는 RevisionID·두 artifact reference/bytes/hash까지 포함하는 다른 계약이다.

같은 legacy logical hash인데 source/tuple/publish 의도가 다르면 기존 Run 재사용/포인터 변경 대신 명시 conflict를 우선 권장한다.
이는 서로 다른 Revision의 유효한 새 요청도 막을 수 있는 보수적 선택이다. 허용해야 할 실제 사례가 있으면
source를 포함한 새 logical identity version·legacy alias/유일성/receipt 이관을 별도 채택한다. 새 필드를 기존 SHA root에 몰래 추가하지 않는다.
정확한 ownerScope·VersionSet hash projection·scope별 generation/잠금은 AR-G03 채택 조건이며 아직 확정된 byte contract가 아니다.
Raw-only의 현재 generation을 Book 전체 무효화로 해석하거나 불변 sourceDeletionGeneration을 현재 권위로 사용하지 않는다.

새 key의 **명시 사용자 요청**이고 같은 full source/calculation binding의 기존 Run이 FAILED이며 active/success가 없을 때는
새 Run/Job·원 실패 lineage로 재접수하는 안을 우선 검토한다. 기존 Run/Job은 불변이고 Compute 자동 attempt retry와 다르다.
기존 성공/active가 있으면 동일 binding·publish 의도에서만 Existing으로 수렴한다. publish 의도 변경은 새 포인터 부작용 대신 conflict/별도 전환 계약이다.
재접수에는 기존 Go dispatch/발행 fence와 남은 Compute 실행·late payload disposition/정리 책임도 확인해야 한다.
local FAILED는 원격 실행 종료 증거가 아니며 terminal Run이라는 사실만으로 Analytics DISCARDED_STALE를 보내지 않는다.
현재 stale ACK 원인은 과거 attempt/증가한 삭제 generation이다. 그 밖의 late result는 승인된 차단·보관/expiry/정리 정책을 별도 명세한다.
실제 실패 lineage 표현·quota·idempotency retention·public 오류 code는 schema/consumer 단계에서 확정한다. 새 retry/cancel endpoint는 만들지 않는다.

### durable admission과 입력 준비

V2 publication eligibility proof가 없으면 현재 422 ANALYTICS_INPUT_UNAVAILABLE이며 Run/artifact/dispatch 이전에 거절한다.
zero-unit V2와 supplement 부재를 구분한다. 이후 Go의 우선안은 짧은 PENDING/QUEUED+Dispatch 접수 뒤
Worker의 Tx 밖 두 artifact 준비→current Guard/claim 아래 once-only full input binding→Compute create다.
public 4상태/progress enum은 유지하며 input binding absent는 명시 preparation checkpoint에만 허용한다.
RUNNING/공개 전 완전 binding이 필요하고 bound Run의 artifact/hash를 재시작 때 바꾸지 않는다.
이 단계 분리는 Go 설계안이며 Compute의 full create pin·원 create/update key/fingerprint/ACK를 바꾸지 않는다.
입력 준비 retry/lease·실행/cleanup budget은 별도 미확정 gate다. Job 생성부터의 Compute 24h cutoff는 Job 이전 준비를 제한하지 않는다.
ADR-071 hard deadline/ADR-073 판단 창을 자동 채택하거나 무제한 재시작/expiry 연장을 허용하지 않는다.

## AP05 — 취소 범위

현재 slice는 POST 1·GET 7을 유지한다. 사용자 Run cancel endpoint·public CANCELLED는 이번 후보의 채택 범위에 넣지 않는 방향이다.
Record/Workflow의 삭제·비공개 R2/A2 취소에는 현재 scope/attempt fence·durable intent·Compute cancel·실제 정리 checkpoint가 필요하다.
취소 접수나 TTL 도래는 purge 완료가 아니고 browser abort/HTTP timeout은 이미 접수된 작업의 취소가 아니다.
발행이 먼저면 정상 결과를 유지하고 차단이 먼저면 늦은 공개를 막는 현재 원자성 원칙을 지킨다.

Book 삭제 뒤 일반 조회 차단과 비공개 A2의 취소는 public Run terminal enum 확장과 다르다.
정상 접근 가능한 Run의 예상 밖 Compute CANCELLED·명시 취소 intent와의 terminal projection은 아직 미확정이다.
이를 임의 FAILED/REPROCESSING_CANCELLED code나 계속 RUNNING인 정상 상태로 숨기지 않는다. 해당 런타임 활성화 전 safe failure/재접수·late payload/ACK disposition 의미를 채택해야 한다.
사용자 취소를 추가하는 대안은 versioned status/오류·멱등·경합·retained 결과·삭제와의 관계를 별도 설계한다.

## AP06 — ingress·오류·목록/수명

별도 Analysis service AND 서명 사용자 위임·operation read/admit scope·Member authority·owner/Record Guard를 요구한다.
기존 Account/Book·Import audience를 자동 승격하지 않고 raw 사용자 header/body ownerId를 신뢰하지 않는다.
정확한 path/profile/header/TTL/key 운영·route registry는 후속 wire 설계다. Core/Gateway는 사용자/서비스 경계를 구분해 중계한다.
browser 인증 실패와 upstream 서비스 credential/위임 설정 실패를 같은 401/logout으로 취급하지 않는 방향을 권장한다.
전자는 현재 브라우저 인증 정책, 후자는 안전한 upstream 의존성 오류/운영 진단으로 분리하고 실제 relay status/code를 명세한다.
Core Member 차단과 Go commit 선형화는 미확정이며 DB Tx/Guard만으로 서비스 간 철회를 원자화했다고 주장하지 않는다.

후속 response 정합화는 GET의 invalid path/page→400, owned tombstone→410(목록 포함), authority/dependency unavailable→503,
저장/참조 integrity 이상→안전한 내부 오류를 우선 비교한다. foreign/absent는 404·완료 전 result는 409 의미를 보존한다.
Member deny/403·service 인증 중계·새 5xx와 safe code/message/retryable/trace의 정확한 operation별 표는 별도 schema gate다.
현재 operation에 없는 status를 구현에서 임의 추가하지 않고 DB 오류를 404/빈 page로 숨기지 않는다.

Run list는 createdAt DESC·opaque ID binary ASC tie-break, page=0/size=20/max=100의 기존 parameter 한도를 우선 유지한다.
items는 full Run union이며 한 응답의 parent/items/total snapshot을 맞춘다. 페이지 간 동결/cursor·새 cap/GET 413은 채택하지 않는다.
retained Go result는 Compute terminal/ACK TTL과 독립적으로 현재 Go lifecycle에 따라 조회한다. raw-only는 Ledger/Analysis를 보존한다.
safe trace는 원 요청/비동기 Worker를 연결하되 PII/row/locator/token·private evidence 전체 hash를 복제하지 않는다.
