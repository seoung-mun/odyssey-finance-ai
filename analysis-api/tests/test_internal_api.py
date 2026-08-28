import json
import re
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app

VALID = {
    "randomSeed": 3,
    "nPaths": 10_000,
    "horizonMonths": 2,
    "periodRatios": [1.0, 1.0],
    "availableVariableBudget": 100,
    "historicalMonthlyVariableSpending": [100, 100, 100],
    "currentAvgVariableSpending": 100,
    "spendingFloor": {"mode": "OFF"},
}


class InternalApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.environment = patch.dict("os.environ", {"INTERNAL_API_TOKEN": "secret"})
        cls.environment.start()
        cls.client = TestClient(app, raise_server_exceptions=False)
        cls.headers = {"X-Internal-Token": "secret"}

    @classmethod
    def tearDownClass(cls):
        cls.environment.stop()

    def test_missing_token_is_unauthorized(self):
        self.assertEqual(self.client.get("/internal/health").status_code, 401)

    def test_health_reports_fallback_readiness(self):
        response = self.client.get("/internal/health", headers=self.headers)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")
        self.assertFalse(response.json()["llmReady"])

    def test_openapi_contains_all_internal_operations(self):
        paths = app.openapi()["paths"]

        self.assertEqual(paths["/internal/simulate"]["post"]["operationId"], "simulatePlan")
        self.assertEqual(
            paths["/internal/custom-option"]["post"]["operationId"], "computeCustomOption"
        )
        self.assertEqual(
            paths["/internal/explanations"]["post"]["operationId"], "generateExplanation"
        )
        self.assertEqual(paths["/internal/health"]["get"]["operationId"], "getInternalHealth")

    def test_openapi_advertises_api_key_and_compute_error_responses(self):
        schema = app.openapi()

        self.assertEqual(
            schema["components"]["securitySchemes"]["internalApiKey"],
            {"type": "apiKey", "in": "header", "name": "X-Internal-Token"},
        )
        for path in ("/internal/simulate", "/internal/custom-option"):
            operation = schema["paths"][path]["post"]
            self.assertEqual(operation["security"], [{"internalApiKey": []}])
            self.assertEqual(
                operation["responses"]["422"]["content"]["application/json"]["schema"],
                {"$ref": "#/components/schemas/ComputeError"},
            )
            self.assertEqual(
                operation["responses"]["500"]["content"]["application/json"]["schema"],
                {"$ref": "#/components/schemas/ComputeError"},
            )

    def test_openapi_matches_fixed_simulation_contract(self):
        schema = app.openapi()
        request = schema["components"]["schemas"]["SimulateRequest"]

        self.assertEqual(schema["info"]["version"], "1.2.0")
        self.assertIn("spendingFloor", request["required"])
        self.assertIn("periodRatios", request["required"])
        self.assertNotIn("currentMonthSpendingToDate", request["properties"])
        self.assertEqual(request["properties"]["nPaths"]["const"], 10_000)
        self.assertEqual(request["properties"]["horizonMonths"]["maximum"], 120)
        levels = request["properties"]["presetLevels"]["items"]
        self.assertEqual(request["properties"]["presetLevels"]["minItems"], 1)
        self.assertEqual(levels["exclusiveMinimum"], 0)
        self.assertEqual(levels["exclusiveMaximum"], 1)
        reduction = schema["components"]["schemas"]["ComputedOption"]["properties"][
            "requiredReductionRate"
        ]
        self.assertEqual(reduction["minimum"], float(1 - (2**63 - 1)))
        self.assertIn(
            "INVALID_INPUT",
            schema["components"]["schemas"]["ComputeError"]["properties"]["code"]["anyOf"][0][
                "enum"
            ],
        )

    def test_simulate_returns_contract_shape_and_camel_snapshot(self):
        response = self.client.post("/internal/simulate", headers=self.headers, json=VALID)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["simulation"]["method"], "IID_BOOTSTRAP")
        self.assertEqual(len(response.json()["options"]), 3)
        self.assertEqual(len(response.json()["percentileBands"]), 6)
        self.assertEqual(response.json()["resolvedSpendingFloor"]["mode"], "OFF")
        snapshot = response.json()["simulation"]["inputSnapshot"]
        self.assertEqual(snapshot["randomSeed"], 3)
        self.assertNotIn("random_seed", snapshot)

    def test_invalid_horizon_uses_compute_error(self):
        response = self.client.post(
            "/internal/simulate", headers=self.headers, json={**VALID, "horizonMonths": 0}
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_HORIZON")

    def test_fixed_path_count_and_horizon_limit_are_enforced(self):
        for update, code in (
            ({"nPaths": 9_999}, "INVALID_INPUT"),
            ({"horizonMonths": 121, "periodRatios": [1.0] * 121}, "INVALID_HORIZON"),
        ):
            with self.subTest(update=update):
                response = self.client.post(
                    "/internal/simulate", headers=self.headers, json={**VALID, **update}
                )
                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json()["code"], code)

    def test_invalid_period_ratios_are_422(self):
        for ratios in ([1.0], [0.0, 1.0], [1.0, 1.1], [0.5, 0.5, 0.5]):
            with self.subTest(ratios=ratios):
                response = self.client.post(
                    "/internal/simulate",
                    headers=self.headers,
                    json={**VALID, "horizonMonths": 3, "periodRatios": ratios},
                )
                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_negative_random_seed_is_422(self):
        response = self.client.post(
            "/internal/simulate", headers=self.headers, json={**VALID, "randomSeed": -1}
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_invalid_policy_warning_percentage_is_422(self):
        for value in (None, "invalid", -0.1, 1.1):
            with self.subTest(value=value):
                response = self.client.post(
                    "/internal/simulate",
                    headers=self.headers,
                    json={**VALID, "policySnapshot": {"aggressiveWarningPct": value}},
                )

                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_non_finite_policy_warning_percentage_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers={**self.headers, "Content-Type": "application/json"},
            content=json.dumps({**VALID, "policySnapshot": {"aggressiveWarningPct": float("nan")}}),
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_huge_integer_policy_warning_percentage_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={**VALID, "policySnapshot": {"aggressiveWarningPct": 10**1000}},
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_money_above_int64_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={**VALID, "availableVariableBudget": 2**63},
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_integer_fields_reject_bool_float_and_string_coercion(self):
        cases = (
            ("/internal/simulate", {"randomSeed": True}),
            ("/internal/simulate", {"randomSeed": 3.0}),
            ("/internal/simulate", {"randomSeed": "3"}),
            ("/internal/simulate", {"horizonMonths": 2.0}),
            ("/internal/simulate", {"nPaths": "10000"}),
            ("/internal/simulate", {"availableVariableBudget": 100.0}),
            ("/internal/simulate", {"historicalMonthlyVariableSpending": [100, True, 100]}),
            (
                "/internal/simulate",
                {"remainingScheduledExpenses": [{"monthIndex": "1", "amount": 10}]},
            ),
            (
                "/internal/simulate",
                {"spendingFloor": {"mode": "CUSTOM", "customMonthlyAmount": "10"}},
            ),
            ("/internal/custom-option", {"baselineMonthlySpending": 100.0}),
        )
        for path, update in cases:
            with self.subTest(path=path, update=update):
                payload = {**VALID, **update}
                if path == "/internal/custom-option":
                    payload.setdefault("baselineMonthlySpending", 100)
                response = self.client.post(path, headers=self.headers, json=payload)
                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_history_horizon_sum_overflow_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "horizonMonths": 2,
                "historicalMonthlyVariableSpending": [2**63 - 1] * 3,
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_derived_recommended_spending_overflow_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "horizonMonths": 1,
                "periodRatios": [1.0],
                "availableVariableBudget": 2**63 - 1,
                "historicalMonthlyVariableSpending": [1, 1, 1],
                "currentAvgVariableSpending": 100,
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_large_negative_reduction_rate_stays_persistable(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "horizonMonths": 12,
                "periodRatios": [1.0] * 12,
                "availableVariableBudget": 150_000_000,
                "historicalMonthlyVariableSpending": [100_000] * 3,
                "currentAvgVariableSpending": 100_000,
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["options"][0]["requiredReductionRate"], -124.0)

    def test_scheduled_expense_outside_horizon_is_invalid_horizon(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "remainingScheduledExpenses": [{"monthIndex": 3, "amount": 10}],
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_HORIZON")
        self.assertTrue(response.json()["detail"]["errors"])

    def test_invalid_scheduled_expense_amount_is_invalid_input(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "remainingScheduledExpenses": [{"monthIndex": 1, "amount": -5}],
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_mixed_validation_errors_are_invalid_input(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "horizonMonths": 0,
                "historicalMonthlyVariableSpending": [100, -1],
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_empty_preset_levels_are_invalid_input(self):
        response = self.client.post(
            "/internal/simulate", headers=self.headers, json={**VALID, "presetLevels": []}
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_expected_compute_error_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={**VALID, "historicalMonthlyVariableSpending": [0, 0, 0]},
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INSUFFICIENT_HISTORY")

    def test_invalid_history_item_is_not_classified_as_insufficient_history(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={**VALID, "historicalMonthlyVariableSpending": [100, 100, -1]},
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    def test_short_history_is_classified_as_insufficient_history(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={**VALID, "historicalMonthlyVariableSpending": [100, 100]},
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INSUFFICIENT_HISTORY")

    def test_unexpected_error_remains_500(self):
        with (
            self.assertLogs("analysis_api", level="ERROR") as logs,
            patch("app.main.compute_presets", side_effect=RuntimeError("broken")),
        ):
            response = self.client.post(
                "/internal/simulate",
                headers={**self.headers, "X-Request-ID": "qa-request"},
                json=VALID,
            )

        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.headers["X-Request-ID"], "qa-request")
        self.assertIn("qa-request", "\n".join(logs.output))
        self.assertIn("/internal/simulate", "\n".join(logs.output))

    def test_custom_option_returns_custom_contract(self):
        response = self.client.post(
            "/internal/custom-option",
            headers=self.headers,
            json={**VALID, "availableVariableBudget": 160, "baselineMonthlySpending": 80},
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["option"]["optionType"], "CUSTOM")
        self.assertIsNone(response.json()["option"]["nominalLevel"])

    def test_auto_floor_with_short_history_is_insufficient_history(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "historicalMonthlyVariableSpending": [10] * 5,
                "spendingFloor": {"mode": "AUTO"},
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INSUFFICIENT_HISTORY")

    def test_custom_option_below_floor_is_invalid_input(self):
        response = self.client.post(
            "/internal/custom-option",
            headers=self.headers,
            json={
                **VALID,
                "baselineMonthlySpending": 79,
                "spendingFloor": {"mode": "CUSTOM", "customMonthlyAmount": 80},
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")

    @patch("app.explanation._ollama_request", side_effect=OSError("offline"))
    def test_explanation_is_number_free_fallback_when_ollama_is_unavailable(self, _post):
        response = self.client.post(
            "/internal/explanations",
            headers=self.headers,
            json={
                "planVersionId": 1,
                "allowedNumbers": [100, 80, 2],
                "plan": {
                    "recommendedMonthlySpending": 80,
                    "currentAvgVariableSpending": 100,
                    "remainingMonths": 2,
                },
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "FALLBACK")
        self.assertIsNone(response.json()["model"])
        self.assertEqual(response.json()["retryCount"], 0)
        self.assertIsNone(re.search(r"\d", response.json()["text"]))


if __name__ == "__main__":
    unittest.main()
