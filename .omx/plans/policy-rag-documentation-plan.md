# Policy RAG 문서화 실행 계획

## Requirements Summary

- 기존 코드와 첨부 v3.0을 통합한 전체 기획서를 만든다.
- KURE/pgvector/metadata/외부 LLM/sLLM 비핵심화 목표 설계도를 만든다.
- 실제 코드·API·DB 기준 수정 지도를 만든다.
- 확정할 수 없는 DB/API/인프라/제품 결정을 미확정 설계로 분리한다.
- 각 사용자 문서는 서로 다른 읽기 전용 적대적 QA 에이전트가 검증한다.

## Acceptance Criteria

- 현재 존재하지 않는 기능을 구현 완료처럼 표현하지 않는다.
- 금액·확률·정책 자격의 AI 금지 경계를 모든 문서가 동일하게 유지한다.
- 실제 경로만 변경 영향 문서에 기록한다.
- P0와 P1을 분리하고 2026-09-07 제출 위험을 명시한다.
- 미확정 결정에는 쟁점·선택지·권장안·영향·결정 시한이 있다.
- 문서별 별도 QA가 blocker/high 결함을 보고하고 수정 후 재검토한다.

## Execution Steps

1. `docs/기획서.md`, OpenAPI, SQL, TODO, 코드, 벤치 결과를 읽어 baseline을 만든다.
2. 제품·계약·런타임·대회 근거를 독립 read-only 조사로 교차 확인한다.
3. 통합 기획서, 정책 RAG 설계, 현행 영향 분석, 미확정 설계를 작성한다.
4. 각 문서를 별도 적대적 QA 에이전트에 배정한다.
5. blocker/high를 문서에 반영하고 경로·링크·용어를 정적 검증한다.

## Risks and Mitigations

- Housing v3.0이 기존 구현보다 지나치게 큼: 전체 방향과 제출 MVP를 분리한다.
- 런타임 KURE 없이 자유입력 의미 검색을 주장할 위험: 대표 질문 방식의 한계를 명시한다.
- `policy_snapshot` 오용: 정부 정책 catalog와 계산 snapshot을 분리한다.
- sLLM 제거 중 API polling 회귀: 제거가 아니라 승인 전 전환 옵션으로 문서화한다.
- 외부 LLM 개인정보 유출: 허용/금지 DTO와 평가 기준을 명시한다.

## Verification

- `rg`로 문서 경로, 용어, 상태 표기, 미확정 링크를 확인한다.
- `git diff --check`로 Markdown whitespace 오류를 확인한다.
- 문서별 읽기 전용 적대적 QA 결과를 수렴한다.
- 코드 변경이나 API/SQL 확정은 수행하지 않는다.
