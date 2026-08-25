# P0 소비 하한 설정과 계획 계산 설계

## 범위

`TODO.md`의 P0만 구현한다. 사용자는 절감률을 직접 입력하지 않고 월 유동지출 하한을
`OFF`, `AUTO`, `CUSTOM` 중 하나로 정한다. Spring은 설정과 계획 버전을 소유하고,
FastAPI만 하한 금액과 계획 숫자를 계산하며, React는 입력과 확정 결과를 구분해 보여준다.

P1 통계 백테스트, P2 LLM, P3 Redis, P5 부하 테스트는 이번 웨이브에서 구현하지 않는다.

## 계약

### DB

`financial_profiles`에 다음 컬럼과 CHECK를 추가한다.

- `spending_floor_mode VARCHAR(10) NOT NULL DEFAULT 'OFF'`
- `custom_monthly_variable_floor BIGINT NULL`
- mode는 `OFF | AUTO | CUSTOM`만 허용한다.
- `CUSTOM`이면 custom 값은 0 이상이어야 하고, 나머지 mode에서는 반드시 `NULL`이다.

기존 행은 `OFF`로 해석한다. `plan_versions.policy_snapshot`에는 요청한 mode/custom 값과
FastAPI가 확정한 하한·최대 절감률을 함께 저장한다. 과거 계획 행은 수정하지 않는다.

### 공개 API

기존 `GET/PUT /me/financial-profile`을 확장한다.

- 입력·응답: `spendingFloorMode`, `customMonthlyVariableFloor`
- 응답 전용: `completeHistoryMonthCount`, `autoSpendingFloorAvailable`
- `AUTO`는 완전월 이력이 6개월 미만이면 400 `AUTO_SPENDING_FLOOR_UNAVAILABLE`로 거부한다.
- 설정이 실제로 바뀌면 기존 `USER_REQUESTED` 재계획 이벤트를 만들고
  `triggerDetails.reason=SPENDING_FLOOR_CHANGED`를 기록한다.

계획 응답은 확정 당시의 `resolvedSpendingFloor`, `effectiveMaxReductionRate`를 노출하고,
각 PRESET/CUSTOM 옵션은 `floorApplied`, `targetCoverageMet`를 노출한다.

### 내부 API

`SimulateRequest`와 이를 재사용하는 `CustomOptionRequest`에 자유 JSON이 아닌
`spendingFloor` 객체를 필수로 추가한다.

- `OFF`: `{ "mode": "OFF" }`
- `AUTO`: `{ "mode": "AUTO" }`
- `CUSTOM`: `{ "mode": "CUSTOM", "customMonthlyAmount": <원 단위 정수> }`

과거 월 배열은 오래된 달부터 최신 달 순서이며 완전월만 포함한다. `AUTO`는 최근 최대
12개월의 p20을 선형 분위수와 ties-to-even 원 단위 반올림으로 계산하고, 6개월 미만이면
422 `INSUFFICIENT_HISTORY`를 반환한다.

응답 최상위에는 `resolvedSpendingFloor`와 `effectiveMaxReductionRate`, 옵션마다
`floorApplied`와 `targetCoverageMet`를 추가한다. 엔진/API 버전은 함께 올린다.

## 계산

FastAPI는 다음 순서로 한 번만 확정한다.

1. mode에서 `requestedFloor`를 구한다. `OFF=0`, `AUTO=최근 12개 완전월 p20`,
   `CUSTOM=customMonthlyAmount`다.
2. `effectiveFloor = min(requestedFloor, currentAvgVariableSpending)`을 계산한다.
3. 현재 평균이 0이면 `effectiveFloor=0`, `effectiveMaxReductionRate=0`으로 한다. 그 외에는
   `1 - effectiveFloor / currentAvgVariableSpending`을 소수 4자리로 반환한다.
4. 각 PRESET의 기존 추천액을 `max(기존 추천액, effectiveFloor)`로 확정한다.
5. 확정 추천액 하나에서 절감률, 실제 coverage, percentile band를 모두 다시 파생한다.
6. `floorApplied`는 기존 추천액보다 확정 추천액이 커졌는지, `targetCoverageMet`는 반올림 전
   실제 coverage가 명목 수준 이상인지 나타낸다.

CUSTOM baseline이 `effectiveFloor`보다 작으면 보정하지 않고 422 `INVALID_INPUT`으로
거부한다. LLM과 Spring은 어떤 금액·비율·확률도 다시 계산하지 않는다.

## Spring 흐름과 트랜잭션

Spring은 KST로 완전월 이력, `periodRatios`, `availableVariableBudget`, 예정지출과 제안된
`spendingFloor`를 조립한다. FastAPI 호출 중 DB 트랜잭션이나 락을 유지하지 않는다.

