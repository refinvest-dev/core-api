# Trading Normalization Result Integrity V1

ADR-066의 공개 결과 계약이다. 입력 identity는 [TRADING_NORMALIZATION_IDENTITY.md](TRADING_NORMALIZATION_IDENTITY.md)를 따른다. Compute 내부 `ReconciliationManifest`/`NormalizationResult` 전체에 대한 기존 내부 hash는 공개 계약 hash의 oracle이 아니다. Core는 받은 공개 DTO만 투영·정규화하고 staging에서 독립 재계산한다.

## 계층과 전송

| 계층 | 필드 | 입력 |
|---|---|---|
| 전송 | `payloadSha256` | `GET /trading-review/terminal-payloads/{payloadId}`의 HTTP response **entity body** exact bytes |
| 조정 명세 | `manifestHash` | 공개 `ReconciliationManifestPayload`의 아래 의미 필드 |
| section | `sectionHash` | section 전체의 정렬된 공개 item 배열 또는 단일 객체 |
| 전체 | `resultHash` | 공개 `NormalizationResultDigestManifest` |

V1 전송은 단일 terminal JSON resource이며 page/chunk 분할은 지원하지 않는다. `payloadSha256`은 `Content-Encoding: identity`로 전송한 UTF-8 JSON entity body exact bytes의 SHA-256 lowercase hex다. BOM, 압축, charset 재인코딩, JSON 재직렬화 뒤 bytes 검증은 허용하지 않는다. `Content-Type`은 `application/vnd.refinvest.trading-normalization+json; charset=utf-8`이며 HTTP header, chunk framing, TLS bytes는 대상이 아니다. Descriptor의 `byteSize`도 그 entity body 길이다. 손상 시 동일 `payloadId` 한 번 재조회하고 다시 다르면 `RESULT_TRANSPORT_HASH_MISMATCH`다. ACK/발행 전에는 resource를 보관한다. 크기 정책·bounded-memory 수신·spool lifecycle은 [result delivery V1](TRADING_NORMALIZATION_RESULT_DELIVERY.md)을 따른다.

향후 page/chunk 도입은 새 delivery version과 OpenAPI endpoint가 필요하다. V1은 page descriptor나 cursor를 수신하지 않는다. V2의 per-page transport hash를 section hash로 사용하거나 연결해 section hash를 계산하지 않는다. 어떤 분할/수신 순서든 같은 unique item 집합은 같은 semantic section hash를 얻어야 한다.

## 공개 입력과 count

V1 `resultSchemaVersion=TRADING_NORMALIZATION_RESULT_V1`, `resultHashSpecificationVersion=TRADING_NORMALIZATION_RESULT_HASH_V1`이다. 모든 section은 필수이고 빈 collection은 `[]`/count 0으로 표현한다. null, 누락, `NOT_APPLICABLE` section은 V1에 없다. Count는 서로 다른 개체의 수이며 page envelope/중복 수신은 포함하지 않는다.

| `NormalizationResultCounts` 필드 | population / source |
|---|---|
| `artifactCoverageCount` | `artifactCoverages`의 role별 객체; 정확히 2 |
| `canonicalRecordCount` | `canonicalRecords`의 서로 다른 `recordId`; 제외 episode를 지지하는 record도 포함 |
| `sourceEvidenceSnapshotCount` | `sourceEvidenceSnapshots`의 서로 다른 `snapshotId` |
| `reviewUnitCount` | `reconciliationManifest.reviewUnits`의 서로 다른 `reviewUnitId`; 제외 unit 포함 |
| `positionAllocationCount` | 모든 review unit의 `allocations`에 있는 서로 다른 `allocationId` |
| `excludedReviewUnitCount` | `exclusionReasons`가 비지 않은 review unit의 수; reviewUnitCount의 부분집합 |
| `capabilityCount` | top-level `capability`의 서로 다른 enum 값 수; unit 내부 capability는 별도 collection 아님 |
| `validationIssueCount` | `qualityReport.validationIssues`의 원소 수; issue identity는 `(artifactRole,sourceRowNumber,code,field,severity)`이며 같은 tuple 재수신은 중복 |
| `reconciliationMatchCount` | `reconciliationManifest.reconciliationMatches`의 서로 다른 `matchId` |
| `reconciliationIssueCount` | `reconciliationManifest.reconciliationIssues`의 서로 다른 `issueId` |

