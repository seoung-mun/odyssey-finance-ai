# TODO

기준일: 2026-08-26. 우선순위와 근거는 `docs/개발-로드맵.md`, 실행 증거는
`docs/QA-결과.md`를 따른다.

## P0 — 구현

- [ ] OpenAPI에만 있는 목표 수정, 예정지출 생성·수정, 재계획 조회·요청·결정·재시도 7개를
  Core에 구현한다.
- [ ] 기존 Analysis 응답 validator를 실제 FastAPI HTTP·DB 저장 경계에서 검증한다.
- [ ] 확정한 M:N 환불 SQL/OpenAPI를 Spring import·history·조회에 연결한다.
- [ ] 실제 PostgreSQL·Redis·FastAPI·Spring 통합 테스트를 만든다.
- [ ] 실제 Spring을 호출하는 browser E2E 레인을 만든다. 기존 fixture Playwright는 UI 단위
  레인으로 유지하되 통합 통과 근거로 쓰지 않는다.

## P1 — 검증·운영

- [ ] OpenAPI operation ↔ Spring mapping/DTO ↔ web parser의 자동 계약 검사를 만든다.
- [ ] 실제 Redis 단절·재시작·pending reclaim에서 설명 상태와 계획 저장이 분리되는지 검증한다.
- [ ] KST 월 경계를 UTC host에서도 검증하고 PostgreSQL/JDBC timezone을 명시한다.
- [ ] 실제 Ollama와 staging 하드웨어에서 품질·지연·RSS를 측정한다.
- [ ] rolling-origin 백테스트와 staging 부하 결과를 재현 가능한 fixture/명령으로 남긴다.
