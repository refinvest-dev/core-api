# Account 목록 wire·consumer 계약

기준일:2026-11-28. [ADR-087](TRADING_REVIEW_ACCOUNT_LIST_POLICY.md) AL-P01~04를 OpenAPI와 consumer에 연결한다.
정책은 채택됐으며 아래 schema는 미구현/미활성 경계를 표시한다. 정적 검사는 실제 JWT/HTTP/DB·운영 수용이 아니다.
public operation의 `x-go-proxy-runtime-enabled: false`는 새 Go 중계를 뜻한다. 현재 Core legacy 목록 route가 없다는 뜻이 아니다.

## 정본과 범위

| 경계 | 정본 | 상태 |
|---|---|---|
| browser→Core | [core-api.yaml](../openapi/core-api.yaml)의 `GET /trading-accounts` | 기존 public operation의 ADR-087 상세. legacy 구현과 전환/consumer 수용은 후속 |
| Core→Go | [Owner Query](../openapi/drafts/trading-review-owner-query.yaml)의 `GET /internal/trading-review/v1/accounts` | 단건 두 GET에 목록 하나 추가, runtime false |
| Go→Core authority | [Member Authority](../openapi/drafts/trading-review-member-authority.yaml)의 기존 POST | request/URL 유지, active reply의 허용 scope 두 개 명시. 목록 지원 구현 후속 |
| 기존 넓은 CRUD 후보 | [Account/Book DRAFT](../openapi/drafts/trading-review-account-book.yaml) | 목록은 위 Owner Query/ADR-087이 우선. 나머지 CRUD/Book 목록은 이 채택으로 확대하지 않음 |

다른 public operation·shared PageParam/SizeParam/PageMetadata·기존 TradingAccount 단건 schema는 유지한다.
목록 전용 parameter와 TradingAccountPage/TradingAccountListItem/TradingAccountListError를 사용한다.
Compute API·legacy ID/hash/key·새 token claim·DB DDL·업무 receipt/Run은 추가하지 않는다.

## 입력과 변환

public/internal query key는 page/size만 허용한다. 미지정0/20, page0~2,147,483,647·size1~100이다.
OpenAPI의 integer schema는 숫자 범위를, `x-wire-pattern`과 `x-query-profile`은 원 query의 lexical/중복 규칙을 나타낸다.
일반 schema validator나 생성 client만으로 원 query 문법/중복 검증이 끝났다고 주장하지 않는다.

- 한 번 strict UTF-8 decode 뒤 ASCII decimal을 검사한다. malformed percent/UTF-8, 빈 값·중복·unknown key,
  부호/공백·소수/지수·선행0·범위 초과와 잘못된 query field는400이다. 미지정만 default다.
- key 순서·정상 percent-encoded ASCII는 의미를 바꾸지 않는다. decoded key가 같은 중복도 거절한다.
- Core는 raw query를 검증한 뒤 typed Int page/size를 만들고 Go에 정규 decimal로 재구성한다.
  Spring의 first-value/빈 값 default/숫자 변환 뒤에는 원 표현을 복원할 수 없으므로 먼저 정상화하지 않는다.
- Go는 인증/authority와 bounded ingress 규칙 뒤 자기 query를 독립 검증하고 기존 int64 Query에 전달한다.
  public의 잘못된 query는 Core에서 거절하므로 Go 요청을 만들지 않는다.
- Core는 곱셈 전에 Long으로 확장하며 최대 offset은214,748,364,700이다. Go 내부 int64 계약은 축소하지 않는다.

body·transfer/content encoding·trailer·path alias를 허용하지 않는다. HEAD를 GET으로 암묵 연결하지 않는다.
기존 URI/header 방어와 framing 오류 우선순위를 적용하고, bounded semantic 입력 검사에 DB를 사용하지 않는다.
엄격한 문법은 기존 Core가 수용하던 빈/비정규/무관 query를 거절할 수 있는 명시적 호환성 변경이다.

## profile·authority binding

audience는 `refinvest:trading-review:owner-query:v1` 하나다. 단건 두 GET은 `trading-review:owner:read`,
Account 목록은 `trading-review:owner:accounts:list`만 허용한다. 배열/공백 구분 복수 scope·prefix/wildcard·초안 audience alias는 거절한다.
scope가 맞아도 caller의 `listOwnerTradingAccounts` 명시 허용이 필요하다. 기존 ReadAllowed·미지정 값으로 상속하지 않는다.

Core issuer는 서버가 선택한 operation으로 scope를 발급한다. service credential→서명/actor/route scope·operation 허용→
현재 Core authority→검증된 Member→Go Reader를 유지한다. 권위 호출은 업무 Tx 밖이며 긍정 cache/무조건 retry를 사용하지 않는다.

authority request의 delegation/expectedActor/checkId와 request8192/response4096 byte 한계는 유지한다.
active reply의 aud는 위 audience 하나, scope enum은 정확히 두 단일 문자열이다. authority가 실제 검증한 scope를 반환한다.
**schema가 두 scope를 허용한다는 것과 이번 token/route에 scope가 맞는지는 별도다.**
Go는 checkId·원 token byte hash와 sub/actor/iss/aud/scope/jti/exp를 원 검증 결과에 맞춰 모두 비교한다.
서명/profile 실패에서는 authority를, 권위 거절/장애/불일치에서는 resource SQL을 시작하지 않는다.

## 응답·decoder 계약