`qualityReport`와 `reconciliationManifest`는 각각 singleton object여서 section `itemCount=1`이다. Count object의 모든 필드는 0 이상 정수이며 terminal·digest manifest의 count가 정확히 같아야 한다. Core는 각 count를 수신 unique item, staging DB 실제 행/관계, 해당 section digest `itemCount`와 비교한다. Nested allocation/제외/validation count도 staging에서 직접 센다. 동일 identity에 다른 public item payload는 `RESULT_ITEM_CONFLICT`이고 어느 쪽도 채택하지 않는다.

## 공개 canonical projection

RFC 8785 JCS를 입력 identity와 동일하게 쓴다: UTF-8, BOM/공백/final newline 없음, UTF-16 code unit 순서로 object key 정렬, ECMAScript string escaping, lone surrogate/중복 key 거절, 문자열 Unicode normalization 없음, SHA-256 lowercase hex. JSON number는 count·sourceScale·rowNumber만 허용하며 비음수 안전 정수(`<= 9007199254740991`)다. 금융 수량/금액/가격은 JSON string plain decimal로만 쓴다. `-0`은 `0`, 불필요한 정수부 0과 소수부 후행 0을 제거해 `1.2300`과 `1.23`은 같은 `"1.23"`이다. 0이 아닌 값의 부호는 유지한다. 원본 scale이 의미 있는 `sourceScale`은 그대로 포함한다. UTC instant는 `YYYY-MM-DDTHH:mm:ss[.fraction]Z`이며 fraction 후행 0을 제거하고 0이면 생략한다. offset을 UTC로 변환하고 서버 locale을 쓰지 않는다. enum은 OpenAPI 대문자 wire 값 그대로다. Boolean은 JSON `true`/`false`다.

Public DTO의 OpenAPI가 선언한 필드만 투영한다. 필수 필드는 누락/null 금지, optional 필드는 값이 없으면 **생략**하고 명시적 null은 허용 schema에서만 유지한다. 빈 배열은 `[]`로 유지하고 누락과 다르다. `recordHash`, `manifestHash`, `sectionHash`, `resultHash` 같은 파생 hash 및 `capturedAt`은 item semantic projection에서 제외한다. `sourceEvidenceSnapshotId`, `recordId`, `snapshotId`, artifact/source row reference는 포함한다. Evidence snapshot의 `allowlistedFields`는 `name`으로 정렬하고 name 중복은 실패한다. source row reference만 보관하며 raw CSV bytes를 복제하지 않는다. 이 공개 DTO에 없는 Compute 내부 필드는 hash 입력이 아니다. 내부 audit hash는 다른 이름으로 유지할 수 있다.

| sectionType | 공개 source | itemCount | 정렬 key |
|---|---|---|---|
| `ARTIFACT_COVERAGES` | `artifactCoverages` | artifactCoverageCount | `artifactRole` |
| `CANONICAL_RECORDS` | `canonicalRecords` | canonicalRecordCount | `recordId` |
| `SOURCE_EVIDENCE_SNAPSHOTS` | `sourceEvidenceSnapshots` | sourceEvidenceSnapshotCount | `snapshotId` |
| `REVIEW_UNITS` | `reconciliationManifest.reviewUnits` | reviewUnitCount | `reviewUnitId` |
| `CAPABILITIES` | top-level `capability` | capabilityCount | enum wire value |
| `QUALITY_REPORT` | `qualityReport` | 1 | singleton |
| `RECONCILIATION_MATCHES` | `reconciliationManifest.reconciliationMatches` | reconciliationMatchCount | `matchId` |
| `RECONCILIATION_ISSUES` | `reconciliationManifest.reconciliationIssues` | reconciliationIssueCount | `issueId` |
| `RECONCILIATION_MANIFEST` | `reconciliationManifest` | 1 | singleton |

