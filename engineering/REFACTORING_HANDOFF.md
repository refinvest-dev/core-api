# Architecture Refactoring Handoff

## Scope and Completion

Trading Review 후속 구조 정리 [Core #113](https://github.com/refinvest-dev/core-api/pull/113)~
[#120](https://github.com/refinvest-dev/core-api/pull/120)도 main에 반영됐다. #120의 merge commit은
`a4f5717`이다. Port 방향·기능별 Service·resource별 Web, 일반 JPA Aggregate 저장과
Normalization/Deletion/grant 실행 책임의 재점검은 [최신 Audit](ARCHITECTURE_AUDIT.md)의
2026-11-04 기록을 따른다. 최초 0~6c-3의 검증 이력과 이번 후속 구조 검증을 구분한다.

아키텍처 리팩토링 실행 계획에 열거한 0~6c-3 코드 변경 PR은 모두 머지됐다.
최초 계획의 마지막 [Core #110](https://github.com/refinvest-dev/core-api/pull/110)은 `9f9046d`로 main에 반영됐다.
[Integration #49](https://github.com/refinvest-dev/integration/pull/49)도 `801241f`로 main에 머지됐으며,
그 안에 #50의 node 설정 변경이 포함된다. 후보 브랜치 검증과 main 조합 검증을 구분해 기록한다.

완료 범위는 Aggregate/typed ID/불변식, Aggregate Store/Mapper, 계층·feature 의존성,
잠금·CAS·발행·삭제와 원본 정책, migration/실행 설정 및 회귀 검증 체계다. 향후 Analytics/Reprocessing
신규 기능이나 실제 운영 배포를 완료했다는 의미가 아니다. 업무 HTTP/OpenAPI·schema·계산 hash 의미를
이번 인계에서 바꾸지 않으며 계산 결정론 영향은 없다.

## Structure Alignment Main Verification — 2026-11-04

후속 구조 정리가 병합된 main 조합을 다음 커밋으로 고정했다.

| 저장소 | 고정 main 커밋 |
|---|---|
| Core | `a4f5717abe3d2c4987912d64e218503b5273505c` |
| Context | `0bcdf79c46ef1d3085f27a68383b88865084f0b3` |
| Compute | `113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963` |
| Web | `9354dfe321ee8543c10e0914249f06d6643ca6db` |
| Integration | `801241f89dd16cef06fd55c3653c15bc70de134f` |

- [Core main CI](https://github.com/refinvest-dev/core-api/actions/runs/41804175117): 31개 테스트 모듈·573개
  테스트, 실제 PostgreSQL 10개 suite·154개, 전체 46개 모듈 경계·bootJar·결과 gate 통과.
  실패·오류·skip은 0개다.
- [main 계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41804703217): 통과.
- [main API/Web·전체 Trading CI](https://github.com/refinvest-dev/integration/actions/runs/41804707189): API
  smoke·브라우저 11개와 전체 Trading raw-policy/schema fixture 통과. Artifact의 다섯 커밋이 위 ref와
  일치하며 raw 정책 시계 4개·삭제 count 2개·version pin 8개가 모두 PASS다. Process control 13개는
  모두 종료 코드 0이고 worker recovery event는 0개다. Runtime fixture는 483.552초다.
- Integration preflight는 3개 통과·Linux에서 Windows 전용 1개 skip이다. Core skip 0개와 구분한다.
  Compute 0017 단일 transaction 진단은 SQLSTATE `55006`으로 rollback되며, 기존 `psql -f`
  autocommit 경로의 ORCH-027 PASS를 이 배포 제약의 해소로 해석하지 않는다.

이번 재점검에서는 R21/R22의 Asset·Strategy 공유 모델 소유권과 Web 오류 의미 경계를 추가 보완한다.
필드·enum·실행 구문은 import/package와 주석을 제외하고 변경 전 17개 파일과 동일함을 대조했다.
이 보완은 위 main의 검증 대상에 포함되지 않으며 이번 PR의 Core 검사·로컬 전체 회귀와 구분한다.
API·Domain·DB schema·계산 hash와 결정론 영향은 없다. Asset series 비공개 정책도 유지한다.

보완 구현은 `3b5c95a`이며 [Core #121](https://github.com/refinvest-dev/core-api/pull/121)에서 검토한다.
이 PR의 병합 전에는 R21/R22가 main에 반영됐다고 취급하지 않는다. 추가 제품 기능이나 운영 배포는
아래 미확인 항목과 별도 범위다.

## Audit Branch Verification

- 구현 커밋의 [Core CI](https://github.com/refinvest-dev/core-api/actions/runs/41809047855)는 31개 모듈·575개
  테스트와 PostgreSQL 10개 suite·154개, 46개 모듈 경계·bootJar·결과 gate를 통과했다.
  실패·오류·skip은 0개다. main의 573개에 전 feature port/Web 경계 검사 2개가 추가됐다.
- 로컬 전체 실행은 Backtest PostgreSQL fixture의 wildcard import 누락, Member application·Strategy domain·
  Trading Review Compute 테스트 worker의 Windows 소켓 listen/bind 오류로 종료 코드 1이었다.
  Import를 바로잡고 네 범위만 재실행했다. 재실행 2회는 Gradle daemon 시작 전 같은 소켓 오류로 실패했고,
  마지막 제한 재실행은 종료 코드 0이었다. 최종 JUnit gate는 31개 모듈·575개, PostgreSQL 154개와
  실패·오류·skip 0개를 확인했다. 최초 전체 명령이 한 번에 성공한 것으로 표시하지 않는다.
- 로컬 변경 전후 Context 문서 26개·두 OpenAPI·Normalization/Analytics 고정 vector 동기화도 확인했다.
  문서만 추가한 최종 HEAD의 필수 CI와 한글 본문 검증은 #121에서 확인한다. 위 main 통합 증적을 이번
  branch에서 API/Web·전체 Trading fixture를 재실행한 것으로 표시하지 않는다.

## Initial Merged Main Verification — 2026-11-03

2026-11-03 검증 시작 시 각 저장소의 main HEAD를 아래 커밋으로 고정했다. 이후 main 이동과 무관하게
이 조합의 결과만 아래 검증 증적으로 해석한다.

| 저장소 | 고정 main 커밋 |
|---|---|
| Core | `9f9046d5f21f8445d90f68a6b65ce748bdbf7512` |
| Context | `0bcdf79c46ef1d3085f27a68383b88865084f0b3` |
| Compute | `113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963` |
| Web | `9354dfe321ee8543c10e0914249f06d6643ca6db` |
| Integration | `801241f89dd16cef06fd55c3653c15bc70de134f` |

- [Core main CI](https://github.com/refinvest-dev/core-api/actions/runs/41608632716): 30개 테스트 모듈·514개 테스트,
  실제 PostgreSQL 10개 suite·144개, 전체 46개 모듈 경계·실행 JAR·결과 gate 통과. 실패·오류·skip은 0개다.
- [main 계약 동기화 CI](https://github.com/refinvest-dev/integration/actions/runs/41609198236): 통과.
- [main API/Web·전체 Trading CI](https://github.com/refinvest-dev/integration/actions/runs/41609202075): API smoke·
  브라우저 11개와 전체 Trading raw-policy/schema fixture 통과. Artifact의 다섯 커밋이 위 고정 ref와
  일치하며 raw 정책 시계 4개·삭제 count 2개·version pin 8개 시나리오가 PASS다. Process-control 13개는
  모두 종료 코드 0이며 worker 자동 복구 없이 통과했다. Runtime fixture는 489.68초다.
- Integration 사전 process-control 검사는 3개 통과·Windows 전용 1개 skip이다. Core 회귀의 skip 0개와
  구분한다. Compute 0017 단일 transaction의 SQLSTATE `55006` rollback 진단도 여전히 남아 있으며,
  기존 `psql -f` 경계의 migration/replay 통과를 해당 제약 해소로 해석하지 않는다.
- 로컬 계약·Context 문서 snapshot 26개와 Normalization/Analytics 고정 vector도 일치했다.

당시 인계 PR은 Core 로컬 문서만 변경했다. 문서 후속 커밋의 최신 Core 검사는 당시 PR check에서 확인했으며,
위 main 코드의 통합 검증을 문서 커밋에서 재실행한 것으로 표시하지 않는다. 실제 운영 credential,
fixture·evidence 산출물 및 로그는 커밋하지 않는다.

## Deployment Evidence Still Required

CI는 분리된 PostgreSQL과 비운영 합성 fixture를 사용한다. 대상 운영 환경의 아래 증거는 아직 확인하지
않았으며, 해당 환경의 담당자가 기존 전환 절차에 따라 확보해야 한다.

| 항목 | 적용 전 필요한 증거 | 절차·소유 경계 |
|---|---|---|
| 기존 DB 전환 | 대상 DB의 실제 Flyway/DDL 이력, 복구 가능한 백업과 복사본 업그레이드 결과, 데이터·index·sequence·OID 보존 | [Schema Migration Rollout](SCHEMA_MIGRATION_ROLLOUT.md)의 deployment/rollback 절차. CI의 새 DB·합성 legacy 검증이 대상 DB 증거를 대신하지 않는다. |
| Writer ID 배정 | 동시에 쓰는 모든 Core 인스턴스의 고유 node 배정, 이전 writer 종료, 같은 node 재사용 시 재시작 전후 시계의 안전 근거 | [Operations Configuration](OPERATIONS_CONFIGURATION.md). 분산 중복 할당 탐지와 durable timestamp high-water mark는 구현하지 않았다. |
| 실행 설정과 노출 | 대상 환경의 명시적 node 설정, health 상세 비노출·metrics 인가, rollback 시에도 validate/노드 배정 유지 | 같은 운영 설정 문서와 schema 전환 문서. CI HTTP 검증과 실제 배포 환경 점검을 구분한다. |
| Compute 0017 | 선택한 migration 실행 경계의 rehearsal 결과와 rollback 확인 | Compute 소유 SQL이다. 기존 `psql -f` 경계와 단일 transaction 실행은 동등하지 않으며, 후자는 SQLSTATE `55006` 진단이 남는다. Core 리팩토링으로 해결됐다고 표시하지 않는다. |

운영 전환을 이어갈 때는 대상 환경·배포할 커밋·DB 이력·writer 목록을 먼저 확정한다. 이 문서는 실제
배포나 DB 변경을 실행하지 않으며, 새로운 allocator 또는 Compute migration 수정은 별도 범위로 검토한다.
