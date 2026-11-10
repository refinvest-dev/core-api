# Import 공개 계약 정합성 리뷰 (DRAFT)

상태: **ADR-076 검토 초안·미채택·runtime 미구현**. 현재 public/Compute OpenAPI와 채택 ADR을 변경하지 않는다.
Go Use Case 리뷰의 미해결 항목에 구체 권장안·대안·consumer 영향·채택 조건을 제시한다.
문서 PR merge·CI 성공은 제품 정책 채택/업무 구현 전환이 아니다.
기준: Context `8351e18e83c981d0199d14c74b165233ce82bd21`, Core `8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2`, Compute `113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963`, Go `e00ee26b05cab9861e733e5eef23726d12c1d5a2`, Web `9354dfe321ee8543c10e0914249f06d6643ca6db`, integration `05980836db117bb3ec26e236698956e20a91ca07`.

## 읽기와 변경 범위

1. 이 문서: filename·VersionSet·후보별 판정과 계약 적용 순서.
2. [request identity·replay](TRADING_REVIEW_IMPORT_REPLAY_DRAFT.md): operation별 legacy fingerprint·입력 동등성·원래 pin/현재 Guard.
3. [응답·오류](TRADING_REVIEW_IMPORT_RESPONSE_DRAFT.md): 상태별 DTO·coverage/quality·접수 뒤 조회·operation 응답 후보.
4. [consumer·수용 계획](TRADING_REVIEW_IMPORT_CONTRACT_ACCEPTANCE_DRAFT.md): source 근거·영향/검증/전파와 미실행 계획.

제품 정본은 [API](TRADING_REVIEW_API.md)·[현재 OpenAPI](../openapi/core-api.yaml),
[Domain](DOMAIN.md)·[Acceptance](TRADING_REVIEW_ACCEPTANCE.md),
[Lifecycle](TRADING_DATA_LIFECYCLE.md)·[Versioning](TRADING_VERSIONING.md)이다.
계산 identity/result/hash·V1/V2 oracle·ACK disposition은 변경하지 않는다. Go 계층/Store·구체 Tx binding은 Go가 소유한다.

아래는 **현재 계약의 충돌/누락을 해소하기 위한 후보**다. 이번 변경은 Markdown뿐이며
`openapi/drafts`에 새 YAML도 추가하지 않는다. 아직 내부 Import path·token profile·새 endpoint를 확정하지 않는다.
후속 schema PR은 이 리뷰의 채택 범위·consumer 수용과 [전파 절차](TRADING_REVIEW_IMPORT_CONTRACT_ACCEPTANCE_DRAFT.md)를 먼저 확인한다.

| 후보 ID | 우선 권장 | 대안·선택 이유 | Go 리뷰 연결 |
|---|---|---|---|
| IC-P01 | filename은 optional nullable 표시 metadata, 나머지 upload metadata 필수 | 필수 유지보다 파일 식별과 불필요 개인정보를 분리. 기존 값 있는 client는 계속 수용 | IUC-R01/R08 |
| IC-P02 | Session 생성 시 exact VersionSet 고정, Validate target은 같은 값의 명시 확인 | Validate 시 current로 바꾸는 대안은 생성 pin·retry/identity 의미를 바꾸므로 별도 설계 필요 | IUC-R02/R03 |
| IC-P03 | operation별 legacy A/B fingerprint 보존·known replay와 새 admission 구분 | 하나의 새 request hash로 통일하면 기존 mapping이 끊김. profile 전환은 별도 migration | IUC-R04/R05/R09 |
| IC-P04 | 기존 보조 role/canValidate 필드는 additive 명세, 검증된 report/coverage를 명시 투영 | consumer 조사 없이 제거/0 합성하지 않음. 큰 결과의 임의 truncation도 금지 | IUC-R06/R07 |
| IC-P05 | command 접수와 현재 응답 조립 분리, 누락된 오류/status·Retry 410 형식 명시 | command마다 영구 full HTTP response 저장·무조건 최초 VALIDATING 반환은 현재 조회/삭제와 충돌 | IUC-R05/R10 |
| IC-P06 | 내부 Import 인증/권한·timeout/trace profile은 후속 별도 계약 | Account/Book read/write 초안을 Import 범위로 자동 확대하지 않음 | IUC-R08/R11/R12 |

