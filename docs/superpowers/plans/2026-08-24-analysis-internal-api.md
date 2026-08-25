# Analysis Internal API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `openapi-internal.yaml`의 인증된 내부 API 4개를 결정론적 계산과 안전한 설명 fallback으로 구현한다.

**Architecture:** Pydantic 모델이 HTTP 신뢰 경계를 검증하고, FastAPI를 모르는 `engine.planning`이 IID 경로·옵션·밴드를 계산한다. 라우트는 두 계층을 연결하며 예상 가능한 입력 오류만 422로 바꾸고 다른 예외는 500으로 남긴다.

**Tech Stack:** Python 3.11, FastAPI, Pydantic 2, NumPy, unittest, HTTPX

**Spec:** `docs/superpowers/specs/2026-08-24-analysis-internal-api-design.md`

## Global Constraints

- 모든 금액 출력은 Python `int`이며 계산 숫자를 LLM이 생성하지 않는다.
- 요청마다 `numpy.random.default_rng(randomSeed)`를 생성하고 전역 난수 상태를 사용하지 않는다.
- 계산 엔진은 FastAPI를 import하지 않는다.
- CPU 바운드 라우트는 `async def`가 아니라 `def`로 선언한다.
- FastAPI는 DB를 읽거나 쓰지 않는다.
- 실제 LLM 호출·LangGraph·Redis 캐시는 이번 범위에서 제외한다.
- 기존 미커밋 파일은 덮어쓰거나 일괄 포맷하지 않는다.

---

### Task 1: OpenAPI 요청·응답 모델

**Files:**
- Create: `analysis-api/app/models.py`
- Create: `analysis-api/tests/test_models.py`
- Modify: `analysis-api/pyproject.toml`
- Modify: `analysis-api/uv.lock`

**Interfaces:**
- Consumes: `API/openapi-internal.yaml`의 8개 schema와 camelCase 필드명
- Produces: `SimulateRequest`, `CustomOptionRequest`, `SimulateResponse`, `CustomOptionResponse`, `ComputedOption`, `PercentileBand`, `ExplanationRequest`, `ExplanationResponse`, `ComputeError`

- [ ] **Step 1: `httpx` 개발 의존성을 추가한다**

Run:

```bash
cd analysis-api && uv add --dev httpx
```

Expected: `pyproject.toml`의 dev group과 `uv.lock`에 HTTPX가 추가된다.

- [ ] **Step 2: 모델 경계의 실패 테스트를 작성한다**

