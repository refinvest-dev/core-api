# Core Schema Migration Rollout

6a/R14는 Hibernate가 생성하던 Core 테이블을 Flyway에 편입하고 기본 `ddl-auto`를 `validate`로 전환한다.
업무 모델·API·계산·hash 의미는 변경하지 않는다. 기존 V1~V17의 SQL과 checksum은 유지한다.

Spring Boot의 Flyway starter를 실행 모듈에 연결한다. 기존 `flyway-core` 의존성만으로는 Boot 4의 자동 설정이
로드되지 않아 실제 JAR가 migration을 실행하지 않았다. Starter가 Flyway 초기화를 연결하고 Hibernate 검증보다
먼저 실행하게 한다([Spring Boot 공식 DB 초기화 문서](https://docs.spring.io/spring-boot/how-to/data-initialization.html)).

## Ownership

| 대상 | 소유 migration |
|---|---|
| Member | 기존 V1 |
| Subscription | V18 |
| SocialIdentity·RefreshSession | V19 |
| Strategy·Version·Condition | V20 |
| BacktestRun·Result·ComputeDispatch | V21 |
| quota·capacity·reservation / refresh family fence | 기존 V11 / V12 |

새 SQL은 기존 JPA 매핑과 같은 테이블을 `CREATE TABLE IF NOT EXISTS`로 정의한다. 기존 row·index·constraint·
identity sequence를 다시 만들거나 재번호화하지 않는다. 기존 관계 외에 feature 간 FK를 추가하지 않는다.
BacktestResult의 `@Lob String`은 PostgreSQL OID를 유지한다. 기존 payload를 TEXT로 변환하거나 다시 직렬화하지 않는다.

## Deployment

1. 대상 DB의 Flyway 이력과 현재 매핑을 확인하고 복구 가능한 백업 및 복사본에서 업그레이드를 검증한다.
   V17 이력이 있는 설치는 V18~V21을 적용한다. 실행 중인 구 Hibernate `update` 인스턴스의 schema 변경이
   겹치지 않도록 배포를 조율한다.
2. Flyway 도입 전 JPA 테이블만 있는 설치는 기존 baseline 0 경로를 사용한다. V11의 활성 Run 소유권 검증과
   예약 복원을 보존한다. 이미 Trading 테이블이 있지만 Flyway 이력이 없는 별도 상태는 이 경로로 추정하지 않는다.
   실제 적용 이력과 SQL을 확인한 뒤 처리하며, 임의 baseline 17이나 `repair`로 실패를 숨기지 않는다.
3. 새 application은 Flyway migration 뒤 Hibernate `validate`로 기동한다. 테이블이 존재하더라도 누락 열·다른
   타입 등은 자동 보정하지 않는다. 실패 원인에 맞는 명시적 후속 migration을 검토한다.
4. 기존 구독·인증 token family·불변 StrategyVersion·BacktestResult·dispatch/lease·quota/capacity가 보존되는지
   확인하고 새 생성·조회·version 추가·결과 복원을 검증한다. `validate`는 모든 index·constraint를 검사하지 않으므로
   이 값들은 DB catalog 비교와 별도 무결성 검증으로 확인한다.

## Rollback

V18~V21 테이블과 Flyway 이력·identity sequence·OID payload를 유지한다. 데이터나 schema history를 삭제해
되돌리지 않는다. 구 application으로 복귀하더라도 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`를 유지해
자동 DDL이 다시 켜지지 않게 한다. 이전 schema의 누락·타입 차이는 검토된 후속 migration으로 처리한다.

## Verification

`CoreSchemaMigrationTest`는 실제 PostgreSQL의 실행별 schema에서 다음을 검증한다.

- 빈 DB의 V1~V21 적용과 전체 JPA 매핑 validate, unique/FK 및 identity 생성.
- V17 + 기존 Hibernate schema의 row·index 식별자·sequence 위치·예약·lease·quota 보존과 OID 한글 payload 복원.
- Flyway 도입 전 JPA schema의 baseline 0 업그레이드와 V11의 활성 Run만 예약 복원.
- 잘못된 기존 schema의 기동 거절과 자동 보정·row 재작성 부재, migration 재실행의 no-op.
- Boot 자동 설정의 migration 실행 뒤 전체 매핑 validate. 수동 Flyway 테스트만 통과하고 실제 기동에서는
  migration이 빠지는 회귀를 별도로 검사한다.

실행에는 분리된 `refinvest_integration` DB와 `CORE_SCHEMA_TEST_JDBC_URL`, `CORE_SCHEMA_TEST_DB_USER`,
`CORE_SCHEMA_TEST_DB_PASSWORD`가 필요하다. 로컬 credential과 실행 증적은 커밋하지 않는다.

최종 app 테스트 104개(실제 PostgreSQL 28개)는 실패·오류·skip 없이 통과했다. 실제 JAR의 빈 DB 기동·
재기동·전략/버전 HTTP 생성·기존 버전 보존도 검증했다.
[후보 CI](https://github.com/refinvest-dev/integration/actions/runs/41422406898)는 Core `d5c9b02`와
Integration `17e459f`의 일반 API/Web 및 전체 Trading fixture를 모두 통과했다. 전체 fixture의 runtime은
473.776초이며 worker 복구 없이 ORCH-024~028·삭제 count·정책 시계·canary·migration replay를 통과했다.

[Core #106](https://github.com/refinvest-dev/core-api/pull/106) →
[Integration #49](https://github.com/refinvest-dev/integration/pull/49) 순서로 main 반영을 마쳤다. 수동 fixture의 초기화
주체와 선택형 Linux CI 실행 방법은 Integration README를 따른다.
머지 후 main 조합 검증과 대상 운영 DB의 미확인 전환 증거는 [Refactoring Handoff](REFACTORING_HANDOFF.md)를 따른다.
