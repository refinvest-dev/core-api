# Account·Book API 필드·중계 설계 (DRAFT)

상태: **검토 초안·미채택·미구현**. 현재 public/Compute OpenAPI, ADR-072와 ADR-074의 두 GET draft를
변경하지 않는다. 이 문서는 기존 schema와 Core 구현의 대조 및 후속 내부 계약 후보이며 runtime 활성화 근거가 아니다.
검토일 2026-11-09, context `8aaebfd638bec8c6463250436fe41dd1acb42865`, Core `38a804392af4f83f748da204f0265c4f8fc60215`, Go `4cf4eac1e067f6e0a028392a5e899b5a53032a75` 기준이다.

## 경계와 소유권

```text
Browser API Request
  → Core: 사용자 인증/CSRF·형식 검증·허용 header 재구성
  → 내부 HTTP Request + 서비스 인증/사용자 위임
  → Go inbound: wire 검증 → Command/Query
  → Use Case: 현재 owner/parent/Guard·멱등/Tx
  → Result → 내부 HTTP Response
  → Core: 허용된 public Response/ErrorResponse
```

제품 wire는 context가 소유하고 Go 계층/구체 mapper는 Go 저장소가 소유한다. Request/Response, C/Q/Result,
Domain Snapshot, DB row는 별도 책임이다. transport shape가 같으면 승인 schema를 재사용할 수 있지만
이를 이유로 Go Application이 JSON/HTTP DTO를 의존하게 만들지 않는다.
초기 Core는 중계하고 기존 Trading Review 쓰기/Worker는 비활성화한다. 같은 물리 DB를 공유하더라도
Member/Strategy/Backtest·Compute 테이블은 Go가 소유하지 않는다. Gateway 전환 시 caller 등록/위임 발급 연계는 별도다.

## 현재 public operation과 응답 선언

다음 error 목록은 현재 OpenAPI path에 **명시된 값**이다. 전체 실제 오류를 보장하는 표가 아니며
생략된 400/410/500/503 등을 이 문서에서 새로 채택한 것이 아니다.

| operationId | public 요청 | 성공 schema/status | 현재 명시된 error status |
|---|---|---|---|
| createTradingAccount | POST /trading-accounts | TradingAccount / 201 | 400,401,403,409 |
| getTradingAccount | GET /trading-accounts/{tradingAccountId} | TradingAccount / 200 | 401,404,410 |
| listTradingAccounts | GET /trading-accounts | TradingAccountPage / 200 | 401 |
| deleteTradingAccount | DELETE /trading-accounts/{tradingAccountId} | DeletionRequest / 202 | 400,401,403,404,409 |
| createTradingBook | POST /trading-accounts/{tradingAccountId}/books | TradingBook / 201 | 400,401,403,404,409,422 |
| getTradingBook | GET /trading-books/{tradingBookId} | TradingBook / 200 | 401,404,410 |
| listTradingBooks | GET /trading-accounts/{tradingAccountId}/books | TradingBookPage / 200 | 401,404 |
| deleteTradingBook | DELETE /trading-books/{tradingBookId} | DeletionRequest / 202 | 400,401,403,404,409 |

생성 replay가 삭제된 Account/Book을 만나는 경우, Book 생성/목록의 삭제 parent, 삭제 command의 삭제 상태
검사는 Core에서 410을 반환할 수 있지만 해당 operation 목록에는 빠져 있다. invalid page의 400·일시 의존 장애도
전역 의미와 path별 선언을 대조해야 한다. **권장안은 기존 의미를 유지하며 실제 가능한 응답을 operation별로
명시하는 것**이다. OpenAPI 수정/호환성 검토는 후속 채택 PR에서 한다. 신규 서버에서 임의 status를 숨기거나
모든 410을 404로 바꾸는 대체안은 적용하지 않는다.

## 요청 필드 → Command/Query

R은 required/non-null, O는 optional이다. public schema의 min/max와 Domain의 추가 의미 검증을 구분한다.

| public 출처·필드 | 현재 wire/제약 | Go 입력 의미·변환 |
|---|---|---|
| 검증된 사용자 | body/query ownerId 없음 | 검증 경로에서만 actor/owner scope 공급. exported struct/숫자는 인증 증명이 아님 |
| path tradingAccountId | R string, public opaque ID | AccountID. 현재 bigint profile은 canonical positive decimal int64를 무손실 검사. 새 public regex를 조용히 추가하지 않음 |
| path tradingBookId | R string, public opaque ID | BookID. parent는 DB 관계에서 해석하며 임의 client parent와 결합하지 않음 |
| CreateAccount.venue | R TradingVenue=BINANCE | Venue. enum 의미 보존, 대소문자 자동 보정 없음 |
| CreateAccount.displayName | R string, minLength 1/maxLength 100 | DisplayName 원문. trim/Unicode normalize 없음; UTF-16/rune·NUL 경계는 호환성 검토 |
| CreateBook.displayName | R string, minLength 1/maxLength 100 | DisplayName 원문 |
| CreateBook.productFamily | R LINEAR_PERPETUAL | ProductFamily. 다른 상품을 현재 MVP에 추가하지 않음 |
| CreateBook.positionMode | R ONE_WAY | PositionMode. source/복원 pin 검증은 후속 업무 |
| CreateBook.settlementAsset | R USDT | SettlementAsset. 통화 변환/추론 없음 |
| CreateBook.reviewTimezone | R string, IANA 의미 | ReviewTimezone 원문. schema에는 minLength 없음; Core는 NotBlank/IANA, Go는 고정 catalog 검증. Compute rule과는 별도 |
| query page | O integer >=0, default 0 | Page. 누락에만 default. 명시 null/잘못된 정수·범위/overflow를 조용히 default로 바꾸지 않음 |
| query size | O integer 1~100, default 20 | Size. page*size checked 연산. 신규 cursor·임의 max page 없음 |
| header Idempotency-Key | command R ASCII [A-Za-z0-9._~-] 1~128자 | IdempotencyKey 그대로. header 이름 대소문자와 달리 key 값의 case/내용은 보존 |
| DELETE body | 현재 정의 없음 | path가 target 결정. 임의 target/scope body를 새로 받지 않음 |

