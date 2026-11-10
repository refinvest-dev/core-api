# Account/Book ID·metadata·Unicode·timezone 호환성 검토 (DRAFT)

상태: **T04/T05 연계 검토안·미채택·새 업무 runtime 미구현**. Context `611f61d3fee6c64b69b2a5f7b6672c37fcc3b7d0`,
Core `6a4f11ca1228a803c9b1eccf334eaa2220ee3551`, Go `70eb89c7c88bf4880cf3366302f9d0a0e16a6f70`,
Compute `113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963`의 source와 로컬 표준 라이브러리 비교를 구분한다.
확정된 신규 Root Snowflake 선택·기존 opaque ID/UUID·wire/hash는 유지한다. 발급/seed·물리 저장과 Go codec은 Go가 소유한다.

## 기존 계약과 권장안의 경계

Account/Book의 path/response ID는 정확한 양의 decimal 문자열이며 JavaScript float로 손실시키지 않는다.
기존 LedgerRevision/NormalizationRun 등의 UUID와 FK·lineage·Compute 계약을 Snowflake 결정만으로 재발급/변환하지 않는다.
ID 생성 성공은 업무 접수/commit·권한의 증거가 아니다. metadata timestamp를 ID bits에서 추정하지 않는다.
Core legacy Trading Review 중단 전제는 유지한다. Core의 다른 ID publisher까지 모두 중단됐다고 가정하지 않는다.

이름은 원문/공백을 보존하는 1~100 Unicode scalar 기준을 **우선 권장**한다. grapheme·UTF-8 byte 수·UTF-16
unit 수와는 다르다. trim/NFC/NFD·대소문자 보정을 도입하지 않는다. Core DTO/Domain/중계·Go·schema의
허용 집합과 legacy 입력·저장값은 채택 전에 함께 검토한다. 이번에 active public 규칙을 바꾸지 않는다.

## T04: identity와 운영 증거

Core와 Go source의 epoch는 2024-01-01 UTC, timestamp/node/sequence 배치는 41/10/12 bits다.
하지만 Core의 process memory/@Synchronized와 Go의 DB node row는 같은 발급 authority가 아니다.
API/Worker·Core의 다른 publisher·독립 DB/schema까지 node/authority가 어떻게 분리되는지 확인해야 한다.
같은 물리 DB나 다른 table이라는 사실만으로 중복 방지/전역 ID 의미를 증명하지 않는다.

현재 Go는 state table이 없으면 실패하지만 node row가 없으면 -1/0 초기화를 시도한다. 기존 데이터/registry가
있어도 그 초기화가 안전하다는 운영 보장은 아직 없다. 권장안은 검증된 초기화와 정상 발급을 구분하고,
legacy/reserved node의 state 누락·reset/잘못된 registry·restore를 조용히 신규 namespace로 취급하지 않는 것이다.
구체 변경·권한·migration/seed는 구현 전 리뷰 대상으로 남기며 이 문서에서 적용하지 않는다.

legacy ID의 node별 high-water와 durable allocator state·이미 발급된 미사용 ID를 함께 조사한다.
business table의 MAX(id)+1이나 빈 state만으로 발급을 재개하지 않는다. backup/restore·clone은 이전 writer가
계속 발급할 수 있는지와 외부로 나간 ID까지 별도 확인한다. 실제 운영/복구 증거·throughput/SLO는 이번에 측정하지 않았다.
동일 key retry·발급한 후보 ID gap·allocator UNKNOWN과 business commit UNKNOWN도 구분한다.

## T05: metadata 정밀도와 시간의 의미

새 metadata는 Core helper의 `plusNanos(500).truncatedTo(MICROS)`와 같은 반올림 후 pin하는 안을 권장한다.
Go 표준 Time.Round(Microsecond)는 아래 로컬 8개 입력의 epoch second/nanos에서 그 helper와 같았다.
Time.Round(0)은 정밀도 정규화가 아니라 해당 표본의 nanos를 그대로 유지했다. DB round-trip 증거는 아니다.
실제 target column precision·driver·응답/replay를 확인해야 하며 지원 범위/overflow를 검증한 뒤 적용한다.

