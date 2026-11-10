# Import request identity·replay 정합성 (DRAFT)

상태: **ADR-076/IC-P03 미채택 후보**. [전체 리뷰](TRADING_REVIEW_IMPORT_CONTRACT_DRAFT.md)를 먼저 읽는다.
기존 request fingerprint를 계산 input/result hash와 구분하며 아래는 고정 source 관측과 우선 권장안이다.

## 현재 source의 operation별 fingerprint

owner + 정본 operationId + Idempotency-Key가 namespace다. header key는 ASCII `[A-Za-z0-9._~-]` 1~128자다.
key 자체·actor token·trace·clock·새 ID를 payload hash에 넣지 않는다.

| operationId | 순서 있는 parts | source 알고리즘 |
|---|---|---|
| createTradingImportSession | bookId decimal, sourceTimezone, reviewTimezone, mode.name, resolved dataPolicyVersion, clientRequestReference 또는 빈 문자열 | A |
| uploadTradingImportArtifact | sessionId decimal, role.name, verified raw SHA, verified size decimal, contentType, dialectCandidate | A |
| validateTradingImportSession | sessionId decimal, 요청 target의 canonical JSON 또는 빈 문자열, validate | B |
| retryTradingImportSession | sessionId decimal, 빈 문자열, retry | B |
| deleteTradingImportSession | sessionId decimal | A |
| deleteTradingSourceArtifact | artifactId decimal | A |

A는 part별 **UTF-8 byte 길이의 ASCII decimal + NUL byte + UTF-8 bytes**를 순서대로 SHA-256한 hex다.
B는 같은 길이 표기 뒤 **colon `:` + UTF-8 bytes**를 연결해 SHA-256한다. Core의 별도 helper 두 개가 이를 구현한다.
ASCII 문자 수·Unicode scalar 수·UTF-16 unit 수로 byte 길이를 대체하지 않는다.
target canonical JSON도 Go 기본 Marshal/map 정렬로 교체하지 않는다. strict decode·Unicode/number 범위를 함께 확인한다.
공개 TargetTradingVersionSet은 필드/버전 문자열의 typed 값으로 검증한 뒤 canonical 표현을 사용한다.

이는 legacy 호환 profile의 관측이며 새 공통 hash 표준 채택이 아니다. operation 이름/path가 바뀌더라도
기존 namespace를 계속 사용할지 명시하고, 이유 없는 A/B 통일·기존 fingerprint 재계산/덮어쓰기는 금지하는 우선안이다.
새 profile이 필요하면 versioned codec·이전 mapping 조회/전환·UNKNOWN fence·rollback을 별도 설계한다.

### 원요청의 동등성 후보

| 경우 | 권장 결과·주의 |
|---|---|
| Create client reference absent/null/empty | 현재 A source의 빈 문자열 의미를 보존. 이 값의 새 trim/NFC·case 변환 없음 |
| Create policy absent/null | 최초 접수에서 resolve된 exact policy를 비교/복구에 사용. replay 시 오늘 current로 재해석하지 않음 |
| Create explicit policy와 최초 resolve된 같은 값 | 현 A profile에서 다른 parts가 같으면 동일 fingerprint. 단, 미지원 신규 정책을 실행 허용하는 예외 아님 |
| Validate target omitted/null | 현 B profile에서 빈 문자열로 동등 |
| Validate explicit exact Session pin | omitted와 실행 입력은 같아도 B의 요청 part는 다름. 같은 key 재사용은 payload conflict, 다른 key는 기존 실행 수렴 가능 |
| Upload filename 변경/생략/null | A profile 밖. raw identity/key payload를 바꾸지 않음. 본문/나머지 metadata 유효성 검사는 유지 |
| Upload size/type/dialect 변경 | A profile 안. 같은 key면 conflict 가능; 새 key의 같은 role/hash라도 최초 descriptor/pin을 덮어쓰지 않음 |
| 다른 parent/target로 같은 key 사용 | namespace는 owner+operation이므로 target part 차이로 conflict. parent별 namespace로 바꾸지 않음 |

Create 정책 replay를 구현하려면 최초 mapping/resource의 policy·요청 해석을 확보하고 그 pin으로 의미를 비교해야 한다.
기존 row가 fingerprint/resourceId만 저장하는 사실을 고려한다. inference가 모호한 legacy row는 새 current를 선택해
정상 replay로 만들지 않는다. authoritative row/unique fence로 확인할 수 없으면 UNKNOWN/compatibility 검토로 남긴다.
기존 저장 자료만으로 항상 복구 가능하다고 주장하지 않는다.

## transport replay·logical 중복·Compute 멱등성

| 경계 | 중복 식별과 역할 |
|---|---|
| public command replay | owner/operation/key + 위 semantic fingerprint. 최초 resource와 현재 허용 상태 |
| upload logical slot | Session/role/hash 및 기존 metadata. 새 key여도 중복 Artifact 생성 금지 |
| normalization logical identity | 원 inputSetHash/exact VersionSet·source generation·결과 쌍. 같은 성공 결과 중복 발행 금지 |
| Dispatch/Compute create | Run/attempt·create fingerprint/grant protocol의 기존 pin. public key 대용 아님 |
| Result/ACK | payload/semantic hash와 ACK ID·Run/attempt/generation/disposition. 결과 결정/outbox commit 뒤 재전송 |