1. 현재 프로필·ACTIVE 목표·계획 입력을 읽고 제안된 설정으로 계산 요청을 만든다.
2. FastAPI를 호출한다.
3. 쓰기 트랜잭션에서 사용자 행을 `FOR UPDATE`로 잠그고 계산 입력을 다시 조립해 읽었던
   입력 snapshot과 같은지 확인한다. 프로필·거래·목표·예정지출 등 계산 입력을 바꾸는
   Spring 쓰기 흐름도 같은 사용자 행을 먼저 잠근다.
4. 입력이 바뀌었으면 409를 반환한다.
5. 그대로면 프로필 변경, 재계획 이벤트, 새 계획 버전, simulation/options/bands를 한 번에
   저장하고 commit한다.

따라서 원격 timeout/5xx에는 설정이나 일부 계획 행이 저장되지 않고, 원격 성공 뒤 DB 실패도
전체 rollback된다. 같은 요청의 자동 재시도나 별도 멱등성 키는 이번 범위에 추가하지 않는다.
사용자 단위 잠금은 MVP 처리량에 맞춘 `ponytail:` 단순화이며, 사용자 한 명의 동시 쓰기가
병목으로 측정될 때만 더 작은 잠금으로 나눈다.

## Front 흐름

금융 프로필 화면에서 세 mode를 선택한다. `CUSTOM`만 원 단위 정수 입력을 열고, `AUTO`는
최근 12개 완전월의 p20이라는 근거를 표시한다. 이력이 6개월 미만이면 `AUTO`를 비활성화하고
이유를 보여준다. 이 값이 안전 생활비를 보장하지 않는 사용자 지정 소비 하한임을 명시한다.

저장 후 생성된 계획 화면은 요청 설정과 확정 하한을 구분하고, 하한 적용 여부와
`targetCoverageMet=false`일 때 낮아진 실제 달성확률을 색상 외 텍스트로 표시한다. API 화면은
loading/empty/error/success를 구분하고 중복 제출과 늦은 이전 응답을 무시한다.

## 병렬 작업과 소유권

메인이 SQL, OpenAPI, 기획서, 평가 문서를 먼저 확정한 뒤 세 빌더를 동시에 실행한다.

- Analysis 빌더: `analysis-api/`의 타입, 엔진, API, 셀프체크와 테스트만 수정한다.
- Core 빌더: `core-api/`의 프로필, 계산 client, 트랜잭션 저장과 테스트만 수정한다.
- Web 빌더: `web/`의 프로필·계획 흐름과 브라우저 상태만 수정한다.

빌더는 공용 계약이나 다른 서비스 파일을 수정하거나 커밋하지 않는다. 메인이 diff를 통합하고
커밋한다.

## 평가와 QA

구현 전에 `docs/evals/spending-floor-p0.md`에 baseline과 다음 합격 조건을 기록한다.

- DB: 세 mode와 CUSTOM 조건 CHECK, `03_verify.sql` 성공
- Analysis: 세 mode, AUTO 5/6/12개월, p20 경계, 평균 0, 하한>평균, coverage 미달,
  CUSTOM 위반, 정수 경계, seed 재현성, 10,000×120 회귀
- Core: KST 완전월·부분월 제외, timeout/5xx/잘못된 JSON, 409 경합, 전체 rollback,
  policy snapshot 재현
- Web: AUTO 비활성화, CUSTOM 검증, loading/empty/error, 연속 저장, 모바일·키보드,
  하한 적용과 coverage 미달 표시
- 통합: 프로필 변경 → 새 계획 버전 → DB 저장 → fan chart/설명 조회

빌더 완료 후 읽기 전용 QA 세 개를 병렬 실행한다.

- 계약·데이터 QA: OpenAPI/DB/금액·비율/트랜잭션
- 적대적 서비스 QA: 경계값, 중복·동시 요청, timeout, 인증, 잘못된 응답
- E2E·변경범위 QA: 브라우저 실패 상태, 핵심 흐름, 소유권 밖 변경

QA는 `severity/evidence/reproduce/impact/owner`만 보고하고 수정하지 않는다. blocker/high만
원 담당 빌더에게 돌리며 빌더·QA 수정 루프는 최대 2회다.

## 의도적으로 제외한 것

Redis, 신규 UI/상태관리/차트 의존성, 별도 하한 추천 endpoint, 카테고리별 하한,
자동 재시도·멱등성 키는 추가하지 않는다. P0 검증 또는 실제 운영 측정에서 필요성이 확인될
때만 별도 설계한다.