키 비교는 UTF-16 code unit ordinal이다. `reviewUnits[].allocations`는 `allocationId`, unit `capability`/`exclusionReasons`와 top-level capability는 wire 문자열, `validationIssues`는 위 identity tuple, quality report의 key/value maps는 JCS key 순서로 정렬한다. Match의 `candidateReviewUnitIds`/`evidenceRecordIds`, issue의 `evidenceRecordIds`는 ID 순, match `deltas`는 `field` 순으로 정렬하고 중복 field를 거절한다. 다른 배열은 OpenAPI에 선언된 의미 순서 또는 stable ID 순서로 정렬한다. V1에 순서 없는 새 배열을 추가하려면 먼저 stable identity와 정렬 규칙을 V2에 정의한다. DB 삽입 순서, page 수신 순서, label, generatedAt, 실행 때 새로 만든 random UUID는 정렬 key가 아니다. `allocationId`는 source execution record + review unit + allocation action + reversal side의 결정적 식별자이며 Compute가 동일 입력에서 같은 값을 내야 한다. `matchId`는 summary record, `issueId`는 issue code와 참조 대상/evidence의 결정적 식별자다.

`sectionHash = SHA-256(JCS(sorted public section items))`. Collection은 전체 배열(`[]` 포함), singleton은 객체 자체를 hash한다. `RECONCILIATION_MANIFEST` singleton은 아래 manifest hash 입력 객체와 동일하므로 그 sectionHash는 manifestHash와 같다. `QUALITY_REPORT`는 공개 quality DTO 전체다. `REVIEW_UNITS` section은 manifest에 중첩된 같은 공개 unit projection이다.

## Manifest와 전체 digest

V1 `manifestHash` 입력 M은 수신한 공개 `ReconciliationManifestPayload`에서 **`manifestHash` 필드 하나만 제거한 객체**다. `counts`를 포함한 다른 필드를 생략하거나 staging에서 재계산한 값으로 치환하지 않는다. `manifestHash = lowercaseHex(SHA-256(UTF-8(JCS(M))))`이다. JCS가 object key를 정렬하므로 수신 JSON의 필드 순서는 결과에 영향을 주지 않는다. 아래 목록은 OpenAPI의 V1 required 필드 전체이며, hash 입력에서는 이 중 `manifestHash`만 제외한다: `manifestVersion`, `manifestHashSpecificationVersion`, `inputSetHash`, `executionInputHash`, `canonicalRecordSetHash`, `qualityReportHash`, `instrumentTermsHash`, `versionSetHash`, `sourceArtifactHashesByRole`, `counts`, `manifestHash`, `reconstructionPolicyVersion`, `reconciliationVersion`, `artifactCoverages`, `reviewUnits`, `reconciliationMatches`, `reconciliationIssues`, `capability`, `quantityConservationPassed`, `cashFlowConservationPassed`. `counts` 누락/null은 정상 manifest가 아니다. 내부 필드와 값의 범위는 기존 OpenAPI `NormalizationResultCounts`를 그대로 따르며, 새 count key를 추가하지 않는다. 빈 section의 count 0도 required `counts`의 값으로 hash한다.

Nested collection은 위 정렬을 적용한다. `sourceArtifactHashesByRole`은 role→원본 SHA-256이며 identity artifact와 대조한다. `qualityReportHash`는 QUALITY_REPORT.sectionHash다. `executionInputHash`는 Compute provenance 선언값이고 Core가 재계산할 수 없는 별도 audit 입력이다. 공개 manifest hash 자체는 수신한 공개 DTO에서 재계산 가능하다. 임시 locator, `generatedAt`, `capturedAt`, worker/attempt/poll 시각, HTTP metadata는 공개 `ReconciliationManifestPayload`의 필드가 아니므로 M에 없다. Optional/null 추가는 V2에서 결정한다.

