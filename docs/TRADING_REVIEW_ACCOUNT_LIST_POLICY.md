# Account 목록 HTTP·위임 정책

상태: **ADR-087 채택(2026-11-28), Account 목록 GET 하나 한정.**
사용자의 재논의 후 정책 확정 요청에 따라 AL-P01~04를 채택했다. 문서 merge/CI는 채택 근거가 아니다.
초안의 별도 audience 안을 조정해 기존 owner-query audience를 유지하고 목록 전용 scope·caller operation 허용으로 격리한다.
현재 [ADR-086](TRADING_REVIEW_OWNER_QUERY_AUTH_POLICY.md)의 단건 두 GET은 기존 scope 그대로 유지한다.
public/internal OpenAPI와 [wire/consumer 상세](TRADING_REVIEW_ACCOUNT_LIST_WIRE_CONTRACT.md)를 정적으로 반영했다. consumer/Go/Core 구현·실제 수용과 운영 활성화는 별도다.
이 문서의 확정 정책을 과거 DRAFT 표기나 기존 runtime의 미지원만으로 재승인하지 않는다.

## 범위와 결정표

| ID | 확정 정책 | 기존 의미와의 관계 |
|---|---|---|
| AL-P01 | 기존 audience를 유지하고 Account 목록 전용 scope·operation 허용을 분리 | 단건 토큰·기존 `readAllowed`만으로 목록을 허용하지 않음 |
| AL-P02 | page는 int32 호환 범위, size 1~100·기본 0/20. 명시된 query는 엄격하게 검증 | Core Int 범위를 보존하되 빈 값/중복/알 수 없는 query·비정규 숫자 처리는 명시적으로 좁힘 |
| AL-P03 | ACTIVE/ARCHIVED 중 Account 삭제 Guard가 유효한 동일 owner만 items/total에 포함 | 기존 Core tombstone 미참여를 그대로 이식하지 않음. 동일 SQL snapshot과 안정 정렬 |
| AL-P04 | 기존 page envelope·인증 오류 mapping을 선택 적용하고 목록 전용 bounded response를 추가 | 단건 16 KiB cap 유지. 목록 2 MiB는 확정 전송 상한이며 부하/전체 heap 수용 수치가 아님 |

후속 route는 다음 하나다. 신규 URL·POST·HEAD·Book 목록·생성/Import/Worker 권한을 이 채택으로 추가하지 않는다.

| Core public | Go internal | operation |
|---|---|---|
| `GET /trading-accounts` | `GET /internal/trading-review/v1/accounts` | `listOwnerTradingAccounts` |

초기 Core가 browser 인증·중계·Member authority를 담당한다. Go가 현재 owner/Account 삭제 가시성과 목록을 소유한다.
Core가 Go를 기다리는 동안 DB Tx/lock·authority 처리 slot을 보유하지 않고, Go는 Member table을 직접 읽지 않는다.
실제 운영 key/Member/DB history·단일 writer/backup restore 조건은 별도다.

## AL-P01: 목록 전용 위임

서비스 인증·RS256/typ/issuer/key·sub/act·TTL60초/leeway5초·JWT4096 bytes·철회·로그 금지는 ADR-086과 같은 규칙을 적용한다.
브라우저 HS256/CSRF·두 방향 credential 분리·private key의 Core 소유도 유지한다.
새 토큰 필드를 추가하지 않고 정확한 기존 9개 claim 구조에서 아래 **쌍**을 고정한다.
같은 Go 조회 수신 대상을 유지하므로 audience를 기능마다 늘리지 않는다. scope는 문자열 하나를 정확히 비교하며
공백 구분 목록·배열·prefix/wildcard·현재 read scope의 포괄 허용을 받지 않는다.
같은 audience가 토큰의 다른 scope/operation 사용을 허용한다는 뜻은 아니다.
초안의 `refinvest:trading-review:account-list:v1` audience는 채택하지 않았으며 호환 alias로도 허용하지 않는다.

| profile | `aud` 단일 string | `scope` 단일 string | 허용 operation |
|---|---|---|---|
| 현재 ADR-086 | `refinvest:trading-review:owner-query:v1` | `trading-review:owner:read` | 현재 Account/Book 단건 두 GET |
| ADR-087 목록 | `refinvest:trading-review:owner-query:v1` | `trading-review:owner:accounts:list` | Account 목록 GET 하나 |

