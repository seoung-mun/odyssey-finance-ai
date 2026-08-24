import hashlib
import json

import numpy as np

ENGINE_VERSION = "0.1.0"
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
    def __init__(self, code: str, message: str, detail: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.detail = detail


def canonical_hash(payload: dict) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


def _camel_case(value):
    if isinstance(value, dict):
        return {
            _camel_case_key(key): _camel_case(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_camel_case(item) for item in value]
    return value


def _camel_case_key(key: str) -> str:
    head, *tail = key.split("_")
    return head + "".join(part.capitalize() for part in tail)


def _normalized_snapshot(payload: dict, input_snapshot: dict | None) -> dict:
    if input_snapshot is not None:
        return input_snapshot
    return _camel_case({key: payload[key] for key in _SNAPSHOT_KEYS if key in payload})


def _sample_paths(payload: dict) -> np.ndarray:
    source = payload["historical_monthly_variable_spending"]
    if max(source) > _INT64_MAX // payload["horizon_months"]:
        raise ComputeInputError("INVALID_INPUT", "과거 지출 합계가 int64 범위를 초과합니다")
    history = np.asarray(source, dtype=np.int64)
    rng = np.random.default_rng(payload["random_seed"])
    return rng.choice(history, size=(payload["n_paths"], payload["horizon_months"]))


# 기획서 5-4: np.rint의 ties-to-even 규칙으로 모든 금액을 최근접 원 반올림
def _round_money(value: float) -> int:
    return int(np.rint(value))


def _option(
    payload: dict,
    totals: np.ndarray,
    spending_ratio: float,
    simulation_coverage: float,
    option_type: str,
    nominal_level: float | None,
) -> dict:
    recommended = max(0, _round_money(payload["current_avg_variable_spending"] * spending_ratio))
    history = np.asarray(payload["historical_monthly_variable_spending"], dtype=np.int64)
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
    reduced_paths = paths * spending_ratio
    monthly_savings = payload["current_avg_variable_spending"] - reduced_paths
    cumulative = np.cumsum(monthly_savings, axis=1)
    cumulative -= payload["current_month_spending_to_date"]
    for expense in payload["remaining_scheduled_expenses"]:
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
    paths = _sample_paths(payload)
    totals = paths.sum(axis=1)
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
        coverage_limit = (
            payload["available_variable_budget"] * current_average // baseline
        )
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
    try:
        compute_presets({**_SAMPLE, "historical_monthly_variable_spending": [0, 0, 0]})
    except ComputeInputError as error:
        assert error.code == "INSUFFICIENT_HISTORY"
    else:
        raise AssertionError("0 지출 입력을 거부해야 합니다")
    print("planning.py self-check OK")
