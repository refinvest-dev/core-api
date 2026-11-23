# Core Member Authority 구현 경계

상태: 2026-11-24 ADR-086의 authority 상태·판정·전용 HTTP 경로 상세 설계. 명시 준비한 capability와
격리 PostgreSQL/HTTP 검증이 범위다. 실제 DB migration/회원 일괄 허용·secret 공급/업무 proxy 활성화는 별도다.
정본은 [ADR-086](https://github.com/refinvest-dev/context/blob/8f00652381ef3c81b9fd11b7e4e452c8f98c451c/docs/TRADING_REVIEW_OWNER_QUERY_AUTH_POLICY.md)이다.

## 책임과 수명

Core auth가 Member access policy·caller/key 활성·jti 철회를 소유한다. role/Member 존재만으로 허용하지 않는다.
권위 판정은 primary의 한 READ COMMITTED statement snapshot이며 token 시간도 그 시점에 검사한다.
Reader는 외부 Spring Tx를 거절하고 autoCommit/READ_COMMITTED connection·primary 여부를 검사한다.
Store는 반대로 같은 datasource의 실제 Tx 참여를 요구한다. 일반 조회/저장 fallback으로 섞지 않는다.

security adapter는 신뢰 snapshot의 RSA public key와 Nimbus/strict JSON으로 원 위임을 검증한다.
Application은 검증→primary Reader→binding된 Result 순서다. HTTP는 inbound capability만 소비하며
원 token/raw error를 반환하지 않고 request/response DTO를 명시 변환한다. Go resource Guard는 별도다.

## 상태와 제어

Member policy는 1:1 auth control record이며 신규 업무 Aggregate/Snowflake ID를 만들지 않는다.
Member/caller/key는 등록 시 기본 차단·positive revision1이고 expected revision CAS로만 허용/차단한다.
Member row 부재/접근 row 부재/차단·caller/kid/jti 철회는 inactive, policy/schema 미준비·DB 장애는 unavailable이다.
권위 schema version/ready는 별도 제어 row로 판정한다. 오래된 replica/cache나 JWT expiry만으로 대신하지 않는다.

현재 최대 Flyway source version21을 확인했다. 실제 인계 history/권한·backup restore가 미확인이라
V22를 자동 추가/적용하지 않는다. 새 DDL은 명시 실행용 candidate SQL이며 격리 fixture에서만 실행한다.
기존 Flyway source/checksum을 변경하거나 live DB에서 baseline/repair하지 않는다.

registerMember는 기본 차단을 만들고 기존 row를 덮지 않는다. 실제 signup에서 같은 Tx 등록·승인된
초기 허용과 기존 회원의 증거 있는 backfill을 조립/검증하기 전 활성화하지 않는다. 운영 회원을 role/존재만으로
일괄 허용하지 않는다. 차단 commit→Go 데이터 삭제/FK 인계→물리 탈퇴의 운영 orchestration은 후속이다.
FK는 Member 물리 삭제를 RESTRICT한다. 제어 Port는 신뢰된 내부 소비자용이며 새 admin HTTP API가 아니다.

## HTTP와 인증

POST /internal/trading-review/v1/delegations/check만 대상으로 service-only stateless chain을 분리한다.
Go 전용 opaque credential은 최소32bytes 엔트로피의 신뢰 snapshot으로 검증하며 browser JWT/Compute key와
분리한다. 인증된 caller는 trading-review-api로 고정하고 원 expectedActor/위임 act.sub와 혼동하지 않는다.
cookie/session을 authority 자격으로 사용하지 않는다. CSRF 제외는 이 좁은 path chain에 한정한다.

필수 capability Bean 둘을 명시 공급해야 controller/filter chain이 등록된다. 이번 변경은 production에
그 Bean/secret을 자동 공급하지 않으며 Go 업무 route·Core proxy도 활성화하지 않는다.
request는8192bytes·field3개/duplicate/unknown/UTF8/추가JSON을 검사하고, JWT4096bytes·header3/claims9/
act.sub·scalar audience·integer time·TTL60/leeway5를 검증한다. response는4096bytes/no-store·정확한 binding이다.

기존 issuer와 같은 RSA2048~4096/E65537·issuer512/kid/jti128 UTF8 bytes의 초기 지원 범위다.
새 public key는 immutable 복사 snapshot으로 준비한다. token URL/discovery를 따라가지 않는다.
입력·lookup/결과의 toString과 오류는 raw token/key/subject/jti/hash를 노출하지 않는다.

## 검증과 활성화 gate

실제 PostgreSQL의 허용/차단 commit 전후·rollback/CAS·누락 state/ready/schema·Member FK·kid/caller/jti 철회,
statement snapshot 중 동시 차단과 token 시간 경계를 검증한다. HTTP는 서비스 자격/브라우저 쿠키·CSRF 분리,
strict input·오류/no-store·active/inactive 정보 비노출을 실제 Spring security/MVC 경로로 검증한다.
기존 auth/browser·issuer·module boundary 회귀를 유지한다. fixture는 loopback refinvest_integration의
새 schema만 만들고 지우며 운영 DB에는 쓰지 않는다.

SQL query timeout은 Reader 생성 때 유한 값으로 주입하고 값은 운영 측정 전 후보로 둔다. 실제 callback
capacity/전체 request budget·TLS termination·제어 권한/감사·키 교체/회수·production Go verifier 연결과
현재 authority→Go owner/parent/Guard·업무 E2E는 후속이다. 문서/CI를 위 운영 수용으로 해석하지 않는다.