- Core issuer는 서버가 선택한 operation에 따라 목록 profile을 발급한다. 브라우저의 audience/scope/owner/caller 입력을 받지 않는다.
- Go는 credential에서 식별한 caller에 **Account 목록의 별도 허용**을 요구한다. 기존 read flag를 복제/상속하거나 미지정을 true로 해석하지 않는다.
- 같은 caller의 Core→Go service credential과 기존 signing key를 사용할 수 있지만, operation 허용과 token profile은 별도다.
  새 issuer/key/credential을 목록 때문에 무조건 만들거나 Compute 공통 key를 재사용하지 않는다.
- route가 요구한 고정 profile과 검증된 token의 쌍을 정확히 비교한다. audience와 scope 각각의 union 검사로 교차 조합을 허용하지 않는다.
  단건 token→목록, 목록 token→단건/Book 목록/쓰기 모두 거절한다. 서명된 owner ID만으로 다른 범위를 허용하지 않는다.
- page/size를 새 JWT claim/hash·업무 key로 넣지 않는다. 유효 시간 안의 여러 page 조회/재시도에도 현재 authority를 매번 확인한다.
- 서비스 credential→서명/profile/actor·operation 허용→현재 Core authority→검증된 identity→Go 목록 순서를 유지한다.
  invalid credential/profile에서는 authority를 호출하지 않고, authority 거절/장애에서는 resource SQL을 실행하지 않는다.

Core authority endpoint와 request의 delegation/expectedActor/checkId 구조는 유지한다.
Core authority verifier는 기존 한 쌍과 새 한 쌍을 명시적으로 지원하고 미지원 profile은 active=false로 처리한다.
active=true의 aud/scope는 실제 검증한 쌍을 반환하며 Go는 원 token의 검증 결과와 정확히 비교한다.
단건 전역 상수를 목록 응답에 덮어쓰거나, token/profile을 검증하지 않은 Expected 값을 principal로 받아서는 안 된다.
Core auth의 현재 Member/caller/kid/jti 판정·response binding·재검사/철회 기준은 ADR-086을 따른다.
authority 긍정 결과를 캐시하거나 목록 사이에 재사용하지 않는다. 수치/필드가 같아도 새 profile의 verifier·authority 수용은 필요하다.

## AL-P02: query 입력과 숫자

| query | 미지정 기본값 | 허용 값 |
|---|---|---|
| page | 0 | ASCII `0` 또는 `[1-9][0-9]*`, 0~2,147,483,647 |
| size | 20 | ASCII `[1-9][0-9]*`, 1~100 |

Core public과 Go internal에 같은 값 범위를 적용한다. Go 내부 int64 Query를 int32로 일괄 변경하지 않는다.
Core는 검증된 숫자를 정규 ASCII decimal query로 Go에 전달한다. offset은 checked int64로 계산한다.
Core에서도 곱셈 전에 Long으로 확장한다. 최대 page×size는 214,748,364,700이며 int32 곱셈 후 변환하지 않는다.
Query 순서와 percent-encoded ASCII의 동등 표현 자체를 token identity/hash로 사용하지 않는다.
각 hop은 query를 한 번 정상 decode한 뒤 정확한 key·값·개수를 확인하며 malformed escape/UTF-8를 거절한다.

`page=`·`size=`·중복 key(같은 값 포함)·unknown key(ownerId/sort/cursor 등)·음수·`+1`·공백·소수/지수·선행 0·범위 초과는400이다.
미지정만 기본값이며 빈 값을 미지정으로 바꾸지 않는다. comma/list나 first/last value 선택으로 중복을 해소하지 않는다.
요청 body·transfer encoding·content encoding·trailer, 잘못된 path alias/인코딩은 기존 bounded ingress 원칙에 따라 거절한다.
HEAD를 GET으로 암묵 공개하지 않는다. URI/header 상한·어떤 잘못된 입력에서 auth보다 먼저 거절하는지는 후속 HTTP 설계에 명시한다.