`NormalizationResultDigestManifest`의 정확한 root 필드는 `resultHashSpecificationVersion`, `resultSchemaVersion`, `inputSetHash`, `inputHashSpecificationVersion`, `targetVersionSet`, `counts`, `sectionDigests`, `reconciliationManifestHash`다. `sectionDigests`는 9개 sectionType을 wire value 순으로 정렬하고 각각 `{sectionType,itemCount,hashSpecificationVersion,sectionHash}`를 가진다. 각 `hashSpecificationVersion`은 root와 같다. `resultHash = SHA-256(JCS(NormalizationResultDigestManifest))`. Manifest에는 `resultHash` 자체가 없다. Core Run/job/attempt, deletion generation, locator/expiry, createdAt, terminalAt은 publication gate에서 별도 비교하지만 의미적 result hash에는 포함하지 않는다. 동일 input/VersionSet/결과 쌍의 정상 재시도는 같은 resultHash가 나와야 한다. `ledgerContentHash`는 `CANONICAL_RECORDS.sectionHash`와 같은 값으로만 전달한다; 과거 내부 record-set hash를 그 이름으로 보내지 않는다.

Core는 terminal identity, inputSetHash/spec, exact VersionSet, current deletion generation, V1 단일 resource, 전송 bytes, count, staging count, section hashes, manifest hash, overall result hash와 evidence→record/unit/artifact/source row 참조를 모두 검증한 뒤 발행한다. Consumer의 검증 순서는 (1) payload schema와 required `counts` 검증, (2) 수신 manifest에서 `manifestHash`만 제거, (3) 수신 `counts`를 포함한 manifest hash 재계산, (4) 선언된 `manifestHash`와 비교, (5) staging 실제 count 계산, (6) 수신 `counts`와 실제 count 비교, (7) section digest 검증, (8) `resultHash` 검증이다. 수신 `counts`를 실제 count로 먼저 바꿔서 hash하면 안 된다. Manifest hash 일치는 실제 count 일치를 뜻하지 않는다. 하나라도 실패하면 Revision/latest pointer를 발행하지 않는다. Compute 금융 계산을 Core가 재수행하지는 않는다. 미지원 result schema/hash version은 `RESULT_SCHEMA_UNSUPPORTED`다. V1 의미 변경은 금지하며 규칙 변경은 V2와 별도 vector를 요구한다. LedgerRevision provenance에 schema/spec/version, counts, section digests, manifestHash, resultHash를 보존하고 기존 Revision hash를 덮어쓰지 않는다. 새 버전 재처리는 새 Revision이다.

고정 비민감 oracle은 `fixtures/trading-normalization/result-integrity-v1.json`이다. 기존 `verify-contract-sync.ps1`과 input identity runtime test는 이 의미적 result integrity를 검증하지 않는다. 후속 Integration은 Compute 생성→전송→Core staging→수신 manifest hash→실제 count→section/overall hash 검증을 관통해야 한다.

Manifest의 `artifactCoverages`/`capability`는 top-level collection과 정확히 같아야 한다.
`canonicalRecordSetHash`는 canonical section, `qualityReportHash`는 quality section digest와
같아야 한다. Match/issue의 모든 record/unit reference도 staging에 존재해야 한다.

## Integrity failure 처리

아래 code는 source CSV의 지원 정책 거부가 아니다. 모두 `retryable=false`, 공개 Session
`PROCESSING_FAILED`, Core Run `TERMINAL_FAILURE`, 사용자 action `WAIT_FOR_RECOVERY`다.
발행과 `PERSISTED` ACK를 금지하고 staging을 폐기하며 Compute terminal resource는 TTL로
정리한다. 삭제 generation 증가에 따른 `DISCARDED_STALE`만 별도 경계다. 운영 로그는
opaque Run ID, section/code, hash prefix 최대 12자만 기록하고 raw row/UID/symbol/거래값/
locator/credential은 기록하지 않는다.

