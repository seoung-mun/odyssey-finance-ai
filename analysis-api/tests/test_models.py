import unittest

from pydantic import ValidationError

from app.models import (
    ComputedOption,
    CustomOptionRequest,
    ExplanationRequest,
    PercentileBand,
    SimulateRequest,
    SimulationMeta,
)

VALID_REQUEST = {
    "randomSeed": 7,
    "horizonMonths": 2,
    "periodRatios": [1.0, 1.0],
    "availableVariableBudget": 100,
    "historicalMonthlyVariableSpending": [80, 100, 120],
    "currentAvgVariableSpending": 100,
}


class SimulateRequestTest(unittest.TestCase):
    def test_defaults_and_aliases_follow_internal_contract(self):
        request = SimulateRequest.model_validate(
            {
                "randomSeed": 7,
                "horizonMonths": 2,
                "periodRatios": [1.0, 1.0],
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
                    "periodRatios": [1.0, 1.0],
                    "availableVariableBudget": 100,
                    "historicalMonthlyVariableSpending": [80, 100, 120],
                    "currentAvgVariableSpending": 100,
                    "remainingScheduledExpenses": [{"monthIndex": 3, "amount": 10}],
                }
            )

    def test_random_seed_is_unsigned_int64(self):
        self.assertEqual(
            SimulateRequest.model_validate({**VALID_REQUEST, "randomSeed": 2**63 - 1}).random_seed,
            2**63 - 1,
        )
        for value in (-1, 2**63):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                SimulateRequest.model_validate({**VALID_REQUEST, "randomSeed": value})

    def test_nonnegative_money_inputs_reject_values_above_int64(self):
        cases = (
            {"availableVariableBudget": 2**63},
            {"historicalMonthlyVariableSpending": [80, 100, 2**63]},
            {"currentAvgVariableSpending": 2**63},
            {"remainingScheduledExpenses": [{"monthIndex": 1, "amount": 2**63}]},
        )
        for update in cases:
            with self.subTest(update=update), self.assertRaises(ValidationError):
                SimulateRequest.model_validate({**VALID_REQUEST, **update})

        with self.assertRaises(ValidationError):
            CustomOptionRequest.model_validate({**VALID_REQUEST, "baselineMonthlySpending": 2**63})

    def test_policy_warning_percentage_is_finite_number_in_unit_interval(self):
        for value in (None, True, "0.1", float("nan"), float("inf"), -0.1, 1.1, 10**1000):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                SimulateRequest.model_validate(
                    {**VALID_REQUEST, "policySnapshot": {"aggressiveWarningPct": value}}
                )

    def test_policy_snapshot_preserves_unknown_keys(self):
        snapshot = {
            "aggressiveWarningPct": 0.25,
            "futurePolicy": {"enabled": True},
        }

        request = SimulateRequest.model_validate({**VALID_REQUEST, "policySnapshot": snapshot})

        self.assertEqual(request.policy_snapshot, snapshot)

    def test_only_fixed_path_count_is_accepted(self):
        self.assertEqual(
            SimulateRequest.model_validate({**VALID_REQUEST, "nPaths": 10_000}).n_paths,
            10_000,
        )
        for value in (1, 9_999, 10_001):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                SimulateRequest.model_validate({**VALID_REQUEST, "nPaths": value})

    def test_horizon_is_limited_to_120_months(self):
        request = SimulateRequest.model_validate(
            {**VALID_REQUEST, "horizonMonths": 120, "periodRatios": [1.0] * 120}
        )
        self.assertEqual(request.horizon_months, 120)

        with self.assertRaises(ValidationError):
            SimulateRequest.model_validate(
                {**VALID_REQUEST, "horizonMonths": 121, "periodRatios": [1.0] * 121}
            )

    def test_period_ratios_match_horizon_and_only_edges_may_be_partial(self):
        invalid = (
            [1.0],
            [0.0, 1.0],
            [1.0, 1.1],
            [0.5, 0.5, 0.5],
        )
        for ratios in invalid:
            with self.subTest(ratios=ratios), self.assertRaises(ValidationError):
                SimulateRequest.model_validate(
                    {**VALID_REQUEST, "horizonMonths": 3, "periodRatios": ratios}
                )

        request = SimulateRequest.model_validate(
            {**VALID_REQUEST, "horizonMonths": 3, "periodRatios": [0.5, 1.0, 0.25]}
        )
        self.assertEqual(request.period_ratios, [0.5, 1.0, 0.25])

    def test_preset_levels_must_not_be_empty(self):
        with self.assertRaises(ValidationError):
            SimulateRequest.model_validate({**VALID_REQUEST, "presetLevels": []})


class ContractModelTest(unittest.TestCase):
    def test_reduction_rate_matches_full_int64_derived_range(self):
        option = ComputedOption.model_validate(
            {
                "optionType": "CUSTOM",
                "recommendedMonthlySpending": 2**63 - 1,
                "requiredReductionRate": 1 - (2**63 - 1),
                "simulationCoverage": 1,
                "historicalFeasibilityRatio": 1,
                "aggressiveWarning": False,
            }
        )

        self.assertLess(option.required_reduction_rate, 0)
        with self.assertRaises(ValidationError):
            ComputedOption.model_validate(
                {
                    "optionType": "CUSTOM",
                    "recommendedMonthlySpending": 2**63 - 1,
                    "requiredReductionRate": -(10**20),
                    "simulationCoverage": 1,
                    "historicalFeasibilityRatio": 1,
                    "aggressiveWarning": False,
                }
            )

    def test_percentile_band_rejects_non_monotonic_values(self):
        with self.assertRaises(ValidationError):
            PercentileBand.model_validate(
                {
                    "optionIndex": 0,
                    "monthIndex": 1,
                    "p10": 0,
                    "p25": 2,
                    "p50": 1,
                    "p75": 3,
                    "p90": 4,
                }
            )

    def test_signed_money_inputs_reject_values_outside_int64(self):
        payload = {
            "planVersionId": 1,
            "allowedNumbers": [1],
            "plan": {
                "recommendedMonthlySpending": 100,
                "currentAvgVariableSpending": 100,
                "remainingMonths": 1,
            },
        }
        for value in (-(2**63) - 1, 2**63):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                ExplanationRequest.model_validate(
                    {
                        **payload,
                        "plan": {**payload["plan"], "recommendedMonthlySpending": value},
                    }
                )

    def test_option_nominal_level_matches_option_type(self):
        base = {
            "recommendedMonthlySpending": 100,
            "requiredReductionRate": 0.1,
            "simulationCoverage": 0.8,
            "historicalFeasibilityRatio": 0.5,
            "aggressiveWarning": False,
        }
        with self.assertRaises(ValidationError):
            ComputedOption.model_validate({**base, "optionType": "PRESET"})
        with self.assertRaises(ValidationError):
            ComputedOption.model_validate({**base, "optionType": "CUSTOM", "nominalLevel": 0.8})

    def test_simulation_meta_rejects_empty_snapshots(self):
        base = {
            "method": "IID_BOOTSTRAP",
            "nPaths": 10_000,
            "randomSeed": 7,
            "inputHash": "a" * 64,
            "engineVersion": "1",
            "inputSnapshot": {},
            "resultSummary": {"median": 1},
        }
        with self.assertRaises(ValidationError):
            SimulationMeta.model_validate(base)
        with self.assertRaises(ValidationError):
            SimulationMeta.model_validate({**base, "inputSnapshot": {"x": 1}, "resultSummary": {}})

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
