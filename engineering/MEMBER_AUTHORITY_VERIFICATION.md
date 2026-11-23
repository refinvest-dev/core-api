# Member Authority 상태·판정·HTTP 검증

상태: 2026-11-24 Core의 명시 준비 capability와 격리 SQL/Spring MVC 경로 구현·검증.
[상세 설계](MEMBER_AUTHORITY_IMPLEMENTATION.md)와 [ADR-086](https://github.com/refinvest-dev/context/blob/8f00652381ef3c81b9fd11b7e4e452c8f98c451c/docs/TRADING_REVIEW_OWNER_QUERY_AUTH_POLICY.md)을 따른다.
운영 회원 초기화·migration·서비스 credential/TLS·Go production 연결·업무 route 활성화는 미완료다.

## 구현과 소유권

[Application](../auth/application/src/main/kotlin/com/refinvest/core/auth/application/authority/CheckMemberAuthorityService.kt)은
서명/profile 검증 후 [primary Reader](../auth/adapter/persistence/src/main/kotlin/com/refinvest/core/auth/adapter/out/persistence/JdbcMemberAuthorityReader.kt)를 한 번 호출한다.
SQL은 primary 여부·schema ready/version·Member/caller/key/jti·token 시간을 동일 READ COMMITTED statement
snapshot에서 판정한다. 외부 Spring Tx는 거절하며 autoCommit/READ_COMMITTED connection과 유한 query timeout을 요구한다.

[제어 Store](../auth/adapter/persistence/src/main/kotlin/com/refinvest/core/auth/adapter/out/persistence/JdbcMemberAuthorityControlStore.kt)는
같은 datasource의 실제 write Tx·등록 기본 차단·expected revision CAS·jti 철회 보존 상한 연장만 제공한다.
다른 datasource의 Tx나 read-only Tx를 Pool fallback으로 사용하지 않는다. 제어 HTTP/승인·감사 운영은 별도다.
MemberAccess는 security control record이고 새 업무 Aggregate/ID가 아니다.

[Core verifier](../auth/adapter/security/src/main/kotlin/com/refinvest/core/auth/adapter/security/authority/NimbusAuthorityDelegationVerifier.kt)는
기존 Nimbus 서명 검증과 Jackson strict duplicate/closed field·UTF8·integer/scalar profile을 사용한다.
public key·credential은 복사한 신뢰 snapshot이며 토큰 URL discovery나 원문 logging을 하지 않는다.
이는 Go production verifier를 구현한 것이 아니다.

[Controller](../auth/adapter/web/src/main/kotlin/com/refinvest/core/auth/adapter/web/authority/MemberAuthorityController.kt)와
[service-only chain](../auth/adapter/web/src/main/kotlin/com/refinvest/core/auth/adapter/web/authority/MemberAuthoritySecurityConfiguration.kt)은
inbound capability 둘을 명시 공급할 때만 등록된다. 준비 Bean을 production에 추가하지 않았다.
원 위임 actor와 고정 authority caller를 분리하고 browser cookie/session을 자격으로 받지 않는다.
CSRF 제외는 exact authority path에 한정한다. 정상/거절/error 모두 no-store이며 내부 challenge를 내보내지 않는다.

## 실제 실행 결과

| 검사 | 최종 확인 |
|---|---|
| Application | authority3 포함 auth23 tests 통과 |
| Security | strict verifier/credential5 + 기존 issuer6, 총11 tests 통과 |
| 실제 PostgreSQL·Spring HTTP | PostgresMemberAuthorityTest12 tests 모두 실행/통과, skip0 |
| Auth module boundary / 기존 producer | 3+1 tests 통과 |
| 최종 관련 재검증 | 총50 tests 통과, fail/error/skip0 |
| 기존 browser/application 회귀 | 앞선 실행에서 RefinvestApplicationTests51 통과. 최종50에 중복 합산하지 않음 |
| 모듈 의존 | verifyAggregateModuleBoundaries 통과 |

초기 malformed Unicode fixture와 중복 Authorization 오류 경로에서 실패를 발견·수정했다. 실제 escaped
wire와 OAuth2 converter 오류 처리로 검사하며, 중복/공백 Bearer에 안전한401·WWW-Authenticate 부재를 확인했다.
최종 테스트 결과와 초기 실패를 성공으로 혼동하지 않는다. raw token/key/DB 원인은 외부 오류에 노출하지 않는다.

## SQL·HTTP assertion 범위

[실제 PostgreSQL 테스트](../app/src/test/kotlin/com/refinvest/PostgresMemberAuthorityTest.kt)는 loopback
refinvest_integration의 무작위 새 schema에만 DDL/fixture를 만들고 닫힌 pool·검증한 이름으로 제거한다.
기존 schema/회원·운영 DB를 쓰거나 읽기 조사 결과로 backfill하지 않는다.

- Member 존재/ADMIN role·누락 access row는 허용 근거가 아니며 등록 기본 차단을 확인했다.
- uncommitted block 중 이전 snapshot은 허용하고, 차단 commit 뒤 새 판정은 거절한다. global 즉시 철회 주장이 아니다.
- CAS stale/중복 등록·rollback, caller/authority caller/kid/jti 철회와 jti 보존 시각 단축 방지, Member FK RESTRICT를 확인했다.
- schema/ready/version·DB 관계 부재는 unavailable, 만료된 token은 signature 선행 허용과 별개로 DB 시점에서 거절한다.
- Store의 Tx 없음/read-only/다른 datasource Tx와 Reader의 caller Tx를 거절했다.
- 실제 서비스·verifier·primary Reader와 Spring Security/MVC를 조립해 active의 full binding과 inactive의4 fields만 반환함을 확인했다.
- cookie/브라우저 JWT·wrong/중복/공백 service 자격 거절, 기존 browser path CSRF403, malformed/duplicate/unknown/크기/추가JSON400을 확인했다.
- invalid 위임은 inactive/DB lookup0회, authority 미준비는 안전한503/retryable로 구분했다.

## DDL와 활성화

[candidate SQL](../auth/adapter/persistence/src/main/resources/sql/member-authority-candidate.sql)은 테스트에서만
명시 실행했다. Flyway V1~21·기존 checksum/history는 바꾸지 않았다. production JPA Entity/Bean이나
V22 migration·자동 all-member grant/새 secret/env는 추가하지 않았다.

이 초기 지원 범위는 RSA2048~4096/E65537·issuer512/kid/jti128 UTF8 bytes와 static immutable credential/key
snapshot이다. 실제 공급/reload/교체·제어 권한/감사·signup의 같은 Tx 등록/허용·승인된 legacy backfill·
탈퇴/Go FK 인계·migration history/backup restore는 활성화 전 조건이다. read replica/failover 환경의 실제 검증도 남아 있다.

이번 HTTP는 실제 Spring MVC/Security + PostgreSQL 경로이며 network/TLS E2E가 아니다. Go client와의
실제 TLS/callback 부하·취소/deadline·생산 profile 수용·Go owner/parent/Guard·업무 API 연결을 실행하지 않았다.
Core→Go 대기 중 Tx/lock/authority 자원을 보유하지 않는 proxy 구현과 운영 capacity는 후속이다.
계약 sync가 필요한 E2E를 실행한 것으로 보고하지 않는다. 기존 browser51 회귀/CI도 위 미실행 범위를 대체하지 않는다.