| code | 공개 message | 재조회 |
|---|---|---|
| `RESULT_TRANSPORT_HASH_MISMATCH` | 결과 전송을 확인할 수 없습니다. | 동일 resource 한 번 |
| `RESULT_COUNT_MISMATCH` | 결과 항목 수를 확인할 수 없습니다. | 없음 |
| `RESULT_SECTION_HASH_MISMATCH` | 결과 항목의 무결성을 확인할 수 없습니다. | 없음 |
| `RECONCILIATION_MANIFEST_HASH_MISMATCH` | 조정 결과의 무결성을 확인할 수 없습니다. | 없음 |
| `RESULT_HASH_MISMATCH` | 전체 결과의 무결성을 확인할 수 없습니다. | 없음 |
| `RESULT_SCHEMA_UNSUPPORTED` | 결과 형식을 처리할 수 없습니다. | 없음 |
| `EVIDENCE_REFERENCE_INVALID` | 결과 근거를 확인할 수 없습니다. | 없음 |
| `RESULT_ITEM_CONFLICT` | 결과 항목이 서로 일치하지 않습니다. | 없음 |

## V2 Analytics 입력 보충 section (ADR-070)

V1 위의 모든 규칙과 [V1 fixed vector](../fixtures/trading-normalization/result-integrity-v1.json)은 그대로 유효하다. V2 terminal은 동일한 단일 resource/media type/delivery policy를 사용하며 `resultSchemaVersion=TRADING_NORMALIZATION_RESULT_V2`, `resultHashSpecificationVersion=TRADING_NORMALIZATION_RESULT_HASH_V2`를 고정한다. 두 값의 교차 조합, V1에 `analyticsInputSupplement` 추가, V2에서 누락은 `RESULT_SCHEMA_UNSUPPORTED`다. Core와 Compute는 지원 가능한 정확한 쌍을 registry에서 선언한다.

V2 terminal의 새 필수 root 필드는 정확히 `analyticsInputSupplement`이다. 그 값은 singleton 객체 `AnalyticsInputSupplementV1`이고 정확히 `schemaVersion=TRADING_ANALYTICS_INPUT_SUPPLEMENT_V1`과 `reviewUnitEngineInputs` 두 필드가 필수다. 다른 필드, null, 누락은 금지한다. `reviewUnitEngineInputs`는 공개 `reconciliationManifest.reviewUnits`의 `reviewUnitId`별 정확히 한 행이며 UTF-16 code unit ordinal 오름차순이다. 행의 정확한 필수 필드는 `reviewUnitId`, `evidenceRecordIds`, `stableEpisodeTimeAvailable`, `allocationEvidence`이고 추가 필드는 금지한다. `allocationEvidence`는 그 unit의 공개 `allocations`와 `allocationId`별 정확히 1:1이며 같은 방식으로 오름차순이다. 각 행의 정확한 필수 필드는 `allocationId`, `evidenceRecordIds`, `allocatedFee`, `allocatedReportedPnl`이고 추가 필드는 금지한다. unit/Allocation ID의 전역 중복과 배열 내 중복을 모두 거절한다. 제외 unit과 Allocation 없는 shell unit도 행이 필요하며 이때 `allocationEvidence=[]`이다. ReviewUnit 자체를 이 section에 복제하지 않는다.

