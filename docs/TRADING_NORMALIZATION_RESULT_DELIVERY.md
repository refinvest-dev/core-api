# Trading Normalization Result Delivery V1

ADR-070의 Normalization 결과 V2도 이 `RESULT_DELIVERY_V1` 단일 resource, 동일 media type, size/bytes hash/ACK/TTL 경계를 사용한다. 아래 V1 schema/hash 행은 기존 V1 조합을 설명한다. V2 조합은 `TRADING_NORMALIZATION_RESULT_V2`/`TRADING_NORMALIZATION_RESULT_HASH_V2`이며 descriptor와 body가 정확히 일치하고 `ANALYTICS_INPUT_SUPPLEMENT`를 포함한 10개 section digest를 제공해야 한다. 전송 policy version은 결과 schema version이 아니다.

ADR-067의 정본이다. 이 문서는 normalization terminal **전송**을 규정한다. 공개 item·count·section·manifest·overall hash의 의미는 [result integrity V1](TRADING_NORMALIZATION_RESULT_INTEGRITY.md), 입력 identity는 [input identity V1](TRADING_NORMALIZATION_IDENTITY.md)을 따른다. 아래 runtime 관찰은 이 계약을 소급해서 바꾸지 않는다.

## 계약 감사 (2026-10-19)

분류는 `CONTRACT_GAP`, `CORE_RUNTIME_DRIFT`, `COMPUTE_RUNTIME_DRIFT`, `HARNESS_GAP`, `NON_FUNCTIONAL_GAP`, `CONSISTENT`이다. Runtime은 Core `TradingNormalizationResultProcessor`/`RestClientTradingNormalizationClient`/`NormalizationResultIntegrity`/JDBC staging과 Compute payload builder/worker/Postgres result store/routes의 현재 파일을 기준으로 했다. Integration의 identity/result integrity test는 fixture bytes·hash와 일부 runtime 계산을 검증하지만 실제 대형 HTTP stream과 제한 heap을 검증하지 않는다.

