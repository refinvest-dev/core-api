# Workflow HTTP 경계·operation registry 후보

상태: **ADR-079/WP01~04의 미채택 문서 후보**, 2026-11-13. 현재 public/Compute OpenAPI·hash/vector·업무 runtime은 변경하지 않는다.
새 path/operation/audience/scope literal은 검토용이며 route/issuer/권한을 활성화하지 않는다.
[DTO 후보](TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md)·[WP01/02](TRADING_REVIEW_WORKFLOW_CONTRACT_DRAFT.md)·
[WP03/04](TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md)·[WR gate](TRADING_REVIEW_WORKFLOW_CONTRACT_ACCEPTANCE_DRAFT.md)를 함께 읽는다.
최초 단계는 의미/DTO·registry 문서 리뷰다. 후속 [OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)에
public/internal YAML·완전한 synthetic 예제·정적 검사를 연결했다. route/issuer·생성 client 지원은 여전히 미검증이다.

## 기존 operation의 소유권

Workflow는 Go 내부 Application 조율자다. 기존 DeleteAccount/Book/Session/Artifact를 중복 public/internal Workflow endpoint로 만들지 않는다.
Member cascade/operator remediation·child prepare/publish/ACK/cleanup은 별도 권위/내부 실행이며 이번 public API에 노출하지 않는다.

| 기존 public operation | public 경로 | 내부 초안 소유/경로·profile |
|---|---|---|
| deleteTradingAccount | DELETE `/trading-accounts/{tradingAccountId}` | Account/Book `deleteOwnerTradingAccount`: DELETE `/internal/trading-review/v1/accounts/{accountId}` |
| deleteTradingBook | DELETE `/trading-books/{tradingBookId}` | Account/Book `deleteOwnerTradingBook`: DELETE `/internal/trading-review/v1/books/{bookId}` |
| deleteTradingImportSession | DELETE `/trading-import-sessions/{importSessionId}` | Import `deleteOwnerTradingImportSession`: DELETE `/internal/trading-review/v1/import-sessions/{sessionId}` |
| deleteTradingSourceArtifact | DELETE `/trading-source-artifacts/{artifactId}` | Import `deleteOwnerTradingSourceArtifact`: DELETE `/internal/trading-review/v1/source-artifacts/{artifactId}` |
| getTradingDeletionRequest | GET `/trading-deletion-requests/{deletionRequestId}` | Account/Book `getOwnerTradingDeletionRequest`: GET `/internal/trading-review/v1/deletion-requests/{deletionRequestId}` |

정확 method/path/operation·key namespace·audience/scope는 기존
[Account/Book](../openapi/drafts/trading-review-account-book.yaml)·[Import](../openapi/drafts/trading-review-import.yaml) 초안이 소유한다.
양쪽은 여전히 미채택이다. 구현은 내부 WUC-01~04로 연결할 수 있지만 registry를 Workflow 이름으로 복제/자동 확대하지 않는다.
Import 삭제 뒤 상태 조회는 Account/Book의 별도 위임이 필요하다. import audience 토큰을 shared deletion status에 그대로 재사용하지 않는다.
삭제 DELETE는 원 operation/key로 202·현재 요청 View를 반환하는 기존 후보, status는 200이다. raw-only는 retained Ledger/Analysis를 보존한다.

## 새 재처리 registry 권장 후보

현재 public 4개는 [core-api.yaml](../openapi/core-api.yaml)에 유지한다. outputs/retry/error Run 연결의 호환성 차이는 별도 versioned public 후보로 검토한다.
아래 public prefix는 `/trading-review/v2`, 내부 prefix는 `/internal/trading-review/v1`이다. suffix와 operationId도 아직 미채택이다.