Unit `evidenceRecordIds`는 **해당 unit에 Compute가 직접 붙인 근거**다. Allocation evidence의 단순 합집합으로 재계산하지 않는다. 조정 과정에서 붙은 Position Summary record가 포함될 수 있으며 Allocation 없는 shell은 Summary record를 가리킬 수 있다. Allocation `evidenceRecordIds`는 그 Allocation이 사용한 공개 `recordId`의 EXECUTION 하나와 **동일 `sourceExecutionKey`/`instrumentId`의 FEE 하나, VENUE_REPORTED_PNL 하나**의 정확한 3개 ID다. 실제 0도 FEE/PnL canonical record의 근거를 유지한다. 양쪽 배열의 모든 원소는 비어 있지 않은 canonical `recordId` 문자열이며 각각 중복 없이 UTF-16 ordinal 오름차순이다. Unit 배열은 비어 있으면 실패한다. 모든 ID는 **이 terminal의** `canonicalRecords` 및 발행될 Revision membership에 존재해야 한다. 각 Allocation 근거 ID는 unit 직접 근거에도 포함되어야 한다. Unit의 직접 근거는 같은 `instrumentId`의 record만 가리킨다. Unit에 추가된 Summary record는 공개 match/issue의 해당 unit reference로 설명 가능해야 한다. 각 record의 `sourceEvidenceSnapshotId`는 수신 snapshot, 해당 Book/Artifact/row에 귀속돼야 한다. Core는 이 참조를 독립 검증하지만 fee/PnL 배분 계산을 재실행하지 않는다.

`allocatedFee`와 `allocatedReportedPnl`은 각 Allocation에서 **항상 필수이고 non-null**인 기존 `TradingMoney` 객체 `{amount,currency,sourceScale}`다. `amount`는 finite plain decimal JSON string으로 정규화되어 `-0`은 `0`, 불필요한 trailing zero는 제거한다. `sourceScale`은 해당 원천 계산의 0 이상 안전 정수로 보존하며 값이 그 scale로 정확히 표현 가능해야 한다. `currency`는 비어 있지 않은 pinned instrument/fee asset 식별자다. Reported PnL currency는 instrument settlement asset과 같고, fee currency는 연결된 canonical fee record asset과 같아야 한다. 공개 Allocation에 non-null 값이 있으면 보충값과 amount·currency·sourceScale까지 정확히 일치해야 한다. 공개 값이 null/생략돼도 보충값은 필수다. 실제 `0`은 `amount:"0"`과 원래 currency/sourceScale로 전달하며 누락, null 또는 임의 0 대체는 실패한다. fee/PnL record가 없다는 정상적인 상태가 정의된 경우에도 Compute가 실제 배분한 0만 전달한다. Core는 보충값과 source record의 관계를 검사하되 합계·분할을 재계산해 보충값을 만들지 않는다.

`stableEpisodeTimeAvailable`은 필수 JSON boolean이고 해당 공개 unit `capability`에 `STABLE_EPISODE_TIME`이 있는 것과 정확히 같다. 시간 필드 존재만 보고 Core가 추론하지 않는다. 불일치는 `EVIDENCE_REFERENCE_INVALID`다. 위 참조/대응/금액 불일치도 같은 code이며 schema·필수 필드·정렬 위반은 `RESULT_SCHEMA_UNSUPPORTED` 또는 중복 ID에 `RESULT_ITEM_CONFLICT`다. 어느 경우든 Revision/latest 발행과 `PERSISTED` ACK를 금지한다.

## 결과 버전 pin과 publication gate

Core가 Run admission에 고정한 `resultSchemaVersion`/`resultHashSpecificationVersion` 쌍을
Compute create가 받아 Job에 보존한다. accepted 및 PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
status는 이 저장된 쌍을 반환한다. Compute는 terminal descriptor, payload root,
`resultDigestManifest`에 같은 쌍만 쓴다. Core는 transport와 schema 검증 후, staging 및
Revision publication 전에 descriptor와 payload root/digest의 쌍이 서로 같고 Run pin과
Job status pin에 정확히 일치하는지 확인한다. 다른 지원 쌍으로 완전히 유효한 결과라도
`RESULT_VERSION_PIN_MISMATCH`로 발행하지 않으며 `PERSISTED` ACK를 보내지 않는다.
이는 `resultHash`만으로 대체할 수 없는 correlation 검증이다. V1/V1과 V2/V2의
result hash 계산 및 기존 fixed vector는 변경하지 않는다.