```python
# analysis-api/tests/test_models.py
import unittest

from pydantic import ValidationError

from app.models import CustomOptionRequest, SimulateRequest


class SimulateRequestTest(unittest.TestCase):
    def test_defaults_and_aliases_follow_internal_contract(self):
        request = SimulateRequest.model_validate(
            {
                "randomSeed": 7,
                "horizonMonths": 2,
                "availableVariableBudget": 100,
                "historicalMonthlyVariableSpending": [80, 100, 120],
                "currentAvgVariableSpending": 100,
            }
        )

        self.assertEqual(request.n_paths, 10_000)
        self.assertEqual(request.preset_levels, [0.70, 0.80, 0.90])
        self.assertEqual(request.model_dump(by_alias=True)["randomSeed"], 7)

    def test_scheduled_expense_outside_horizon_is_rejected(self):
        with self.assertRaises(ValidationError):
            SimulateRequest.model_validate(
                {
                    "randomSeed": 7,
                    "horizonMonths": 2,
                    "availableVariableBudget": 100,
                    "historicalMonthlyVariableSpending": [80, 100, 120],
                    "currentAvgVariableSpending": 100,
                    "remainingScheduledExpenses": [{"monthIndex": 3, "amount": 10}],
                }
            )

    def test_negative_custom_baseline_is_rejected(self):
        with self.assertRaises(ValidationError):
            CustomOptionRequest.model_validate(
                {
                    "randomSeed": 7,
                    "horizonMonths": 2,
                    "availableVariableBudget": 100,
                    "historicalMonthlyVariableSpending": [80, 100, 120],
                    "currentAvgVariableSpending": 100,
                    "baselineMonthlySpending": -1,
                }
            )


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: 테스트가 모델 부재로 실패하는지 확인한다**

Run:

```bash
cd analysis-api && uv run python -m unittest tests.test_models -v
```

Expected: `ModuleNotFoundError: No module named 'app.models'`.

- [ ] **Step 4: 최소 Pydantic 모델을 구현한다**

`pydantic.alias_generators.to_camel`, `ConfigDict(populate_by_name=True, alias_generator=to_camel)`,
`Field` 제약과 `model_validator(mode="after")`를 사용한다. `SimulateRequest`의 Python 필드는
다음 시그니처를 유지한다.

```python
class SimulateRequest(ApiModel):
    random_seed: int
    n_paths: int = Field(default=10_000, ge=1)
    horizon_months: int = Field(ge=1)
    available_variable_budget: int = Field(ge=0)
    historical_monthly_variable_spending: list[Annotated[int, Field(ge=0)]] = Field(
        min_length=3
    )
    current_avg_variable_spending: int = Field(ge=0)
    current_month_spending_to_date: int = Field(default=0, ge=0)
    remaining_scheduled_expenses: list[ScheduledExpense] = Field(default_factory=list)
    preset_levels: list[Annotated[float, Field(gt=0, lt=1)]] = Field(
        default_factory=lambda: [0.70, 0.80, 0.90]
    )
    policy_snapshot: dict[str, Any] = Field(default_factory=dict)
```

`CustomOptionRequest`는 `SimulateRequest`를 상속하고 `baseline_monthly_spending: int =
Field(ge=0)`만 추가한다. 응답 모델은 명세의 required 필드를 필수로 선언하고
`option_type: Literal["PRESET", "CUSTOM"]`, `status: Literal["READY", "FALLBACK"]`을
사용한다.

- [ ] **Step 5: 모델 테스트와 린트를 통과시킨다**

Run:

```bash
cd analysis-api && uv run python -m unittest tests.test_models -v
cd analysis-api && uv run ruff check app/models.py tests/test_models.py
```

Expected: 3 tests pass, Ruff exits 0.

- [ ] **Step 6: 모델 단위 변경을 커밋한다**

```bash
git add analysis-api/app/models.py analysis-api/tests/test_models.py analysis-api/pyproject.toml analysis-api/uv.lock
git commit -m "feat(analysis): 내부 API 계약 모델 추가"
```

---

### Task 2: 결정론적 IID 계획 엔진

**Files:**
- Create: `analysis-api/engine/planning.py`
- Create: `analysis-api/tests/test_planning.py`

**Interfaces:**
- Consumes: `compute_presets(payload: dict)`, `compute_custom(payload: dict)`의 정규화된 snake_case dict
- Produces: OpenAPI 응답에 바로 넣을 수 있는 camelCase dict, `ComputeInputError(code, message, detail=None)`, `canonical_hash(payload: dict) -> str`

- [ ] **Step 1: 고정 입력의 계산 결과 테스트를 작성한다**

```python
# analysis-api/tests/test_planning.py
import unittest

from engine.planning import ComputeInputError, canonical_hash, compute_custom, compute_presets


BASE = {
    "random_seed": 3,
    "n_paths": 8,
    "horizon_months": 2,
    "available_variable_budget": 100,
    "historical_monthly_variable_spending": [100, 100, 100],
    "current_avg_variable_spending": 100,
    "current_month_spending_to_date": 5,
    "remaining_scheduled_expenses": [{"month_index": 2, "amount": 10}],
    "preset_levels": [0.70],
    "policy_snapshot": {"aggressiveWarningPct": 0.10},
}


