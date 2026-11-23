# Owner Query 위임 발급기 구현·검증

상태: 2026-11-23 ADR-086 첫 구현. 발급 capability·키 준비·Kotlin→Go 선택 vector 검증까지다.
[상세 설계](OWNER_QUERY_DELEGATION_ISSUER.md)와 [Context 정본](https://github.com/refinvest-dev/context/blob/8f00652381ef3c81b9fd11b7e4e452c8f98c451c/docs/TRADING_REVIEW_OWNER_QUERY_AUTH_POLICY.md)을 따른다.

## 구현 경계

[Application](../auth/application/src/main/kotlin/com/refinvest/core/auth/application/delegation/IssueOwnerQueryDelegationService.kt)은
기존 CurrentMemberIdProvider와 Clock·새 UUID jti로만 발급한다. 입력 없는 execute()/Result이며 빈 Command나
외부 owner/caller/scope 입력은 없다. [signer](../auth/adapter/security/src/main/kotlin/com/refinvest/core/auth/adapter/security/delegation/NimbusOwnerQueryDelegationSigner.kt)는
고정 RS256 header/claims·단일 string audience·integer NumericDate·정확한 60초 만료를 생성한다.
[키 준비](../auth/adapter/security/src/main/kotlin/com/refinvest/core/auth/adapter/security/delegation/OwnerQuerySigningKey.kt)는
JCA RSA 복사 snapshot과 실제 key-pair 서명 검증을 수행한다. 키와 Result는 redacted toString이며
getter/library 실패는 원 cause 없이 안전한 내부 분류로 반환한다.

자동 component/Bean·HTTP/proxy·권위 state·migration·secret/env/배포를 추가하지 않았다. 기존 Core
startup과 브라우저 HS256·CSRF는 유지한다. 이 명시 준비 예외를 Core의 일반 component scanning
관례 변경이나 실제 auth/권위 운영 인계로 해석하지 않는다.

## 실제 검증

| 검사 | 결과/범위 |
|---|---|
| 첫 Core Gradle 실행 | 81 tests 통과, 실패/skip0. 새 Application4·security6·producer1, 기존 auth16·app 회귀51·auth boundary3 |
| producer metadata/새 테스트 가독성 정리 후 | 관련27 tests 재검증 통과. app의51/boundary3은 앞선 실행 증거이며 새로 실행했다고 하지 않음 |
| Core 모듈 의존 | verifyAggregateModuleBoundaries와 AuthModuleBoundaryTest3 통과 |
| Context 문서 snapshot | 선행 Core #147의 정확 commit 기반으로 72 files 일치. 이 작업이 snapshot을 직접 편집하지 않음 |
| Kotlin→Go 호환 | 실제 Core 생성 artifact18 cases와 고정 공개 snapshot18 cases 모두 통과. 39개의 Go Test/subtest pass event |
| Go 경계/정적 검사 | 아키텍처31 tests와 memberauthority/architecture 대상 vet 통과 |

[Core producer test](../app/src/test/kotlin/com/refinvest/OwnerQueryDelegationInteropTest.kt)는 메모리에서만
2048/4096 RSA private key를 생성한다. export에는 public n/e/kid, .invalid issuer와 가상 Member·고정
과거 시각의 testOnly token/negative case만 담는다. private key·실제 credential·사용자 데이터는 없다.

18 cases 중 정상4는 2^53 초과 ID, max int64 ID, 4096 RSA key, exp+4 leeway다. 거절14는 exp+5,
미래 iat, issuer/kid/audience/actor/type, 배열 audience, 추가 role/header, unsigned payload·signature
변조, duplicate subject, 브라우저 HS256이다. 거절 시 authority stub이 호출되지 않음을 확인했다.

Go CI의 고정 fixture 검증과 매번 Kotlin을 실행한 검증은 구분한다. 이번 로컬 호환 실행은 새 Core
artifact를 환경변수로 명시 전달했다. producer 재실행은 새 test key/token을 만들기 때문에 byte가
달라질 수 있다. Go 측 fixture source manifest는 producer commit/path·hash와 export SHA를 기록한다.

## 재현

```powershell
.\gradlew.bat :auth:application:test :auth:adapter:security:test :app:test --tests 'com.refinvest.OwnerQueryDelegationInteropTest' --console=plain --no-daemon '-Dkotlin.compiler.execution.strategy=in-process' --max-workers=1
```

Go 작업 트리에서는 producer의 `app/build/delegation-interop/vectors.json` 절대 경로를
TRADING_REVIEW_KOTLIN_DELEGATION_VECTOR에 설정하고 `go test -count=1 -run '^TestKotlinIssuerInterop$' ./experiments/memberauthority`를 실행한다.
해당 환경변수는 test만 읽으며 production config가 아니다. DB/HTTP E2E를 실행하는 명령이 아니다.

## 남은 조건

선택한 vector 호환이 production Go parser/library·모든 Unicode/키 지원 범위를 증명하지 않는다.
실험은 자체 strict parser이며 production 포팅/독립 보안 검토·HTTP 입력 상한/오류·CPU admission은 별도다.
Core Member/caller/kid/jti 권위 state·primary 판정·service-only endpoint·차단 commit/키 공급·회수·
실제 callback 부하·두 GET/proxy/DB Guard·운영 활성화와 기존 TR writer/DB history/ID 인계는 미완료다.
첫 구현에 대한 테스트/CI를 위 항목의 수용 성공으로 확대하지 않는다. Core #147을 먼저 반영한다.
