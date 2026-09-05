from math import isfinite
from typing import Annotated, Any, Literal

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel
from pydantic_core import PydanticCustomError

INT64_MIN = -(2**63)
INT64_MAX = 2**63 - 1
Money = Annotated[int, Field(strict=True, ge=INT64_MIN, le=INT64_MAX)]
NonNegativeMoney = Annotated[int, Field(strict=True, ge=0, le=INT64_MAX)]


def strict_integer(value: Any) -> int:
    """JSON 정수만 통과시켜 bool·float·문자열의 묵시적 변환을 막는다."""

    if type(value) is not int:
        raise ValueError("정수여야 합니다")
    return value


FixedPathCount = Annotated[Literal[10_000], BeforeValidator(strict_integer)]


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ScheduledExpense(ApiModel):
    month_index: int = Field(strict=True, ge=1)
    amount: NonNegativeMoney


class PlanningAdjustmentModel(ApiModel):
    """Planning simulate 요청에 허용된 확정 benefit 필드만 받는다."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


YearMonthText = Annotated[str, Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")]
PositiveIdentifier = Annotated[int, Field(strict=True, ge=1, le=INT64_MAX)]
PositiveBenefitMoney = Annotated[int, Field(strict=True, ge=1, le=10**15)]


class PlanningOneTimeFunding(PlanningAdjustmentModel):
    source: Literal["POLICY_BENEFIT"]
    policy_benefit_id: PositiveIdentifier
    policy_version_id: PositiveIdentifier
    adjustment_type: Literal["ONE_TIME_FUNDING"]
    amount_won: PositiveBenefitMoney
    start_year_month: YearMonthText


class PlanningMonthlyExpenseReduction(PlanningAdjustmentModel):
    source: Literal["POLICY_BENEFIT"]
    policy_benefit_id: PositiveIdentifier
    policy_version_id: PositiveIdentifier
    adjustment_type: Literal["MONTHLY_EXPENSE_REDUCTION"]
    amount_won: PositiveBenefitMoney
    start_year_month: YearMonthText
    end_year_month: YearMonthText

    @model_validator(mode="after")
    def validate_month_order(self) -> "PlanningMonthlyExpenseReduction":
        """고정 폭 YYYY-MM 문자열 순서로 inclusive 지원 기간을 검증한다."""

        if self.end_year_month < self.start_year_month:
            raise ValueError("endYearMonth는 startYearMonth보다 빠를 수 없습니다")
        return self


PlanningFutureCashflowAdjustment = Annotated[
    PlanningOneTimeFunding | PlanningMonthlyExpenseReduction,
    Field(discriminator="adjustment_type"),
]


class SimulateRequest(ApiModel):
    random_seed: int = Field(strict=True, ge=0, le=INT64_MAX)
    n_paths: FixedPathCount = 10_000
    horizon_months: int = Field(strict=True, ge=1, le=120)
    period_ratios: list[Annotated[float, Field(gt=0, le=1)]]
    available_variable_budget: NonNegativeMoney
    historical_monthly_variable_spending: list[NonNegativeMoney] = Field(min_length=3)
    current_avg_variable_spending: NonNegativeMoney
    remaining_scheduled_expenses: list[ScheduledExpense] = Field(default_factory=list)
    simulation_start_year_month: YearMonthText | None = None
    future_cashflow_adjustments: list[PlanningFutureCashflowAdjustment] = Field(
        default_factory=list
    )
    preset_levels: list[Annotated[float, Field(gt=0, lt=1)]] = Field(
        min_length=1, default_factory=lambda: [0.70, 0.80, 0.90]
    )
    policy_snapshot: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_request(self) -> "SimulateRequest":
        """요청 필드 사이의 기간 관계와 정책 비율을 검증해 자기 자신을 반환한다."""

        if len(self.period_ratios) != self.horizon_months:
            raise ValueError("periodRatios 길이는 horizonMonths와 같아야 합니다")
        if any(ratio != 1.0 for ratio in self.period_ratios[1:-1]):
            raise ValueError("periodRatios의 중간 달은 1이어야 합니다")
        if any(
            item.month_index > self.horizon_months for item in self.remaining_scheduled_expenses
        ):
            raise PydanticCustomError(
                "invalid_horizon",
                "예정지출의 monthIndex가 horizonMonths를 초과할 수 없습니다",
            )
        if self.future_cashflow_adjustments and self.simulation_start_year_month is None:
            raise ValueError("정책 현금흐름 조정에는 simulationStartYearMonth가 필요합니다")
        if "aggressiveWarningPct" in self.policy_snapshot:
            warning_pct = self.policy_snapshot["aggressiveWarningPct"]
            if (
                isinstance(warning_pct, bool)
                or not isinstance(warning_pct, (int, float))
                or isinstance(warning_pct, int)
                and not 0 <= warning_pct <= 1
                or isinstance(warning_pct, float)
                and (not isfinite(warning_pct) or not 0 <= warning_pct <= 1)
            ):
                raise ValueError("aggressiveWarningPct는 0 이상 1 이하의 유한한 숫자여야 합니다")
        return self


class CustomOptionRequest(SimulateRequest):
    baseline_monthly_spending: NonNegativeMoney


class ComputedOption(ApiModel):
    option_type: Literal["PRESET", "CUSTOM"]
    nominal_level: float | None = None
    recommended_monthly_spending: NonNegativeMoney
    required_reduction_rate: float = Field(ge=1 - INT64_MAX, le=1)
    simulation_coverage: float = Field(ge=0, le=1)
    historical_feasibility_ratio: float = Field(ge=0, le=1)
    aggressive_warning: bool
    target_coverage_met: bool

    @model_validator(mode="after")
    def validate_nominal_level(self) -> "ComputedOption":
        """옵션 종류에 맞는 nominalLevel 유무를 검증해 자기 자신을 반환한다."""

        if (self.option_type == "PRESET") != (self.nominal_level is not None):
            raise ValueError("PRESET은 nominalLevel이 필요하고 CUSTOM은 null이어야 합니다")
        return self


class PercentileBand(ApiModel):
    option_index: int = Field(strict=True)
    month_index: int = Field(strict=True, ge=1)
    metric_type: str = "CUMULATIVE_SAVINGS"
    p10: Money
    p25: Money
    p50: Money
    p75: Money
    p90: Money

    @model_validator(mode="after")
    def validate_monotonicity(self) -> "PercentileBand":
        """분위수 값의 단조 증가를 검증해 자기 자신을 반환한다."""

        if not self.p10 <= self.p25 <= self.p50 <= self.p75 <= self.p90:
            raise ValueError("분위수 밴드는 p10부터 p90까지 단조 증가해야 합니다")
        return self


class SimulationMeta(ApiModel):
    method: Literal["IID_BOOTSTRAP"]
    n_paths: int = Field(strict=True)
    random_seed: int = Field(strict=True)
    input_hash: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
    engine_version: str
    input_snapshot: dict[str, Any] = Field(min_length=1)
    result_summary: dict[str, Any] = Field(min_length=1)


class SimulateResponse(ApiModel):
    simulation: SimulationMeta
    options: list[ComputedOption]
    percentile_bands: list[PercentileBand]


class CustomOptionResponse(ApiModel):
    option: ComputedOption
    percentile_bands: list[PercentileBand]


class ScenarioApiModel(ApiModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class ScenarioScheduledExpense(ScheduledExpense):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class ScenarioPolicySnapshot(ScenarioApiModel):
    aggressiveWarningPct: Annotated[
        float,
        Field(strict=True, ge=0, le=1, allow_inf_nan=False),
    ] = 0.10


class ScenarioPlanInput(SimulateRequest):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )
    remaining_scheduled_expenses: list[ScenarioScheduledExpense] = Field(default_factory=list)
    policy_snapshot: ScenarioPolicySnapshot = Field(default_factory=ScenarioPolicySnapshot)


class SelectedPresetOption(ScenarioApiModel):
    option_type: Literal["PRESET"]
    nominal_level: float = Field(gt=0, lt=1)


class SelectedCustomOption(ScenarioApiModel):
    option_type: Literal["CUSTOM"]
    baseline_monthly_spending: NonNegativeMoney


ScenarioSelectedOption = Annotated[
    SelectedPresetOption | SelectedCustomOption,
    Field(discriminator="option_type"),
]


class OneTimeFundingAdjustment(ScenarioApiModel):
    type: Literal["ONE_TIME_FUNDING"]
    amount_won: int = Field(strict=True, ge=1, le=10**15)
    start_month_index: int = Field(strict=True, ge=1, le=120)
    source_version: str = Field(min_length=1, max_length=100)


class MonthlyExpenseReductionAdjustment(ScenarioApiModel):
    type: Literal["MONTHLY_EXPENSE_REDUCTION"]
    amount_won: int = Field(strict=True, ge=1, le=10**15)
    start_month_index: int = Field(strict=True, ge=1, le=120)
    end_month_index: int = Field(strict=True, ge=1, le=120)
    source_version: str = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_month_order(self) -> "MonthlyExpenseReductionAdjustment":
        """월 지원 종료가 시작보다 빠른 요청을 거부한다."""

        if self.end_month_index < self.start_month_index:
            raise ValueError("endMonthIndex는 startMonthIndex보다 빠를 수 없습니다")
        return self


FutureCashflowAdjustment = Annotated[
    OneTimeFundingAdjustment | MonthlyExpenseReductionAdjustment,
    Field(discriminator="type"),
]


class PolicyScenarioRequest(ScenarioApiModel):
    plan_input: ScenarioPlanInput
    selected_option: ScenarioSelectedOption
    adjustment: FutureCashflowAdjustment


class PolicyScenarioResponse(ScenarioApiModel):
    current_plan_summary: ComputedOption
    assumed_plan_summary: ComputedOption
    current_bands: list[PercentileBand]
    assumed_bands: list[PercentileBand]


class ComputeError(ApiModel):
    code: (
        Literal[
            "INSUFFICIENT_HISTORY",
            "INVALID_HORIZON",
            "INVALID_INPUT",
            "LLM_UNAVAILABLE",
            "SERIALIZATION_ERROR",
        ]
        | None
    ) = None
    message: str | None = None
    detail: dict[str, Any] | None = None
