# 남은 작업

기준일: 2026-08-25  
표시: `[ ]` 미완료, `[x]` 결정 완료

이 문서는 FastAPI 개발에서 파생된 후속 작업 중심이다. 전체 제품 범위와 구현 순서는
`docs/통합-서비스-설계.md`, OpenAPI, SQL과 실제 코드 상태를 함께 본다.

## P0 — 백엔드 주의사항 QA 후속

- [ ] Spring의 원 단위 금액 평균 계산에서 `double`을 제거한다.
  - `PlanningQueryService`의 계산 입력 평균과 `TransactionServiceImpl`의 카테고리 평균을
    정수 또는 `BigDecimal` 기반으로 계산한다.
  - 원 단위 반올림 방향을 계약에 명시하고 `2^53` 전후와 `BIGINT` 경계 회귀 테스트를
    추가한다.
- [ ] `DataIntegrityViolationException`을 실제 PostgreSQL 제약 이름으로 분기한다.
  - 공개 계약의 `PROPOSED_PLAN_EXISTS`, `ACTIVE_GOAL_EXISTS`,
    `OPTION_ALREADY_SELECTED`, `DECISION_ALREADY_MADE`를 해당 409 경합에 매핑한다.
  - 예상하지 못한 무결성 오류를 일반 `STATE_CONFLICT`로 숨기지 않고 500으로 처리한다.
  - 정상 경합인 `23505`는 ERROR로 기록하지 않으며 제약별 응답 테스트를 추가한다.

완료 기준: 큰 정수 금액의 평균이 정확하고, 동시 요청에서 OpenAPI에 명시된 409 code가
반환되며 Core `./gradlew check`가 통과한다.

## P0 — `r_max` 사용자 설정과 계산 엔진

- [x] 사용자에게 `r_max` 비율을 직접 입력시키지 않는다.
  - 화면에서는 **최소 월 유동지출**을 원 단위로 받는다.
  - 설정 모드는 `OFF`, `AUTO`, `CUSTOM` 세 가지다.
  - 이 값은 안전한 생활비를 보장하는 값이 아니라, 사용자가 정하는 소비 절감 하한임을
    화면에 명시한다.
- [x] `AUTO`는 타인 평균이 아닌 사용자 본인의 최근 12개 완전월 유동지출 중 p20을
  추천한다.
  - 완전월 이력이 6개월 미만이면 임의의 기본값을 만들지 않고 `AUTO`를 비활성화한다.
  - 카테고리별 최솟값 합산은 데이터와 UX가 준비될 때만 재검토한다.
- [x] `financial_profiles`에 사용자 선호를 저장한다.
  - `spending_floor_mode`: `OFF | AUTO | CUSTOM`
  - `custom_monthly_variable_floor`: `CUSTOM`일 때만 0 이상 정수, 나머지는 `NULL`
  - DB CHECK와 `03_verify.sql` 검증을 함께 추가한다.
- [x] 공개 프로필 API에 위 설정의 조회·수정 계약을 추가한다.
- [x] 내부 계산 API에 자유 JSON이 아닌 타입이 있는 `spendingFloor` 입력을 추가한다.
  - `OFF`: 요청 하한 0원
  - `AUTO`: 엔진이 최근 12개 완전월의 p20을 원 단위로 계산
  - `CUSTOM`: 요청의 `customMonthlyAmount` 사용
  - 과거 월 배열은 오래된 달부터 최신 달 순서이며 부분 월을 포함하지 않는다고 명세한다.
- [x] 엔진에서 계획별 확정값을 계산한다.
  - `effectiveFloor = min(requestedFloor, currentAvgVariableSpending)`
  - 현재 평균이 0원이면 `effectiveFloor=0`, `r_max=0`
  - 그 외에는 `r_max = 1 - effectiveFloor / currentAvgVariableSpending`
  - PRESET 추천액은 `max(기존 추천액, effectiveFloor)`로 확정한 뒤 절감률, coverage,
    percentile band를 모두 그 금액에서 다시 파생한다.
  - CUSTOM 금액이 확정 하한보다 작으면 조용히 보정하지 않고 `INVALID_INPUT` 422로 거부한다.
- [x] 응답에 `resolvedSpendingFloor`, `effectiveMaxReductionRate`, `floorApplied`,
  `targetCoverageMet`를 노출한다.
  - 하한 때문에 목표 신뢰수준을 못 채워도 오류로 만들지 않고 실제 coverage를 반환한다.
  - Spring은 계산 당시 요청·확정값을 `plan_versions.policy_snapshot`에 저장해 과거 계획을
    재현할 수 있게 한다.
