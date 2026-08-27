# QA 결과

## 2026-08-27 백엔드 재검증

기준: `docs/QA-가이드.md`, `docs/evals/real-mvp-integration.md`
범위: Core·Analysis 백엔드만. Web/브라우저는 프론트 재작업 예정으로 이번 웨이브 범위 밖.
방법: `scripts/real_scenario_qa.py` — 격리 compose project(랜덤 project명·loopback 포트·
일회용 secret)에서 실제 PostgreSQL 16.4·Redis 7.4·Uvicorn·Spring·Caddy를 기동해 사용자
시나리오(목표 입력 → 선반영 → 재계획)와 적대적 케이스를 실제 HTTPS API로 수행했다.

### 결론

2026-08-26 판정의 두 blocker(Core→Analysis HTTP 전송, 무필터 거래 목록 500)는 코드 레벨에서
이미 고쳐져 있었으나 실제 재검증이 없었다. 이번에 실제 스택으로 재검증해 **REAL로 확정**했다.
그 과정에서 이전에 알려지지 않았던 P0급 결함 2건을 실제 요청으로 새로 발견해 고쳤다:

- **[blocker→해결] 현실적이지 않은 거래 금액이 월별 집계를 깨뜨림.** `amount=Long.MAX_VALUE`인
  거래를 적재하면 그 자체는 성공하지만, `monthly_spending_summary` VIEW의
  `SUM(...)::bigint`가 그 사용자의 다른 거래와 합산할 때 BIGINT 범위를 넘겨 `SQLSTATE 22003`으로
  깨진다. 이후 그 사용자의 **모든** 계획 생성 요청이 500 대신 알 수 없는 `DataIntegrityViolationException`
  기반 409로 영구히 막힌다. `TransactionInput`/`RefundAllocationInput`에 현실적 상한(10^15원,
  `TransactionDtos.MAX_AMOUNT`)을 추가해 재발을 막았다(`API/openapi-public.yaml` 동기화,
  사용자 승인 하에 계약 변경).
- **[high→해결] 재계획 저장의 Hibernate flush 순서 latent bug.** `PlanningCommandService.save()`가
  기존 PROPOSED 계획을 STALE로 바꾸는 UPDATE와 새 계획 INSERT 사이에 flush가 없어, Hibernate가
  update보다 insert를 먼저 내보내면 `uq_plan_version_proposed_per_goal`(goal당 PROPOSED 1개)을
  순간적으로 위반할 수 있었다(01_schema.sql이 이미 이 순서 위험을 주석으로 경고하고 있었다).
  `select()`의 supersede 처리와 동일하게 `plans.flush()`를 추가했다.

### 이번에 REAL로 확정한 것

- 실제 Uvicorn에 대한 계획 생성: option 3개·band 57개·simulation이 한 transaction으로 저장.
- 거래 무필터·카테고리 필터·기간+카테고리 복합필터·커서 페이지네이션 — 전부 200.
- 환불 linked·pending·unmatched, 늦은 PAYMENT resolve, 중복 import 멱등성(회귀 유지).
- 선반영: 예정지출 생성·수정이 즉시 재계획을 선계산하고(`triggeredReplanEventId`), 결정
  (`ACCEPT_NEW_PLAN`/`KEEP_CURRENT_PLAN`)이 계획 상태와 소비 기반 재계획 억제를 정확히 반영.
- 자동 재계획: 큰 거래 shock가 `replan_events`에 정확히 1건만 생성(중복 없음).
- 수동 infeasible 재계획 422 `PLAN_INFEASIBLE`, 부분 행 없이 append-only.
- Analysis 연결 거부 → 503, 계획 관련 행 0건(부분 저장 없음).
- Redis 다운 → 계획 생성은 성공하고 설명은 즉시 FALLBACK으로 마감(무기한 PENDING 아님, 설계
  의도대로 동작). 재기동 뒤 새 설명은 정상 수렴.
