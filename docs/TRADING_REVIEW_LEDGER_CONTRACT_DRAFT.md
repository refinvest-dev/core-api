# Ledger 조회·발행 계약 정합성 리뷰 (DRAFT)

상태: **ADR-077 검토 초안·미채택·runtime 미구현**. 현재 public/Compute OpenAPI와 채택 ADR을 유지한다.
Go LUC-01~08의 LP01~05를 같은 ID로 가져와 제품 의미·대안·consumer 영향·채택 조건을 검토한다.
문서 merge/CI 성공과 정책 채택·구현·데이터 인계·route 활성화는 각각 다르다.

## 읽는 순서와 정본

1. 이 문서: LP01~04, 조회/발행의 공통 제약.
2. [Evidence 리뷰](TRADING_REVIEW_LEDGER_EVIDENCE_DRAFT.md): LP05의 allocation bundle·metric 문맥·version/hash.
3. [consumer·수용/전파](TRADING_REVIEW_LEDGER_CONTRACT_ACCEPTANCE_DRAFT.md): 고정 source, 영향, LR-G01~08과 미실행 계획.

[API](TRADING_REVIEW_API.md)·[public schema](../openapi/core-api.yaml), [Versioning](TRADING_VERSIONING.md)·
[Lifecycle](TRADING_DATA_LIFECYCLE.md)·[Persistence](TRADING_REVIEW_PERSISTENCE.md),
[Analytics input identity](TRADING_ANALYTICS_INPUT_IDENTITY.md)·[Acceptance](TRADING_REVIEW_ACCEPTANCE.md)가 현재 정본이다.
Go의 패키지/Store/Tx binding·물리 이관 설계는 Go가 소유한다. 이 문서는 Module Facade를 RPC로 바꾸지 않는다.

| ID | 우선 후보 | 현재와의 차이·대안 | 채택 전 핵심 |
|---|---|---|---|
| LP01 | 최초 성공 publication Tx에 고정/저장한 시각을 public createdAt/publishedAt으로 사용 | 별도 preparation createdAt 영속화 대안. 현재 Core는 publishedAt만 보유 | 과거 응답/저장 anchor와 의미 충돌 조사 |
| LP02 | 새 Aggregate Snowflake·legacy UUID의 원 identity 보존, public opaque string | Go의 TEXT 참조 이관 우선 검토 vs 명시 dual-column/mapping | 실제 전체 FK/cast/consumer·hash/복구·node 안전성 |
| LP03 | 증거 있는 ordinary import만 known-none parents, 재처리는 immutable DAG | unknown을 []로 바꾸는 대안은 거부, unknown 호환 정책은 별도 결정 | reupload 포함 출처/edge·동일 owner/Book·closure |
| LP04 | full LedgerRevision/quality 유지, Book revisionNumber DESC 우선 제안 | summary/detail 분리는 별도 version 전환 | total/items snapshot·정렬/크기·decoder/성능 |
| LP05 | 명시 versioned allocation bundle과 selection 문맥 | 기존 flat group 유지 대안은 evidence 문서에서 비교 | page/total/hash·legacy·bounded fan-out |

## LP01 — public createdAt과 publication 시각

현재 schema는 createdAt/publishedAt을 모두 필수로 요구한다. 검토한 Core Root/row/V5는 별도 createdAt이 없고
publication stamp만 저장한다. 누락값을 Run.createdAt·현재 clock·Snowflake timestamp로 채우는 것은 허용하지 않는다.

우선 후보는 public resource의 생성 시각을 **최초 성공 publication Tx에 고정/저장된 publishedAt**과 같게 정의하는 것이다.
미공개 candidate 준비 시각은 내부 preparedAt/audit로 분리한다. 이 시각은 DB commit의 실제 벽시각을 측정한 값이 아니다.
Tx 실패 시 공개 resource가 생기지 않으며, commit UNKNOWN은 원 identity의 영속 상태를 복구한 뒤 같은 stamp를 읽는다.
retry/ACK 재전송·조회 때 새 시각을 찍거나 이미 저장된 stamp를 덮지 않는다. 복합 재처리의 R2도 최종 R2/A2 공개 Tx가 기준이다.

대안은 준비 시각을 별도로 저장하고 public createdAt으로 쓰는 것이다. 준비/공개 지연을 표현할 수 있지만
legacy 준비 이력 부재와 private staging 수명 노출, 별도 저장/삭제 정책이 필요해 첫 안을 우선 제안한다.

채택 전에 과거 반환 DTO/serializer·DB stamp 출처·timestamp precision/timezone와 복구 이력을 확인한다.
legacy publishedAt이 검증되고 이미 노출된 createdAt 의미와 충돌하지 않는 자료에만 같은 값 projection을 적용할 수 있다.
다른 createdAt이 이미 공개됐거나 publication proof가 없으면 분류·명시 migration/호환 정책을 먼저 정한다.
이 초안만으로 Go Domain 문서의 누락값 보정 금지를 해제하지 않는다. historical unknown을 지금 시각으로 backfill하지 않는다.

