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
from fractions import Fraction
from math import lcm

import numpy as np

ENGINE_VERSION = "1.2.0"
_INT64_MIN = -(2**63)
_INT64_MAX = 2**63 - 1
_SNAPSHOT_KEYS = (
    "random_seed",
    "n_paths",
    "horizon_months",
    "period_ratios",
    "available_variable_budget",
    "historical_monthly_variable_spending",
    "current_avg_variable_spending",
    "spending_floor",
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
        """계산 오류 코드·메시지·선택 상세정보를 예외에 저장한다."""

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


def _calendar_ratio(value: float) -> Fraction:
    """달력 일수로 만든 비율을 분모 31 이하의 정확한 분수로 복원한다."""

    ratio = Fraction(value).limit_denominator(31)
    if abs(float(ratio) - value) > 1e-12:
        raise ComputeInputError("INVALID_INPUT", "periodRatios는 달력 일수 비율이어야 합니다")
    return ratio


def _sample_paths(payload: dict) -> tuple[np.ndarray, int]:
    """IID 표본과 기간 비율을 공통 분모 정수로 적용한 경로를 반환한다.

    예정지출은 이미 확정된 사건이라 history에 포함하지 않는다. 경로별 총 유동지출 T에도
    넣지 않고, 옵션 비율이 확정된 뒤 누적저축 band에서 해당 월에 고정 차감한다.
    """

    source = payload["historical_monthly_variable_spending"]
    ratios = [_calendar_ratio(value) for value in payload["period_ratios"]]
    if max(source) * sum(ratios) > _INT64_MAX:
        raise ComputeInputError("INVALID_INPUT", "과거 지출 합계가 int64 범위를 초과합니다")
    history = np.asarray(source, dtype=np.int64)
    rng = np.random.default_rng(payload["random_seed"])
    sampled = rng.choice(history, size=(payload["n_paths"], payload["horizon_months"]))
    scale = lcm(*(ratio.denominator for ratio in ratios))
    weights = np.asarray(
        [ratio.numerator * (scale // ratio.denominator) for ratio in ratios],
        dtype=np.int64,
    )
    if max(source) * sum(int(weight) for weight in weights) <= _INT64_MAX:
        weighted = sampled * weights
    else:
        weighted = sampled.astype(object) * weights.astype(object)
    return weighted, scale


def _round_fraction(numerator: int, denominator: int = 1) -> int:
    """정확한 분수를 ties-to-even 규칙으로 원 단위 반올림한다."""

    quotient, remainder = divmod(abs(int(numerator)), int(denominator))
    if remainder * 2 > denominator or remainder * 2 == denominator and quotient % 2:
        quotient += 1
    rounded = -quotient if numerator < 0 else quotient
    if not _INT64_MIN <= rounded <= _INT64_MAX:
        raise ComputeInputError("INVALID_INPUT", "계산 결과 금액이 int64 범위를 초과합니다")
    return rounded


def _linear_quantile(sorted_values: np.ndarray, level: float) -> tuple[int, int]:
    """정렬된 정수 표본의 선형 분위수를 분자와 분모로 반환한다."""

    position = Fraction(str(level)) * (len(sorted_values) - 1)
    lower = position.numerator // position.denominator
    remainder = position.numerator % position.denominator
    numerator = int(sorted_values[lower]) * (position.denominator - remainder)
    if remainder:
        numerator += int(sorted_values[lower + 1]) * remainder
    return numerator, position.denominator


def _quantile_money(sorted_values: np.ndarray, level: float, scale: int) -> int:
    """공통 분모 정수 표본의 분위수를 원 단위 정수로 반환한다."""

    numerator, denominator = _linear_quantile(sorted_values, level)
    return _round_fraction(numerator, denominator * scale)


def _coverage(totals: np.ndarray, available: int, numerator: int, denominator: int) -> float:
    """정수 교차곱의 나눗셈 경계로 예산을 충족하는 경로 비율을 반환한다."""

    if numerator == 0:
        return 1.0
    threshold = available * denominator // numerator
    return float(np.mean(totals <= threshold))


def _resolve_spending_floor(payload: dict) -> tuple[dict, float]:
    """요청 mode를 확정 하한과 최대 절감률로 변환한다."""

    floor = payload["spending_floor"]
    mode = floor["mode"]
    history_months = None
    if mode == "OFF":
        requested = 0
    elif mode == "CUSTOM":
        requested = floor["custom_monthly_amount"]
    else:
        history = payload["historical_monthly_variable_spending"][-12:]
        if len(history) < 6:
            raise ComputeInputError("INSUFFICIENT_HISTORY", "AUTO는 완전월 이력 6개월이 필요합니다")
        sorted_history = np.sort(np.asarray(history, dtype=np.int64))
        numerator, denominator = _linear_quantile(sorted_history, 0.20)
        requested = _round_fraction(numerator, denominator)
        history_months = len(history)
    current_average = payload["current_avg_variable_spending"]
    effective = min(requested, current_average)
    effective_max_reduction_rate = (
        0.0 if current_average == 0 else round(1 - effective / current_average, 4)
    )
    return (
        {
            "mode": mode,
            "requestedMonthlyAmount": requested,
            "effectiveMonthlyAmount": effective,
            "autoHistoryMonths": history_months,
        },
        effective_max_reduction_rate,
    )


def _option(
    payload: dict,
    recommended: int,
    spending_ratio: float,
    simulation_coverage: float,
    option_type: str,
    nominal_level: float | None,
    effective_max_reduction_rate: float,
    floor_applied: bool,
    target_coverage_met: bool,
) -> dict:
    """한 옵션의 화면·DB 저장용 요약 지표를 조립한다.

    ``spending_ratio``는 현재 소비 중 유지하는 비율이다. 따라서 절감률은
    ``1 - spending_ratio``이며, 가용예산이 넉넉하면 음수가 될 수 있다.
    """

    history = np.asarray(payload["historical_monthly_variable_spending"][-24:], dtype=np.int64)
    feasibility = np.mean(history <= recommended)
    warning_threshold = payload.get("policy_snapshot", {}).get("aggressiveWarningPct", 0.10)
    reduction_rate = (
        0.0 if payload["current_avg_variable_spending"] == recommended == 0 else 1 - spending_ratio
    )
    return {
        "optionType": option_type,
        "nominalLevel": None if nominal_level is None else round(float(nominal_level), 3),
        "recommendedMonthlySpending": recommended,
        "requiredReductionRate": round(float(reduction_rate), 4),
        "simulationCoverage": round(float(simulation_coverage), 4),
        "historicalFeasibilityRatio": round(float(feasibility), 4),
        "aggressiveWarning": bool(feasibility <= warning_threshold),
        "effectiveMaxReductionRate": effective_max_reduction_rate,
        "floorApplied": floor_applied,
        "targetCoverageMet": target_coverage_met,
    }


def _bands(
    payload: dict,
    weighted_paths: np.ndarray,
    scale: int,
    recommended: int,
    option_index: int,
) -> list[dict]:
    """옵션 적용 후 월별 누적저축의 p10·p25·p50·p75·p90을 반환한다.

    기간 비율과 소비 비율은 공통 분모 정수로 누적하고 최종 분위수에서만 원 단위로
    반올림한다.
    """

    current_average = payload["current_avg_variable_spending"]
    if current_average == 0:
        denominator = scale
        expense_total = sum(
            item["amount"] * denominator for item in payload["remaining_scheduled_expenses"]
        )
        monthly_savings = np.zeros_like(
            weighted_paths, dtype=object if expense_total > _INT64_MAX else np.int64
        )
    else:
        denominator = scale * current_average
        ratios = [_calendar_ratio(value) for value in payload["period_ratios"]]
        weights = [ratio.numerator * (scale // ratio.denominator) for ratio in ratios]
        baseline = [current_average * current_average * weight for weight in weights]
        source = payload["historical_monthly_variable_spending"]
        worst = sum(
            max(
                abs(base - min(source) * weight * recommended),
                abs(base - max(source) * weight * recommended),
            )
            for base, weight in zip(baseline, weights, strict=True)
        ) + sum(item["amount"] * denominator for item in payload["remaining_scheduled_expenses"])
        largest_operand = max(max(baseline), max(source) * max(weights) * recommended)
        dtype = object if max(worst, largest_operand) > _INT64_MAX else np.int64
        monthly_savings = (
            np.asarray(baseline, dtype=dtype) - weighted_paths.astype(dtype) * recommended
        )
    cumulative = np.cumsum(monthly_savings, axis=1)
    for expense in payload["remaining_scheduled_expenses"]:
        cumulative[:, expense["month_index"] - 1 :] -= expense["amount"] * denominator
    sorted_cumulative = np.sort(cumulative, axis=0)
    levels = (0.10, 0.25, 0.50, 0.75, 0.90)
    return [
        {
            "optionIndex": option_index,
            "monthIndex": month_index + 1,
            "metricType": "CUMULATIVE_SAVINGS",
            **{
                f"p{int(level * 100)}": _quantile_money(
                    sorted_cumulative[:, month_index], level, denominator
                )
                for level in levels
            },
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

    resolved_floor, effective_max_reduction_rate = _resolve_spending_floor(payload)
    effective_floor = resolved_floor["effectiveMonthlyAmount"]
    weighted_paths, scale = _sample_paths(payload)
    totals = weighted_paths.sum(axis=1)
    sorted_totals = np.sort(totals)
    options = []
    bands = []
    summaries = []
    for option_index, level in enumerate(payload["preset_levels"]):
        quantile_numerator, quantile_denominator = _linear_quantile(sorted_totals, level)
        if quantile_numerator == 0:
            raise ComputeInputError("INSUFFICIENT_HISTORY", "0보다 큰 과거 지출이 필요합니다")
        original_recommendation = _round_fraction(
            payload["current_avg_variable_spending"]
            * payload["available_variable_budget"]
            * scale
            * quantile_denominator,
            quantile_numerator,
        )
        recommended = max(original_recommendation, effective_floor)
        spending_ratio = (
            0.0
            if payload["current_avg_variable_spending"] == 0
            else recommended / payload["current_avg_variable_spending"]
        )
        coverage = _coverage(
            totals,
            payload["available_variable_budget"],
            recommended,
            scale * payload["current_avg_variable_spending"],
        )
        options.append(
            _option(
                payload,
                recommended,
                float(spending_ratio),
                coverage,
                "PRESET",
                float(level),
                effective_max_reduction_rate,
                recommended > original_recommendation,
                coverage >= level,
            )
        )
        option_bands = _bands(payload, weighted_paths, scale, recommended, option_index)
        bands.extend(option_bands)
        summaries.append(
            {
                "nominalLevel": round(float(level), 3),
                "totalSpendingQuantile": _round_fraction(
                    quantile_numerator, quantile_denominator * scale
                ),
                "finalMedianCumulativeSavings": option_bands[-1]["p50"],
            }
        )
    snapshot = _normalized_snapshot(payload, input_snapshot)
    return {
        "simulation": _simulation(payload, snapshot, {"presetSummaries": summaries}),
        "resolvedSpendingFloor": resolved_floor,
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
    resolved_floor, effective_max_reduction_rate = _resolve_spending_floor(payload)
    if baseline < resolved_floor["effectiveMonthlyAmount"]:
        raise ComputeInputError("INVALID_INPUT", "baseline은 확정 소비 하한 이상이어야 합니다")
    if current_average == 0 and baseline != 0:
        raise ComputeInputError("INVALID_INPUT", "현재 평균이 0이면 baseline도 0이어야 합니다")
    spending_ratio = 0.0 if current_average == 0 else baseline / current_average
    weighted_paths, scale = _sample_paths(payload)
    totals = weighted_paths.sum(axis=1)
    coverage = _coverage(
        totals,
        payload["available_variable_budget"],
        baseline,
        scale * current_average,
    )
    return {
        "resolvedSpendingFloor": resolved_floor,
        "option": _option(
            payload,
            baseline,
            spending_ratio,
            coverage,
            "CUSTOM",
            None,
            effective_max_reduction_rate,
            False,
            True,
        ),
        "percentileBands": _bands(payload, weighted_paths, scale, baseline, 0),
    }


if __name__ == "__main__":
    _SAMPLE = {
        "random_seed": 3,
        "n_paths": 8,
        "horizon_months": 1,
        "period_ratios": [1.0],
        "available_variable_budget": 50,
        "historical_monthly_variable_spending": [100, 100, 100],
        "current_avg_variable_spending": 100,
        "spending_floor": {"mode": "OFF", "custom_monthly_amount": None},
        "remaining_scheduled_expenses": [],
        "preset_levels": [0.70],
        "policy_snapshot": {},
    }
    _result = compute_presets(_SAMPLE)
    assert _result == compute_presets(_SAMPLE)
    assert len(_result["percentileBands"]) == 1
    assert _result["resolvedSpendingFloor"]["effectiveMonthlyAmount"] == 0
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
