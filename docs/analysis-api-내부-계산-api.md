# analysis-api 내부 계산 API

## 1. 이 서비스가 하는 일

`analysis-api`는 Spring이 넘겨준 재무 정보를 받아 다음 결과를 계산한다.

- 앞으로 소비가 어떻게 변할지 여러 경로를 만든다.
- 목표를 달성하기 위한 월 지출액과 절감률을 계산한다.
- 시간이 지날수록 저축액이 어떻게 쌓일지 범위를 만든다.

이 서비스는 DB를 읽거나 저장하지 않는다. Spring이 입력을 전달하면
`analysis-api`가 계산 결과만 JSON으로 돌려준다. 계획 버전과 계산 결과를 DB에
저장하는 일은 Spring의 책임이다.

```text
사용자 정보와 거래 내역
  → Spring이 계산용 입력을 만듦
  → analysis-api가 시뮬레이션과 계획을 계산
  → Spring이 결과를 DB에 저장
```

구현 기준은 `API/openapi-internal.yaml`이다. 몬테카를로 방식은 IID 부트스트랩,
경로 수는 10,000으로 확정했다. 채택 기록은 `docs/IID-vs-블록-부트스트랩.md`에 있다.

## 2. 먼저 알아야 할 용어

### 자동 추천 계획 (`PRESET`)

`PRESET`은 `present`가 아니라 “미리 정해둔 설정”을 뜻하는 `preset`이다. 사용자가
월 지출액을 직접 입력하지 않아도 엔진이 목표 달성 가능성에 따라 여러 계획을
자동으로 계산한다.

```text
느슨한 계획: 목표 달성 가능성을 낮게 잡고 지출을 덜 줄임
표준 계획: 중간 수준
빡센 계획: 목표 달성 가능성을 높게 잡고 지출을 더 줄임
```

### 직접 설정 계획 (`CUSTOM`)

사용자가 “앞으로 한 달에 80만 원만 쓰겠다”처럼 월 지출액을 직접 입력한다.
엔진은 그 계획으로 목표를 달성할 가능성을 다시 계산한다.

### 달성 비율 (`simulationCoverage`)

만든 미래 경로 중 예산을 지킨 경로의 비율이다. 10,000개 경로 중 8,000개가
목표를 달성했다면 `0.8`, 즉 80%다.

### 결과 범위 (`percentile band`)

미래를 하나의 금액으로 단정하지 않고 “낮은 경우부터 높은 경우까지”를 범위로
보여준다. `p50`은 가운데 결과고, `p10`과 `p90`은 상대적으로 낮거나 높은 결과다.
화면에서는 저축 예상 범위를 보여주는 fan chart의 자료가 된다.

### 계산 당시 입력 복사본 (`inputSnapshot`)

계산에 실제로 쓴 입력을 그대로 남긴 것이다. 나중에 거래 내역이 바뀌어도 “왜 당시에
이 결과가 나왔는지”를 확인할 수 있다.

## 3. 구현된 API

| 방식 | 경로 | 쉽게 풀어쓴 역할 |
|---|---|---|
| GET | `/internal/health` | 계산 엔진과 LLM이 사용 가능한지 확인 |
| POST | `/internal/simulate` | 미래 지출 경로를 만들고 자동 추천 계획 계산 |
| POST | `/internal/custom-option` | 사용자가 직접 정한 지출액의 달성 가능성 계산 |
| POST | `/internal/explanations` | 계산된 계획을 설명하는 글 반환 |

모든 API는 `X-Internal-Token`이 필요하다. 외부 사용자가 직접 호출하는 API가 아니라
Spring만 호출하는 내부 API이기 때문이다.

아직 실제 LLM 모델을 정하지 않았다. 따라서 설명 API는 숫자가 없는 안전한 기본 문구와
`FALLBACK`, `model: null`을 반환한다. LLM, LangGraph, Redis 캐시는 현재 범위에 없다.

## 4. 요청 하나가 처리되는 과정

