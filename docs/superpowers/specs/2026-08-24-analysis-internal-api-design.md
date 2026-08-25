# Analysis Internal API Design

## 목표

`API/openapi-internal.yaml`의 내부 API 4개를 FastAPI에 구현한다. FastAPI는 DB를
읽거나 쓰지 않고, Spring이 전달한 입력으로 계산한 JSON만 반환한다.

## 범위

- `GET /internal/health`
- `POST /internal/simulate`
- `POST /internal/custom-option`
- `POST /internal/explanations`
- 모든 경로의 `X-Internal-Token` 인증
- OpenAPI 요청·응답 계약과 공통 422 오류 형식

실제 LLM 호출, LangGraph, Redis 캐시, DB 연동은 제외한다. 모델이 확정되지 않았으므로
설명 API는 숫자가 없는 안전한 템플릿과 `FALLBACK` 상태를 반환한다.

## 구조

### `analysis-api/app/models.py`

OpenAPI의 camelCase 요청·응답을 Pydantic 모델로 표현한다. 금액과 기간은 신뢰 경계에서
검증하며, 음수 지출·음수 baseline·목표 기간 밖의 예정지출을 허용하지 않는다.
`nPaths`는 10,000, `horizonMonths`는 최대 120으로 제한하고 달력 월 비율을 필수로 받는다.

### `analysis-api/engine/planning.py`

FastAPI를 import하지 않는 순수 계산 모듈이다. 요청마다
`numpy.random.default_rng(random_seed)`를 만들고 과거 월별 유동지출에서 IID
복원추출한다. IID와 10,000 경로는 기획서와 내부 API의 확정값이다.

### `analysis-api/app/main.py`

인증, 라우팅, Pydantic 모델과 계산 함수 연결, `ComputeError` 형식의 422 변환을 맡는다.
CPU 연산 경로는 모두 동기 `def`로 선언한다.

## 계산 흐름

### 공통 시나리오

과거 월별 지출 `history`에서 `[10_000, horizon_months]` 경로를 한 번 생성하고 각 열에
`period_ratios`를 적용한다.
경로별 총 유동지출은 `T = paths.sum(axis=1)`이다. 예정지출은 `T`와 bootstrap 표본에서
제외한다.

요청 기본값까지 반영한 Pydantic 직렬화 결과를
`json.dumps(sort_keys=True, separators=(",", ":"))`로 정규화하고
SHA-256 해시를 계산한다. 같은 요청과 seed는 같은 경로·응답·해시를 만든다.

### PRESET

각 명목 수준 `p`에 대해 다음 값을 계산한다.

```text
Qp = percentile(T, p * 100)
r = 1 - available_variable_budget / Qp
recommended_monthly_spending = round(current_avg_variable_spending * (1 - r))
actual_ratio = recommended_monthly_spending / current_avg_variable_spending
simulation_coverage = mean(T * actual_ratio <= available_variable_budget)
historical_feasibility_ratio = mean(history <= recommended_monthly_spending)
```

`requiredReductionRate`와 `simulationCoverage`는 소수 4자리,
`nominalLevel`은 소수 3자리로 반올림한다. 금액과 numpy 정수는 Python `int`로
변환한다. `aggressiveWarning`은 `historicalFeasibilityRatio`가
`policySnapshot.aggressiveWarningPct` 이하일 때 참이며, 정책값이 없으면 `0.10`을
사용한다.

### CUSTOM

`requiredReductionRate = 1 - baseline / current_avg_variable_spending`으로 구하고 PRESET과
같은 경로에 적용한다. `nominalLevel`은 `null`이다. 현재 평균이 0이면 baseline도 0인
경우만 허용하고, 그 외에는 422를 반환한다.

### 월별 밴드

`CUMULATIVE_SAVINGS`는 현재 소비 수준에서 옵션을 적용해 추가로 확보한 누적 금액으로
정의한다.

```text
monthly_savings = current_avg_variable_spending * period_ratios - paths * actual_ratio
cumulative_savings = cumsum(monthly_savings)
```

이번 달 기지출은 Spring이 `availableVariableBudget`에 반영하므로 다시 차감하지 않는다.
예정지출은 해당 `monthIndex`에 고정 차감하고 이후 월 누적값에도 반영한다. 분위수
10·25·50·75·90은 한 번의
`numpy.percentile(..., axis=0)` 호출로 계산하고, `monthIndex` 1부터 목표 기간까지
빠짐없이 반환한다.

## 오류 처리

- 과거 지출 3개월 미만, 기간 1개월 미만, 경로 수 1 미만은 Pydantic 검증에서 차단한다.
- 음수 금액, 빈 과거 지출, 기간 밖 예정지출은 `422 ComputeError`로 변환한다.
- 모든 과거 지출이 0이라 분위수 기반 감축률을 구할 수 없으면 422를 반환한다.
- 토큰 누락·불일치는 비교 시간 공격을 피하도록 `secrets.compare_digest`로 검사하고 401을
  반환한다.

422와 500은 예외 타입으로 명시적으로 분기한다.

```text
RequestValidationError 또는 ComputeInputError -> 422 ComputeError
그 밖의 예외                                  -> FastAPI 기본 500
```

`ComputeInputError`는 엔진이 예상 가능한 입력 문제를 발견했을 때만 직접 발생시킨다.
라우트에서 `except Exception`으로 감싸지 않으며, 프로그래밍 오류·numpy 오류·응답 직렬화
오류처럼 예상하지 못한 실패는 422로 바꾸지 않는다. 테스트에서는 의도적인
`ComputeInputError`가 422인지, 임의의 `RuntimeError`가 500인지 각각 확인한다.

## 설명 fallback

설명 응답은 `status=FALLBACK`, `model=null`, `retryCount=0`, 빈 `failedNumbers`, UTC offset이
있는 `generatedAt`을 반환한다. 템플릿에는 숫자를 넣지 않아 LLM 숫자 검증 규칙을
우회하거나 위반하지 않는다. 실제 모델이 정해지면 별도 모듈에서 생성·숫자 검증·최대
재시도·fallback 순서로 연결한다.

## 검증

구현은 실패하는 테스트를 먼저 작성하고 통과시키는 순서로 진행한다.

- `engine/planning.py`의 `__main__` assert: seed 재현성, 밴드 단조성, 1개월 기간,
  PRESET 순서, CUSTOM 충족률, 계산 불가능한 0 지출
- `unittest`와 FastAPI `TestClient`: 토큰 누락 401, health 응답, 정상 simulate/custom,
  입력 오류 422 형식, 예상하지 못한 오류 500, 같은 seed의 동일 응답, 설명 fallback
- `uv run ruff check .`
- `uv run python -m engine.planning`
- `uv run python -m unittest discover -s tests -v`
- 실행 중인 uvicorn에 `curl`로 인증 포함 실제 요청 smoke test

`TestClient` 실행에 필요한 `httpx`만 개발 의존성으로 추가한다. 별도 테스트 프레임워크,
OpenAPI 코드 생성기, DB fixture는 추가하지 않는다.
