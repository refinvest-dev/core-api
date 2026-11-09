# Member Authority Protocol (DRAFT)

상태: ADR-074의 **미채택·미구현** 보완안. [조회 계약 초안](TRADING_REVIEW_OWNER_QUERY_CONTRACT_DRAFT.md)의
권위 Member 확인을 구체화한다. 기존 공개 Core/Compute API, Member 모델, logout, Go route를 바꾸지 않는다.
[authority OpenAPI 초안](../openapi/drafts/trading-review-member-authority.yaml)을 함께 검토한다.

## 실제 Core와 설계 차이

Core revision `272b6b90bb1136ad598ab8eb8f70ec8aa6228c6b`의 Member는 id/role/createdAt이고 role은
MEMBER/ADMIN이다. 접근 차단 상태·authorization revision은 없다. GetMemberService의 존재 확인만으로
차단·철회까지 구현했다고 하지 않는다. JwtAuthenticationTokenIssuer의 access JWT에는 familyId가 없고
LogoutService는 refresh family를 철회한다. 원래 access token의 jti와 새 위임의 jti도 서로 다르다.

제안은 Core auth가 별도의 **MemberAccessState**와 caller/key/위임 jti의 권위 철회 상태를 소유하는 것이다.
MemberAccessState의 허용/차단·positive revision은 Member role과 별개다. role=ADMIN도 다른 owner의
데이터를 읽을 수 없다. 이 문서는 논리 계약만 제안하며 table/backfill/migration을 생성하지 않는다.

## 호출과 자격 분리

```text
Core/Gateway -- caller credential + 사용자 위임 --> Go
Go: 로컬 검증 -- Go 전용 Core-target credential --> Core authority
Core: 원 위임 재검증 + 현재 접근/철회 판정 --> Go: owner/parent/삭제 Guard 조회
```

Core는 이 경로에 한정한 service 인증 chain을 구성하고 브라우저 cookie를 자격으로 받지 않는다.
기존 브라우저 API의 CSRF를 전역 해제하지 않는다.

새 endpoint는 Core 소유 `POST /internal/trading-review/v1/delegations/check`다. Go→Core credential의
등록 주체는 trading-review-api이고 Core authority용 최소 권한만 갖는다. Core→Go credential,
브라우저 JWT secret, Compute key를 전달하거나 재사용하지 않는다. TLS server identity를 검증하고
redirect·임의 discovery·환경 proxy를 따라가며 토큰을 전달하지 않는다.

