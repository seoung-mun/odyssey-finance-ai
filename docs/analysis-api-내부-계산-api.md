# analysis-api 내부 계산 API 구현

## 1. 목적과 책임 경계

`analysis-api`는 Spring이 전달한 재무 입력으로 시뮬레이션과 계획 옵션을 계산해 JSON으로
반환한다. DB를 읽거나 쓰지 않는다. 계획 버전, 시뮬레이션 실행, 옵션과 밴드 저장은
Spring이 한 트랜잭션에서 처리한다.

구현 기준은 `API/openapi-internal.yaml`이다. 기획서 5-5에는 block bootstrap이 적혀 있지만,
현재 내부 호출 계약은 IID bootstrap을 명시하므로 이 버전의 엔진은 IID를 사용한다.

구현된 경로는 다음과 같다.

| Method | Path | 역할 |
|---|---|---|
| GET | `/internal/health` | 엔진 및 LLM 준비 상태 반환 |
| POST | `/internal/simulate` | IID 경로 생성, PRESET 옵션과 밴드 계산 |
| POST | `/internal/custom-option` | 사용자가 정한 baseline의 충족률과 밴드 계산 |
| POST | `/internal/explanations` | 숫자가 없는 안전한 템플릿 fallback 반환 |

실제 LLM 모델이 확정되지 않았으므로 설명 API는 `FALLBACK`, `model: null`을 반환한다.
LLM, LangGraph, Redis 캐시는 이번 구현 범위에 포함하지 않았다.

## 2. 파일 구성

| 파일 | 책임 |
|---|---|
| `analysis-api/app/models.py` | OpenAPI 요청·응답 모델과 신뢰 경계 검증 |
| `analysis-api/app/main.py` | 내부 토큰 인증, 라우팅, 422/500 오류 분기 |
| `analysis-api/engine/planning.py` | FastAPI에 의존하지 않는 순수 계산 |
| `analysis-api/tests/test_models.py` | 요청·응답 계약 검증 |
| `analysis-api/tests/test_planning.py` | 계산식, 재현성, 수치 경계 검증 |
| `analysis-api/tests/test_internal_api.py` | 실제 HTTP 계약과 인증 검증 |

모든 API에는 `X-Internal-Token`이 필요하다. `APIKeyHeader`를 사용해 실행 시 인증뿐 아니라
FastAPI가 생성하는 OpenAPI에도 `internalApiKey` security scheme이 나타나게 했다.

## 3. 전체 데이터 흐름

```text
Spring의 camelCase JSON
  → Pydantic 검증 및 snake_case 변환
  → planning.py에 계산 입력 전달
  → 요청 전용 default_rng(seed)로 IID 경로 생성
  → PRESET 또는 CUSTOM 옵션 계산
  → 누적저축 percentile band 계산
  → Python int/camelCase 응답
  → Spring이 DB에 한 트랜잭션으로 저장
```

Pydantic 모델은 기본값까지 채운 camelCase snapshot을 별도로 만든다. 엔진은 이 snapshot만
canonical JSON으로 직렬화해 SHA-256 `inputHash`를 생성한다. 따라서 동일한 정규화 입력은
항상 동일한 해시를 만든다.

## 4. `engine/planning.py` 설명

### 4-1. 공개 인터페이스

외부에서 사용하는 함수는 세 개다.

```python
canonical_hash(payload: dict) -> str
compute_presets(payload: dict, input_snapshot: dict | None = None) -> dict
compute_custom(payload: dict, input_snapshot: dict | None = None) -> dict
```

`ComputeInputError`는 계산기가 예상할 수 있는 입력 문제만 표현한다. 라우트는 이 예외를
422 `ComputeError`로 바꾼다. 다른 예외는 잡지 않기 때문에 프로그래밍 오류가 계산 불가
422로 숨겨지지 않고 500으로 드러난다.

### 4-2. 입력 snapshot과 해시

`canonical_hash()`는 다음 규칙으로 JSON을 고정한다.

```python
json.dumps(payload, sort_keys=True, separators=(",", ":"))
```

Python 기본값인 `ensure_ascii=True`로 비ASCII 문자를 이스케이프하고, 키 순서와 공백
차이를 제거한 뒤 SHA-256을 계산한다. 라우트가 전달한 camelCase
`input_snapshot`이 있으면 그 객체만 해시와 응답에 사용한다. 엔진 함수를 직접 호출한
경우에는 계산 입력의 허용된 키만 camelCase로 바꿔 snapshot을 만든다.

### 4-3. IID 경로 생성

`_sample_paths()`는 과거 월별 유동지출에서 복원추출한다.

```text
history shape: [과거 개월]
sampled paths shape: [nPaths, horizonMonths]
```

요청마다 `np.random.default_rng(randomSeed)`를 새로 만든다. 전역 `np.random`을 사용하지
않으므로 동시 요청끼리 난수 상태가 섞이지 않고, 같은 seed와 입력은 같은 경로를 만든다.