CreateAccount/Book request는 additionalProperties=false다. missing/null·unknown property·중복 key·대소문자 key·
후행 JSON 등의 decoder 처리는 각 wire의 승인 규약을 맞춘다. 현재 Core의 실제 unknown field 거절 여부는
annotation만으로 검증됐다고 주장하지 않는다. Domain New만으로 JSON-level 검증을 대체하지 않는다.
단건/목록 Query에는 Idempotency-Key를 요구하거나 영속 claim을 만들지 않는다.

멱등 namespace는 기존 public operationId다. 내부 operation 이름/path로 교체하지 않는다. Account 생성의
venue/name, Book 생성의 parent/name/조합/zone, 삭제의 target ID가 semantic fingerprint이며 인증 토큰·trace·
request deadline·새 ID는 포함하지 않는다. Core의 UTF-8 length-prefix SHA-256 fingerprint와 legacy mapping을
보존하고, request JSON bytes로 새 hash를 만들지 않는다. exact algorithm/vector는 이관 계약에서 확정한다.

## Result → public 응답 필드

### TradingAccount

| public 필드 | 현재 schema | Result 출처·규칙 |
|---|---|---|
| id | R string | Account identity를 decimal 문자열로 변환, float64 경유 없음 |
| venue | R TradingVenue | 허용된 venue 값 |
| displayName | R string | 저장 원문. 이름을 account identity/unique key로 해석하지 않음 |
| status | R ACTIVE/ARCHIVED | 허용된 metadata lifecycle만. 삭제 중 Root 상태를 성공 schema에 억지로 넣지 않음 |
| createdAt | R RFC3339 date-time | server metadata instant, UTC 의미·DB precision 보존. 응답 시 새 now로 교체하지 않음 |

owner/deletionGeneration/version/updatedAt은 현재 public Account 필드가 아니다. Aggregate Snapshot을 그대로 내보내지 않는다.

### TradingBook

| public 필드 | 현재 schema | Result 출처·규칙 |
|---|---|---|
| id | R string | Book identity 문자열 |
| tradingAccountId | R string | 내부 AccountID/accountId를 이 wire 이름으로 명시 변환 |
| venue | R TradingVenue | parent에서 고정한 값 |
| productFamily | R TradingProductFamily | 고정 productFamily |
| positionMode | R TradingPositionMode | 고정 positionMode |
| settlementAsset | R string | 저장된 settlementAsset. 생성 입력의 USDT enum과 response schema를 구분 |
| reviewTimezone | R string | 저장 원문. server local/고정 offset으로 치환하지 않음 |
| status | R ACTIVE/ARCHIVED | archive 의미 채택 후 같은 정책으로 mapping |
| createdAt | R date-time | Book metadata 시각 |
| latestRevisionId | O nullable string | Record의 공개 완료 Revision pointer. 없는 값·조회하지 않은 값을 혼동하지 않음 |
| latestAnalysisRunId | O nullable string | Record 소유 pointer를 Analysis 발행과 원자 갱신. Record가 Analysis Repository를 직접 읽지 않음 |
| displayName | **현재 schema에 없음** | request/Domain/Core Result/Go projection에는 있음. 자동 응답 추가 금지 |

Core TradingBookResponse에도 displayName과 두 latest pointer가 없다. pointer 생략 자체는 현재 optional schema상
가능하지만 완전한 최신 상태 projection이라는 증거는 아니다. 새 DTO에서 optional 필드를 항상 null로 채워
"실제로 pointer 없음"이라고 단정하지 않는다.
**권장안**: Book 이름이 생성 입력과 조회에 필요하므로 public displayName을 additive optional field로 먼저 제안하고
Core/Go/Web consumer를 대조한 뒤 required 전환 여부를 별도 결정한다. additive라도 strict decoder/old data의
호환성을 검증한다. 이름·pointer의 실제 저장/조회 근거가 준비된 뒤 schema/mapper를 함께 변경한다.

### Page와 DeletionRequest

| 필드 | 현재 schema | Result 출처·규칙 |
|---|---|---|
| page.items | R array, Account 또는 Book schema | 조회 projection 목록, 0건은 []. nil→null 금지 |
| page.page/size | R integer, page>=0/size 1~100 | 요청에 적용된 page/size. 0/기본값을 omitempty로 누락하지 않음 |
| page.total | R int64 >=0 | 동일 owner/가시성 snapshot의 전체 수. 빈 page의 0건과 전체 total을 혼동하지 않음 |
| deletion.id/targetType/targetId/requestedScope | R | 최초 request identity·서버가 고정한 target/scope. opaque ID는 locator가 아님 |
| deletion.state | R DeletionRequestState | 신규 REQUESTED 또는 replay의 현재 상태. 202 자체가 물리 purge 완료가 아님 |
| deletion.affectedResources | R object | accounts/books/importSessions/artifacts/ledgerRevisions/analysisRuns/reprocessingRuns 각 R non-negative int64 |
| affectedResources.snapshotStatus | O REQUEST_TIME_SNAPSHOT/LEGACY_PARTIAL | 누락은 legacy partial. 기존 0건을 verified absence로 승격하지 않음 |
| deletion.revokedAt/requestedAt/updatedAt | R date-time | 영속 접수/차단/갱신 시각 |
| deletion.primaryPurgeStatus/objectPurgeStatus/computeRuntimePurgeStatus/backupPurgeStatus | 각 R PurgeComponentStatus | 각 component의 실제 상태. primary 완료로 전체 완료/backup 부재를 만들지 않음 |
| deletion.retryable | R boolean | 현재 terminal/실패 의미. false도 필수로 직렬화 |
| deletion.backupPurgeDueAt | O nullable date-time | 정책상 due/비적용 의미. TTL 도래만으로 검증 완료 아님 |
| deletion.startedAt/completedAt | O nullable date-time | 영속 실제 시작/완료 시각. 접수 시 now로 조작하지 않음 |
| deletion.failure | O nullable StructuredFailure | code/message/retryable와 허용 details. provider 원문/locator 없음 |