- 예정지출 동시 수정 2건 → 500 없이 낙관적 잠금으로 정리(200/200 또는 200/409), 최종 행 일관.
- 사용자 B가 A의 목표·계획·재계획 이벤트·예정지출을 조회·수정·결정 시도 → 전부 404.
- 컨테이너 로그에 secret·token 미노출.

### 이번 웨이브에서 다루지 않음(범위 밖 또는 미검증)

- Web/브라우저 전체 흐름, Playwright, Vercel→Caddy 실제 도메인 HTTPS — 프론트 재작업 예정.
- Redis consumer가 메시지를 pending으로 들고 있는 도중 재기동되는 pending reclaim·중복 delivery
  — black-box compose 조작으로 안전하게 재현할 방법을 못 찾아 미검증으로 남긴다.
- 공개 API 31개 전체 대조표(이번엔 핵심 경로들을 실제로 태웠지만 전수 대조는 아님).
- 실제 Ollama 설명 품질·지연, AWS/secret store, 부하 테스트.

### 회귀

- Core `./gradlew check` — 통과(격리 폴더의 사전 diff에 있던 spotless 위반은 `spotlessApply`로
  정리).
- Analysis Ruff — 위반 0. Analysis 전체 unittest 103개 — 전부 통과.
- `sql/03_verify.sql` 77개, `sql/06_verify_refund_allocations.sql` — 새 일회용 PostgreSQL
  16.4 컨테이너(01→02→05→V4 적용, 검증 뒤 폐기)에서 전부 통과. `03_verify.sql`은 빈 DB를
  전제하므로 24시간째 떠 있는 `dacon` 개발 스택에는 실행하지 않았다.

---

# 2026-08-26 판정 (구 기록)

## 결론

현재 상태는 **모듈 단위 녹색, 실제 MVP 수직 흐름 실패**다. 공개 API 31개 mapping은 코드에
존재하지만 실제 브라우저 흐름과 계획 생성이 blocker에서 중단되므로 구현 완료로 인정하지
않는다. 이번 판정에는 메인이 배정하지 않은 목업을 합격 근거로 사용하지 않았다.

실제 검증에서 환불 배분·중복 거래·사용자 소유권·입력 경계와 부분 저장 방지는 버텼다.
반면 브라우저 인증 연결, Core→Analysis HTTP 전송, 기본 거래 목록 쿼리가 실제 환경에서
실패했다. Redis 내구성, 재계획, 실제 Ollama는 선행 blocker 때문에 미검증이다.

## 증거 분류

| 범위 | 직접 실행 결과 | 분류 | 판정 한계 |
|---|---:|---|---|
| Analysis Ruff·unittest | 103개 통과 | 혼합 | 엔진·fixture 직접 호출은 `REAL` 단독 컴포넌트, TestClient·patch·가짜 HTTP는 `MOCK` |
| Core Gradle | 90개 통과 | `MOCK`/단위 | Mockito·직접 객체·고정 `HttpServer`; 실제 Spring/JPA/Redis/Analysis 합격 근거 아님 |
| Web Vitest | 47개 통과 | `MOCK`/단위 | jsdom·주입 fetcher; 실제 브라우저↔Core 근거 아님 |
| 기존 Playwright | 5개 통과 | `MOCK` | `page.route().fulfill()`로 핵심 API 전부 대체 |
| 실제 Playwright | 1개 실패, 소유권 1개 skip | `REAL`/미검증 | 인증 직후 최초 화면 진입 실패, 이후 시나리오 전체 미검증 |
| PostgreSQL SQL | `03_verify.sql`, `06_verify_refund_allocations.sql` 통과 | `REAL` | DB 함수·제약 레인; Spring 사용자 흐름과는 별도 |
| 실제 서비스 수직 실행 | PG·Redis·Uvicorn·Spring·Vite·Chromium 기동 | `REAL` | 아래 최초 실패 지점까지 검증 |

