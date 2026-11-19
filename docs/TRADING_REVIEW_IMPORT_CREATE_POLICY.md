# Trading Review ImportSession Creation Policy

상태: **채택(2026-11-17), CreateImportSession 한정.** 사용자와 권고안을 논의하고 재검토 후 반영하라는 명시 요청에 따라 확정했다.
[ADR-084](DECISIONS.md#adr-084--trading-review-importsession-creation-and-replay)이 식별자다. 정책 채택과 실제 구현·HTTP/consumer·운영 수용은 별개다.
ADR-076 전체·Validate/Retry/Upload/삭제·위임/운영 후보는 이번 결정으로 채택하지 않는다.

## 목적과 경계

현재 owner의 유효한 ACTIVE Account→Book 아래 빈 RECEIVED ImportSession을 만들고 원 command mapping을 같은 실제 DB Tx에 저장한다.
동일 요청 replay는 원 접수의 복구이며 새 Session·Run·Artifact·Dispatch·Revision을 만드는 명령이 아니다.
Book.latest pointer와 기존 Session의 입력/pin·상태·generation을 변경하지 않는다.

새 Session은 artifact 0개, Run/Revision 없음, version/generation=0, deletionPending=false다.
Artifact 두 role이 나중에 채워져도 명시 Validate 전에는 계산하지 않는 기존 상태 계약을 유지한다.
Book별 RECEIVED Session 하나라는 제약은 추가하지 않는다. Core의 active-session unique는 한 Session의 active NormalizationRun에 속한다.

## 신규 pin 선택과 known replay

신규 접수는 다음을 따른다.

1. sourceTimezone·reviewTimezone을 명시 입력으로 받고 IANA 이름과 MVP mode/Book 조합을 검증한다. Book timezone을 자동 상속하거나 같은 값으로 강제하지 않는다.
2. optional dataPolicyVersion 생략/null은 이번 신규 admission의 승인된 current immutable policy ID를 선택한다. 명시 값은 승인된 지원 조합인지 확인한다.
3. 정책과 exact VersionSet은 일관된 승인 snapshot에서 선택해 Session에 고정한다. client Create body에 새 VersionSet 필드를 추가하지 않는다.
4. pin 준비는 business Tx 밖에서 수행한다. 여기서 current는 신규 admission의 선택 시점 값이며 commit 순간의 절대 최신 label을 보장하는 계약이 아니다.
   단순 current label 교체는 이미 준비한 불변 pin을 재해석하는 근거가 아니다. 명시적 실행 차단/cutover가 있으면 그 기존 gate를 우회하지 않는다.
5. Domain은 명시된 pin을 받으며 registry I/O·current/default 보정을 하지 않는다. 비공백 JSON만으로 정확성·지원 조합이 증명되지 않는다.

같은 key의 known replay는 현재 authority/owner·parent·삭제 Guard를 통과한 뒤 **원 mapping과 그 Session의 생성 pin**으로 의미를 비교한다.
오늘의 current를 다시 선택하거나 provider의 신규 생성 지원 여부를 원 접수 복구의 필수 조건으로 삼지 않는다.

| 원 접수 | 같은 key의 요청 | 결과 |
|---|---|---|
| policy 생략으로 V1 선택 | policy 생략/null | 원 V1으로 비교해 기존 Session 반환 |
| policy 생략/명시 V1으로 접수 | 명시 V1, 나머지 동일 | 같은 의미로 replay |
| 원 V1 접수 | 명시 다른 policy, 나머지 동일 | 현재 Guard 뒤 payload conflict; 새 Session 없음 |
| 원 V1 접수 후 current/신규 지원 변경 | 원 요청 replay | 원 pin으로 복구; 새 실행/repin 없음 |
| mapping/pin·owner/Book binding 손상 또는 모호한 legacy | 어느 replay | 안전한 저장 정합성/의존성 오류로 중단. 새 current·새 ID로 보정하지 않음 |

새 key에 대한 지원하지 않는 정책은 기존 VERSION_UNAVAILABLE 의미로 거절한다. known key의 payload 비교와 신규 지원 검사는 구분한다.
일반 형식/Unicode 검증과 현재 접근 Guard를 생략하지 않는다. 원 resourceId가 있다는 사실만으로 소유권·parent 일치를 신뢰하지 않는다.
서로 다른 Book에 같은 key를 사용하면 owner+operation namespace 안에서 payload conflict이며 Book별 key namespace로 변경하지 않는다.

Create 시 고정한 pin을 변경하지 않는 것과 후속 실행 지원 여부는 별개다. 실제 Compute 실행 가능성은 Validate admission에서 다시 확인해야 한다.
이 결정은 Validate의 생략 target·명시 target 비교·Retry 전체 profile을 확정하지 않는다. [IC-P02](TRADING_REVIEW_IMPORT_CONTRACT_DRAFT.md#ic-p02-versionset의-선택-시점)의
후속 Validate/registry 차이와 현재 schema 설명을 해당 구현 전에 정합화한다. current가 바뀌었다고 Session을 암묵 upgrade하는 예외는 없다.

## namespace와 fingerprint 보존

namespace는 기존 owner + `createTradingImportSession` + Idempotency-Key다. key는 ASCII `[A-Za-z0-9._~-]` 1~128자를 유지한다.
fingerprint A는 다음 순서의 parts를 기존 방식으로 누적한 SHA-256 hex다.

```text
BookID의 canonical decimal
sourceTimezone
reviewTimezone
declaredPositionMode
resolved dataPolicyVersion
clientRequestReference 또는 빈 문자열

각 part: UTF-8 byte 길이의 ASCII decimal + NUL 1 byte + 원 UTF-8 bytes
```

신규는 이번에 선택한 policy, known replay는 최초 생성 policy를 쓴다. policy 생략/null과 최초 resolved policy 명시는 위 조건 아래 같은 의미다.
reference 생략/null/빈 문자열은 기존처럼 동일하다. 공백·대소문자·NFC/NFD는 원문대로 구분한다.
여기서 원문은 **유효하게 decode된 문자열 값의 UTF-8 bytes**다. `"a"`와 `"\u0061"`처럼 같은 문자열을 표현한 JSON escape 차이는 별도 identity가 아니다.
raw HTTP JSON bytes·field 순서·owner/key/trace/clock/새 ID·전체 VersionSet JSON을 fingerprint parts에 추가하지 않는다.
정규화/복원 input hash·logical key·result/ACK hash를 재사용하거나 A/B codec을 통일하지 않는다.

reference는 요청 동등성 값이며 새 logical dedup key가 아니다. source Session에 별도 reference column이 없다는 사실을 이유로
원문을 복구했다고 가정하거나 불필요한 새 원문 보관을 추가하지 않는다. legacy fingerprint/mapping을 재계산해 덮어쓰지 않는다.

## reference의 Unicode와 길이

clientRequestReference는 optional nullable이며 **유효한 Unicode scalar value 최대 100개**다. byte 수·UTF-16 unit·화면 grapheme 수로 세지 않는다.

| 입력 | 개수/판정 |
|---|---|
| 생략/null/빈 문자열 | 허용, fingerprint에서 빈 문자열과 동일 |
| `가` 또는 `😀` 하나 | 각각 1개 |
| `e`와 결합 악센트 | 2개, NFC로 변환하지 않음 |
| scalar 100개 / 101개 | 허용 / 기존 REQUEST_INVALID 의미로 거절 |
| 잘못된 UTF-8·단독 surrogate escape | 형식 오류로 거절, 대체 문자로 고쳐 접수하지 않음 |
| 실제 U+FFFD 문자 | 유효한 scalar이며 그 문자라는 이유만으로 거절하지 않음 |

UTF-8 유효성을 먼저 검사한 뒤 code point 수를 센다. JSON raw UTF-8 검사만으로 단독 `\uD800` 같은 escape를 잡았다고 하지 않는다.
decoder의 자동 대체 후 RuneCount만 적용하면 서로 다른 잘못된 요청이 같은 fingerprint로 바뀔 수 있다.
[Go JSON 문서](https://pkg.go.dev/encoding/json#Unmarshal)의 기본 대체 동작을 고려해 inbound의 strict 처리와 실제 consumer 왕복을 검증한다.
Go/Python/Kotlin/JS 각각의 기본 length 함수를 계약으로 삼지 않는다. [Kotlin String.length](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/length.html)는
UTF-16 Char 단위이며, 이 결정은 기존 Core service의 일부 supplementary 문자 허용 범위를 넓히는 변경이다.
기존 정상 요청의 hash를 바꾸지는 않는다. 전체 요청 byte/depth·압축/stream 예산에 Workflow ADR-082 수치를 복제하지 않는다.

현재 OpenAPI의 maxLength=100·nullable/필수 필드는 유지하고 길이의 의미를 설명에 명시한다.
strict surrogate 거절·원문 보존은 schema checker만으로 보장되지 않는다. Core 중계가 reserialize/정규화/대체하거나 Web이 다른 길이로 잘라 보내지 않는지 실제 수용이 필요하다.

## 현재 접근과 상태

현재 authority·owner·정확한 parent 관계/venue·MVP 조합과 삭제 Guard는 신규·replay 모두 필수다.
첫 적용은 **신규 생성과 known replay 모두 ACTIVE Account·Book**을 요구하는 기존 경계를 유지한다.
[ADR-083](TRADING_REVIEW_BOOK_METADATA_POLICY.md)의 ARCHIVED metadata Get/List 허용은 Create 권한이 아니다.

| 현재 상태 | 신규 / known replay |
|---|---|
| ACTIVE Account·Book, 정상 입력 | 새 RECEIVED Session / 원 Session 반환 |
| 원 Session이 VALIDATING·ACCEPTED·REJECTED·PROCESSING_FAILED로 진전 | known replay는 원 Session 반환, 원 상태/pin 유지, 새 작업 없음 |
| raw-only 삭제/만료, parent ACTIVE·Session 정상 접근 가능 | 그 이유만으로 replay 차단하지 않음. SessionID 복구만 하며 raw/grant/실행 권한 없음 |
| Account 또는 Book ARCHIVED | 신규·replay 차단 유지 |
| Account·Book·Session 삭제 중/실패·owned tombstone/삭제 proof | 차단. 물리 purge 후 mapping만 보고 재생성하지 않음 |
| foreign/absent | 기존 존재 숨김 유지 |

raw-only generation 증가와 Session 전체 deletionPending/삭제 proof를 혼동하지 않는다. generation>0 일괄 거절이나 현재값으로 덮어쓰기 금지다.
archive는 실제 삭제와 다른 업무 상태다. Create의 archive 거절은 이번 범위에서 기존 RESOURCE_DELETED/410 호환을 유지하되,
그 오류를 이유로 Tombstone·Generation·삭제 작업을 생성하지 않는다. 다른 operation의 archive 오류까지 같은 코드로 통일하지 않는다.
payload conflict는 IDEMPOTENCY_PAYLOAD_CONFLICT/409, 입력/지원 정책 오류는 기존 operation별 catalog를 따른다.
저장 손상·provider/Tx 장애를 400/404/409로 숨기거나 raw driver/SDK 오류를 노출하지 않는다. 새 공개 오류 registry는 만들지 않는다.

Application Result의 SessionID receipt와 현재 public full Session DTO는 구분한다.
현재 응답은 기존 schema·별도 Query/접근 검증으로 조립하며 과거 RECEIVED 응답을 그대로 고정 replay한다고 보장하지 않는다.
commit 뒤 Query 실패·disconnect·브라우저 abort는 접수 취소가 아니다. SessionID만 복구했다는 이유로 full response·Artifact/grant 접근을 추가 허용하지 않는다.

## provider와 transaction 보장

provider는 승인된 정책/VersionSet 조합을 일관된 snapshot으로 반환하는 작은 능력으로 시작할 수 있다.
배포 manifest/설정·등록 profile을 사용할 수 있으며 새 registry 서비스·거대한 공통 abstraction을 선제 도입하지 않는다.
exact 필수 값·계산/결과 pin의 정본 의미를 검증하고 mutable latest·가까운 버전 fallback·legacy default 보정을 하지 않는다.
서로 다른 current 읽기를 섞어 승인되지 않은 조합을 만들지 않는다. 현재 label·실제 실행 지원·운영 cutover는 서로 다른 판정이다.

### 승인 근거와 신규 선택

2026-11-19 후속 재검토에서 아래 Create 한정 해석을 확정했다. 공급 파일/조립의 기술 선택은 Go가 소유하며 새로운 공개 registry API를 정의하지 않는다.

승인은 다음 세 검증을 구분한다.

| 검증 | 필요한 근거 |
|---|---|
| 구조 | 필수 exact 값·metadata schema·중복/미지원 값·기존 저장 제약 |
| identity | 해당 pin 종류의 정본 hash projection/canonical codec과 고정 vector로 계산한 hash 일치 |
| 지원 조합 | 정책·Normalizer/Reconstruction·해당하는 Analytics·implementation digest 조합과 producer/consumer의 명시 지원 근거 |

hash 문자열 형태나 DTO의 생성 가능성은 identity/지원 승인이 아니다. 파일 자체의 checksum도 내부 VersionSet hash를 대신하지 않는다.
Go는 신규 pin의 승인 조합을 선택·고정하고, Compute는 실행 시 자신의 exact 구현 지원을 검증한다.
[Versioning](TRADING_VERSIONING.md#3-version-registry와-availability)의 availability와 compatibility 의미를 유지하며
Core라는 기존 문구의 Trading Review 업무 책임은 [서비스 경계](TRADING_REVIEW_SERVICE_BOUNDARY.md)에 따라 Go로 인계한다.

신규 Create는 배포에서 검증한 불변 승인 snapshot을 사용할 수 있으며 매 요청의 Compute 실시간 조회를 요구하지 않는다.
이 근거는 admission 선택 시점의 승인/지원 조합이다. Compute의 현재 생존·미래 실행 성공이나 긴급 차단의 즉시 전파를 보장하지 않는다.
실제 실행 가능성은 후속 Validate admission에서 다시 확인한다. 이미 명시된 차단/cutover gate는 별도로 준수하며 정적 snapshot을 이유로 우회하지 않는다.
current 교체만으로 이미 선택한 exact pin을 바꾸지 않는다. Validate target/Retry의 미채택 상세를 이번 보완으로 확정하지 않는다.

### 기존 pin의 decode와 손상 구분

원 receipt 복구는 신규 승인 profile 목록·current provider 가용성과 분리한다. 기존 pin의 구조/identity 검증은 적용되는 저장 형식의 근거로 수행한다.
신규 지원 종료와 현재 decoder의 미지원, 실제 저장 손상을 구분한다.

| 기존 값/상태 | Create known replay 처리 |
|---|---|
| 지원하는 정상 저장 형식·원 binding | 현재 권위/parent/삭제 Guard와 원 policy/mapping으로 복구 |
| 신규 선택/실행 지원이 종료됐지만 원 값은 정상 | 원 receipt 복구 허용. 새 실행 가능성을 부여하지 않음 |
| 현재 decoder가 읽지 못하는 과거 형식 | 손상으로 단정하지 않음. 실제 producer/schema/hash 근거 확인 전 안전한 저장/의존성 오류로 중단 |
| 과거에 정상 지원한 형식임이 확인됨 | 그 형식의 명시 decoder·고정 vector로 원 의미/identity를 검증한 뒤 복구. current를 default로 채우지 않음 |
| 해당 형식에서 검증된 hash 불일치·필수 pin 누락·owner/Book binding 손상 | 안전한 저장 정합성/의존성 오류로 중단. 자동 재생성/보정 없음 |
| NULL resource·원 mapping이 모호한 legacy | 원 증거 확인 전 중단. 새 key/current/ID로 복구했다고 표시하지 않음 |

정본 codec/projection 자체가 불명확한 값을 임의 JSON 직렬화로 재계산해 hash 불일치라 판정하지 않는다.
호환 decoder는 필요한 해석 경계에만 둔다. raw JSON·기존 ID·hash·request fingerprint/mapping을 재작성하거나 알 수 없는 의미 필드를 조용히 버리지 않는다.
decoder 추가가 계산 입력 migration/실행 지원을 뜻하지 않는다. legacy 수용 범위는 실제 저장 inventory와 형식별 고정 vector로 입증하며 이 문서만으로 기존 DB가 호환된다고 주장하지 않는다.

### transaction 경계

논리적 실행 순서는 인증/입력 → Tx 밖 신규 후보 준비(필요할 때) → request namespace fence → parent/current Guard → payload/replay 판단 → Insert+Complete → commit 뒤 Result다.
Go의 구체 Tx-bound capability·carrier·row/암묵 FK/unique 잠금·SQL/index는 Go가 소유하고 Store 구현 전에 후보 순서를 정해 실제 경합으로 검증한다.
외부 I/O·ID 발급은 business Tx 밖이며 Session Insert와 Complete는 같은 실제 Tx다. 기존 Tx에 참여할 때 독립 commit/pool fallback이 없다.
후발 후보가 경쟁에서 지면 승자 pin/mapping을 검증해 replay하고 덮어쓰지 않는다. unused ID는 재사용하지 않는다.
rollback·취소·panic cleanup·종료 scope/foreign/nested 거절과 UNKNOWN의 원 key/권위 fence 복구는 필수 보장이다.
단일 SELECT 0건으로 이전 commit 실패를 확정하거나 새 key로 자동 재접수하지 않는다.
이 문서는 carrier table/수명·legacy 설치·모든 writer 참여 또는 특정 SQL을 검증한 증거가 아니다.

## 실제 수용과 적용 순서

| 검증할 경계 | 기대 결과 / 현재 상태 |
|---|---|
| current 변경 뒤 생략/null/명시 원 policy replay | 원 Session/pin 유지, 신규 provider 지원 여부로 재접수하지 않음; 미실행 |
| 같은 key/different Book·입력, NULL resource·손상 parent/pin | conflict/안전한 저장 오류, 새 Session 없음; 미실행 |
| supplementary·100/101·combining·NFC/NFD·escape 등가 | scalar 길이·원문 hash·escape 의미 일치; 실제 HTTP/consumer 미실행 |
| 잘못된 UTF-8·단독 surrogate·정상 U+FFFD | strict 거절과 정상 문자 보존 구분; 실제 decoder 미실행 |
| ACTIVE/ARCHIVED·terminal replay·raw-only·full 삭제 | 위 접근 표·원 상태/Count/Generation/pointer 보존; 미실행 |
| same key 동시성·삭제 경합·Complete/commit 실패 | 원자성·현재 Guard·중복 없음·전체 rollback; 실제 SQL/전체 writer 미실행 |
| UNKNOWN/응답 유실·post-commit 조회 실패 | 원 identity로 복구, 새 key/자동 실행 없음; 미실행 |

Context 정본·설명 schema → 필요한 snapshot 전파/consumer 차이 확인 → 명시한 Go 격리 구현/수용 → HTTP/운영 연결 순으로 진행한다.
실제 target/history·ID/node·key/backup·Core TR writer 중단 증거를 확보하기 전 기존 DB baseline/repair/replay를 하지 않는다.
현재 Core runtime은 고정 V1/UTF-16 길이·원 member/현재 pin 의존이 남아 있으며 이 문서만으로 수정되지 않는다.

## 채택하지 않은 부분과 기존 초안

ADR-076 IC-P02의 **Create pin**과 IC-P03의 **Create A/replay 부분**만 이 정책이 우선한다.
Validate target 기본값·명시 비교/Retry 정책·Upload replay·filename·quality/보조 DTO·내부 audience/scope/키·Archive API는 후보/후속 상태를 유지한다.
ADR-074/075/076 전체·다른 Workflow/Analysis·private 수명·새 endpoint/enum/hash/ACK·runtime 구현을 자동 채택하지 않는다.
기존 DRAFT의 포괄적인 미채택 문구로 이번 Create 결정에 재승인을 요구하지 않는다.

관측 source: Core `f7fe434a8d65d0d826d9750da0272cb04f8189a5`의
[Create](https://github.com/refinvest-dev/core-api/blob/f7fe434a8d65d0d826d9750da0272cb04f8189a5/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/importsession/create/CreateTradingImportSessionService.kt),
[Session](https://github.com/refinvest-dev/core-api/blob/f7fe434a8d65d0d826d9750da0272cb04f8189a5/tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain/TradingImportSession.kt),
[fingerprint helper](https://github.com/refinvest-dev/core-api/blob/f7fe434a8d65d0d826d9750da0272cb04f8189a5/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/support/TradingRequestChecks.kt).
현재와 다른 runtime/DB 상태를 관측한 기록이나 새 hash oracle은 아니다.