Core DeletionResponse에는 startedAt이 없고 optional nullable backupPurgeDueAt/completedAt/failure는 Kotlin nullable다.
실제 null/omitted 출력은 serializer 설정/HTTP 증적으로 확인해야 한다. Go는 필수 0/false를 누락하지 않고
unknown/not-applicable/not-started를 임의 default 0/false로 합치지 않는다. public 숫자 int64 count를 ID처럼
문자열로 바꾸는 것도 현재 계약 변경이므로 하지 않는다.

## 내부 Core/Gateway→Go 경로 후보

다음은 후속 OpenAPI 초안으로 옮길 **경로 후보**다. 두 GET은 ADR-074에 이미 DRAFT로 존재하고 나머지
6개는 이 문서의 새 제안이다. 기존 OpenAPI를 수정하거나 production scope를 확장한 상태가 아니다.

| 내부 method/path 후보 | 내부 operationId 후보 | Go Use Case | 성공 의미 |
|---|---|---|---|
| POST /internal/trading-review/v1/accounts | createOwnerTradingAccount | CreateAccount | 201 TradingAccount |
| GET /internal/trading-review/v1/accounts/{accountId} | getOwnerTradingAccount (기존 DRAFT) | GetAccount | 200 TradingAccount |
| GET /internal/trading-review/v1/accounts | listOwnerTradingAccounts | ListAccounts | 200 TradingAccountPage |
| DELETE /internal/trading-review/v1/accounts/{accountId} | deleteOwnerTradingAccount | DeleteAccount | 202 DeletionRequest |
| POST /internal/trading-review/v1/accounts/{accountId}/books | createOwnerTradingBook | CreateBook | 201 TradingBook |
| GET /internal/trading-review/v1/books/{bookId} | getOwnerTradingBook (기존 DRAFT) | GetBook | 200 TradingBook |
| GET /internal/trading-review/v1/accounts/{accountId}/books | listOwnerTradingBooks | ListBooks | 200 TradingBookPage |
| DELETE /internal/trading-review/v1/books/{bookId} | deleteOwnerTradingBook | DeleteBook | 202 DeletionRequest |

내부 body/page/응답은 위 public schema의 채택된 필드를 우선 재사용하는 안이다. transport wrapper에 Use Case Result
전체를 자동 직렬화하지 않는다. route의 `{accountId}`와 응답 `tradingAccountId`를 혼동하지 않는다.
삭제 status GET은 이번 8개 밖이지만 202 운영에는 필요하다. 기존 public getTradingDeletionRequest를 연결할
내부 조회/전 owner 권한 경로를 후속 계약에 포함해야 하며, 8개만으로 삭제 UX가 완성됐다고 표시하지 않는다.

## 인증·header·trace·수명 후보

| 값 | 브라우저→Core | Core/Gateway→Go 검토안 |
|---|---|---|
| Cookie/X-XSRF-TOKEN | 현재 Cookie JWT/CSRF 계약 | 전달된 cookie를 인증에 사용하지 않음. Core가 인증/CSRF 거절 전에 Go command를 호출하지 않음 |
| Authorization | 브라우저 값은 신뢰 경계에서 제거·재구성 | ADR-074 방식의 caller별 service Bearer 후보. 사용자 JWT/Compute key와 별개 |
| X-RefInvest-User-Delegation | 외부 주입 값 제거 | 별도 RS256 사용자 위임 후보. 서비스 인증과 AND, actor/caller binding·현재 Member authority·Go owner/Guard 모두 필요 |
| Idempotency-Key | public command 필수 | 같은 값·기존 namespace 의미로 전달. retry에 새 key 생성 금지 |
| Content-Type/Accept | 생성 JSON 요청/JSON 응답 | JSON route에 맞게 명시 검증, error도 JSON. HTML login·redirect를 성공처럼 중계하지 않음 |
| traceparent/tracestate | 유효성 검사, 미유효 parent면 새 trace | 유효 값만 전달, tracestate는 유효 parent와 함께. baggage·raw token/PII 전달 없음 |
| Cache-Control | 개인정보/삭제 철회 경계 | ADR-074처럼 모든 응답 no-store 권장. Core/CDN 경계에서도 유지 검증 필요 |
| Retry-After/WWW-Authenticate/Set-Cookie | 기존 public 의미만 노출 | 내부 header blind forwarding 금지. allowlist·오류 mapping별 결정, 새 header 의무 자동 도입 없음 |

**쓰기 인증 범위**: 기존 audience `refinvest:trading-review:owner-query:v1`와 정확한 read scope는 두 GET 전용 초안이다.
그 토큰이 create/delete를 허용한다고 해석하지 않는다. 통합 후속 profile 후보는 별도 audience
`refinvest:trading-review:account-book:v1`와 operation별 정확한 `trading-review:owner:read` /
`trading-review:owner:create` / `trading-review:owner:delete` scope다. 단건/목록은 read, 생성은 create, 삭제는 delete만 허용한다.
caller registry도 해당 operation을 허용해야 한다. owner/parent/현재 Guard 검증은 scope로 대체하지 않는다.
이 문자열/profile은 **미채택 후보**이며 기존 두 GET의 audience/검증자를 즉시 바꾸거나 두 profile을 동시에
수락하도록 활성화하지 않는다. 통합 여부·구/신 issuer 전환·키 rotation·TTL·상한·Member 철회는 별도 채택 gate다.

위임 갱신·service credential rotation으로 semantic payload/key가 달라지지 않는다. JWT jti는 업무 멱등 key가 아니다.
GET의 유효 위임 재사용과 command의 같은 key retry는 구분하며 사용자 JWT는 Worker·멱등 row에 저장하지 않는다.
Member RPC/Compute/Storage는 DB Tx 밖이다. 현재 Member 거절/서명 성공만으로 철회 linearization이 해결됐다고 하지 않는다.