| method / suffix | public operationId 후보 | internal operationId 후보 | public idempotency namespace | 성공 |
|---|---|---|---|---|
| POST `/trading-books/{tradingBookId}/reprocessing-runs` | requestTradingReprocessingV2Draft | admitOwnerTradingReprocessing | requestTradingReprocessing | 202/Run 후보 |
| GET `/trading-books/{tradingBookId}/reprocessing-runs` | listTradingReprocessingRunsV2Draft | listOwnerTradingReprocessingRuns | 없음 | 200/Page 후보 |
| GET `/trading-reprocessing-runs/{reprocessingRunId}` | getTradingReprocessingRunV2Draft | getOwnerTradingReprocessingRun | 없음 | 200/Run 후보 |
| POST `/trading-reprocessing-runs/{reprocessingRunId}/cancellation` | cancelTradingReprocessingV2Draft | cancelOwnerTradingReprocessing | cancelTradingReprocessing | 202/현재 terminal 포함 |

별도 public operationId가 생겨도 namespace를 새로 만들어 이전 key 충돌을 우회하지 않는다. 기존 request의 outputs/receipt 의미를
확정할 수 없으면 legacy mapping/consumer 리뷰 대상이며 같은 key를 새 Run에 재결속하거나 v1 payload를 무조건 v2로 변환하지 않는다.
cancel은 body 없는 기존 의미를 유지하는 후보며 `{}`/임의 retry/target body를 새 명령으로 받아들이지 않는다. 허용 decoder는 schema 단계에서 명시한다.
GET은 계산/접수/claim·repair/pointer 변경을 수행하지 않는다. stage/private child는 정상 조회에서 숨긴다.

## 서비스 인증과 사용자 위임

| 경계 | 권장 profile/검증 |
|---|---|
| 브라우저→Core | 기존 정상 인증·CSRF(write)·세션 정책. 이 문서는 JWT를 browser가 Go에 직접 보내는 경로를 열지 않음 |
| Core/향후 Gateway→Go 재처리 | TLS·service credential **AND** 서명 사용자 위임. audience 후보 `refinvest:trading-review:reprocessing:v1` |
| 재처리 operation class | POST 접수 `trading-review:reprocessing:admit`, GET `trading-review:reprocessing:read`, POST 취소 `trading-review:reprocessing:cancel` 후보 |
| 삭제/삭제 상태 | 기존 Account/Book·Import의 exact registered profile. 새 reprocessing audience로 삭제 control을 허용하지 않음 |
| Worker/operator/Member cascade | durable intent·현재 control/authority의 별도 검증. 사용자 위임/JWT나 body flag를 운영 권한으로 재사용하지 않음 |

위임 header 후보는 기존 `X-RefInvest-User-Delegation`, service credential은 기존 채택할 내부 transport profile을 따른다.
RS256/`refinvest-user-delegation+jwt`·신뢰 issuer/key registry·canonical positive decimal Member sub·act.sub와 인증 caller 결속·
operation의 exact audience/scope·현재 Member authority를 검증한다. wildcard/admin reason·원 audience 자동 승격은 없다.
method+route+audience+scope+caller allowlist registry가 필요하다. 새 issuer/키·TTL/skew/size·rotation 수치는 여기서 선택하지 않는다.
Core 인증/위임 확인은 Go의 Account→Book→source/Run 소속·현재 Guard를 대신하지 않는다. sourceAnalysisRun도 같은 owner/Book/source 관계를 검증한다.
Core Member 차단과 Go commit 선형화·Worker authority·실제 issuer 운영은 WR-G07/08의 미확정 조건이다.

## request timeout·receipt·cancel

