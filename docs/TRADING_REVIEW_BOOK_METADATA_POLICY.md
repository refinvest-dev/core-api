# Trading Review Book Metadata Read Policy

상태: **채택(2026-11-17), GetBook·ListBooks metadata 한정**. 사용자와 좁은 권고안을 논의한 뒤 진행 지시에 따라 확정했다.
[ADR-083](DECISIONS.md#adr-083--trading-review-book-metadata-reads-for-archived-scopes)이 이 결정의 식별자다.
정책 채택과 실제 Core/Go 구현·HTTP/consumer·운영 수용은 별개다. 문서 merge/CI를 구현 완료로 해석하지 않는다.

## 결정과 범위

검증된 owner가 유효한 Account→Book 관계와 현재 삭제 Guard를 통과하면, **GetBook·ListBooks는 ACTIVE와 ARCHIVED parent/Book의 metadata를 허용한다.**
ARCHIVED는 보관된 metadata 상태이며 삭제·소유권 상실·신규 실행 허용을 뜻하지 않는다. 목록에 보이는 Book의 단건 metadata도 같은 정책으로 읽을 수 있어야 한다.

| Account | Book | GetBook metadata | ListBooks |
|---|---|---|---|
| ACTIVE | ACTIVE | 허용 | 포함 |
| ARCHIVED | ACTIVE | 허용 | 포함 |
| ACTIVE | ARCHIVED | 허용 | 포함 |
| ARCHIVED | ARCHIVED | 허용 | 포함 |
| 삭제 중/실패·owned Account tombstone | 모든 상태 | 차단 | parent 오류. 정상 빈 목록으로 바꾸지 않음 |
| 조회 가능한 parent | 삭제 중/실패·owned Book tombstone | 차단 | 해당 Book을 items/total에서 제외 |

정상 허용은 동일 owner·정확한 ID/parent/venue 체인·유효 lifecycle/generation 등 기존 무결성 검증이 전제다.
foreign/absent는 기존 존재 숨김 원칙을 유지한다. tombstone은 요청 owner/대상 type/ID 범위에서만 해석한다.
보존 중인 owned 삭제 proof와 물리 purge를 정상 접근으로 되돌리지 않으며, proof 만료·이미 시작된 조회의 처리는 기존 lifecycle 정책을 따른다.
소유한 Book이 존재하나 parent 관계가 깨진 경우도 metadata 허용의 근거가 아니다. 기존 무결성 오류를 조용히 정상 목록/빈 결과로 바꾸지 않는다.

ListBooks는 parent Guard와 같은 가시성 조건의 items/total을 일관된 snapshot에서 판정한다.
유효 parent의 Book 0개와 범위 밖 page는 nonnil 빈 items·실제 total이며, parent 부재/foreign/삭제와 구분한다.
삭제 commit 뒤 새 정상 조회는 차단한다. 이미 시작한 snapshot/응답을 DB Guard만으로 회수한다고 보장하지 않는다.

## 채택하지 않은 동작

이번 결정은 [Account/Book 후보 P01](TRADING_REVIEW_ACCOUNT_BOOK_API_DESIGN_DRAFT.md)의 metadata 일부만 확정한다.

| 비대상 | 유지할 경계 |
|---|---|
| 신규 CreateBook | 현재 ACTIVE Account 요구 유지. archive metadata 조회 성공을 신규 생성 승인으로 재사용하지 않음 |
| CreateBook 생성 replay | 현재 ACTIVE parent/Book 요구와 기존 멱등 namespace·fingerprint·원 mapping/오류 의미 유지 |
| CreateAccount/replay | 기존 동작 유지. Account read/replay 의미를 Book으로 일괄 복제하지 않음 |
| DeleteAccount/Book·control/status | archive 삭제 허용·새 remediation/overlap 정책을 채택하지 않음. 기존 삭제 Guard/control 의미 유지 |
| grant/Artifact·Ledger/Analysis 결과·Import/Compute/재처리 | 추가 접근·실행 권한 없음. 별도 업무 계약/현재 Guard 적용 |
| Archive/Unarchive API | 새 command/endpoint·상태 전이·보관 job을 도입하지 않음 |
| P02~04·ADR-074/075 전체 | Book displayName public 추가·pointer NULL/미조회·인증/위임/오류 registry/운영값은 별도 후보/검증 |

따라서 보관된 Book은 metadata로 읽을 수 있어도 같은 Book의 과거 생성 요청 replay는 거절될 수 있다.
이는 이번 좁은 채택에서 남기는 동작 차이다. HTTP 활성화 검토에서는 이 차이를 consumer에 명시하고,
replay를 완화하려면 원 identity/접근/삭제/멱등성 계약을 별도로 결정한다. 새 key·다른 payload를 replay로 해석하지 않는다.

raw-only 삭제를 Book 전체 무효화로 해석하지 않는다. 이 결정은 기존 Raw/Ledger/Analysis 보존·삭제 generation 의미를 변경하지 않는다.

## Public/internal 계약 경계

대상 업무는 public `getTradingBook`·`listTradingBooks`의 metadata 의미와 해당 Go 내부 Use Case다.
새 endpoint·version/profile selector·query flag·namespace·필드/enum·hash/vector는 추가하지 않았다.
현재 TradingBook schema/공개 필드와 내부 projection은 그대로 구분한다. 내부 displayName이 있다고 public 응답 필드를 독자 추가하지 않는다.
latest Ledger/Analysis pointer를 읽지 않았다는 이유로 가짜 ID·빈 문자열·확인되지 않은 null을 만들지 않는다.

ADR-074/075의 인증/wire 설계는 여전히 DRAFT다. 이번 정책으로 서비스 인증·사용자 위임·현재 Member 권위 검증·owner/삭제 Guard를 생략하지 않는다.
기존 public 존재 숨김/삭제 오류 의미를 유지하며 metadata archive만을 이유로 삭제 오류를 내지 않는 방향으로 이행한다.
정확한 내부 오류 mapping과 Core/Go의 기존 차이는 실제 구현/consumer 검증에서 닫는다. 새 공개 오류 code/status를 이 문서에서 발명하지 않는다.

## 관측한 기존 source와 이행 영향

아래는 정책 채택 직전의 고정 관측이며 정책 적용/수용 증거가 아니다.

| 소유자 | 기준과 차이 |
|---|---|
| Context | `33647a697dab68fe0636f5a8518061cada81f605`의 [P01 후보](https://github.com/refinvest-dev/context/blob/33647a697dab68fe0636f5a8518061cada81f605/docs/TRADING_REVIEW_ACCOUNT_BOOK_API_DESIGN_DRAFT.md)는 metadata뿐 아니라 replay/삭제까지 제안한다. 이번 결정은 그 일부만 채택 |
| Core | `f7fe434a8d65d0d826d9750da0272cb04f8189a5`의 [GetBook](https://github.com/refinvest-dev/core-api/blob/f7fe434a8d65d0d826d9750da0272cb04f8189a5/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/book/get/GetTradingBookService.kt)은 parent/Book ACTIVE만 허용. [ListBooks](https://github.com/refinvest-dev/core-api/blob/f7fe434a8d65d0d826d9750da0272cb04f8189a5/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/book/list/ListTradingBooksService.kt)·[목록 SQL](https://github.com/refinvest-dev/core-api/blob/f7fe434a8d65d0d826d9750da0272cb04f8189a5/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingBookJpaStore.kt)은 ARCHIVED parent를 통과시키고 ACTIVE child만 포함 |
| Go | `d9c7a538111fc3974852e84134573749bd69da0b`의 [Book read Guard](https://github.com/refinvest-dev/trading-review-api/blob/d9c7a538111fc3974852e84134573749bd69da0b/internal/modules/tradingrecord/internal/domain/book/access.go)는 parent/Book archive를 보류. [CreateBook](https://github.com/refinvest-dev/trading-review-api/blob/d9c7a538111fc3974852e84134573749bd69da0b/internal/modules/tradingrecord/internal/application/book/create.go)은 자체 ACTIVE parent/Book 검증을 사용하며 ListBooks는 아직 없음 |

후속 Go 격리 구현은 metadata Guard/GetBook 정합화와 ListBooks를 함께 검증하고 생성/replay 요구를 유지한다.
Core의 기존 Trading Review runtime을 바로 수정해 이중 writer를 운영하는 계획은 아니다. Core writer 중단/Go 인계 원칙과 실제 중계/consumer 변경을 구분한다.
현재 Core·Go runtime은 이번 문서 작업으로 변경되지 않는다.

## 수용 계획과 활성화 조건

아래는 **후속 구현/수용 계획이며 미실행**이다. 기존 canonical Scenario ID/Then과 계산 fixture를 재명명하거나 실행 성공으로 기록하지 않는다.
TR-I01/15와 TR-ACC-AUTH-002의 foreign 숨김 원칙을 추적하며, 별도 read matrix의 새 사례를 그 기존 단건 시나리오 자체로 바꾸지 않는다.

| 검사 범위 | 필요한 증거 |
|---|---|
| Read matrix | ACTIVE/ARCHIVED parent×Book 4종 모두 GetBook metadata 허용·ListBooks 포함, 저장 상태/ID/시각 변경 없음 |
| 삭제 우선권 | Account/Book 삭제 상태·owned tombstone·purge/proof·새 조회 차단. parent 오류와 정상 []·삭제 child 제외 구분 |
| 소유권/관계 | foreign/absent 숨김, 다른 owner/type의 tombstone 분리, ID/parent/venue·generation 손상 거절 |
| Page/일관성 | bounded items·정확 total·빈/범위 밖·동시각 정렬·큰 ID 무손실, parent/child 동시 변경과 같은 snapshot |
| 비대상 회귀 | 신규 생성·생성 replay ACTIVE 요구, 멱등 mapping·fingerprint·삭제/control·grant/실행 권한 유지 |
| 수명/실패 | request 취소/deadline·Rows.Err/Close·cleanup 관측·같은 Tx·commit 실패에 부분 성공 없음 |
| Consumer | 실제 Core relay/Web 필드·archive status/오류·리스트→단건 이동, 기존 replay 차이 설명. public/internal wire 차이의 실제 검증 |
| 활성화/운영 | 실제 인증/Member 권위·DB history/ID·단일 writer 인계·계약 sync/HTTP E2E·성능/부하·복구 조건을 따로 충족 |

문서/정적 검사·PR CI·Go 격리 PostgreSQL 성공은 서로 다른 증거다. 실제 수용 전 HTTP/운영 완료로 표시하지 않는다.
정책은 확정됐으므로 후속 작업에서 이 metadata 범위 자체의 재승인을 요구하지 않는다. 구현 범위/실행 증거·비대상 정책은 개별 상태를 따른다.