request deadline, outbound connect/read timeout, DB lock/statement budget, durable purge lease/retry 정책은 별도다.
이 문서는 기존 DRAFT의 숫자를 command에 적용하거나 새 숫자/총 삭제 hard deadline을 채택하지 않는다.
브라우저 abort는 request ctx 취소이며 commit된 생성/삭제를 취소하지 않는다. 응답 유실/commit UNKNOWN은
같은 key와 영속 상태로 복구한다. proxy 자동 retry는 같은 body/key·재인증·유한 정책 없이 활성화하지 않는다.

## 오류의 내부→public 매핑 검토

| 원인/경계 | 현재 의미·관측 | 중계 권장안·확정 상태 |
|---|---|---|
| public 미인증/CSRF | 기존 401 AUTHENTICATION_REQUIRED/403 CSRF_VALIDATION_FAILED | Core에서 거절, Go 미호출 |
| malformed/key/page/ID | 400 REQUEST_INVALID/IDEMPOTENCY_KEY_INVALID | 입력 거절. 기존 두 GET draft INVALID_REQUEST와 public REQUEST_INVALID 이름 차이 명시 mapping 필요 |
| foreign/absent | 404 RESOURCE_NOT_FOUND | public 같은 hidden 404, metadata/details 누출 없음 |
| owner 삭제 | tombstone 중 410 RESOURCE_DELETED, 만료 뒤 404 | operation별 response 선언 보완, deletion control replay는 정상 접근과 구분 |
| 같은 key 다른 payload | 409 IDEMPOTENCY_PAYLOAD_CONFLICT | public 의미 유지. 원문 payload/key/fingerprint 비노출 |
| invalid IANA 등 semantic 입력 | Core의 REVIEW_TIMEZONE_INVALID/422 등 | field별 기존 의미 확인. 새 generic code로 무조건 덮지 않음 |
| CAS/저장 손상/DB unique | Core CONCURRENT_MODIFICATION/409 등 관측 | 0건/driver error를 하나의 상태로 단정하지 않음. 정본 taxonomy와 원인별 mapping 필요 |
| Go 서비스 credential/위임/호출자 권한 실패 | 기존 DRAFT의 내부 401/403 code | ADR-074의 public 502 UPSTREAM_SERVICE_ERROR 제안 참고. 사용자 401로 blind relay해 재로그인 유도하지 않음; 새 code 채택 아님 |
| 현재 Member 접근 거절 | authority DRAFT | public 403 제안, session/logout/회원 탈퇴 원인별 mapping 미확정. 서비스 설정 실패와 분리 |
| authority/일시 DB 장애 | DRAFT 503 제안 | 권한 우회·NotFound·빈 목록으로 변환 금지, retryable 의미/Retry-After는 원인별 검토 |
| request budget 만료 | DRAFT 504 제안 | timeout 응답만으로 미commit 보장 없음. command UNKNOWN/cleanup 우선 의미 보존 |
| unexpected failure/UNKNOWN | 기존 INTERNAL_ERROR 및 durable 상태 재조회 필요 | raw driver/stack 노출 금지. 새 UNKNOWN public code를 이 문서에서 추가하지 않음 |

ErrorResponse는 현재 code/message/traceId/retryable 필수, details는 optional allowlisted 배열이다. Core의 실제
TradingErrorResponse는 userAction/occurredAt을 포함할 수 있고 details가 없다. schema가 additionalProperties=false는
아니므로 그 사실만으로 schema 위반이라고 단정하지 않지만, Go에서 미정 부가 필드를 기본 API로 복제하지 않는다.
Core handler는 message=code와 새 UUID traceId를 반환한다. 새 설계는 안전한 message mapping·요청 trace 상관관계를
정의해야 하며 기존 UUID 형식도 허용한다. UUID를 W3C trace-id로 가장하거나 traceparent 전체를 message에 넣지 않는다.
서비스/위임 실패의 세부 원문·provider 오류·header/SQL/PII는 모든 경계에서 제외한다.

## 채택과 후속 검증

1. archive의 Account/Book 단건·목록·삭제 의미를 일관되게 결정한다. ADR-074 metadata 허용은 여전히 DRAFT다.
2. Book displayName·latest pointer·nullable/미조회, operation별 오류 선언·public 메시지/trace를 리뷰한다.
3. 새 내부 6개와 기존 두 GET 통합/전환 여부, auth profile·권한·header·deadline/중계 오류를 별도 OpenAPI 초안으로 확정한다.
4. 승인 schema·example·negative case·legacy fingerprint vector를 작성하고 Core/Go/Web snapshots와 실제 mapper를 검증한다.
5. 저장/Guard·Count·ID/metadata·삭제 control 조회/중첩 scope·DB 인계를 검토한 뒤 확정 범위로 구현 단계 전환한다.