| 항목 | Context/OpenAPI | Compute runtime | Core runtime | 분류 | 조치 |
|---|---|---|---|---|---|
| terminal status | `COMPLETED`만 descriptor·payload, `FAILED`는 safe failure | 동일 | 완료 polling 후 processor 실행 | CONSISTENT | 유지 |
| result endpoint | job `/result` descriptor → `/terminal-payloads/{payloadId}` | 두 route 제공 | 두 route 조회 | CONSISTENT | 유지 |
| delivery mode | `SINGLE_RESOURCE`, page/chunk 없음 | descriptor에 `SINGLE_RESOURCE` | 단일 다운로드 확인 | CONSISTENT | 유지 |
| media type | vendor `application/vnd.refinvest.trading-normalization+json` UTF-8 | 같은 vendor type과 charset | vendor subtype 검사 | CONSISTENT | `application/json` 권장안 대신 기존 vendor JSON 유지 |
| content encoding | identity, 압축 없음 | identity header | identity 또는 header 생략 허용 | CONTRACT_GAP | V1 descriptor에 identity 명시; 생략 header는 HTTP identity로 해석, 다른 값 거절 |
| Content-Length | descriptor `byteSize`; HTTP length 처리 미정 | 일반 Response의 body length | 50 MiB header 선검사, stream actual count; header와 actual 비교 없음 | CORE_RUNTIME_DRIFT | descriptor/header/actual의 일치 검사와 무길이 경로 추가 |
| payload bytes hash | descriptor `payloadSha256` | 직렬화 bytes SHA-256, 저장·재조회 검증 | spool 중 SHA-256, descriptor와 비교 | CONSISTENT | 유지 |
| hash 대상 bytes | exact UTF-8 entity body; header/framing 제외 | 직렬화 JSON bytes | 받은 body bytes | CONSISTENT | size도 같은 bytes로 고정 |
| result schema version | `TRADING_NORMALIZATION_RESULT_V1` required | 생성·descriptor | descriptor와 body 검증 | CONSISTENT | 유지 |
| result hash specification | `TRADING_NORMALIZATION_RESULT_HASH_V1` required | 생성·descriptor | 독립 재계산 | CONSISTENT | 유지 |
| required identity fields | job/logical/run/session, attempt, generation, input, version | payload·descriptor에 해당 값 | current Run/attempt/generation/input/version 대조 | CONSISTENT | required 유지 |
| declared counts | payload·descriptor required, staging count 대조 | 생성·보존 | verifier·staging count 대조 | CONSISTENT | 유지 |
| section digests | 9개 section required | 생성·보존 | 독립 재계산 | CONSISTENT | 유지 |
| manifest hash | 공개 manifest JCS hash | 공개 projection 계산 | 독립 재계산 | CONSISTENT | 유지 |
| overall resultHash | 공개 digest manifest JCS hash | 생성·보존 | 독립 재계산 | CONSISTENT | 전송 metadata 제외 유지 |
| empty section | 필수 `[]`/count 0, singleton 1 | 공개 builder가 배열 생성 | verifier가 count 검사 | CONSISTENT | 유지 |
| failure response | FAILED safe code; integrity별 분류 | 512 MiB 초과를 `INCOMPLETE_ANALYSIS_RESULT` | 50 MiB 초과·encoding을 `RESULT_SCHEMA_UNSUPPORTED`, 중단을 dependency failure로 합침 | CONTRACT_GAP | 전송 failure code 분리 |
| ACK | publication commit 후 `PERSISTED`; 삭제 stale만 `DISCARDED_STALE` | ACK 후 purge | durable ACK/outbox | CONSISTENT | 유지 |
| payload retention | ACK 또는 terminal 후 최대 PT24H | encrypted DB payload, ACK/TTL purge | 완료 뒤 ACK | CONSISTENT | 유지 |
| deletion race | generation/tombstone 우선 | job/payload purge | publication 직전 generation 재검사 | CONSISTENT | 수신 중 취소·spool cleanup 추가 |
| V1 최대 크기 | 교환·effective limit 없음 | 512 MiB 고정, 실제 bytes로 검사 | 50 MiB 고정 | CONTRACT_GAP | Run pin policy와 min 규칙 적용; 숫자는 운영 설정 |
| delivery policy request | create에 Run pin `resultDeliveryPolicy` required | DTO/job persistence가 필드를 받거나 보존하지 않음; 현재 create fingerprint는 request 전체의 locator/expiry만 제외 | dispatcher가 필드를 보내지 않음 | COMPUTE_RUNTIME_DRIFT | 양쪽 snapshot 동기화 후 Core 전송, Compute immutable 저장·fingerprint 분리; 기존 deployment와 wire 비호환 |
| descriptor policy metadata | policy version과 content encoding required | 두 필드 반환하지 않음 | 두 필드를 읽거나 pin과 비교하지 않음 | CORE_RUNTIME_DRIFT | Compute descriptor 생성과 Core 검증을 함께 갱신 |
| Core 메모리 | streaming 수신/parse 상한 미정 | 해당 없음 | 8 KiB spool 수신 후 전체 JSON Map·staged items 재적재; transport hash 전 stage metadata 생성 | CORE_RUNTIME_DRIFT | hash 후에만 staging 시작, streaming parser·bounded batch와 재검증 경로 구현 |
| 임시 spool | privacy 분류 없음 | 해당 없음 | OS 일반 temp에 평문 파일 | NON_FUNCTIONAL_GAP | 보호된 spool·orphan sweep·삭제 연동 |
| 대형 runtime 증거 | acceptance 미정 | 단위 result integrity test | 단위/DB publication test | HARNESS_GAP | 실제 Compute→Core HTTP, 제한 heap, fault injection 추가 |

## V1 policy와 byte 의미