각 행은 우선안이며 미채택이다. 특히 P04의 보조 필드 의미·TTL 개선, P03의 replay 순서와 P05의
fast-terminal 응답은 현재 모든 Core 동작과 같다는 뜻이 아니다. 채택 시 구현/consumer 변경을 함께 확인한다.

## IC-P01: filename의 필수성

현재 API 설명 §2.2는 filename을 선택 metadata라고 하지만 `UploadArtifactMetadata.required`에는 filename이 있고
Core controller는 정확히 여섯 key와 nonblank filename을 요구한다. Application도 basename을 검사하며
새 artifact Domain 저장은 filename=null이다. 응답 filename은 nullable다. 설명만 보고 optional 구현을 완료했다고 할 수 없다.

권장 후보는 다음과 같다.

| 입력 | 후보 의미 |
|---|---|
| filename omitted 또는 null | 표시 이름 없음. 두 경우 같은 request identity이며 업로드 자체 허용 |
| non-null filename | 안전한 표시용 basename만 처리. 빈/제어 문자·잘못된 Unicode와 허용 길이 위반은 입력 오류 |
| 다른 filename·file part의 이름 | source identity·raw hash·public request fingerprint를 바꾸지 않음 |
| storage object key/path | 서버 생성 opaque 값만 사용. filename으로 생성/접근하지 않음 |

크기/type/hash/role/dialect의 다섯 metadata 필수값·file part·50 MiB 상한은 유지한다.
두 multipart part 순서도 유지하며 optional filename 때문에 metadata part 전체를 optional로 만들지 않는다.
표시 길이는 기존 255 한도를 유지하는 방향이며 단위는 Unicode scalar를 우선 권장한다.
Core UTF-16 length·basename 처리·stored legacy 값의 허용 범위는 schema/decoder 동시 리뷰가 필요하다.
UTF-8 byte 상한을 255로 바꾸거나 Unicode normalize/trim으로 identity를 바꾸지 않는다.

응답이 제공된 이름을 그대로 echo한다는 보장은 추가하지 않는다. 보관할 실제 표시 consumer가 없다면
서버는 null을 반환할 수 있다. 보관할 때도 raw lifecycle까지만 두며 raw와 함께 purge한다.
file header의 이름 역시 식별/인증 근거가 아니며 filename·raw cell·locator·전체 checksum을 로그/trace에 복제하지 않는다.
50 MiB와 infrastructure/metadata parser 제한은 서로 다른 한도다. 실제 proxy/중계/stream 검증 없이 새로운 숫자를 고정하지 않는다.

대안은 현재 schema의 filename 필수를 유지하고 설명을 수정하는 것이다. source identity에 이름이 필요하지 않고
현재 저장도 null이므로 optional을 우선 권장하지만, 현재 decoder/consumer가 이를 허용한다는 증거는 아직 없다.
채택 전 OpenAPI required/nullability·Core parser·Go mapper·synthetic multipart 예제와 하위 호환성을 함께 변경한다.

## IC-P02: VersionSet의 선택 시점

현재 Create source는 `versions.currentJson()`을 Session에 저장한다. Validate factory는 requested target이
있으면 Session.versionSetJson과 canonical equality를 요구하고, 실행 가능성은 currentJson 전체 equality로 확인한다.
반면 Validate request schema는 omitted target을 admission current exact set으로 설명한다.
Versioning 정본의 exact pin/immutable retry·재처리 원칙과 함께 선택 시점을 통일해야 한다.

권장 후보:

1. Create가 승인하는 exact immutable VersionSet을 Session에 고정한다. client의 Create body에 새 version 필드를 추가하지 않는다.
2. Validate `{}` 또는 targetVersionSet=null은 **Session의 원래 pin 사용**이다. 새 current를 resolve/저장하지 않는다.
3. 명시 targetVersionSet은 전체 Session pin의 exact canonical 값과 같아야 하는 확인값이다. 다른 set은 VERSION_UNAVAILABLE로 거절한다.
4. 아직 새 Run을 접수할 때는 원래 set을 실행할 수 있는지 확인한다. current label과 같다는 사실만으로 실행 가능성을 증명하지 않는다.
   배포가 exact 과거 set을 지원하면 그것을 사용할 수 있고, 지원하지 않으면 422 VERSION_UNAVAILABLE다. 가까운 버전 fallback은 없다.