```text
1. Spring이 camelCase JSON을 보낸다.
2. Pydantic이 금액·기간·배열을 검사한다.
3. Python 계산에 쓰기 편하게 snake_case로 바꾼다.
4. planning.py가 미래 지출 경로를 만든다.
5. 자동 추천 또는 직접 설정 계획을 계산한다.
6. 월별 누적저축 예상 범위를 만든다.
7. 결과를 camelCase JSON으로 Spring에 돌려준다.
8. Spring이 한 트랜잭션으로 DB에 저장한다.
```

`Pydantic`은 API입구에서 JSON의 형태와 범위를 검사하는 도구다. `camelCase`는
`currentAvgVariableSpending`, `snake_case`는 `current_avg_variable_spending`처럼 단어를
적는 방식의 차이다.

## 5. `planning.py` 계산 흐름

### 5-1. 동일한 입력인지 확인하는 해시

`inputSnapshot`은 Pydantic이 생략된 기본값까지 채운 뒤의 입력이다. 엔진은 이 객체를
다음 규칙으로 문자열로 바꾼다.

```python
json.dumps(input_snapshot, sort_keys=True, separators=(",", ":"))
```

- JSON 키를 항상 같은 순서로 정렬한다.
- 불필요한 공백을 제거한다.
- Python 기본값인 `ensure_ascii=True`를 사용한다.

정규화한 문자열에 SHA-256을 적용한 결과가 `inputHash`다. 해시는 계산을 대신하는
값이 아니라, 나중에 두 계산이 같은 입력으로 실행됐는지 확인하는 식별표다.

### 5-2. 미래 지출 경로 만들기

`_sample_paths()`는 과거 월별 유동지출에서 한 달씩 복원추출해 미래 경로를 만든다.
현재는 각 달을 독립적으로 뽑는 IID 방식이다.

```text
입력: 과거 12~24개월의 월별 지출
출력: nPaths개의 미래 경로, 각 경로는 horizonMonths개월
```

예를 들어 `nPaths=10_000`, `horizonMonths=12`면 “앞으로 12개월”을 가정한 소비
경로 10,000개를 만든다.

`horizonMonths`는 KST 기준 현재 월과 목표 월을 모두 포함하며 최대 120개월이다.
`periodRatios`는 같은 길이로, 첫 달과 마지막 달의 실제 활성 일수 비율을 담는다.
중간 달은 반드시 `1.0`이다. IID로 뽑은 각 열과 현재 월평균 기준값에 이 비율을
같이 적용한다.

같은 `randomSeed`와 입력은 항상 같은 경로를 만든다. 요청마다 독립된
`np.random.default_rng(randomSeed)`를 사용하므로 동시 요청의 난수 상태가 섞이지
않는다.

예정지출은 미래의 불확실한 소비가 아니라 이미 정해진 금액이다. 따라서 무작위로 뽑지
않고 해당 달의 누적저축에서 정해진 금액을 뺀다.

### 5-3. 자동 추천 계획 계산

`compute_presets()`는 미래 경로를 한 번만 만든 뒤 모든 계획에 같은 경로를 사용한다.
계획마다 다른 난수를 사용하면 강도 차이와 난수 차이를 구분하기 어렵기 때문이다.

```text
T  = 각 미래 경로의 전체 기간 지출 합계
Qp = T를 작은 순서로 줄 세웠을 때 p 지점의 금액
후보 소비 유지 비율 = 가용 유동예산 / Qp
추천 월지출 = 현재 월평균 × 후보 소비 유지 비율을 원 단위로 반올림
실제 소비 유지 비율 = 추천 월지출 / 현재 월평균
필요 절감률 = 1 - 실제 소비 유지 비율
```

숫자로 보면 다음과 같다.

```text
과거 월지출: 항상 100만 원
남은 기간: 2개월
경로별 총지출 T: 200만 원
가용 유동예산: 100만 원

소비 유지 비율 = 100 / 200 = 50%
필요 절감률 = 1 - 50% = 50%
추천 월지출 = 현재 월평균 100만 원 × 50% = 50만 원
```