**호환성 차이:** 현재 Core는 `@RequestParam(defaultValue=...)`·Int 변환을 사용하고 query 전체를 검증하지 않는다.
위 엄격한 처리로 바꾸면 framework가 받아들이던 빈 값/일부 숫자 표현·중복/무관 query가 거절될 수 있다.
기존 public OpenAPI는 shared PageParam에 page maximum/format이 없고 목록400도 명시하지 않는다.
후속 schema 반영은 이 operation의 parameter/response를 구체화하며 shared PageParam을 바꿔 다른 목록의 범위를 축소하지 않는다.
현재 소비자 조사와 실제 Core binding 회귀 없이 완전한 호환성을 주장하지 않는다.

public browser 인증 거절은 기존 Core 인증 경로다. 선택 proxy의 public query 검증이 실패하면400을 반환하고 Go를 호출하지 않는다.
유효 query로 Go에 도착한 요청은 기존 서비스/위임·authority 검증 후 DTO를 Query로 변환한다.
body/크기 등 envelope 방어와 사용자 인증 뒤 semantic 입력 검증을 구분하며 SQL을 입력 검증에 사용하지 않는다.

## AL-P03: owner·삭제 가시성과 페이지

- 같은 owner의 ACTIVE/ARCHIVED Account 중 owned Account tombstone이 없는 행만 반환한다. ADMIN도 다른 owner를 보지 못한다.
  DELETION_PENDING/DELETION_FAILED 등 차단된 Account와 물리 삭제된 Account는 items와 total에서 함께 제외한다.
- 다른 owner의 Account와 삭제 자원은 결과 항목/total에 존재를 드러내지 않는다. 개별 resource404/410을 목록 전체 오류로 바꾸지 않는다.
- Raw-only/Book 삭제만으로 살아 있는 Account metadata를 숨기지 않는다. Account 삭제 Guard를 Book/raw generation과 혼동하지 않는다.
- items와 total은 같은 owner/status/tombstone 조건의 한 READ COMMITTED SQL statement snapshot으로 얻는다.
  `createdAt DESC, id DESC`를 적용한다. 일반 목록에 새 `FOR UPDATE`/Scope 잠금을 붙이지 않는다.
- 삭제 commit 뒤 획득한 resource snapshot은 해당 Account를 제외한다. 이미 얻은 snapshot의 제한된 응답까지 전역 즉시 철회한다고 주장하지 않는다.
  여러 page 요청 사이에는 동결/반복 가능한 pagination을 보장하지 않는다.
- 항목 없음·범위 밖 page는 모두200과 `items: []`이며 실제 total을 유지한다. SQL/scan/commit 실패를 정상 빈 목록으로 바꾸지 않는다.
- 목록은 ID/clock·업무 멱등성 row/receipt·job/outbox를 생성하지 않는다. large offset/count 비용은 size 제한만으로 보장되지 않는다.

기존 Go 격리 Reader는 위 방향을 구현/검증했다. 기존 Core 목록은 owner/status 조건은 갖지만 Account tombstone 조건이 없다.
이 차이는 HTTP 연결의 의도된 정합성 변경이며 실제 source·삭제 경합/consumer 수용으로 확인해야 한다.

## AL-P04: 응답·오류·본문 상한

성공은 기존 `items`, `page`, `size`, `total` envelope다. Account의 id/venue/displayName/status/createdAt만 포함한다.
ID는 canonical positive decimal int64 **문자열**, page/size는 검증된 request 숫자, total은0~9,223,372,036,854,775,807의 JSON integer다.
Go/Core는 float64/Double을 경유하지 않는다. `items: null`·누락/unknown/중복 field·부정합 item 수/중복 ID/정렬·request page/size 불일치를 거절한다.
count/offset로 expected item 수를 검증하고 잘못된 upstream을 부분 성공/빈 목록으로 복구하지 않는다.
`total`을 새 string으로 바꾸거나 JavaScript 한계에 맞춰 자르지 않는다. 향후 Web은 큰 정수의 처리 근거를 확보해야 하며
JSON number를 유지한다는 것만으로 browser precision 보존을 주장하지 않는다.