후속 검증에는 required/null/unknown/중복 key, 큰 ID/overflow, Unicode/IANA, 0/false/[], pointer 생략/null,
삭제 replay·202 뒤 disconnect/UNKNOWN, 서비스 인증 오류와 사용자 인증 오류 분리, trace/no-store/header sanitization을 포함한다.
문서/CI 정적 검사는 이 HTTP 동작·DB 경합·실제 발급/키 운영의 실행 증거가 아니다.
기존 script의 docs/*.md mapping으로 Core 문서 snapshot 전파 대상이다. context merge 뒤 자동 전파 PR/검사를
확인해야 하며 이 문서 추가만으로 snapshot 전파·Go 포함 E2E가 완료됐다고 하지 않는다.

## 근거

- [서비스 경계](TRADING_REVIEW_SERVICE_BOUNDARY.md), [public API](TRADING_REVIEW_API.md),
  [OpenAPI](../openapi/core-api.yaml), [lifecycle](TRADING_DATA_LIFECYCLE.md), [Acceptance](TRADING_REVIEW_ACCEPTANCE.md)
- [Owner Query DRAFT](TRADING_REVIEW_OWNER_QUERY_CONTRACT_DRAFT.md),
  [기존 두 GET 초안](../openapi/drafts/trading-review-owner-query.yaml),
  [Member authority DRAFT](TRADING_REVIEW_MEMBER_AUTHORITY_DRAFT.md)
- [Core DTO/mapper](https://github.com/refinvest-dev/core-api/tree/38a804392af4f83f748da204f0265c4f8fc60215/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web),
  [Core Use Cases](https://github.com/refinvest-dev/core-api/tree/38a804392af4f83f748da204f0265c4f8fc60215/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application)
- [Go Domain/Use Case 검토](https://github.com/refinvest-dev/trading-review-api/tree/4cf4eac1e067f6e0a028392a5e899b5a53032a75/docs/design)

## 우선순위별 정책 리뷰: 구체 권장안

상태: **추가 검토안·미채택**. 이 절은 "미정" 목록에서 우선안을 선택해 리뷰할 수 있게 만든 것이다.
현재 OpenAPI/ADR-074·Core/Go 동작을 바꾸지 않는다. 문서 PR의 merge와 제품 정책 채택·구현 전환은 구분한다.
추가 대조 기준: context `4a3933a370e5a7b152a86450aed4ed401afc3adc`, Core `dc206eeadefbd701d2d9cc856081d6ce858a0c51`, Go `07118fa24cac356c523620faa5fa775d896e358a`.
P01~P04는 이 초안의 로컬 검토 식별자이며 새 정본 Acceptance ID/채택 ADR이 아니다.

### P01: archive를 metadata read 제한과 삭제로 혼동하지 않음

대안은 Core의 현재 비대칭 유지, 모든 archive 접근 거절, metadata 조회와 정당한 삭제 허용이다.
**권장안은 마지막 안**이다. archive를 삭제로 응답하면 사용자가 보관된 항목을 조회/정리하기 어렵고,
현재 Account 조회·Book 목록/단건 사이의 차이를 그대로 옮기게 된다. 실제 삭제 Guard와는 계속 분리한다.

다음 표는 **채택 시 목표 의미**이며 runtime 현재값이 아니다. 검증된 owner·유효 관계·삭제 Guard가 전제다.

| 행동 | 대상/상위가 ACTIVE | 삭제되지 않은 ARCHIVED | 삭제 중/삭제 tombstone |
|---|---|---|---|
| GetAccount | metadata 허용 | metadata 허용 | owned 410, foreign/absent 404 |
| ListAccounts | 목록 포함 | 목록 포함 | 해당 항목 제외 |
| GetBook | metadata 허용 | Book 또는 parent가 archive여도 metadata 허용 | Book/parent Guard 우선 차단 |
| ListBooks | ACTIVE/ARCHIVED Book 포함 | archive parent도 조회 허용, ACTIVE/ARCHIVED Book 포함 | 삭제 parent는 owned 410, 삭제 Book은 items/total 제외 |
| 신규 CreateBook | 유효 입력이면 생성 | parent archive면 409 RESOURCE_STATE_CONFLICT 권장 | parent 삭제는 owned 410 |
| CreateAccount/Book의 정당한 replay | 최초 identity와 현재 허용 metadata | 새 생성 없이 최초 identity·archive metadata 반환 권장 | 삭제된 결과/상위는 정상 생성 결과로 재활성화하지 않음 |
| 신규 DeleteAccount/Book | 삭제 접수 허용 | parent/target archive만으로 거절하지 않고 삭제 접수 허용 | 중첩 scope/기존 request 제어 규칙 필요; 신규 성공으로 덮지 않음 |
| 기존 삭제 replay/status | 기존 request의 검증된 owner control 경로 | 동일 | Root 부재/차단과 무관하게 정당한 기존 request 조회, 정상 접근 부활 없음 |

foreign/absent는 항상 존재 정보를 숨긴다. owned 410은 정본 tombstone 보관 범위 안에서만 사용하고 만료 뒤에는 404다.
신규 생성과 생성 replay는 같은 의미가 아니다. replay가 archive metadata를 반환해도 새 Book/Run을 만들거나
현재 write 권한을 부여하지 않는다. 요청 method의 caller/scope 권한은 replay에도 계속 적용한다.
새 key·변경 payload는 replay가 아니며 archive parent 아래 새 생성으로 우회하지 못한다.

영향: Core GetBook의 non-ACTIVE→410, ListBooks의 ACTIVE-only child filter, CreateBook replay의 ACTIVE 요구,
DeleteBook의 ACTIVE parent/Book 요구와 Go의 archive 보류를 **채택 후 함께** 변경해야 한다.
409 RESOURCE_STATE_CONFLICT는 기존 taxonomy를 사용하는 권장 mapping이며 archive에 대한 적용은 신규 정책 제안이다.
ARCHIVE/UNARCHIVE endpoint·진행 job 취소·artifact 접근/grant·정본 계산은 이 표로 추가하거나 변경하지 않는다.
실제 archive 이력·생성 fingerprint·화면 목록/정리 동작·정상/위반 사례를 확인하고 ADR-074 및 기존 API 의미를 함께 리뷰한다.

### P02: Book 응답과 nullable latest pointer

**권장안**: public TradingBook에 displayName을 additive optional 필드로 먼저 제안한다.
채택한 새 Core/Go mapper는 저장 근거가 있는 이름을 반환하고, 기존 응답/legacy consumer를 확인한 뒤
required 전환을 별도 결정한다. 이 문서에서 required로 즉시 바꾸거나 이름 없는 row를 빈 문자열로 보정하지 않는다.
추가 필드를 엄격하게 거절하는 consumer가 있을 수 있으므로 additive=무조건 호환이라고 가정하지 않는다.

latestRevisionId/latestAnalysisRunId는 기존 O nullable 계약을 유지한다. **확인된 없음은 null, 조회하지 않았거나
정확한 근거가 없는 값은 생략하는 안**을 권장한다. 명시 projection에 필요한 known/unknown 의미를 설계하되
모든 optional 값에 범용 Option type을 강제하지 않는다. pointer를 안 읽었다고 항상 null을 쓰지 않는다.
완전한 latest 상태를 표방할 endpoint는 실제 Record 소유 pointer를 조회하고 publication/CAS·owner/Book·
completed-only 정합성을 갖춰야 한다. 미공개 staging/추정 Analysis·새 ID를 응답에 만들지 않는다.

### P03: 오류·메시지·trace는 기존 envelope 안에서 정리

**권장안**: 기존 code/message/traceId/retryable와 allowlisted details를 유지하고 operation별 가능한
400/404/409/410/422·의존 오류를 빠짐없이 선언한다. 새 Problem Details·generic Result 오류 envelope는 도입하지 않는다.
service/위임 인증 장애를 public 사용자 401과 분리하는 ADR-074 중계안을 우선 검토하되 새 502/504 code는
초안 단계에 둔다. 원인 미확정의 write 0건·UNKNOWN을 일반 version conflict/미접수로 단정하지 않는다.

공개 message는 오류 code와 별도의 안전한 한국어 catalog로 관리하는 방향을 권장한다. Domain sentinel/driver
message·payload를 그대로 반환하지 않고 code·retryable 의미와 문구를 각각 리뷰한다. 문구를 통계/분기 key로 사용하지 않는다.
Core의 message=code와 새 UUID correlation은 현재 구현 사실이다. 후속 mapper가 기존 UUID traceId 형식을
허용하면서 요청 trace와 상관관계를 유지하게 하고, 임의 UUID를 W3C trace-id라고 표시하지 않는다.
userAction/occurredAt은 현재 Core 부가 필드 관측이므로 신규 Go의 공통 필수 응답으로 자동 채택하지 않는다.

### P04: read/create/delete 위임과 삭제 status control query

**권장안**: 앞 절의 별도 account-book audience·정확한 read/create/delete scope와 caller operation allowlist를
후속 통합 profile 후보로 유지한다. 기존 owner-query audience의 read token이 create/delete를 허용하게 확대하지 않는다.
서명·service 인증·Member authority·owner/parent/현재 Guard 모두 필요하며 새로운 토큰 문자열만으로 해결됐다고 하지 않는다.

삭제 202 뒤 polling을 위해 기존 public `GET /trading-deletion-requests/{deletionRequestId}`를 연결할
**내부 후보** `GET /internal/trading-review/v1/deletion-requests/{deletionRequestId}` /
`getOwnerTradingDeletionRequest`를 후속 schema에 포함하는 안을 권장한다. 새 public 기능을 추가하는 것이 아니라
이미 있는 public 동작의 Go 연결 보완안이다. 후속 ADR-075의 9개 operation draft에 내부 OpenAPI를 작성했으며, 여전히 미채택이다.
이 GET은 read scope+caller 허용으로 자기 request만 조회한다. Root의 ACTIVE/존재를 요구하지 않고
request owner/target/control 보관 정책을 검증하며, 외부 다른 owner에는 같은 hidden 404다.
Member 접근 차단/탈퇴 후 상태 조회 허용은 authority/수명주기 계약에서 별도 결정한다. "전 owner 조회"를
삭제된 Member 인증 우회나 다른 사용자 토큰 허용으로 해석하지 않는다.
DELETE의 내부 replay가 읽기만 하더라도 DELETE route에는 delete scope를 요구한다. read token으로 DELETE를
호출해 replay를 시도하도록 허용하지 않는다. Worker control 능력과 이 사용자 metadata GET도 구분한다.

중첩 삭제의 count/control도 연계 검토한다. 상위 요청의 count는 기존 request-time logical resource 의미를
보존하고 그 시점에 실제 남은 대상 집합에서 고정하는 방향을 권장한다. 기존 child request의 과거 count를
그대로 합산하거나 이미 purge된 child를 새 live count에 더하지 않는다. 삭제 중이지만 아직 남은 row와
external cleanup task의 정확한 포함 범위는 scope별로 정의해야 한다. 원래 child의 identity/count/checkpoint/
terminal 이력은 보존하고, 상위 차단 유지·공통 purge의 멱등 처리와 각 request의 완료 증거를 함께 검증한다.
이 권장안으로 count schema/기존 snapshot을 수정하거나 child를 자동 COMPLETED로 만들지 않는다.

### 이번 리뷰의 종료 기준과 다음 산출물

이번 단계의 완료는 P01~P04의 우선안·변경 영향·반대 대안·채택 조건을 구체화한 것이다.
현재 규칙, 제안 선택, 실제 환경/검증 증적 대기를 각각 기록하고 CI 성공이나 일반적인 "다음 작업" 요청을
제품 정책 채택/구현 전환으로 해석하지 않는다. Go의 기술/운영 선택은 해당 저장소의 decision review에 연결한다.
후속 계약 작업은 리뷰 결과를 ADR/정본·OpenAPI draft·positive/negative example에 반영하고 snapshot 영향/consumer
검증을 준비하는 것이다. 구현은 확정 범위와 명시적인 단계 전환에 따라 별도 진행한다.

## 9개 operation 계약 후보의 정적 구체화

[ADR-075 DRAFT](DECISIONS.md#adr-075--trading-review-accountbook-contract-candidate-draft)와
[Account/Book OpenAPI draft](../openapi/drafts/trading-review-account-book.yaml)에 기존 8개 업무와 삭제 status
GET 후보를 구체화했다. 기존 public/Compute·ADR-074 두 GET은 변경하지 않는다. 새 파일의
x-contract-status=draft/x-runtime-enabled=false/x-policy-status=proposed는 채택/활성화 gate다.
Book 응답 확장은 해당 draft의 local allOf에서만 표현하며 public schema를 수정하지 않았다.
vendor extension과 semantic plan은 실제 인증/멱등/DB/HTTP 구현이 아니다. schema 거부 사례와
int64 upper bound·IANA/현재 owner·commit 복구 등 실행 계획을 분리해 기록한다.

## P01/P02의 현재 코드·소비자 대조와 수용 계획

상태: **권장안의 호환성 리뷰·미채택·업무 실행 미검증**. 아래 source 사실은 Core
`6a4f11ca1228a803c9b1eccf334eaa2220ee3551`, Go `537c43db83e107242a86d04aace8b45c967a33ef`,
Web `9354dfe321ee8543c10e0914249f06d6643ca6db`를 읽은 결과다. 실제 DB의 archive 이력·legacy 값이나
배포 consumer를 조사한 결과가 아니다. ADR-075 merge도 정책 채택을 뜻하지 않는다.

### 현재 사실과 변경이 필요한 경계

| 경계 | 현재 source 사실 | P01/P02 권장안 채택 시 필요한 일 |
|---|---|---|
| Account get/list | Get은 삭제 상태를 차단하고 archive는 허용. 목록 item/count는 ACTIVE·ARCHIVED 포함 | 이 의미를 보존하며 Book과 일관되게 수용 검증 |
| Account 생성 replay·삭제 | 생성 replay와 신규 삭제는 ACTIVE·ARCHIVED 허용 | 이미 허용된 동작을 신규 변경으로 오인하지 않음 |
| Book get/list | Get은 Book/parent non-ACTIVE를 RESOURCE_DELETED로 거부. List는 archive parent를 허용하지만 item/count SQL은 ACTIVE-only | 조회 정책과 item/count의 동일한 owner·상태 필터를 함께 변경. archive를 삭제로 표시하지 않음 |
| Book 생성 | parent ACTIVE 확인이 idempotency claim보다 먼저이며 replay 결과도 ACTIVE만 허용 | 신규 생성 차단은 유지하고, 정당한 replay의 현재 archive metadata 허용 경로를 구분. read/create/delete 권한은 유지 |
| Book 직접 삭제 | Service가 parent/Book ACTIVE를 요구하고 Domain requestDeletion도 Book ACTIVE만 허용 | Controller/Service만 수정하면 Domain에서 실패하므로 Domain 전이·Application·저장 조건을 함께 검토 |
| Account 삭제 cascade | 하위 Book SQL은 이미 ACTIVE·ARCHIVED를 삭제 중으로 전이 | 직접 Book 삭제와 비대칭인 사실을 기록. 기존 진행/실패 child request를 새 요청으로 덮지 않음 |
| Go 기존 ownerquery | Account archive는 조회 가능. Book/parent archive는 ErrArchived이고 Application이 ErrUnavailable로 변환 | 현재 Go가 HTTP 410을 반환한다고 표현하지 않음. HTTP route 미연결이며 내부 오류 정책도 채택 후 조정 |
| Book 응답 | Core Result에는 displayName이 있지만 TradingBookResponse에서 누락. Result에는 두 latest pointer가 없음 | optional 이름과 pointer의 조회 근거·presence를 Result→DTO 각 경계에서 명시. Result/Domain을 그대로 JSON으로 노출하지 않음 |

Core persistence projection의 `latestLedgerRevisionId` 존재만으로 HTTP mapper가 그 값을 받은 것은 아니다.
현재 Result가 값을 전달하지 않고 Go ownerquery도 latest를 읽지 않으므로 이 경로의 부재는 **미조회**다.
Analysis 구현이나 table이 이번 slice에 없다는 이유로 `latestAnalysisRunId: null`을 추정하지 않는다.

### Web 소비자 조사 범위

고정 Web revision의 추적 파일에서 `trading-accounts`, `trading-books`, `TradingAccount`, `TradingBook`,
`trading-deletion-requests`를 검색하면 계약 snapshot에만 나타난다. `src/`의 전용 호출·타입·화면은
이번 검색에서 찾지 못했다. `src/entities/account`는 Member 계정 기능이며 TradingAccount 소비자로 세지 않는다.
공통 `src/shared/api/http.ts`는 응답 JSON을 전달하고 401이면 refresh를 시도한다. 따라서 내부 서비스 인증
실패를 public 사용자 401로 그대로 보내면 불필요한 refresh가 발생할 수 있다는 source상 위험은 P03에서 이어 검토한다.

이는 현재 checkout의 static inventory다. 배포된 구버전·별도 client·외부 consumer가 없거나 optional 필드 추가를
모두 허용한다는 증거가 아니다. Web 전용 수용 테스트나 새 Go API E2E가 존재/통과한다고 보고하지 않는다.

### P01 수용 계획

아래 ID는 이 초안의 **실행하지 않은 로컬 검토 사례**다. 정본 TR-ACC Scenario ID나 구현된 fixture가 아니다.
모든 사례는 caller/service·Member·owner 검증과 현재 삭제 Guard를 전제로 하며 foreign/absent에 대한
존재 정보는 숨긴다. 신규 조회·쓰기의 삭제 우선권은 TR-I14/TR-I15, 삭제 진행 표시는 TR-I19와 대조한다.

| ID | Given / When | 채택 시 Then과 관측할 부작용 |
|---|---|---|
| P01-C01 | owner의 archive Account를 Get/List | metadata 성공, 목록에 포함. 이름·identity·generation·version 변경 없음 |
| P01-C02 | Book 또는 parent가 archive이며 GetBook | metadata 성공, Book/parent ACTIVE로 변경 없음. artifact·job 접근 허용으로 확대하지 않음 |
| P01-C03 | 같은 Account에 ACTIVE/ARCHIVED/삭제 중 Book과 다른 owner Book이 섞여 ListBooks | 자기 ACTIVE·ARCHIVED만 items/total에 포함. 동일 snapshot·동일 predicate·기존 정렬/페이지 경계를 검증 |
| P01-C04 | 삭제 중/삭제 tombstone parent로 ListBooks | owned 삭제 오류. 정상 빈 목록으로 숨기지 않음. tombstone 보관 종료 후 판정은 기존 보관 정책 |
| P01-C05 | archive parent 아래 새 key로 CreateBook | 409 RESOURCE_STATE_CONFLICT 권장. Book/멱등 성공 mapping·dispatch·ID 사용 완료 기록을 새로 commit하지 않음 |
| P01-C06 | 같은 owner/operation/key/fingerprint로 Account/Book 생성 replay, 결과 또는 parent가 archive | 최초 identity와 허용된 현재 metadata를 반환. 신규 resource·멱등 성공 mapping·Run/dispatch를 만들지 않음 |
| P01-C07 | 같은 생성 key의 결과/parent가 삭제됨 | archive 허용보다 삭제가 우선. 정상 201 metadata로 부활시키지 않음. retained owned tombstone은 410, foreign/absent는 hidden 404 |
| P01-C08 | archive Book 또는 archive parent 아래 Book을 정당하게 신규 삭제 | 202 접수. Domain 전이·현재 fence·generation·DeletionRequest/checklist·멱등 mapping의 원자성 검증. 완료가 아님 |
| P01-C09 | Root purge 뒤 정당한 기존 DELETE replay 또는 status GET | 원래 request identity/현재 진행을 owner control 경로로 조회. Root ACTIVE/존재를 요구하지 않고 새 삭제·접근 부활 없음. DELETE에는 delete scope 유지 |
| P01-C10 | 다른 owner/차단된 Member/권한 없는 caller가 archive 또는 replay 경로 호출 | 인증/인가·hidden 404 규칙 유지. archive/replay를 권한 우회로 사용하지 않음 |
| P01-C11 | 같은 key에 다른 payload와 archive/삭제 상태가 동시에 존재 | 성공 replay는 금지. 숨김·현재 Guard·claim 순서에 따른 정확한 conflict 우선순위는 P03/T01에서 확정하며 이 표로 새 precedence를 채택하지 않음 |
| P01-C12 | 삭제와 metadata 조회/생성 replay가 경합 | 삭제 commit 이후 시작된 정상 조회·새 publication은 차단. 이미 시작한 snapshot/응답의 경계와 장기 grant 철회는 별도 수명 정책으로 검증 |

P01-C05의 실패 시 기존 durable ID allocator의 후보 발급으로 gap이 생기는 것과 업무 데이터 commit은 별개다.
ID를 재사용하거나 생성 실패를 기존 mapping 삭제로 보상하지 않는다. UNKNOWN은 실패/미접수로 단정하지 않고
같은 key/fence로 영속 상태를 확인한다. 위 사례는 SQL 경합이나 UNKNOWN 복구를 실행한 증거가 아니다.

### P02 수용 계획과 mapper 입력의 세 의미

| ID | 근거/입력 | 채택 시 출력·검증 |
|---|---|---|
| P02-C01 | 검증된 저장 Book 이름이 Result까지 전달됨 | optional displayName에 원문을 반환. 필수 전환은 별도 변경. 다른 필드로 이름을 합성하지 않음 |
| P02-C02 | legacy 이름의 저장 근거가 없거나 손상됨 | 빈 문자열/default로 보정하지 않음. 허용된 생략과 저장 오류를 legacy 정책에서 구분; 이번 리뷰는 정상 값으로 승인하지 않음 |
| P02-C03 | latest를 조회하지 않았거나 정확한 판단 근거가 없음 | 해당 optional 필드 생략. 미조회→null 변환 금지 |
| P02-C04 | 같은 판정 snapshot에서 공개 pointer 부재가 확인됨 | 명시 null. 신규 생성의 초기 상태도 해당 Tx/조회로 보장된 근거가 있어야 함 |
| P02-C05 | 공개된 유효 pointer가 확인됨 | 정확한 opaque ID 문자열. 같은 Book/owner·Revision/Analysis 연결과 completed-only 정합성 확인; staging 값은 반환하지 않음 |
| P02-C06 | 오래된 응답·추가 필드를 거부하는 consumer·목록 item mapper | optional 필드 없는 기존 응답도 허용하는지, 새 필드에 strict decoder가 실패하는지 확인. Create/Get/List/replay mapper를 모두 대조 |

Go의 단일 `*string`에 omitempty를 붙인 것만으로 P02-C03의 omitted와 C04의 null을 모두 표현했다고
가정하지 않는다. 구체 DTO 표현과 직렬화 예제 검증은 Go 문서가 소유하며 Domain에 HTTP JSON tag·공통
Option/Base type을 도입하는 근거가 아니다. 이 표는 public OpenAPI의 Book 필드를 수정하지 않는다.

### 변경 순서와 남은 리뷰

P01/P02는 권장안으로 유지한다. 이번에 확인한 source 차이와 위 사례를 기준으로 정책/consumer 영향을
검토한 뒤 별도 채택한다. Core legacy를 계속 확장하는 구현 계획이 아니라, 필요한 기존 수정과 Go 이관 명세를
구분하는 비교표다. 실제 적용 범위에는 Domain·Use Case·Store/Reader·목록 count·Result/HTTP mapper가 함께 들어간다.
신규 Archive/Rename/PATCH·UI·grant/job 정책은 추가하지 않는다.

다음은 P03의 operation별 내부→public 오류/안전한 메시지·trace와 P04의 issuer/Member/권한·취소/timeout
경계를 검토한다. 이후 T01~T05·legacy 인계 증거로 연결한다. 전 항목을 새로 미정으로 되돌리거나 schema/CI
성공을 제품 정책 채택·Go 업무 구현 전환으로 해석하지 않는다.

근거: [Core application](https://github.com/refinvest-dev/core-api/tree/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application),
[Core Book Domain](https://github.com/refinvest-dev/core-api/blob/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain/TradingBook.kt),
[Core persistence](https://github.com/refinvest-dev/core-api/tree/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence),
[Core HTTP Book](https://github.com/refinvest-dev/core-api/blob/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/book/TradingBookResponse.kt),
[Go ownerquery](https://github.com/refinvest-dev/trading-review-api/tree/537c43db83e107242a86d04aace8b45c967a33ef/internal/modules/tradingrecord),
[Web HTTP client](https://github.com/refinvest-dev/web/blob/9354dfe321ee8543c10e0914249f06d6643ca6db/src/shared/api/http.ts).