- [ ] 설정 변경은 기존 계획을 수정하지 않고 새 계획 버전을 만든다.
  - 기존 `USER_REQUESTED` 재계획 사유와 `trigger_details.reason=SPENDING_FLOOR_CHANGED`를
    재사용하며 새 enum은 만들지 않는다.
- [x] 기획서, 내부·공개 OpenAPI, README, 서비스별 AGENTS의 미확정 문구를 위 계약으로
  통일하고 내부 API/엔진 버전을 올린다.
- [x] 셀프체크와 테스트를 추가한다.
  - 세 모드, AUTO 이력 부족, p20 경계, 현재 평균 0원, 사용자 하한이 평균보다 큰 경우
  - 하한 미적용/적용, 목표 coverage 미달, CUSTOM 하한 위반
  - 추천액·절감률·coverage·band가 같은 확정 금액을 사용하는지 확인
  - 정수 경계, seed 재현성, 기존 10,000×120 성능 회귀 확인

완료 기준: DB 검증, 전체 unittest, 엔진 셀프체크, Ruff, OpenAPI lint, Compose 검증이
통과하고 Spring이 저장한 입력 스냅샷으로 같은 결과를 재현한다.

## P1 — 통계 백테스트

- [ ] 실제 사용자형 월별 데이터셋과 평가 기간을 고정하고 개인정보 없는 재현 가능한
  fixture를 만든다.
- [ ] rolling-origin 방식으로 과거만 학습 입력에 사용해 1·3·6개월 horizon을 평가한다.
- [ ] 70%·80%·90% 예측구간의 empirical coverage와 목표 coverage 차이를 기록한다.
- [ ] 전체 coverage 오차 절댓값 10%p 이내를 1차 통과선으로 둔다. horizon별 표본 수와
  신뢰구간도 함께 기록해 표본이 적은 결과를 합격처럼 해석하지 않는다.
- [ ] IID 결과가 통과선을 넘지 못할 때만 블록 부트스트랩을 같은 split에서 비교한다.
- [ ] seed, 데이터 버전, 실행 명령, 결과 표를 `analysis-api/bench/`와 분리된 백테스트
  보고서에 남긴다.

완료 기준: 누수 없는 실행 스크립트와 고정 결과 보고서가 있고, IID 유지 또는 방식 변경의
근거가 수치로 남아 있다.

## P2 — 실제 LLM과 숫자 가드레일

- [x] `qwen3.5:2b-q4_K_M` 로컬 사전 평가와 AWS 비용 후보를 기록했다.
  - 결과: `analysis-api/bench/qwen35-2b-evaluation.md`
  - 운영 context 2K, 활성 추론 1개를 기본 한계로 둔다.
  - 서울 리전 1차 후보는 CPU 전용 `t4g.large`이며 느리면 GPU 승급 대신 템플릿 fallback을
    사용한다.
- [x] 로컬 평가를 바탕으로 모델 `qwen3.5:2b-q4_K_M`과 프롬프트 `v1`을 고정한다.
- [ ] 배포 예정 하드웨어에서 한국어 설명 품질·지연시간·메모리를 다시 측정한다.
- [x] 계산 JSON만 입력받는 설명 모듈을 `engine/` 밖에 구현한다. LLM이 계산 엔진을 호출하거나
  숫자를 새로 만들 수 없게 한다.
- [x] 원, 만 원, %, 개월, 날짜 등 표시 형식을 정규화한 뒤 출력의 모든 숫자를 허용된 원본
  값과 대조한다.
- [x] 불일치 숫자를 다음 프롬프트에 전달해 최초 생성 후 최대 2회 교정하고, 최종
  실패·전체 15초 timeout·모델 장애는 항상 숫자 없는 템플릿 `FALLBACK`으로 종료한다.
- [x] 숫자 불일치 잔존율 0%, fallback 성공률 100%, 무한 재시도 0건을 자동 테스트로
  검증한다. 평균 재시도 횟수와 p50/p95 생성 시간을 별도로 기록한다.
- [ ] 실제 모델이 준비된 뒤에만 `llmReady=true`와 모델명을 노출한다.

완료 기준: 정상·숫자 환각·외국어 혼입·타임아웃 fixture가 모두 안전하게 종료되고 계산
응답의 숫자가 LLM 출력 때문에 변하지 않는다.

## P3 — Redis 설명 queue (캐시 제외)

