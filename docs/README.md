# Context 문서 스냅샷

제품 도메인, ADR, 공통 아키텍처와 Trading Review 문서의 정본은 `context` 저장소다.
아래 파일은 Core 작업 시 동일한 문맥을 사용할 수 있도록 정본을 그대로 복제한 snapshot이다.

- `context/AI_AGENT.md` → `docs/AI_AGENT.md`
- `context/docs/*.md` → `docs/*.md`

`docs/README.md`와 `docs/GIT_WORKFLOW.md`는 Core가 소유하는 로컬 문서이며 snapshot이 아니다.
계약 snapshot인 `openapi/`의 동기화 여부는 integration 저장소의
`scripts/verify-contract-sync.ps1`로 검증한다.
`openapi/CONTEXT_REVISION`은 이 문서, OpenAPI, fixture snapshot의 기준 Context commit을 기록한다.
`context/fixtures/trading-normalization/`의 고정 hash vector와 검증용 schema/wire fixture는
`tradingreview/application/src/test/resources/trading-normalization/`에 바이트 그대로 복제한다.
context/fixtures/trading-analytics/의 Analytics identity/result vector와 schema는
	radingreview/application/src/test/resources/trading-analytics-protocol/v1/에 바이트 그대로 복제한다.
이 fixture들의 정본은 Context에 있으며 Core에서 기대 hash를 수정하지 않는다.

정본을 변경해야 하면 먼저 Context에 반영한 뒤 이 저장소의 snapshot을 갱신한다. 갱신 후에는
다음 명령으로 누락과 내용 불일치를 확인한다.

```powershell
./scripts/verify-context-doc-sync.ps1
```

## Trading Review 검토 초안

ADR-073/074와 관련 문서 snapshot은 미채택 초안이다. 아래 파일을 복제한 것은 runtime API/인증을
활성화하거나 실제 Member authority·Go 중계를 구현했다는 뜻이 아니다.

- [Owner query OpenAPI 초안](../openapi/drafts/trading-review-owner-query.yaml)
- [Member authority OpenAPI 초안](../openapi/drafts/trading-review-member-authority.yaml)
- [Synthetic authority fixture](../tradingreview/application/src/test/resources/trading-review-member-authority-draft/authority-cases.json)

OpenAPI 초안은 정식 `core-api.yaml`/`compute-api.yaml`과 분리한다. synthetic fixture는 JSON 전체
바이트를 hash 기준으로 삼는 golden vector가 아니며 checkout CRLF/LF만 정규화해 비교한다.
위임 문자열의 SHA-256·draft/runtime 비활성 상태는 integration의 필수 검사에서 Context verifier로
검증한다. 기존 Normalization/Analytics golden vector의 바이트 비교는 유지한다.

## Owner Query 위임 발급기 준비

[발급기 상세 설계](../engineering/OWNER_QUERY_DELEGATION_ISSUER.md)는 ADR-086의 첫 Core 구현 범위와 명시 조립·키 snapshot·언어 간 검증 기준을 설명한다. 실제 authority·proxy/HTTP·운영 활성화는 별도다.

[구현·검증 기록](../engineering/OWNER_QUERY_DELEGATION_VERIFICATION.md)은 실제 발급/키 snapshot·Kotlin→Go 선택 vector와 미구현 authority/운영 범위를 구분한다.

## Member Authority 판정 준비

[상태·Tx·HTTP 설계](../engineering/MEMBER_AUTHORITY_IMPLEMENTATION.md)와 [실제 검증/미구현 범위](../engineering/MEMBER_AUTHORITY_VERIFICATION.md)는 ADR-086의 primary snapshot·제어 Store·service-only endpoint 준비를 설명한다. 운영 회원 초기화·DDL 인계·secret/Go production 연결은 별도다.

## Owner Query Metadata 중계 준비

[Core 중계 설계](../engineering/OWNER_QUERY_PROXY_IMPLEMENTATION.md)와
[구현·검증 기록](../engineering/OWNER_QUERY_PROXY_VERIFICATION.md)은 ADR-086의 두 GET에 대한
비동기 HTTPS client·인증 principal 위임·안전한 응답과 선택적 Controller 연결을 설명한다.
기본 비활성화를 유지한다. [서비스 fixture](../engineering/OWNER_QUERY_SERVICE_FIXTURE.md)는 실제 Go/authority 연결의 전용 검증 조립이며 key 공급·DB/TR writer 인계·운영 활성화는 후속이다.

[production 조립 계획](../engineering/OWNER_QUERY_PRODUCTION_COMPOSITION.md)은 신뢰 snapshot·자원 소유·실제 Boot 등록/종료와 회원/DB·단일 writer 조건을 분리한다. 다음 구현은 loader/명시 preparation이며 production Bean 연결·일반 Main 재수용은 뒤 단계다.