목록 본문 cap의 **상한은 2 MiB(2,097,152 bytes)**다. 기존 단건 metadata는16 KiB를 유지한다.
항목 하나는 기존 단건이 허용하는 검증/16 KiB encoded JSON 경계를 유지하고 최대100개와 envelope를 포함한다.
100×16 KiB와 작은 envelope가2 MiB 이내라는 제한된 크기 근거이며, displayName의 새 생성 규칙/100자 제한을 metadata 읽기에 추가하지 않는다.
전체를 제한 없이 marshal/적재한 뒤 길이만 검사하지 않는다. Go는 값/항목/전체를 제한하고 성공 header를 쓰기 전에 완성한다.
Core는 선언 길이·실제 streaming 길이를 각각 제한하고 header/stream 거절 시 해당 body subscription을 취소한다.
목록 전용 JSON depth/array/token/string 제한을 정의하고 잘못된 UTF-8/JSON·추가 JSON·압축/redirect·예상 밖 header/status를 거절한다.
새 cap을 단건 공통 전역값에 적용하지 않는다. 이 유한 payload 상한은 전체 heap·DB count/offset 비용·동시성/SLO의 증거가 아니다.

성공/오류는 모두 no-store다. trace는 ADR-086과 같은 검증/child 규칙을 사용하고 raw token/사용자 정보/baggage를 전파하지 않는다.
고정 안전 message를 사용하는 기존 code/message/traceId/retryable envelope를 선택 적용한다.

| Go status/code | public Core status/code | retryable |
|---|---|---|
| 400 INVALID_REQUEST | 400 INVALID_REQUEST | false |
| 401 SERVICE_AUTHENTICATION_FAILED / USER_DELEGATION_INVALID | 502 UPSTREAM_SERVICE_ERROR | false |
| 403 CALLER_NOT_ALLOWED | 502 UPSTREAM_SERVICE_ERROR | false |
| 403 MEMBER_ACCESS_DENIED | 403 MEMBER_ACCESS_DENIED | false |
| 503 AUTHORITY_UNAVAILABLE / OWNER_QUERY_UNAVAILABLE | 503 동일 code | true |
| 504 REQUEST_DEADLINE_EXCEEDED | 504 REQUEST_DEADLINE_EXCEEDED | true |
| malformed/unknown/oversized upstream | 502 UPSTREAM_SERVICE_ERROR | false |

Go의 데이터/조립/serialization 실패는 기존 OWNER_QUERY_UNAVAILABLE 계열이다. 목록의 owned 삭제 항목은 제외하므로404/410을 새 operation에 선언하지 않는다.
예상하지 않은 upstream404/410도 안전한502이며 browser 로그인401·WWW-Authenticate/redirect로 중계하지 않는다.
Core 자체 admission/drain 실패는503 OWNER_QUERY_UNAVAILABLE, 전체 budget 만료는504다. 내부401/403을 모두 그대로 반환하지 않는다.
현재 Core public 목록 schema에는 위 오류가 모두 기술돼 있지 않으므로 후속 schema 반영에서 해당 operation에만 명시한다.
재처리 원문 중계 규칙이나 다른 operation의 오류 registry를 전역 변경하지 않는다.

Core는 검증한 page response를 명시 public DTO로 반환한다. 임의 JSON pass-through나 metadata 삭제/변조는 하지 않는다.
선택된 목록 proxy의 실패를 legacy Core DB 조회로 fallback하지 않는다. 기본 비활성/명시 opt-in·준비 실패 시 미공개 원칙을 유지한다.
목록 전용 허용/준비가 없는데 단건 runtime flag가 켜졌다는 이유로 목록 route를 중계하지 않는다.
proxy 미선택 시 legacy를 유지하는 전환 상태와 운영 rollback·권한 차단 보존 여부는 실제 topology/인계에서 별도 판정한다.

## 수용 계획과 적용 순서

