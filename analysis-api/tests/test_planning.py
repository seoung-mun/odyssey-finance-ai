import unittest

from engine.planning import ComputeInputError, canonical_hash, compute_custom, compute_presets

BASE = {
    "random_seed": 3,
    "n_paths": 10_000,
    "horizon_months": 2,
    "period_ratios": [1.0, 1.0],
    "available_variable_budget": 100,
    "historical_monthly_variable_spending": [100, 100, 100],
    "current_avg_variable_spending": 100,
    "spending_floor": {"mode": "OFF", "custom_monthly_amount": None},
    "remaining_scheduled_expenses": [{"month_index": 2, "amount": 10}],
    "preset_levels": [0.70],
    "policy_snapshot": {"aggressiveWarningPct": 0.10},
}

INPUT_SNAPSHOT = {
    "randomSeed": 3,
    "nPaths": 10_000,
    "horizonMonths": 2,
    "periodRatios": [1.0, 1.0],
    "availableVariableBudget": 100,
    "historicalMonthlyVariableSpending": [100, 100, 100],
    "currentAvgVariableSpending": 100,
    "spendingFloor": {"mode": "OFF", "customMonthlyAmount": None},
    "remainingScheduledExpenses": [{"monthIndex": 2, "amount": 10}],
    "presetLevels": [0.70],
    "policySnapshot": {"aggressiveWarningPct": 0.10},
}


