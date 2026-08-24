from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ScheduledExpense(ApiModel):
    month_index: int = Field(ge=1)
    amount: int = Field(ge=0)


class SimulateRequest(ApiModel):
    random_seed: int
    n_paths: int = Field(default=10_000, ge=1)
    horizon_months: int = Field(ge=1)
    available_variable_budget: int = Field(ge=0)
    historical_monthly_variable_spending: list[Annotated[int, Field(ge=0)]] = Field(min_length=3)
    current_avg_variable_spending: int = Field(ge=0)
    current_month_spending_to_date: int = Field(default=0, ge=0)
    remaining_scheduled_expenses: list[ScheduledExpense] = Field(default_factory=list)
    preset_levels: list[Annotated[float, Field(gt=0, lt=1)]] = Field(
        default_factory=lambda: [0.70, 0.80, 0.90]
    )
    policy_snapshot: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_scheduled_expenses(self) -> "SimulateRequest":
        if any(
            item.month_index > self.horizon_months
            for item in self.remaining_scheduled_expenses
        ):
            raise ValueError("예정지출의 monthIndex가 horizonMonths를 초과할 수 없습니다")
        return self


class CustomOptionRequest(SimulateRequest):
    baseline_monthly_spending: int = Field(ge=0)


class ComputedOption(ApiModel):
    option_type: Literal["PRESET", "CUSTOM"]
    nominal_level: float | None = None
    recommended_monthly_spending: int = Field(ge=0)
    required_reduction_rate: float = Field(le=1)
    simulation_coverage: float = Field(ge=0, le=1)
    historical_feasibility_ratio: float = Field(ge=0, le=1)
    aggressive_warning: bool

    @model_validator(mode="after")
    def validate_nominal_level(self) -> "ComputedOption":
        if (self.option_type == "PRESET") != (self.nominal_level is not None):
            raise ValueError("PRESET은 nominalLevel이 필요하고 CUSTOM은 null이어야 합니다")
        return self


class PercentileBand(ApiModel):
    option_index: int
    month_index: int = Field(ge=1)
    metric_type: str = "CUMULATIVE_SAVINGS"
    p10: int
    p25: int
    p50: int
    p75: int
    p90: int


class SimulationMeta(ApiModel):
    method: Literal["IID_BOOTSTRAP"]
    n_paths: int
    random_seed: int
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


class ExplanationPlan(ApiModel):
    recommended_monthly_spending: int
    current_avg_variable_spending: int
    remaining_months: int
    target_amount: int | None = None
    current_saved_amount: int | None = None
    simulation_coverage: float | None = None
    aggressive_warning: bool | None = None


class PreviousPlan(ApiModel):
    version_no: int | None = None
    recommended_monthly_spending: int | None = None
    delta_monthly_spending: int | None = None
    trigger_type: str | None = None
    trigger_details: dict[str, Any] | None = None


class ExplanationRequest(ApiModel):
    plan_version_id: int
    max_retry: int = Field(default=3, ge=0, le=5)
    allowed_numbers: list[int]
    plan: ExplanationPlan
    previous_plan: PreviousPlan | None = None


class ExplanationResponse(ApiModel):
    status: Literal["READY", "FALLBACK"]
    text: str
    model: str | None = None
    retry_count: int | None = None
    failed_numbers: list[str] | None = None
    generated_at: datetime | None = None


class ComputeError(ApiModel):
    code: str | None = None
    message: str | None = None
    detail: dict[str, Any] | None = None
