# TODO

기준일: 2026-08-28. 우선순위와 근거는 `docs/개발-로드맵.md`, 실행 증거는
`docs/QA-결과.md`, 합격 기준은 `docs/QA-가이드.md`를 따른다.

`scripts/real_scenario_qa.py`가 격리 compose project에서 실제 사용자 시나리오(목표 입력 →
선반영 → 재계획)와 적대적 케이스를 실제 HTTP/DB/Redis로 검증한다. 아래 백엔드 P0는 이
스크립트로 REAL 재검증했다. Web/브라우저 항목은 이번 웨이브에서 다루지 않았다(프론트 재작업
예정).

## P0 — 실제 MVP blocker

- [x] Core→Analysis HTTP/1.1 고정을 실제 Uvicorn에 대한 계획 생성 요청(200, option 3개,
  band 57개, 한 transaction 저장)으로 재검증했다.
- [x] 거래 목록 무필터·카테고리 필터·기간+카테고리 복합필터·커서 페이지네이션을 실제
  PostgreSQL에서 검증했다. `GET /transactions?limit=50` 500 재발 없음.
- [x] E2E 인증의 Secure·HttpOnly·SameSite=Strict·Path refresh cookie를 실제 HTTPS
  **browser**(Playwright)에서 저장하고 새로고침·재로그인까지 검증했다.
- [x] 백엔드 자동 기동 레인을 만들었다(`scripts/real_scenario_qa.py`): 랜덤 project명·랜덤
  loopback 포트·일회용 secret으로 PostgreSQL·Redis·Uvicorn·Spring·Caddy를 기동해 health
  대기 후 실행하고 종료 시 소유 컨테이너·볼륨·이미지만 정리한다. Web(Vite) 기동 레인은 프론트
  재작업 뒤 별도로 만든다.
- [ ] 공개 API 31개 전수를 실제 Spring HTTP로 호출해 mapping·status·DTO·소유권을 검증한다.
  이번 웨이브에서 인증·온보딩·거래(적재/조회/집계/환불)·목표·계획(생성/커스텀/선택/설명)·
  예정지출·재계획(수동/자동/결정)·대시보드 경로를 실제로 태웠지만 31개 전체 대조표는 아직
  없다.

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
  재계획 결정을 실제 API에서 404로 거부함을 검증했다. (브라우저 레인은 미검증)
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

- [ ] 실제 Ollama와 staging 하드웨어에서 품질·지연·RSS·15초 fallback을 측정한다.
- [ ] rolling-origin 백테스트와 coverage를 고정 fixture·seed·명령으로 남긴다.
- [ ] 기능·QA가 녹색이 된 뒤 별도 staging에서만 부하 테스트와 성능 최적화를 시작한다.