V2도 `NormalizationResultCounts`의 **기존 10개 필드만** 사용한다. terminal, manifest, digest manifest의 count는 동일하다. 새 `ANALYTICS_INPUT_SUPPLEMENT` section의 `itemCount=1` (빈 unit 배열이어도 singleton 객체 1개), 내부 `reviewUnitEngineInputs.length=reviewUnitCount`, 모든 `allocationEvidence.length`의 합=`positionAllocationCount`다. V2 `sectionDigests`는 기존 9개에 `ANALYTICS_INPUT_SUPPLEMENT`를 더한 정확히 10개이며 sectionType wire value의 UTF-16 ordinal 오름차순이다. 각 digest의 `hashSpecificationVersion=TRADING_NORMALIZATION_RESULT_HASH_V2`다.

보충 section의 `sectionHash=lowercaseHex(SHA-256(UTF-8(JCS(analyticsInputSupplement))))`이며 객체 전체를 투영한다. 파생 hash나 timestamp 제외 규칙은 이 객체에 해당하지 않는다. 다른 9개 section의 **입력 projection과 hash bytes는 V1과 동일**하다. 특히 `RECONCILIATION_MANIFEST` section은 수신 manifest에서 `manifestHash`만 제거한 객체다. Manifest의 `manifestHashSpecificationVersion`은 V2에서도 `TRADING_NORMALIZATION_RESULT_HASH_V1`이고 그 hash와 section hash는 같다. `counts`에 새 필드를 넣지 않으며 ReviewUnit/Allocation 공개 형태도 바꾸지 않는다. `ledgerContentHash`는 여전히 `CANONICAL_RECORDS.sectionHash`다.

V2 `NormalizationResultDigestManifest`의 root 필드 8개는 V1과 같고, `resultSchemaVersion`/`resultHashSpecificationVersion`에 V2를 쓰며 `sectionDigests`에 10개를 넣는다. `resultHash=lowercaseHex(SHA-256(UTF-8(JCS(resultDigestManifest))))`이고 `resultDigestManifest`/terminal/descriptor의 version, counts, ordered sectionDigests, `reconciliationManifestHash`, `resultHash`는 서로 정확히 일치해야 한다. 따라서 보충값이 바뀌면 section hash와 전체 result hash가 바뀌지만 manifest hash는 바뀌지 않는다. Descriptor는 section 본문을 복제하지 않고 V2 version과 10번째 digest를 노출한다. Transport bytes hash는 그 보충 객체를 포함한 exact entity body를 대상으로 한다.

Core는 transport·manifest·count·section·result 및 1:1/근거 검증을 모두 완료한 후 같은 publication transaction에 보충 객체를 새 LedgerRevision의 불변 데이터로 저장한다. Analytics input 생성은 **그 저장값만** `AnalyticsLedgerInputV1.reviewUnitEngineInputs`로 복사한다. Canonical record, 공개 Allocation 또는 capability에서 누락 근거·금액을 역산하거나 기본값을 채우지 않는다. 과거 V1 Revision을 읽는 decoder와 hash를 유지한다. V1 Revision에는 이 객체가 없다는 사실을 보존하고 Analytics admission은 `ANALYTICS_INPUT_UNAVAILABLE`로 거절한다. 새 V2 Revision을 만드는 명시적이고 호환되는 재처리만 분석 가능 상태로 만들 수 있다. Raw가 없으면 canonical record와 pinned terms로 가능한 `RERUN_RECONSTRUCTION`/`REBUILD_LEDGER`만 허용하며 registry/입력 부재에는 기존 재처리 실패 code를 쓴다. V1 신규 생산 중단·reader 퇴역은 별도 migration/registry 결정이고 이미 발행된 V1을 변조하지 않는다.

V2 비민감 oracle은 [result-integrity-v2.json](../fixtures/trading-normalization/result-integrity-v2.json)이다. `sectionVectors`는 10개 hash와 itemCount, `manifestVector`와 `resultDigestManifestVector`는 각 JCS 입력과 기대 hash, `invalidCases`는 참조 불일치 실패를 명시한다. 기존 V1 oracle은 수정하지 않는다.