기존 `AnalysisClientTest`의 JDK 고정 응답 서버, FastAPI `TestClient`, patched Ollama,
Repository mock, jsdom fetcher, route fulfillment는 모두 보조 단위 증거다. 대응 `REAL`이 실패하거나
없으므로 해당 서비스 경계를 통과로 판정하지 않는다.

## 실제 수직 시나리오 결과

### 통과

- 실제 PostgreSQL 16.4와 Redis 7.4를 loopback에 기동하고 migration·health를 확인했다.
- 사용자 샘플 적재 전후 `onboardingComplete=false → true`, 같은 요청 재시도 `loaded=false`.
- 사용자 B가 사용자 A의 목표를 조회하면 `404 RESOURCE_NOT_FOUND`.
- 같은 거래 import 재시도는 `inserted=4 → 0`, `skipped=0 → 4`로 중복 원장을 만들지 않았다.
- linked·pending·unmatched 환불 금액과 상태가 보존됐다.
- 늦게 도착한 PAYMENT가 pending 환불 allocation을 실제 DB에서 resolved로 바꿨다.
- PAYMENT 소비 표본과 환불 allocation 반영 월 집계를 실제 DB/API에서 대조했다.
- 음수 금액, 잘못된 날짜, int64 범위 초과 입력은 모두 400으로 거부됐다.
- 실제 Uvicorn에 올바른 계산 JSON을 직접 보내면 200, option 3개, band 57개를 반환했다.
- 계획 생성 실패 뒤 `plan_versions`, `simulation_runs`, `plan_options`가 모두 0이라 부분 저장은
  남지 않았다.

### [blocker] 실제 브라우저 인증 뒤 온보딩에 진입하지 못한다

- evidence: route interception 없는 Chromium이 `/onboarding`에서 90초 동안
  `샘플로 둘러보기` 버튼을 찾지 못했다.
- cause: 실제 E2E 스펙은 인증 API에서 access token을 받지만 앱 메모리 상태에 전달하지 않는다.
  refresh cookie는 `Secure`인데 E2E origin은 평문 HTTP라 앱의 refresh 부팅도 성립하지 않았다.
- impact: 온보딩, 계획 선택, 대시보드, 재계획, 새로고침·재로그인 전체가 미검증이다.
- owner: Web/Core 인증 통합 빌더.
- security: 실패 trace는 token·cookie 노출 가능성 때문에 열지 않고 삭제했다.

### [blocker] Core→Analysis 실제 계획 생성이 422로 실패한다

- evidence: `POST /api/v1/goals/{goalId}/plan-versions`는 공개 400, 실제 Uvicorn은
  `/internal/simulate` 422와 body 전체 누락을 보고했다. 동일 JSON을 직접 HTTP/1.1로 보내면
  200이다.
- cause: 공유 JDK `HttpClient` 기본 버전이 cleartext HTTP/2 upgrade를 시도하고 Uvicorn이 이를
  거부하면서 요청 body가 전달되지 않는다. 강제 HTTP/1.1에서는 body와 필드별 검증이 정상이다.
- impact: 최초 계획, 옵션 선택, 대시보드 계획, 모든 재계획이 막힌다.
- owner: Core HTTP/통합 빌더.
- integrity: 관련 계획 행은 0으로 rollback되어 부분 저장은 없다.

### [high] 필터 없는 거래 목록이 실제 PostgreSQL에서 500이다

- evidence: `GET /api/v1/transactions?limit=50` → `500 INTERNAL_ERROR`, PostgreSQL은
  `could not determine data type of parameter $2`를 반환했다.
- cause: nullable 필터를 한 JPQL/native query에서 처리하면서 null 파라미터 타입을 확정하지
  못한다.
- impact: 기본 거래내역 화면을 열 수 없다.
- owner: Core transaction 빌더.

## 아직 합격하지 않은 정적·단위 QA 항목

