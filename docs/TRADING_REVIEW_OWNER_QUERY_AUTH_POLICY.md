# Trading Review Owner Query Authentication Policy

상태: **ADR-086 채택(2026-11-23), Account·Book metadata 단건 GET 두 개의 보안·authority 계약 한정.**
사용자와 권고안을 재검토하고 정책 확정 요청에 따라 채택했다. 문서 merge·CI가 채택 근거는 아니다.
실제 Core issuer/권위 상태·Go production 인증·route·키 공급/운영·HTTP 수용 완료를 뜻하지 않는다.

## 적용 범위와 기존 계약

| Go 내부 operation | 공개 Core operation |
|---|---|
| `GET /internal/trading-review/v1/accounts/{accountId}` | `GET /trading-accounts/{accountId}` |
| `GET /internal/trading-review/v1/books/{bookId}` | `GET /trading-books/{bookId}` |

초기 Core가 브라우저 인증·CSRF와 위임 발급/중계를 담당한다. 향후 Gateway는 별도 caller 등록과
인증된 사용자 위임 발급 연계를 갖춰야 하며 직접 issuer가 될 필요는 없다. Core의 Trading Review
writer/worker 중단과 Core 인증·중계/authority 운영은 구분한다. 서비스 경계는 [ADR-072](TRADING_REVIEW_SERVICE_BOUNDARY.md)를 따른다.

이 결정은 ADR-074의 두 GET에 대한 서비스 인증·위임 profile·현재 authority·오류 중계·키 운영 원칙을
대체한다. 전체 ADR-074나 ADR-075~079, 목록·생성/replay·Import·삭제·재처리·grant·Worker 권한을
일괄 채택하지 않는다. read scope를 다른 operation에 재사용하지 않는다. HEAD도 암묵적으로 공개하지 않는다.
Book metadata의 ARCHIVED parent/Book 허용은 [ADR-083](TRADING_REVIEW_BOOK_METADATA_POLICY.md)이 정본이며
이 인증 결정으로 Account·Book의 새로운 상태/업무 권한을 만들지 않는다.

현재 공개 Core/Compute schema와 legacy ID·hash·멱등성 key는 유지한다. 아래 두 내부 OpenAPI는
**정책이 연결된 미활성 상세 설계 산출물**이며 `draft`/`x-runtime-enabled: false`를 유지한다.
그 표기를 이 정책의 재승인 요구나 runtime 활성화로 해석하지 않는다.

- [Owner Query OpenAPI](../openapi/drafts/trading-review-owner-query.yaml)
- [Member Authority OpenAPI](../openapi/drafts/trading-review-member-authority.yaml)

## 서비스 인증과 사용자 위임

TLS server identity 검증 위에서 아래 두 자격을 **모두** 요구한다. TLS termination 이후에도 인증
자격을 운반하는 신뢰 경계를 명시하고 보호한다. 임의 외부 헤더나 평문 전달을 신뢰하지 않는다.

| 전달 | 의미/검증 |
|---|---|
| `Authorization: Bearer <opaque service credential>` | Go 대상 registry로 caller 식별·활성·operation 권한 확인 |
| `X-RefInvest-User-Delegation: <compact JWT>` | Core auth가 서명한 사용자·대상·범위·actor·시간 검증 |

Core는 브라우저가 보낸 내부 Authorization/delegation/identity 헤더를 제거하고, 검증된 사용자와
실제 caller로 자격을 재구성한다. ownerId/userId/role/caller request field로 위임하지 않는다.
서비스 인증만 통과하거나 JWT만 유효한 요청은 허용하지 않는다. credential 비교는 constant-time
비교를 사용하되 전체 HTTP 처리 시간이 constant-time이라고 주장하지 않는다.

