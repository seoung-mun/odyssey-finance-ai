"""내부 계획 API의 결정론적 계산 엔진.

처리 흐름:
1. 과거 월별 유동지출에서 요청 전용 seed로 IID 경로를 한 번 생성한다.
2. PRESET은 경로별 총지출 분위수, CUSTOM은 사용자 baseline으로 소비 비율을 정한다.
3. 같은 경로에 비율을 적용해 옵션과 월별 누적저축 percentile band를 만든다.
4. 계산 당시 입력 snapshot과 결과 요약을 Spring이 저장할 JSON으로 반환한다.

FastAPI와 DB를 import하지 않는다. 이 파일의 숫자는 전부 규칙 기반 계산과 NumPy
시뮬레이션에서 나오며 LLM은 관여하지 않는다.
"""

import hashlib
import json

import numpy as np

ENGINE_VERSION = "0.1.1"
_INT64_MIN = -(2**63)
_INT64_MAX = 2**63 - 1
_SNAPSHOT_KEYS = (
    "random_seed",
    "n_paths",
    "horizon_months",
    "available_variable_budget",
    "historical_monthly_variable_spending",
    "current_avg_variable_spending",
    "current_month_spending_to_date",
    "remaining_scheduled_expenses",
    "preset_levels",
    "policy_snapshot",
    "baseline_monthly_spending",
)