## LP02 — 신규 Snowflake와 legacy UUID

새 Go Aggregate ID는 이미 Snowflake를 사용하기로 했다. 현재 public Revision ID는 UUID format 없는 string이며
검토한 Core Revision PK/FK·Book latest/Session created pointer와 reader cast는 UUID 기반이다.
public이 string이라는 사실만으로 내부 SQL·SDK·checkpoint가 신규 decimal을 받을 수 있다는 뜻은 아니다.

제품/경계 후보는 다음과 같다.

- 기존 Revision의 ID와 모든 참조는 그대로 보존한다. 새 Revision은 canonical decimal Snowflake를 사용한다.
- Go typed RevisionID가 legacy/new 표현을 구분하고 public/Compute에서는 손실 없는 opaque string으로 전달한다.
  client가 숫자로 파싱·정렬하거나 생성 시각을 추출하지 않는다. int64를 JSON number로 보내거나 float64를 거치지 않는다.
- case/leading zero 등 canonical 형식과 legacy 입력 허용은 실제 stored/accepted 표본을 조사해 확정한다.
  대소문자 UUID 입력의 기존 허용 동작을 조사하지 않고 새 400이나 별도 identity를 만들지 않는다.
- Snowflake 정책은 새 Aggregate root에 적용한다. canonical record/unit/snapshot의 semantic identity와
  Compute job/attempt·opaque token·ACK ID를 일괄 변환하지 않는다. 해시 입력의 공개 record/snapshot ID도 유지한다.

Go의 물리 우선 검토안은 Revision PK/FK/pointer를 원 문자열을 보존하는 opaque TEXT로 인계하는 것이다.
대안은 legacy/new 병행 column과 명시 mapping이다. 전자는 mixed 조회를 단순화하지만 전체 FK/index/CAST와
정렬/유일성·실제 data history·lock 비용/rollback을 검증해야 한다. 후자는 UUID consumer를 잠시 유지할 수 있으나
두 identity/부분 갱신·조인/중복 제약 복잡성이 늘어난다. **이 문서는 어느 DDL도 채택·실행하지 않는다.**

특히 normalization hash에서 service Revision ID 제외와 Analytics input identity의 ledgerRevisionId 포함은 다른 규칙이다.
legacy Revision 재식별은 과거 Analytics inputHash/logical key를 바꿀 수 있다. 원 ID·artifact bytes/hash·Run/ACK와
lineage/checkpoint 연결을 보존하며 replay 시 새 ID·새 작업을 발급하지 않는다. UUID 비트에 Snowflake를 숨기지 않는다.
cutover는 기존 Core TR writer/Worker 중단 후 진행하며 두 서버가 같은 업무 데이터를 동시에 쓰는 설계는 요구하지 않는다.
같은 PostgreSQL에서 TR 소유 데이터만 인계한다. Member/Strategy/Backtest/Compute 테이블은 인계 대상이 아니다.

## LP03 — known-none parent와 immutable lineage

현재 parentLedgerRevisionIds는 필수 배열이고 lineage는 nullable다. Versioning은 same-owner/Book의 DAG와
source/target·VersionSet·resultHash·reprocessing provenance를 요구하지만 검토한 Core row는 일반 DAG를 저장하지 않는다.

우선 후보는 **독립 ordinary import임이 입증된 경우만** parents=[]/lineage=null을 known-none으로 표현하는 것이다.
선택적 reuploadOf/parent edge, registered migration·재처리 이력이 있으면 그 고정된 관계를 보존한다.
현재 Book latest나 revisionNumber-1을 자동 parent로 붙이지 않는다. 두 번째 독립 import도 이전 내용의 누적이 아니다.

재처리는 source parent들의 같은 owner/Book·중복/self/cycle 부재·source/target exact version과 hash를 검증한다.
edge와 공개 target/포인터는 같은 최종 publication Tx에 참여하며 R2만 먼저 공개하지 않는다.
일반 Session ACCEPTED 전이를 복합 재처리에 그대로 복사하지 않는다. source deletion 뒤 lineage 식별자/근거의
보존·마스킹·접근은 현재 lifecycle을 따른다. DAG closure를 이유로 삭제 데이터를 되살리거나 foreign parent를 노출하지 않는다.

historical unknown은 known-none과 다르다. migration은 source/audit/기존 reprocessing 기록으로 provenance를 분류해야 한다.
unknown을 []/null로 성공 반환하는 대안은 채택하지 않는다. unknown 자원의 조회를 중단할지, versioned 명시 상태를 추가할지,
검증 가능한 복구를 할지는 실제 inventory와 기존 consumer 수용 후 별도 결정한다. 현재 schema에 임의 null/새 오류를 넣지 않는다.