예정지출은 sampling 대상과 경로별 총 유동지출 `T`에서 제외된다. 이미 확정된 이벤트이므로
확률 표본으로 다시 뽑지 않고, 나중에 누적저축 밴드에서 해당 월부터 고정 차감한다.

과거 최대 지출과 기간의 곱이 int64를 넘으면 NumPy 합산 전에 `ComputeInputError`를
발생시킨다. 경로를 만든 뒤 overflow를 발견하면 이미 잘못된 숫자가 계산에 섞이기 때문이다.

### 4-4. PRESET 계산

`compute_presets()`는 한 번 만든 경로를 모든 신뢰수준이 공유한다.

```text
T  = 각 경로의 horizon 전체 유동지출 합
Qp = percentile(T, p)
spending_ratio = availableVariableBudget / Qp
requiredReductionRate = 1 - spending_ratio
```

`presetLevels=[0.70, 0.80, 0.90]`이면 Q70, Q80, Q90을 한 번에 구한다. 옵션마다 경로를
다시 생성하지 않으므로 common random numbers가 유지된다.

예를 들어 과거 지출이 항상 100원이고 기간이 2개월이면 모든 경로의 `T`는 200원이다.
가용 유동예산이 100원이면 다음과 같다.

```text
spending_ratio = 100 / 200 = 0.5
requiredReductionRate = 1 - 0.5 = 0.5
현재 월평균이 100원이면 추천 월 지출 = 50원
```

PRESET의 `simulationCoverage`는 `totals <= quantile`의 비율로 계산한다. 처음에는 절감률을
구한 뒤 다시 `1 - reduction`을 계산했지만, `1/3`, `1/9` 같은 비율에서 부동소수점 상쇄로
정확한 예산 경계가 실패했다. 현재는 원래 spending ratio와 분위수 경계를 직접 사용한다.

가용예산이 Qp보다 크면 spending ratio가 1보다 커지고 절감률은 음수가 될 수 있다. 이는
현재 평균보다 더 써도 계획을 충족한다는 의미이므로 스키마도 음수 절감률을 허용한다.

### 4-5. CUSTOM 계산

`compute_custom()`은 사용자가 먼저 정한 월 baseline으로 비율을 계산한다.

```text
spending_ratio = baselineMonthlySpending / currentAvgVariableSpending
requiredReductionRate = 1 - spending_ratio
```

같은 seed와 sampling 입력으로 PRESET과 동일한 시나리오 형태를 재생성한다. 별도의 예측이나
재학습은 없다. `nominalLevel`은 반드시 `null`이다.

CUSTOM coverage는 부동소수점 곱셈 대신 다음과 동치인 정수 경계를 사용한다.

```text
T × baseline / currentAverage <= availableBudget
T <= floor(availableBudget × currentAverage / baseline)
```

현재 평균과 baseline이 모두 0이면 추천 지출 0으로 계산할 수 있다. 현재 평균만 0이고
baseline이 0보다 크면 비율을 정의할 수 없으므로 422로 반환한다.

### 4-6. 월별 누적저축 밴드

`_bands()`는 옵션 비율을 같은 sampled paths에 적용한다.

```text
reducedPaths = paths × spendingRatio
monthlySavings = currentAvgVariableSpending - reducedPaths
cumulativeSavings = cumsum(monthlySavings)
```

`currentMonthSpendingToDate`는 전체 누적 궤적에서 차감한다. 예정지출은 `monthIndex`부터
마지막 월까지 차감해 이후 누적값에도 계속 반영한다.

모든 경로의 누적저축에서 p10, p25, p50, p75, p90을 한 번에 계산한다. 출력 모델은
`p10 <= p25 <= p50 <= p75 <= p90`을 다시 검사하므로 잘못된 밴드가 Spring DB까지 가지
않는다.

현재 금액 출력은 `np.rint`의 ties-to-even 규칙으로 최근접 원에 반올림한 뒤 Python
`int`로 변환한다.

### 4-7. 옵션 보조 지표

`_option()`은 다음 값을 조립한다.

- `recommendedMonthlySpending`: 현재 월평균에 spending ratio를 적용한 금액
- `requiredReductionRate`: 현재 월평균 대비 감축률
- `simulationCoverage`: sampled paths 중 예산을 충족한 비율
- `historicalFeasibilityRatio`: 과거 월 중 추천 지출 이하였던 달의 비율
- `aggressiveWarning`: 과거 실현 비율이 정책 임계치 이하인지 여부

`policySnapshot.aggressiveWarningPct`가 없으면 0.10을 사용한다. 알려진 이 키만 숫자 범위를
검증하고, 다른 정책 키는 snapshot 보존을 위해 그대로 둔다.

## 5. 실제 발생한 문제와 해결

### 5-1. FastAPI가 DB CRUD를 해야 한다는 범위 오해

내부 명세와 `docs/주의사항.md`를 대조해 DB 쓰기는 Spring 전담임을 확인했다. FastAPI에는
저장소 계층을 만들지 않고 계산 API만 구현했다.

