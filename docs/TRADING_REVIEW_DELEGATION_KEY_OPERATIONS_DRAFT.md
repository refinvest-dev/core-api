# 위임 키 발급·교체·철회 (DRAFT)

상태: ADR-074의 미채택 운영 보완안. [Member authority](TRADING_REVIEW_MEMBER_AUTHORITY_DRAFT.md)와
함께 검토한다. secret/key·새 env·배포 설정을 만들거나 기존 브라우저/Compute 인증을 변경하지 않는다.

## 분리할 자격과 소유권

| 자격 | 소유/사용 | 대체하면 안 되는 값 |
|---|---|---|
| Core/Gateway→Go caller credential | Go 대상 service registry, caller별 최소 권한 | Go→Core key·브라우저/Compute key |
| Go→Core authority credential | Core 대상 registry, trading-review-api만 확인 권한 | 원 inbound Authorization |
| RS256 위임 signing private key | Core auth의 별도 issuer, Go에는 전달하지 않음 | browser HS256 secret |
| issuer/kid 검증 public key snapshot | Go·Core의 신뢰 registry | JWT header의 임의 URL/key |
| Member/caller/kid/jti 권위 철회 상태 | Core auth control store | pubkey cache·토큰 expiry만의 판정 |

위임 발급자는 인증된 Member와 실제 전달 caller를 사용한다. owner/actor/role request field를 받아
임의 위임을 발급하지 않는다. 새 jti를 만들고 read scope·대상 audience·짧은 expiry를 고정한다.
현재 Core의 브라우저 HS256 issuer가 이 issuer를 이미 제공한다고 가정하지 않는다.

## Signing key 상태와 정상 교체

1. 새 RSA key/kid를 생성한다. 최소 2048-bit이며 kid는 issuer 안에서 재사용하지 않는다.
   private key는 issuer secret store에만 두고 public key만 배포한다.
2. PREPARED key를 신뢰 snapshot에 넣어 Go/Core 검증자에 먼저 배포한다. 전체 snapshot을 검증해
   원자적으로 교체하고 잘못된 snapshot은 거절한다. 필요한 revision을 받지 못한 node는 drain한다.
3. 검증자 배포 확인 후 authority control store와 issuer를 새 SIGNING kid로 전환한다.
   원래 key는 VERIFY_ONLY로 두며 새 token을 발급하지 않는다.
4. 마지막 구 key 발급 시각에 최대 TTL·양방향 skew·진행 요청 예산을 더한 뒤 구 key를 retire한다.
   초안 값 60+2×5+5=75초는 보수적 예시이며 clock/latency 측정과 운영 계약으로 확정한다.
5. 구 private key의 사용 중단/접근 회수·검증 key 제거 결과를 확인한다. rollout/ack/회수 완료를
   단순한 파일 배포 요청 접수로 선언하지 않는다.

임의 JWT jku/x5u/jwk를 fetch하지 않는다. kid unknown이면 거절하고 browser key/다른 issuer로 fallback하지
않는다. reload 실패·issuer 먼저 전환·구 token 만료 경계·node 일부 실패를 검증한다. 배포된 코드와
secret manager가 아직 없어 이 절차를 실제 실행했다고 보고하지 않는다.

## 긴급 철회

권위 store의 kid REVOKED 또는 actor/member 차단을 먼저 commit하고 추가 발급을 중단한다.
새 authority 판정은 inactive여야 한다. stale pubkey를 가진 Go도 online authority를 생략하지 않으므로
철회를 무시한 새 허용을 만들지 않는다. 이어 검증 key/credential snapshot을 전파하고 미확인 node를
drain한다. 모든 node의 registry 회수 완료와 Core 판정 commit을 다른 증거로 기록한다.
이미 판정/접수된 제한된 read의 반환을 되돌렸다고 하거나 전역 무지연 철회를 약속하지 않는다.

특정 위임 jti 철회는 원 exp+허용 skew까지 재등장할 수 없도록 유지한다. 삭제/만료 cleanup은 해당
철회 보장 뒤에만 수행한다. token/전체 jti·subject/hash를 application log에 남기지 않고 허용된
운영 code·registry revision·checkId 정도로 진단한다. 정확한 audit 접근/보관은 기존 lifecycle을 따른다.

## Service credential 교체·장애

두 방향의 새 credential은 각각 32~96 random bytes를 unpadded base64url(43~128 ASCII 문자)로
발급한다는 profile을 제안한다. 값의 정규화/대체 없이 registry의 정확한 값으로 비교한다.
caller ID는 유지하고 새 credential을 registry에 먼저 등록한다. 호출자는 신뢰된 secret snapshot을
새 값으로 교체하고 적용을 확인한 뒤 구 credential을 회수한다. 일시적으로 두 credential을 허용해도
새 actor ID를 만들거나 scope를 넓히지 않는다. 각 방향의 key는 다른 target에서 거절되어야 한다.

Go→Core credential 오류는 authority unavailable이며 owner 조회를 허용하지 않는다. inbound key나
사용자 토큰으로 재시도하지 않는다. Core→Go key의 회수/actor 차단과 authority caller 비활성도 각각
시험한다. secret getter는 신속·비차단이고 실패 시 원문 key를 반환 오류/로그에 넣지 않는다.

## 채택에 필요한 증거

실제 Core issuer의 서명·신뢰 snapshot·동시 rollout/ack·권위 store commit·jti 만료·Member 탈퇴 연계,
기존 browser logout과의 구분·callback 부하/장애·Go 검증/authority/owner 조회를 함께 검증한다.
이번 Go fixture는 synthetic delegation과 TLS transport·service credential 교체만 다룬다. signing key
발급·교체·철회의 crypto/DB 성공을 대체하지 않는다. authority code/migration과 운영 담당·rollback을
구체화한 뒤 ADR-074의 채택 여부를 판단한다.