## LP04 — 완전 응답·정렬·제한

현재 ListLedgerRevisions.items는 Get과 같은 full LedgerRevision이다. qualitySummary도 full TradingQualityReport이며
stripped quality base만 반환하거나 필수 count/issue/근거를 빈 값으로 채우는 summary가 아니다.
canonical records/full manifest 자체를 기본 Revision 응답에 추가하지 않는다.

우선안은 현재 full shape를 유지하고 Book revisionNumber DESC를 명시 정렬로 채택하는 것이다.
Book+revisionNumber 유일성 아래 동점은 정상 자료에서 없어야 한다. 필요 시 Revision opaque ID의 binary/text ASC를
명시 보조 기준으로 검토하되 ID를 숫자/생성 시각으로 정렬하지 않는다. 중복 ordinal은 tie-break로 저장 손상을 숨기지 않는다.
현재 계약이 정렬을 정했다고 가정하지 않으며 기존 client가 기대한 순서를 확인한다.

한 응답의 parent/access·items·total은 동일한 논리 read snapshot을 사용한다. concurrent publish/delete 중 다른
snapshot total과 items를 섞지 않는다. PostgreSQL 방식·짧은 Tx는 Go 저장 설계가 정한다.
서로 다른 페이지 요청 사이 동일 snapshot은 현재 page/size 계약이 보장하지 않는다. 새 publication에 따른 offset 이동이
허용 불가하면 별도 cursor/snapshot version을 검토해야 한다. immutable item과 cross-request page 안정성은 다른 보장이다.

현재 page>=0, size=1~100, total은 비음수 int64다. checked offset과 빈 page/total 의미를 보존한다.
size cap만으로 full quality fan-out·응답 bytes·serializer heap·동시 요청 비용이 제한되지 않는다.
Core proxy/Go reader·serializer·필드별 대량 배열·동시성·네트워크 제한을 synthetic 표본으로 측정하고 정상 유효 payload를 수용해야 한다.
값/row를 임의 truncate하거나 size=100을 사실상 거부하는 숨은 한도·GET 413/새 error code를 도입하지 않는다.

완전 응답을 승인한 budget 안에서 제공할 수 없다면 summary/detail·quality 별도 paging을 명시 schema/version과
consumer migration으로 설계한다. required 필드 생략·list item만 몰래 summary로 교체하는 방식은 대안이 아니다.

## 공통 접근·수명·발행 경계

Ledger 내부 HTTP/profile은 아직 설계·채택하지 않았다. ADR-074~076 audience/token을 새 Ledger route에 자동 허용하지 않는다.
Core/Gateway 서비스 인증과 최종 사용자 위임은 별도로 검증하고 현재 Member authority·Account/Book owner·Guard를 확인한다.
foreign/absent를 통한 존재 유출을 막고 owned 삭제·staging은 현재 계약과 승인된 오류 매핑을 따른다.
선언되지 않은 400/403/5xx·원문 DB/SDK 오류를 임의 추가/노출하지 않는다. trace는 민감정보 없는 승인 형식만 전달한다.

GET read는 새 Run/artifact/grant/dispatch를 만들지 않는다. Analysis가 metric/role별 page/total/order를 결정하고
Record는 bounded exact UnitID batch와 Revision-bound 사실/완전성만 돌려준다. 누락을 skip해 total을 다시 계산하지 않는다.
ResolveAnalysisSource는 exact V2 supplement/두 artifact input을 준비할 수 있는지 확인하는 read 경계이며
Run/Artifact/grant 생성과는 분리한다. V2 zero-unit은 zero supplement rows가 유효할 수 있고 legacy V1은 새 분석 접수 전에 거절한다.

요청 timeout/브라우저 abort는 해당 bounded read 수명만 끝낸다. 이미 durable 접수된 다른 작업은 취소하지 않는다.
일반 publication은 Record가 Tx를 소유하고 복합 R2/A2는 Workflow의 같은 실제 Tx에 참여한다.
외부 Compute/Storage는 Tx 밖에서 수행하며 고정 Stage 독립 검증과 현재 deletion proof를 공개 직전에 재검증한다.
결과 disposition/모듈별 ACK Outbox는 같은 commit에 저장하고 ACK는 이후 멱등 재전송한다.
private prepare는 PERSISTED가 아니다. R2 조회는 일반 includeStaging 옵션 대신 별도 scope/attempt-bound capability로 설계한다.
[ADR-073 수명/판단 창](TRADING_REPROCESSING_RESULT_LIFECYCLE_DRAFT.md)은 여전히 DRAFT이며 새 TTL/deadline을 이 문서에서 만들지 않는다.