class PlanningTest(unittest.TestCase):
    def test_canonical_hash_sorts_keys(self):
        self.assertEqual(
            canonical_hash({"b": 2, "a": 1}),
            "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777",
        )

    def test_preset_uses_closed_form_and_builds_monotonic_bands(self):
        result = compute_presets(BASE)

        self.assertEqual(result["options"][0]["requiredReductionRate"], 0.5)
        self.assertEqual(result["options"][0]["recommendedMonthlySpending"], 50)
        self.assertEqual(result["options"][0]["simulationCoverage"], 1.0)
        self.assertEqual(result["percentileBands"][0]["p50"], 45)
        self.assertEqual(result["percentileBands"][1]["p50"], 85)
        for band in result["percentileBands"]:
            self.assertLessEqual(band["p10"], band["p25"])
            self.assertLessEqual(band["p25"], band["p50"])
            self.assertLessEqual(band["p50"], band["p75"])
            self.assertLessEqual(band["p75"], band["p90"])

    def test_same_seed_reproduces_result(self):
        self.assertEqual(compute_presets(BASE), compute_presets(BASE))

    def test_custom_applies_baseline_to_same_scenario_shape(self):
        result = compute_custom({**BASE, "available_variable_budget": 160, "baseline_monthly_spending": 80})

        self.assertIsNone(result["option"]["nominalLevel"])
        self.assertEqual(result["option"]["requiredReductionRate"], 0.2)
        self.assertEqual(result["option"]["simulationCoverage"], 1.0)

    def test_zero_history_is_expected_compute_error(self):
        with self.assertRaises(ComputeInputError) as caught:
            compute_presets({**BASE, "historical_monthly_variable_spending": [0, 0, 0]})

        self.assertEqual(caught.exception.code, "INSUFFICIENT_HISTORY")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 엔진 부재로 테스트가 실패하는지 확인한다**

Run:

```bash
cd analysis-api && uv run python -m unittest tests.test_planning -v
```

Expected: `ModuleNotFoundError: No module named 'engine.planning'`.

- [ ] **Step 3: 최소 벡터 계산을 구현한다**

다음 공개 인터페이스와 핵심 계산을 유지한다.

```python
class ComputeInputError(ValueError):
    def __init__(self, code: str, message: str, detail: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.detail = detail


def canonical_hash(payload: dict) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


def _sample_paths(payload: dict) -> np.ndarray:
    history = np.asarray(payload["historical_monthly_variable_spending"], dtype=np.int64)
    rng = np.random.default_rng(payload["random_seed"])
    return rng.choice(history, size=(payload["n_paths"], payload["horizon_months"]))


def compute_presets(payload: dict) -> dict:
    paths = _sample_paths(payload)
    totals = paths.sum(axis=1)
    if not totals.any():
        raise ComputeInputError("INSUFFICIENT_HISTORY", "0보다 큰 과거 지출이 필요합니다")
    quantiles = np.percentile(totals, np.asarray(payload["preset_levels"]) * 100)
    reductions = 1 - payload["available_variable_budget"] / quantiles
    return _preset_response(payload, paths, totals, reductions)


def compute_custom(payload: dict) -> dict:
    current_average = payload["current_avg_variable_spending"]
    baseline = payload["baseline_monthly_spending"]
    if current_average == 0 and baseline != 0:
        raise ComputeInputError("INVALID_INPUT", "현재 평균이 0이면 baseline도 0이어야 합니다")
    reduction = 0.0 if current_average == 0 else 1 - baseline / current_average
    paths = _sample_paths(payload)
    return _custom_response(payload, paths, reduction)
```

`_preset_response`와 `_custom_response`는 같은 파일의 내부 함수이며 `_option`과 `_bands`를
공유한다. `_bands`의 누적 계산은 다음과 같다.

```python
reduced_paths = paths * (1 - reduction)
monthly_savings = payload["current_avg_variable_spending"] - reduced_paths
cumulative = np.cumsum(monthly_savings, axis=1)
cumulative -= payload["current_month_spending_to_date"]
for expense in payload["remaining_scheduled_expenses"]:
    cumulative[:, expense["month_index"] - 1 :] -= expense["amount"]
percentiles = np.percentile(cumulative, [10, 25, 50, 75, 90], axis=0)
```

