# Trading Review Go service boundary

상태: ADR-072에 따라 채택된 목표 서비스 경계(2026-11-06). 서버·wire·migration 배포 완료가 아니다.
이 문서는 서비스 소유권과 경계의 정본이다. Go 패키지 구조와 구현 예제는 Go 저장소가 소유한다.
기존 Trading Review 정본의 제품 불변식/계산/version/hash/state/ACK 의미는 유지한다.

## 소유권과 배포
Trading Review를 독립 Go API/Worker로 분리한다. tradingrecord와 analysis를 업무 모듈로 두고
삭제/복합 재처리는 workflow Application이 조율한다. Python Compute는 정규화·복원·분석을 계산한다.
기존 PostgreSQL을 그대로 사용하며 Trading Review 관련 테이블·Artifact·Guard·dispatch·outbox만
Go 소유로 이전한다. Member/Strategy/Backtest와 Compute 테이블은 기존 소유권을 유지한다.
Core 잠정 중단으로 동시 Trading Review 쓰기 운영은 요구하지 않는다. 초기 Core 경유 서비스 시
Core는 인증·중계하고 기존 Trading Review 실행은 비활성화한다. 이후 Gateway로 진입 역할을 옮긴다.
물리 DB 공유가 다른 서비스 테이블 쓰기 권한을 부여하지 않는다.

## 통신·인증·소유권
초기 Web→Core→Go, 이후 Web→Gateway→Go. Core/Gateway→Go는 HTTP/JSON을 채택한다.
Compute는 기존 HTTP 계약을 유지한다. gRPC 병행 구현은 현재 범위가 아니다.
브라우저 진입점은 사용자 인증/CSRF를 담당한다. Go는 서비스 인증과 짧은 수명의 서명된 사용자
위임을 검증한다. issuer/audience/expiry/서명/호출 서비스 binding을 검사한다.
서비스 인증과 사용자 identity는 별개이며 raw identity 헤더/body ownerId만 신뢰하지 않는다.
외부 내부용 헤더는 제거·재구성한다. Gateway는 직접 issuer가 될 필요가 없다.
Go가 Account→Book→Import/Revision/Analysis 소속과 현재 Guard를 최종 검증한다.
Worker는 사용자 JWT를 저장하지 않고 승인된 입력·owner와 현재 Guard로 실행/발행을 판단한다.
정확한 token/header/TTL/key rotation/철회/Member 상태 연계는 wire 보안 설계에서 확정한다.

## 실행 수명
요청 timeout, Compute 통신 timeout, attempt deadline, 전체 Run deadline, worker lease를 구분한다.
접수는 Run/Dispatch/멱등성 기록 commit 후 응답하고 Worker가 독립 실행한다.
HTTP 응답 유실은 작업 실패 확정이 아니다. 같은 키로 상태를 복구한다.
정책상 deadline은 재시작으로 임의 연장하지 않는다. 새 수치/오류/실행 정책은 별도 계약 변경이다.
ADR-071의 hard-deadline 초안은 승인되지 않았으며 이 결정이 해당 초안을 승인하지 않는다.

## 멱등성·오류·trace
동일 사용자/operation/key + 동일 semantic request는 기존 접수 결과를 반환한다.
다른 payload 재사용은 conflict이며 동시 접수는 DB 제약으로 제어한다.
재응답에도 현재 권한/삭제 정책을 검사한다. 외부 키와 Compute attempt/ACK identity는 구분한다.
기존 공개 오류 계약을 유지한다. Problem Details는 후속 OpenAPI 호환성 검토 대상이다.
traceparent/tracestate로 호출을 연결하되 인증 근거로 사용하지 않는다. 거래 원문/token/PII를
baggage에 넣지 않는다. 비동기 Worker는 원래 요청과 연결 가능한 실행 trace를 남긴다.

## 취소·원자성
브라우저 abort는 연결/요청 취소이며 commit된 Run을 취소하지 않는다.
명시 작업 취소는 별도 Command로 owner/state 확인 후 취소 의도를 영속화하고 발행을 차단한다.
실제 Compute 종료와 정리를 확인하며 취소 접수를 purge 완료로 해석하지 않는다.
발행/취소는 원자적 guard/CAS로 경쟁한다. 발행이 먼저면 결과 유지, 취소가 먼저면 늦은 공개 차단.
취소가 기존 Revision 삭제를 뜻하지 않는다. 새로운 state/endpoint/disposition을 추측하지 않는다.

Record/Analysis는 동일 실제 DB Tx에 참여한다. WithinTx는 후보이며 전달 방식은 구현 설계에서 비교한다.
외부 호출은 Tx 밖, Tx 누락/종료 scope 재사용/중첩은 오류, 오류/취소/panic은 cleanup한다.
Book latest pointers는 TradingRecord 소유이며 Analysis 발행과 원자적으로 갱신한다.
Guard/Generation/Tombstone은 TradingRecord 권위다. Raw-only는 Ledger/Analysis를 보존한다.
복합 재처리 R2/A2는 비공개 준비 후 함께 공개하며 실패/취소 시 R1/A1을 유지한다.
각 모듈의 ACK outbox는 disposition과 같은 commit에 저장하고 이후 멱등 전송한다.

