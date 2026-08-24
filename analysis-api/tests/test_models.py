import unittest

from pydantic import ValidationError

from app.models import CustomOptionRequest, SimulateRequest


class SimulateRequestTest(unittest.TestCase):
    def test_defaults_and_aliases_follow_internal_contract(self):
        request = SimulateRequest.model_validate(
            {
                "randomSeed": 7,
                "horizonMonths": 2,
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
                    "availableVariableBudget": 100,
                    "historicalMonthlyVariableSpending": [80, 100, 120],
                    "currentAvgVariableSpending": 100,
                    "remainingScheduledExpenses": [{"monthIndex": 3, "amount": 10}],
                }
            )

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
