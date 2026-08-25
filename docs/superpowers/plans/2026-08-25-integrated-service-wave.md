# 통합 서비스 첫 구현 웨이브

> 승인 기준: `docs/통합-서비스-설계.md`. 테스트를 먼저 실패시킨 뒤 최소 구현으로 통과시킨다.

## 1. 메인: 계약과 DB

수정: `API/openapi-public.yaml`, `API/openapi-internal.yaml`, `API/README.md`, `sql/01_schema.sql`,
`sql/02_integrity.sql`, `sql/03_verify.sql`, `TODO.md`.

- 소비 하한 typed input/output, 프로필 필드, retry/sample-data/auth cookie/error 계약을 먼저 반영한다.
- refresh session, sample loaded timestamp, 소비 하한 CHECK, 설명 상태, replan 소유권·중복 제약을 SQL에 반영한다.
- `03_verify.sql`에는 새 불변조건 검증만 추가하고 기존 실패 감지 방식은 바꾸지 않는다.
- OpenAPI validator와 PostgreSQL Compose 적용으로 계약을 검증한다.

## 2. Analysis 빌더

수정: `analysis-api/**`만.

- `tests/test_models.py`, `tests/test_planning.py`, `tests/test_internal_api.py`에 소비 하한 실패 테스트를 먼저 추가한다.
- `SpendingFloor` 모델과 AUTO p20, effective floor, PRESET/CUSTOM 일관 계산을 최소 변경으로 구현한다.
- 엔진 버전을 올리고 셀프체크·전체 unittest·Ruff를 통과한다.

## 3. Core 빌더

수정: `core-api/**`만.

- Spring Security/Resource Server/JOSE, Redis client 의존성과 `ddl-auto=validate`를 반영한다.
- RFC 7807/request ID와 Google 교환·refresh rotation·logout의 테스트를 먼저 작성한다.
- 확정 SQL entity/repository, 프로필·목표·계획 수직 흐름과 FastAPI 외부 트랜잭션 경계를 구현한다.
- Redis는 설명 enqueue/fallback 경계만 구현하고 캐시를 만들지 않는다.

## 4. Web 빌더

수정: `web/**`만.

- Router/Vitest/RTL/Playwright를 추가하고 auth/error routing 테스트를 먼저 작성한다.
- Google 로그인, 신규 사용자 선택형 샘플/온보딩, 대시보드·계획 경로·fan chart의 loading/empty/error/success를 구현한다.
- 401/403/404/409/500/network/Error Boundary와 중복·늦은 요청 방어를 구현한다.
- API에 없는 상태 관리·UI/차트 라이브러리는 추가하지 않는다.

## 5. 통합과 QA

- 메인이 서비스별 전체 검사와 diff를 수행한다.
- 빌더 완료 뒤 계약·데이터, 적대적 서비스, E2E·변경범위 QA를 읽기 전용으로 병렬 실행한다.
- blocker/high만 같은 빌더에게 최대 두 번째 수정 루프로 돌리고 전체 회귀 후 메인이 커밋한다.
- push·PR·배포는 하지 않는다.
