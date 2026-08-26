# QA 결과

기준일: 2026-08-26
범위: 코드·OpenAPI·SQL 대조, 실제 PostgreSQL, 모듈 단위 검사. 이 문서는 배포 승인 문서가 아니다.

## 결론

모듈 단위 검사는 통과했지만 통합 통과는 아니다. 현재 배포를 막는 항목은 실제 Spring 연동
E2E 부재, 공개 API 7개 미구현, 환불 월 계산 계약 충돌이다.

## 실행 결과

| 범위 | 결과 | 한계 |
| --- | --- | --- |
| Analysis | Ruff 통과, unittest 97개 통과 | TestClient·mock만 사용; Spring serializer 자동 연동 없음 |
| Core | `./gradlew check --rerun-tasks` 통과 | PostgreSQL/JPA/SpringBoot 실통합 테스트 없음 |
| Web | tsc, lint, Vitest 42개, build, Playwright 5개 통과 | Playwright는 모든 핵심 API를 fixture로 가로챔 |
| PostgreSQL | SQL `01 → 02 → 05` 적용, KST 경계·중복 import·교차소유권 통과 | Spring/Redis/FastAPI와 함께 기동한 검사는 아님 |
| Spring↔FastAPI 수동 교차 | 정상 camelCase payload는 200·option 3·band 6, 같은 seed 결과 동일 | 자동화되지 않았고 timeout/5xx/DB 저장 흐름 미검사 |

## 확인된 결함

### [blocker] 실제 서비스 경계 E2E가 없다

- 증거: `web/e2e/core-flow.spec.ts`가 auth, dashboard, onboarding, plan endpoint를
  `page.route(...).fulfill(...)`로 대체하고 `playwright.config.ts`는 Vite만 기동한다.
- 재현: Core를 띄우지 않아도 `npm run test:e2e`가 5개 모두 통과한다.
- 영향: DTO, 상태 코드, 보안, DB, FastAPI 오류가 브라우저까지 전달되는지 0% 검증이다.
- 조치: real E2E 레인과 실제 service integration 레인을 P0으로 만든다.

### [high] 공개 OpenAPI 7개 operation이 Core controller에 없다

- 대상: 목표 수정; 예정지출 생성·수정; 재계획 목록·요청·결정·재시도.
- 증거: `API/openapi-public.yaml`의 해당 path와 `GoalController`, `ScheduledExpenseController`,
  `PlanningController` mapping을 대조했다. Goal은 GET/POST, 예정지출은 GET만 구현됐다.
- 영향: 목표 변경·예정지출 변경·rolling replan 흐름이 404다.

### [high] 환불 월이 FastAPI 계획 생성을 422로 만든다

- 증거: SQL은 REFUND를 차감해 음수 `bootstrap_eligible_spending`을 만들 수 있고,
  `PlanningQueryService`가 이를 그대로 보낸다. FastAPI history는 non-negative integer만
  받는다.
- 재현: Spring 형태 payload의 history를 `[-100, 200, 300]`으로 보내면
  `/internal/simulate`가 `422 INVALID_INPUT`을 반환한다.
- 영향: 환불이 결제보다 큰 과거 월이 하나면 계획 생성이 실패한다.
- 상태: M:N `refund_allocations`, 원 PAYMENT 월 소비 조정, 미연결 환불 현금 유입 분리 SQL과
  공개 계약을 추가했다. Spring import/history 연결과 실제 통합 검증이 남아 있다.

### [해소 확인 필요] 불완전한 Analysis 응답 저장 차단

- 재검토: 현재 `CalculationResponseValidator`는 simulation/snapshot, PRESET 0.70·0.80·0.90,
  horizon별 band와 숫자 범위를 저장 전에 검증한다. `AnalysisClientTest`의 빈 응답 허용은 HTTP
  역직렬화 경계 테스트이며 계획 저장 허용 근거가 아니다.
- 남은 조치: 실제 FastAPI HTTP와 DB를 연결해 validator 실패가 부분 저장 없이 끝나는지 확인한다.

### [high] 배포 topology의 실제 브라우저 검증이 없다

- 증거: Compose/Caddy는 Core만 제공하고 web은 Vercel rewrite/API_ORIGIN을 별도 사용한다.
- 영향: Vercel rewrite를 선택했지만 SPA 제공, HTTPS, refresh cookie, Google origin이 실제로
  함께 동작하는지 미검증이라 심사 URL에서 로그인 실패 가능.

### [medium] 테스트가 실제 저장소·오류 경계를 가린다

- Core는 Mockito와 가짜 HTTP server가 주류이며 PostgreSQL/JPA integration test가 없다.
- Web E2E는 401 refresh와 성공 fixture만 다루며 403/404/409/422/503, malformed JSON,
  timeout을 실제 서버와 검증하지 않는다.
- refresh cookie Path는 `/api/v1/auth`로 확정됐지만 실제 브라우저 cookie 전송 검증은 없다.

## 실제 PostgreSQL에서 확인한 항목

- `sql/01_schema.sql`, `02_integrity.sql`, `05_integrated_service.sql` 적용 성공
- KST half-open 월 경계 집계 정상
- 동일 사용자 `external_transaction_id` 중복 차단
- 다른 사용자의 예정지출을 거래에 연결하는 FK 차단

## 모듈 공통 계약

1. OpenAPI와 SQL이 기준이다. 변경은 메인이 문서·계약을 먼저 확정한다.
2. 돈은 정수 원 단위다. Spring/Web은 FastAPI 숫자를 다시 계산하지 않는다.
3. Spring→FastAPI는 camelCase, `X-Internal-Token`, `nPaths=10000`, unsigned seed와
   non-negative history 규약을 동시에 지킨다. 환불 정책 확정 전에는 history 변환을 임의로
   바꾸지 않는다.
4. 외부 호출 실패·불완전 계산 응답은 계획/DB의 부분 상태를 남기지 않고 정의된 오류로 끝나야 한다.
5. mock/fixture 테스트는 단위 검증일 뿐이다. SQL·HTTP·쿠키·queue는 실제 의존성을 붙인
   별도 레인에서만 합격 판정을 낸다.

## 미검사

실 Google OIDC, 실제 HTTPS/Vercel rewrite, 실제 Ollama 추론, Redis reclaim/재시작,
동시 재계획 lock, FastAPI timeout·5xx 뒤 DB rollback은 아직 검증하지 않았다.
