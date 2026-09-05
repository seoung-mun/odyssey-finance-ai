import unittest

from engine.planning import (
    ComputeInputError,
    canonical_hash,
    cashflow_adjustments_for_horizon,
    compute_custom,
    compute_policy_scenario,
    compute_presets,
)

BASE = {
    "random_seed": 3,
    "n_paths": 10_000,
    "horizon_months": 2,
    "period_ratios": [1.0, 1.0],
    "available_variable_budget": 100,
    "historical_monthly_variable_spending": [100, 100, 100],
    "current_avg_variable_spending": 100,
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
    "remainingScheduledExpenses": [{"monthIndex": 2, "amount": 10}],
    "presetLevels": [0.70],
    "policySnapshot": {"aggressiveWarningPct": 0.10},
}


class PlanningTest(unittest.TestCase):
    def test_seed_selects_reproducible_non_degenerate_bootstrap_paths(self):
        payload = {
            **BASE,
            "horizon_months": 3,
            "period_ratios": [1.0, 1.0, 1.0],
            "available_variable_budget": 1_500_000,
            "historical_monthly_variable_spending": [
                820_000, 1_240_000, 950_000, 1_410_000, 760_000, 1_100_000,
                1_320_000, 880_000, 1_190_000, 1_010_000, 1_470_000, 930_000,
                1_280_000, 850_000, 1_160_000, 1_390_000, 970_000, 1_220_000,
                790_000, 1_340_000, 1_050_000, 1_430_000, 900_000, 1_250_000,
            ],
            "current_avg_variable_spending": 1_100_000,
            "remaining_scheduled_expenses": [],
            "preset_levels": [0.70, 0.80, 0.90],
        }

        seeded = compute_presets(payload)
        other_seed = compute_presets({**payload, "random_seed": 4})

        self.assertEqual(
            [band["p50"] for band in seeded["percentileBands"] if band["optionIndex"] == 0],
            [633_898, 1_255_084, 1_884_745],
        )
        self.assertEqual(seeded, compute_presets(payload))
        self.assertNotEqual(seeded, other_seed)

    def test_cashflow_helper_applies_exact_inclusive_and_additive_months(self):
        payload = {
            **BASE,
            "horizon_months": 4,
            "period_ratios": [1.0] * 4,
            "simulation_start_year_month": "2026-10",
            "future_cashflow_adjustments": [
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "ONE_TIME_FUNDING",
                    "amount_won": 300_000,
                    "start_year_month": "2026-11",
                },
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "ONE_TIME_FUNDING",
                    "amount_won": 500_000,
                    "start_year_month": "2026-11",
                },
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "MONTHLY_EXPENSE_REDUCTION",
                    "amount_won": 200_000,
                    "start_year_month": "2026-09",
                    "end_year_month": "2026-12",
                },
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "ONE_TIME_FUNDING",
                    "amount_won": 700_000,
                    "start_year_month": "2026-09",
                },
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "ONE_TIME_FUNDING",
                    "amount_won": 900_000,
                    "start_year_month": "2027-02",
                },
            ],
        }

        self.assertEqual(
            cashflow_adjustments_for_horizon(payload),
            [200_000, 1_000_000, 200_000, 0],
        )

    def test_monthly_helper_counts_both_ends_as_twelve_payments(self):
        payload = {
            **BASE,
            "horizon_months": 14,
            "period_ratios": [1.0] * 14,
            "simulation_start_year_month": "2026-09",
            "future_cashflow_adjustments": [
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "MONTHLY_EXPENSE_REDUCTION",
                    "amount_won": 200_000,
                    "start_year_month": "2026-10",
                    "end_year_month": "2027-09",
                }
            ],
        }

        offsets = cashflow_adjustments_for_horizon(payload)

        self.assertEqual(offsets[0], 0)
        self.assertEqual(offsets[1:13], [200_000] * 12)
        self.assertEqual(offsets[13], 0)

    def test_one_time_changes_band_only_from_payment_month(self):
        baseline_payload = {
            **BASE,
            "horizon_months": 3,
            "period_ratios": [1.0] * 3,
            "available_variable_budget": 150,
            "remaining_scheduled_expenses": [],
        }
        policy_payload = {
            **baseline_payload,
            "simulation_start_year_month": "2026-09",
            "future_cashflow_adjustments": [
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "ONE_TIME_FUNDING",
                    "amount_won": 300,
                    "start_year_month": "2026-10",
                }
            ],
        }

        baseline = compute_presets(baseline_payload)
        policy = compute_presets(policy_payload)

        self.assertEqual(policy["options"][0]["recommendedMonthlySpending"], 50)
        self.assertEqual([band["p50"] for band in baseline["percentileBands"]], [50, 100, 150])
        self.assertEqual([band["p50"] for band in policy["percentileBands"]], [50, 400, 450])

    def test_monthly_reduction_stops_new_additions_after_inclusive_end(self):
        payload = {
            **BASE,
            "horizon_months": 3,
            "period_ratios": [1.0] * 3,
            "available_variable_budget": 150,
            "remaining_scheduled_expenses": [],
            "simulation_start_year_month": "2026-09",
            "future_cashflow_adjustments": [
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "MONTHLY_EXPENSE_REDUCTION",
                    "amount_won": 20,
                    "start_year_month": "2026-09",
                    "end_year_month": "2026-10",
                }
            ],
        }

        result = compute_presets(payload)

        self.assertEqual([band["p50"] for band in result["percentileBands"]], [70, 140, 190])

    def test_custom_option_uses_same_confirmed_cashflow_offsets(self):
        baseline_payload = {
            **BASE,
            "horizon_months": 2,
            "period_ratios": [1.0, 1.0],
            "available_variable_budget": 100,
            "remaining_scheduled_expenses": [],
            "baseline_monthly_spending": 50,
        }
        adjusted_payload = {
            **baseline_payload,
            "simulation_start_year_month": "2026-09",
            "future_cashflow_adjustments": [
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "ONE_TIME_FUNDING",
                    "amount_won": 30,
                    "start_year_month": "2026-10",
                }
            ],
        }

        baseline = compute_custom(baseline_payload)
        adjusted = compute_custom(adjusted_payload)

        self.assertEqual(adjusted["option"]["recommendedMonthlySpending"], 50)
        self.assertEqual(
            [
                new["p50"] - old["p50"]
                for old, new in zip(
                    baseline["percentileBands"], adjusted["percentileBands"], strict=True
                )
            ],
            [0, 30],
        )

    def test_empty_adjustments_preserve_existing_calculation_and_three_preset_offsets_match(self):
        three_levels = {**BASE, "preset_levels": [0.70, 0.80, 0.90]}
        explicit_empty = {**three_levels, "future_cashflow_adjustments": []}
        baseline = compute_presets(three_levels)
        empty = compute_presets(explicit_empty)

        self.assertEqual(baseline["options"], empty["options"])
        self.assertEqual(baseline["percentileBands"], empty["percentileBands"])
        self.assertEqual(baseline["simulation"]["inputHash"], empty["simulation"]["inputHash"])
        self.assertEqual(
            baseline["simulation"]["inputSnapshot"], empty["simulation"]["inputSnapshot"]
        )
        self.assertEqual(
            baseline["simulation"]["resultSummary"], empty["simulation"]["resultSummary"]
        )
        self.assertEqual(baseline["simulation"]["randomSeed"], empty["simulation"]["randomSeed"])

        adjusted_payload = {
            **three_levels,
            "simulation_start_year_month": "2026-09",
            "future_cashflow_adjustments": [
                {
                    "source": "POLICY_BENEFIT",
                    "adjustment_type": "MONTHLY_EXPENSE_REDUCTION",
                    "amount_won": 20,
                    "start_year_month": "2026-09",
                    "end_year_month": "2026-10",
                }
            ],
        }
        adjusted = compute_presets(adjusted_payload)
        horizon = three_levels["horizon_months"]
        for option_index in range(3):
            start = option_index * horizon
            baseline_medians = [
                band["p50"] for band in baseline["percentileBands"][start : start + horizon]
            ]
            adjusted_medians = [
                band["p50"] for band in adjusted["percentileBands"][start : start + horizon]
            ]
            self.assertEqual(
                adjusted["options"][option_index]["recommendedMonthlySpending"],
                baseline["options"][option_index]["recommendedMonthlySpending"],
            )
            self.assertEqual(
                [new - old for old, new in zip(baseline_medians, adjusted_medians, strict=True)],
                [20, 40],
            )

    def test_policy_scenario_one_time_changes_summary_but_not_bands(self):
        result = compute_policy_scenario(
            {**BASE, "remaining_scheduled_expenses": []},
            {"option_type": "PRESET", "nominal_level": 0.70},
            {
                "type": "ONE_TIME_FUNDING",
                "amount_won": 100,
                "start_month_index": 1,
                "source_version": "2026-08-31",
            },
        )

        self.assertEqual(
            result["currentPlanSummary"],
            {
                "optionType": "PRESET",
                "nominalLevel": 0.7,
                "recommendedMonthlySpending": 50,
                "requiredReductionRate": 0.5,
                "simulationCoverage": 1.0,
                "historicalFeasibilityRatio": 0.0,
                "aggressiveWarning": True,
                "targetCoverageMet": True,
            },
        )
        self.assertEqual(
            result["assumedPlanSummary"],
            {
                "optionType": "PRESET",
                "nominalLevel": 0.7,
                "recommendedMonthlySpending": 100,
                "requiredReductionRate": 0.0,
                "simulationCoverage": 1.0,
                "historicalFeasibilityRatio": 1.0,
                "aggressiveWarning": False,
                "targetCoverageMet": True,
            },
        )
        expected_bands = [
            {
                "optionIndex": 0,
                "monthIndex": month,
                "metricType": "CUMULATIVE_SAVINGS",
                "p10": value,
                "p25": value,
                "p50": value,
                "p75": value,
                "p90": value,
            }
            for month, value in ((1, 50), (2, 100))
        ]
        self.assertEqual(result["currentBands"], expected_bands)
        self.assertEqual(result["assumedBands"], expected_bands)

    def test_policy_scenario_custom_keeps_baseline_and_applies_full_month_amount(self):
        plan = {**BASE, "available_variable_budget": 50, "remaining_scheduled_expenses": []}
        selected = {"option_type": "CUSTOM", "baseline_monthly_spending": 50}
        adjustment = {
            "type": "MONTHLY_EXPENSE_REDUCTION",
            "amount_won": 25,
            "start_month_index": 1,
            "end_month_index": 2,
            "source_version": "2026-08-31",
        }
        result = compute_policy_scenario(plan, selected, adjustment)

        self.assertEqual(result["currentPlanSummary"]["recommendedMonthlySpending"], 50)
        self.assertEqual(result["assumedPlanSummary"]["recommendedMonthlySpending"], 50)
        self.assertEqual(result["currentPlanSummary"]["simulationCoverage"], 0.0)
        self.assertEqual(result["assumedPlanSummary"]["simulationCoverage"], 1.0)
        self.assertEqual([band["p50"] for band in result["currentBands"]], [50, 100])
        self.assertEqual([band["p50"] for band in result["assumedBands"]], [75, 150])
        repeated = [compute_policy_scenario(plan, selected, adjustment) for _ in range(10)]
        self.assertEqual(repeated, [result] * 10)

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
