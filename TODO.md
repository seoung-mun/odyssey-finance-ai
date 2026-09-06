# TODO

기준일: 2026-08-28. 우선순위와 근거는 `docs/개발-로드맵.md`, 실행 증거는
`docs/QA-결과.md`, 합격 기준은 `docs/QA-가이드.md`를 따른다.

`scripts/real_scenario_qa.py`가 격리 compose project에서 실제 사용자 시나리오(목표 입력 →
선반영 → 재계획)와 적대적 케이스를 실제 HTTP/DB/Redis로 검증한다. 아래 백엔드 P0는 이
스크립트로 REAL 재검증했다. 데모 Web 흐름도 같은 실행의 route interception 없는 실제
Playwright로 검증한다.

## P0 — 실제 MVP blocker

- [x] Core→Analysis HTTP/1.1 고정을 실제 Uvicorn에 대한 계획 생성 요청(200, option 3개,
  band 57개, 한 transaction 저장)으로 재검증했다.
- [x] 거래 목록 무필터·카테고리 필터·기간+카테고리 복합필터·커서 페이지네이션을 실제
  PostgreSQL에서 검증했다. `GET /transactions?limit=50` 500 재발 없음.
- [x] E2E 인증의 Secure·HttpOnly·SameSite=Strict·Path refresh cookie를 실제 HTTPS
  **browser**(Playwright)에서 저장하고 새로고침·재로그인까지 검증했다.
- [x] 백엔드 자동 기동 레인을 만들었다(`scripts/real_scenario_qa.py`): 랜덤 project명·랜덤
  loopback 포트·일회용 secret으로 PostgreSQL·Redis·Uvicorn·Spring·Caddy를 기동해 health
  대기 후 실행하고 종료 시 소유 컨테이너·볼륨·이미지만 정리한다. Caddy가 production Web
  bundle을 함께 제공하고 같은 실행에서 REAL Playwright를 수행한다.
- [x] 설명 생성을 Core의 local Spring AI/Ollama 경로로 이전했다. 기본·e2e·kill-switch에서는
  즉시 `FALLBACK`하고, `llm` profile에서는 실제 `qwen3:0.6b-q4_K_M` `/api/chat` 호출을
  확인했다. Ollama 중단 중에도 챗·계획·대시보드는 정상이고 설명은 단일 deadline 안에
  `FALLBACK`으로 수렴했다. 운영 배포에는 Ollama를 포함하지 않는다.
- [x] OpenAPI에서 자동 산출한 공개 operation 전수를 실제 Spring HTTPS 실행 목록과 대조했다.
  41개 중 실제 Google ID token이 필요한 `exchangeGoogleToken`만 성공 응답 미검증이며, 잘못된
  token의 401 폐쇄 경계는 REAL로 확인했다. 나머지 40개는 mapping·status·DTO·소유권을 실제
  PostgreSQL·Redis·Analysis 경계까지 호출해 성공 응답을 확인했다.
- [x] 2026-09-06: `docker compose up`한 스택에서 적금·정책이 모두 0건이던 원인을 실측으로
  확정하고 고쳤다. 적금은 `app.finlife-timeout` 기본값(5s)이 실제 FSS 응답(16.5초 실측)보다
  짧아 항상 timeout이었다 — 45s로 올리고, 그만큼 기동을 막지 않도록 startup refresh를 별도
  virtual thread로 분리했으며, 전송 계층 예외에 한해 재시도(최대 3회)를 추가했다
  (`SavingsCatalogRefreshService`, 신규 `SavingsCatalogRefreshServiceTest` 6건).
  정책은 `PolicyArtifactImportCommand`가 `app.policy-artifact-path`에 배선된 적이 없어
  runner 자체가 등록되지 않았던 것 — `docker-compose.yml`/`docker-compose.prod.yml`에
  승인된 `data/policy/policy-artifact-calculable-approved-23.json`을 읽는 one-shot
  `policy-import` 서비스를 추가했다(core-api healthy 후 실행, 멱등). `scripts/real_scenario_qa.py`도
  이 서비스가 QA의 `up --wait` 게이트를 깨지 않도록 `run --rm`으로 분리하고, 활성화 검증
  (`verify_policy_runtime_activation`: ACTIVE snapshot 1 / policy 23 / rule 11)을 추가했다.
  자세한 조사 근거는 `docs/정책-작업-진행.md`의 Stage 1B-B2 Runtime activation 절 참고.

## P0 — 금융·트랜잭션·재계획

