import socket
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app


def explanation_payload(**updates):
    """설명 API의 유효한 기본 요청을 만든다."""

    payload = {
        "planVersionId": 1,
        "allowedNumbers": [700_000, 200_000, 8, 10_000_000, 3_000_000],
        "plan": {
            "recommendedMonthlySpending": 700_000,
            "currentAvgVariableSpending": 200_000,
            "remainingMonths": 8,
            "targetAmount": 10_000_000,
            "currentSavedAmount": 3_000_000,
            "aggressiveWarning": False,
        },
        "previousPlan": None,
    }
    payload.update(updates)
    return payload


class ExplanationApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.environment = patch.dict("os.environ", {"INTERNAL_API_TOKEN": "secret"})
        cls.environment.start()
        cls.client = TestClient(app, raise_server_exceptions=False)
        cls.headers = {"X-Internal-Token": "secret"}

    @classmethod
    def tearDownClass(cls):
        cls.environment.stop()

    def post(self, payload=None):
        return self.client.post(
            "/internal/explanations",
            headers=self.headers,
            json=payload or explanation_payload(),
        )

    def test_returns_ready_deterministic_template(self):
        first = self.post()
        second = self.post()

        self.assertEqual(first.status_code, 200)
        body = first.json()
        self.assertEqual(body["status"], "READY")
        self.assertEqual(body["model"], "deterministic-template-v1")
        self.assertEqual(body["retryCount"], 0)
        self.assertEqual(body["failedNumbers"], [])
        self.assertEqual(body["text"], second.json()["text"])
        self.assertEqual(
            body["text"],
            "현재 계획의 권장 월 변동지출은 700,000원입니다. "
            "최근 월평균 변동지출은 200,000원이며, 남은 기간은 8개월입니다. "
            "목표 금액은 10,000,000원입니다. 현재 저축액은 3,000,000원입니다. "
            "현재 계획에 맞춰 지출 내역을 꾸준히 확인해 주세요.",
        )

    def test_aggressive_warning_uses_warning_template(self):
        payload = explanation_payload()
        payload["plan"]["aggressiveWarning"] = True

        body = self.post(payload).json()

        self.assertEqual(body["status"], "READY")
        self.assertIn("실천 부담이 큰 계획", body["text"])

    def test_optional_amounts_are_omitted(self):
        payload = explanation_payload(
            allowedNumbers=[0, 1],
            plan={
                "recommendedMonthlySpending": 0,
                "currentAvgVariableSpending": 0,
                "remainingMonths": 1,
                "targetAmount": None,
                "currentSavedAmount": None,
                "aggressiveWarning": None,
            },
        )

        body = self.post(payload).json()

        self.assertEqual(body["status"], "READY")
        self.assertNotIn("목표 금액", body["text"])
        self.assertNotIn("현재 저축액", body["text"])

    def test_does_not_open_network_connection(self):
        with patch.object(socket, "create_connection", side_effect=AssertionError("network call")):
            response = self.post()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "READY")

    def test_max_retry_does_not_change_template_result(self):
        zero_retry = self.post(explanation_payload(maxRetry=0)).json()
        two_retries = self.post(explanation_payload(maxRetry=2)).json()

        self.assertEqual(zero_retry["text"], two_retries["text"])
        self.assertEqual(zero_retry["retryCount"], 0)
        self.assertEqual(two_retries["retryCount"], 0)


if __name__ == "__main__":
    unittest.main()
