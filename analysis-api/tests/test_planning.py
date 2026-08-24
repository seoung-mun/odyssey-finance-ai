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

INPUT_SNAPSHOT = {
    "randomSeed": 3,
    "nPaths": 8,
    "horizonMonths": 2,
    "availableVariableBudget": 100,
    "historicalMonthlyVariableSpending": [100, 100, 100],
    "currentAvgVariableSpending": 100,
    "currentMonthSpendingToDate": 5,
    "remainingScheduledExpenses": [{"monthIndex": 2, "amount": 10}],
    "presetLevels": [0.70],
    "policySnapshot": {"aggressiveWarningPct": 0.10},
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
        result = compute_custom(
            {**BASE, "available_variable_budget": 160, "baseline_monthly_spending": 80}
        )

        self.assertIsNone(result["option"]["nominalLevel"])
        self.assertEqual(result["option"]["requiredReductionRate"], 0.2)
        self.assertEqual(result["option"]["simulationCoverage"], 1.0)

    def test_zero_history_is_expected_compute_error(self):
        with self.assertRaises(ComputeInputError) as caught:
            compute_presets({**BASE, "historical_monthly_variable_spending": [0, 0, 0]})

        self.assertEqual(caught.exception.code, "INSUFFICIENT_HISTORY")

    def test_zero_selected_quantile_is_expected_compute_error(self):
        with self.assertRaises(ComputeInputError) as caught:
            compute_presets(
                {
                    **BASE,
                    "random_seed": 0,
                    "historical_monthly_variable_spending": [0, 100, 100],
                    "preset_levels": [0.01],
                }
            )

        self.assertEqual(caught.exception.code, "INSUFFICIENT_HISTORY")

    def test_explicit_camel_snapshot_is_the_only_hash_input(self):
        result = compute_presets({**BASE, "internal_bookkeeping": "ignored"}, INPUT_SNAPSHOT)

        self.assertEqual(result["simulation"]["inputSnapshot"], INPUT_SNAPSHOT)
        self.assertEqual(result["simulation"]["inputHash"], canonical_hash(INPUT_SNAPSHOT))
        self.assertIsInstance(result["options"][0]["recommendedMonthlySpending"], int)
        self.assertIsInstance(result["percentileBands"][0]["p50"], int)

    def test_preset_keeps_exact_recommended_spending_at_non_binary_ratio(self):
        result = compute_presets(
            {
                **BASE,
                "horizon_months": 1,
                "available_variable_budget": 100,
                "historical_monthly_variable_spending": [300, 300, 300],
                "current_avg_variable_spending": 300,
                "current_month_spending_to_date": 0,
                "remaining_scheduled_expenses": [],
            }
        )

        self.assertEqual(result["options"][0]["recommendedMonthlySpending"], 100)

    def test_preset_counts_exact_budget_boundary_at_non_binary_ratio(self):
        result = compute_presets(
            {
                **BASE,
                "horizon_months": 1,
                "available_variable_budget": 1,
                "historical_monthly_variable_spending": [9, 9, 9],
                "current_avg_variable_spending": 9,
                "current_month_spending_to_date": 0,
                "remaining_scheduled_expenses": [],
            }
        )

        self.assertEqual(result["options"][0]["simulationCoverage"], 1.0)

    def test_negative_preset_reduction_rounds_recommendation_to_nearest_won(self):
        result = compute_presets(
            {
                **BASE,
                "horizon_months": 1,
                "available_variable_budget": 61,
                "historical_monthly_variable_spending": [7, 7, 7],
                "current_avg_variable_spending": 7,
                "current_month_spending_to_date": 0,
                "remaining_scheduled_expenses": [],
            }
        )

        option = result["options"][0]
        self.assertEqual(option["recommendedMonthlySpending"], 61)
        self.assertLess(option["requiredReductionRate"], 0)

    def test_custom_coverage_counts_exact_integer_boundary(self):
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "available_variable_budget": 63,
                "historical_monthly_variable_spending": [77, 77, 77],
                "current_avg_variable_spending": 11,
                "current_month_spending_to_date": 0,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": 9,
            }
        )

        self.assertEqual(result["option"]["simulationCoverage"], 1.0)

    def test_custom_band_rounds_cumulative_savings_to_nearest_won(self):
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "available_variable_budget": 10_000,
                "historical_monthly_variable_spending": [85, 85, 85],
                "current_avg_variable_spending": 17,
                "current_month_spending_to_date": 0,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": 3,
            }
        )

        self.assertEqual(result["percentileBands"][0]["p50"], 2)

    def test_zero_current_average_and_baseline_are_computable(self):
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "available_variable_budget": 100,
                "historical_monthly_variable_spending": [100, 100, 100],
                "current_avg_variable_spending": 0,
                "current_month_spending_to_date": 0,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": 0,
            }
        )

        self.assertEqual(result["option"]["recommendedMonthlySpending"], 0)
        self.assertEqual(result["option"]["simulationCoverage"], 1.0)

    def test_preset_options_follow_requested_level_order(self):
        result = compute_presets(
            {
                **BASE,
                "n_paths": 1_000,
                "horizon_months": 1,
                "available_variable_budget": 100,
                "historical_monthly_variable_spending": [100, 200, 300],
                "current_avg_variable_spending": 200,
                "current_month_spending_to_date": 0,
                "remaining_scheduled_expenses": [],
                "preset_levels": [0.7, 0.8, 0.9],
            }
        )

        options = result["options"]
        self.assertEqual([option["nominalLevel"] for option in options], [0.7, 0.8, 0.9])
        self.assertGreaterEqual(
            options[0]["recommendedMonthlySpending"],
            options[1]["recommendedMonthlySpending"],
        )
        self.assertGreaterEqual(
            options[1]["recommendedMonthlySpending"],
            options[2]["recommendedMonthlySpending"],
        )

    def test_history_horizon_sum_overflow_is_expected_compute_error(self):
        with self.assertRaises(ComputeInputError) as caught:
            compute_presets(
                {
                    **BASE,
                    "horizon_months": 2,
                    "historical_monthly_variable_spending": [2**63 - 1] * 3,
                }
            )

        self.assertEqual(caught.exception.code, "INVALID_INPUT")


if __name__ == "__main__":
    unittest.main()