`presetLevels=[0.70, 0.80, 0.90]`이면 70%, 80%, 90% 지점의 총지출을 한 번에
구한다. 더 높은 지점을 기준으로 삼을수록 고지출 경로까지 대비하므로 보통 더
빡센 계획이 된다.

가용예산이 충분히 크면 절감률이 음수가 될 수 있다. 이는 오류가 아니라 “현재 평균보다
더 써도 목표를 달성할 수 있다”는 뜻이다.

### 5-4. 직접 설정 계획 계산

`compute_custom()`은 사용자가 정한 월지출액을 현재 평균과 비교한다.

```text
현재 월평균: 100만 원
사용자가 정한 월지출: 80만 원

소비 유지 비율 = 80 / 100 = 80%
필요 절감률 = 1 - 80% = 20%
```

이 비율을 같은 미래 경로에 적용해 예산을 지킨 경로의 비율을 구한다. 자동 추천과
달리 목표 확률을 먼저 정한 계획이 아니므로 `nominalLevel`은 `null`이다.

현재 평균과 사용자 입력이 모두 0원이면 실제 소비 유지 비율과 절감률을 모두 0으로
계산한다. 현재 평균이 0원인데
사용자 입력만 0원보다 크면 비율을 나눌 수 없으므로 422를 반환한다.

### 5-5. 월별 저축 예상 범위 만들기

`_bands()`는 각 미래 경로에 계획의 소비 유지 비율을 적용한다.

```text
줄인 월지출 = 원래 경로의 월지출 × 소비 유지 비율
월저축 = 현재 월평균 지출 × periodRatio - 줄인 월지출
누적저축 = 1개월차부터 해당 달까지의 월저축 합계
```

이번 달 기지출은 Spring이 `availableVariableBudget`에 이미 한 번 반영하므로 여기서
다시 빼지 않는다. 예정지출은 해당 달부터 마지막 달까지의 누적값에 계속 영향을 준다.

모든 경로를 작은 순서로 보고 `p10`, `p25`, `p50`, `p75`, `p90`을 한 번에 계산한다.
응답 모델은 다음 순서가 깨지면 결과를 거부한다.

```text
p10 ≤ p25 ≤ p50 ≤ p75 ≤ p90
```

### 5-6. 화면과 DB에 저장할 요약값

`_option()`은 다음 값을 만든다.

| 필드 | 의미 |
|---|---|
| `recommendedMonthlySpending` | 계획이 추천하는 월지출액 |
| `requiredReductionRate` | 현재 월평균과 비교한 절감률 |
| `simulationCoverage` | 미래 경로 중 예산을 지킨 비율 |
| `historicalFeasibilityRatio` | 최근 24개월 중 추천액 이하로 실제 소비한 달의 비율 |
| `aggressiveWarning` | 과거에도 거의 없었던 수준으로 소비를 줄이라는 계획인지 |

`aggressiveWarningPct`가 없으면 10%를 기준으로 쓴다. 예를 들어 최근 24개월 중
추천액 이하로 쓴 달이 하나도 없으면 이 비율은 0%고, 10% 기준 이하이므로
`aggressiveWarning=true`가 된다.

### 5-7. 금액 범위와 반올림

금액 출력은 최근접 원 단위로 반올림한 Python `int`다. Spring과 DB의 `BIGINT`에 안전하게
저장하려면 부호 있는 int64 범위에 들어야 한다. 입력은 정상이어도 비율을 곱한
결과가 int64를 넘을 수 있으므로 공통 반올림 함수가 최종 금액도 검사한다. 범위를
넘으면 `INVALID_INPUT` 422를 반환한다.

## 6. 오류를 구분하는 방법

서버가 모든 예외를 422로 바꾸면 실제 코드 버그까지 “사용자 입력 문제”로 숨겨진다.
따라서 예상할 수 있는 입력·계산 문제만 422로 바꾼다.