`ResultDeliveryPolicy`의 wire 이름은 `NormalizationResultDeliveryPolicy`다. Core가 normalization Run 시작 시 `policyVersion=RESULT_DELIVERY_V1`, `mode=SINGLE_RESOURCE`, 양의 `maxAcceptedPayloadBytes`, `acceptedContentEncodings=[identity]`를 pin해 create request에 보낸다. 값은 배포 설정이다. 이미 배포한 Core 50 MiB/Compute 512 MiB는 runtime 관찰값이지 영구 계약 상수가 아니다. 성공 payload는 하나의 HTTP resource, vendor JSON (`application/vnd.refinvest.trading-normalization+json; charset=utf-8`), UTF-8/BOM 없음, `Content-Encoding: identity`다. Header 생략은 HTTP identity와 동등하다. 압축, range, resumable retrieval, page/chunk는 V1에 없다. 네트워크 중단은 같은 resource 전체 재조회다.

Policy는 `inputIdentity` 밖의 transport 영역이며 `inputSetHash`, 공개 semantic `resultHash`, result digest manifest, section/manifest hash에 포함되지 않는다. Run과 Compute job에 최초 admission 값 그대로 보존한다. retry attempt, grant renewal, idempotent replay로 바꾸지 않는다. 같은 job에 다른 policy는 conflict이며 새 limit 적용은 새 Run/job lineage가 필요하다. Compute는 자체 구성 상한과 광고된 상한의 `min`을 effective maximum으로 사용한다. safe 운영 metadata에는 policy version, Core advertised maximum, Compute configured maximum, 적용된 maximum과 제한 주체(Core/Compute)만 기록한다. 사용자 식별자·locator·거래 내용은 넣지 않는다.

`maxAcceptedPayloadBytes`, descriptor `byteSize`, `payloadSha256`의 대상은 **동일한 HTTP response entity body의 exact UTF-8 bytes**다. HTTP header, transfer framing, TLS bytes는 제외한다. BOM·압축·재인코딩·JSON 재직렬화는 허용하지 않는다. Terminal descriptor는 policy version, `SINGLE_RESOURCE`, media type, `identity`, `byteSize`, SHA-256, schema/hash specification, expiry와 `payloadId`를 required로 가진다. `payloadId`는 고정 endpoint의 opaque resource identity이며 signed locator를 descriptor에 담지 않는다. `byteSize`/media type/encoding/policy/locator/expiry/attempt는 의미적 resultHash 입력이 아니다.

## Compute finalization과 Core 수신

Compute는 공개 DTO 투영 → 결정적 serialization → 실제 UTF-8 bytes 길이 측정 → effective maximum 비교 → exact bytes SHA-256 → 암호화 persistence → `COMPLETED` 순서로 처리한다. 추정치만으로 성공시키지 않는다. 초과하면 payload/descriptor를 발행하거나 `COMPLETED`로 전환하지 않고 safe `FAILED/RESULT_PAYLOAD_LIMIT_EXCEEDED`로 끝낸다. 크기 제한은 CSV 형식 거부가 아니다. 미완성 payload를 잘라 제공하지 않는다. Compute가 자체 제한을 더 작게 설정해도 advertised 계약 범위 내 정상 결과를 임의로 거부하지 않도록 배포 전 호환성 검사를 한다. 더 작은 자체 제한을 허용하려면 Core와 운영 설정을 맞추거나 정책 협상을 실패시켜야 한다.

Core는 descriptor policy/identity/expiry와 크기를 먼저 확인한다. HTTP client의 transparent decompression을 끄고 `Content-Encoding`을 body 수신 전에 검사한다. HTTP `Content-Length`가 있으면 body 전에 Core 한도 및 descriptor 길이와 대조한다. 없더라도 실제 streamed byte count로 상한을 강제한다. 실제 bytes가 상한 또는 선언 길이를 넘는 즉시 취소하고 spool을 지운다. EOF 시 header가 있었다면 header와 actual, descriptor와 actual이 모두 같아야 한다. 더 짧거나 다른 길이는 `RESULT_PAYLOAD_LENGTH_MISMATCH`다. Header는 사전 거절용일 뿐 actual count를 대체하지 않는다. EOF 전에 network/source 예외가 나면 `RESULT_PAYLOAD_STREAM_ABORTED`다.

