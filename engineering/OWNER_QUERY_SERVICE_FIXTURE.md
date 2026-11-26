# Owner Query 서비스 E2E fixture

전용 `app` test classpath의 `com.refinvestfixture.ownerquery.OwnerQueryServiceFixtureKt`는
실제 RefinvestApplication을 실행하면서 synthetic issuer/authority/reader를 명시 등록한다.
Controller·Spring Security·RS256 signer/verifier·primary JDBC authority·Core→Go client는 생산 구현을 사용하고 mock하지 않는다.
생산 Core entrypoint/bootJar에는 포함되지 않으며 운영 key 공급·회원 초기화·route 활성화 설정의 대체물이 아니다.

`./gradlew :app:prepareOwnerQueryFixture`는 testClasses를 컴파일하고 `app/build/owner-query-fixture-classpath.txt`를 생성한다.
integration harness가 생성한 파일을 `REFINVEST_OWNER_QUERY_FIXTURE_FILE`로 전달하며 파일/키/env를 커밋하지 않는다.
fixture는 loopback `refinvest_integration`과 `tnorm_<12hex>` schema, loopback bind만 허용한다.
생산의 무조건 등록 spool sweeper는 fixture에서 제외하고, 다른 TR worker도 등록되지 않았는지 확인한다.
JVM 임시 디렉터리는 harness가 새 실행 폴더 안으로 제한한다. 전역 temp spool을 사용하지 않는다.
키·인증 자격·Go origin/CA는 한 번 읽은 immutable snapshot이다. 부분 준비 실패는 startup 실패이며 기존 DB 조회로 우회하지 않는다.

전체 실행·계약 sync·schema/키 준비·process 소유/종료·증적은 integration harness가 소유한다.
Core의 실제 production Bean 공급, DB migration/인계·Core TR writer 운영 중단·backup restore·부하/rotation 수용은 별도다.