| 상황 | 응답 |
|---|---|
| JSON 형태나 입력 범위가 틀림 | `INVALID_INPUT` 422 |
| 이력 배열 길이가 3개 미만 | `INSUFFICIENT_HISTORY` 422 |
| 이력 원소 금액 범위가 틀림 | `INVALID_INPUT` 422 |
| 기간이 1..120개월 밖임 | `INVALID_HORIZON` 422 |
| 계산 결과 금액이 int64를 넘음 | `INVALID_INPUT` 422 |
| 프로그래밍 오류나 예상하지 못한 서버 예외 | 500 |

`RequestValidationError`와 `ComputeInputError`만 422로 처리한다. 모든 예외를 잡는
catch-all handler는 없다. 테스트에서 `RuntimeError`를 강제로 발생시켜 500이 유지되는지
확인한다.

## 7. 코드 파일 안내

| 파일 | 역할 |
|---|---|
| `analysis-api/app/models.py` | API에 들어오고 나가는 JSON의 형태와 범위 검사 |
| `analysis-api/app/main.py` | 내부 토큰 인증, URL과 함수 연결, 422와 500 구분 |
| `analysis-api/engine/planning.py` | FastAPI나 DB를 모르는 순수 계산 로직 |
| `analysis-api/tests/test_models.py` | JSON 계약과 숫자 범위 테스트 |
| `analysis-api/tests/test_planning.py` | 계산식, 난수 재현성, 숫자 경계 테스트 |
| `analysis-api/tests/test_internal_api.py` | 인증부터 HTTP 응답까지 전체 API 테스트 |

`planning.py`를 먼저 읽을 때는 다음 순서를 추천한다.

```text
compute_presets() 또는 compute_custom()
  → _sample_paths(): 미래 경로 생성
  → _option(): 월지출·절감률·달성 비율 요약
  → _bands(): 월별 누적저축 범위
  → _simulation(): 입력 복사본·해시·엔진 버전 조립
```

## 8. 실제로 발생한 문제와 해결

### 8-1. FastAPI가 DB CRUD까지 해야 하는지 모호했던 문제

내부 OpenAPI와 `docs/주의사항.md`를 대조해 DB 저장은 Spring의 책임임을 확인했다.
FastAPI에는 저장소 계층을 만들지 않고 계산 API만 구현했다.

### 8-2. 부트스트랩 방식이 문서마다 달랐던 문제

IID와 10,000 경로를 기획서와 OpenAPI의 확정값으로 통일했다. 블록 방식은
rolling-origin 백테스트 결과가 나쁠 때만 재검토한다.

### 8-3. 잘못된 입력을 422로 바꾸는 과정이 다시 500을 만든 문제

Pydantic의 복합 검증 오류에는 JSON으로 바로 바꿀 수 없는 `ValueError` 객체가 들어갈
수 있다. 이를 그대로 응답에 넣어 직렬화가 실패했다. `jsonable_encoder()`로 먼저
바꾼 뒤 422 응답을 만들도록 수정했다.

### 8-4. 비율을 여러 번 뒤집으면 원 단위 경계가 틀려지던 문제

컴퓨터의 실수는 `1/3`처럼 끝나지 않는 값을 정확하게 저장하지 못한다. 그 결과 다음
문제가 실제 테스트에서 재현됐다.

- 정답인 100원이 99원으로 잘림
- 예산과 정확히 같은 경로가 실패한 것으로 계산됨
- 누적저축 2원이 1원으로 나옴

계산 중에 소비 유지 비율을 다시 절감률로 바꾸고 뒤집는 과정을 줄였다. 예산 경계는
정수 교차곱으로 비교하고, 출력 금액은 최근접 원으로 반올림했다.

### 8-5. 최근 24개월이 아닌 전체 이력을 비교하던 문제

