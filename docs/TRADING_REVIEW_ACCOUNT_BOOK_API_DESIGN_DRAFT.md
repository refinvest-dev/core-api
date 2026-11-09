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