- [x] 계획 생성 성공 경로에서 option 3개·모든 band(57개)·simulation이 한 transaction으로
  저장되고, Analysis 연결 거부 시 계획 관련 행이 0건(부분 저장 없음)임을 실제 DB로 검증했다.
- [x] drift 상세 계산 자체는 `ReplanTriggerPolicy`가 이미 `BigInteger`로 overflow를 막고
  있었지만, 그 입력을 만드는 `monthly_spending_summary` SQL VIEW의 `SUM(...)::bigint`가 여전히
  현실적이지 않은 금액(예: `Long.MAX_VALUE`)을 만나면 `SQLSTATE 22003`으로 깨져 해당 사용자의
  이후 모든 계획 계산을 막는다는 걸 실제 요청으로 재현했다. `TransactionDtos.MAX_AMOUNT`
  (10^15원) 입력 상한을 추가해 재발을 막았고 상한 자체는 정상 처리됨을 재검증했다
  (`API/openapi-public.yaml`도 함께 갱신).
- [x] 수동 infeasible 재계획이 OpenAPI대로 422 `PLAN_INFEASIBLE`을 반환하고, 422여도 append-only
  계획 행만 남을 뿐(부분 행 아님) 기존 ACTIVE 계획은 불변임을 실제 DB로 검증했다.
- [x] 예정지출 동시 수정 2건을 동시에 보내 낙관적 잠금(`readPlanInputForUpdate` 재검증)이
  500 없이 정리되고(200/200 또는 200/409) 최종 저장 행이 둘 중 하나로 일관됨을 검증했다.
  그 과정에서 `PlanningCommandService.save()`가 기존 PROPOSED 계획을 STALE로 바꾸는 UPDATE와
  새 계획 INSERT 사이에 명시적 flush가 없어, Hibernate가 update보다 insert를 먼저 내보내면
  `uq_plan_version_proposed_per_goal`을 순간적으로 위반할 수 있는 latent bug를 발견해 고쳤다
  (`select()`의 supersede 처리와 동일한 패턴으로 `plans.flush()` 추가).
- [x] 실제 Redis 중단·재기동에서 계획 생성 자체는 계속 성공하고, `ExplanationQueuePublisher`가
  발행 실패를 감지해 같은 요청 안에서 설명 상태를 즉시 `FALLBACK`으로 마감함을(무기한 PENDING
  방치 아님) 확인했다. 재기동 뒤 새 설명은 정상 수렴한다. consumer가 메시지를 이미 pending으로
  들고 있는 도중 재기동되는 pending reclaim·중복 delivery 시나리오는 black-box compose 조작으로
  안전하게 재현할 방법을 찾지 못해 **미검증**으로 남긴다.
- [x] linked·pending·unmatched 환불, 늦은 PAYMENT resolve, 월 집계, 중복 import를 실제
  PostgreSQL과 공개 API에서 검증했다.

## P0 — Web 실제 사용자 흐름

- [ ] route interception 없는 브라우저가 인증 → 온보딩 → 거래 → 목표 → 계획 → 옵션 선택 →
  대시보드 → 예정지출 → 재계획 → 새로고침을 끝까지 수행한다. (프론트 재작업 예정, 이번
  웨이브에서 데모 테스터 선택 → seed → 계획 → 옵션 선택 → 대시보드 → 새로고침까지는 실제
  API로 통과했으며, 수동 거래·예정지출·재계획 UI 경로는 남아 있다.)
- [x] 사용자 A/B를 테스트가 직접 생성하고 B의 A 목표·계획·재계획 이벤트 목록·예정지출 조회 및
  재계획 결정을 실제 Playwright browser context와 API에서 404로 거부함을 검증했다.
- [x] 존재하지 않는 달력 날짜(`2099-02-30`)를 백엔드가 클라이언트 우회 여부와 무관하게 400으로
  거부함을 실제 요청으로 확인했다. Web의 `<input type=date>` 우회 경로 자체는 미검증(범위 밖).
- [ ] 재계획 403·404·422를 구분하고 422에서도 현재 활성 계획과 수정 안내를 보존한다. 이번
  웨이브는 422(PLAN_INFEASIBLE)와 404(IDOR)만 검증했고 403 경로와 Web 쪽 상태 보존은
  미검증이다.
- [ ] 실제 auth 레인의 Playwright trace에서 token·cookie를 남기지 않도록 trace 정책을
  분리한다. (Playwright 자체가 이번 웨이브 범위 밖)

## P1 — 운영 보안·배포

