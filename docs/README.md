# Context 문서 스냅샷

제품 도메인, ADR, 공통 아키텍처와 Trading Review 문서의 정본은 `context` 저장소다.
아래 파일은 Core 작업 시 동일한 문맥을 사용할 수 있도록 정본을 그대로 복제한 snapshot이다.

- `context/AI_AGENT.md` → `docs/AI_AGENT.md`
- `context/docs/*.md` → `docs/*.md`

`docs/README.md`와 `docs/GIT_WORKFLOW.md`는 Core가 소유하는 로컬 문서이며 snapshot이 아니다.
계약 snapshot인 `openapi/`의 동기화 여부는 integration 저장소의
`scripts/verify-contract-sync.ps1`로 검증한다.

정본을 변경해야 하면 먼저 Context에 반영한 뒤 이 저장소의 snapshot을 갱신한다. 갱신 후에는
다음 명령으로 누락과 내용 불일치를 확인한다.

```powershell
./scripts/verify-context-doc-sync.ps1
```
