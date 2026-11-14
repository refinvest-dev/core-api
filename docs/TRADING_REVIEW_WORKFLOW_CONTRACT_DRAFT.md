# Workflow 재처리 접수·산출물 계약 후보 (DRAFT)

상태: **ADR-079 검토 초안·미채택·runtime 미구현**. Go Workflow WF-G01/02의 제품 계약 공백을 다룬다.
문서 merge/CI는 정책 채택이나 구현 전환이 아니다. 현재 OpenAPI·hash/vector·상태/ACK는 유지한다.

## 읽는 순서와 소유권

1. 이 문서: WP01 산출물 선택·WP02 접수 실패의 영속 기록과 HTTP 의미.
2. [identity·child 실행](TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md): WP03 replay/retry·WP04 child binding.
3. [consumer·수용·전파](TRADING_REVIEW_WORKFLOW_CONTRACT_ACCEPTANCE_DRAFT.md): 고정 source·WR-G01~08·미실행 계획.

현재 정본은 [Versioning §§6~12](TRADING_VERSIONING.md)·[API §8](TRADING_REVIEW_API.md)·
[Persistence §§5/7](TRADING_REVIEW_PERSISTENCE.md)·[Acceptance](TRADING_REVIEW_ACCEPTANCE.md)·
[public OpenAPI](../openapi/core-api.yaml)·[Compute OpenAPI](../openapi/compute-api.yaml)다.
[ADR-072 서비스 경계](TRADING_REVIEW_SERVICE_BOUNDARY.md)에 따라 Record는 DeletionRequest/ReprocessingRun·
Guard/Generation/Tombstone·Book pointers, Analysis는 자기 Run·private 준비/정리 checkpoint를 소유한다.
Workflow는 이를 조율하는 Application이며 자체 Aggregate·독립 진행 상태/table·engine을 갖지 않는다.
Go의 C/Q/Result·Store/Reader·Tx-bound capability/물리 저장은 Go 저장소가 소유한다.

| 후보 | 우선 권장 | 대안과 비용 |
|---|---|---|
| WP01 | 향후 versioned request에 명시 산출물 선택, full binding에 고정 | config 유무로 추론하면 기존 null/default 요청의 실행 의미를 증명해야 함 |
| WP02 | 권한·source identity를 확인한 business admission 실패는 durable FAILED와 고정 오류 receipt; 현재 동기 410/422 보존 | 모두 202/terminal로 바꾸면 현재 API §8과 consumer 오류 경로의 명시 호환 변경 필요 |
| WP03 | 같은 public key는 원 receipt/Run, 명시 retry는 새 key+실패 parent 참조·같은 logical key/새 Run/attempt | 새 key만으로 retry하면 응답 유실 복구와 사용자 재실행 의도가 섞임 |
| WP04 | 원 accepted Session을 보존하는 별도 child 실행 binding/profile | 일반 validation 경로 재사용은 Session 전이·active uniqueness·공개 side effect와 충돌 |

## WP01 — 요청 산출물과 publication 의도

Versioning §7은 새 Revision과 **요청 시** 새 Analysis를 규정하지만 현재 RequestTradingReprocessingRequest는
source/type/target/reason과 optional sourceAnalysisRunId/analysisConfig·publishAsLatest만 갖는다.
optional config·VersionSet component·parent Analysis 존재는 산출물 요청 자체의 권위가 될 수 없다.

아래 이름은 **semantic 선택지**이며 새 enum/JSON 필드를 현재 wire에 추가한 것이 아니다.

| type | 허용할 산출물 후보 | 접수에 pin할 의미 |
|---|---|---|
| REPARSE_SOURCE | Ledger-only 또는 Ledger+Analysis | 유효 raw/metadata·exact target·새 R2; Analysis 요청이면 그 R2의 A2 |
| REBUILD_LEDGER / RERUN_RECONSTRUCTION | Ledger-only 또는 Ledger+Analysis | canonical/terms 또는 등록 migration·새 R2; 선택한 A2 |
| RERUN_ANALYTICS | Analysis-only | retained source Revision의 새 A2; 새 Revision 생성 없음 |
| REEVALUATE_FINDINGS | Analysis-only | compatible source Metric/evidence/config·새 A2; full analytics로 자동 승격 없음 |

Analysis를 요청하면 완전한 canonical config와 exact Analytics target을 고정한다. 생략/null을 최신 config로
조용히 채우거나 sourceAnalysisRunId가 있다는 이유로 source config를 복사하지 않는다. Ledger-only에서는
analysisConfig를 받지 않는 안을 권장하며 기존 target tuple 전체를 임의 삭제/재hash하지 않는다.
parent Analysis는 diff/lineage source이고 Analysis 출력 요청과 구분한다. Finding 재평가의 parent/result/Metric 요구와
같은 Revision으로 계산하는 허용 경로는 exact compatibility 및 실제 Compute 지원을 각각 확인한다.

requested outputs·정확 source IDs·원 resolved target/config·publishAsLatest를 immutable full binding으로 비교한다.
기존 logical SHA root에 outputs/publish 필드를 몰래 넣지 않는다. 같은 legacy logical key에 다른 binding이면
REPROCESSING_CONFLICT를 우선 권장한다. 따라서 다른 출력 조합의 유효 요청도 막힐 수 있다. 이를 허용해야 하면
별도 logical identity version·alias/uniqueness/성공 receipt 이관을 먼저 채택한다.