내부에서는 `rng.choice(history, size=(n_paths, horizon))`, 경로별 `sum(axis=1)`, 한 번의
`np.percentile(cumulative, [10, 25, 50, 75, 90], axis=0)`만 사용한다. 응답 금액은
`int()`, 비율은 소수 4자리, nominal level은 소수 3자리로 반올림한다.
`inputSnapshot`은 정규화 payload 전체, `resultSummary`는 각 preset의 총지출 분위수와
중앙 누적저축 최종값을 담아 빈 객체가 되지 않게 한다. 엔진 버전은 프로젝트 버전과 같은
`0.1.0`으로 둔다.

- [ ] **Step 4: 레포 관례의 assert 셀프체크를 추가한다**

`planning.py` 하단에서 1개월 입력, seed 재현성, 밴드 단조성, 0 지출 오류를 assert하고
`planning.py self-check OK`를 출력한다. 테스트 fixture와 별도 프레임워크를 import하지 않는다.

- [ ] **Step 5: 엔진 테스트·셀프체크·린트를 통과시킨다**

Run:

```bash
cd analysis-api && uv run python -m unittest tests.test_planning -v
cd analysis-api && uv run python -m engine.planning
cd analysis-api && uv run ruff check engine/planning.py tests/test_planning.py
```

Expected: 5 tests pass, self-check OK, Ruff exits 0.

- [ ] **Step 6: 엔진 변경을 커밋한다**

```bash
git add analysis-api/engine/planning.py analysis-api/tests/test_planning.py
git commit -m "feat(engine): IID 기반 계획 계산 추가"
```

---

### Task 3: 인증된 FastAPI 라우트와 오류 분기

**Files:**
- Modify: `analysis-api/app/main.py`
- Create: `analysis-api/tests/test_internal_api.py`

**Interfaces:**
- Consumes: Task 1의 Pydantic 모델, Task 2의 `compute_presets`, `compute_custom`, `ComputeInputError`
- Produces: `/internal/health`, `/internal/simulate`, `/internal/custom-option`, `/internal/explanations`

- [ ] **Step 1: 실제 HTTP 계약의 실패 테스트를 작성한다**

