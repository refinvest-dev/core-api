# Ledger 계약 consumer·수용·전파 리뷰 (DRAFT)

상태: **ADR-077·LP01~05 미채택**. [의미/대안](TRADING_REVIEW_LEDGER_CONTRACT_DRAFT.md)과
[evidence 후보](TRADING_REVIEW_LEDGER_EVIDENCE_DRAFT.md)의 채택 조건이며 실행 보고서가 아니다.
기존 TR-ACC·Go LGR/LUC 계획을 유지한다. source 관측·정적 검증·미실행 계획·실제 운영 인계를 구분한다.

## 고정 source와 소비자 범위

| source/consumer | 관측·영향 | 조사 한계/후속 |
|---|---|---|
| Context `7c7d6fbee0334e599bd72189a2f65c492e4064f3` | public Revision 필수 createdAt/publishedAt/parents·full quality/list item, evidence flat/no allocationId/no metric selector | schema 설명/shape 관측이며 실제 반환 이력/운영 호출 증거 아님 |
| Go `c77c1440faf160b40aa93f3dc2477f345efe664f` | LUC-01~08·LP01~05와 LGR/LUC Domain·수용 계획 | 설계 카드이며 업무 Query/발행 구현 성공 아님 |
| Core `d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1` | UUID PK/FK/cast·publishedAt stamp, stripped quality base와 publication reader | 실제 catalog/저장 이력/배포 DB와 모든 동적 consumer는 미확인 |
| Compute `113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963` | Analytics request ledgerRevisionId는 nonempty string, input identity에 포함 | string schema만으로 새 Snowflake end-to-end/DB 수용 보장 못함 |
| Web `9354dfe321ee8543c10e0914249f06d6643ca6db` | tracked src에서 아래 Ledger 경로/operation 직접 호출 미발견 | generated/동적 caller·외부 SDK·실제 사용자 요청은 별도 조사 |
| integration `ec40a12614a5a9f35cffaa57280c6512b93f371a` | docs/*.md는 Core snapshot 자동 분류, active/draft YAML은 명시 mapping | 새 Go 업무 E2E나 후보 채택 gate 구현을 뜻하지 않음 |

주요 고정 근거:

- [public OpenAPI](https://github.com/refinvest-dev/context/blob/7c7d6fbee0334e599bd72189a2f65c492e4064f3/openapi/core-api.yaml),
  [Versioning](https://github.com/refinvest-dev/context/blob/7c7d6fbee0334e599bd72189a2f65c492e4064f3/docs/TRADING_VERSIONING.md),
  [Analytics identity](https://github.com/refinvest-dev/context/blob/7c7d6fbee0334e599bd72189a2f65c492e4064f3/docs/TRADING_ANALYTICS_INPUT_IDENTITY.md).
- [Go LUC/LP](https://github.com/refinvest-dev/trading-review-api/blob/c77c1440faf160b40aa93f3dc2477f345efe664f/docs/design/ledger-usecases/api-mapping.md),
  [기존 수용 계획](https://github.com/refinvest-dev/trading-review-api/blob/c77c1440faf160b40aa93f3dc2477f345efe664f/docs/design/ledger-usecases/review-and-acceptance.md).
- [Core Root](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain/LedgerRevision.kt),
  [record](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/LedgerRevisionRecord.kt),
  [mapper](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/LedgerRevisionPersistenceMapper.kt),
  [V5](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/adapter/persistence/src/main/resources/db/migration/V5__trading_normalization_publication.sql),
  [publication reader](https://github.com/refinvest-dev/core-api/blob/d49a1d84b57d0fdb6d6c73cf48cf7057c0187ef1/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcTradingLedgerPublicationReader.kt).
- [Compute request](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/api/schemas/trading_analytics_jobs.py),
  [identity validation](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/contracts/analytics/identity.py),
  [input validation](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/application/analytics_input.py),
  [persistence](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/persistence/postgres/trading_analytics_jobs.py).
- [Web src](https://github.com/refinvest-dev/web/tree/9354dfe321ee8543c10e0914249f06d6643ca6db/src),
  [snapshot mapping](https://github.com/refinvest-dev/integration/blob/ec40a12614a5a9f35cffaa57280c6512b93f371a/scripts/contract-sync-map.json).

Core tradingreview와 Web src의 `ledger-revisions|listLedgerRevisions|getLedgerRevision|getReviewUnitEvidence|review-units`
직접 경로/이름 검색에서 대응 endpoint/caller를 찾지 못했다. 배포 서비스 미구현/모든 consumer 부재를 증명한 것은 아니다.
Compute identity/JSON 저장·FK 여부도 실제 DB history와 실행으로 확인해야 한다.

## 채택·구현·활성화 gate

| Gate | 필요한 산출물·책임 | 현재 상태 |
|---|---|---|
| LR-G01: 정책 | LP별 채택/보류·대안·기존 ADR 충돌/대체 범위와 리뷰 결과를 Context에 기록 | 미채택 |
| LR-G02: legacy provenance | 시각/UUID 표현·부모 관계 known-none/unknown·quality 완전성·과거 응답 inventory와 처리 분류 | 실제 DB/응답 이력 미조사 |
| LR-G03: ID/인계 | Go의 실제 catalog·PK/FK/index/cast/pointer/Run/ACK/checkpoint 목록·hash 보존·rollback·Snowflake node/restart | 물리 대안 검토만, DDL 미선택 |
| LR-G04: ingress/access | 별도 Ledger service AND 위임 profile·operation/read 권한·Member authority·owner/Guard·오류/trace·Core 중계 | 내부 wire 미설계·기존 profile 재사용 승인 없음 |
| LR-G05: schema/consumer | 정렬/full DTO·evidence version/selection/page/total·legacy 전환·Core/Web/SDK decoder와 cache compatibility | 실제 consumer 수용 미실행 |
| LR-G06: evidence/hash | 완전 chain/identity/order·metric 역할/exclusion·masking·retained legacy·exact hash projection/oracle | 새 wire/hash 미확정 |
| LR-G07: runtime 수용 | 아래 계획과 LUC/LGR의 HTTP/SQL/동시성·Stage/UNKNOWN/ACK·대형 응답/부하 증적 | 업무 미구현·계획 미실행 |
| LR-G08: 전파/활성화 | Context→snapshot→consumer PR, 단일 writer cutover·DB backup/복구·go/no-go/실행 증거 | 문서 전파 예정, 운영 활성화 아님 |

각 gate는 검토 체크리스트다. CI에 자동 구현된 승인 장치라는 뜻이 아니다.
LP별 부분 채택도 가능하나 createdAt 정의만 채택한 것을 evidence/ID migration까지 승인한 것으로 해석하지 않는다.
ADR-074~076 DRAFT merge/정적 검사도 LR-G04의 실제 issuer/authority/HTTP 검증을 대신하지 않는다.

## 미실행 제품 수용 계획

아래는 계약/legacy/consumer 기준의 추가 계획 14개다. Go LUC-A/LGR-A와 겹치는 것은 같은 증적에 연결하고
중복 성공으로 세지 않는다. 현재 fixture·실제 HTTP/SQL/부하 실행 결과는 없다.

| 계획 ID | Given / When | Then·관련 Go 계획 |
|---|---|---|
| TR-PLAN-LEDGER-CONTRACT-001 | first publication rollback/commit/UNKNOWN 뒤 재시도·ACK 재전송 | 채택한 동일 영속 stamp·새 시각 덮기 없음; LUC-A02/15 |
| TR-PLAN-LEDGER-CONTRACT-002 | 검증된 legacy stamp/이미 다른 createdAt 공개/anchor 누락 | 분류별 승인 projection·호환 처리, 현재 clock/ID 시각 합성 없음; LGR-A06 |
| TR-PLAN-LEDGER-CONTRACT-003 | 신규 decimal/legacy UUID·대소문자/leading-zero·큰 ID client 왕복 | 원 identity·approved parsing·손실 없는 string, silent 재발급 없음; LUC-A05 |
| TR-PLAN-LEDGER-CONTRACT-004 | mixed ID FK/pointer/cast·Run/ACK/lineage/checkpoint·Analytics input replay | 전체 참조 closure/hash/기존 receipt 보존·이관/rollback 실행; LGR-A05 |
| TR-PLAN-LEDGER-CONTRACT-005 | ordinary/reupload/reprocessing/unknown historical provenance | known-none과 unknown 분리·승인 정책·부모 추정 없음; LUC-A02/17 |
| TR-PLAN-LEDGER-CONTRACT-006 | foreign/dup/self/cyclic DAG·R2 Record 성공 뒤 A2 실패 | chain 검증·같은 Tx rollback·기존 pair 유지; LUC-A16/17 |
| TR-PLAN-LEDGER-CONTRACT-007 | list/get full quality·최대 fan-out·page/size/offset 경계 | 동일 완전 DTO·checked 계산·승인 budget, truncate/summary 대체 없음; LUC-A03/04 |
| TR-PLAN-LEDGER-CONTRACT-008 | 같은 응답 도중 publication/delete·다음 page 사이 새 publish | 한 응답의 total/items/access snapshot 일치·승인 cross-request 의미; LUC-A03 |
| TR-PLAN-LEDGER-CONTRACT-009 | 같은 allocation의 다수 record/snapshot·reversal·같은 record/action | 원 identity·완전 chain·승인 bundle/page/total·합성 key/분리 없음; LUC-A08/09 |
| TR-PLAN-LEDGER-CONTRACT-010 | unit이 metric마다 다른 role/exclusion·잘못된/누락 selection | result-bound 문맥·승인 오류/legacy 의미, 임의 SUPPORTING 없음; LUC-A10 |
| TR-PLAN-LEDGER-CONTRACT-011 | 새 bundle/old flat·strict decoder/cache·evidence schema/order/masking | 명시 version 수용·원 hash/identity 또는 별도 version oracle, 묵시 변경 없음; LUC-A10/20 |
| TR-PLAN-LEDGER-CONTRACT-012 | bundle 하나가 정상 byte/fan-out budget 초과·동시 proxy 응답 | 완전 전달 가능한 승인 설계/측정·bounded 자원, silent truncation 없음; LUC-A04/09/13 |
| TR-PLAN-LEDGER-CONTRACT-013 | raw-only/book 삭제·foreign/authority revoke·read abort | 현재 권한·retained masking·raw 미복원·다른 durable 작업 취소 없음; LUC-A08/12/18/19 |
| TR-PLAN-LEDGER-CONTRACT-014 | legacy V1/V2 zero-unit·private R2·최종 공개/ACK 장애 | V2 proof·private capability·prepare≠ACK·최종 pair 원자 공개·ACK만 재전송; LUC-A11/14~17 |

## 문서·계약 전파와 이관 순서

1. 이 DRAFT를 리뷰하고 LP별 정책·미해결 legacy/consumer를 기록한다. 현재 OpenAPI/fixture/런타임은 유지한다.
2. 후보가 main에 반영되면 기존 integration mapping의 docs/*.md로 Core 문서 snapshot을 전파한다.
   새 Markdown은 기존 wildcard 대상이다. 이번에는 새 OpenAPI YAML을 만들지 않으므로 별도 draft 파일 분류를 추가하지 않는다.
   Core snapshot은 policy/runtime 적용이 아니다. candidate preview와 main 동기화/증거를 구분한다.
3. 채택 범위가 확정되면 별도 Context schema/예제·registry/version/hash oracle PR과 소비자 수용 계획을 만든다.
   신규 YAML을 만들 때는 integration 파일 분류를 먼저 준비해 preview의 unclassified 실패를 막는다.
   Go는 아직 Context snapshot 체계가 없으므로 고정 Context commit/PR 링크와 자체 설계 추적을 사용한다.
4. Core proxy·Go mapper/profile·Web/SDK·Compute의 실제 영향별 PR을 연결한다. Compute identity가 같으면 바꾸지 않는다.
   도메인/Use Case/DTO·저장·Analysis/Workflow 검토를 마친 후 명시 구현 범위에서 코드를 작성한다.
5. 인계 전 실제 DB/체크포인트·node ID·backup/복구 리허설과 각 수용 증거를 확보한다.
   Core 중단/기존 TR writer·Worker 비활성화를 확인한 뒤 TR만 같은 PostgreSQL에서 Go에 인계한다.
   Core 중계 활성화는 기존 업무 writer 재활성화와 다르다. 후일 Gateway 전환도 위임/오류·trace 계약을 유지한다.
6. E2E 전 contract sync·Core doc snapshot 검사를 실행한다. 당시 source SHA·clean 여부·실제 결과를 남기며
   기존 Core/Compute smoke를 신규 Go Ledger 업무/부하/인계 성공으로 해석하지 않는다.

운영 rollback은 이미 생성된 신규 Snowflake/참조를 UUID-only writer가 읽을 수 있는지 먼저 확인해야 한다.
단순 Core 재시작이나 reverse CAST는 복구 계획이 아니다. 양쪽 writer 중단·접수/조회 gate·복구 가능한 forward 경로,
backup 시점 이후 접수분과 결과/ACK 보존·동시 writer 금지를 포함한 구체 runbook을 별도로 검증한다.
이번 변경은 문서뿐이므로 문서 revert로 runtime/DB가 바뀌지는 않는다. 실제 cutover/복구는 아직 수행하지 않았다.