- [x] 계산 API는 동기로 유지하고 LLM 설명 생성만 Redis Stream queue로 분리한다.
- [x] Redis cache는 구현하지 않는다.
- [x] Spring이 작업 접수와 공개 API 상태를 소유하고, LLM worker가
  `PENDING → PROCESSING → READY | FALLBACK | FAILED` 상태를 갱신하게 한다.
- [x] payload는 `planVersionId + inputHash + promptVersion`, 전체 deadline 15초, 최초 생성과
  최대 2회 교정으로 총 모델 호출 최대 3회, 중복 delivery 멱등과 pending reclaim으로 정의한다.
- [x] Redis 장애 시 계산 결과는 정상 제공하고 설명만 템플릿 fallback으로 낮추는 코드와
  단위 테스트를 구현한다.
- [ ] 실제 Redis 컨테이너 단절·재시작에서도 같은 장애 격리를 통합 검증한다.
- [ ] 큐 대기시간, worker 처리량과 실패율을 로그/메트릭으로 남긴다.

완료 기준: 중복 요청, worker 재시작, timeout, Redis 단절 테스트에서 작업 유실이나 무한
대기가 없다. cache는 이번 범위에 포함하지 않는다.

## P4 — Spring·프론트 통합

- [x] Spring이 KST 기준 `periodRatios`, 최종 `availableVariableBudget`, 완전월 이력,
  `spendingFloor`를 내부 API 계약대로 생성한다.
- [x] Spring이 계산 입력·결과·확정 하한을 하나의 계획 버전으로 원자적으로 저장한다.
- [x] 프론트에서 `OFF/AUTO/CUSTOM`, AUTO 추천 근거, 하한 적용 여부, 하한으로 낮아진 실제
  달성확률을 구분해 보여준다.
- [ ] 10일 같은 부분 월 목표가 월 단위 시뮬레이션 안에서 일수 비례로 표시되는 E2E 시나리오를
  포함한다.

완료 기준: 프로필 변경 → 재계획 → DB 저장 → fan chart/설명 조회가 한 사용자 흐름으로
통과한다.

## P5 — 실제 배포환경 부하 테스트

- [ ] staging 배포 전에 KST 월 경계를 UTC 호스트에서도 재검증하고 필요하면
  `hibernate.jdbc.time_zone`, Postgres `TZ`·`PGTZ`를 명시한다.
- [ ] `docs/미확정-설계.md` D-009의 PostgreSQL image tag를 확정한 뒤 Compose에 반영한다.
- [ ] 심사용 인스턴스와 분리된 staging에서 계산-only, DB 설명 재사용, 실시간 LLM 생성,
  조회+생성 혼합 workload를 정의한다.
- [ ] 동시 사용자별 처리량, HTTP p50/p95/p99, 오류율, Redis queue 대기시간, CPU/RSS,
  DB pool과 설명 재사용률을 수집한다. cache가 없으므로 hit ratio는 수집하지 않는다.
- [ ] 1차 게이트는 계산 API p95 500ms 이하, 오류율 1% 미만, 메모리 지속 증가 없음으로 둔다.
  LLM 포함 응답 기준은 실제 모델·하드웨어 측정 후 심사 UX 제한과 함께 확정한다.
- [ ] 병목을 한 번 측정한 뒤 필요한 부분만 수정하고 동일 workload로 전후 결과를 기록한다.
  - 계획 상세의 `plan → options → bands` N+1은 쿼리 수나 p95 병목이 확인될 때만
    `EntityGraph` 또는 fetch join으로 줄인다.
- [ ] 2026-09-07 11:00부터 2026-09-11 23:59까지 심사용 환경에서 부하 테스트, 배포,
  인프라 변경을 하지 않는다.

완료 기준: 실행 명령, 환경 사양, workload, 원본 결과, 요약 보고서가 남고 배포 게이트를
자동으로 판정할 수 있다.

## 문서 정합성 정리

- [x] `docs/기획서.md`의 `r_max` 미확정을 제거하고 위 사용자 선택형 정책으로 갱신한다.
- [x] 내부 API 문서의 남은 결정 절과 OpenAPI 설명을 실제 구현과 맞춘다.
- [ ] 벤치마크와 통계 백테스트를 구분한다. 전자는 리소스/지연시간, 후자는 예측 coverage를
  평가한다.
- [ ] 실제 구현 순서는 `r_max → 통계 백테스트 → LLM 가드레일 → 통합 → staging 부하
  테스트`로 유지한다. Redis는 설명 queue에만 사용하고 계산 비동기화는 staging p95 500ms
  초과 또는 CPU 포화가 실측될 때만 재검토한다.