```python
# analysis-api/tests/test_internal_api.py
import re
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app


VALID = {
    "randomSeed": 3,
    "nPaths": 8,
    "horizonMonths": 2,
    "availableVariableBudget": 100,
    "historicalMonthlyVariableSpending": [100, 100, 100],
    "currentAvgVariableSpending": 100,
}


class InternalApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.environment = patch.dict("os.environ", {"INTERNAL_API_TOKEN": "secret"})
        cls.environment.start()
        cls.client = TestClient(app, raise_server_exceptions=False)
        cls.headers = {"X-Internal-Token": "secret"}

    @classmethod
    def tearDownClass(cls):
        cls.environment.stop()

    def test_missing_token_is_unauthorized(self):
        self.assertEqual(self.client.get("/internal/health").status_code, 401)

    def test_health_reports_fallback_readiness(self):
        response = self.client.get("/internal/health", headers=self.headers)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")
        self.assertFalse(response.json()["llmReady"])

    def test_simulate_returns_contract_shape(self):
        response = self.client.post("/internal/simulate", headers=self.headers, json=VALID)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["simulation"]["method"], "IID_BOOTSTRAP")
        self.assertEqual(len(response.json()["options"]), 3)
        self.assertEqual(len(response.json()["percentileBands"]), 6)

    def test_invalid_horizon_uses_compute_error(self):
        response = self.client.post(
            "/internal/simulate", headers=self.headers, json={**VALID, "horizonMonths": 0}
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_HORIZON")

    def test_expected_compute_error_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={**VALID, "historicalMonthlyVariableSpending": [0, 0, 0]},
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INSUFFICIENT_HISTORY")

    def test_unexpected_error_remains_500(self):
        with patch("app.main.compute_presets", side_effect=RuntimeError("broken")):
            response = self.client.post("/internal/simulate", headers=self.headers, json=VALID)

        self.assertEqual(response.status_code, 500)

    def test_custom_option_returns_custom_contract(self):
        response = self.client.post(
            "/internal/custom-option",
            headers=self.headers,
            json={**VALID, "availableVariableBudget": 160, "baselineMonthlySpending": 80},
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["option"]["optionType"], "CUSTOM")
        self.assertIsNone(response.json()["option"]["nominalLevel"])

    def test_explanation_is_number_free_fallback(self):
        response = self.client.post(
            "/internal/explanations",
            headers=self.headers,
            json={
                "planVersionId": 1,
                "allowedNumbers": [100, 80, 2],
                "plan": {
                    "recommendedMonthlySpending": 80,
                    "currentAvgVariableSpending": 100,
                    "remainingMonths": 2,
                },
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "FALLBACK")
        self.assertIsNone(response.json()["model"])
        self.assertIsNone(re.search(r"\d", response.json()["text"]))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 미구현 라우트 때문에 테스트가 실패하는지 확인한다**

Run:

```bash
cd analysis-api && uv run python -m unittest tests.test_internal_api -v
```

Expected: health 외 경로가 404를 반환해 실패한다.

- [ ] **Step 3: 공통 오류 handler와 동기 라우트를 구현한다**

```python
@app.exception_handler(RequestValidationError)
def handle_validation_error(_request: Request, exc: RequestValidationError):
    locations = {str(part) for error in exc.errors() for part in error["loc"]}
    if "historicalMonthlyVariableSpending" in locations:
        code = "INSUFFICIENT_HISTORY"
    elif {"horizonMonths", "remainingScheduledExpenses"} & locations:
        code = "INVALID_HORIZON"
    else:
        code = "INVALID_INPUT"
    body = ComputeError(code=code, message="요청 값을 확인해 주세요", detail={"errors": exc.errors()})
    return JSONResponse(status_code=422, content=body.model_dump(by_alias=True))


@app.exception_handler(ComputeInputError)
def handle_compute_error(_request: Request, exc: ComputeInputError):
    body = ComputeError(code=exc.code, message=exc.message, detail=exc.detail)
    return JSONResponse(status_code=422, content=body.model_dump(by_alias=True))


@app.post("/internal/simulate", response_model=SimulateResponse)
def simulate(request: SimulateRequest):
    return compute_presets(request.model_dump())


@app.post("/internal/custom-option", response_model=CustomOptionResponse)
def custom_option(request: CustomOptionRequest):
    return compute_custom(request.model_dump())


@app.post("/internal/explanations", response_model=ExplanationResponse)
def explanations(_request: ExplanationRequest):
    return ExplanationResponse(
        status="FALLBACK",
        text=(
            "계산된 계획을 확인해 주세요.\n"
            "현재 소비 흐름을 반영했습니다.\n"
            "상황이 바뀌면 다시 계산할 수 있습니다."
        ),
        model=None,
        retry_count=0,
        failed_numbers=[],
        generated_at=datetime.now(timezone.utc),
    )