publishAsLatest 생략은 현재 true default를 유지한다. false는 결과 공개 후 기존 pair 유지다. true도
Ledger-only의 기존 A1 해제/유지나 동시 writer 승자 규칙을 정하지 않는다. WF-G04가 열려 있으므로
Ledger-only latest의 활성화를 이 후보만으로 허용하지 않는다. R2+A2는 모두 준비한 뒤 단일 Tx에서 공개한다.
실패·취소는 R1/A1을 보존한다. historical 결과 공개와 latest 선택은 별도 의미다.

신규 versioned request는 selector를 명시하도록 하는 안을 권장한다. 현재 request를 자동 변환하는 default는
기존 consumer의 실제 산출물 의도/요청 이력을 확인한 뒤 별도로 정한다. 증거 없는 legacy 요청은 새 profile로
활성화하지 않으며 기존 payload/receipt를 새 의미로 재해석하지 않는다.

## WP02 — 실패 Run과 즉시 HTTP 거절의 경계

현재 API §8은 raw 없는 reparse를 동기 **410 REPROCESSING_SOURCE_UNAVAILABLE**, 즉시 판정 가능한
registry/compatibility를 422로 거절한다. 반면 상태 표 §3.3은 Run 부재도 허용하고,
TR-ACC-DELETE-005·REPROCESS-002/008은 **failed ReprocessingRun만** 남기도록 요구한다.
둘 중 하나를 구현 편의로 생략하지 않는다. 우선 후보는 **HTTP 거절과 durable failure 기록을 함께** 하는 것이다.

| 상황 | 후보 저장/응답 경계 |
|---|---|
| malformed schema/key·인증/위임 실패·foreign/현재 삭제된 Book 또는 source 접근 거절 | 새 Run/Dispatch 없음. 현재 operation 오류·존재 숨김/삭제 계약 유지 |
| 접근 가능한 retained Revision을 지정했고 raw만 삭제/TTL 만료 | 현재 Guard 아래 실패 Run·원 request mapping·410 오류 receipt를 같은 Tx에 기록. Compute/새 Revision/Analysis 없음 |
| source/target identity를 고정할 수 있으나 version unavailable/incompatible | 실패 Run·정확 target/registry snapshot·422 오류 receipt 원자 기록. nearest fallback 없음 |
| 정상 접수 후 입력 준비/실행에서 발견한 실패 | 접수 202 뒤 전용 FinishFailure로 terminal FAILED. 요청 당시 receipt와 현재 Run 상태는 구분 |
| registry 일시 통신 장애·DB commit UNKNOWN | source/version 불가로 확정하지 않음. 현재 503/복구 규칙; durable 실패나 새 attempt를 추정 생성하지 않음 |

business 실패를 저장하려면 owner/Book·정확 source·target 요청·logical claim·실패 code를 증명할 수 있어야 한다.
임의 sourceVersionSet/hash·소실된 lineage/FK를 default로 만들어 실패 Run을 채우지 않는다. source tuple 자체가
확정 불가한 요청의 거절/기록 범위는 schema/legacy gate다. Raw-only는 retained Ledger/Analysis 접근을 차단하지 않는다.
Book/Account/해당 Session 삭제로 source 접근 자체가 차단된 경우와 raw bytes만 없는 경우를 분리한다.

실패 기록과 오류 receipt가 commit되기 전에는 확정된 business rejection을 반환하지 않는다. commit UNKNOWN이면
원 key/claim/receipt로 조정하며 SELECT miss 하나로 rollback을 추정하지 않는다. 접수 전 실패에는 Dispatch를 만들지 않는다.
정상 admission은 Run/Dispatch/mapping이 원자적이고 일반 Update가 이 경계를 대신하지 못한다.

같은 key+같은 의미는 최초 응답 종류/status/code·원 Run mapping으로 복구한다. 410으로 기록한 요청을 replay에서
202로 바꾸거나, 최초 202 뒤 FAILED가 됐다고 원 접수 receipt를 422로 바꾸지 않는다. 현재 권한/Guard 검사는 먼저다.
현재 StructuredFailure/ErrorResponse에 실패 Run 연결을 새로 발명하지 않는다. 직접 Run ID를 알리는 방식은
versioned 오류/문서화된 header 등 consumer 계약의 채택 조건이다. 목록만으로 정확 request를 찾을 수 있다고 가정하지 않는다.
202에 FAILED Run을 반환하는 대안은 가능하지만 active API §8 변경·상태 표/Then/consumer 전환을 함께 승인해야 한다.

실패 Run은 불변 audit이며 조회가 재실행을 유발하지 않는다. retry는 [WP03](TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md#wp03--replay와-explicit-retry)을 따른다.


## 원 admission 의미 후속

[③ receipt 논의안](TRADING_REVIEW_REPROCESSING_RECEIPT_POLICY_DRAFT.md)은 WP02/03의 원 접수 종류·현재 상태·key별 이력을 구체화한다.
정상/기록 rejection의 같은 Tx·commit 확인 후 응답·UNKNOWN 원 fence 복구, mapping TTL 경과/삭제 시 비노출을 구분한다.
다른 key의 동일FAILED 수렴에서 그 Run의 검증된 원 admission 종류를 계승하는 미채택 권장안을 포함한다.
이 문서/①~③의 병합을 정책 채택·schema 변경·업무 구현·보관 예외 승인으로 해석하지 않는다.