### 5-2. 기획서와 OpenAPI의 bootstrap 방식 불일치

기획서는 block bootstrap, 현재 내부 OpenAPI는 IID를 명시한다. 실제 Spring 호출 계약을
우선해 IID로 구현했다. block bootstrap 전환은 API와 기획 결정을 먼저 일치시킨 뒤 별도
변경으로 진행해야 한다.

### 5-3. Pydantic 검증 오류가 다시 500이 되는 문제

cross-field validator의 오류 detail에는 `ValueError` 객체가 포함될 수 있다. 이를 그대로
`JSONResponse`에 넣으면 직렬화가 실패한다. `jsonable_encoder(exc.errors())`로 변환한 뒤
422 응답을 만든다.

### 5-4. 모든 예외를 422로 숨길 위험

`RequestValidationError`와 `ComputeInputError`만 422로 처리한다. catch-all exception
handler는 등록하지 않았다. 테스트에서 `RuntimeError`를 강제로 발생시켜 500이 유지되는지
확인한다.

### 5-5. 부동소수점 상쇄로 금액과 coverage가 달라진 문제

다음 문제가 실제 테스트에서 재현됐다.

- 정확한 추천 지출 100원이 99원으로 절삭
- 예산과 정확히 같은 경로의 coverage가 1.0이 아니라 0.0
- CUSTOM 누적저축 2원이 1원으로 출력

절감률을 다시 뒤집지 않고 spending ratio를 유지하고, CUSTOM coverage는 정수 경계로
비교했다. 출력 금액은 최근접 원 반올림으로 통일했다.

### 5-6. 생성 OpenAPI에 내부 인증이 없던 문제

일반 `Header` dependency는 실행 인증은 했지만 생성 OpenAPI에 security scheme을 만들지
못했다. `APIKeyHeader`로 바꾸고 compute 경로에 422 `ComputeError` 응답 모델을 선언했다.

### 5-7. unittest가 0개를 발견한 문제

`tests/`가 package가 아니어서 `unittest discover`가 테스트를 찾지 못했다. 빈
`tests/__init__.py`를 추가해 전체 테스트를 발견하게 했다.

## 6. 검증 방법

```bash
cd analysis-api
uv sync
uv run ruff check .
uv run python -m engine.planning
uv run python -m engine.montecarlo
uv run python -m unittest discover -s tests -v
```

OpenAPI와 Compose 설정은 저장소 루트에서 검증한다.

```bash
cd API
npx --yes @redocly/cli lint openapi-public.yaml openapi-internal.yaml --config redocly.yaml
```

```bash
INTERNAL_API_TOKEN=verification-token docker compose config -q
```

실제 HTTP smoke test는 다음처럼 실행한다.

```bash
cd analysis-api
INTERNAL_API_TOKEN=secret uv run uvicorn app.main:app --port 8001
```

```bash
curl -H 'X-Internal-Token: secret' http://127.0.0.1:8001/internal/health
```

현재 자동 검증 결과는 Ruff, 두 엔진 셀프체크, OpenAPI lint, Compose config와 unittest
42개 통과다. TestClient 실행 시 Starlette가 HTTPX 대신 HTTPX2로 이전하라는 deprecation
warning을 출력하지만 현재 테스트 결과에는 영향을 주지 않는다.

## 7. 확인된 미해결 경계 문제

자동 테스트 밖의 추가 경계 검증에서 다음 두 건이 재현된다.

### 7-1. 지나치게 큰 정책 정수

`aggressiveWarningPct=10**1000`은 현재 `math.isfinite()` 호출에서 `OverflowError`가 발생한다.
잘못된 정책값이므로 422여야 하지만 현재는 예외가 전파된다.

수정 방향은 bool, int, float를 분리해 검사하는 것이다. int는 `0 <= value <= 1`만 비교하고,
float에만 `math.isfinite()`를 적용하면 된다.

### 7-2. int64 최댓값의 float 변환

유효한 int64 최댓값 `2**63-1`이 spending ratio 계산 과정에서 float로 바뀌면 정밀도를
잃고 `2**63`으로 반올림될 수 있다. 응답의 int64 상한을 1원 초과해 응답 검증 500이 된다.

수정 방향은 추천 금액과 밴드 계산에서 float 비율 대신 정수 교차곱, `Fraction` 또는
`Decimal`을 사용해 int64 경계까지 정확도를 유지하는 것이다.

### 7-3. `nPaths × horizonMonths` 상한

현재 두 값에는 최소값만 있고 제품 상한은 없다. 내부 토큰으로 보호되지만 인증된 오설정이
매우 큰 배열을 요청하면 OOM 위험이 있다. `n_paths`와 block size가 팀 논의 대기 항목이므로
임의의 상한은 넣지 않았다. 기준을 확정한 뒤 OpenAPI를 먼저 수정하고 Pydantic 상한을 같은
값으로 적용해야 한다.