```

Pydantic 오류의 위치에 `historicalMonthlyVariableSpending`이 있으면
`INSUFFICIENT_HISTORY`, `horizonMonths`나 `remainingScheduledExpenses`가 있으면
`INVALID_HORIZON`, 나머지는 `INVALID_INPUT`으로 매핑한다. `ComputeInputError`는 자체
code/message/detail을 그대로 반환한다. `Exception` handler는 등록하지 않는다.

health는 `status="ok"`, `engineVersion="0.1.0"`, `llmModel=""`, `llmReady=false`를
반환한다. explanation은 숫자가 없는 한국어 3줄, `model=None`, `retryCount=0`,
`failedNumbers=[]`, `datetime.now(timezone.utc)`를 반환한다.

- [ ] **Step 4: API 테스트와 기존 main 셀프체크를 통과시킨다**

Run:

```bash
cd analysis-api && uv run python -m unittest tests.test_internal_api -v
cd analysis-api && uv run python -m app.main
cd analysis-api && uv run ruff check app/main.py tests/test_internal_api.py
```

Expected: 8 tests pass, main self-check OK, Ruff exits 0.

- [ ] **Step 5: 라우트 변경을 커밋한다**

`analysis-api/app/main.py`에는 작업 시작 전 사용자 미커밋 인증 변경이 있으므로, 사용자
확인 없이 이 단계의 커밋을 실행하지 않는다. 커밋 허가를 받은 경우에만 다음을 실행한다.

```bash
git add analysis-api/app/main.py analysis-api/tests/test_internal_api.py
git commit -m "feat(analysis): 내부 계산 API 라우트 연결"
```

---

### Task 4: 전체 계약과 실제 요청 검증

**Files:**
- Modify: `analysis-api/tests/test_internal_api.py`

**Interfaces:**
- Consumes: 완성된 FastAPI app과 OpenAPI 내부 계약
- Produces: 회귀 검증 명령과 실제 HTTP smoke 결과

- [ ] **Step 1: 앱 OpenAPI 경로 검사를 추가한다**

```python
def test_openapi_contains_all_internal_operations(self):
    paths = app.openapi()["paths"]

    self.assertEqual(paths["/internal/simulate"]["post"]["operationId"], "simulatePlan")
    self.assertEqual(
        paths["/internal/custom-option"]["post"]["operationId"], "computeCustomOption"
    )
    self.assertEqual(
        paths["/internal/explanations"]["post"]["operationId"], "generateExplanation"
    )
    self.assertEqual(paths["/internal/health"]["get"]["operationId"], "getInternalHealth")
```

- [ ] **Step 2: operationId가 달라 실패하는지 확인한다**

Run:

```bash
cd analysis-api && uv run python -m unittest tests.test_internal_api.InternalApiTest.test_openapi_contains_all_internal_operations -v
```

Expected: FastAPI 자동 operationId가 명세 값과 달라 FAIL.

- [ ] **Step 3: 각 decorator에 명세 operationId를 추가한다**

```python
@app.post("/internal/simulate", operation_id="simulatePlan", response_model=SimulateResponse)
```

나머지 세 경로에도 각각 `computeCustomOption`, `generateExplanation`,
`getInternalHealth`를 지정한다.

- [ ] **Step 4: 전체 자동 검증을 실행한다**

Run:

```bash
cd analysis-api && uv run ruff check .
cd analysis-api && uv run python -m engine.planning
cd analysis-api && uv run python -m engine.montecarlo
cd analysis-api && uv run python -m unittest discover -s tests -v
```

Expected: Ruff exits 0, 두 engine self-check가 OK, 모든 unittest가 PASS.

- [ ] **Step 5: 실제 uvicorn에 인증 요청을 보낸다**

Terminal 1:

```bash
cd analysis-api && INTERNAL_API_TOKEN=secret uv run uvicorn app.main:app --port 8001
```

Terminal 2:

```bash
curl -sS -H 'X-Internal-Token: secret' http://127.0.0.1:8001/internal/health
curl -sS -X POST -H 'Content-Type: application/json' -H 'X-Internal-Token: secret' \
  http://127.0.0.1:8001/internal/simulate \
  -d '{"randomSeed":3,"nPaths":8,"horizonMonths":2,"availableVariableBudget":100,"historicalMonthlyVariableSpending":[100,100,100],"currentAvgVariableSpending":100}'
```

Expected: health는 `status=ok`, simulate는 `IID_BOOTSTRAP`, 옵션 3개, 밴드 6개를 반환한다.

- [ ] **Step 6: 최종 diff를 확인한다**

Run:

```bash
git diff --check
git status --short
```

Expected: whitespace 오류가 없고, 기존 사용자 변경이 보존되어 있다. 커밋은 Task 3의 사용자
변경 확인 전에는 추가로 만들지 않는다.
