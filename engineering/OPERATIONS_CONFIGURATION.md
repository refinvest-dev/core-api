# Core Operations Configuration

6b/R15는 공개 health 정보와 Core writer의 Snowflake node ID 설정을 다룬다. 업무 API·schema·계산·hash 규칙은
변경하지 않으며 Snowflake epoch·bit layout·sequence·clock 검증도 유지한다.

## Health

기본 health는 `show-details: never`, `show-components: never`를 사용한다. 익명 요청이나 access cookie,
개발용 scrape credential이 있어도 DB·디스크·예외 진단 정보를 반환하지 않는다. 응답에는 aggregate `status`와
활성화된 표준 `liveness`/`readiness` group 이름만 포함될 수 있다. Contributor 장애는 계속 DOWN/503으로 반영한다.
상세 노출 제어는 [Spring Boot health API](https://docs.spring.io/spring-boot/api/rest/actuator/health.html)를 따른다.

`dev-observability`도 health 상세를 다시 켜지 않는다. Prometheus의 loopback 바인딩·전용 credential·인가와
기존 업무 API 인증/CSRF는 유지한다. Endpoint의 공개 목록을 넓히거나 새 진단 API를 만들지 않는다.

## Node Assignment

- 모든 실행에서 `SNOWFLAKE_NODE_ID` 또는 명시적 `refinvest.id.node-id` 설정이 필요하다. 전역 기본값은 없다.
  범위는 0~1023이며 누락·빈 값·정수가 아닌 값·범위 밖 값은 기동을 실패시킨다. 테스트 설정의 0은 테스트 전용이다.
- 같은 ID 공간에 쓰는 동시 Core 인스턴스에는 서로 다른 node ID를 배정한다. 단일 개발 인스턴스도 0 등을
  명시해야 한다. Node ID를 PID·시각·임의 hostname hash로 추정하지 않는다.
- 고정한 같은 시각에도 서로 다른 node ID의 값은 겹치지 않는다. 그러나 같은 node ID를 두 프로세스에 배정한
  운영 실수는 단일 JVM 설정 검증으로 탐지할 수 없다. 분산 registry/lease allocator를 이번 변경에 추가하지 않는다.
- 재시작·rolling 배포에서는 node ID별 실행 인스턴스를 관리하고 이전 writer 종료를 확인한다. 같은 node ID의
  재사용에는 시계 정상화도 필요하다. 기존 generator의 clock rollback 검증을 해제하거나 기다림으로 감추지 않는다.

PowerShell의 단일 인스턴스 예시는 다음과 같다. DB·JWT 등 기존 설정은 별도로 제공한다.

```powershell
$env:SNOWFLAKE_NODE_ID = '3'
.\gradlew.bat :app:bootRun
```

## Integration

Integration의 예제/CI 환경은 primary node 0을 명시한다. 다중 Core fixture는 별도 Spring 설정으로 secondary 1,
canary 2를 사용한다. 실제 적용 node ID를 프로세스 identity에 포함하므로 설정이 바뀌면 실행 중인 프로세스를
재사용하지 않는다. 변경된 설정으로 재기동하며 상세 절차는 Integration README를 따른다.

## Deployment and Rollback

새 코드 배포 전에 writer별 node ID를 할당하고 환경에 반영한다. 기본값 0에 의존하던 기존 환경은 먼저 갱신한다.
DB migration이나 기존 ID 변환은 없다. Rollback에서도 명시적 node 할당과 health 상세 제한을 유지한다.

## Verification

실행 설정을 읽는 기동 검증은 node ID 누락·잘못된 값·양 끝 경계를 검사한다. 동일 millisecond의 두 node가
생성한 512개 ID의 비중복, 공개 health의 정상/장애 신호와 상세 차단, 개발 metrics 인가도 검증한다.
실제 JAR와 후보 CI 결과는 작업 계획 및 연결 PR에 기록하며 credential·로컬 증적은 커밋하지 않는다.
