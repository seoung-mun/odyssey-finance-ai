import json
import socket
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app


@contextmanager
def raw_http_server(raw_response):
    """요청 하나마다 지정한 raw HTTP 응답을 보내는 로컬 서버를 제공한다."""

    class RawHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            try:
                self.connection.sendall(raw_response)
            except OSError:
                pass

        def log_message(self, _format, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), RawHandler)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01))
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def explanation_payload(**updates):
    payload = {
        "planVersionId": 1,
        "allowedNumbers": [700_000, 200_000, 8, -10, 32],
        "plan": {
            "recommendedMonthlySpending": 700_000,
            "currentAvgVariableSpending": 200_000,
            "remainingMonths": 8,
            "aggressiveWarning": False,
        },
        "previousPlan": None,
    }
    payload.update(updates)
    return payload


class ExplanationApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.environment = patch.dict(
            "os.environ",
            {"INTERNAL_API_TOKEN": "secret", "OLLAMA_URL": "http://ollama:11434"},
        )
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

    @patch("app.explanation._ollama_request")
    def test_valid_ollama_text_returns_ready_with_fixed_options(self, post):
        post.return_value = {
            "response": "월 지출은 700,000원, 조정액은 -10원이며 기간은 8개월입니다."
        }

        response = self.post()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "READY")
        self.assertEqual(response.json()["model"], "qwen3.5:2b-q4_K_M")
        self.assertEqual(response.json()["retryCount"], 0)
        payload = post.call_args.kwargs["payload"]
        self.assertEqual(payload["model"], "qwen3.5:2b-q4_K_M")
        self.assertFalse(payload["stream"])
        self.assertEqual(payload["options"]["num_ctx"], 2048)

    @patch("app.explanation._ollama_request")
    def test_bad_number_is_corrected_at_most_twice(self, post):
        post.side_effect = [
            {"response": "월 지출은 70만원입니다."},
            {"response": "월 지출은 7원입니다."},
            {"response": "월 지출은 700,000원입니다."},
        ]

        response = self.post()

        self.assertEqual(response.json()["status"], "READY")
        self.assertEqual(response.json()["retryCount"], 2)
        self.assertEqual(post.call_count, 3)
        self.assertIn("70", post.call_args_list[1].kwargs["payload"]["prompt"])
        self.assertIn("7", post.call_args_list[2].kwargs["payload"]["prompt"])

    @patch("app.explanation._ollama_request")
    def test_max_retry_zero_falls_back_without_correction(self, post):
        post.return_value = {"response": "월 지출은 70만원입니다."}

        response = self.post(explanation_payload(maxRetry=0))

        body = response.json()
        self.assertEqual(body["status"], "FALLBACK")
        self.assertEqual(body["retryCount"], 0)
        self.assertEqual(body["failedNumbers"], ["70", "<KOREAN_NUMERAL>"])
        self.assertEqual(post.call_count, 1)
        self.assertIsNone(body["model"])
        self.assertFalse(any(char.isdigit() for char in body["text"]))

    @patch("app.explanation._ollama_request")
    def test_repeated_bad_numbers_fall_back_with_safe_unique_values(self, post):
        post.return_value = {"response": "잘못된 값은 -9, 1.5, 44%, 44%, 1e6입니다."}

        response = self.post()

        body = response.json()
        self.assertEqual(body["status"], "FALLBACK")
        self.assertEqual(body["retryCount"], 2)
        self.assertEqual(body["failedNumbers"], ["-9", "1.5", "44", "1000000"])
        self.assertEqual(post.call_count, 3)

    @patch("app.explanation._ollama_request")
    def test_embedded_ascii_number_is_still_validated(self, post):
        post.return_value = {"response": "월700001원만 사용하세요."}

        body = self.post(explanation_payload(maxRetry=0)).json()

        self.assertEqual(body["status"], "FALLBACK")
        self.assertEqual(body["failedNumbers"], ["700001"])

    @patch("app.explanation._ollama_request")
    def test_embedded_allowed_ascii_number_remains_ready(self, post):
        post.return_value = {"response": "월700000원만 사용하세요."}

        body = self.post(explanation_payload(maxRetry=0)).json()

        self.assertEqual(body["status"], "READY")

    @patch("app.explanation._ollama_request")
    def test_non_ascii_numeric_characters_never_reach_ready(self, post):
        for text in ("금액은 ①원입니다.", "금액은 １２３원입니다.", "기간은 Ⅻ개월입니다."):
            with self.subTest(text=text):
                post.return_value = {"response": text}

                body = self.post(explanation_payload(maxRetry=0)).json()

                self.assertEqual(body["status"], "FALLBACK")

    @patch("app.explanation._ollama_request")
    def test_korean_numerals_with_financial_units_never_reach_ready(self, post):
        for text in ("칠십만 원을 쓰세요.", "일곱 개월 남았습니다.", "삼십 퍼센트입니다."):
            with self.subTest(text=text):
                post.return_value = {"response": text}

                body = self.post(explanation_payload(maxRetry=0)).json()

                self.assertEqual(body["status"], "FALLBACK")

    @patch("app.explanation._ollama_request")
    def test_extreme_numeric_expression_falls_back_instead_of_500(self, post):
        post.return_value = {"response": "값은 1e" + "9" * 1_000 + "입니다."}

        response = self.post(explanation_payload(maxRetry=0))

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "FALLBACK")

    @patch("app.explanation._ollama_request")
    def test_transport_exception_releases_inference_lock(self, post):
        post.side_effect = [
            TimeoutError("slow"),
            {"response": "기간은 8개월입니다."},
        ]

        first = self.post(explanation_payload(maxRetry=0)).json()
        second = self.post(explanation_payload(maxRetry=0)).json()

        self.assertEqual(first["status"], "FALLBACK")
        self.assertEqual(second["status"], "READY")

    def test_slow_trickle_response_obeys_total_wall_deadline(self):
        response_body = json.dumps(
            {"response": "기간은 8개월입니다."}, ensure_ascii=False
        ).encode()

        class SlowHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response_body)))
                self.end_headers()
                try:
                    for byte in response_body:
                        self.wfile.write(bytes([byte]))
                        self.wfile.flush()
                        time.sleep(0.01)
                except BrokenPipeError:
                    pass

            def log_message(self, _format, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowHandler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01))
        thread.start()
        started = time.monotonic()
        try:
            with (
                patch.dict("os.environ", {"OLLAMA_URL": f"http://127.0.0.1:{server.server_port}"}),
                patch("app.explanation.TOTAL_TIMEOUT_SECONDS", 0.15),
            ):
                body = self.post(explanation_payload(maxRetry=0)).json()
                elapsed = time.monotonic() - started
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        self.assertEqual(body["status"], "FALLBACK")
        self.assertLess(elapsed, 0.4)

    def test_trickle_then_stall_cannot_restart_socket_timeout(self):
        response_body = b'{"response":"8"}'

        class StallHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response_body)))
                self.end_headers()
                try:
                    for byte in response_body[:5]:
                        self.wfile.write(bytes([byte]))
                        self.wfile.flush()
                        time.sleep(0.02)
                    time.sleep(0.5)
                except OSError:
                    pass

            def log_message(self, _format, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), StallHandler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01))
        thread.start()
        started = time.monotonic()
        try:
            with (
                patch.dict("os.environ", {"OLLAMA_URL": f"http://127.0.0.1:{server.server_port}"}),
                patch("app.explanation.TOTAL_TIMEOUT_SECONDS", 0.15),
            ):
                body = self.post(explanation_payload(maxRetry=0)).json()
                elapsed = time.monotonic() - started
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        self.assertEqual(body["status"], "FALLBACK")
        self.assertLess(elapsed, 0.23)

    def test_header_trickle_obeys_deadline_and_leaves_no_request_running(self):
        response_body = b'{"response":"8"}'

        class HeaderHandler(BaseHTTPRequestHandler):
            request_count = 0
            count_lock = threading.Lock()

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                with self.count_lock:
                    self.__class__.request_count += 1
                    request_number = self.request_count
                try:
                    self.connection.sendall(b"HTTP/1.1 200 OK\r\n")
                    if request_number == 1:
                        for byte in b"X-Slow-Header: trickle\r\n":
                            self.connection.sendall(bytes([byte]))
                            time.sleep(0.01)
                    headers = (
                        b"Content-Type: application/json\r\n"
                        + f"Content-Length: {len(response_body)}\r\n".encode()
                        + b"Connection: close\r\n\r\n"
                    )
                    self.connection.sendall(headers + response_body)
                except OSError:
                    pass

            def log_message(self, _format, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), HeaderHandler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01))
        thread.start()
        try:
            with (
                patch.dict("os.environ", {"OLLAMA_URL": f"http://127.0.0.1:{server.server_port}"}),
                patch("app.explanation.TOTAL_TIMEOUT_SECONDS", 0.15),
            ):
                started = time.monotonic()
                first = self.post(explanation_payload(maxRetry=0)).json()
                elapsed = time.monotonic() - started
                second = self.post(explanation_payload(maxRetry=0)).json()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        self.assertEqual(first["status"], "FALLBACK")
        self.assertLess(elapsed, 0.2)
        self.assertEqual(second["status"], "READY")

    def test_actual_transport_rejects_http_error_malformed_and_oversized_body(self):
        oversized = b"x" * 262_145
        responses = (
            b"HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            (
                b"HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n"
                b"not-json"
            ),
            (
                b"HTTP/1.1 200 OK\r\n"
                + f"Content-Length: {len(oversized)}\r\n".encode()
                + b"Connection: close\r\n\r\n"
                + oversized
            ),
        )
        for raw_response in responses:
            with self.subTest(prefix=raw_response[:30]):
                with raw_http_server(raw_response) as url, patch.dict(
                    "os.environ", {"OLLAMA_URL": url}
                ):
                    body = self.post(explanation_payload(maxRetry=0)).json()

                self.assertEqual(body["status"], "FALLBACK")

    def test_connection_refused_and_invalid_urls_fall_back(self):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            refused_url = f"http://127.0.0.1:{reserved.getsockname()[1]}"
        for url in (refused_url, "ftp://ollama:11434", "http://user@ollama:11434", "http://x/a"):
            with self.subTest(url=url), patch.dict("os.environ", {"OLLAMA_URL": url}):
                body = self.post(explanation_payload(maxRetry=0)).json()

            self.assertEqual(body["status"], "FALLBACK")

    @patch("app.explanation._ollama_request")
    def test_empty_allowed_numbers_accepts_number_free_text_and_bool_null_input(self, post):
        post.return_value = {"response": "계획을 차분히 이어가세요."}
        payload = explanation_payload(
            allowedNumbers=[],
            plan={
                "recommendedMonthlySpending": 0,
                "currentAvgVariableSpending": 0,
                "remainingMonths": 1,
                "aggressiveWarning": True,
                "targetAmount": None,
            },
        )

        response = self.post(payload)

        self.assertEqual(response.json()["status"], "READY")

    @patch("app.explanation._ollama_request")
    def test_transport_http_malformed_and_empty_failures_return_fallback(self, post):
        failures = (
            TimeoutError("slow"),
            OSError("offline"),
            ValueError("not json"),
            {"response": None},
            {"response": "   "},
        )
        for failure in failures:
            with self.subTest(failure=failure):
                post.reset_mock(side_effect=True, return_value=True)
                if isinstance(failure, Exception):
                    post.side_effect = failure
                else:
                    post.return_value = failure

                body = self.post().json()

                self.assertEqual(body["status"], "FALLBACK")
                self.assertEqual(body["retryCount"], 0)
                self.assertEqual(body["failedNumbers"], [])
                self.assertIsNone(body["model"])

    @patch("app.explanation._ollama_request")
    def test_each_correction_uses_only_remaining_total_deadline(self, post):
        post.side_effect = [
            {"response": "잘못된 99원"},
            {"response": "올바른 8개월"},
        ]

        clock = [10.0, 10.0, 10.5, 11.0, 12.0, 12.5]
        with patch("app.explanation.monotonic", side_effect=clock):
            response = self.post()

        self.assertEqual(response.json()["status"], "READY")
        self.assertEqual(post.call_args_list[0].kwargs["deadline"], 25.0)
        self.assertEqual(post.call_args_list[1].kwargs["deadline"], 25.0)

    def test_lock_wait_timeout_returns_fallback_without_calling_ollama(self):
        class BusyLock:
            def acquire(self, timeout):
                self.timeout = timeout
                return False

            def release(self):
                raise AssertionError("unacquired lock must not be released")

        lock = BusyLock()
        with (
            patch("app.explanation._inference_lock", lock),
            patch("app.explanation._ollama_request") as post,
        ):
            body = self.post().json()

        self.assertEqual(body["status"], "FALLBACK")
        self.assertEqual(body["retryCount"], 0)
        self.assertLessEqual(lock.timeout, 15)
        post.assert_not_called()

    def test_concurrent_requests_never_overlap_ollama_inference(self):
        active = 0
        peak = 0
        state_lock = threading.Lock()

        def fake_post(**_kwargs):
            nonlocal active, peak
            with state_lock:
                active += 1
                peak = max(peak, active)
            threading.Event().wait(0.03)
            with state_lock:
                active -= 1
            return {"response": "기간은 8개월입니다."}

        with patch("app.explanation._ollama_request", side_effect=fake_post):
            with ThreadPoolExecutor(max_workers=2) as pool:
                responses = list(pool.map(lambda _index: self.post(), range(2)))

        self.assertEqual([r.json()["status"] for r in responses], ["READY", "READY"])
        self.assertEqual(peak, 1)


if __name__ == "__main__":
    unittest.main()
