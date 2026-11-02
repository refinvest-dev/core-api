# Deletion Count Snapshot Rollout

5c-2/R18의 요청 시점 count와 Core V17 전환 절차다. 정본은 Context의
`TRADING_REVIEW_API.md`·`TRADING_REVIEW_PERSISTENCE.md`와 `AffectedResourceSummary`다.

## Behavior

- Account→Book→Session의 기존 command 잠금과 동일 transaction에서 owner/scope inventory를 한 SQL로 집계한다.
  Count·snapshotStatus·tombstone·DeletionRequest·idempotency 결과는 함께 commit 또는 rollback된다.
- 일곱 count는 logical scope에 포함된 자원 수다. 파일 수나 실제 제거된 row 수가 아니다. Raw-only는 artifact 1개만,
  subtree는 해당 scope의 Session/artifact/Revision과 구현된 종속 Run을 포함한다. 중첩 요청의 수는 합산하지 않는다.
- 현재 Core에 저장 모델이 없는 Analysis/Reprocessing Run은 0이다. NormalizationRun은 Session 소속이므로
  analysisRuns에 포함하지 않는다. 해당 Aggregate가 구현될 때 inventory query도 확장해야 한다.
- GET·purge 후 replay·remediation은 저장된 count를 사용한다. Mapper는 I/O 없이 값만 변환하고 count는
  component 부재 checkpoint나 삭제 완료의 증거로 사용하지 않는다.

## Legacy Data

V17은 기존 네 count·state·version·attempt와 삭제 진행 증거를 변경하지 않는다. 새 세 count의 0은
LEGACY_PARTIAL의 placeholder이며 정확한 부재가 아니다. 과거 요청을 현재 live row로 재집계하지 않는다.
정본의 선택형 `affectedResources.snapshotStatus`는 누락도 LEGACY_PARTIAL로 해석한다.

## Review and Verification

- [Context #45](https://github.com/refinvest-dev/context/pull/45) →
  [Core #105](https://github.com/refinvest-dev/core-api/pull/105) /
  [Web #47](https://github.com/refinvest-dev/web/pull/47) →
  [Integration #48](https://github.com/refinvest-dev/integration/pull/48) 순서로 반영한다.
- Core 관련 테스트 277개(실제 PostgreSQL 70개 포함)와 후보 커밋 조합의
  [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41405900708)를 통과했다.
- 실제 publication이 있는 Book과 나머지 두 Book을 가진 Account의 purge 완료·primary 부재·동일 DELETE
  replay를 검증했다. 일곱 count와 REQUEST_TIME_SNAPSHOT은 요청 응답부터 purge 후 replay까지 동일했다.
- Core `0d589cd`·Integration `b9471b5` 조합의 전체 raw-policy E2E는 종료 코드 0으로 통과했다.
  Session/raw-only count·정책 시계·ORCH-024~028·migration/replay도 포함한다. Worker의 Windows 소켓 오류
  복구 2회와 실패한 이전 실행은 별도 로컬 로그로 보존했다.

## Deployment

1. Context 계약을 먼저 반영하고 Core/Web snapshot과 구현·Integration 검증을 연결한다. Snapshot은 정본
   commit으로부터 동기화하고 revision provenance를 확인한다.
2. 삭제 요청·remediation writer의 전환 범위를 확인하고 V17을 적용한다. 새 코드는 V17 열이 필요하다.
3. 관련 writer를 새 코드로 전환한다. 구 writer가 생성한 요청은 DB 기본값 LEGACY_PARTIAL을 가진다.
   기존 exact snapshot을 remediation에서도 모두 보존하려면 remediation writer까지 함께 전환해야 한다.
4. 분리 DB와 합성 자료에서 일곱 count·parent lock 경합·rollback·purge 후 replay·legacy 복원을 확인한다.
   Revision이 있는 scope의 purge에는 실제 analytics artifact 저장소 설정도 필요하다. 비활성 저장소는
   부재 증거를 만들지 않고 삭제를 실패 처리한다.

## Rollback

새 열·count·provenance·Flyway 이력과 삭제 진행 증거를 유지한다. 구 코드는 새 count를 완전히 노출하지
못하므로 과거 부분 집계로 취급하며, 새 코드로 복구해 정확한 snapshot과 remediation 보존을 재확인한다.
Legacy를 현재 수로 backfill하거나 count를 0으로 초기화하지 않는다.
