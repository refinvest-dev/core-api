# Owner Query 위임 발급기 설계

상태: ADR-086의 첫 Core 구현 범위를 위한 상세 설계(2026-11-23). 인증된 사용자에 대한 위임 발급 capability를
명시 생성으로 준비한다. 자동 component/Bean·proxy/HTTP route·Member authority·secret 공급/배포는 후속이다.
정본은 [Context ADR-086](https://github.com/refinvest-dev/context/blob/8f00652381ef3c81b9fd11b7e4e452c8f98c451c/docs/TRADING_REVIEW_OWNER_QUERY_AUTH_POLICY.md)이다.

## 입력과 책임

외부 request에서 Member/caller/scope/audience/TTL을 받지 않는다. Application은 기존
CurrentMemberIdProvider의 인증된 Member와 Clock·새 UUID jti를 사용한다. 초기 actor는 core-api이며
두 metadata 단건 GET용이다. Gateway·목록/쓰기·Worker profile을 허용하지 않는다.

새 Use Case는 execute()와 Result만 필요하다. 실제 입력이 없어 빈 Command를 만들지 않는다.
Application은 작은 OwnerQueryDelegationSigner와 그 Payload만 의존한다. signer는 owner 접근 허용이나
Member 존재/차단을 판정하지 않는다. 최종 Go 요청은 현재 authority와 owner/parent/Guard를 통과해야 한다.

| 위치 | 책임 |
|---|---|
| auth:port inbound/delegation | 발급 Use Case·token과 발급/만료 시각 Result |
| auth:port outbound/delegation | signer/Payload·고정 TTL·안전한 내부 실패 분류 |
| auth:application delegation | 인증 identity 취득·초 단위 시각·새 jti·발급 호출/Result |
| auth:adapter:security delegation | Nimbus RS256·엄격한 발급 profile·준비한 키 snapshot 사용 |
| app test | 실제 Application+security 조립과 Go consumer용 공개키/서명 vector 생성 |

## JWT·키 snapshot

현재 사용 중인 Nimbus JOSE + JWT를 사용한다. Nimbus JWSObject/Payload로 JSON의 aud를 단일 string,
NumericDate를 integer로 고정한다. 브라우저용 Spring JwtEncoder/HS256 key/decoder를 교체하지 않는다.
Nimbus의 [RSA JWS API](https://connect2id.com/products/nimbus-jose-jwt/examples/jws-with-rsa-signature)를 사용하며
서명 알고리즘이나 JSON 직렬화를 자체 구현하지 않는다. 수신 JWT parser는 이 단계에 추가하지 않는다.

첫 구현은 export 가능한 JCA 소프트웨어 RSA 키의 immutable 복사 snapshot을 받는다. RSA 2048~4096-bit,
E=65537을 지원하고 public/private modulus 일치와 준비 시 실제 서명/검증을 확인한다. 이는 채택 정책의
최소2048보다 좁은 구현 지원 범위이며 다른 키·HSM/KMS는 미지원이다. issuer≤512 UTF8 bytes,
kid/jti≤128 UTF8 bytes는 구현 자원 제한이다. 유효 Unicode·비어 있지 않은 값·제어 문자 금지를 검사한다.

외부 I/O 없는 신뢰된 getter로 매 발급에 snapshot 하나를 받는다. 매 호출 새 signer/JWS 객체를 사용한다.
snapshot 교체 후 발급은 새 kid를 쓰고, 구 public key의 검증 유지·retire/revoke는 별도 운영 gate다.
키 생성/파일/환경변수·자동 reload·key registry 배포를 제공하지 않는다. 잘못된 snapshot·getter/signing
실패는 raw 원인/cause 없이 안전한 내부 오류로 거절한다. token/키의 toString은 redacted다.

발급은 TTL60초·nbf=iat·정확한 header3/claims9/act.sub·4096bytes 상한을 지킨다. UTC Instant는
Application에서 초로 절삭하고 signer는 정수 초·positive Member/epoch·유효 jti를 검증한다.
leeway5초는 verifier 수락 규칙이며 발급 만료를 65초로 늘리지 않는다.

## 조립과 활성화

이 준비 단계는 필수 signer Bean이 없어 기존 Core startup이 실패하지 않도록 자동 등록하지 않는다.
명시 생성한 Service/Signer를 실제 소비자와 연결할 때 :app composition root에서 Bean을 구성한다.
Application·security adapter의 상호 concrete 의존이나 HTTP→outbound 직접 호출을 만들지 않는다.
기존 일반 application component scanning 관례를 전역 변경하는 정책이 아니다.

Core authority state·primary snapshot·service-only endpoint·두 방향 credential/키 운영·Go production
검증기와 proxy 연결까지 갖추기 전 업무 route는 활성화하지 않는다. Core의 기존 Trading Review
writer 중단·실제 DB/ID 인계 gate도 별도다. 토큰 발급 성공을 resource 접근 허용으로 사용하지 않는다.

## 검증 기준

Core unit은 정확한 profile/크기·큰 Member ID·입력/키 실패·키 snapshot 전환·redaction을 검증한다.
Application test는 기존 principal 사용·초 단위 Clock·매번 새 jti·실패 전파를 검증한다.
app의 producer test는 실제 서비스/signer로 token을 만들고 테스트 메모리에서만 private key를 생성한다.
build/delegation-interop/vectors.json에는 testOnly 표기와 public key·가상 사용자 token/negative vector만
내보낸다. private key/실제 사용자/서비스 credential은 내보내거나 커밋하지 않는다.

Go 실험 테스트는 새 Kotlin 산출물과 고정된 공개 fixture 양쪽을 검증한다. wrong issuer/audience/type/
actor/kid·서명 변조·HS256·duplicate claim·만료 경계를 거절하고 authority stub 호출 여부를 확인한다.
이는 실제 Kotlin 서명 호환 증거이며 production Go JWT library·실제 Core authority/HTTP/TLS·차단 commit·
rotation 운영 수용을 대신하지 않는다. 기존 browser authentication/CSRF·module boundary 회귀도 실행한다.
