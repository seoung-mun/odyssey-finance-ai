import re
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app

VALID = {
    "randomSeed": 3,
    "nPaths": 8,
    "horizonMonths": 2,
    "availableVariableBudget": 100,
    "historicalMonthlyVariableSpending": [100, 100, 100],
    "currentAvgVariableSpending": 100,
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

    def test_simulate_returns_contract_shape_and_camel_snapshot(self):
        response = self.client.post("/internal/simulate", headers=self.headers, json=VALID)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["simulation"]["method"], "IID_BOOTSTRAP")
        self.assertEqual(len(response.json()["options"]), 3)
        self.assertEqual(len(response.json()["percentileBands"]), 6)
        snapshot = response.json()["simulation"]["inputSnapshot"]
        self.assertEqual(snapshot["randomSeed"], 3)
        self.assertNotIn("random_seed", snapshot)

    def test_invalid_horizon_uses_compute_error(self):
        response = self.client.post(
            "/internal/simulate", headers=self.headers, json={**VALID, "horizonMonths": 0}
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_HORIZON")

    def test_cross_field_validation_error_is_json_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={
                **VALID,
                "remainingScheduledExpenses": [{"monthIndex": 3, "amount": 10}],
            },
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INVALID_INPUT")
        self.assertTrue(response.json()["detail"]["errors"])

    def test_expected_compute_error_is_422(self):
        response = self.client.post(
            "/internal/simulate",
            headers=self.headers,
            json={**VALID, "historicalMonthlyVariableSpending": [0, 0, 0]},
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["code"], "INSUFFICIENT_HISTORY")

    def test_unexpected_error_remains_500(self):
        with patch("app.main.compute_presets", side_effect=RuntimeError("broken")):
            response = self.client.post("/internal/simulate", headers=self.headers, json=VALID)

        self.assertEqual(response.status_code, 500)

    def test_custom_option_returns_custom_contract(self):
        response = self.client.post(
            "/internal/custom-option",
            headers=self.headers,
            json={**VALID, "availableVariableBudget": 160, "baselineMonthlySpending": 80},
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["option"]["optionType"], "CUSTOM")
        self.assertIsNone(response.json()["option"]["nominalLevel"])

    def test_explanation_is_number_free_fallback(self):
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
        self.assertIsNone(re.search(r"\d", response.json()["text"]))


if __name__ == "__main__":
    unittest.main()