class PlanningTest(unittest.TestCase):
    def test_auto_floor_uses_only_latest_twelve_complete_months(self):
        result = compute_presets(
            {
                **BASE,
                "historical_monthly_variable_spending": [1_000] + list(range(10, 130, 10)),
                "current_avg_variable_spending": 200,
                "spending_floor": {"mode": "AUTO", "custom_monthly_amount": None},
            }
        )

        self.assertEqual(
            result["resolvedSpendingFloor"],
            {
                "mode": "AUTO",
                "requestedMonthlyAmount": 32,
                "effectiveMonthlyAmount": 32,
                "autoHistoryMonths": 12,
            },
        )

    def test_auto_floor_rejects_five_months_and_accepts_six(self):
        with self.assertRaises(ComputeInputError) as caught:
            compute_presets(
                {
                    **BASE,
                    "historical_monthly_variable_spending": [10, 20, 30, 40, 50],
                    "spending_floor": {"mode": "AUTO", "custom_monthly_amount": None},
                }
            )
        self.assertEqual(caught.exception.code, "INSUFFICIENT_HISTORY")

        result = compute_presets(
            {
                **BASE,
                "historical_monthly_variable_spending": [10, 20, 30, 40, 50, 60],
                "spending_floor": {"mode": "AUTO", "custom_monthly_amount": None},
            }
        )
        self.assertEqual(result["resolvedSpendingFloor"]["requestedMonthlyAmount"], 20)

    def test_preset_floor_recomputes_option_and_bands_from_final_amount(self):
        result = compute_presets(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [1.0],
                "available_variable_budget": 30,
                "remaining_scheduled_expenses": [],
                "spending_floor": {"mode": "CUSTOM", "custom_monthly_amount": 80},
            }
        )

        option = result["options"][0]
        self.assertEqual(option["recommendedMonthlySpending"], 80)
        self.assertEqual(option["requiredReductionRate"], 0.2)
        self.assertEqual(option["simulationCoverage"], 0.0)
        self.assertEqual(option["effectiveMaxReductionRate"], 0.2)
        self.assertTrue(option["floorApplied"])
        self.assertFalse(option["targetCoverageMet"])
        self.assertEqual(result["percentileBands"][0]["p50"], 20)

    def test_floor_is_capped_at_average_and_zero_average_resolves_to_zero(self):
        capped = compute_presets(
            {
                **BASE,
                "spending_floor": {"mode": "CUSTOM", "custom_monthly_amount": 200},
            }
        )
        self.assertEqual(capped["resolvedSpendingFloor"]["effectiveMonthlyAmount"], 100)
        self.assertEqual(capped["options"][0]["effectiveMaxReductionRate"], 0.0)

        zero = compute_custom(
            {
                **BASE,
                "current_avg_variable_spending": 0,
                "baseline_monthly_spending": 0,
                "spending_floor": {"mode": "CUSTOM", "custom_monthly_amount": 100},
            }
        )
        self.assertEqual(zero["resolvedSpendingFloor"]["effectiveMonthlyAmount"], 0)
        self.assertEqual(zero["option"]["effectiveMaxReductionRate"], 0.0)

    def test_custom_baseline_below_effective_floor_is_rejected_without_clamping(self):
        with self.assertRaises(ComputeInputError) as caught:
            compute_custom(
                {
                    **BASE,
                    "baseline_monthly_spending": 79,
                    "spending_floor": {"mode": "CUSTOM", "custom_monthly_amount": 80},
                }
            )

        self.assertEqual(caught.exception.code, "INVALID_INPUT")

    def test_canonical_hash_sorts_keys(self):
        self.assertEqual(
            canonical_hash({"b": 2, "a": 1}),
            "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777",
        )

    def test_canonical_hash_uses_ascii_escaped_json(self):
        self.assertEqual(
            canonical_hash({"policySnapshot": {"note": "빡센"}}),
            "7abd49c40334ebfcc7c39f2b6dde5935e81b94c8c7767fd447d679df409389a9",
        )

    def test_preset_uses_closed_form_and_builds_monotonic_bands(self):
        result = compute_presets(BASE)

        self.assertEqual(result["options"][0]["requiredReductionRate"], 0.5)
        self.assertEqual(result["options"][0]["recommendedMonthlySpending"], 50)
        self.assertEqual(result["options"][0]["simulationCoverage"], 1.0)
        self.assertEqual(result["percentileBands"][0]["p50"], 50)
        self.assertEqual(result["percentileBands"][1]["p50"], 90)
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

    def test_custom_feasibility_uses_only_latest_24_months(self):
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [1.0],
                "historical_monthly_variable_spending": [10] * 12 + [1_000] * 24,
                "current_avg_variable_spending": 1_000,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": 500,
            }
        )

        self.assertEqual(result["option"]["historicalFeasibilityRatio"], 0.0)
        self.assertTrue(result["option"]["aggressiveWarning"])

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
                "period_ratios": [1.0],
                "available_variable_budget": 100,
                "historical_monthly_variable_spending": [300, 300, 300],
                "current_avg_variable_spending": 300,
                "remaining_scheduled_expenses": [],
            }
        )

        self.assertEqual(result["options"][0]["recommendedMonthlySpending"], 100)

    def test_preset_counts_exact_budget_boundary_at_non_binary_ratio(self):
        result = compute_presets(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [1.0],
                "available_variable_budget": 1,
                "historical_monthly_variable_spending": [9, 9, 9],
                "current_avg_variable_spending": 9,
                "remaining_scheduled_expenses": [],
            }
        )

        self.assertEqual(result["options"][0]["simulationCoverage"], 1.0)

    def test_negative_preset_reduction_rounds_recommendation_to_nearest_won(self):
        result = compute_presets(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [1.0],
                "available_variable_budget": 61,
                "historical_monthly_variable_spending": [7, 7, 7],
                "current_avg_variable_spending": 7,
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
                "period_ratios": [1.0],
                "available_variable_budget": 63,
                "historical_monthly_variable_spending": [77, 77, 77],
                "current_avg_variable_spending": 11,
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
                "period_ratios": [1.0],
                "available_variable_budget": 10_000,
                "historical_monthly_variable_spending": [85, 85, 85],
                "current_avg_variable_spending": 17,
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
                "period_ratios": [1.0],
                "available_variable_budget": 100,
                "historical_monthly_variable_spending": [100, 100, 100],
                "current_avg_variable_spending": 0,
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
                "period_ratios": [1.0],
                "available_variable_budget": 100,
                "historical_monthly_variable_spending": [100, 200, 300],
                "current_avg_variable_spending": 200,
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

    def test_derived_recommended_spending_overflow_is_expected_compute_error(self):
        with self.assertRaises(ComputeInputError) as caught:
            compute_presets(
                {
                    **BASE,
                    "horizon_months": 1,
                    "period_ratios": [1.0],
                    "available_variable_budget": 2**63 - 1,
                    "historical_monthly_variable_spending": [1, 1, 1],
                    "current_avg_variable_spending": 100,
                    "remaining_scheduled_expenses": [],
                }
            )

        self.assertEqual(caught.exception.code, "INVALID_INPUT")

    def test_partial_calendar_months_scale_paths_and_average_baseline(self):
        cases = (
            ([0.5], [25]),
            ([0.5, 1.0, 1.0], [25, 75, 125]),
            ([1.0, 1.0, 0.25], [50, 100, 112]),
        )
        for ratios, medians in cases:
            with self.subTest(ratios=ratios):
                result = compute_custom(
                    {
                        **BASE,
                        "horizon_months": len(ratios),
                        "period_ratios": ratios,
                        "available_variable_budget": 10_000,
                        "remaining_scheduled_expenses": [],
                        "baseline_monthly_spending": 50,
                    }
                )
                self.assertEqual([band["p50"] for band in result["percentileBands"]], medians)

    def test_current_month_spending_is_not_subtracted_twice(self):
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [1.0],
                "available_variable_budget": 50,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": 50,
            }
        )

        self.assertEqual(result["percentileBands"][0]["p50"], 50)

    def test_preset_uses_rounded_recommendation_ratio_everywhere(self):
        result = compute_presets(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [1.0],
                "available_variable_budget": 3,
                "historical_monthly_variable_spending": [8, 8, 8],
                "current_avg_variable_spending": 10,
                "remaining_scheduled_expenses": [],
            }
        )

        option = result["options"][0]
        self.assertEqual(option["recommendedMonthlySpending"], 4)
        self.assertEqual(option["requiredReductionRate"], 0.6)
        self.assertEqual(option["simulationCoverage"], 0.0)
        self.assertEqual(result["percentileBands"][0]["p50"], 7)

    def test_zero_average_and_recommendation_use_zero_ratio(self):
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [0.5],
                "available_variable_budget": 100,
                "current_avg_variable_spending": 0,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": 0,
            }
        )

        self.assertEqual(result["option"]["requiredReductionRate"], 0.0)
        self.assertEqual(result["percentileBands"][0]["p50"], 0)

    def test_partial_month_money_is_rounded_exactly_above_float_safe_integer(self):
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [0.1],
                "available_variable_budget": 2**63 - 1,
                "historical_monthly_variable_spending": [1, 1, 1],
                "current_avg_variable_spending": 2**53 + 2,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": 0,
            }
        )

        self.assertEqual(result["percentileBands"][0]["p50"], 900_719_925_474_099)

    def test_large_equal_baseline_and_spending_do_not_overflow_before_cancelling(self):
        amount = 2**40
        result = compute_custom(
            {
                **BASE,
                "horizon_months": 1,
                "period_ratios": [1.0],
                "available_variable_budget": amount,
                "historical_monthly_variable_spending": [amount] * 3,
                "current_avg_variable_spending": amount,
                "remaining_scheduled_expenses": [],
                "baseline_monthly_spending": amount,
            }
        )

        self.assertEqual(result["percentileBands"][0]["p50"], 0)


if __name__ == "__main__":
    unittest.main()