Core 수신은 HTTP stream → 보호된 임시 spool에 작은 버퍼로 기록하면서 incremental SHA-256/byte count → 전체 length/hash 확인 → spool 재열기 → 제한된 streaming JSON parser → item identity 검증과 bounded batch staging → section/count/manifest/overall 및 Evidence 참조 검증 → publication transaction 순서다. Hash가 맞기 전 JSON parsing, Domain result 처리, staging을 금지한다. Hash mismatch는 같은 `payloadId`를 한 번만 전체 재조회하며 반복 시 `RESULT_TRANSPORT_HASH_MISMATCH`다. Parser는 전체 `ByteArray`, `String`, JSON tree 또는 전체 staged item 목록을 heap에 만들지 않는다. 객체 깊이, 문자열/token 길이, section별·전체 item 수를 제한하고 중복 key/identity를 거절한다. Batch 크기는 구성 가능하며 semantic hash와 count에 영향을 주지 않는다. Singleton manifest/quality도 운영 parser/token 한도에 묶고 큰 nested 배열은 item 단위로 staging한다. 최종 digest는 순서 독립적인 staging row를 안정 ID 순으로 읽어 계산하며 전체 item 배열을 heap에 적재하지 않는다. 부분 staging은 외부 조회와 publication에서 보이지 않는다.

Interoperability 한도는 최소 `maxAcceptedPayloadBytes` 하나다. section/total item 상한을 양 서비스가 공유해야 할 필요가 검증되면 policy의 새 version에 넣는다. Local safety 한도는 parser depth, string/token bytes, section·total item count, batch size, spool disk quota, 처리 timeout, orphan retention이다. 이 값은 서비스 설정과 capacity test로 관리하며 허용된 V1 payload를 정상적으로 처리할 수 있는 조합이어야 한다. quota/timeout 부족은 계약상 payload 초과로 위장하지 않고 일시적 의존성 실패로 재시도한다. 반복적으로 정상 범위를 거부하면 배포 호환성 결함이다.

## Spool 및 삭제

Spool은 거래값이 있는 restricted artifact다. 기존 Core encrypted artifact storage 또는 동등하게 보호된 spool abstraction과 기존 키/보안 정책을 사용한다. 일반 OS temp에 평문으로 장기 저장하지 않는다. 서버 생성 opaque 이름, 최소 권한, 별도 quota를 적용하고 path에 user ID·Binance UID·symbol·원본 filename을 넣지 않는다. path/content/locator/credential을 log, metric, error, backup에 내보내지 않는다. 성공·hash mismatch·parse/staging 실패 시 즉시 삭제한다. 실패 조사용 격리가 필요하다면 암호화된 짧은 TTL과 접근 감사가 있는 명시적 정책만 허용한다. 기본은 격리 없이 삭제다. Session 삭제/tombstone generation 상승 시 stream을 취소하고 spool·staging을 즉시 정리한다. Crash orphan은 generation과 생성시각을 기록한 sweeper가 정리하며 orphan 보존 상한은 운영 설정으로 pin하고 terminal payload 최대 PT24H를 넘지 않는다. Spool은 backup 대상이 아니다. 삭제 재검사는 publication transaction 직전에도 수행한다.

## V2 경계와 harness

V1 크기 초과는 structured limit failure다. V2는 별도 협상된 page/chunk resource, resumable retrieval와 per-page transport hash를 도입할 수 있다. Semantic section/hash는 pagination과 독립이며 V1 payload를 부분 전송하는 방식으로 V2를 흉내 내지 않는다. V2의 page schema, cursor, endpoint와 숫자 한도는 이번 결정에 포함하지 않는다.

후속 Integration은 실제 Compute result stream → Core bounded receive → hash 검증 → streaming parse/batch staging → semantic 검증 후 publication을 관통한다. 큰 비민감 synthetic payload, Content-Length 초과/생략, actual 초과, truncation, 1-byte 변조, unsupported encoding, crash/orphan cleanup, 제한 JVM heap, 수신 중 Session 삭제를 각각 검사한다. 전체 body buffering이 없음을 heap/allocator 관찰로 검증한다. 기존 fixed result vector 회귀도 유지한다. 실제 Binance CSV·UID·거래값·signed locator·credential은 fixture에 넣지 않는다.