## 책임 행렬
| 대상 | 업무·쓰기 소유자 | 외부 협력 |
|---|---|---|
| Member/사용자 인증/Strategy/Backtest | Core | 브라우저 진입과 Go 사용자 위임 |
| Account/Book/Import/NormalizationRun/Ledger/근거 | Go TradingRecord | Compute normalization 위임 |
| AnalysisRun/Result/Metric/Finding/Evidence | Go Analysis | Record 공개 Revision/Guard, Compute analytics |
| Guard/Generation/Tombstone/DeletionRequest/ReprocessingRun/Book pointers | Go TradingRecord | Workflow가 두 모듈 진행 조율 |
| 모듈별 Dispatch/멱등성/ACK outbox/Artifact | 해당 Go 업무 모듈 | 외부 전송은 commit 이후 |
| Compute job/attempt/lease/terminal payload | Compute | Go는 HTTP 제출/조회/ACK/purge 계약 사용 |
| Asset/Calendar/DatasetSnapshot | Compute | 기존 Core/Compute 계약 유지 |
| 계약/도메인/ADR | context | 구현 저장소 snapshot은 복제본 |
| 프로세스 조합/fixture/E2E/증적 | integration | sibling 경로 parameter 지원 |

Workflow는 독립 Aggregate/상태 테이블을 소유하지 않는다. 위 행렬은 모듈 소유 책임이며
물리 table/column명·FK·migration 번호를 확정하지 않는다. 관련 지원 테이블도 인계 목록에 포함한다.
같은 DB라도 Go는 Core의 Member 테이블을 직접 수정하거나 인증 authority로 사용하지 않는다.
회원 정지/탈퇴와 기존 FK/cascade의 연계 계약은 이관 전 확정한다.

## 계약 우선순위와 전환 범위
기존 문서의 Trading Review 업무 소유자로 쓰인 Core는 이 목표 설계에서 Go로 이전한다.
브라우저 인증/중계·Member·Strategy/Backtest의 Core 의미는 바뀌지 않는다.
OpenAPI 필드명(coreLogicalJobId 등), hash 입력/namespace, enum, golden vector를 단순 명칭 변경하지 않는다.
기존 OpenAPI는 현재 wire 정본이다. 신규 Core→Go 계약·호출자 인증은 구현 전에 별도 확정한다.
기존 코드/snapshot/E2E가 새 설계를 이미 충족한다고 간주하지 않는다.
Go 세부 문서: `../../trading-review-api/trading-review-api/ARCHITECTURE.md`.

## 정본 적용 순서
1. 서비스 소유권/호출 경계는 ADR-072와 이 문서를 적용한다.
2. 공개 Web→Core와 Go→Compute의 현재 정확한 path/schema/error는 기존 OpenAPI를 적용한다.
   새 Core→Go wire는 기존 두 OpenAPI에 정의돼 있다고 가정하지 않는다.
3. 계산·불변식·보관·버전/해시·상태 전이는 해당 Trading Review 정본과 승인된 ADR을 적용한다.
4. 과거 문서/fixture의 업무 actor `Core`는 Trading Review 소유 역할에 한해 Go로 대응한다.
   브라우저 인증/CSRF·중계, Member/Backtest의 Core와 wire 식별자는 이 치환 대상이 아니다.
   role 대응만으로 transport/state/schema의 신규 동작을 발명하지 않는다.
5. 현재 코드/검증 증거는 실제 실행한 경로를 나타낸다. 과거 Core E2E 성공을 Go 성공으로 재분류하지 않는다.

## 이관과 완료 gate
| 단계 | 확인할 증거 |
|---|---|
| 현재 구현 조사 | Kotlin Trading Review 기능/테이블/FK/migration/object/key/checkpoint와 정본 대응표 |
| 계약 준비 | Core→Go 위임/HTTP 계약, Go→Compute 호출 권한, 공개 오류 호환성 확정 |
| DB 인계 | 기존 migration baseline, 기존 ID/hash/이력 보존, 전체 migration 순서, rollback/복구 계획 |
| 실행 인계 | 기존 Run/attempt/lease/ACK의 재개 또는 명시 종료, key/grant 접근과 만료 처리 |
| 연결 검증 | Core 기존 업무 비활성화, Go API/Worker, 실제 DB/HTTP로 권한·발행·삭제·ACK 검증 |
| 계약 증적 | Go revision 포함 snapshot/fixture 동기화 및 integration evidence |

Core 중단 기간에는 integration이 Go를 직접 검증한다. Core 경유 검증에는 인증·중계용 Core가 필요하다.
동시 쓰기 배포·dual-write 프로토콜은 범위에 없다. 사용자 데이터 DB reset을 이관 절차로 사용하지 않는다.
integration 테스트는 분리된 integration DB에서 수행한다.

## 미확정 항목과 소유 위치
| 항목 | 확정 위치 |
|---|---|
| 사용자 위임 claim/issuer/audience/key rotation/TTL/즉시 철회/Member 연계 | context 보안·API 계약 |
| 내부 Core→Go 경로/오류/멱등성 보관/취소 state·endpoint | context API/OpenAPI |
| SQL scope 잠금 순서/count 고정/서로 다른 Tx scope 차단 | Go persistence 설계 및 실제 DB 검증 |
| WithinTx/명시 scope/UoW 선택 | Go 구현 설계 ADR 및 spike |
| timeout/stream/spool/staging 수치와 운영 한도 | 기존 승인 계약 대조 후 측정·정책 확정 |
| 발급된 URL/진행 중 조회의 철회와 실제 purge 증명 | context lifecycle 계약 |

이 항목의 확정 전 production 구현이나 E2E 완료를 추정하지 않는다.