5. known replay/기존 결과 조회는 현재 접근 Guard를 검증하지만 새 계산 실행 가능성을 이유로 결과를 다시 계산하지 않는다.
   운영 cutover로 해당 route 자체가 중단된 경우에는 정본의 gate/503 정책을 유지한다.
6. 명시 Retry는 원 input/VersionSet·terms·결과 쌍과 generation을 재검증한다. 버전 변경은 별도의 immutable 재처리다.

Session 전체 VersionSet과 normalization inputIdentity에 들어가는 projection은 기존 정본대로 구분한다.
analytics 부분이나 composite hash를 Go 편의로 다시 계산/제거하지 않는다. 결과 schema/hash 쌍은 계산 입력 hash와 별도 pin이다.
legacy absent pin을 current/default로 복구해 신규 실행하지 않는다. 실행 정책(retry/delivery)과 계산 pin도 같은 의미가 아니다.
새 명시 retry Run의 실행 정책 선택은 별도 수용 조건/감사 기록으로 확정하며 ADR-071 hard deadline을 도입하지 않는다.

대안은 Validate admission 시 처음 exact set을 고정하는 것이다. 그렇게 하려면 Create response의 versions 의미·
이미 생성된 Session pin·명시 요청·재시도/identity·기존 mapping을 함께 바꿔야 한다. 이번 우선안은 그 변경을 피한다.
current 버전 교체만으로 대기 Session을 암묵 upgrade하거나 accepted Revision을 다시 쓰지 않는다.

## IC-P06: 내부 wire와 적용 gate

현재 public operation 9개는 유지한다. 목표는 Web→Core(브라우저 인증/CSRF)→Go(서비스/서명 사용자 위임·
owner/Guard·업무 종단)이며 Core TR 업무 writer/Worker는 비활성화한다. 동일 업무 데이터의 이중 쓰기 운영을 요구하지 않는다.
Go는 기존 PostgreSQL의 TR 데이터만 소유하고 Member/Strategy/Backtest/Compute 데이터를 쓰지 않는다.

내부 Import contract는 다음에 별도 명세한다: caller registry·audience/type/scope와 operation registry,
서비스 인증 AND 사용자 위임·현재 authority/owner/Guard, read/admit/upload/delete/control 권한,
streaming body/part 규칙·중계 header allowlist·오류 번역·trace 상관관계, request/Compute timeout과 durable 실행/lease·raw TTL.
정확한 token/header/TTL/key 수치·internal path는 이 문서에서 새로 선택하지 않는다.
ADR-074/075는 여전히 미채택이다. signed 위임 없이 owner 숫자를 전달해도 된다는 예외를 만들지 않는다.
명시 CancelImport endpoint·새 disposition·동기 계산 API도 추가하지 않는다.

이번에 채택한 제품 정책은 없다. 다음은 이 후보의 consumer/오류·identity 수용, 후속 OpenAPI와 정확한 내부 profile 리뷰이며
문서만으로 Store/HTTP/Worker 구현이나 실제 DB/운영 전환을 시작하지 않는다.


## 내부 HTTP 후보 보완

[내부 HTTP 초안](TRADING_REVIEW_IMPORT_HTTP_DRAFT.md)·[OpenAPI](../openapi/drafts/trading-review-import.yaml)에서
IC-P01~P06의 9개 operation·별도 auth/profile·scope·req/res·오류/멱등성·stream/control 경계를 구체화했다.
현재 active public/Compute OpenAPI와 정책을 대체하지 않으며 별도 정적 checker·24개 미실행 semantic 계획을 따른다.
내부 path/profile도 미채택이다. 새 wire를 작성했다는 사실만으로 authority/저장/Worker 구현을 완료하지 않는다.