성공은 정확히 items/page/size/total이다. item은 id/venue/displayName/status/createdAt만 포함한다.
ID는 canonical positive int64 string이며 server가 upper bound도 확인한다. total은 nonnegative int64 JSON integer다.
Core Int/Long·Go int64를 Double/float64로 중간 변환하지 않는다. raw JSON numeric exponent/fraction을 integer 필드에 자동 수용하지 않는다.
browser는 total의 큰 정수를 조용히 반올림하지 않는 처리 근거가 필요하다. 새 string field나 임의 count cap으로 보정하지 않는다.

page/size는 요청과 일치하며 items 수는 `min(size,max(0,total-page*size))`다.
동일 owner/status/Account tombstone의 한 SQL snapshot·createdAt DESC/id DESC·중복 ID 금지를 적용한다.
다른 owner/차단된 Account는 items/total에 포함하지 않는다. Raw-only/Book 삭제는 살아 있는 Account 전체 제외가 아니다.
빈/범위 밖 page는200·[]·실제 total이다. 불일치/DB 오류를 부분/빈 성공으로 고치지 않는다.

목록 본문 cap은2,097,152 bytes, encoded item은16,384 bytes·최대100개다. 단건 cap16,384 bytes는 유지한다.
OpenAPI의 x-max-encoded-bytes는 JSON schema만으로 검증되지 않는다. Go는 값·각 encoded item·누적 bytes를
한정한 뒤 성공 header를 쓴다. Core는 선언/stream 길이·UTF-8·JSON depth/token/string/array 제한을 검사한다.
공백/escape를 포함한 실제 수신 bytes의 상한과 모델을 재직렬화한 크기를 혼동하지 않는다.
중복/unknown/missing JSON field·잘못된 shape/status/header·압축/redirect/challenge·무효 숫자를 거절한다.
decoder depth3·token4096은 Go 적용 문서의 기술안이며 실제 유효100개/escape/크기 경계로 확인해 고정한다.
전체 cap을 넘긴 뒤에만 무제한 수신/할당을 중단하거나, header 거절 Future 완료를 물리 body 취소로 간주하지 않는다.

모든 성공/오류는 no-store다. 목록의 owned 삭제 항목은 제외하므로 정상 오류로404/410을 선언하지 않는다.
내부401/서비스권한403→public502, 실제 Member 거절403, authority/owner 실행 불가503, deadline504를 적용한다.
예상하지 않은 upstream404/410·malformed/unknown/초과 응답도502다. 기존 browser 인증401은 별도이며 내부 challenge를 전달하지 않는다.
각 status의 code/message/retryable·strict 오류 envelope와 예제는 YAML의 목록 operation에 고정했다.
재처리 원문 중계나 다른 public operation의 오류를 전역 변경하지 않는다.

## consumer 구현 책임과 적용 순서

| 소비자 | 구현할 경계 | 필요한 실제 검증 |
|---|---|---|
| Core auth | 목록 issuer scope·두 scope authority verifier/실제 scope 회신, 기존 key/Member 상태 수명 | AL-A01~04, 기존 단건/HS256/CSRF·철회/장애 회귀 |
| Go | route가 선택한 profile/permission·authority binding, raw query→Query, 목록 DTO/제한 serializer·module 조립 | AL-A01~09, 실제 PG/취소/모듈 경계 |
| Core proxy/web | strict public query·명시 목록 선택, bounded list client/decoder·typed DTO·deadline/drain·오류 mapping | AL-A05/08~11, 물리 body 취소/slot 재사용·legacy fallback 없음 |
| Web/외부 consumer | 기존 page envelope·안전한 숫자 처리·400/403/502/503/504 및 abort 의미 | AL-A08/11/12, 실제 사용/호환성. snapshot 선언만으로 사용을 입증하지 않음 |
| integration | 계약 sync 후 실제 Main 목록 경로·고정 source/정리·범위별 증적 | AL-A01~12, 단건/Main/fleet/transport와 별도 집계 |

Context 계약→snapshot/consumer 검토→Go/Core의 명시 구현→실제 E2E 순서다.
정책을 재승인하지 않으며 현재 unsupported runtime을 억지로 활성화하거나 선택된 proxy 실패에 legacy fallback을 추가하지 않는다.
E2E 전 `verify-contract-sync.ps1`을 실행하며 Web 포함이면 `-IncludeWeb`을 사용한다. 현재 작업은 snapshot을 직접 apply하지 않았다.
운영 key/Member 초기화·DB history·TR writer 중단·rollback/restore·부하/SLO는 별도 gate다.

## 정적 검사와 미실행 범위

[기존 entrypoint](../scripts/verify-trading-review-owner-query-draft.py)가 [목록 contract checker](../scripts/account_list_contract.py)를 호출한다.
기존 단건16개 응답 예제/5개 mutation을 유지하고 목록의 schema/예제·query/reference 관계·scope/오류/크기 변경을 검사한다.
[authority checker](../scripts/verify-trading-review-member-authority-draft.py)는 기존 synthetic fixture를 변경하지 않고
목록 scope schema와 schema-valid 응답의 scope binding 불일치를 구분한다.

이는 signed JWT 검증·실제 Core state/SQL·HTTP 입력·stream/subscription·duplicate JSON member 수신 검사의 실행이 아니다.
Python reference의 compact UTF-8 모델 크기·정적 byte 경계는 실제 Go/Core encoder/전체 heap/네트워크·운영 예산 측정도 아니다.
기존 AL-A01~12는 이 정적 결과로 완료 처리하지 않으며 실제 구현 단계별 증적을 연결한다.