아래 AL-A는 **이 정책의 수용 추적 ID**다. 현재 canonical TR-ACC에 실행 성공을 추가한 것이 아니며 전부 미실행이다.
삭제 의미는 [TR-ACC-DELETE-007](TRADING_REVIEW_ACCEPTANCE.md#tr-acc-delete-007--delete-tradingaccount),
일반 authority/취소는 ADR-086의 제한된 snapshot/수명 원칙과 연결한다.

| ID | 필요한 실제 assertion | 상태 |
|---|---|---|
| AL-A01 | Kotlin 목록 issuer→Go verifier→Core authority에서 고정 profile/actor/checkId/hash/aud/scope 일치 | 미실행 |
| AL-A02 | 단건↔목록 token 교차·미지원/초안 audience·잘못된 scope/배열/복수 scope·Book 목록/쓰기 재사용 거절, invalid token은 authority/SQL 미호출 | 미실행 |
| AL-A03 | caller의 기존 read 허용만 있음/목록 미지정·명시 거절이면 목록 거절. actor 위조 거절 | 미실행 |
| AL-A04 | primary Member/caller/kid/jti 차단 commit 전후·authority 장애/불일치/만료 후 SQL 미호출 | 미실행 |
| AL-A05 | 미지정·page0/max·size1/100·빈/중복/unknown·인코딩/숫자 경계, public/internal400과 Core 재구성 일치 | 미실행 |
| AL-A06 | 동일 owner의 ACTIVE/ARCHIVED·foreign/admin·삭제 진행/실패+tombstone·raw/Book-only matrix | 미실행 |
| AL-A07 | items/total 동일 snapshot·동시 Account 삭제·안정 보조 정렬·범위 밖 빈 배열과 실제 total | 미실행 |
| AL-A08 | ID 2^53 초과 무손실·total int64/max/overflow·중복/unknown/missing JSON·echo/count/정렬 검증 | 미실행 |
| AL-A09 | 유효한 최대100개 페이지·단건 cap 초과지만 목록 cap 이내 성공·선언/stream cap+1 실패·물리 취소/slot 재사용 | 미실행 |
| AL-A10 | 부분 body deadline·browser abort·정상/강제 drain·primary pool/연결 정리·실패 시 legacy fallback 없음 | 미실행 |
| AL-A11 | public 오류/trace/no-store·내부 challenge/민감정보 미노출·기존 browser HS256/CSRF·단건 두 GET 회귀 | 미실행 |
| AL-A12 | 계약 sync 후 실제 Main/Core→Go→Core→DB 경로·기존 consumer response·선택 flag 독립·기술 readiness/운영 판정 분리 | 미실행 |

1. AL-P01~04는 명시 채택됐다. 기존 audience 아래 단건/목록의 서로 다른 단일 scope와 caller operation을 정확히 격리한다. 채택 재승인 대신 아래 미구현/미실행 조건을 해결한다.
2. Context에서 선택한 internal 목록 OpenAPI와 Member authority profile pair·public operation의 query/response/error 상세를 갱신한다.
   기존 Account/Book YAML의 더 넓은 미채택 CRUD profile을 목록 구현의 근거로 사용하지 않는다.
   새 draft 파일을 만들면 integration contract map에 분류한 뒤 preview/consumer snapshot을 반영한다.
3. Go의 목록용 ingress/authority/profile·DTO/소비자 조립을 구현한다. Domain/Application에는 JWT/HTTP 객체를 넘기지 않는다.
4. Core issuer/authority·bounded list client/decoder·proxy/Controller와 명시 준비/운영 설정을 연결한다. source/정책 차이를 검증한다.
5. E2E 전 계약 sync(`-IncludeWeb`은 snapshot 검사)를 실행하고 AL-A01~12를 실제 범위별 증적으로 닫는다.
   기존 단건11/일반 Main16/fleet13/transport9의 성공을 새 목록 성공으로 합산하지 않는다.
6. 운영 배포는 실제 신뢰 공급·Member/DB history·TR writer 중단·rollback/restore를 별도로 확인한다.

## 조사한 source와 남은 차이

고정 조사 시점은 Go `e4086b355df2cd8e29e5518c27b86645de3f491e`, Core `5ed414e45c595c93078b465feaeccc9ac72695bc`,
Web `79b11966c37717f8683a124325f40a990cb28197`, Context의 초안 이전 main `8f00652381ef3c81b9fd11b7e4e452c8f98c451c`다.

- [Go 내부 Query/Result](https://github.com/refinvest-dev/trading-review-api/blob/e4086b355df2cd8e29e5518c27b86645de3f491e/internal/modules/tradingrecord/contract/ownerquery/list_accounts.go)·[Application](https://github.com/refinvest-dev/trading-review-api/blob/e4086b355df2cd8e29e5518c27b86645de3f491e/internal/modules/tradingrecord/internal/application/ownerquery/list_accounts.go)·[Reader](https://github.com/refinvest-dev/trading-review-api/blob/e4086b355df2cd8e29e5518c27b86645de3f491e/internal/modules/tradingrecord/internal/adapter/outbound/postgres/ownerquery/list_accounts.go)는 내부 구현이다. 실제 목록 auth/HTTP는 없다.
- [Go ingress](https://github.com/refinvest-dev/trading-review-api/blob/e4086b355df2cd8e29e5518c27b86645de3f491e/internal/modules/tradingrecord/internal/adapter/inbound/delegation/profile.go)와 [authority Expected](https://github.com/refinvest-dev/trading-review-api/blob/e4086b355df2cd8e29e5518c27b86645de3f491e/internal/modules/tradingrecord/internal/port/outbound/authority/check.go)는 단건 pair를 고정한다. route만 추가해서는 목록 profile을 지원하지 못한다.
- [Core Controller](https://github.com/refinvest-dev/core-api/blob/5ed414e45c595c93078b465feaeccc9ac72695bc/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/account/TradingAccountController.kt)는 단건만 proxy를 선택하고 목록은 legacy Application을 호출한다. [page DTO](https://github.com/refinvest-dev/core-api/blob/5ed414e45c595c93078b465feaeccc9ac72695bc/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/pagination/TradingPageResponse.kt)는 page/size Int·total Long이다.
- [Core verifier](https://github.com/refinvest-dev/core-api/blob/5ed414e45c595c93078b465feaeccc9ac72695bc/auth/adapter/security/src/main/kotlin/com/refinvest/core/auth/adapter/security/authority/NimbusAuthorityDelegationVerifier.kt)와 [metadata decoder](https://github.com/refinvest-dev/core-api/blob/5ed414e45c595c93078b465feaeccc9ac72695bc/tradingreview/adapter/reviewapi/src/main/kotlin/com/refinvest/core/tradingreview/adapter/reviewapi/OwnerMetadataResponseDecoder.kt)는 현재 단건 profile/shape/depth·16 KiB cap이다. 목록 전용 pair/array/budget 검증을 별도 수용해야 한다.
- [기존 Account/Book 초안](TRADING_REVIEW_ACCOUNT_BOOK_API_DESIGN_DRAFT.md)·[YAML](../openapi/drafts/trading-review-account-book.yaml)의 넓은 audience/read scope·목록 오류/범위는 이 채택을 확대하는 근거가 아니다.
  public [Account 목록](../openapi/core-api.yaml)의 해당 operation parameter/응답 상세 보완은 후속 작업이다. 현재 wire를 변경하지 않았다.
- Web src/tests에서 목록 전용 구현을 이번 source 검색으로 찾지 못했다. [현재 Web snapshot](https://github.com/refinvest-dev/web/blob/79b11966c37717f8683a124325f40a990cb28197/docs/api/open-api.yaml)은 선언이며 실제 consumer 지원/큰 정수 처리의 증거가 아니다. 외부 consumer의 부재도 입증하지 않는다.


## 확정 범위와 후속 설계

정책으로 확정한 것은 route·audience/scope/operation 권한·query 문법/범위·동일 snapshot 가시성·성공/오류 의미·본문 상한이다.
최대100개·item당16 KiB·목록2 MiB는 구현 중 편의에 따라 확대하거나 size를 잘라 우회하지 않는다.
JSON decoder의 depth/token 제한·함수/파일/typed profile·준비 설정의 물리 형식은 이 유효 입력을 수용하는 범위에서 구현 전에 상세화하고 시험한다.
timeout/pool/동시성/SLO는 별도 측정·운영값이다. 정책 채택을 해당 수치나 운영 key/Member/DB 인계의 승인으로 바꾸지 않는다.
`total`은 기존 JSON integer int64를 유지한다. Go/Core 무손실 전달과 browser precision 보장은 별개이며,
향후 Web consumer는 큰 값을 조용히 반올림하지 않는 lossless decode 또는 명시적 미지원 처리를 검증해야 한다.

후속 [wire/consumer 계약](TRADING_REVIEW_ACCOUNT_LIST_WIRE_CONTRACT.md)에 목록 전용 public/internal schema·정확 scope/authority·정적 검사와 구현 책임을 연결했다. 정적 검사를 AL-A01~12 실제 수용으로 해석하지 않는다.