- [ ] 실제 API domain을 Caddy site address로 설정하고 HTTPS 인증서·80→443 redirect·cookie·
  Vercel rewrite를 실제 브라우저에서 검증한다.
- [ ] AWS에서 80/443 외 SG 차단, EBS와 snapshot/backup 암호화, 최소권한 DB role을 검증한다.
- [ ] JWT·DB·Redis·Analysis token을 운영 secret store로 주입하고 회전·폐기 절차를 만든다.
- [ ] 개발 secret 파일 권한을 0600으로 제한하고 QA 포트가 loopback인지 자동 사전 검사한다.
- [ ] 내부 서비스가 multi-host로 분리되면 Core↔Analysis/Ollama/DB/Redis TLS 또는 mTLS를 적용한다.
- [ ] 실제 Google 테스트 계정 로그인은 수동 1회만 하고 반복·다중 사용자는 자동 E2E 인증으로
  검증한다.

## P1 — 평가·성능

- [ ] 로컬 Ollama의 설명 품질·지연·RSS를 별도 측정한다. 운영은 Ollama를 사용하지 않으며,
  연결·장애 격리와 deadline fallback은 REAL E2E로 완료했다.
- [ ] rolling-origin 백테스트와 coverage를 고정 fixture·seed·명령으로 남긴다.
- [ ] 기능·QA가 녹색이 된 뒤 별도 staging에서만 부하 테스트와 성능 최적화를 시작한다.
- [ ] `DEFERRED_MVP` 테스트를 담당자·생략 이유·재개 조건과 함께 목록화하고 위험도 순으로
  API·DB·브라우저 회귀를 실행한다.
- [ ] 첫 전체 부하 결과를 `baseline-001`로 고정한다. commit/image/dataset/snapshot/인스턴스,
  동시 사용자·ramp-up·측정 시간, p50/p95/p99·throughput·오류율·CPU/RSS·DB query/pool을 남긴다.
- [ ] 이후 성능 변경마다 가설, 변경 commit, 동일 조건의 전후 수치, 회귀, 채택·원복 결정을
  ledger로 누적한다.

## P2 — Web 보완

- [x] 선택된 계획의 `percentileBands`가 비어 있으면 예상 범위·목적지·월별 상세를 렌더링하지
  않고 데이터 없음 상태를 표시한다.

## P1 — 테스트 실효성 후속

- [ ] `PolicyInformationalExposureHttpPostgresTest`의 `INFORMATIONAL` 기대값과 승인 artifact의
  `ELIGIBILITY_ONLY` 계약을 확정하고 실제 PostgreSQL 집합 회귀를 녹색으로 만든다.
- [ ] `LayerArchitectureTest`의 문자열 lint 실패를 동작 테스트와 분리하거나 제품 코드 위반을
  수정한 뒤 `core-api ./gradlew check` 전체를 통과시킨다.
- [ ] `scripts/test_real_compose_qa.sh`를 문자열 grep이 아닌 조작 compose JSON의 실제 포트
  검증으로 교체하고, policy artifact evaluate/validate의 종료코드와 `python -O` 실행을 보강한다.
- [x] 2026-09-06: `scripts/real_scenario_qa.py`의 공개 operation coverage 게이트(`match_operation` +
  `raise ScenarioFailure(f"OpenAPI에 없는 공개 호출: ...")`, 커밋 `5a4ae54`)가 `OPTIONS`
  preflight 호출(`run_scenario`의 CORS 테스트, 커밋 `45670cb`)을 항상 실패로 잡던 것을 고쳤다.
  `contract_operations`가 `get|post|put|patch|delete`만 파싱해 `OPTIONS`는 애초에
  `PUBLIC_OPERATIONS`에 없기 때문이며, `--backend-only` REAL 실행에서 실제로 재현했다
  (`OPTIONS /api/v1/auth/refresh`에서 즉시 FAILED). CORS preflight는 OpenAPI가 문서화하는
  업무 operation이 아니므로 coverage 게이트 대상에서 제외(`observedVia: CORS_PREFLIGHT`)하고
  기존 `APPROVED_BYPASS` 집계와는 분리했다. 이후 `--backend-only` 172단계 REAL 전체가
  통과했다(공개 operation 40/41, 나머지 1개는 실제 Google 계정 필요로 기존 미검증 사유 유지).
- [ ] Web 담당자는 Onboarding의 하드코딩 `90%`를
  `historicalFeasibilityRatio` 바인딩으로 교체하고, 노후 Vitest 선택자와 `formatMoney` 반올림
  변이를 갱신한다.