- drift 상세 JSON의 `recommendedMonthlySpending × day`가 int64 overflow를 낼 수 있다.
- 수동 infeasible 재계획이 OpenAPI의 422 대신 200을 반환한다.
- 예정지출 동시 수정에서 계산 snapshot과 최종 행이 달라질 수 있다.
- Web 날짜 검사는 input type을 우회하면 존재하지 않는 달력 날짜를 API로 보낼 수 있다.
- Web은 재계획 403·404·422를 일반 전체화면 오류로 축약한다.
- 멀티유저 실제 Playwright는 foreign plan ID가 없으면 skip한다.

위 항목은 단위·정적 재현이므로 최신 `REAL` 시나리오 통과 항목과 섞지 않는다. 수정 후 실제
HTTP·DB·브라우저에서 다시 검증한다.

## 보안·암호화 QA

### 현재 보호되는 것

- 실제 secret 파일은 Git ignore 대상이고 tracked `.env.example`은 placeholder만 포함한다.
- JWT secret은 32 byte 미만을 거부하고 Analysis 내부 token은 공백을 거부한다.
- access JWT는 Web 메모리에만 두며 local/session storage에 저장하지 않는다.
- refresh JWT 원문은 DB에 저장하지 않고 SHA-256 digest만 저장한다.
- refresh cookie는 `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/api/v1/auth`이며 logout도 같은
  path로 삭제한다.
- Redis stream에는 planVersionId·inputHash·promptVersion만 있고 금융 원문·token은 없다.
- 기본 compose에서 PostgreSQL·Redis·Core·Analysis·Ollama는 host port를 publish하지 않는다.

### 남은 위험

- [high] Caddy 기본 주소와 예제값이 `:80`이라 기본 compose는 HTTP다. 실제 운영 domain을
  Caddy site address로 넣고 인증서와 HTTP→HTTPS redirect를 실측하기 전에는 OAuth·JWT·금융
  데이터 전송을 합격시킬 수 없다.
- [medium] 실제 Playwright가 `trace: retain-on-failure`이고 E2E/Bearer token을 header에 보내므로
  CI artifact에 token이 남을 수 있다.
- [medium/운영] 이메일, 소득·고정비, 목표, 거래처·금액, 계획 snapshot·simulation 결과는 DB에
  애플리케이션 암호화 없이 저장된다. EBS·snapshot·backup 암호화와 최소권한 DB role 증거도
  아직 없다.
- [판정 불가/운영] 비밀값은 compose 환경변수로 주입되어 Docker daemon 권한자에게 보인다.
  운영 secret store 연결과 회전 절차가 없다.
- [low/로컬] ignored `.env`와 `web/.env.local` 권한이 0644다.

Core→Analysis→Ollama의 평문 HTTP는 현재 단일 compose private network 안에서 host port가
없으므로 로컬 예외로 분류한다. 운영에서도 동일하게 격리된 단일 host private network인지
증명하지 못하거나 multi-host로 분리하면 TLS 또는 mTLS가 필요하다.

QA 중 최초 PG·Redis·Spring이 전체 인터페이스에 bind된 사실을 발견했다. 즉시 중지하고
`127.0.0.1` 전용으로 재기동한 뒤 금융 요청을 수행했으며, 종료 시 소유 프로세스와 컨테이너를
전부 정리했다.

## 미검사

- 옵션 선택·대시보드·예정지출·재계획 목록/결정/재시도 전체 흐름
- Redis queue consumer, 중단·재기동, pending reclaim, 중복 delivery
- 동시 계획·목표·예정지출·재계획 요청과 응답 유실 재시도
- 실제 Ollama 설명, 숫자 대조, timeout·RSS
- 실제 Google 테스트 계정 로그인 1회
- 실제 Vercel→Caddy HTTPS, 인증서 chain, redirect, HSTS/CSP
- AWS SG, Secrets Manager, EBS·snapshot·backup 암호화와 DB 최소권한
