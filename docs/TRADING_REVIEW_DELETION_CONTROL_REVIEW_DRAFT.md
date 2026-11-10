# Trading Review 삭제 control·중첩 scope 검토 (DRAFT)

상태: **T01~T03 연계 제품 검토안·미채택·미구현**. ADR-072와 현재 lifecycle/persistence/OpenAPI가 우선한다.
Core source `6a4f11ca1228a803c9b1eccf334eaa2220ee3551`, Go `00cc38ba5ce55f35a156858a59f03976a43db199`,
Context `aba5fedaa38c3e7590e6d15a0e65420a167efb2a`를 대조했다. 실제 target DB나 경합을 조사/실행한 것이 아니다.
물리 잠금·SQL·Go Port는 Go 저장소가 소유하며 여기서는 관측 의미와 수용 조건을 제안한다.

## 기존 확정과 이번 권장안

삭제 접수 commit은 정상 접근/새 admission을 차단한다. 같은 scope의 모든 관련 writer가 공통 fence에
참여하고 Record/Analysis 변경은 동일 실제 Tx에서 원자적으로 수행한다. 원격 Compute/Storage·authority는
그 Tx 밖이다. retry/Worker 재시작이 기존 count·generation·원 요청 이력을 바꾸지 않는다.
Core legacy Trading Review 중단 전제는 유지한다. 이는 Go 내부 동시 경로의 규약이며 Core/Go dual-write 전환안을 추가하지 않는다.
이 원칙은 [서비스 경계](TRADING_REVIEW_SERVICE_BOUNDARY.md), [논리 저장](TRADING_REVIEW_PERSISTENCE.md),
[lifecycle](TRADING_DATA_LIFECYCLE.md#7-삭제-상태-모델), [수용 계약](TRADING_REVIEW_ACCEPTANCE.md)을 따른다.
아래의 세부 overlap·대상 보존 방식은 그 원칙을 구현할 **추가 권장안**이며 새 wire/상태/DDL 채택이 아니다.

## Core에서 관측한 것과 증명되지 않은 것

| 관측한 source | 사실 | Go 설계에서 확인할 것 |
|---|---|---|
| DeleteAccount/Book 서비스 | 멱등 claim 후 scope lock·차단·inventory·request를 저장. CreateBook은 Account lock 후 claim | 순서 차이만으로 deadlock을 주장하지 않고 전체 writer/claim 경합을 검토 |
| JdbcDeletionScopeInventory | 실제 Tx를 요구하고 하나의 집계 query로 기존 소유 scope의 row를 센다. Analysis/Reprocessing는 runtime 부재로 기본 0 | 0이 Go의 실제 부재 증거는 아님. 새 모듈 inventory·동일 Tx 참여를 검증 |
| JdbcTradingDeletionWorkStore/DeletionWorksetRows | queue claim의 Tx에서 아직 없으면 workset을 capture하고, 별도 plan/finish Tx·외부 purge로 진행 | 접수 시 count와 나중 capture의 대상 보존·락 참여가 자동으로 같은 것은 아님 |
| DeletionScopeRows.hasUnfinishedChildren | Session task를 join하여 descendant를 찾음 | Session 없는 Book·Root purge 뒤 관계가 포함되는지 별도 증명. 이 source만으로 장애 발생/모든 누락을 단정하지 않음 |
| DeletionRequestRecord / session task | request에 독립 parent AccountID가 없고 session task에는 Account/Book ID가 있음 | Session 0개도 보장되는 불변 parent provenance 필요. client 값으로 추론하지 않음 |
| V14 remediation / execution CAS | 원 FAILED 이력을 남기고 새 request·workset/항목 증거를 이어받으며 lease/token·version 등을 확인 | Root 없이 실행할 control 권한과 기존 original/remediation identity·checkpoint 보존 |

실제 publication 일부는 Account→Book→Session을 잠그지만 모든 writer/queue/cleanup이 동일한 fence에 참여한다고
증명한 것은 아니다. 기존 Go 실험도 일부 표본의 증거이며 아래 새 control·overlap 프로토콜의 실행 증거가 아니다.

## count·논리 대상·실행 대상의 세 경계

| 기록 | 고정/갱신 의미 | 금지 |
|---|---|---|
| 요청 시 count와 provenance | 해당 요청 시점의 논리 resource 수·REQUEST_TIME_SNAPSHOT 의미를 고정 | purge된 row 수로 재계산, child의 과거 count 합산, LEGACY_PARTIAL을 사후 새 snapshot으로 바꾸기 |
| 논리 scope membership·cleanup 범위 | 같은 fence 아래 확정한 owner/parent/scope·세대와 필요한 정리 범위를 보존 | count 숫자만으로 모든 target locator가 보존됐다고 주장 |
| 구체 workset/checkpoint | 초기 논리 대상에서 발생한 object/Run/attempt/dispatch·확인 상태를 정확한 identity에 결속 | 현재 row가 없다는 이유로 원격 자원도 없음 처리, 무관한 새 resource를 계획에 추가 |

우선안은 count를 고정하는 접수 Tx에서 cleanup 범위를 복구할 근거도 확보하는 것이다. 모든 거래 row를
메모리에 읽지 않는다. 상세 locator를 후속에 capture한다면 다른 purge가 원래 근거를 없애기 전에
이미 보존된 workset/불변 관계·증거로 완전한 범위를 복구할 수 있어야 한다. 그 보장이 없으면 지연 capture를
안전하다고 취급하지 않는다. bounded SQL/manifest·참조 보존 방식과 Tx 크기는 구현 설계/부하 검증 대상이다.

공개 일곱 count는 기존 accounts/books/importSessions/artifacts/ledgerRevisions/analysisRuns/reprocessingRuns다.
권장 membership은 owner/scope에 속해 요청 시 실제 남은 논리 모델이며, 이미 삭제 중인 row도 일괄 제외하지 않는다.
삭제 tombstone·audit/control/task row는 같은 resource로 중복 세지 않는다. NormalizationRun은 Session 소속으로
정리 대상이지만 AnalysisRun count가 아니다. staging chunk·임시 copy는 cleanup 대상이며 논리 Revision/Run
identity와 중복하지 않는다. 새 Go staging 모델의 상태별 포함은 명시적인 membership으로 검증하며 published-only
필터를 편의상 추가하지 않는다. 각 count가 보존된 논리 모델을 세는 의미와 실제 object 수를 구분한다.

## 부모·자식 삭제 overlap의 권장 의미

1. child 요청이 먼저 있어도 parent 차단은 새 요청 commit에서 적용한다. 원 child identity/count/provenance/
   state/checkpoint와 자기 generation을 덮지 않는다. 이미 pending/failed child를 새 parent generation 값으로 치환하지 않는다.
2. parent count는 자기 시점의 live membership이다. 남아 있는 pending Book 한 개는 parent에 한 번 포함하고,
   child 과거 books=1을 합산해 books=2로 만들지 않는다. Book row가 이미 purge됐으면 새 live Book count에는 넣지 않는다.
3. row가 사라졌어도 그 child의 object/Compute/key/copy 정리가 남았다면 parent의 cleanup 범위에서 빠지지 않는다.
   Session이 없는 Book의 descendant 관계도 request/보존 계획에서 식별돼야 한다.
4. 우선안은 parent의 의존 정리 범위를 기존 child 또는 동일 owner/scope/generation의 검증된 remediation으로
   충족하는 것이다. child FAILED는 자동 재개/COMPLETED로 바꾸지 않는다. unresolved/실패 의존성은 정상 완료로 처리하지 않고
   기존 SLA·실패/복구 규칙을 적용한다. parent deadline을 child 처리 때문에 임의 연장하지 않는다.
5. 같은 물리 자원 삭제는 idempotent하게 중복될 수 있지만 lease/항목 완료 CAS는 각 request의 authority를 따른다.
   component 증거 공유/인수는 정확한 scope·generation·resource/locator identity·범위가 검증될 때의 별도 protocol이다.
   parent의 성공 상태만 보고 child checkpoint를 생성하거나 다른 request의 lease로 child를 완료하지 않는다.
6. 의존 방향은 parent 정리→기존 child/remediation coverage로 제한하고, child는 정상 parent ACTIVE를 기다리지 않는다.
   parent 차단은 유지하면서 검증된 cleanup control을 진행할 수 있어야 한다. 양쪽이 서로 완료를 기다리는 순환을 만들지 않는다.

이는 count 합산·요청 자동 병합·새 workflow Aggregate/engine을 도입하는 안이 아니다. 상태·checkpoint 소유자는
기존 Record/Analysis이며 상위 Application workflow가 조율한다. 실제 component 공유를 단순화하려면 그 범위와
원자성·복구를 별도로 입증해야 한다.

## Root 이후 control과 scope generation

읽기 전용 replay/status는 검증된 request owner/control을 읽으며 Root ACTIVE/존재를 요구하지 않는다.
새 claim·generation 증가·실행 권한 부여는 하지 않는다. 미발견은 새 admission/이전 rollback의 증거가 아니다.
상태를 바꾸는 control은 원 request/owner·불변 target/parent·scope 세대·현재 lease/version·보존 계획과
같은 conflict fence에 참여한다. Root row가 없으면 pool fallback·임의 client parent·관계 추론으로 진행하지 않는다.
provenance는 누가 무엇을 정리하는지의 근거이고, 모든 겹치는 경로가 같은 잠금을 잡는다는 증거와는 다르다.

Root가 사라진 뒤의 공통 잠금 기준과 그 metadata 수명은 Go에서 비교한다. read-only replay만을 위해 불필요한
parent 값을 강제하지 않지만 mutation에 필요한 관계가 없으면 원 요청을 성공/부재로 바꾸지 않고 복구 대상으로 남긴다.
control/audit 수명과 삭제·backup 정책을 맞추며 무기한 식별자 보존을 새로 허용하지 않는다.

generation은 scope identity와 함께 해석한다. raw-only 변경은 Book 전체 무효화가 아니며, 기존 Revision의
sourceDeletionGeneration은 불변 입력 pin으로 남는다. Raw scope가 바뀌어도 Book scope가 유효한 retained
Ledger/Analysis는 보존한다. 서로 다른 scope의 숫자를 같게 덮거나 내부 Guard를 숫자 max 하나로 대체하지 않는다.
기존 Compute/ACK의 generation scalar·hash·payload는 현재 계약 그대로이며 필요한 projection 의미를 먼저 검증한다.

삭제 전 승인된 dispatch/RPC가 늦게 Compute job ID를 확정할 수 있다. 초기 Run/attempt/logical key와 결속된
후속 식별자를 cleanup 계획에 보완하는 경로를 검토하되 과거 논리 count를 늘리지 않는다. unresolved ID·cancel 접수·
TTL·ACK 성공만으로 실제 부재를 선언하지 않는다. 삭제 후 새 정상 admission/발행은 계속 거절한다.

## 수용 계획

아래 DCTRL-R01~R10은 **미실행 로컬 검토 사례**이며 정본 Scenario ID나 fixture가 아니다.
TR-I14/15/19/24와 기존 TR-ACC-DELETE-003/006/007/011/012/013에 해당하는 assertion을 실제 검증 때 연결한다.

| ID | Given / When | Then 계획 |
|---|---|---|
| DCTRL-R01 | Session 0개 Book의 child 요청 후 parent 삭제·Root purge | parent 관계·cleanup coverage 유지, Session task 존재만으로 관계 유무 판단하지 않음 |
| DCTRL-R02 | 아직 남은 pending Book과 과거 child books=1 | parent live count에 한 번 포함, 과거 count 합산/child count 변경 없음 |
| DCTRL-R03 | child Book row는 사라지고 원격 정리만 남음 | live Book count 0이어도 정리 대상/의존 증거는 남음, 조기 COMPLETED 금지 |
| DCTRL-R04 | purge 뒤 original/replay/remediation 조회 | 원 count/provenance·원 FAILED 이력 보존, 현재 row로 snapshot 재생성 금지 |
| DCTRL-R05 | FAILED child와 새 remediation이 parent에 겹침 | 정확한 원본/세대·checkpoint coverage로 의존 해소, parent/child 대기 순환·원본 성공 덮어쓰기 없음 |
| DCTRL-R06 | parent provenance/대상 locator가 없거나 다른 owner | fail closed/복구 대기, client 추론·성공/부재 처리 없음 |
| DCTRL-R07 | raw-only generation 변경 후 기존 Ledger/Analysis 접근 | Book 유효 scope는 유지, 입력 pin 불변, raw와 전체 Book 삭제 분리 |
| DCTRL-R08 | 삭제 접수 후 이전 dispatch의 job ID 확정 | 원 Run/attempt/logical key로 cleanup 재조정, count 고정·발행 거절·실제 부재 확인 |
| DCTRL-R09 | hash가 같지만 다른 owner의 object·영수증 | scope별 object/키·증거 격리, cross-owner 삭제/공유 완료 없음 |
| DCTRL-R10 | control 보관 종료·Worker lease 만료·backup 추적 | mutation 가능 수명과 보관 정책 일치, stale Worker 재개/무기한 보존·backup 만료=live 완료 오인 없음 |

다음은 Go T01~T03의 writer/lock graph·Root-independent control carrier·구체 count/plan과 실제 PostgreSQL
검증 계획을 리뷰하고, T04/T05 ID/metadata 및 E01/E02 환경/이관 증거로 이어간다. source/문서 CI나
새 문서 PR merge는 이 정책의 채택·실제 purge/경합 검증·업무 구현 전환이 아니다.

근거: [Core persistence source](https://github.com/refinvest-dev/core-api/tree/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence),
[Core deletion application](https://github.com/refinvest-dev/core-api/tree/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/deletion),
[Account/Book 후보](TRADING_REVIEW_ACCOUNT_BOOK_API_DESIGN_DRAFT.md).