Core→Go, Go→Core authority credential과 브라우저 HS256 secret, Compute의
`REFINVEST_INTERNAL_API_KEY`, 위임 signing private key는 각각 분리한다. 기존 Core/Compute 공통 key
정책은 유지하며 새 Go 인증에 재사용하지 않는다. 서비스 credential은 최소 32 random bytes의
엔트로피로 발급한다. 정확한 공급 파일/환경변수·발급 도구·상한은 구현 설계에서 정하고 입력을 제한한다.
secret은 보호된 공급 경로로 제공하며 production secret을 저장소/.env.example에 넣지 않는다.
mTLS는 현재 필수 조건으로 채택하지 않는다. 도입 시 caller binding·TLS 운영 계약을 별도 검토한다.

## 위임 JWT profile

Core auth의 별도 issuer가 **RS256**으로 발급하고 private key는 Core에만 둔다. Go/Core 검증자는
신뢰 설정의 issuer/kid별 public key를 사용한다. 브라우저 access/refresh JWT·HS256·`none`을
위임으로 받지 않는다. 토큰 type/audience/검증 규칙 분리는 [RFC 8725 §3.12](https://www.rfc-editor.org/rfc/rfc8725.html#section-3.12)를 따른다.

| 구분 | 고정 규칙 |
|---|---|
| protected header | `alg=RS256`, `typ=refinvest-user-delegation+jwt`, 비어 있지 않은 `kid` |
| `iss` | 환경별 명시 allowlist와 정확히 일치. 임의 discovery 금지 |
| `aud` | 단일 string `refinvest:trading-review:owner-query:v1` |
| `sub` | 인증된 내부 Member ID의 canonical positive decimal int64 문자열. email/provider subject 아님 |
| `act` | `sub` 하나를 가진 object. `act.sub`는 credential에서 식별한 caller와 정확히 일치 |
| `scope` | 단일 string `trading-review:owner:read`; 이 문서의 두 GET만 허용 |
| `iat`, `nbf`, `exp` | 손실 없는 positive integer NumericDate. `nbf=iat`, `0 < exp-iat <= 60` |
| `jti` | issuer가 생성한 비충돌 opaque 식별자. 일회용 admission key·업무 멱등성 key 아님 |

clock leeway는 **5초**, JWT 전체 상한은 **4096 bytes**다. `iat <= now+5`, `nbf <= now+5`,
`now < exp+5`를 적용하며 overflow를 안전하게 거절한다. nominal TTL 이후에도 leeway만큼 수락될 수
있음을 명시한다. Core의 권위 snapshot 시점에도 같은 시간 profile을 만족해야 하며 Go는 authority
응답 후 principal을 넘기기 전에 시간과 Context를 다시 확인한다. TTL/상한을 설정으로 임의 완화하지 않는다.

header는 alg/typ/kid, claims는 위 아홉 필드, act는 sub만 허용한다. 중복/알 수 없는 JSON member,
추가 JSON·잘못된 UTF-8·잘못된 타입·손실 변환을 거절한다. compact JWT의 원 서명 bytes를 검증하며
원 JSON을 다시 직렬화해 서명 입력으로 쓰지 않는다. `jku`, `x5u`, embedded `jwk`, 미지원 extension은
거절하고 토큰이 가리킨 URL/key를 fetch하지 않는다. RSA는 최소 2048-bit다.

이 고정 profile의 header/claims 계약과 실험의 key 개수·RSA 상한·exponent 등 자원 제한은 구분한다.
production library/strict decoder와 지원 key 범위는 구현 전에 상세 설계·보안 리뷰로 정한다.
현재 Go의 좁은 parser 실험을 production 구현/독립 보안 검토 완료로 승격하지 않는다.

유효 시간 안의 GET retry는 같은 위임을 재사용할 수 있다. jti는 일회 소비하지 않으며 TTL·TLS·caller
binding만으로 완전한 replay 방지나 proof-of-possession을 보장하지 않는다. scope 아래 resource의
실제 소유권을 Go가 다시 확인한다. actor claim 사용은 [RFC 8693 §4.1](https://www.rfc-editor.org/rfc/rfc8693.html#section-4.1)의
의미를 따르지만 OAuth token exchange endpoint 전체를 도입한 것은 아니다.

## 현재 Member authority와 Go 검증 순서

1. TLS/HTTP 입력 상한·중복 인증 header를 검사하고 서비스 credential을 검증한다.
2. JWT 서명/profile/actor binding과 caller의 operation 권한을 확인한다.
3. DB Transaction 밖에서 Core authority를 동기 호출한다. 매 요청 새 checkId를 사용한다.
4. 허용된 Member identity만 기존 owner query에 전달한다. Go는 owner/parent/현재 삭제 Guard를
   resource SQL snapshot에서 검증한다. ADMIN도 다른 owner를 우회하지 않는다.
5. 명시 response DTO로 변환하고 `Cache-Control: no-store`를 적용한다.

서명/서비스 인증 실패는 authority 호출을 만들지 않는다. authority 거절·장애·binding 불일치에서는
resource lookup을 시작하지 않는다. Go가 Member/auth 테이블을 직접 조회해 Core 권위를 대신하지 않는다.
authority의 positive decision을 캐시·재사용하거나 policyRevision/checkedAt을 권한 lease로 쓰지 않는다.

Core auth는 Member 존재·별도 접근 허용/차단 상태와 positive policyRevision, 원 caller/authority caller의
활성 상태, kid/jti 철회 상태를 권위 저장소에서 판정한다. Member role/existence만으로 허용하지 않는다.
Member 접근 상태 row 부재는 거절, schema/version 미준비·권위 저장 장애는 unavailable이다.
구체 테이블/enum·Member 연계·신규 가입/기존 회원 backfill·revision/CAS는 Core 상세 설계에서 확정한다.

판정은 primary의 하나의 READ COMMITTED statement snapshot 또는 동등하게 입증한 일관된 단위를
사용한다. stale replica/혼합한 옛 cache로 active를 반환하지 않는다. token 시간도 그 판정 시점에
검사한다. 차단/철회 commit 뒤 얻은 snapshot은 허용할 수 없다. 이미 허용된 제한된 조회는 이후
철회가 commit돼도 끝날 수 있다. 응답 완료까지의 전역 즉시 철회나 Core/Go 분산 Tx는 보장하지 않는다.

## Authority request와 response

endpoint는 Core auth 소유 `POST /internal/trading-review/v1/delegations/check`다. Go 전용 Core-target
credential만 받고 브라우저 cookie/원 inbound credential을 받지 않는다. 이 path의 별도 service 인증
chain이 기존 브라우저 API의 CSRF를 전역 해제해서는 안 된다.

| request field | 의미 |
|---|---|
| `delegation` | 원 compact JWT. Core도 서명/profile을 재검증 |
| `expectedActor` | Go ingress에서 인증한 caller. 현재 authority 호출자인 trading-review-api와 구분 |
| `checkId` | 매 검사마다 생성한 128-bit random lowercase hex(32자) |

request는 최대 **8192 bytes**, response는 최대 **4096 bytes**의 단일 JSON object다. 중복/알 수 없는
field·추가 JSON을 거절한다. response의 status/media type/필수 no-store·타입·크기를 확인한다.

- `200 active=false`: active/checkId/tokenSha256/checkedAt만 반환한다. Member 부재/차단, 접근 상태
  row 부재, caller/kid/jti 철회, 위임 무효를 포함하며 이유·신원을 노출하지 않는다.
- `200 active=true`: 위 네 필드에 sub/actor/iss/aud/scope/jti/exp/policyRevision을 추가한다.
  Go가 검증한 원 identity와 모든 binding을 정확히 비교한다.
- schema 미준비·권위 저장소 장애는 `503 AUTHORITY_UNAVAILABLE`다. authority 자체의 credential
  오류/권한 부족은 내부 401/403이며 Go에서는 authority unavailable로 처리한다.

tokenSha256은 원 compact token bytes의 lowercase SHA-256 hex이며 checkId/hash를 모두 비교한다.
checkedAt은 UTC 관측 시각, policyRevision은 canonical positive decimal int64 문자열이다. hash/nonce는
권한 증명이나 DB 최신성의 암호학적 증명이 아니다. 실제 신선함은 Core snapshot 구현으로 보장한다.
active=false는 MEMBER_ACCESS_DENIED, malformed/mismatch·network/control 장애는 AUTHORITY_UNAVAILABLE로
거절한다. 이 JSON protocol은 [RFC 7662](https://www.rfc-editor.org/rfc/rfc7662.html)의 form endpoint를 구현한 것이 아니다.

## 철회와 수명 경계

Member 전체 차단, 원 caller/authority caller 비활성, kid·특정 위임 jti 철회를 다룬다. Core의 현재
logout은 refresh family 철회이며 access에는 familyId가 없다. 이를 모든 access/위임의 즉시 철회로
해석하거나 한 기기 logout을 Member 전체 차단으로 바꾸지 않는다. session 단위 위임 철회는 별도 계약이다.
탈퇴는 접근 차단 commit을 먼저 확정하고 Go 데이터 삭제/FK 인계 후 Member 물리 삭제로 진행한다.
그 orchestration·재시작/복구는 별도 구현·수용 대상이다.

Worker에는 사용자 JWT/authority allow를 저장하지 않는다. 이미 접수된 작업은 승인 입력·owner와 현재
Guard·명시 취소 정책을 따른다. Member 차단이 기존 작업을 자동 취소한다는 정책은 이 결정에 없다.

## 오류 registry와 Core 중계

기존 ErrorResponse의 code/message/traceId/retryable envelope를 유지한다. 아래 registry는 두 GET의
내부 오류와 public 중계 정책이다. 서비스 인증 오류를 사용자 재로그인으로 잘못 유도하지 않는다.

| Go status/code | public Core status/code | retryable |
|---|---|---|
| 400 INVALID_REQUEST | 400 INVALID_REQUEST | false |
| 401 SERVICE_AUTHENTICATION_FAILED / USER_DELEGATION_INVALID | 502 UPSTREAM_SERVICE_ERROR | false |
| 403 CALLER_NOT_ALLOWED | 502 UPSTREAM_SERVICE_ERROR | false |
| 403 MEMBER_ACCESS_DENIED | 403 MEMBER_ACCESS_DENIED | false |
| 404 RESOURCE_NOT_FOUND | 404 RESOURCE_NOT_FOUND | false |
| 410 RESOURCE_DELETED | 410 RESOURCE_DELETED | false |
| 503 AUTHORITY_UNAVAILABLE / OWNER_QUERY_UNAVAILABLE | 503 동일 code | true |
| 504 REQUEST_DEADLINE_EXCEEDED | 504 REQUEST_DEADLINE_EXCEEDED | true |

foreign/absent는 같은 404로 처리하고, 410은 owned 삭제 Guard에만 적용한다.

브라우저 인증 자체의 실패는 기존 Core 인증 경로에서 401로 처리한다. authority의 400/401/403·불일치
response는 Go에서 503이며, active=false인 사용자 접근 거절과 구분한다. 알 수 없는 upstream 오류는
안전한 502로 처리하고 임의 status/code를 그대로 공개하지 않는다. 공개 message는 고정된 안전한
문구로 상세 설계에서 확정하며 SQL·토큰·credential·raw header·리소스 metadata를 넣지 않는다.

응답은 no-store이며 내부 WWW-Authenticate challenge나 login redirect/HTML을 브라우저로 넘기지 않는다.
이 인증 mapping은 두 GET의 예외 규칙이며 [재처리 원문 중계](TRADING_REVIEW_REPROCESSING_WIRE_CONTRACT.md)를
전역 변경하지 않는다. retryable은 자동 재시도의 권고 여부이지 접수/rollback 성공 증거가 아니다.

## Timeout·trace·취소

authority RPC는 Go 요청의 남은 budget 안에서 DB Tx 밖에 실행한다. Core 전체 budget은 Go 처리와
응답 여유를 포함하며, 각 단계는 upstream과 local의 더 짧은 deadline을 따른다. authority RPC/전체
요청 수치는 부하·장애 측정 후 활성화 전에 확정한다. 과거 1초/5초/12초 제안은 채택 수치가 아니다.
cleanup은 독립된 유한 예산이며 장기 Run/attempt deadline·lease와 구분한다.

authority 장애에 무조건 retry하거나 positive cache로 우회하지 않는다. Core proxy는 DB Tx/row lock·
authority semaphore를 쥔 채 Go를 기다리지 않고 authority는 Go/public proxy를 역호출하지 않는다.
callback의 thread/connection pool·동시성 제한·처리 여유를 실제 부하로 검증한다.

검증된 traceparent/tracestate만 전달하고 malformed parent는 새 trace로 시작한다. 유효 parent 없는
tracestate와 baggage는 버린다. 토큰/credential·거래 원문·사용자 식별자를 trace에 넣지 않는다.
브라우저 abort는 해당 read Context 취소이며 commit된 durable job의 취소가 아니다. 두 GET은 업무
멱등성 row/Run을 만들지 않는다. 재시도 위임이 만료됐으면 같은 사용자/caller 의미로 새로 발급한다.

## 키 공급·교체·철회

Core signing private key는 보호된 issuer 공급 경로에만 두고 검증자는 public key만 받는다. 신뢰
snapshot은 전체 검증 후 원자 교체하며 issuer가 새 kid를 사용하기 전에 모든 serving 검증자에
먼저 배포/확인한다. 미확인 node는 drain한다. kid는 issuer 안에서 재사용하지 않는다.

구 key는 발급 중지 후 VERIFY_ONLY로 유지하고 마지막 발급부터 최대 TTL·양방향 skew·진행 요청
예산을 고려한 창 뒤 retire한다. 과거 75초 예시는 고정 운영값이 아니다. service credential도 같은
caller에 새 값을 먼저 등록하고 호출자 전환/확인 뒤 구 값을 회수한다. 제한된 두 자격 overlap은
권한 확대가 아니다. rotation 도구/registry 형식·배포 ack는 구현 설계·운영 리허설로 구체화한다.

긴급 철회는 권위 store의 차단/철회를 commit하고 추가 발급을 중단한 뒤 verifier/credential
snapshot을 전파한다. public key cache만으로 철회를 대신하지 않는다. jti 철회는 해당 token의
최종 수락 가능 시점(exp+leeway)까지 유지한다. 원문 token/credential·전체 jti·subject/hash를
application log/trace/error/checkpoint에 남기지 않는다. 허용된 운영 code·registry revision·checkId로 진단한다.

## 구현과 활성화의 완료 조건

정책 채택과 runtime 활성화는 별개다. 구현 전에 production JWT library/strict parser·지원 key 범위,
Core state/schema·초기화/backfill·제어 API 권한/감사, HTTP 입력 상한·안전 message를 설계한다.
파일/함수/환경변수 이름은 서비스 구현 소유이며 새 의미·scope·public field를 임의 추가하지 않는다.

활성화 전 실제 Kotlin issuer→Go 검증 positive/negative vector, browser HS256/CSRF 회귀, Member·caller·
kid·jti 차단 commit 전후/권위 저장 장애, 응답 binding·만료·key/credential rotation·reload 실패,
Core→Go→Core callback 부하/취소/trace/error→실제 Go DB owner/parent/Guard 경로를 검증한다.
새 회원/기존 Member state 초기화와 탈퇴 연계·실제 secret 공급/회수·timeout 수치를 확인한다.

E2E 전 integration 계약 sync가 선행한다. 실제 DB history/ID·Core TR 단일 writer 인계·backup restore와
서비스 활성화 gate는 유지한다. 기존 synthetic fixture·Go RSA/TLS 실험·문서 CI로 이를 완료 처리하지 않는다.
후속 구현은 이 채택을 재승인받지 않되 수용 실패·미지원 범위는 숨기지 않는다.
