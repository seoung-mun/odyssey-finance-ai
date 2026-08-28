# 실제 MVP 통합 평가

기준일: 2026-08-26

## Baseline

- Core 단위 90개, Web 단위 47개, 기존 Playwright 5개는 실제 서비스 경계의 합격 근거가 아니다.
- 실제 브라우저는 HTTP origin의 Secure refresh cookie 때문에 인증 직후 실패한다.
- Core의 기본 JDK client는 Uvicorn에 h2c upgrade를 시도해 계산 body를 잃는다.
- 필터 없는 거래 목록은 실제 PostgreSQL에서 파라미터 타입 오류로 500이다.

## Acceptance

1. `docker compose up --build`로 Web·Caddy·Core·Analysis·PostgreSQL·Redis를 기동한다.
2. Caddy의 로컬 HTTPS 단일 origin에서 Secure refresh cookie를 저장·회전·삭제한다.
3. 실제 Chromium이 사용자 A/B를 직접 만들고 온보딩 → 거래 → 목표 → 계획 → 옵션 선택 →
   대시보드 → 예정지출 → 재계획 → 새로고침·재로그인을 완주한다.
4. Core가 실제 Uvicorn에 온전한 JSON을 보내 option 3개와 모든 band를 실제 PostgreSQL에 한
   transaction으로 저장한다.
5. 거래 조회 무필터·단일필터·복합필터가 실제 PostgreSQL에서 200이다.
6. linked·pending·unmatched 환불과 늦은 PAYMENT resolve가 회귀 없이 유지된다.
7. 모든 테스트 자원은 loopback 또는 compose private network에만 노출되고 종료 시 정리된다.

## Focused tests

- JDK HTTP/1.1 Core→실제 Uvicorn request body·token·request ID·200/422
- 실제 PostgreSQL 거래 query의 null·category·type·기간 필터 조합
- drift int64 최대값, manual infeasible 422, 예정지출 동시 수정 snapshot
- 실제 HTTPS cookie login/refresh/logout, 멀티유저 IDOR
- Web의 실재하지 않는 날짜와 재계획 403·404·422 상태 보존

## Adversarial cases

- 동일 import·계획·재계획 동시 요청과 응답 유실 뒤 재시도
- Analysis 연결 거부·프로세스 종료 timeout·잘못된 성공 body와 부분 저장 0건
- Redis 중단·재기동·pending reclaim·중복 delivery
- int64 최대값, 0·음수, 월말·윤년·KST 자정
- trace·로그·컨테이너 inspect·열린 포트의 token/금융 원문 노출

## Full regression

- Analysis Ruff와 전체 unittest
- Core `./gradlew check --rerun-tasks`
- Web lint·Vitest·build
- 실제 PostgreSQL `03_verify.sql`, `06_verify_refund_allocations.sql`
- route interception 없는 실제 HTTPS Playwright
- 전체 diff에서 API·SQL·계산 숫자 변경과 새 의존성 없음 확인

## 불합격 조건

- `MOCK`, `CONTRACT_STUB`, in-memory DB, 고정 HTTP 응답, `page.route().fulfill()`을 실제 합격
  근거로 사용
- 실제 서비스 한 hop이라도 실행하지 못한 상태를 통과 처리
- token·cookie·금융 원문이 trace나 공유 로그에 남음
- 계획·재계획 실패 뒤 부분 DB 행 또는 중복 이벤트가 남음

## 결과 (2026-08-27, `scripts/real_scenario_qa.py`, 백엔드만)

- 4·5·6·7번 합격 조건은 REAL로 충족했다: Core가 실제 Uvicorn에 온전한 JSON을 보내 option
  3개·band 57개를 한 transaction으로 저장, 거래 조회 무필터·단일필터·복합필터 200, 환불
  linked·pending·unmatched·늦은 PAYMENT resolve 회귀 없음, 모든 테스트 자원은 loopback/compose
  private network에만 노출되고 종료 시 정리됨(격리 project 컨테이너·볼륨·이미지 0개 잔존
  확인).
- 2·3번(Caddy 로컬 HTTPS의 실제 Chromium 흐름, 인증→온보딩→...→재로그인 전체 브라우저 완주)은
  프론트 재작업 예정으로 이번 웨이브 범위 밖 — 미검증.
- adversarial cases 중 "Analysis 연결 거부·잘못된 성공 body와 부분 저장 0건", "int64 최댓값·
  음수", "trace·로그의 token/금융 원문 노출"은 검증했다. "Redis 중단·재기동·pending reclaim·
  중복 delivery"는 중단까지는 검증했지만 pending reclaim·중복 delivery는 미검증.
- full regression 중 Analysis Ruff·unittest, Core `./gradlew check`, `sql/03_verify.sql`,
  `sql/06_verify_refund_allocations.sql`은 통과. 실제 HTTPS Playwright는 범위 밖.
- 이 과정에서 새로 발견해 고친 결함 2건(월별 집계 SQL VIEW의 금액 overflow, 재계획 저장의
  Hibernate flush 순서)은 `docs/QA-결과.md`의 2026-08-27 항목에 기록했다.