`historicalFeasibilityRatio`의 계약은 최근 24개월 기준이지만 기존 코드는 받은 이력 전체를
사용했다. 24개월보다 긴 이력이 오면 과거의 다른 소비 습관 때문에 경고 결과가
뒤집힐 수 있었다. 현재는 가장 최근인 뒤쪽 24개월만 사용한다.

### 8-6. 한글이 들어간 입력의 해시가 명세와 달랐던 문제

Python은 JSON을 만들 때 한글을 그대로 남길 수도 있고 `\uXXXX`로 바꿀 수도 있다. 두
방식은 의미는 같지만 문자열이 다르므로 해시도 달라진다. OpenAPI의 공식에 맞춰
Python 기본값을 사용하고, 해시 대상이 기본값까지 적용된 `inputSnapshot`임을 문서에
명시했다.

### 8-7. 입력은 int64인데 계산 결과가 int64를 넘어 500이 나던 문제

기존 검사는 과거 지출과 기간을 곱한 값만 확인했다. 하지만 가용예산 때문에 소비 유지
비율이 크게 나오면 추천 월지출액이 int64를 넘을 수 있었다. 응답 모델 검증이 실패해
500이 나왔다.

현재는 추천 금액과 분위수 밴드가 모두 통과하는 공통 금액 변환 함수에서 int64 범위를
검사한다. 넘으면 예상 가능한 계산 불가 입력이므로 422를 반환한다. 이 분기는
예상하지 못한 서버 오류를 500으로 남겨두는 규칙과 충돌하지 않는다.

### 8-8. 실행 인증은 되는데 OpenAPI에는 인증이 없던 문제

일반 `Header` 검사는 요청을 막을 수는 있었지만 FastAPI가 만든 OpenAPI에 인증 방식을
표시하지 못했다. `APIKeyHeader`로 바꾸어 실행 인증과 OpenAPI 계약이 같아지게 했다.

### 8-9. 전체 테스트 명령이 테스트를 0개만 찾던 문제

`tests/`가 Python package가 아니어서 `unittest discover`가 테스트를 찾지 못했다.
`tests/__init__.py`를 추가해 전체 테스트가 발견되게 했다.

## 9. 검증 방법

### Python 코드

```bash
cd analysis-api
uv sync
uv run ruff check .
uv run python -m engine.planning
uv run python -m engine.montecarlo
uv run python -m unittest discover -s tests -v
```

### OpenAPI

```bash
cd API
npx --yes @redocly/cli lint openapi-public.yaml openapi-internal.yaml --config redocly.yaml
```

### Docker Compose 설정

```bash
INTERNAL_API_TOKEN=verification-token docker compose config -q
```

### 실제 HTTP 호출

```bash
cd analysis-api
INTERNAL_API_TOKEN=secret uv run uvicorn app.main:app --port 8001
```

오류 재현 시 `LOG_LEVEL=DEBUG`로 실행하면 요청 ID·경로·상태·처리시간을 확인할 수 있다.
예상하지 못한 예외는 같은 요청 ID와 스택을 ERROR로 남긴다. 토큰과 요청 본문은 기록하지
않으며, 호출 측이 보낸 안전한 `X-Request-ID`는 응답에도 그대로 반환한다.

다른 터미널에서:

```bash
curl -H 'X-Internal-Token: secret' http://127.0.0.1:8001/internal/health
```

현재 Python 자동 테스트는 67개다. `TestClient`를 실행하면 Starlette가 HTTPX2로 이전하라는
폐기 예정 경고를 출력하지만 현재 테스트 결과에는 영향을 주지 않는다.

## 10. 아직 남은 결정과 경계 문제

### 10-1. `r_max`

비현실적인 과도한 절감률을 막는 상한이다. 산정 기준과 API 입력 위치를 팀에서 아직
확정하지 않았으므로 현재 코드에는 넣지 않았다.

### 10-2. 실제 LLM 연결

모델 확정 전에는 설명 API가 항상 `200 FALLBACK`, `llmReady=false`, `retryCount=0`을
반환한다. 모델 선택과 숫자 가드레일 연결은 별도 작업이다.