이 문서는 inputSetHash·Compute request/create·resultHash·ACK identity를 변경하지 않는다.
transport record 최소 24시간 이후에도 aggregate logical mapping·unique constraint, 삭제 ledger 180일의 정본을 지킨다.
retention이 지난 key를 무조건 신규 업무로 받아도 된다는 뜻은 아니다.

## known replay와 새 admission의 순서 후보

1. 서비스/사용자 인증·권한과 owner 범위를 확인한다. foreign/absent·tombstone 정보 노출 순서를 지킨다.
2. 정상 data 접근 Guard 또는 정당한 deletion control 접근을 구분하고 원 key/mapping·payload를 확인한다.
3. known replay는 현재 허용 resource/result로 수렴한다. **새 실행 전제**를 매번 적용해 terminal replay를 막거나 다시 실행하지 않는다.
4. 새 logical admission인 경우에만 현재 parent ACTIVE·Session 상태·raw/버전 실행 가능성·generation을 최종 lock 아래 검사한다.
5. 새 변경과 key mapping·Run/Dispatch 또는 삭제 차단/count/checklist를 같은 Tx로 확정한다.

이는 단일 SQL 순서의 채택이 아니다. auth preflight 뒤 payload/stream 검증, scope/멱등 row의 전체 lock graph와
conflict/unique fence가 필요하다. 기존 upload는 canUpload를 replay 전에 검사하고 삭제는 claim을 parent lock 전에 한다.
위 후보를 도입할 때 기존 key/다른 target·삭제/Worker와의 교착/경합도 검증한다.
원 key replay를 빠르게 처리하려고 미검증 업로드 body·다른 owner resource를 신뢰하지 않는다.
전환 gate로 validation/retry route 전체를 중단하는 기존 운영 규칙은 이 순서가 우회하지 않는다.

| command | known replay 후보의 범위 |
|---|---|
| Create | 현재 접근 가능한 최초 Session. 새 current policy로 재접수하지 않음 |
| Upload | 동일 요청의 기존 Artifact, Session이 이미 VALIDATING/terminal이어도 새 upload와 구분. raw 접근 차단/expiry·삭제에 대한 410은 유지하고 복원/교체하지 않음 |
| Validate/Retry | 원 접수에 연결된 현재 Session. terminal이어도 같은 key로 새 Run 생성 없음 |
| Delete raw/Session | 검증된 former owner의 기존 DeletionRequest/control 값. 정상 resource의 tombstone만으로 replay를 막지 않음 |

Raw-only 뒤 Session/Revision 조회와 upload replay의 raw 가용성 조건을 혼동하지 않는다. raw가 없다는 이유만으로
보존 결과를 삭제하지 않는다. 모든 replay는 현재 권한/삭제 정책 아래이며 오래된 성공 응답을 무조건 반환하지 않는다.
replay 결과에서 filename·raw descriptor를 되살리거나 만료된 grant를 발급하지 않는다.

## 응답 유실·UNKNOWN·정리

known abort와 commit UNKNOWN을 구분한다. 연결/요청 timeout·브라우저 abort는 미접수 확정이 아니다.
같은 operation/key·fingerprint·Run/attempt/target identity와 권위 DB의 unique fence로 상태를 해소한다.
새 connection SELECT 0건만으로 이전 Tx rollback을 확정하거나 새 key로 자동 재접수하지 않는다.
확인할 수 없으면 안전한 dependency/불확실 상태로 유지하고 새 공개 오류 code/헤더는 후속 계약에서 명시한다.

Object upload는 DB 밖에서 끝나므로 commit UNKNOWN에 final Object를 즉시 삭제하면 안 된다.
채택 여부/참조/lease·grace·cleanup checkpoint로 안전하게 수렴해야 한다. 외부 purge/취소 수용은 부재 증명이 아니다.
cleanup 실패가 원래 commit을 실패로 덮어 중복 실행을 유도하지 않도록 구분한다.
상세 Tx binding/SQL·orphan reconciliation은 Go 저장 명세와 실제 장애 테스트의 책임이다.

source: [A helper](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/support/TradingRequestChecks.kt), [B helper](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/NormalizationInputFingerprint.kt),
[Create](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/importsession/create/CreateTradingImportSessionService.kt), [Upload](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/importsession/upload/UploadTradingImportArtifactService.kt), [Validate](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/normalization/validate/ValidateTradingImportSessionService.kt), [Retry](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/normalization/retry/RetryTradingImportSessionService.kt),
[멱등 mapping adapter](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcTradingIdempotencyStoreAdapter.kt). 계산 pin은 [Identity 정본](TRADING_NORMALIZATION_IDENTITY.md)을 따른다.
