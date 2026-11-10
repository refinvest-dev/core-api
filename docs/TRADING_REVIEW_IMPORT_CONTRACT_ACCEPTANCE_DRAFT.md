# Import 계약 후보 consumer·수용·전파 계획 (DRAFT)

상태: **ADR-076 미채택 리뷰/미실행 계획**. [정책 후보](TRADING_REVIEW_IMPORT_CONTRACT_DRAFT.md),
[identity/replay](TRADING_REVIEW_IMPORT_REPLAY_DRAFT.md), [response/error](TRADING_REVIEW_IMPORT_RESPONSE_DRAFT.md)를 함께 읽는다.
아래 source 관측·정적 문서 검증과 실제 runtime/DB/HTTP 수용을 구분한다.

## 조사 범위와 consumer 영향

고정 SHA는 정책 진입점에 기록했다. tracked source와 필요한 snapshot을 읽었으며 사용자 환경값·실제 데이터는 출력하지 않았다.

| 저장소·실제 경계 | source 관측 | 채택 시 영향·아직 검증하지 않은 것 |
|---|---|---|
| Core public parser/controller | filename 여섯 필드 필수·metadata 양 순서, create/validate/retry 뒤 Get | optional filename·strict 타입/unknown 처리·post-commit 의미·replay 순서/오류 보완 필요. 기존 annotation만으로 strict 검증 주장 금지 |
| Core Application/Domain/DB | 생성 VersionSet pin·Validate exact equality/current equality, 두 fingerprint helper, Store/idempotency | 원 pin 복구·legacy mapping·exact 실행 registry·lock graph/UNKNOWN을 source와 대조. Go로 무조건 그대로 이식하지 않음 |
| Core response | 보조 role/canValidate 노출, coverage/detection null 고정, quality는 publication에서 읽음 | schema additive 명시·TTL 힌트·검증된 role coverage·상태별 quality/null·현재 응답 조립 검증 필요 |
| Web src | 해당 SHA의 src에서 trading-import-sessions/TradingImportSession·보조 field/targetVersionSet 호출 미발견 | 현재 source 소비자 미발견일 뿐 외부 client/배포 consumer 부재 증명 아님. generated docs snapshot과 실제 UI 구현 구분 |
| integration normalization harness | filename을 포함해 **file 먼저 metadata 나중** upload, Validate `{}`, canValidate/uploaded roles 사용·초기 VALIDATING 단정 | 필드 제거·part 순서 제한은 breaking. fast-terminal/current replay 수용 검증은 별도이며 이번에 harness를 바꾸거나 실행하지 않음 |
| Compute | normalization inputIdentity/hash·result V1/V2·grant/ACK는 독립 pinned 계약 | 이번 후보는 bytes/VersionSet/결과 쌍/hash/ACK wire를 바꾸지 않음. detection 값이 현재 coverage에 있다는 가정 금지 |
| Go | Domain·9개 Use Case/API 설계, runtime은 business HTTP/Worker 미연결 | 해당 candidate와 기존 설계를 연결하고 저장/실행/profile 상세화. 문서/CI 성공≠실제 Go 업무 HTTP/DB 수용 |

### 고정 source 근거