class ComputeInputError(ValueError):
    """정상적인 요청 처리 중 예상할 수 있는 계산 불가 입력.

    app 계층은 이 예외만 422로 변환한다. NumPy 오류나 프로그래밍 오류까지 이 타입으로
    감싸면 서버 결함이 사용자 입력 오류로 숨겨지므로 catch-all 용도로 사용하지 않는다.
    """

    def __init__(self, code: str, message: str, detail: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.detail = detail


def canonical_hash(payload: dict) -> str:
    """키 순서와 공백에 영향받지 않는 입력 snapshot SHA-256을 만든다."""

    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


def _camel_case(value):
    """엔진 직접 호출 시 snake_case 계산 입력을 API snapshot 형태로 바꾼다."""

    if isinstance(value, dict):
        return {_camel_case_key(key): _camel_case(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_camel_case(item) for item in value]
    return value


def _camel_case_key(key: str) -> str:
    """snake_case 키 하나를 lowerCamelCase로 변환한다."""

    head, *tail = key.split("_")
    return head + "".join(part.capitalize() for part in tail)


def _normalized_snapshot(payload: dict, input_snapshot: dict | None) -> dict:
    """해시와 응답에 남길 입력만 선택한다.

    FastAPI 호출에서는 Pydantic 기본값까지 반영된 camelCase snapshot이 넘어온다. 엔진을
    배치나 셀프체크에서 직접 호출할 때만 허용 목록으로 snapshot을 재구성한다. 계산 중간값이
    hash 입력에 섞이면 같은 사용자 입력의 hash가 구현 세부사항에 따라 달라진다.
    """

    if input_snapshot is not None:
        return input_snapshot
    return _camel_case({key: payload[key] for key in _SNAPSHOT_KEYS if key in payload})


def _sample_paths(payload: dict) -> np.ndarray:
    """과거 월별 지출에서 ``[n_paths, horizon_months]`` IID 경로를 생성한다.

    예정지출은 이미 확정된 사건이라 history에 포함하지 않는다. 경로별 총 유동지출 T에도
    넣지 않고, 옵션 비율이 확정된 뒤 누적저축 band에서 해당 월에 고정 차감한다.
    """

    source = payload["historical_monthly_variable_spending"]
    # NumPy int64 합산 뒤에는 overflow 여부를 복원할 수 없으므로 배열 생성 전에 막는다.
    if max(source) > _INT64_MAX // payload["horizon_months"]:
        raise ComputeInputError("INVALID_INPUT", "과거 지출 합계가 int64 범위를 초과합니다")
    history = np.asarray(source, dtype=np.int64)
    # 전역 np.random을 쓰면 동시 요청 순서에 따라 같은 seed의 결과가 달라진다.
    rng = np.random.default_rng(payload["random_seed"])
    return rng.choice(history, size=(payload["n_paths"], payload["horizon_months"]))


# 기획서 5-4: np.rint의 ties-to-even 규칙으로 모든 금액을 최근접 원 반올림
def _round_money(value: float) -> int:
    """시뮬레이션의 실수 금액을 최근접 원 단위 Python int로 변환한다."""

    rounded = np.rint(value)
    if not np.isfinite(rounded) or not _INT64_MIN <= rounded <= _INT64_MAX:
        raise ComputeInputError("INVALID_INPUT", "계산 결과 금액이 int64 범위를 초과합니다")
    return int(rounded)


def _option(
    payload: dict,
    totals: np.ndarray,
    spending_ratio: float,
    simulation_coverage: float,
    option_type: str,
    nominal_level: float | None,
) -> dict:
    """한 옵션의 화면·DB 저장용 요약 지표를 조립한다.

    ``spending_ratio``는 현재 소비 중 유지하는 비율이다. 따라서 절감률은
    ``1 - spending_ratio``이며, 가용예산이 넉넉하면 음수가 될 수 있다.
    """

    # ponytail: float 비율은 int64 최댓값에서 1원 오차 가능. 극단값 지원 시 Fraction 사용.
    recommended = max(0, _round_money(payload["current_avg_variable_spending"] * spending_ratio))
    history = np.asarray(payload["historical_monthly_variable_spending"][-24:], dtype=np.int64)
    feasibility = np.mean(history <= recommended)
    warning_threshold = payload.get("policy_snapshot", {}).get("aggressiveWarningPct", 0.10)
    return {
        "optionType": option_type,
        "nominalLevel": None if nominal_level is None else round(float(nominal_level), 3),
        "recommendedMonthlySpending": recommended,
        "requiredReductionRate": round(float(1 - spending_ratio), 4),
        "simulationCoverage": round(float(simulation_coverage), 4),
        "historicalFeasibilityRatio": round(float(feasibility), 4),
        "aggressiveWarning": bool(feasibility <= warning_threshold),
    }


def _bands(
    payload: dict, paths: np.ndarray, spending_ratio: float, option_index: int
) -> list[dict]:
    """옵션 적용 후 월별 누적저축의 p10·p25·p50·p75·p90을 반환한다.

    ``paths``와 ``reduced_paths``는 ``[n_paths, horizon_months]``이고, percentile 결과는
    ``[5, horizon_months]``이다. 모든 분위수를 한 호출에서 계산해 반올림 경로 차이로
    단조성이 깨지는 것을 피한다.
    """

    reduced_paths = paths * spending_ratio
    monthly_savings = payload["current_avg_variable_spending"] - reduced_paths
    cumulative = np.cumsum(monthly_savings, axis=1)
    # 이미 쓴 돈은 첫 달 이후 모든 누적 시점에 영향을 주므로 전체 궤적에서 차감한다.
    cumulative -= payload["current_month_spending_to_date"]
    for expense in payload["remaining_scheduled_expenses"]:
        # N개월차 예정지출은 그 달부터 마지막 달까지의 누적저축을 낮춘다.
        cumulative[:, expense["month_index"] - 1 :] -= expense["amount"]
    percentiles = np.percentile(cumulative, [10, 25, 50, 75, 90], axis=0)
    return [
        {
            "optionIndex": option_index,
            "monthIndex": month_index + 1,
            "metricType": "CUMULATIVE_SAVINGS",
            "p10": _round_money(percentiles[0, month_index]),
            "p25": _round_money(percentiles[1, month_index]),
            "p50": _round_money(percentiles[2, month_index]),
            "p75": _round_money(percentiles[3, month_index]),
            "p90": _round_money(percentiles[4, month_index]),
        }
        for month_index in range(payload["horizon_months"])
    ]


def _simulation(payload: dict, input_snapshot: dict, result_summary: dict) -> dict:
    """Spring이 재현·감사 목적으로 저장할 시뮬레이션 metadata를 조립한다."""

    return {
        "method": "IID_BOOTSTRAP",
        "nPaths": payload["n_paths"],
        "randomSeed": payload["random_seed"],
        "inputHash": canonical_hash(input_snapshot),
        "engineVersion": ENGINE_VERSION,
        "inputSnapshot": input_snapshot,
        "resultSummary": result_summary,
    }


def compute_presets(payload: dict, input_snapshot: dict | None = None) -> dict:
    """요청 순서의 PRESET 수준별 옵션과 누적저축 band를 계산한다.

    경로별 총 유동지출을 ``T``라 하면 각 수준 p의 소비 유지 비율은 ``A / Qp(T)``이고
    절감률은 ``1 - A / Qp(T)``다. 모든 수준이 같은 ``paths``를 공유하므로 옵션마다
    재시뮬레이션하지 않는다.
    """

    paths = _sample_paths(payload)
    # T shape: [n_paths]. 예정지출은 확정 이벤트라 이 분포에 포함하지 않는다.
    totals = paths.sum(axis=1)
    # quantiles shape: [len(preset_levels)]. Q가 0이면 폐형식 비율을 정의할 수 없다.
    quantiles = np.percentile(totals, np.asarray(payload["preset_levels"]) * 100)
    if np.any(quantiles == 0):
        raise ComputeInputError("INSUFFICIENT_HISTORY", "0보다 큰 과거 지출이 필요합니다")
    spending_ratios = payload["available_variable_budget"] / quantiles
    options = []
    bands = []
    summaries = []
    for option_index, (level, quantile, spending_ratio) in enumerate(
        zip(payload["preset_levels"], quantiles, spending_ratios, strict=True)
    ):
        # A/Qp를 경로에 적용한 비교와 동치다. 비율을 곱하지 않아 경계 float 오차를 피한다.
        coverage = 1.0 if payload["available_variable_budget"] == 0 else np.mean(totals <= quantile)
        options.append(
            _option(payload, totals, float(spending_ratio), float(coverage), "PRESET", float(level))
        )
        option_bands = _bands(payload, paths, float(spending_ratio), option_index)
        bands.extend(option_bands)
        summaries.append(
            {
                "nominalLevel": round(float(level), 3),
                "totalSpendingQuantile": _round_money(quantile),
                "finalMedianCumulativeSavings": option_bands[-1]["p50"],
            }
        )
    snapshot = _normalized_snapshot(payload, input_snapshot)
    return {
        "simulation": _simulation(payload, snapshot, {"presetSummaries": summaries}),
        "options": options,
        "percentileBands": bands,
    }


def compute_custom(payload: dict, input_snapshot: dict | None = None) -> dict:
    """사용자가 정한 월 baseline의 CUSTOM 옵션과 band를 계산한다.

    PRESET처럼 목표 coverage에서 지출액을 역산하지 않는다. baseline을 먼저 정하고, 같은
    seed로 재생성한 시나리오가 그 baseline에서 예산을 충족하는 비율을 계산한다.
    """

    current_average = payload["current_avg_variable_spending"]
    baseline = payload["baseline_monthly_spending"]
    if current_average == 0 and baseline != 0:
        raise ComputeInputError("INVALID_INPUT", "현재 평균이 0이면 baseline도 0이어야 합니다")
    spending_ratio = 1.0 if current_average == 0 else baseline / current_average
    paths = _sample_paths(payload)
    totals = paths.sum(axis=1)
    if current_average == 0:
        coverage = np.mean(totals <= payload["available_variable_budget"])
    elif baseline == 0:
        coverage = 1.0
    else:
        # T * baseline / current_average <= A를 정수 경계 비교로 바꿔 float 오차를 피한다.
        coverage_limit = payload["available_variable_budget"] * current_average // baseline
        coverage = np.mean(totals <= coverage_limit)
    return {
        "option": _option(payload, totals, spending_ratio, float(coverage), "CUSTOM", None),
        "percentileBands": _bands(payload, paths, spending_ratio, 0),
    }


if __name__ == "__main__":
    _SAMPLE = {
        "random_seed": 3,
        "n_paths": 8,
        "horizon_months": 1,
        "available_variable_budget": 50,
        "historical_monthly_variable_spending": [100, 100, 100],
        "current_avg_variable_spending": 100,
        "current_month_spending_to_date": 0,
        "remaining_scheduled_expenses": [],
        "preset_levels": [0.70],
        "policy_snapshot": {},
    }
    _result = compute_presets(_SAMPLE)
    assert _result == compute_presets(_SAMPLE)
    assert len(_result["percentileBands"]) == 1
    _band = _result["percentileBands"][0]
    assert _band["p10"] <= _band["p25"] <= _band["p50"] <= _band["p75"] <= _band["p90"]
    _negative = compute_presets(
        {
            **_SAMPLE,
            "available_variable_budget": 61,
            "historical_monthly_variable_spending": [7, 7, 7],
            "current_avg_variable_spending": 7,
        }
    )
    assert _negative["options"][0]["recommendedMonthlySpending"] == 61
    assert _negative["options"][0]["requiredReductionRate"] < 0
    _ordered = compute_presets(
        {
            **_SAMPLE,
            "n_paths": 1_000,
            "historical_monthly_variable_spending": [100, 200, 300],
            "current_avg_variable_spending": 200,
            "preset_levels": [0.7, 0.8, 0.9],
        }
    )
    assert [option["nominalLevel"] for option in _ordered["options"]] == [0.7, 0.8, 0.9]
    assert [option["recommendedMonthlySpending"] for option in _ordered["options"]] == sorted(
        (option["recommendedMonthlySpending"] for option in _ordered["options"]), reverse=True
    )
    _custom = compute_custom(
        {
            **_SAMPLE,
            "available_variable_budget": 63,
            "historical_monthly_variable_spending": [77, 77, 77],
            "current_avg_variable_spending": 11,
            "baseline_monthly_spending": 9,
        }
    )
    assert _custom["option"]["simulationCoverage"] == 1.0
    _zero_average = compute_custom(
        {**_SAMPLE, "current_avg_variable_spending": 0, "baseline_monthly_spending": 0}
    )
    assert _zero_average["option"]["recommendedMonthlySpending"] == 0
    _recent_history = compute_custom(
        {
            **_SAMPLE,
            "historical_monthly_variable_spending": [10] * 12 + [1_000] * 24,
            "current_avg_variable_spending": 1_000,
            "baseline_monthly_spending": 500,
        }
    )
    assert _recent_history["option"]["historicalFeasibilityRatio"] == 0.0
    assert canonical_hash({"policySnapshot": {"note": "빡센"}}) == (
        "7abd49c40334ebfcc7c39f2b6dde5935e81b94c8c7767fd447d679df409389a9"
    )
    try:
        compute_presets({**_SAMPLE, "historical_monthly_variable_spending": [0, 0, 0]})
    except ComputeInputError as error:
        assert error.code == "INSUFFICIENT_HISTORY"
    else:
        raise AssertionError("0 지출 입력을 거부해야 합니다")
    try:
        compute_presets(
            {
                **_SAMPLE,
                "available_variable_budget": _INT64_MAX,
                "historical_monthly_variable_spending": [1, 1, 1],
                "current_avg_variable_spending": 100,
            }
        )
    except ComputeInputError as error:
        assert error.code == "INVALID_INPUT"
    else:
        raise AssertionError("int64를 초과하는 계산 결과를 거부해야 합니다")
    print("planning.py self-check OK")
