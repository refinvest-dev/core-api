# Owner Query snapshot 교체·fleet 수용

기준일:2026-11-28. Context ADR-086·PCP-12의 key·양방향 credential 순차 교체를 synthetic fleet에서 검증했다.
[실제13개 시나리오 증적](https://github.com/refinvest-dev/integration/blob/e380b0faedf15f4f9e8fbe1e515bb0ca04b89883/evidence/owner-query-service/2026-11-28-fleet.json)은 Core `0f8491d21e63ed5534ed59cc7749b0fe400ac27a`·Go `67e5e0df6885a2619b5c484a1f2ee5d58e7cd6cf`·
harness `208889a01af8d867aa8d620600d304d2e1182a6b`의 clean tracked source와 실제 binary/source SHA를 고정한다.
운영 전환 판정은 계속 **NO_GO**다. 기존 test launcher11개·[일반 Main16개](OWNER_QUERY_MAIN_VERIFICATION.md)와 합산하지 않는다.

## 조립과 지원 절차

Core bootJar2개·Go Main2개는 같은 service build에 서로 다른 불변 snapshot을 적용했다. 초기 trust·중첩 trust·새 trust가
섞인 구성 세대의 수용이며 구/신 binary 호환성 증거가 아니다. 일반 opt-in root와 실제 issuer/authority/primary DB를 쓴다.
고정 loopback TLS relay가 metadata/authority 원 요청과 실제 응답을 전달하며 판정·서명을 대체하지 않는다.
정상 matrix10회는 각각 ingress Core2×Go2×authority Core2의8조합에서 Account/Book 응답과 실제 두 hop 각16개를 확인했다.
운영 routing·fleet inventory·처리량을 이 fixture로 승인하지 않는다.

| 적용 순서 | 실제 확인 |
|---|---|
| verifier 선배포 | Core authority의 새 public key/callback overlap, Go ingress overlap과 serving verifier 준비 |
| 미확인 node 제외 | readiness200인 구 Go도 새 kid 요청은 내부401/public502, authority 호출 없음. 정상 routing에서 제외하고 새 snapshot 검증 후 복귀 |
| 발급/자격 전환 | Core A/B의 구·신 signer/ingress 자격과 Go A/B의 구·신 callback 자격 조합 성공 |
| VERIFY_ONLY | 마지막 구 signing Core drain/종료 후 실제 Core 발급의 아직 유효한 구 token을 모든 Go/authority 조합에서 수락 |
| 손상된 교체 입력 | 파일 mutation 뒤 기존 process 정상 유지. 신규 Core SNAPSHOT·Go resources 오류로 nonzero exit/READY 미공개 |
| 현재 권위 | Member·원 caller·구 kid·특정 jti 차단 commit 뒤 거절, fresh jti 정상. 재허용은 명시 fixture SQL이며 자동 복원이 아님 |
| retirement | TTL60·양방향 skew 각5·fixture request12의 보수적 실제 대기 후 새 값만 남김. 구 자격401, 새 자격·새 위임 성공 |
| 구 key 공격 | 철회 전 구 private key의 fresh 공격 서명은 검증될 수 있음. primary kid 차단 뒤403, public key 회수 뒤 authority 호출 전401 |
| 정리 | Core routing 제외→표준 인증/CSRF shutdown·complete 로그/exit. relay·소유 process 종료 뒤 소유 schema 제거 |

## Core 소유권과 수명

신뢰 파일 변경은 요청 중 reload/전환이 아니다. runtime이 준비된 signing key/검증 set·방향별 자격을 계속 사용한다.
새 프로세스의 전체 snapshot을 검증하고 실제 요청으로 확인한 뒤 serving에 넣는다. 손상된 신규 파일을 이전 key/env로
자동 fallback하지 않는다. 이 검증의 replacement는 기존 다른 node가 계속 serving하는 순차 재시작이다.
개별 Core process의 무중단 reload나 자동 배포 controller를 추가하지 않았다.

`VERIFY_ONLY`는 구 issuer의 발급 중단을 뜻한다. 탈취된 private key의 새 서명을 거절하는 별도 signing cutoff registry가
생긴 것은 아니다. 긴급 철회는 현재 primary authority의 kid 차단 commit과 추가 발급 중단·검증자 전파를 함께 수행한다.
구 private material의 보관/폐기·감사·secret supplier는 운영 조건이다. token/jti/hash는 application log/trace/evidence에 두지 않는다.
jti hash와 exp+leeway 보관 시점은 fixture authority DB에만 기록했다.

retirement의82초 산식은 이 fixture의 TTL·skew·request budget 조합이며 운영 기본값이 아니다. 같은 host의 실제 시계를 썼고
clock-skewed host·운영 LB/미확인 node 전파·긴급 차단 리허설은 검증하지 않았다. TLS CA/server leaf 교체도 별도다.
Go cleanup의 Windows 강제 종료를 graceful drain으로 보고하지 않는다. active read/callback 종료 순서는 별도 일반 Main 검증을 따른다.

## 검사와 남은 조건

integration 안전 검사9개와 계약 sync(-IncludeWeb)·실제13개 시나리오가 통과했다. 최초 개발 실행의 공격 probe는 iat/exp만
갱신하고 nbf를 유지해 서버가 올바르게 거절했다. fixture 시간 profile을 수정·회귀 검사한 뒤 고정 source로 다시 실행했다.
Core 업무/조립 코드는 변경하지 않았다. 정확 PR head의 기존 Core/PostgreSQL CI는 별도로 확인한다.

실제 key/credential 공급·ACL/KMS·보호 mount·폐기/복원·회원 초기화/탈퇴·DB history/legacy ID/FK·Core TR 단일 writer 중단·
backup restore·production routing/구신 binary/부하 수용은 남아 있다. PCP-12의 한정 구성 세대 수용으로 운영 gate를 닫지 않는다.
후속 [실제 body/취소 수용](OWNER_QUERY_TRANSPORT_VERIFICATION.md)과 [운영 인계/후속 구현 분리](OWNER_QUERY_ACTIVATION_READINESS.md)를 따른다. 운영 활성화 전에 별도 판정한다.