- [Go Use Case 리뷰](https://github.com/refinvest-dev/trading-review-api/blob/e00ee26b05cab9861e733e5eef23726d12c1d5a2/docs/design/importing-usecases/review-and-acceptance.md),
  [매핑](https://github.com/refinvest-dev/trading-review-api/blob/e00ee26b05cab9861e733e5eef23726d12c1d5a2/docs/design/importing-usecases/api-mapping.md).
- Core [controller](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/importsession/TradingImportSessionController.kt)·[book controller](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/importsession/TradingBookImportController.kt),
  [Create](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/importsession/create/CreateTradingImportSessionService.kt)·[Upload](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/importsession/upload/UploadTradingImportArtifactService.kt)·[Validate](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/normalization/validate/ValidateTradingImportSessionService.kt)·[Retry](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/normalization/retry/RetryTradingImportSessionService.kt)·[factory](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/normalization/NormalizationAdmissionFactory.kt).
- Core [Session response](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/importsession/TradingImportSessionResponse.kt)·[Artifact response](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/artifact/TradingSourceArtifactResponse.kt)·[Session result](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/port/src/main/kotlin/com/refinvest/core/tradingreview/port/inbound/importsession/TradingImportSessionResult.kt)·[Domain](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain/TradingImportSession.kt).
- [NUL helper](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/support/TradingRequestChecks.kt)·[colon helper](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/NormalizationInputFingerprint.kt)·[멱등 adapter](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcTradingIdempotencyStoreAdapter.kt).
- [integration 실제 synthetic caller](https://github.com/refinvest-dev/integration/blob/05980836db117bb3ec26e236698956e20a91ca07/scripts/test_trading_normalization_runtime_contract.py), [Web src tree](https://github.com/refinvest-dev/web/tree/9354dfe321ee8543c10e0914249f06d6643ca6db/src),
  [Compute coverage/result 정본](../openapi/compute-api.yaml).

quality 전용 경로 부재는 고정 Core application/port/web 및 source의
`quality-report|getTradingImportQualityReport|GetTradingImportQualityReport` 검색 관측이다. 배포 API 404 실험이 아니다.
Web은 tracked src의 `trading-import-sessions|TradingImportSession|uploadedArtifactRoles|missingArtifactRoles|canValidate|targetVersionSet`
미발견을 확인했다. 이 검색 결과를 저장소 밖 client/운영 상태로 일반화하지 않는다.

## 미실행 제품 수용 계획

아래 ID는 **미채택 draft 계획**이다. 기존 TR-ACC-IMPORT-ORCH/QUALITY/INTEGRITY 및
Go IUC-A/IMP-D를 대체하지 않으며 fixture 구현/검증 성공을 뜻하지 않는다.
합의한 schema/decoder·실제 SQL/HTTP에 Given/When/Then을 연결한 뒤 증거를 남긴다.

| 계획 ID | Given / When | Then |
|---|---|---|
| TR-PLAN-IMPORT-CONTRACT-001 | filename omitted/null/유효 basename·기존 여섯 필드 요청 | 채택한 optional 규칙·다섯 필수 metadata 유지, 기존 client 수용·path/identity 미사용 |
| TR-PLAN-IMPORT-CONTRACT-002 | 빈/제어/잘못된 Unicode·255 경계·path 성분 filename | 합의된 basename/문자 단위 처리·안전 오류, 저장/로그/cleanup 수명 제한 |
| TR-PLAN-IMPORT-CONTRACT-003 | multipart 양 순서·중복 part·oversize/잘린 body·취소 | 호환 순서·actual 50MiB/hash 검증, bounded 소비·단일 Close·slot 미소비/cleanup |
| TR-PLAN-IMPORT-CONTRACT-004 | Session pin S 생성 뒤 current T 교체, Validate `{}`/null | S 유지, exact S 실행 지원 여부로 접수/422 결정·묵시 upgrade 없음 |
| TR-PLAN-IMPORT-CONTRACT-005 | Validate 명시 S/T·unknown/duplicate key·legacy absent pin | exact 확인/명확 오류, current/default로 저장 이상 보정 금지 |
| TR-PLAN-IMPORT-CONTRACT-006 | 명시 Retry·자동 retry·재처리와 실행 정책 변경 | 원 input/VersionSet/result pair·retryOf 보존, 새 Run/같은 Run 구분·정책 선택 기록 |
| TR-PLAN-IMPORT-CONTRACT-007 | A/B operation별 ASCII/Unicode parts·명시 target 정렬 표본 | 원 byte length/구분자·canonical/hash/legacy mapping 일치, 통일 hash 전환 없음 |
| TR-PLAN-IMPORT-CONTRACT-008 | Create null/explicit policy·reference null/empty, current 변경 뒤 같은 key | 최초 resolved pin으로 payload 비교·동일 resource, 오늘 current 재해석/중복 접수 없음 |
| TR-PLAN-IMPORT-CONTRACT-009 | Validate omitted/null/explicit S의 같은/새 key | B profile 요청 동등성 보존·same key 다른 payload conflict, logical 입력 동일 시 새 실행 무조건 생성 금지 |
| TR-PLAN-IMPORT-CONTRACT-010 | upload 같은 role/hash·filename 변경·size/type/dialect/key 변경 | identity와 request equality 구분·최초 descriptor/pin 불변·정확한 replay/conflict |
| TR-PLAN-IMPORT-CONTRACT-011 | 같은 key replay 시 Session 상태 진전·raw expiry/삭제·route cutover | 허용된 기존 결과 또는 현재 접근/gate 오류, 새 Run/Object/slot 복원 없음 |
| TR-PLAN-IMPORT-CONTRACT-012 | concurrent key/다른 parent·delete/control과 lock 경쟁 | owner/operation namespace·unique fence로 단일 접수/명확 conflict, 교착/부분 mapping 방지 |
| TR-PLAN-IMPORT-CONTRACT-013 | Object 성공 후 commit UNKNOWN·조회 0건·cleanup 실패 | 권위 fence로 원 접수 복구, adopted Object 보호·unresolved 재조정, 새 key 자동 재접수 금지 |
| TR-PLAN-IMPORT-CONTRACT-014 | 보조 field absent 구형/신규 response·ACTIVE inventory와 TTL equality | additive 호환, inventory set 일관·expiry canValidate=false·힌트≠실행 권한 |
| TR-PLAN-IMPORT-CONTRACT-015 | 검증된 두 role coverage·다른 Session latest·legacy proof 없음 | 정확 Session/input/Revision/role 투영 또는 null, 완전 export/detection 값 추정 금지 |
| TR-PLAN-IMPORT-CONTRACT-016 | raw-only 뒤 accepted report/coverage·정상 raw 재접근 | 보존 결과 유지·raw 차단, filename/DEK/grant 재생성 없음 |
| TR-PLAN-IMPORT-CONTRACT-017 | 다섯 Session status·rejected safe failure·운영/integrity failure | 상태별 필수/nullable report·failure·Revision 규칙, quality 0/빈 값 합성 금지 |
| TR-PLAN-IMPORT-CONTRACT-018 | quality query: exact report/미완료/rejected-null/손상·대형 issues | 정확 200 또는 unavailable/안전 오류, 다른 report 대체·임의 truncation 없음·bound 검증 |
| TR-PLAN-IMPORT-CONTRACT-019 | command commit 뒤 Worker 즉시 terminal·동일 key replay | 원 성공 status와 현재 full DTO, 최초 VALIDATING 강제/중복 Run 없음 |
| TR-PLAN-IMPORT-CONTRACT-020 | commit 뒤 조회 전 삭제·DB dependency/응답 유실·browser abort | 현재 접근/오류와 접수 사실 구분, 같은 key 복구·durable 작업 유지 |
| TR-PLAN-IMPORT-CONTRACT-021 | operation별 invalid path/query·owned deletion·exact version/의존성 오류 | 채택한 response/status 명시·safe envelope, foreign 404와 owned 410·archive 구분 |
| TR-PLAN-IMPORT-CONTRACT-022 | Retry raw 만료/삭제·저장 Restore/CAS/UNKNOWN 오류 | 전용 410 필수 userAction/occurredAt·false retryable, 내부 원인을 blanket 400/409로 축약 금지 |
| TR-PLAN-IMPORT-CONTRACT-023 | raw/Session 삭제와 publication·count/closure·purge/replay | raw-only 결과 보존 / Session 종속 immutable closure·단일 Tx/Guard·실제 부재·control 유지 |
| TR-PLAN-IMPORT-CONTRACT-024 | 서비스/사용자 위임 위조·read token write 사용·trace/timeout 중계 | 후속 채택 profile의 auth/operation 격리·현재 owner/Guard, safe trace·request와 durable 수명 분리 |

## 채택·schema·전파 순서

1. IC-P01~P06의 채택 범위와 기존 consumer·legacy 자료/불명확성·응답/오류·version 실행/수명 정책을 리뷰한다.
   이번 네 문서/ADR-076은 계속 DRAFT다. 기존 API/계산/삭제 불변식을 완화하는 근거가 아니다.
2. 후속 Context PR에서 채택한 public required/nullability·선택 시점·보조 필드·operation error/status를 OpenAPI에 반영하고
   정확한 내부 Import 권한/profile/wire와 정적 수용/거부 예제를 별도 정의한다. 위험한 schema 변이를 검증한다.
3. 새 contract file을 만들면 integration manifest에 분류를 먼저 마련한다. **현재 네 문서는 docs/*.md wildcard로
   Core docs snapshot 대상**이다. 새 YAML/fixture는 없으므로 이번에 map 변경은 필요 없다.
4. Context PR에서는 `contract_sync.py plan`으로 분류/대상/변경 파일을 확인한다. plan은 일치 검증/적용이 아니다.
   merge 후 정본 SHA의 Core 문서 snapshot PR·marker와 검증을 별도 확인한다. 이 단계는 정책/새 업무 구현 활성화가 아니다.
5. Core는 TR writer/Worker 중단·중계 경로를, Go는 확정한 업무 범위/Guard/Store/Worker를 별도 구현한다.
   같은 DB의 TR 테이블만 인계하고 Member/Compute 테이블·운영 설정/DB를 이번에 변경하지 않는다.
6. 실제 target/history·정책·auth·단일 쓰기·ID·snapshot·후속 수용 fixture가 준비되면 격리 integration에서 필요한 검증을 수행한다.
   E2E 전 verify-contract-sync.ps1 필수, Web 포함 시 -IncludeWeb. 기존 Core→Compute smoke는 Go 업무 E2E가 아니다.

이번 검증은 문서/고정 source·기존 정적 canonical checker·분류/impact plan 범위다.
위 24개 계획, 새 schema/serializer·internal JWT/HTTP·SQL 경합·Object/Compute·실제 DB 인계/E2E는 미실행이다.
public/Compute OpenAPI·golden vector·기존 fixture bytes를 그대로 유지한다. Determinism/Point-in-Time 영향 없음.