| 입력 경계 | 로컬 표준 표현식 관측 |
|---|---|
| .123456499 / .123456500 / .123456999 | .123456 / .123457 / .123457 |
| .999999499 / .999999500 | .999999 / 다음 초 |
| day carry / 1969-12-31T23:59:59.999999500Z | 다음 날 / Unix epoch |
| 9999년 말 .999999500 | year 10000으로 carry, Go JSON encode는 실패 |

원 parent .123456700→저장 반올림 .123457, 그 뒤 child 원시각 .123456900은 저장 parent보다 앞일 수 있다.
Go Book.New의 추가 parent createdAt 비교는 이 source/정밀도 반례와 cross-host clock에 비춰 제거를 우선 검토한다.
같은 Aggregate의 updatedAt>=createdAt 불변식을 제거하거나 시각을 임의 max/clamp하자는 뜻은 아니다.
Core 최초 Result와 저장 후 replay의 precision 차이 가능성은 source상 추론이며 실제 HTTP로 측정하지 않았다.

새 metadata pin은 기존 createdAt 재작성·원본 거래/source/hash 시각·영속 deadline/lease의 일괄 반올림이 아니다.
ID 발급 DB clock, metadata Application clock, lease/expiry 권위 시각을 같은 값/의미로 바꾸지 않는다.

## T05: 문자열 길이와 strict ingress

로컬 이름 검증식 13개에서 이모지 51개는 Java length=102/Go runes=51로, Core의 길이식과 Go의 허용이 달랐다.
Core DTO는 @NotEmpty/@Size(max=100), Domain은 .length를 사용한다. Go Domain만 바꾸거나 Core의 기존
중계 DTO를 그대로 재사용하면 HTTP 전체 허용 범위가 같아진다고 할 수 없다. NUL 포함 값도 길이만으로 거절되지 않는다.
실제 Bean Validation·Controller·DB insert를 실행한 결과와 이 검증식 비교를 구분한다.

현재 legacy Go encoding/json은 invalid UTF-8·단독 UTF-16 surrogate escape를 U+FFFD로 대체했다.
로컬 8개 JSON 동작에서 json.Valid/DisallowUnknownFields도 이를 거절하지 않았고 decode 후 UTF-8/rune 검증식은
대체된 값을 허용했다. 따라서 Domain만 검증하면 원 입력 손상을 알아낼 수 없다. 정상 U+FFFD 문자와 literal
backslash-u 텍스트는 유효 입력일 수 있으므로 replacement 문자나 regex를 일괄 금지하는 해법도 사용하지 않는다.

우선 권장안은 **최초 Core public parse부터 Go 내부 parse까지 strict Unicode를 적용**하여 손상 입력을
거절하고, 그 뒤 기존 원문 필드로 semantic fingerprint/Command를 만드는 것이다. raw UTF-8 검사만으로는 ASCII
surrogate escape를 판별할 수 없다. 실제 codec/검증 방식을 확정해 well-formed pair·literal escape·정상 U+FFFD와
손상을 구분해야 한다. 새 JSON library/validator·공통 Base class/새 error code는 이번에 선택/구현하지 않는다.

## T05: timezone 이름 집합과 규칙을 구분