접수는 짧은 Tx에서 durable pin/Run/Dispatch 또는 채택 시 WP02 실패 receipt를 저장한 뒤 응답한다.
Compute/Storage 장기 처리를 원 HTTP에 매달지 않는다. response 유실/브라우저 abort·사용자 JWT 만료는 이미 접수한 작업의 취소가 아니다.
같은 key·같은 의미는 현재 operation 접근 승인 뒤 원 admission 의미/Run으로 복구한다. 원 202 뒤 FAILED를 이유로 422로 재응답하지 않는다.
원 410/422 recorded rejection도 승인된 접근 아래 원 오류 의미/Run을 재현한다. 현재 권한/Guard 거절은 replay보다 먼저 적용하며 과거 receipt로 권한을 연장하지 않는다.
cancel 202는 내부 publication CAS decision/현재 Run 응답이고 Compute 프로세스 종료나 purge 완료가 아니다.
COMPLETED/FAILED/CANCELLED가 먼저면 기존 terminal을 반환한다. 취소가 먼저면 child 공개 차단·cleanup 참조를 함께 저장한다.
public request key·logical hash·Compute create/grant/ACK key를 분리하고 remote cancellation의 HTTP status를 browser에 복사하지 않는다.

## 오류·중계·trace·캐시

strict input은 400, 인증/위임·권한은 각 경계의 안전한 401/403, foreign/부재는 존재 숨김 404,
owned 삭제/확정 source 부재는 해당 410, key/full binding 경쟁은 409, 확정 version/compatibility는 422로 대조한다.
500/503/중계 timeout을 404·빈 목록·확정 source 실패로 바꾸지 않는다. 정확 code/status와 504 중계 선택은 OpenAPI/consumer 단계의 gate다.
내부 service/delegation 거절을 browser 세션 만료와 동일시해 무조건 logout하지 않는다. Core는 allowlist에 따라 safe upstream 오류로 변환한다.
UNKNOWN의 retryable은 같은 key/fence 복구 의미이지 새 key/새 Run 허가가 아니다. DB commit UNKNOWN을 확정 rejection으로 응답하지 않는다.

traceparent/tracestate는 검증·제한해 request/서비스 hop으로 전달한다. ErrorResponse.traceId는 이번 요청의 안전한 correlation이다.
원 receipt의 업무 의미를 replay해도 traceId/현재 상태/전송 header까지 원 bytes로 고정하는 것은 아니다. 고정 body 재전송이 필요한 Compute ACK와 다르다.
raw value/filename·locator/signed URL/query·키/JWT·금융 payload·driver 원인은 error/log/metric에 넣지 않는다.
민감 Run/status/오류는 no-store 후보로 두고 인증/위임 identity가 cache key에서 섞이지 않게 한다. 정확 Vary/relay header는 schema 단계에서 고정한다.
public Location을 제공한다면 브라우저용 versioned status URL이어야 한다. internal path/signed URL을 그대로 전달하지 않는다. 이번에 Location 의무를 새로 채택하지 않는다.

## 후속 검증·전파

이번 문서의 operation/field literal은 schema/issuer/SQL/route 구현 증거가 아니다. 기존 WR-DRAFT-01~14와 TR-ACC/Go 계획을 이어 사용한다.
후속 [public/internal OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)에 위 registry/DTO의 schema·정적 예제/위험 변이를 표현했다.
새 YAML을 추가하기 **전에** integration exact file 분류/gate·Core snapshot 의존을 준비한다. unclassified 파일로 preview가 실패하는 상태를 성공 처리하지 않는다.
기존 contract 문서 snapshot은 Context merge 후 별도 전파한다. Go는 고정 source 링크를 사용하며 snapshot 체계 구축을 주장하지 않는다.
정책 채택·실제 HTTP/JWT/authority·DB/동시성·child/private 수명·Analytics actual absence·단일 writer/운영 인계는 별도다.


## HTTP/DTO 문서 단계 검사 (당시)

변경 Markdown 7개·상대 링크 77개·anchor 1개·고정 source 경로 16개를 확인했다.
기존 삭제 5개 registry의 public/internal method/path/operation 일치·JSON 예시 5개 문법/중복 key 검사,
기존 Markdown 회귀 3개와 Analytics 정적 계약 검사(local Markdown links 312 resolved)를 통과했다.
사용자/환경 파일 20개 hash 보존도 확인했다. 이 결과는 새 schema/HTTP/JWT/authority·업무/운영 수용 증거가 아니다.