요청은 원 compact JWT `delegation`, 원 Go ingress에서 **인증한 caller ID** `expectedActor`, Go가 매
검사에 만든 128-bit random hex `checkId`만 받는다. raw memberId/ownerId/role을 받지 않는다.
Core는 최대 8192 bytes의 단일 JSON object만 받고 중복/알 수 없는 field와 extra JSON을 거절한다.
JWT에서 subject를 다시 검증해 act.sub=expectedActor를 검사한다. 현재 authority caller인
trading-review-api와 원 위임 actor인 core-api/gateway를 혼동하지 않는다.
이 JSON profile은 active-state 질의를 참고한 별도 내부 계약이며 RFC 7662의 form endpoint를 구현한
것은 아니다. [RFC 7662](https://www.rfc-editor.org/rfc/rfc7662.html)

## 판정과 응답

Core는 원 위임의 RS256/type/issuer/key/audience/시간/scope를 검증한 뒤 현재 Member 존재·접근 상태,
원 actor와 authority caller의 활성 상태, kid와 위임 jti의 철회를 함께 확인한다. 실제 구현에서는
하나의 READ COMMITTED statement snapshot 또는 동등하게 입증한 판정 단위를 사용한다.
지연된 read replica나 registry의 옛 local cache만으로 enabled/revoked를 판정하지 않는다. 정상 조회와 제어 경로의 권위 상태
초기화가 끝나지 않았거나 읽기에 실패하면 503이다.

- Member 부재/차단, MemberAccessState 부재, caller/kid/jti 철회, 유효하지 않은 위임: `200 active=false`.
- 필요한 policy schema/version 미준비, DB/control-store 장애: `503 AUTHORITY_UNAVAILABLE`.
- authority 자체의 Go service credential 오류/권한 부족: 내부 401/403. Go에서는 503으로 거절한다.

active=false에는 active/checkId/tokenSha256/checkedAt 네 필드만 둔다. 이유·Member 존재·신원·role을
노출하지 않는다. active=true에는 검증한 sub/actor/iss/aud/scope/jti/exp와 Member policyRevision을
추가한다. 모든 응답은 no-store이며 tokenSha256은 원 compact token bytes의 SHA-256이다.
이 hash는 인증 증명이나 외부 멱등성 key가 아니며 로그에 남기지 않는다.

Go는 status/media type/size·중복/알 수 없는 JSON field를 검사하고 checkId·hash·모든 active identity를
자기가 검증한 요청 값과 비교한다. 날짜는 UTC date-time이고 exp는 손실 없는 integer다. policyRevision은
canonical positive int64 string이다. active=false는 MEMBER_ACCESS_DENIED, 나머지 장애/불일치는
AUTHORITY_UNAVAILABLE로 거절한다. 둘 다 resource lookup을 진행하지 않는다.

매 요청 새로운 checkId로 동기 확인하고 positive decision을 캐시·재사용하지 않는다. checkedAt이나
policyRevision을 권한 token/장기 lease로 사용하지 않는다. checkedAt은 관측용이다. nonce는 예전 wire
응답의 재사용을 검출하지만 fresh DB 판정의 암호학적 증명은 아니다. 현재 정책의 신선함은 Core의
권위 snapshot 구현/검증에 달려 있다. 남은 request budget을 지키고 맹목적 application retry를 하지 않는다.

## 철회 시점과 이미 시작한 요청

접근 허용의 기준 시점은 **Core의 권위 상태 판정 snapshot**이다. token 시간 조건도 이 시점에
유효해야 하며 서명 검증과 DB 판정 사이에 만료된 위임을 임의로 연장하지 않는다. Member 차단/actor 비활성/kid 또는
jti 철회가 commit된 뒤 얻은 snapshot은 active=true를 반환할 수 없다. 판정이 먼저 끝났다면 이후
철회가 commit되어도 이미 허용한 제한된 조회는 끝날 수 있다. HTTP 응답 완료를 포함한 전역 즉시
철회나 Core/Go 사이의 하나의 DB Tx를 보장하는 계약이 아니다.

Go의 owner/parent/삭제 Guard는 별도이며 resource SQL snapshot에서 다시 확인한다. 권위 허용 응답은
Book 삭제를 무시할 권한이 아니다. Member 탈퇴는 접근 차단 commit을 먼저 확정하고 Go의 기존
Account subtree 삭제 workflow와 FK 인계를 거쳐 Member 물리 삭제로 이어져야 한다. 실제 orchestration,
실패 복구·state 초기화/backfill·Core transaction 경계는 구현/검증 gate다.

## 현재 logout과 v1 철회 범위

v1 제안은 Member 전체 접근 차단, caller/kid 비활성, 특정 위임 jti 철회를 다룬다. 기존 refresh-family
logout을 모든 access/위임 토큰의 즉시 철회로 자동 해석하지 않는다. familyId가 없는 access token에서
로그아웃 대상 family를 추정하거나, 한 기기 logout을 Member 전체 BLOCKED로 바꾸지 않는다.
origin session과 위임을 연결하는 별도 계약/상태를 도입하기 전에는 session 단위 즉시 위임 철회를
주장하지 않는다. 위임 자체의 짧은 expiry/profile 검증은 계속 적용한다.

## Budget과 호출 순환 방지

authority RPC 1초(상한 2초), JSON reply 4096 bytes를 제안한다. 기존 Go active budget 5초와 더 짧은
Context를 사용하고 DB transaction 밖에서 호출한다. 값은 측정 전 초안이다. redirect/무제한 body,
재시도 증폭·positive cache로 장애를 숨기지 않는다. trust/credential getter는 신속한 신뢰 snapshot
조회여야 하며 Go Context가 임의의 비협조 함수까지 강제 중단하지는 못한다.

초기 Core→Go→Core는 서비스 간 callback이다. Core proxy는 DB Tx/row lock·authority에 필요한 semaphore를
잡은 채 Go를 기다리지 않는다. authority에 처리 여유를 격리하고 finite budget/동시성 제한·부하 검증을
수행한다. authority가 다시 Go를 호출하거나 public proxy route로 우회하는 순환은 금지한다.
직접 구현 전에는 단순 모듈 import DAG만으로 이 callback의 안전성을 증명했다고 하지 않는다.

## 구현·검증 순서

1. [키 운영 보완안](TRADING_REVIEW_DELEGATION_KEY_OPERATIONS_DRAFT.md)과 함께 계약을 검토한다.
2. Core auth의 권위 상태·위임 issuer·credential registry·endpoint와 migration/초기화를 구현한다.
3. 실제 서명·잘못된 issuer/actor·철회 commit 전후·Core proxy callback 부하/장애를 검사한다.
4. Go의 verified principal→authority→기존 owner query를 연결하고 snapshot/계약 전파·HTTP E2E를 검증한다.

Go의 이번 실험은 synthetic token fixture와 실제 TLS transport/binding만 검증한다. Core/JWT/DB authority의
구현, signing key rotation의 실제 성공, Member 차단의 실제 commit 검증이나 production readiness를
뜻하지 않는다. ADR-074는 계속 DRAFT이고 DB baseline도 보류한다.
