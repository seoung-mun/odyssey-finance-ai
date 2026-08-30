# MVP 공수·검증 전략 반영 계획

## Requirements Summary

- 개발자 2명 모두 Codex를 적극 활용한다는 전제로 공수를 산정한다.
- 2026-09-07 MVP는 핵심 수직 흐름과 최소 안전 gate를 우선한다.
- 전수 DB/API·부하·성능 검증은 기능 안정화 후 별도 단계로 실행한다.
- 미실행 테스트를 통과로 오인하지 않도록 `DEFERRED_MVP`로 기록한다.

## Acceptance Criteria

- P0 공수가 인일 범위와 달력 기간으로 제시된다.
- 두 명의 병렬 소유권과 공동 gate가 구분된다.
- 실제 PostgreSQL smoke, 개인정보 경계, 계산 회귀, HTTPS E2E는 MVP 필수로 남는다.
- 후속 성능 baseline에 환경·부하 조건·p50/p95/p99·자원·DB 지표가 포함된다.
- 심사 운영 기간에는 부하 테스트와 인스턴스 변경을 금지한다.

## Implementation Steps

1. `docs/MVP-공수-검증-계획.md`에 공수·역할·일정·검증 등급을 기록한다.
2. `docs/기획서.md`에 인력 및 검증 전략 요약을 연결한다.
3. `docs/현행-변경영향분석.md`의 Wave와 합격 기준을 최소 gate/후속 검증으로 구분한다.
4. `TODO.md`에 성능 baseline과 개선 ledger 작업을 추가한다.
5. 용어와 Markdown diff를 검증한다.

## Risks and Mitigations

- Codex 생산성을 중복 계산: 본 공수에 이미 포함하고 별도 배수를 적용하지 않는다.
- 테스트 축소가 무검증 배포로 변질: 실제 DB·privacy·계산·E2E 최소 gate는 유지한다.
- 미실행 항목이 완료로 오인: `DEFERRED_MVP`와 재개 조건을 의무화한다.
- 성능 수치 비교 조건 drift: commit, dataset, infra, concurrency를 baseline 키로 고정한다.

## Verification

- `git diff --check`
- 문서 간 P0/후속 테스트 경계와 날짜 일치 확인
- 공수 합계와 역할별 작업 중복 확인