로컬 JDK 25.0.1(tzdb 2025b)은 604개, 고정 Go 1.27.3 catalog는 598개, 로컬 Compute Python 3.11.5의
tzdata package 2026.3/TZPATH=[]는 598개 이름을 제공했다. Go/로컬 Compute 집합은 같았고 JDK와 공통은 591개다.
JDK 전용 13개 SystemV/*, Go 전용 7개 EST/Factory/GMT+0/GMT-0/HST/MST/ROC를 확인했다.
이름 포함은 product 적합성·tz rule 동등성·운영 Compute 이미지의 실제 지원을 증명하지 않는다.

우선안은 version/hash가 명시된 이름 catalog를 유지하고 SDK 교체·JDK/Compute alias 차이를 명시적으로 리뷰하는 것이다.
지금 intersection을 적용해 기존 허용 이름을 삭제하거나 Go-only 이름을 자동 확장하지 않는다. 실제 기존 저장값·
운영 TZPATH/package와 source/review timezone 양쪽의 지원, historical DST/offset을 확인해야 한다.
Go는 이름만 보유하고 Compute는 ZoneInfo로 규칙을 사용하므로 이름 집합의 일치를 계산 동등성으로 표시하지 않는다.

legacy 값의 metadata projection과 command의 Aggregate Restore/새 계산은 별도로 검토한다. 현재 Go ownerquery는
비어 있지 않은 timezone 문자열을 projection에 보존하지만 Book Restore/New는 고정 catalog를 검사한다.
이 차이를 RestoreUnchecked·default UTC·불투명한 alias 치환으로 숨기지 않는다. 읽기 projection이 계산/admission
허가를 뜻하지 않으며, alias/정책 전환이 필요하면 immutable Book/입력 pin·version/hash 의미와 함께 리뷰한다.

## 실제 관측과 미실행 수용 계획

[Go 로컬 비교 보고서](https://github.com/refinvest-dev/trading-review-api/blob/0f316eb97fe62fc52eaa2b6d8a178a865585116a/engineering/ACCOUNT_BOOK_MODEL_VERIFICATION.md#t04t05-값-표현의-로컬-비교-증거)는 순수 allocator 테스트 3개, 이름식 13개·시간 경계 8개·JSON 동작 8개,
catalog 집합/출처를 기록한다. stdlib/source 식 관측이며 새 생성 API·DB 저장·Core 실제 validation·target DB audit가 아니다.
아래 VAL-R01~R08은 새 **미실행 로컬 계획**이며 정본 Scenario ID가 아니다.

| ID | 검증할 조건 | 기대 의미 |
|---|---|---|
| VAL-R01 | 신규 Snowflake·기존 bigint/UUID·JS 큰 ID | 정확한 문자열/기존 FK·lineage 보존, cast/재발급·0 fallback 없음 |
| VAL-R02 | emoji/combining/공백·100 경계의 Core DTO→Go→DB | 승인한 길이 의미 일치, trim/normalize·계층별 숨은 제한 없음 |
| VAL-R03 | invalid UTF-8·unpaired/pair·정상 U+FFFD/literal escape | 최초 경계에서 손상 거절·원문 의미 보존, decode 후 정상화로 우회 없음 |
| VAL-R04 | 새 metadata·저장·첫 응답/replay·carry/범위 | 승인 precision으로 일치, old createdAt/source/hash/deadline 재작성 없음 |
| VAL-R05 | parent/child clock·저장 반올림 차이 | 비승인 cross-Aggregate 시각 제약 제거 검토, 자기 Aggregate 시간 불변식 유지 |
| VAL-R06 | legacy JDK-only 이름·Go-only 이름·Restore/조회 | projection·쓰기·계산 호환성 구분, alias/UTC fallback 없음 |
| VAL-R07 | 실제 Compute tz rule/버전·같은 이름의 DST 경계 | 이름 집합 일치와 재현 가능한 계산 구분, 자동 catalog/VersionSet 변경 없음 |
| VAL-R08 | 같은 key·raw Unicode/zone 이름·원본 timestamp pin | 기존 namespace/fingerprint·source/hash 보존, 재시도에 새 ID/시간 의미 강제 없음 |

다음은 E01/E02의 실제 history/catalog·ID/값 inventory·권한/backup/복구·Core 비활성화/인계 증거를 모으는 단계다.
설계 문서나 로컬 비교 성공만으로 위 정책 채택·운영 seed·runtime 활성화·업무 구현 전환을 승인하지 않는다.

근거: [Core Snowflake](https://github.com/refinvest-dev/core-api/blob/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/shared/infrastructure/src/main/kotlin/com/refinvest/core/shared/infrastructure/id/SnowflakeIdGenerator.kt),
[Core snapshot helper](https://github.com/refinvest-dev/core-api/blob/6a4f11ca1228a803c9b1eccf334eaa2220ee3551/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingJpaSnapshots.kt),
[Compute ZoneInfo identity](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/contracts/normalization/identity.py),
[Go JSON](https://pkg.go.dev/encoding/json#Unmarshal), [Java String.length](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#length()).
