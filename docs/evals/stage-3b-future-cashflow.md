# Stage 3B future cashflow 평가

## 범위

Stage 3A의 `PlanInput.futureCashflowAdjustments`를 내부 simulate/custom-option 계약으로 전달하고,
Analysis의 기존 월별 누적저축 band에 deterministic positive cashflow로 반영한다.

## 기준선과 불변식

- `currentSavedAmount`, `monthlyIncome`, `monthlyFixedCost`, `availableVariableBudget`을 policy
  amount로 덮어쓰거나 선반영하지 않는다.
- adjustment는 추천 지출 역산이나 random seed/draw를 바꾸지 않고 월별 cashflow band에만 더한다.
- field 누락과 `futureCashflowAdjustments=[]`의 계산 결과는 동일하다.
- ONE_TIME은 정확한 지급월 한 번, MONTHLY는 start/end 포함 교집합 월마다 전액 적용한다.
- 같은 달 복수 adjustment는 checked int64 addition으로 합산한다.
- 기존 Planning의 시작월은 `targetDate`와 `horizonMonths`에서 재구성하며 새 `now()`를 사용하지
  않는다.

## 합격 조건

- Spring JSON은 `YYYY-MM`과 default empty list를 전달하고 `confirmedAt`은 계산 계약에서 제외한다.
- FastAPI는 legacy missing field를 empty로 처리하고 malformed month/type/기간을 거부한다.
- 지급 전 ONE_TIME band는 baseline과 같고 지급월부터 정확히 amount만 증가한다.
- MONTHLY는 inclusive 월마다 누적 delta가 amount씩 증가하고 종료 후 새 addition만 멈춘다.
- 세 PRESET은 같은 deterministic offset을 사용하며 policy amount를 level로 스케일하지 않는다.
- horizon 밖 ONE_TIME은 0, 일부 겹치는 MONTHLY는 교집합만 적용한다.

## Focused verification

- Core request mapping test
- Pydantic model/API compatibility tests
- deterministic helper 및 fixed-seed engine tests
- empty adjustment exact calculation regression
- engine self-check, Ruff, Core Spotless

DB, public API, Replan, frontend는 변경하거나 재검증하지 않는다.
